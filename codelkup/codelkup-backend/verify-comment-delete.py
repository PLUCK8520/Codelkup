#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
评论删除语义验收（附着型：挂在**已运行**的后端上，不自己起 jar）。

为什么单独一个脚本：`verify-sprint3.py` 覆盖了"删评论的权限与计数"，
但**没有**覆盖"删根评论时它下面那些回复的去向" —— 而前端 `CommentSection.onDeleted`
恰好踩在这条语义上：

    服务端只逻辑删除根评论那一条、计数只 -1；其下的回复行仍然存在，
    `listByPost` 会把它们**提升为一级评论**返回（后端有意为之：不因为有人删了自己的
    评论就静默吞掉别人的回复）。

前端曾经按"整棵子树一起消失"处理（本地扣 1 + N 条），于是出现两个可见问题：
计数被多扣、刷新之后那几条回复"又回来了"。2026-09-29 的前端修复改成了
"根评论只扣 1 → 重新拉取"，本脚本就是把这条后端语义钉死：
**以后谁改了后端行为，前端那处处理会立刻被这里发现。**

覆盖：
  [D1] 作者删自己的回复 → 成功，且帖子 commentCount 只 -1
  [D2] 删根评论 → 成功后 commentCount 只 -1（**不级联扣掉它下面的回复**）
  [D3] 被删根评论下的回复**被提升为一级评论**（parentId 归一化为 0，内容不消失）
  [D4] 非作者删他人内容被拒（40302）

用法：
    python verify-comment-delete.py
    python verify-comment-delete.py --base http://127.0.0.1:8085

退出码：0 = 全通过，1 = 有失败
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8085"

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


# 绕开系统代理：本机开发常配了代理，走代理会打不到 127.0.0.1
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method: str, path: str, body: dict | None = None, token: str | None = None):
    """返回 (http_status, 解析后的 body)。业务失败同样是 HTTP 200 + code。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def register(tag: str) -> tuple[str, int]:
    """注册一个临时账号，返回 (token, userId)。邮箱带时间戳避免撞库。"""
    email = f"cdel-{tag}-{int(time.time() * 1000)}@example.com"
    st, b = call(
        "POST",
        "/api/auth/register",
        {"email": email, "nickname": f"删测{tag}", "password": "Test123456"},
    )
    if b.get("code") != 0:
        print(f"  [STOP] 注册失败（限流？先清桶再跑）：{b}")
        sys.exit(1)
    return b["data"]["accessToken"], b["data"]["user"]["id"]


def main() -> None:
    print("=== 评论删除语义（附着型）===")

    # ---------------- 准备 ----------------
    st, b = call("GET", "/api/boards")
    board_id = (b.get("data") or [{}])[0].get("id")
    check("能取到板块（后端在跑）", bool(board_id), b)

    token_a, uid_a = register("a")
    token_b, uid_b = register("b")
    check("两个临时账号注册成功", bool(token_a and token_b))

    st, b = call(
        "POST",
        "/api/posts",
        {
            "title": "评论删除语义验收帖",
            "content": "这条帖子用于验证删根评论时其回复的去向，跑完会自行删除。",
            "boardId": board_id,
        },
        token=token_a,
    )
    pid = b.get("data", {}).get("id")
    check("发帖成功", bool(pid), b)
    if not pid:
        sys.exit(1)

    def comment_count() -> int:
        _, d = call("GET", f"/api/posts/{pid}")
        return d.get("data", {}).get("commentCount")

    # ---------------- 建树：根评论(B) + 回复(A) ----------------
    st, b = call("POST", f"/api/posts/{pid}/comments", {"content": "根评论：疑问在这里。"}, token=token_b)
    root = b.get("data", {}).get("id")
    check("发根评论成功", bool(root), b)
    check("计数 = 1", comment_count() == 1, comment_count())

    st, b = call(
        "POST",
        f"/api/posts/{pid}/comments",
        {"content": "回复一：作者的补充说明。", "parentId": root, "replyUserId": uid_b},
        token=token_a,
    )
    reply_a = b.get("data", {}).get("id")
    check("发表回复成功", bool(reply_a), b)
    check("计数 = 2", comment_count() == 2, comment_count())

    # ---------------- D4：非作者删他人回复 ----------------
    st, b = call("DELETE", f"/api/comments/{reply_a}", token=token_b)
    check("D4 非作者删除他人回复被拒 (40302)", b.get("code") == 40302, b.get("code"))
    check("D4b 被拒后计数不变（仍 2）", comment_count() == 2, comment_count())

    # ---------------- D1：作者删自己的回复 ----------------
    st, b = call("DELETE", f"/api/comments/{reply_a}", token=token_a)
    check("D1 作者删自己的回复成功", b.get("code") == 0, b.get("code"))
    check("D1b 删回复后计数 = 1（只减这一条）", comment_count() == 1, comment_count())

    # ---------------- 再挂一条回复，供 D2/D3 使用 ----------------
    st, b = call(
        "POST",
        f"/api/posts/{pid}/comments",
        {"content": "回复二：留着验证根评论删除后的去向。", "parentId": root, "replyUserId": uid_b},
        token=token_a,
    )
    reply_a2 = b.get("data", {}).get("id")
    check("再次发表回复成功（计数 = 2）", bool(reply_a2) and comment_count() == 2, comment_count())

    # ---------------- D2 + D3：删根评论 ----------------
    st, b = call("DELETE", f"/api/comments/{root}", token=token_b)
    check("D2 作者删自己的根评论成功", b.get("code") == 0, b.get("code"))
    check(
        "D2b 计数只 -1（**不级联**扣掉下面的回复）",
        comment_count() == 1,
        f"实际 {comment_count()}（若为 0 说明后端改成了级联删除，前端那处处理需同步调整）",
    )

    st, b = call("GET", f"/api/posts/{pid}/comments")
    lst = b.get("data") or []
    promoted = next((c for c in lst if c.get("id") == reply_a2), None)
    check("D3 原回复仍可见（内容没有被静默吞掉）", promoted is not None, [c.get("id") for c in lst])
    check(
        "D3b 已被提升为一级评论（parentId 归一化为 0）",
        promoted is not None and promoted.get("parentId") == 0,
        promoted.get("parentId") if promoted else None,
    )

    # ---------------- 清理现场 ----------------
    st, b = call("DELETE", f"/api/comments/{reply_a2}", token=token_a)
    check("清理：删掉提升后的回复", b.get("code") == 0, b.get("code"))
    st, b = call("DELETE", f"/api/posts/{pid}", token=token_a)
    check("清理：删掉测试帖", b.get("code") == 0, b.get("code"))

    print("=" * 58)
    print(f"评论删除语义 | 用例总数 {PASS + FAIL}  |  PASS={PASS}  FAIL={FAIL}")
    print("=" * 58)
    if FAIL:
        print("失败用例：\n  - " + "\n  - ".join(FAILURES))
        sys.exit(1)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE, help="后端地址，默认 http://127.0.0.1:8085")
    args = ap.parse_args()
    BASE = args.base.rstrip("/")
    main()
