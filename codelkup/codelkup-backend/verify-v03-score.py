#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 M2 验收 —— 热度分（t_post.score）是否真的在起作用。

对应 ALIGNMENT 的 S1 / S2：
  S1 热度排序真实生效（高互动帖排在低互动旧帖之前）
  S2 死字段清零（score 有写入路径，且与互动量正相关）

核心思路（构造一对"互相矛盾"的帖子）：
  先发 A（高互动：被赞 + 被收藏 + 被评论），后发 B（零互动）。
  然后比较两种排序：
     sort=latest → B 在前（B 更晚创建，按时间倒序）
     sort=hot    → A 在前（A 热度高）
  如果 hot 与 latest 给出**同一个顺序**，说明 hot 退化成了时间序 ——
  这正是 score 死字段时的表现，也是本脚本要钉住的不变量。

前提：后端需把热度分任务调快（默认 5 分钟一轮，脚本等不起）：
    java -jar target/codelkup-backend-0.0.1-SNAPSHOT.jar \
        --codelkup.score.initial-delay-ms=2000 --codelkup.score.interval-ms=5000

用法：
    python3 verify-v03-score.py
    python3 verify-v03-score.py --base http://127.0.0.1:8085 --timeout 60

退出码：0 = 全通过，1 = 有失败
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8085"
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379

PASS = 0
FAIL = 0
FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        FAILURES.append(name)
        print(f"  [FAIL] {name}" + (f"  -> {detail}" if detail else ""))


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method: str, path: str, body: dict | None = None,
         token: str | None = None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=30) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"raw": raw, "status": e.code}


# ==================== Redis（只用于清理限流桶，RESP over socket）====================

def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(
        f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def count_rate_buckets() -> int:
    """当前还剩几个注册限流桶（用于断言"确实清干净了"）"""
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    return len([line for line in resp.split("\r\n") if line.startswith("codelkup:")])


def cleanup_rate_buckets() -> int:
    """清掉注册限流桶：本脚本要注册 2 个用户，且需可重复执行（与 security 脚本同一约定）"""
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


# ==================== 用例 ====================

def register(nickname: str, tag: str) -> tuple[int, dict]:
    ts = int(time.time())
    email = f"{tag}_{ts}@test.com"
    body = call("POST", "/api/auth/register",
                {"email": email, "password": "abc12345", "nickname": nickname})
    if body.get("code") != 0:
        raise RuntimeError(f"注册失败：{body}")
    return body["data"]["user"]["id"], body["data"]


def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT
    parser = argparse.ArgumentParser(description="V0.3 热度分验收")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    parser.add_argument("--timeout", type=int, default=60,
                        help="等待热度分任务重算的最长秒数（任务间隔由后端配置决定）")
    args = parser.parse_args()
    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port

    print("=" * 58)
    print(f"V0.3 热度分验收 | base={BASE} | 等待上限={args.timeout}s")
    print("=" * 58)

    if call("GET", "/api/system/health").get("code") != 0:
        print("后端不可用")
        return 1

    print("\n[0] 预清理注册限流桶（保证脚本可重复执行）")
    try:
        print(f"  已清理 {cleanup_rate_buckets()} 个残留计数键")
    except OSError as e:
        print(f"  警告：连不上 Redis，跳过预清理（{e}）")

    print("\n[1] 造数据：先发高互动帖 A，再发零互动帖 B")
    author_id, author = register("热度作者", "score_author")
    liker_id, liker = register("热度互动者", "score_liker")
    token_a, token_b = author["accessToken"], liker["accessToken"]

    boards = call("GET", "/api/boards").get("data") or []
    if not boards:
        print("  拿不到板块，无法发帖")
        return 1
    board_id = boards[0]["id"]

    ts = int(time.time())

    # ⚠️ 顺序即是断言的前提（这个顺序曾被写反过）：
    #    A（高互动）先发 → 创建时间更早；B（零互动）后发 → 创建时间更晚。
    #    于是 latest 里 B 在前、hot 里 A 在前，两种排序必然不同。
    #    写反时 latest 与 hot 会"看起来一致"，而断言又恰好可能因为
    #    "同一秒内的并列顺序不稳定"而侥幸通过 —— 那是假绿，不是通过。
    hot = call("POST", "/api/posts", {
        "title": f"verify-v03-score 高互动帖 {ts}",
        "content": f"这个帖子用于验证热度分：它会被点赞、收藏、评论各一次。{ts}",
        "boardId": board_id, "type": 0, "tags": ["verify"],
    }, token=token_a)

    # ⚠️ 必须跨过一秒再发第二帖：t_post.create_time 是**秒精度**，而 latest 的
    #    ORDER BY（is_top DESC, create_time DESC）没有 id tiebreaker ——
    #    同一秒内的两条帖子顺序由索引决定（实测：id 升序，即旧帖在前）。
    #    这不是产品缺陷而是精度边界（人手打字不可能同秒发两帖），
    #    但会让"latest 里新帖在前"这条断言随机失败，所以固件必须保证时间戳不同。
    time.sleep(1.1)

    cold = call("POST", "/api/posts", {
        "title": f"verify-v03-score 零互动帖 {ts}",
        "content": f"这个帖子用于验证热度分：它没有任何互动，且创建时间更晚。{ts}",
        "boardId": board_id, "type": 0, "tags": ["verify"],
    }, token=token_a)
    check("两个探针帖创建成功",
          cold.get("code") == 0 and hot.get("code") == 0,
          f"cold={cold.get('code')} hot={hot.get('code')}")
    cold_id, hot_id = cold["data"]["id"], hot["data"]["id"]

    # A：点赞 + 收藏 + 评论各一次（评论由另一个用户发出，更贴近真实场景）
    call("POST", f"/api/posts/{hot_id}/like", token=token_b)
    call("POST", f"/api/posts/{hot_id}/collect", token=token_b)
    comment = call("POST", f"/api/posts/{hot_id}/comments",
                   {"content": "互动一下，让这条有热度。"}, token=token_b)
    check("高互动帖已获得 1 赞 1 藏 1 评",
          comment.get("code") == 0, f"comment={comment.get('code')}")

    print(f"\n[2] 等待热度分任务重算（最长 {args.timeout}s）")
    deadline = time.time() + args.timeout
    hot_score = cold_score = 0.0
    while time.time() < deadline:
        data = call("GET", f"/api/posts?sort=hot&authorId={author_id}&size=50").get("data") or {}
        rows = {r["id"]: r for r in (data.get("records") or [])}
        if hot_id in rows and cold_id in rows:
            hot_score = float(rows[hot_id].get("score") or 0)
            cold_score = float(rows[cold_id].get("score") or 0)
            if hot_score > 0 and cold_score > 0:
                break
        time.sleep(3)

    check("高互动帖 score > 0（死字段已被写入）", hot_score > 0, f"score={hot_score}")
    check("零互动帖 score ≈ 1（基础分，未被互动放大）",
          0.9 <= cold_score <= 1.1, f"score={cold_score}")
    check("互动放大生效：高互动帖分数至少是零互动帖的 3 倍",
          hot_score >= cold_score * 3, f"hot={hot_score} cold={cold_score}")

    print("\n[3] 排序语义：hot 必须与 latest 给出不同顺序")
    hot_rows = [r["id"] for r in
                ((call("GET", f"/api/posts?sort=hot&authorId={author_id}&size=50")
                  .get("data") or {}).get("records") or [])]
    latest_rows = [r["id"] for r in
                   ((call("GET", f"/api/posts?sort=latest&authorId={author_id}&size=50")
                     .get("data") or {}).get("records") or [])]
    check("hot：高互动帖排在零互动帖之前",
          hot_rows.index(hot_id) < hot_rows.index(cold_id), f"hot顺序={hot_rows}")
    check("latest：后发的零互动帖排在前面（按时间倒序）",
          latest_rows.index(cold_id) < latest_rows.index(hot_id), f"latest顺序={latest_rows}")
    check("两种排序结果确实不同（说明 hot 不再是时间序的伪装）",
          hot_rows[0] != latest_rows[0], f"hot首位={hot_rows[0]} latest首位={latest_rows[0]}")

    print("\n[4] 自清理")
    try:
        cleanup_rate_buckets()
        # ⚠️ 断言的是"清理后还剩几个"，不是"删掉了几个" —— 之前写成后者，
        #    明明清干净了也会因为"删了 1 个"而假失败（实测踩过）
        left = count_rate_buckets()
        check("注册限流桶已清空（不占用后续脚本的额度）", left == 0, f"剩余={left}")
    except OSError as e:
        check("自清理成功", False, str(e))

    print("\n" + "=" * 58)
    print(f"热度分验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print(f"（score：高互动={hot_score} 零互动={cold_score}）")
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())