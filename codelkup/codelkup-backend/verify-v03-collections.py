#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 M2 验收 —— 我的收藏（M2-8 后端语义）。

覆盖 TASKS 的验收列，并把它拆成可证伪的断言：
    收藏 2 帖 → 列表返回 2 条、按收藏时间倒序、含摘要与作者
    分页正确（size=1 时 pages=2，两页合起来正好是那 2 帖）
    取消收藏 → 该帖从列表消失（total 变成 1）
    未登录 → 40101（而不是空列表 —— 空列表会被前端误读为"没收藏过"）

为什么不断言"接口返回 200"：那说明不了任何事。真正要验的是
**列表里的内容与顺序对不对**、以及**取消后会不会残留**。

用法：
    python3 verify-v03-collections.py
    python3 verify-v03-collections.py --base http://127.0.0.1:8085

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
            return {"raw": raw, "status": e.code}


def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def cleanup_rate_buckets() -> int:
    """只清注册限流桶。

    ⚠️ 绝不碰 codelkup:auth:* —— 那里是 refresh token 白名单，
    清掉会让所有会话莫名失效（踩过一次，见 TASKS 执行备注）。
    """
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT
    parser = argparse.ArgumentParser(description="V0.3 我的收藏验收")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    args = parser.parse_args()
    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port

    print("=" * 58)
    print(f"V0.3 我的收藏验收 | base={BASE}")
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
    nickname = f"收藏测试{ts % 10000}"

    print("\n[1] 注册一次性账号 + 取一个板块")
    reg = call("POST", "/api/auth/register",
               {"email": f"collect_{ts}@test.com", "password": "abc12345", "nickname": nickname})
    check("注册成功", reg.get("code") == 0, reg.get("code"))
    if reg.get("code") != 0:
        print("  注册失败，后续无法继续")
        return 1
    access = reg["data"]["accessToken"]

    boards = call("GET", "/api/boards")
    check("板块列表可用", boards.get("code") == 0 and boards.get("data"), boards.get("code"))
    board = boards["data"][0]

    print("\n[2] 发 2 帖并依次收藏（A 先、B 后）")
    post_a = call("POST", "/api/posts",
                  {"title": f"收藏验收帖A {ts}", "content": "用于验收我的收藏的帖子 A，正文够十个字。",
                   "boardId": board["id"]}, token=access)
    post_b = call("POST", "/api/posts",
                  {"title": f"收藏验收帖B {ts}", "content": "用于验收我的收藏的帖子 B，正文够十个字。",
                   "boardId": board["id"]}, token=access)
    check("发帖 A 成功", post_a.get("code") == 0, post_a.get("code"))
    check("发帖 B 成功", post_b.get("code") == 0, post_b.get("code"))
    if post_a.get("code") != 0 or post_b.get("code") != 0:
        return 1
    id_a, id_b = post_a["data"]["id"], post_b["data"]["id"]

    ca = call("POST", f"/api/posts/{id_a}/collect", token=access)
    cb = call("POST", f"/api/posts/{id_b}/collect", token=access)
    check("收藏 A 成功", ca.get("code") == 0, ca.get("code"))
    check("收藏 B 成功", cb.get("code") == 0, cb.get("code"))

    print("\n[3] 列表：2 条、收藏时间倒序、含作者与摘要")
    res = call("GET", "/api/users/me/collections?page=1&size=20", token=access)
    check("接口可用", res.get("code") == 0, res.get("code"))
    data = res.get("data") or {}
    records = data.get("records") or []
    check("total = 2", data.get("total") == 2, data.get("total"))
    check("返回 2 条", len(records) == 2, len(records))
    if len(records) == 2:
        # 同一秒内收藏两条时，次级排序键 ua.id DESC 保证后收藏的排前面 —— 顺序可确定
        check("后收藏的 B 排在前（收藏时间倒序）",
              records[0].get("id") == id_b and records[1].get("id") == id_a,
              f"实际顺序={[r.get('id') for r in records]}")
        first = records[0]
        check("含作者昵称", first.get("authorNickname") == nickname, first.get("authorNickname"))
        check("含板块名", first.get("boardName") == board["name"], first.get("boardName"))
        check("含摘要", bool((first.get("summary") or "").strip()), repr(first.get("summary")))
        check("collected 标记为 true（卡片显示已收藏态）", first.get("collected") is True,
              first.get("collected"))

    print("\n[4] 分页：size=1 时两页合起来正好是这 2 帖")
    p1 = call("GET", "/api/users/me/collections?page=1&size=1", token=access).get("data") or {}
    p2 = call("GET", "/api/users/me/collections?page=2&size=1", token=access).get("data") or {}
    check("每页 1 条", len(p1.get("records") or []) == 1 and len(p2.get("records") or []) == 1)
    check("pages = 2", p1.get("pages") == 2, p1.get("pages"))
    ids = {r.get("id") for r in (p1.get("records") or []) + (p2.get("records") or [])}
    check("两页合起来 = {A, B}（无重复无遗漏）", ids == {id_a, id_b}, ids)

    print("\n[5] 参数校验与未登录")
    too_big = call("GET", "/api/users/me/collections?page=1&size=999", token=access)
    check("size 超上限被拒（40001）", too_big.get("code") == 40001, too_big.get("code"))

    anon = call("GET", "/api/users/me/collections")
    check("未登录返回 40101（不是空列表）", anon.get("code") == 40101, anon.get("code"))

    print("\n[6] 取消收藏 A → 列表里只剩 B")
    un = call("DELETE", f"/api/posts/{id_a}/collect", token=access)
    check("取消收藏成功", un.get("code") == 0, un.get("code"))
    after = call("GET", "/api/users/me/collections?page=1&size=20", token=access).get("data") or {}
    left = after.get("records") or []
    check("total = 1", after.get("total") == 1, after.get("total"))
    check("剩下的正是 B", len(left) == 1 and left[0].get("id") == id_b, [r.get("id") for r in left])

    print("\n[7] 自清理")
    try:
        cleanup_rate_buckets()
        check("注册限流桶已清空", True)
    except OSError as e:
        check("自清理成功", False, str(e))

    print("\n" + "=" * 58)
    print(f"我的收藏验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())