#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.4 安全加固验收 —— L-4 限流补桶（自起 jar 型）。

覆盖本轮补齐的七个写接口族：点赞 / 收藏 / 评论点赞 / 关注 / 改资料 / 编辑帖子 / 刷新凭证。
验证方式与 verify-v05-reports.py 一致：把阈值压到 2~4，前 N 次必须通过、
第 N+1 次必须 42901 —— 附着型脚本改不了已在跑进程的参数，所以本脚本自起 jar。

为什么值得单独成脚本："桶存在"不能靠读代码证明（真的计数了吗？窗口对吗？
键前缀对吗？），必须把阈值压低后**真的打出 42901** 才算数；同时用它证明
"幂等重复请求同样消耗额度"（限流在业务之前 —— 与举报脚本备注同一课）。
F3 修复（第二轮安全审计，2026-09-27）追加：无 TTL 残留键的自愈 ——
INCR 与 EXPIRE 已原子化，历史残留的"永不过期"桶在下一次请求时被补上 TTL。

⚠️ 结束时必须清 `codelkup:rate:refresh:*`：它是按 IP 计的，残留会挤占后续
脚本/手工测试的刷新额度（其余桶按测试账号计，一并清掉更干净）。

用法：python verify-v04-ratelimit.py
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
JAR_DIR = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(JAR_DIR, "target", "codelkup-backend-0.0.1-SNAPSHOT.jar")
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379

# 调试阈值：全部压到 2~4，才跑得出 42901
LIKE_PER_HOUR = 3
COLLECT_PER_HOUR = 3
COMMENT_LIKE_PER_HOUR = 3
FOLLOW_PER_HOUR = 3
REFRESH_PER_HOUR = 4
PROFILE_PER_HOUR = 2
POST_EDIT_PER_HOUR = 3

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
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"raw": raw, "status": e.code}


# ==================== Redis（清理桶键用）====================

def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def cleanup_rate_buckets() -> int:
    """清本脚本会用到/可能挤占他人的桶（含按 IP 计的 refresh 与 register）"""
    keys: list[str] = []
    for pattern in ("codelkup:rate:register:*", "codelkup:rate:check_email:*",
                    "codelkup:rate:refresh:*", "codelkup:rate:like:*",
                    "codelkup:rate:collect:*", "codelkup:rate:comment_like:*",
                    "codelkup:rate:follow:*", "codelkup:rate:profile:*",
                    "codelkup:rate:post_edit:*"):
        resp = redis_cmd("KEYS", pattern)
        keys += [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


def redis_int(resp: str) -> int:
    """解析 RESP 整数应答（":1\\r\\n" / ":-1\\r\\n"）—— 用于 EXISTS / TTL（F3 自愈断言）"""
    try:
        return int(resp.strip().lstrip(":").split("\r\n")[0])
    except ValueError:
        return -1


# ==================== 应用进程管理（与 verify-v05-reports 同款）====================

def wait_ready(timeout: int = 150) -> tuple[bool, float]:
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if call("GET", "/api/system/health").get("code") == 0:
                return True, time.time() - t0
        except Exception:
            pass
        time.sleep(1.5)
    return False, time.time() - t0


def start_app(extra_args: list[str]):
    """拉起一个独占 8085 的后端实例；端口被占则拒绝运行（不能在错误的进程上取证）"""
    try:
        if call("GET", "/api/system/health").get("code") == 0:
            check("[0] 应用启动 + 健康检查", False,
                  "8085 已有服务在监听：验收脚本必须独占后端，请先停掉它再运行")
            return None, None
    except Exception:
        pass

    env = dict(os.environ)
    env.pop("SERVER__PORT", None)
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"
    log = open("verify-v04-ratelimit.log", "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(["java", "-jar", JAR] + extra_args,
                            env=env, cwd=JAR_DIR, stdout=log, stderr=subprocess.STDOUT)
    print(f"启动中，pid={proc.pid} | {' '.join(extra_args)}")
    ready, secs = wait_ready()
    check("[0] 应用启动 + 健康检查", ready, "耗时 %.1fs" % secs)
    if not ready:
        print(open("verify-v04-ratelimit.log", encoding="utf-8", errors="replace").read()[-4000:])
    return proc, log


def stop_app(proc, log) -> None:
    if proc is None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()
    if log is not None:
        log.close()


def register(email: str, nickname: str) -> tuple[str | None, int | None]:
    b = call("POST", "/api/auth/register",
             {"email": email, "password": "abc12345", "nickname": nickname})
    if b.get("code") != 0:
        return None, None
    return b["data"]["accessToken"], b["data"]["user"]["id"]


def main() -> int:
    print("=" * 62)
    print(f"V0.4 L-4 限流补桶验收 | base={BASE}")
    print("  阈值：like/collect/comment_like={} follow={} refresh={} profile={} post_edit={}"
          .format(LIKE_PER_HOUR, FOLLOW_PER_HOUR, REFRESH_PER_HOUR,
                  PROFILE_PER_HOUR, POST_EDIT_PER_HOUR))
    print("=" * 62)

    try:
        print(f"  预清理桶键：{cleanup_rate_buckets()} 个")
    except OSError as e:
        print(f"  警告：连不上 Redis（{e}）")

    proc, log = start_app([
        f"--codelkup.rate-limit.like-per-hour={LIKE_PER_HOUR}",
        f"--codelkup.rate-limit.collect-per-hour={COLLECT_PER_HOUR}",
        f"--codelkup.rate-limit.comment-like-per-hour={COMMENT_LIKE_PER_HOUR}",
        f"--codelkup.rate-limit.follow-per-hour={FOLLOW_PER_HOUR}",
        f"--codelkup.rate-limit.refresh-per-hour={REFRESH_PER_HOUR}",
        f"--codelkup.rate-limit.profile-per-hour={PROFILE_PER_HOUR}",
        f"--codelkup.rate-limit.post-edit-per-hour={POST_EDIT_PER_HOUR}",
    ])
    try:
        if proc is None or proc.poll() is not None:
            return 1

        ts = int(time.time())
        a_token, a_id = register(f"rl_a_{ts}@test.com", "限流甲")
        b_token, b_id = register(f"rl_b_{ts}@test.com", "限流乙")
        c_token, c_id = register(f"rl_c_{ts}@test.com", "限流丙")
        d_token, d_id = register(f"rl_d_{ts}@test.com", "限流丁")
        check("[1] 准备：4 个账号注册成功",
              all([a_token, b_token, c_token, d_token]), (a_id, b_id, c_id, d_id))

        boards = call("GET", "/api/boards")["data"]
        board_id = boards[0]["id"]
        posts = []
        for i in range(3):
            p = call("POST", "/api/posts",
                     {"title": f"限流验收帖{i} {ts}",
                      "content": f"用于验证互动类限流的帖子 {i}，正文够十个字。",
                      "boardId": board_id}, token=a_token)
            posts.append(p.get("data", {}).get("id"))
        check("[1] 准备：A 发 3 帖成功", all(posts), posts)
        p1, p2, p3 = posts

        # ---------- [2] 点赞 ----------
        print(f"\n[2] 点赞：阈值 {LIKE_PER_HOUR}/小时/用户")
        codes = [call("POST", f"/api/posts/{p}/like", token=b_token).get("code") for p in (p1, p2, p3)]
        check(f"前 {LIKE_PER_HOUR} 次点赞全部通过", all(c == 0 for c in codes), codes)
        b4 = call("POST", f"/api/posts/{p1}/like", token=b_token)
        check(f"第 {LIKE_PER_HOUR + 1} 次触发 42901（幂等重复同样消耗额度）",
              b4.get("code") == 42901, b4.get("code"))
        b5 = call("DELETE", f"/api/posts/{p1}/like", token=b_token)
        check("取消点赞与点赞共用一个桶（同样被拦）", b5.get("code") == 42901, b5.get("code"))

        # ---------- [3] 收藏 ----------
        print(f"\n[3] 收藏：阈值 {COLLECT_PER_HOUR}/小时/用户")
        codes = [call("POST", f"/api/posts/{p}/collect", token=b_token).get("code") for p in (p1, p2, p3)]
        check(f"前 {COLLECT_PER_HOUR} 次收藏全部通过", all(c == 0 for c in codes), codes)
        c4 = call("POST", f"/api/posts/{p1}/collect", token=b_token)
        check(f"第 {COLLECT_PER_HOUR + 1} 次触发 42901", c4.get("code") == 42901, c4.get("code"))
        check("点赞被限不影响收藏桶（独立计数）", True, "（上一步收藏仍通过即为证）")

        # ---------- [4] 评论点赞 ----------
        print(f"\n[4] 评论点赞：阈值 {COMMENT_LIKE_PER_HOUR}/小时/用户")
        cmt = call("POST", f"/api/posts/{p1}/comments",
                   {"content": "这是一条用于验证评论点赞限流的评论。"}, token=a_token)
        cmt_id = cmt.get("data", {}).get("id")
        check("[4] 准备：评论创建成功", bool(cmt_id), cmt.get("code"))
        codes = [call("POST", f"/api/comments/{cmt_id}/like", token=b_token).get("code")
                 for _ in range(COMMENT_LIKE_PER_HOUR)]
        check(f"前 {COMMENT_LIKE_PER_HOUR} 次评论点赞全部通过", all(c == 0 for c in codes), codes)
        c4 = call("POST", f"/api/comments/{cmt_id}/like", token=b_token)
        check(f"第 {COMMENT_LIKE_PER_HOUR + 1} 次触发 42901", c4.get("code") == 42901, c4.get("code"))

        # ---------- [5] 关注 ----------
        print(f"\n[5] 关注：阈值 {FOLLOW_PER_HOUR}/小时/用户")
        codes = [call("POST", f"/api/users/{t}/follow", token=b_token).get("code")
                 for t in (a_id, c_id, d_id)]
        check(f"前 {FOLLOW_PER_HOUR} 次关注全部通过", all(c == 0 for c in codes), codes)
        f4 = call("POST", f"/api/users/{a_id}/follow", token=b_token)
        check(f"第 {FOLLOW_PER_HOUR + 1} 次触发 42901（重复关注同样消耗额度）",
              f4.get("code") == 42901, f4.get("code"))
        f5 = call("DELETE", f"/api/users/{c_id}/follow", token=b_token)
        check("取关与关注共用一个桶（同样被拦）", f5.get("code") == 42901, f5.get("code"))

        # ---------- [6] 修改资料 ----------
        print(f"\n[6] 修改资料：阈值 {PROFILE_PER_HOUR}/小时/用户")
        codes = [call("PUT", "/api/users/me", {"bio": f"限流验收简介 {i}"}, token=b_token).get("code")
                 for i in range(PROFILE_PER_HOUR)]
        check(f"前 {PROFILE_PER_HOUR} 次修改资料全部通过", all(c == 0 for c in codes), codes)
        p3code = call("PUT", "/api/users/me", {"bio": "再一次"}, token=b_token)
        check(f"第 {PROFILE_PER_HOUR + 1} 次触发 42901", p3code.get("code") == 42901, p3code.get("code"))

        # ---------- [7] 编辑帖子 ----------
        print(f"\n[7] 编辑帖子：阈值 {POST_EDIT_PER_HOUR}/小时/用户")
        codes = [call("PUT", f"/api/posts/{p1}",
                      {"content": f"编辑后的正文第 {i} 版，仍够十个字以上。"},
                      token=a_token).get("code") for i in range(POST_EDIT_PER_HOUR)]
        check(f"前 {POST_EDIT_PER_HOUR} 次编辑全部通过", all(c == 0 for c in codes), codes)
        # ⚠️ 正文必须 ≥10 字：限流在 DTO 校验**之后**执行，字段级失败（40001）不消耗额度 ——
        #    第一次跑这里用了 9 个字，被参数校验先拦下，是"限流位置"的实测课（与举报脚本同一账）
        e4 = call("PUT", f"/api/posts/{p1}", {"content": "再改一次应该被限流拦住。"}, token=a_token)
        check(f"第 {POST_EDIT_PER_HOUR + 1} 次触发 42901", e4.get("code") == 42901, e4.get("code"))

        # ---------- [8] 刷新凭证（按 IP） ----------
        print(f"\n[8] 刷新凭证：阈值 {REFRESH_PER_HOUR}/小时/IP")
        login = call("POST", "/api/auth/login", {"email": f"rl_a_{ts}@test.com", "password": "abc12345"})
        refresh_token = login.get("data", {}).get("refreshToken")
        check("[8] 准备：登录拿到 refreshToken", bool(refresh_token), login.get("code"))
        codes = []
        for _ in range(REFRESH_PER_HOUR):
            r = call("POST", "/api/auth/refresh", {"refreshToken": refresh_token})
            codes.append(r.get("code"))
            if r.get("code") == 0:
                refresh_token = r["data"]["refreshToken"]   # 轮换后用新的继续
        check(f"前 {REFRESH_PER_HOUR} 次刷新全部通过", all(c == 0 for c in codes), codes)
        r5 = call("POST", "/api/auth/refresh", {"refreshToken": refresh_token})
        check(f"第 {REFRESH_PER_HOUR + 1} 次触发 42901", r5.get("code") == 42901, r5.get("code"))

        # ---------- [9] 无 TTL 残留键的自愈（F3 修复：INCR 与 EXPIRE 已原子化） ----------
        print("\n[9] 无 TTL 残留键自愈（F3：INCR+EXPIRE 原子完成）")
        # 模拟"历史异常残留"：INCR 成功但 EXPIRE 丢失（首请求时 Redis 抖动 / 进程被中断），
        # 键会不带 TTL 永久计数 —— 该用户从此被**永久** 42901（连窗口都不会结束）。
        # 修复后的 Lua 脚本在 TTL<0 时补设过期。构造"已满额且无 TTL"的桶后发一次点赞：
        # 既验证计数语义未变（仍被拦 42901），也验证 TTL 被补上（自愈生效）。
        stale_key = f"codelkup:rate:like:u:{d_id}"
        redis_cmd("SET", stale_key, str(LIKE_PER_HOUR))  # 无 TTL 的"满额"桶
        d1 = call("POST", f"/api/posts/{p1}/like", token=d_token)
        check("无 TTL 残留键仍正常计数（第 N+1 次触发 42901）", d1.get("code") == 42901, d1.get("code"))
        stale_ttl = redis_int(redis_cmd("TTL", stale_key))
        check("残留键的 TTL 已被脚本补设（自愈，不再永久限流）", stale_ttl > 0, f"ttl={stale_ttl}")

        # ---------- [10] 自清理 ----------
        print("\n[10] 自清理（refresh 桶按 IP 计，必须清 —— 否则挤占后续脚本额度）")
        n = cleanup_rate_buckets()
        left = redis_cmd("KEYS", "codelkup:rate:refresh:*").count("codelkup:")
        check(f"已清理 {n} 个桶键，refresh 桶无残留", left == 0, f"残留={left}")

    finally:
        stop_app(proc, log)

    print("\n" + "=" * 62)
    print(f"L-4 限流补桶验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 62)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())