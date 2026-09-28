#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 M2 验收 —— 声望与等级（t_user.reputation / level）是否真的在结算。

对应 ALIGNMENT 的 S2（死字段清零）：这两个字段此前只在注册时写死 0 / 1。

本脚本按"事件序列"逐条断言（每一步都读一次公开主页接口，不看数据库内部值）：
    B 点赞 +2   →  取消点赞 -2  →  收藏 +3  →  取消收藏 -3
    B 评论 → A 采纳 +15   →  A 给自己的帖子点赞（不给分）→  重复采纳同一条（幂等，不重复给分）
最后（若本机有 mysql CLI）把声望改到阈值下方再触发一次加分，验证**等级会跟着升级**。

设计原则：**加分与减分必须对称** —— 只加不减就是刷分口子，
所以每个正向动作都配一个反向断言，而不是只测"采纳 +15"。

用法：
    python3 verify-v03-reputation.py
    python3 verify-v03-reputation.py --base http://127.0.0.1:8085

退出码：0 = 全通过，1 = 有失败
"""
from __future__ import annotations

import argparse
import io
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8085"
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379
MYSQL = r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
# dev 库口令**不入库**（本机开发凭据）：优先 DEV_DB_PASSWORD 环境变量，其次读仓库根 .env 的 DB_PASSWORD。
# 说明：验收脚本要直连本机 MySQL 做"接口值 ↔ 库真值"对账，口令不能写死在仓库里。
def _dev_pw() -> str:
    pw = os.environ.get("DEV_DB_PASSWORD")
    if pw:
        return pw
    env_path = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), ".env")
    try:
        with io.open(env_path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("DB_PASSWORD="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    print("✗ 未找到本机 dev 库口令：设 DEV_DB_PASSWORD 环境变量，或在仓库根 .env 里写 DB_PASSWORD=...")
    sys.exit(1)


DB_ARGS = ["-ucodelkup", "-p" + _dev_pw(), "codelkup", "-N", "-B"]

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


def call(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
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


# ==================== Redis（清理限流桶）====================

def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def count_rate_buckets() -> int:
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    return len([line for line in resp.split("\r\n") if line.startswith("codelkup:")])


def cleanup_rate_buckets() -> None:
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)


# ==================== 辅助 ====================

def register(nickname: str, tag: str) -> dict:
    ts = int(time.time())
    body = call("POST", "/api/auth/register",
                {"email": f"{tag}_{ts}@test.com", "password": "abc12345", "nickname": nickname})
    if body.get("code") != 0:
        raise RuntimeError(f"注册失败：{body}")
    return body["data"]


def profile(user_id: int) -> tuple[int, int]:
    """读公开主页（不接受登录态，纯游客视角）→ (声望, 等级)"""
    data = call("GET", f"/api/users/{user_id}").get("data") or {}
    return int(data.get("reputation") or 0), int(data.get("level") or 0)


def expect_reputation(user_id: int, expected: int, name: str) -> None:
    rep, _ = profile(user_id)
    check(name, rep == expected, f"期望声望={expected} 实际={rep}")


def mysql_available() -> bool:
    try:
        return subprocess.run([MYSQL, "--version"], capture_output=True, timeout=10).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def db(sql: str) -> None:
    subprocess.run([MYSQL, *DB_ARGS, "-e", sql], capture_output=True, timeout=30)


# ==================== 用例 ====================

def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT
    parser = argparse.ArgumentParser(description="V0.3 声望与等级验收")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    args = parser.parse_args()
    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port

    print("=" * 58)
    print(f"V0.3 声望/等级验收 | base={BASE}")
    print("=" * 58)

    if call("GET", "/api/system/health").get("code") != 0:
        print("后端不可用")
        return 1

    print("\n[0] 预清理注册限流桶")
    try:
        cleanup_rate_buckets()
        print("  已清理")
    except OSError as e:
        print(f"  警告：连不上 Redis（{e}）")

    print("\n[1] 造数据：作者 A + 互动者 B，A 发一个提问帖")
    a = register("声望作者", "rep_author")
    b = register("声望互动者", "rep_actor")
    a_id, b_id = a["user"]["id"], b["user"]["id"]
    token_a, token_b = a["accessToken"], b["accessToken"]
    boards = call("GET", "/api/boards").get("data") or []
    post = call("POST", "/api/posts", {
        "title": f"verify-v03-reputation 提问帖 {int(time.time())}",
        "content": "这个提问帖用于验证声望结算：被点赞、被收藏、回答被采纳各应加多少分。",
        "boardId": boards[0]["id"], "type": 1, "tags": ["verify"],
    }, token=token_a)
    check("提问帖创建成功（type=1，可采纳）", post.get("code") == 0, post.get("code"))
    post_id = post["data"]["id"]

    rep, level = profile(a_id)
    check("初始状态：声望 0 / 等级 1", rep == 0 and level == 1, f"rep={rep} level={level}")

    print("\n[2] 点赞 +2 / 取消点赞 -2（必须对称）")
    call("POST", f"/api/posts/{post_id}/like", token=token_b)
    expect_reputation(a_id, 2, "被点赞 → +2")
    call("DELETE", f"/api/posts/{post_id}/like", token=token_b)
    expect_reputation(a_id, 0, "取消点赞 → -2（回到 0）")

    print("\n[3] 收藏 +3 / 取消收藏 -3")
    call("POST", f"/api/posts/{post_id}/collect", token=token_b)
    expect_reputation(a_id, 3, "被收藏 → +3")
    call("DELETE", f"/api/posts/{post_id}/collect", token=token_b)
    expect_reputation(a_id, 0, "取消收藏 → -3（回到 0）")

    print("\n[4] 评论被点赞 +2 / 取消 -2")
    comment = call("POST", f"/api/posts/{post_id}/comments",
                   {"content": "我来试着回答这个问题。"}, token=token_b)
    check("B 发表评论成功", comment.get("code") == 0, comment.get("code"))
    comment_id = comment["data"]["id"]
    call("POST", f"/api/comments/{comment_id}/like", token=token_a)
    expect_reputation(b_id, 2, "评论被楼主点赞 → 评论作者 +2")
    call("DELETE", f"/api/comments/{comment_id}/like", token=token_a)
    expect_reputation(b_id, 0, "取消评论点赞 → 评论作者 -2")

    print("\n[5] 采纳 +15，且不给自己的回答加分 / 重复采纳不重复给分")
    accept = call("POST", f"/api/posts/{post_id}/accept/{comment_id}", token=token_a)
    check("采纳成功", accept.get("code") == 0, accept.get("code"))
    expect_reputation(b_id, 15, "回答被采纳 → +15")

    st_self = call("POST", f"/api/posts/{post_id}/like", token=token_a)
    check("自赞被后端拒绝（L-1 修复，40001）", st_self.get("code") == 40001, st_self.get("code"))
    expect_reputation(a_id, 0, "给自己的帖子点赞 → 不给分（防自刷）")

    call("POST", f"/api/posts/{post_id}/accept/{comment_id}", token=token_a)
    expect_reputation(b_id, 15, "重复采纳同一条 → 仍是 15（幂等，不重复给分）")

    print("\n[6] 等级随声望升级（端到端）")
    if not mysql_available():
        print("  [SKIP] 未找到 mysql CLI，跳过等级阈值端到端验证（阈值边界已由 ReputationServiceTest 覆盖）")
    else:
        # 把 B 的声望压到 49（Lv.1 的最大值），再由 A 取消点赞→重新点赞，触发一次 +2 → 51（Lv.2）
        db(f"UPDATE t_user SET reputation=49, level=1 WHERE id={b_id}")
        rep, level = profile(b_id)
        check("压到 49 后仍为等级 1", rep == 49 and level == 1, f"rep={rep} level={level}")
        call("POST", f"/api/comments/{comment_id}/like", token=token_a)
        rep, level = profile(b_id)
        check("跨过 50 阈值后自动升到等级 2", rep == 51 and level == 2, f"rep={rep} level={level}")

    print("\n[7] 自清理")
    try:
        cleanup_rate_buckets()
        left = count_rate_buckets()
        check("注册限流桶已清空", left == 0, f"剩余={left}")
    except OSError as e:
        check("自清理成功", False, str(e))

    print("\n" + "=" * 58)
    print(f"声望/等级验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())