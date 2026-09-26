#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 M2 验收 —— 提问闭环（M2-14 后端语义）。

覆盖 TASKS 的验收列，并把它拆成可证伪的断言：
    ?type=1&solved=false 只返回未采纳的提问帖（普通帖与已采纳帖都不在）
    ?type=1&solved=true  只返回已采纳的提问帖
    采纳后：该帖从"待解决"消失、在"已解决"出现
    ?solved=false 但没带 type → 当没传（普通帖照常返回）——"仅提问帖生效"
    主页采纳率与手算一致（2 提问 1 采纳 → 0.5），且没有提问的用户为 null
    （null 而不是 0：前端要显示「—」而不是误导性的 0%）

用法：
    python verify-v03-questions.py
    python verify-v03-questions.py --base http://127.0.0.1:8085

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
            return {"raw": raw[:300], "status": e.code}


def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def cleanup_rate_buckets() -> None:
    """只清注册限流桶（⚠️ 绝不碰 codelkup:auth:*）"""
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)


def register(email: str, nickname: str) -> dict:
    return call("POST", "/api/auth/register",
                {"email": email, "password": "abc12345", "nickname": nickname})


def ids_of(res: dict) -> set:
    return {r["id"] for r in ((res.get("data") or {}).get("records") or [])}


def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT
    parser = argparse.ArgumentParser(description="V0.3 提问闭环验收")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    args = parser.parse_args()
    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port

    print("=" * 58)
    print(f"V0.3 提问闭环验收 | base={BASE}")
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

    ts = int(time.time())
    print("\n[1] 两个账号：A 提问（作者）、B 回答（评论者）")
    a = register(f"qa_author_{ts}@test.com", f"提问者{ts % 10000}")
    b = register(f"qa_answer_{ts}@test.com", f"回答者{ts % 10000}")
    check("A 注册成功", a.get("code") == 0, a.get("code"))
    check("B 注册成功", b.get("code") == 0, b.get("code"))
    if a.get("code") != 0 or b.get("code") != 0:
        return 1
    token_a, token_b = a["data"]["accessToken"], b["data"]["accessToken"]
    uid_a = call("GET", "/api/users/me", token=token_a)["data"]["id"]

    boards = call("GET", "/api/boards")
    board_id = boards["data"][0]["id"]

    print("\n[2] A 发 2 个提问帖 + 1 个普通帖")
    q1 = call("POST", "/api/posts",
              {"title": f"提问帖Q1 {ts}", "content": "这是第一个提问帖的正文，够十个字了。",
               "boardId": board_id, "type": 1}, token=token_a)
    q2 = call("POST", "/api/posts",
              {"title": f"提问帖Q2 {ts}", "content": "这是第二个提问帖的正文，够十个字了。",
               "boardId": board_id, "type": 1}, token=token_a)
    normal = call("POST", "/api/posts",
                  {"title": f"普通讨论帖 {ts}", "content": "这是普通讨论帖的正文，够十个字了。",
                   "boardId": board_id, "type": 0}, token=token_a)
    check("提问帖 Q1/Q2 创建成功", q1.get("code") == 0 and q2.get("code") == 0,
          (q1.get("code"), q2.get("code")))
    check("普通帖创建成功", normal.get("code") == 0, normal.get("code"))
    if q1.get("code") != 0 or q2.get("code") != 0 or normal.get("code") != 0:
        return 1
    q1_id, q2_id, normal_id = q1["data"]["id"], q2["data"]["id"], normal["data"]["id"]

    print("\n[3] 待解决筛选：只能看见未采纳的提问帖")
    waiting = call("GET", f"/api/posts?type=1&solved=false&authorId={uid_a}&page=1&size=20")
    check("接口可用", waiting.get("code") == 0, waiting.get("code"))
    check("待解决 = {Q1, Q2}", ids_of(waiting) == {q1_id, q2_id}, ids_of(waiting))
    check("普通帖不在待解决里（solved 只作用提问帖）",
          normal_id not in ids_of(waiting), ids_of(waiting))
    check("total 也只算提问帖", (waiting.get("data") or {}).get("total") == 2,
          (waiting.get("data") or {}).get("total"))

    solved_empty = call("GET", f"/api/posts?type=1&solved=true&authorId={uid_a}&page=1&size=20")
    check("此时「已解决」为空", ids_of(solved_empty) == set() and
          (solved_empty.get("data") or {}).get("total") == 0,
          (ids_of(solved_empty), (solved_empty.get("data") or {}).get("total")))

    no_type = call("GET", f"/api/posts?solved=false&authorId={uid_a}&page=1&size=20")
    check("不带 type 时 solved 被忽略（普通帖照常返回）",
          ids_of(no_type) == {q1_id, q2_id, normal_id}, ids_of(no_type))

    print("\n[4] B 回答 Q1，A 采纳")
    comment = call("POST", f"/api/posts/{q1_id}/comments",
                   {"content": "试试把线程池换成虚拟线程池，问题应该就没了。"}, token=token_b)
    check("B 评论成功", comment.get("code") == 0, comment.get("code"))
    if comment.get("code") != 0:
        return 1
    accept = call("POST", f"/api/posts/{q1_id}/accept/{comment['data']['id']}", token=token_a)
    check("A 采纳成功", accept.get("code") == 0, accept.get("code"))

    print("\n[5] 采纳后：从「待解决」消失，出现在「已解决」")
    waiting2 = call("GET", f"/api/posts?type=1&solved=false&authorId={uid_a}&page=1&size=20")
    check("待解决只剩 Q2", ids_of(waiting2) == {q2_id}, ids_of(waiting2))
    solved2 = call("GET", f"/api/posts?type=1&solved=true&authorId={uid_a}&page=1&size=20")
    check("已解决正好是 Q1", ids_of(solved2) == {q1_id}, ids_of(solved2))

    print("\n[6] 主页采纳率与手算一致（2 提问 1 采纳 → 0.5）")
    profile = call("GET", f"/api/users/{uid_a}")
    data = profile.get("data") or {}
    check("askedCount = 2（普通帖不计入）", data.get("askedCount") == 2, data.get("askedCount"))
    check("acceptedCount = 1", data.get("acceptedCount") == 1, data.get("acceptedCount"))
    check("acceptRate = 0.5", data.get("acceptRate") == 0.5, data.get("acceptRate"))

    uid_b = call("GET", "/api/users/me", token=token_b)["data"]["id"]
    profile_b = call("GET", f"/api/users/{uid_b}").get("data") or {}
    check("没提问过的用户：askedCount=0", profile_b.get("askedCount") == 0, profile_b.get("askedCount"))
    check("没提问过的用户：acceptedCount=0", profile_b.get("acceptedCount") == 0, profile_b.get("acceptedCount"))
    check("没提问过的用户：acceptRate=null（不是 0）",
          profile_b.get("acceptRate") is None, profile_b.get("acceptRate"))

    print("\n[7] 自清理")
    try:
        cleanup_rate_buckets()
        check("注册限流桶已清空", True)
    except OSError as e:
        check("自清理成功", False, str(e))

    print("\n" + "=" * 58)
    print(f"提问闭环验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())