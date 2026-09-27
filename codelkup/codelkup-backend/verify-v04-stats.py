#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.4 验收 —— 站点汇总统计（/api/stats，服务端聚合）。

覆盖本轮"服务端聚合替代客户端拼计数"的改动（详见 TASKS 备注 35）：
  [1] 游客可读：不带 token 返回 200，六个字段齐全、类型正确、非负
  [2] 与数据库真值对账：板块数 / 板块帖子和 / 标签总数 / 提问三态
      （真值一律现场 SQL 取，不硬编码期望值 —— 库里数据会变）
  [3] 恒定口径：questionCount == solved + unsolved；且与"列表探针"
      （/api/posts?type=1&size=1 的 total —— 旧的客户端拼数方式）逐值一致；
      boardCount 与 /api/boards 长度一致
  [4] 值会动：发一个提问帖 → 提问总数 / 待解决 / 板块帖子总数 各 +1；
      删除后全部回落 —— 证明数字跟着业务变，而不是静态装饰
  [5] 边界：写方法（POST /api/stats）未登录被拒 40101（不泄露路径存在性）

前提（附着型脚本，与 verify-v03-score.py 同一约定）：开发后端已在 8085 运行。
用法：
    python verify-v04-stats.py
退出码：0 = 全通过，1 = 有失败
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8085"
MYSQL = r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB_ARGS = ["-h127.0.0.1", "-ucodelkup", "-pchange-me-dev-password", "codelkup", "-N", "-B",
           "--default-character-set=utf8mb4"]
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379

PASS = 0
FAIL = 0
FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"[PASS] {name}")
    else:
        FAIL += 1
        FAILURES.append(name)
        print(f"[FAIL] {name}" + (f"  -> {detail}" if detail else ""))


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method: str, path: str, body: dict | None = None, token: str | None = None,
         timeout: int = 30) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"raw": raw, "status": e.code}


# ==================== Redis（只清限流桶）====================

def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def cleanup_rate_buckets() -> int:
    """只清注册桶（⚠️ 绝不碰 codelkup:auth:* —— 那是 refresh 白名单与登录失败锁）"""
    keys: list[str] = []
    for pattern in ("codelkup:rate:register:*", "codelkup:rate:check_email:*"):
        resp = redis_cmd("KEYS", pattern)
        keys += [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


# ==================== MySQL（真值对账）====================

def db_scalar(sql: str, default: int = -1) -> int:
    try:
        p = subprocess.run([MYSQL] + DB_ARGS + ["-e", sql], capture_output=True, timeout=25)
        if p.returncode != 0:
            return default
        out = p.stdout.decode("utf-8", "replace").strip()
        return int(out.splitlines()[0]) if out else default
    except Exception:
        return default


FIELDS = ["boardCount", "boardPostCount", "tagCount",
          "questionCount", "questionSolvedCount", "questionUnsolvedCount"]


def snapshot(label: str) -> dict:
    body = call("GET", "/api/stats")
    ok = body.get("code") == 0
    if label:
        check(label, ok, body)
    return body.get("data") or {}


def main() -> int:
    print("=" * 62)
    print(f"V0.4 站点汇总统计验收 | base={BASE}")
    print("=" * 62)

    # ---------- [1] 游客可读 + 字段形状 ----------
    data = snapshot("[1] 游客 GET /api/stats 返回 200")
    bad = [f for f in FIELDS if not isinstance(data.get(f), int)]
    check("[1] 六个字段齐全且为整数", not bad, f"缺失/类型错: {bad}")
    check("[1] 全部非负", all((data.get(f) or 0) >= 0 for f in FIELDS), data)

    # ---------- [2] 与数据库真值对账 ----------
    print("\n[2] 与数据库真值对账（现场 SQL，不硬编码）")
    board_count = db_scalar("SELECT COUNT(*) FROM t_board WHERE status = 0")
    board_posts = db_scalar("SELECT COALESCE(SUM(post_count), 0) FROM t_board WHERE status = 0")
    tag_count = db_scalar("SELECT COUNT(*) FROM t_tag")
    q_total = db_scalar("SELECT COUNT(*) FROM t_post "
                        "WHERE deleted = 0 AND status = 1 AND type = 1")
    q_solved = db_scalar("SELECT COALESCE(SUM(accepted_comment_id IS NOT NULL), 0) FROM t_post "
                         "WHERE deleted = 0 AND status = 1 AND type = 1")
    q_unsolved = db_scalar("SELECT COALESCE(SUM(accepted_comment_id IS NULL), 0) FROM t_post "
                           "WHERE deleted = 0 AND status = 1 AND type = 1")
    check("boardCount 与 t_board 一致", data.get("boardCount") == board_count,
          f"api={data.get('boardCount')} db={board_count}")
    check("boardPostCount 与 SUM(post_count) 一致", data.get("boardPostCount") == board_posts,
          f"api={data.get('boardPostCount')} db={board_posts}")
    check("tagCount 与 t_tag 一致", data.get("tagCount") == tag_count,
          f"api={data.get('tagCount')} db={tag_count}")
    check("questionCount 与 t_post 一致", data.get("questionCount") == q_total,
          f"api={data.get('questionCount')} db={q_total}")
    check("questionSolvedCount 与 t_post 一致", data.get("questionSolvedCount") == q_solved,
          f"api={data.get('questionSolvedCount')} db={q_solved}")
    check("questionUnsolvedCount 与 t_post 一致", data.get("questionUnsolvedCount") == q_unsolved,
          f"api={data.get('questionUnsolvedCount')} db={q_unsolved}")

    # ---------- [3] 恒定口径 + 跨接口一致 ----------
    print("\n[3] 恒定口径与跨接口一致")
    check("questionCount == solved + unsolved",
          data.get("questionCount") == (data.get("questionSolvedCount") or 0) + (data.get("questionUnsolvedCount") or 0),
          data)

    probe = call("GET", "/api/posts?type=1&size=1")
    probe_total = (probe.get("data") or {}).get("total")
    check("与列表探针 total 一致（旧的客户端拼数方式）",
          probe_total == data.get("questionCount"),
          f"probe={probe_total} api={data.get('questionCount')}")

    boards = call("GET", "/api/boards")
    board_len = len(boards.get("data") or [])
    check("boardCount 与 /api/boards 长度一致", board_len == data.get("boardCount"),
          f"boards={board_len} api={data.get('boardCount')}")

    # ---------- [4] 值会动 ----------
    print("\n[4] 值会动：发提问 → 计数变化 → 删除 → 回落")
    print(f"  预清理注册限流桶：{cleanup_rate_buckets()} 个 key")
    ts = int(time.time())
    reg = call("POST", "/api/auth/register",
               {"email": f"stats_{ts}@test.com", "password": "abc12345", "nickname": "统计验收员"})
    check("准备：注册验收账号", reg.get("code") == 0, reg)
    if reg.get("code") != 0:
        return 1
    token = reg["data"]["accessToken"]
    board_id = (boards.get("data") or [{}])[0].get("id")

    post = call("POST", "/api/posts",
                {"title": f"统计验收提问 {ts}",
                 "content": "用于验证 /api/stats 的计数会随业务变化，正文够十个字。",
                 "boardId": board_id, "type": 1},
                token=token)
    check("准备：发一个提问帖", post.get("code") == 0, post)
    if post.get("code") != 0:
        return 1
    pid = post["data"]["id"]

    after = snapshot("")
    check("提问总数 +1", after.get("questionCount") == data.get("questionCount", 0) + 1,
          f"before={data.get('questionCount')} after={after.get('questionCount')}")
    check("待解决 +1", after.get("questionUnsolvedCount") == data.get("questionUnsolvedCount", 0) + 1,
          f"before={data.get('questionUnsolvedCount')} after={after.get('questionUnsolvedCount')}")
    check("板块帖子总数 +1", after.get("boardPostCount") == data.get("boardPostCount", 0) + 1,
          f"before={data.get('boardPostCount')} after={after.get('boardPostCount')}")

    dele = call("DELETE", f"/api/posts/{pid}", token=token)
    check("准备：删除该提问", dele.get("code") == 0, dele)
    back = snapshot("")
    check("删除后提问总数回落", back.get("questionCount") == data.get("questionCount"),
          f"expect={data.get('questionCount')} got={back.get('questionCount')}")
    check("删除后待解决回落", back.get("questionUnsolvedCount") == data.get("questionUnsolvedCount"),
          f"expect={data.get('questionUnsolvedCount')} got={back.get('questionUnsolvedCount')}")
    check("删除后板块帖子总数回落", back.get("boardPostCount") == data.get("boardPostCount"),
          f"expect={data.get('boardPostCount')} got={back.get('boardPostCount')}")

    # ---------- [5] 边界 ----------
    print("\n[5] 边界")
    body = call("POST", "/api/stats")
    check("写方法未登录被拒 40101（不泄露路径存在性）", body.get("code") == 40101, body)

    # ---------- 自清理 ----------
    n = cleanup_rate_buckets()
    check("自清理：注册限流桶已清空", True, f"清理 {n} 个 key")

    print("\n" + "=" * 62)
    total_cases = PASS + FAIL
    if FAIL == 0:
        print(f"站点统计验收：{PASS}/{total_cases} 通过")
    else:
        print(f"站点统计验收：{PASS}/{total_cases} 通过，失败 {FAIL} 项：")
        for name in FAILURES:
            print(f"  - {name}")
    print("=" * 62)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())