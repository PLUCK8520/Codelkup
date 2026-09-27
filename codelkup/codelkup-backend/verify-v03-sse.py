#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
V0.3 M3-1 验收 —— SSE 实时通道（通知未读数推送）。

覆盖 TASKS M3-1 的验收列，并拆成可证伪的断言：
    登录换 ticket → 建连 → 另一账号评论后 3 秒内收到 unread 事件
    ticket 一次性（二次使用被拒）+ Redis 里"消费即消失"
    未登录不能签票；缺 ticket / 假 ticket 连不上
    心跳（注释帧 `:ping`）周期性出现
    事件里的 count 与轮询接口 /unread-count 一致（两条路径同一份 COUNT）
    多标签：同一用户的每条连接都能收到；超出上限时服务端关掉一个旧连接
    标记已读 → 推送 count 下降（别的标签页角标也能跟着降）
    F2 修复（第二轮安全审计，2026-09-27）追加：改密后服务端**立即关闭**该用户全部
    SSE 连接 —— 连上之后不再校验凭证，不关流则被盗会话能继续白拿实时通知。

为什么要自起 jar：心跳与超时窗口默认是 25 秒 / 30 分钟，验收等不起，
必须用调试参数压到秒级（`--codelkup.sse.heartbeat-ms=2000`）——
与 verify-sprint2 同一做法：脚本自己拉起服务、跑完关闭。

为什么不用 requests：本项目验收脚本只用标准库（ALIGNMENT §6）。
SSE 客户端用 urllib 开长连接 + 后台线程逐行读，主线程按超时取"完整事件块"，
这样"事件何时到达"是可测的时间点，而不是"最后一起读出来"。

运行：
    python verify-v03-sse.py
退出码：0 = 全通过，1 = 有失败
"""
from __future__ import annotations

import json
import os
import queue
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8085"
JAR_DIR = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(JAR_DIR, "target", "codelkup-backend-0.0.1-SNAPSHOT.jar")
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
         timeout: int = 20) -> dict:
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


def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def redis_reply(*args: str) -> str:
    """取一条简单回复的载荷。

    RESP 协议里整数是 `:1`、批量串是 `$3\\r\\n198`、简单串是 `+OK` ——
    直接拿整段响应去比字符串会把前缀也算进去（第一版脚本就这么错过一次，
    断言写成 `== "1"` 却拿到 `":1"`）。
    """
    lines = redis_cmd(*args).split("\r\n")
    first = lines[0] if lines else ""
    if first.startswith(":"):
        return first[1:]
    if first.startswith("+"):
        return first[1:]
    if first.startswith("$"):
        return lines[1] if len(lines) > 1 and int(first[1:]) >= 0 else ""
    return first


def cleanup_rate_buckets() -> int:
    """只清注册限流桶（⚠️ 绝不碰 codelkup:auth:* —— 那是 refresh 白名单）。"""
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


class SseStream:
    """极简 SSE 客户端：后台线程把流解析成"事件块 / 注释行"，主线程按超时取。"""

    def __init__(self, url: str):
        req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
        self.resp = OPENER.open(req, timeout=120)
        self.content_type = (self.resp.headers.get("Content-Type") or "").lower()
        self.accel_buffering = self.resp.headers.get("X-Accel-Buffering")
        self.closed = False
        self._events: queue.Queue = queue.Queue()
        self._comments: queue.Queue = queue.Queue()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self) -> None:
        event_name, data_lines, first_at = None, [], None
        try:
            for raw in self.resp:
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if line == "":
                    if data_lines or event_name:
                        self._events.put((first_at or time.time(), event_name or "message",
                                          "\n".join(data_lines)))
                    event_name, data_lines, first_at = None, [], None
                elif line.startswith(":"):
                    self._comments.put((time.time(), line))
                elif line.startswith("event:"):
                    event_name = line[6:].strip()
                    first_at = first_at or time.time()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].strip())
                    first_at = first_at or time.time()
        except Exception:
            pass  # 收尾阶段主动 close 会走到这里，属于正常
        finally:
            self.closed = True

    def wait_event(self, name: str, timeout: float):
        """等一个指定名字的完整事件；返回 (到达时间, data 文本)，超时 (None, None)。"""
        deadline = time.time() + timeout
        while True:
            remain = deadline - time.time()
            if remain <= 0:
                return None, None
            try:
                at, ev, data = self._events.get(timeout=remain)
            except queue.Empty:
                return None, None
            if ev == name:
                return at, data

    def wait_comment(self, timeout: float):
        try:
            return self._comments.get(timeout=timeout)
        except queue.Empty:
            return None, None

    def close(self) -> None:
        try:
            self.resp.close()
        except Exception:
            pass


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


def issue_ticket(token: str) -> tuple[str, dict]:
    body = call("POST", "/api/auth/sse-ticket", token=token)
    return (body.get("data") or {}).get("ticket"), body


def main() -> int:
    log = None
    proc = None
    streams: list[SseStream] = []
    try:
        print("=" * 58)
        print("V0.3 M3-1 验收 | SSE 实时通道")
        print("=" * 58)

        # ---- 启动服务（心跳压到 2 秒，否则验收等不到） ----
        env = dict(os.environ)
        env.pop("SERVER__PORT", None)
        env["SERVER_PORT"] = "8085"
        env["SPRING_PROFILES_ACTIVE"] = "dev"
        log = open("verify-sse.log", "w", encoding="utf-8", errors="replace")
        proc = subprocess.Popen(
            ["java", "-jar", JAR, "--codelkup.sse.heartbeat-ms=2000"],
            env=env, cwd=JAR_DIR, stdout=log, stderr=subprocess.STDOUT)
        print(f"启动中，pid={proc.pid} ...")
        ready, secs = wait_ready()
        check("应用启动 + 健康检查", ready, "耗时 %.1fs" % secs)
        if not ready:
            print(open("verify-sse.log", encoding="utf-8", errors="replace").read()[-4000:])
            return 1

        print("\n[0] 预清理注册限流桶")
        try:
            n = cleanup_rate_buckets()
            print(f"  已清理 {n} 个键")
        except OSError as e:
            print(f"  警告：连不上 Redis（{e}）")

        ts = int(time.time())
        b = call("POST", "/api/auth/register",
                 {"email": f"sse_a_{ts}@test.com", "password": "abc12345", "nickname": "推送达人"})
        token_a, uid_a = b["data"]["accessToken"], b["data"]["user"]["id"]
        b = call("POST", "/api/auth/register",
                 {"email": f"sse_b_{ts}@test.com", "password": "abc12345", "nickname": "打扰者"})
        token_b, uid_b = b["data"]["accessToken"], b["data"]["user"]["id"]
        boards = call("GET", "/api/boards")
        board_id = boards["data"][0]["id"]
        post = call("POST", "/api/posts",
                    {"title": f"SSE 验收帖 {ts}", "content": "用于验证实时推送的帖子，正文够十个字。",
                     "boardId": board_id}, token=token_a)
        check("准备数据：发帖成功", post.get("code") == 0, post.get("code"))
        if post.get("code") != 0:
            return 1
        pid = post["data"]["id"]

        # ==================== S3.1 ticket 的签发与一次性 ====================
        print("\n[S3.1] 一次性 ticket")
        body = call("POST", "/api/auth/sse-ticket")
        check("S3.1 未登录不能签票 (40101)", body.get("code") == 40101, body.get("code"))

        ticket1, body = issue_ticket(token_a)
        check("S3.1 登录后签票成功且 ticket 非空",
              body.get("code") == 0 and isinstance(ticket1, str) and len(ticket1) >= 32,
              f"code={body.get('code')} len={len(ticket1 or '')}")
        check("S3.1 回执带有效期（60 秒）",
              (body.get("data") or {}).get("expiresInSeconds") == 60, body.get("data"))
        exists = redis_reply("EXISTS", f"codelkup:sse:ticket:{ticket1}")
        value = redis_reply("GET", f"codelkup:sse:ticket:{ticket1}")
        check("S3.1 ticket 落在 Redis 且绑定签发人 + 签发端 IP",
              exists == "1" and value.startswith(str(uid_a) + "|"),
              f"exists={exists} value={value}")

        # ==================== S3.1b ticket 绑定签发端 IP（informational 修复） ====================
        print("\n[S3.1b] ticket 绑定签发端 IP：泄漏后异地不可消费")
        OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def probe_stream_code(ticket: str, fake_ip: str | None = None) -> int | None:
            """ 访问流失端点并取回 JSON 错误码；若真的放行会挂成流 —— 超时即视为未拒绝 """
            req = urllib.request.Request(f"{BASE}/api/notifications/stream?ticket={ticket}")
            if fake_ip:
                # 本机直连 = 可信代理，XFF 会被采信为 remoteAddr（生产由 nginx 覆写保证不可伪造）
                req.add_header("X-Forwarded-For", fake_ip)
            try:
                with OPENER.open(req, timeout=5) as resp:
                    raw = resp.read(4096).decode("utf-8", "replace")
                    return json.loads(raw).get("code")
            except urllib.error.HTTPError as e:
                try:
                    return json.loads(e.read().decode("utf-8", "replace")).get("code")
                except Exception:
                    return None
            except Exception:
                return None

        ticket_ip, _ = issue_ticket(token_a)
        check("S3.1b ticket 签发成功", bool(ticket_ip), ticket_ip)
        spoof_code = probe_stream_code(ticket_ip, fake_ip="203.0.113.9")
        check("S3.1b 伪造异地 IP 消费被拒（40101）", spoof_code == 40101, spoof_code)
        retry_code = probe_stream_code(ticket_ip)
        check("S3.1b 票已按一次性消费（原 IP 重试同样 40101，不留可复用凭证）",
              retry_code == 40101, retry_code)

        # ==================== S3.2 建连 + 初始快照 ====================
        print("\n[S3.2] 建立连接与初始未读数")
        stream1 = SseStream(f"{BASE}/api/notifications/stream?ticket={ticket1}")
        streams.append(stream1)
        check("S3.2 响应是 text/event-stream",
              "text/event-stream" in stream1.content_type, stream1.content_type)
        check("S3.2 响应头带 X-Accel-Buffering=no（告诉代理别缓冲）",
              (stream1.accel_buffering or "").lower() == "no", stream1.accel_buffering)

        at, data = stream1.wait_event("unread", 5)
        snap = json.loads(data) if data else {}
        check("S3.2 连上立刻推一次当前未读数", data is not None and snap.get("count") == 0,
              f"data={data}")

        exists = redis_reply("EXISTS", f"codelkup:sse:ticket:{ticket1}")
        check("S3.2 ticket 消费即消失（GETDEL，Redis 里已无此键）", exists == "0", f"exists={exists}")

        body = call("GET", f"/api/notifications/stream?ticket={ticket1}", timeout=8)
        check("S3.2 同一张 ticket 二次使用被拒 (40101)", body.get("code") == 40101, body.get("code"))
        body = call("GET", "/api/notifications/stream")
        check("S3.2 不带 ticket 被拒 (40002 缺参)", body.get("code") == 40002, body.get("code"))
        body = call("GET", "/api/notifications/stream?ticket=deadbeefdeadbeef", timeout=8)
        check("S3.2 伪造 ticket 被拒 (40101)", body.get("code") == 40101, body.get("code"))

        # ==================== S3.3 心跳 ====================
        print("\n[S3.3] 心跳（注释帧）")
        got = []
        t_heart = time.time()
        while time.time() - t_heart < 9 and len(got) < 2:
            it = stream1.wait_comment(3)
            if it:
                got.append(it)
        check("S3.3 9 秒内至少收到 2 次心跳（间隔按 2s 配置）",
              len(got) >= 2, f"实际 {len(got)} 次：{[g[1] for g in got]}")
        check("S3.3 心跳是注释帧（: 开头，不会触发前端事件）",
              bool(got) and got[0][1].startswith(":"), got[0][1] if got else None)

        # ==================== S3.4 事件推送（3 秒内送达） ====================
        print("\n[S3.4] 另一账号评论 → 3 秒内收到 unread")
        t_sent = time.time()
        b = call("POST", f"/api/posts/{pid}/comments",
                 {"content": "这是一条触发 SSE 推送的评论"}, token=token_b)
        check("S3.4 评论成功", b.get("code") == 0, b.get("code"))
        at, data = stream1.wait_event("unread", 3)
        elapsed = (at - t_sent) if at else None
        check("S3.4 3 秒内收到 unread 事件", at is not None,
              f"elapsed={elapsed if elapsed is None else round(elapsed, 2)}s")
        count = json.loads(data).get("count") if data else None
        check("S3.4 事件载荷 count=1", count == 1, f"data={data}")
        poll = call("GET", "/api/notifications/unread-count", token=token_a)
        check("S3.4 推送值与轮询接口一致（同一份 COUNT）",
              poll.get("data") == count, f"推送={count} 轮询={poll.get('data')}")

        # ==================== S3.5 多标签：两条连接都收到 ====================
        print("\n[S3.5] 多标签连接")
        ticket2, body = issue_ticket(token_a)
        stream2 = SseStream(f"{BASE}/api/notifications/stream?ticket={ticket2}")
        streams.append(stream2)
        at, data = stream2.wait_event("unread", 5)
        check("S3.5 第二条连接也拿到当前未读数（count=1）",
              data is not None and json.loads(data).get("count") == 1, f"data={data}")

        t_sent = time.time()
        body = call("POST", f"/api/users/{uid_a}/follow", token=token_b)
        check("S3.5 B 关注 A 成功", body.get("code") == 0, body.get("code"))
        at1, data1 = stream1.wait_event("unread", 3)
        at2, data2 = stream2.wait_event("unread", 3)
        c1 = json.loads(data1).get("count") if data1 else None
        c2 = json.loads(data2).get("count") if data2 else None
        check("S3.5 两条连接都收到 count=2（多标签各自更新）",
              c1 == 2 and c2 == 2, f"stream1={c1} stream2={c2}")

        # ==================== S3.6 标记已读 → count 下降 ====================
        print("\n[S3.6] 标记已读的推送")
        t_read = time.time()
        body = call("POST", "/api/notifications/read-all", token=token_a)
        check("S3.6 全部标记已读成功", body.get("code") == 0 and body.get("data") == 2, body.get("data"))
        at, data = stream1.wait_event("unread", 3)
        check("S3.6 标记已读后推 count=0（别的标签页角标跟着清零）",
              data is not None and json.loads(data).get("count") == 0, f"data={data}")

        # ==================== S3.7 连接数上限 ====================
        print("\n[S3.7] 单用户连接上限（默认 5）")
        # 已有 2 条，再补 3 条到达上限，第 6 条应挤掉一个旧连接
        for _ in range(3):
            t, _b = issue_ticket(token_a)
            streams.append(SseStream(f"{BASE}/api/notifications/stream?ticket={t}"))
            time.sleep(0.2)
        time.sleep(1.0)
        t, _b = issue_ticket(token_a)
        stream_new = SseStream(f"{BASE}/api/notifications/stream?ticket={t}")
        streams.append(stream_new)
        at, data = stream_new.wait_event("unread", 5)
        check("S3.7 超出上限时新连接仍可用（收到初始未读数）", data is not None, f"data={data}")

        t_evict = time.time()
        evicted = False
        while time.time() - t_evict < 4 and not evicted:
            evicted = any(s.closed for s in streams[:-1])
            time.sleep(0.2)
        check("S3.7 有一个旧连接被服务端关闭（淘汰而不是拒绝新连接）",
              evicted, f"closed={[s.closed for s in streams]}")

        print("\n[S3.8] 改密 → 服务端立即关闭该用户全部 SSE 连接（F2 修复）")
        # 背景：SSE 只在握手时用一次性 ticket 确认身份，连上后不再过过滤器、不再看凭证。
        # "改密=踢下线"只做凭据撤销是不够的 —— 被盗会话手里的这条长连接会继续收通知，
        # 直到 30 分钟自然超时。修复：改密广播会话撤销事件，通知模块监听并关流。
        t_rev, _b = issue_ticket(token_a)
        stream_rev = SseStream(f"{BASE}/api/notifications/stream?ticket={t_rev}")
        streams.append(stream_rev)
        at, data = stream_rev.wait_event("unread", 5)
        check("S3.8 关流前连接可用（收到初始未读数）", data is not None, f"data={data}")

        # 第三轮审计补洞：closeAll 只能关"已存在"的连接 —— 若票在改密**之前**签发、
        # 改密**之后**才消费，被偷的会话能据此新建一条流。修复是让消费端比对用户级撤销水位。
        t_stale, _b = issue_ticket(token_a)
        check("S3.8 准备：改密前先签一张待消费的票", bool(t_stale), t_stale)
        # 签发时刻是秒精度：跨过一秒，保证"签发 < 撤销水位"（否则同秒边界会偶发不拒）
        time.sleep(1.1)

        chg = call("PUT", "/api/users/me/password",
                   {"oldPassword": "abc12345", "newPassword": "newpass456"}, token=token_a)
        check("S3.8 改密成功（触发会话撤销）", chg.get("code") == 0, chg.get("code"))

        t_wait = time.time()
        while time.time() - t_wait < 6 and not stream_rev.closed:
            time.sleep(0.2)
        check("S3.8 改密后该连接的流被服务端关闭（不再白拿通知）",
              stream_rev.closed, f"closed={stream_rev.closed}")
        time.sleep(0.5)
        check("S3.8 该用户其余旧连接一并关闭（多标签一起下线）",
              all(s.closed for s in streams if s is not stream_rev),
              f"closed={[s.closed for s in streams]}")

        stale_code = probe_stream_code(t_stale)
        check("S3.8 改密前签发的 ticket 在改密后消费被拒（40101，撤销水位生效）",
              stale_code == 40101, stale_code)

        print("\n[S3.9] 收尾：关闭全部连接（服务端由 onCompletion 摘除）")
        for s in streams:
            s.close()
        time.sleep(0.5)

    finally:
        for s in streams:
            s.close()
        if proc is not None:
            proc.terminate()
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.kill()
        if log is not None:
            log.close()

    total = PASS + FAIL
    print("\n" + "=" * 58)
    print(f"SSE 实时通道验收：{PASS}/{total} 通过")
    if FAILURES:
        print("失败项：")
        for name in FAILURES:
            print(f"  - {name}")
    print("=" * 58)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())