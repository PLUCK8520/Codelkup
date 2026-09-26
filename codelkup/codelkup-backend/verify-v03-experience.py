#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 M2 验收 —— 体验闭环**跨功能串联**（M2-16）。

<h2>与四个单点脚本的分工</h2>
收藏 / 上传 / 改密 / 提问闭环各自已有专属脚本（verify-v03-collections / upload /
password / questions），它们各自注册独立账号、只测自己那一块。本脚本<b>不重复</b>
那些单点断言，而是走一遍**同一个用户的真实旅程**，专测两件事：

  1. **串联是否还成立**：上传得到的 URL 写进 Markdown 正文后，详情接口返回的正文
     里能读到它、并且用这个 URL 真能取回图片（上传 ↔ 内容 的接缝）。
  2. **会话一致性**：改密后旧凭证必须失效（单点脚本已测），而**新凭证**要能继续
     读到"改密前发生的事"（收藏列表仍是 2 条、采纳率仍是 1.0）——
     这类"功能 A 改变了会话、功能 B 却读不到自己的数据"的缺陷，
     单点脚本永远发现不了。

用法：
    python verify-v03-experience.py
    python verify-v03-experience.py --base http://127.0.0.1:8085

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
import uuid

BASE = "http://127.0.0.1:8085"
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379

PASS = 0
FAIL = 0
FAILURES: list[str] = []

OLD_PWD = "abc12345"
NEW_PWD = "newpass456"


def check(name: str, ok: bool, detail: str = "") -> None:
    """detail 建议直接传响应体 —— 失败时要能一眼看出是哪个字段不对"""
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


def upload(path: str, filename: str, content: bytes, token: str | None = None) -> dict:
    """手工拼 multipart（纯标准库）。

    与 verify-v03-upload.py 里的同名函数是**有意重复**的：
    本目录的验收脚本一律自包含、可单独拷走运行，不为省十几行代码引入共享模块。
    """
    boundary = "----codelkupverify" + uuid.uuid4().hex
    body = (
        f"--{boundary}\r\n".encode()
        + f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode()
        + b"Content-Type: application/octet-stream\r\n\r\n"
        + content
        + f"\r\n--{boundary}--\r\n".encode()
    )
    req = urllib.request.Request(BASE + path, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=60) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"raw": raw[:300], "status": e.code}


def fetch_raw(path: str) -> tuple[int, bytes]:
    """取静态资源原始字节（校验上传的图片真的能访问，而不只是接口回了 url）"""
    try:
        with OPENER.open(BASE + path, timeout=30) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def cleanup_rate_buckets() -> None:
    """只清注册/上传桶。⚠️ 绝不碰 codelkup:auth:*（refresh 白名单在那里）"""
    for pattern in ("codelkup:rate:register:*", "codelkup:rate:upload:*"):
        resp = redis_cmd("KEYS", pattern)
        keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
        if keys:
            redis_cmd("DEL", *keys)


def png_bytes() -> bytes:
    return b"\x89PNG\r\n\x1a\n" + b"\x00" * 512


def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT
    parser = argparse.ArgumentParser(description="V0.3 体验闭环串联验收")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    args = parser.parse_args()
    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port

    print("=" * 58)
    print(f"V0.3 体验闭环串联验收 | base={BASE}")
    print("=" * 58)

    if call("GET", "/api/system/health").get("code") != 0:
        print("后端不可用")
        return 1

    print("\n[0] 预清理注册 / 上传限流桶")
    try:
        cleanup_rate_buckets()
        print("  已清理")
    except OSError as e:
        print(f"  警告：连不上 Redis（{e}）")

    ts = int(time.time())
    print("\n[1] 用户 A（主角）与 B（回答者）注册")
    a = call("POST", "/api/auth/register",
             {"email": f"exp_a_{ts}@test.com", "password": OLD_PWD, "nickname": f"体验A{ts % 10000}"})
    b = call("POST", "/api/auth/register",
             {"email": f"exp_b_{ts}@test.com", "password": OLD_PWD, "nickname": f"体验B{ts % 10000}"})
    check("A 注册成功", a.get("code") == 0, a)
    check("B 注册成功", b.get("code") == 0, b)
    if a.get("code") != 0 or b.get("code") != 0:
        return 1
    token_a, refresh_a = a["data"]["accessToken"], a["data"]["refreshToken"]
    token_b = b["data"]["accessToken"]
    uid_a = call("GET", "/api/users/me", token=token_a)["data"]["id"]
    board_id = (call("GET", "/api/boards").get("data") or [{}])[0]["id"]

    print("\n[2] 接缝一：上传的图片要能写进正文，并且真能访问")
    img = upload("/api/uploads/images", "journey.png", png_bytes(), token=token_a)
    check("上传成功", img.get("code") == 0, img)
    img_url = (img.get("data") or {}).get("url", "")
    check("拿到 URL", img_url.startswith("/uploads/posts/"), img_url)

    status, raw = fetch_raw(img_url) if img_url else (0, b"")
    check("该 URL 可直接访问（GET 200）", status == 200, status)
    check("取回的字节确实是 PNG（魔数一致）",
          raw[:4] == b"\x89PNG", raw[:4].hex())

    post = call("POST", "/api/posts", {
        "title": f"体验闭环提问帖 {ts}",
        "content": f"这是一个提问帖，正文里引用了一张已上传的图：\n\n![配图]({img_url})\n\n请问虚拟线程适合什么场景？",
        "boardId": board_id, "type": 1,
    }, token=token_a)
    check("发提问帖成功", post.get("code") == 0, post)
    q_id = post["data"]["id"]

    detail = call("GET", f"/api/posts/{q_id}")
    check("详情正文里保留了图片 Markdown（接缝没丢）",
          img_url in (detail.get("data") or {}).get("content", ""), (detail.get("data") or {}).get("content"))

    print("\n[3] 提问闭环：B 回答 → A 采纳 → 采纳率变 1.0")
    comment = call("POST", f"/api/posts/{q_id}/comments",
                   {"content": "IO 密集的场景收益最明显，CPU 密集型反而更慢。"}, token=token_b)
    check("B 回答成功", comment.get("code") == 0, comment)
    accept = call("POST", f"/api/posts/{q_id}/accept/{comment['data']['id']}", token=token_a)
    check("A 采纳成功", accept.get("code") == 0, accept)

    profile = call("GET", f"/api/users/{uid_a}").get("data") or {}
    check("采纳率 1/1 = 1.0", profile.get("acceptRate") == 1.0, profile)
    solved_list = call("GET", f"/api/posts?type=1&solved=true&authorId={uid_a}")
    waiting_list = call("GET", f"/api/posts?type=1&solved=false&authorId={uid_a}")
    check("该帖在「已解决」里",
          q_id in {r["id"] for r in ((solved_list.get("data") or {}).get("records") or [])}, solved_list)
    check("该帖不在「待解决」里",
          q_id not in {r["id"] for r in ((waiting_list.get("data") or {}).get("records") or [])}, waiting_list)

    print("\n[4] 收藏两帖（自问自答的那条 + 回复里提到的另一条）")
    other = call("POST", "/api/posts",
                 {"title": f"体验闭环另一帖 {ts}", "content": "另一篇用于收藏的帖子，正文够十个字。",
                  "boardId": board_id, "type": 0}, token=token_a)
    check("第二条帖子创建成功", other.get("code") == 0, other)
    other_id = other["data"]["id"]
    check("收藏提问帖成功", call("POST", f"/api/posts/{q_id}/collect", token=token_a).get("code") == 0)
    check("收藏另一帖成功", call("POST", f"/api/posts/{other_id}/collect", token=token_a).get("code") == 0)

    collections = call("GET", "/api/users/me/collections?page=1&size=20", token=token_a).get("data") or {}
    check("收藏列表 2 条", collections.get("total") == 2, collections)
    check("后收藏的排前面",
          [r["id"] for r in (collections.get("records") or [])] == [other_id, q_id], collections)

    print("\n[5] 改密：旧凭证失效（单点脚本已覆盖），新凭证要能读到改密前的数据")
    changed = call("PUT", "/api/users/me/password",
                   {"oldPassword": OLD_PWD, "newPassword": NEW_PWD}, token=token_a)
    check("改密成功", changed.get("code") == 0, changed)
    check("旧 accessToken 已被拉黑（40101）",
          call("GET", "/api/users/me", token=token_a).get("code") == 40101)
    check("旧 refreshToken 已失效（40102）",
          call("POST", "/api/auth/refresh", {"refreshToken": refresh_a}).get("code") == 40102)

    relogin = call("POST", "/api/auth/login", {"email": f"exp_a_{ts}@test.com", "password": NEW_PWD})
    check("新密码可登录", relogin.get("code") == 0, relogin)
    if relogin.get("code") != 0:
        return 1
    token_new = relogin["data"]["accessToken"]

    collections2 = call("GET", "/api/users/me/collections?page=1&size=20", token=token_new).get("data") or {}
    check("改密后仍能读到 2 条收藏（会话变了、数据没丢）",
          collections2.get("total") == 2, collections2)
    profile2 = call("GET", f"/api/users/{uid_a}").get("data") or {}
    check("改密后采纳率仍是 1.0", profile2.get("acceptRate") == 1.0, profile2)
    mine = call("GET", f"/api/posts?authorId={uid_a}&page=1&size=20", token=token_new).get("data") or {}
    check("改密后仍能看到自己的 2 篇帖子", mine.get("total") == 2, mine)
    check("改密后仍能上传图片（新会话的写权限正常）",
          upload("/api/uploads/images", "after-pwd.png", png_bytes(), token=token_new).get("code") == 0)

    print("\n[6] 自清理")
    try:
        cleanup_rate_buckets()
        check("限流桶已清空", True)
    except OSError as e:
        check("自清理成功", False, str(e))

    print("\n" + "=" * 58)
    print(f"体验闭环串联验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())