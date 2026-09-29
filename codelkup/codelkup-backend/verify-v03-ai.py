#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
V0.3 M3-5 / M3-6 验收 —— 相似问题提示（不调 LLM）+ RAG 问答。

<h2>两个阶段，两次拉起服务</h2>
    阶段 A（**不配 key**，真实默认部署形态）：
        M3-5 的全部断言 + RAG 的"未启用降级"断言
        （无 key → 仅检索结果 + 可读提示；且不消耗预算、不写缓存）
    阶段 B（**key + 本地 stub 端点**）：
        把 base-url 指向脚本自己起的 HTTP stub，冒充 DeepSeek 的 OpenAI 兼容端点。
        于是"检索 → prompt → 调用 → 引用映射 → 缓存 → 日志"整条链路都能真验，
        **不需要真的 API key 也不需要花钱**。

<h2>为什么值得这么绕</h2>
    只验"无 key 降级"等于只验了反面：真正会出问题的是正面链路 ——
    prompt 里有没有防注入声明、引用编号映射对不对、缓存有没有真的省下调用、
    日预算是不是只对"真调用"计数。这些用 stub 全都能钉死。

运行：
    python verify-v03-ai.py                       # 默认实现（codelkup.ai.impl=rest）
    python verify-v03-ai.py --ai-impl spring-ai   # 切到 Spring AI 实现（M3-11）：断言必须同样全过
退出码：0 = 全通过，1 = 有失败
"""
from __future__ import annotations

import hashlib
import json
import os
import io
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = "http://127.0.0.1:8085"
JAR_DIR = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(JAR_DIR, "target", "codelkup-backend-0.0.1-SNAPSHOT.jar")
# MySQL 客户端不在 PATH 里：换机器时用环境变量 MYSQL_CLIENT 覆盖即可
MYSQL = os.environ.get("MYSQL_CLIENT", r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe")
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


DB_ARGS = ["-h127.0.0.1", "-ucodelkup", "-p" + _dev_pw(), "codelkup", "-N", "-B"]
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379

# 调试阈值（默认值见 application.yml）：
#   相似提示压到 6 才验得到 42901；问答压到 4，日预算压到 2
SIMILAR_PER_HOUR = 6
ASK_PER_HOUR = 4
DAILY_BUDGET = 2
STUB_API_KEY = "stub-key-for-verification"

# M3-11：`--ai-impl spring-ai` 让三个阶段全部切到 Spring AI 实现（留空 = 用 yml 的默认值 rest）。
# 验收口径就是"换实现后这套断言仍然全过" —— 同一份配置、同一组期望，差的只有"谁发请求"。
# 若实现被换掉而某条协议断言开始失败（比如重试导致 stub 收到多次请求），这里会立刻暴露。
AI_IMPL = next((sys.argv[i + 1] for i, a in enumerate(sys.argv)
                if a == "--ai-impl" and i + 1 < len(sys.argv)), "")

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


def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def redis_reply(*args: str) -> str:
    """取一条简单回复的载荷（RESP：`:1` / `$3\\r\\n198` / `+OK`）"""
    lines = redis_cmd(*args).split("\r\n")
    first = lines[0] if lines else ""
    if first.startswith(":") or first.startswith("+"):
        return first[1:]
    if first.startswith("$"):
        return lines[1] if len(lines) > 1 and int(first[1:]) >= 0 else ""
    return first


def db_rows(sql: str) -> list[list[str]]:
    """直连 MySQL 取多行（tab 分隔）—— 期望值从真值来源取，不硬编码"""
    try:
        p = subprocess.run([MYSQL] + DB_ARGS + ["-e", sql], capture_output=True, timeout=25)
        if p.returncode != 0:
            return []
        out = p.stdout.decode("utf-8", "replace").strip()
        return [line.split("\t") for line in out.splitlines()] if out else []
    except Exception:
        return []


def cleanup_rate_buckets() -> int:
    """只清注册限流桶（⚠️ 绝不碰 codelkup:auth:* —— 那是 refresh 白名单）"""
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


def fingerprint(question: str) -> str:
    """与 Java 侧 AiAnswerService.fingerprint 同一套归一化（去首尾空白、折叠空白、转小写）"""
    normalized = re.sub(r"\s+", " ", question.strip()).lower()
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def cleanup_ai_keys() -> int:
    """只清 codelkup:ai:*（问题缓存 + 日预算计数）。

    ⚠️ 断言的口径是"本次运行"，而这两个键的 TTL 分别是 6 小时 / 2 天 ——
    不清理的话，第二次跑同一个脚本会撞上上次的缓存与预算。
    ⚠️ 绝不碰 codelkup:auth:*（那是 refresh 白名单）。
    """
    resp = redis_cmd("KEYS", "codelkup:ai:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


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


# ==================== 冒充 DeepSeek 的本地 stub ====================

class StubUpstream:
    """极简 OpenAI 兼容端点：记录收到的请求，按内容返回固定答案。"""

    def __init__(self):
        self.requests: list[dict] = []
        self.reply_content = "虚拟线程更适合 IO 密集场景 [1]。"
        self.summary_content = "- 虚拟线程在 IO 密集场景吞吐提升明显\n- 平台线程在 CPU 密集场景仍占优"
        # 供 P95 / 超时两节使用：按问题里的标记词模拟"慢上游"与"长到超时的上游"
        self.slow_marker = "慢上游"
        self.hang_marker = "超时上游"
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def _read_body(self) -> str:
                """读请求体。

                ⚠️ 必须处理 chunked：JDK HttpClient（Boot 默认选的底层实现）在
                请求体长度未知时用分块传输，光看 Content-Length 会读到**空字符串** ——
                表现为"断言 prompt 全红但答案是通的"，非常容易误判成业务 bug。
                （本脚本第一版就踩了：stub 收到的 body 一直是空的。）
                """
                if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
                    raw = b""
                    while True:
                        size_line = self.rfile.readline().strip()
                        size = int(size_line.split(b";")[0], 16)
                        if size == 0:
                            self.rfile.readline()
                            break
                        raw += self.rfile.read(size)
                        self.rfile.readline()
                    return raw.decode("utf-8", "replace")
                length = int(self.headers.get("Content-Length", 0))
                return self.rfile.read(length).decode("utf-8", "replace")

            def do_POST(self):  # noqa: N802
                body = self._read_body()
                outer.requests.append({
                    "path": self.path,
                    "auth": self.headers.get("Authorization"),
                    "body": body,
                })
                # 按请求内容分流应答：摘要请求（正文：）与"不带编号"的问答各自有固定答案，
                # 其余走默认的带 [1] 引用答案
                if "正文：" in body:
                    content = outer.summary_content
                elif "应该怎么做" in body:
                    content = "这段答案没有标注任何编号。"
                else:
                    content = outer.reply_content

                # 模拟上游延迟：3 秒（应能正常返回）与 12 秒（应被客户端 8 秒超时掐断）
                if outer.hang_marker in body:
                    time.sleep(12)
                elif outer.slow_marker in body:
                    time.sleep(3)
                payload = json.dumps({
                    "id": "chatcmpl-stub",
                    "model": "deepseek-chat",
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": content}}],
                    "usage": {"prompt_tokens": 80, "completion_tokens": 43, "total_tokens": 123},
                }, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):  # 静音
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def stop(self) -> None:
        self.server.shutdown()


# ==================== 应用进程管理 ====================

def start_app(extra_args: list[str], label: str):
    """拉起一个**独占**的后端实例。

    ⚠️ 先确认 8085 上没有别的服务：本脚本必须独占后端。
    踩过的坑（2026-09-26）：开发后端还在跑时启动脚本 → 我们自己的 jar
    绑定端口失败**直接退出**，而 wait_ready 仍然在**旧实例**上通过 ——
    于是全套断言跑在了"没有 key、默认阈值"的错误进程上，
    表现为莫名其妙的 NO_KEY、限流阈值失效、stub 收不到请求 ——
    比"直接报错"难查得多。宁可拒绝运行，也不能在错误的进程上取证。
    """
    try:
        if call("GET", "/api/system/health").get("code") == 0:
            check(f"[{label}] 应用启动 + 健康检查", False,
                  "8085 端口已有服务在监听：验收脚本必须独占后端，请先停掉它再运行")
            return None, None
    except Exception:
        pass  # 连不上 = 端口空闲 ✓

    env = dict(os.environ)
    env.pop("SERVER__PORT", None)
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"
    if AI_IMPL:
        # 实现切换走命令行参数（优先级高于 yml 默认值），三个阶段一致
        extra_args = list(extra_args) + [f"--codelkup.ai.impl={AI_IMPL}"]
    log = open(f"verify-ai-{label}.log", "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(["java", "-jar", JAR] + extra_args,
                            env=env, cwd=JAR_DIR, stdout=log, stderr=subprocess.STDOUT)
    print(f"启动中（{label}），pid={proc.pid} | {' '.join(extra_args)}")
    ready, secs = wait_ready()
    check(f"[{label}] 应用启动 + 健康检查", ready, "耗时 %.1fs" % secs)
    if not ready:
        print(open(f"verify-ai-{label}.log", encoding="utf-8", errors="replace").read()[-4000:])
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


def similar(title: str, token: str | None) -> dict:
    return call("POST", "/api/ai/similar-questions", {"title": title}, token=token)


def ask(question: str, token: str | None) -> dict:
    return call("POST", "/api/ai/ask", {"question": question}, token=token)


def summarize(post_id: int, token: str | None) -> dict:
    return call("POST", f"/api/ai/summarize/{post_id}", token=token)


def ids(body: dict) -> list:
    return [item.get("postId") for item in (body.get("data") or [])]


# ==================== 阶段 A：不配 key（真实默认形态） ====================

def phase_a() -> dict:
    print("\n" + "=" * 58)
    print("阶段 A：不配 key —— 相似提示全量 + RAG 未启用降级")
    print("=" * 58)
    proc, log = start_app([f"--codelkup.rate-limit.similar-per-hour={SIMILAR_PER_HOUR}"], "a")
    ctx: dict = {}
    try:
        if proc is None or proc.poll() is not None:
            return ctx
        print("\n[0] 预清理注册限流桶")
        try:
            print(f"  已清理 {cleanup_rate_buckets()} 个键")
        except OSError as e:
            print(f"  警告：连不上 Redis（{e}）")

        ts = int(time.time())
        b = call("POST", "/api/auth/register",
                 {"email": f"ai_{ts}@test.com", "password": "abc12345", "nickname": "AI验收员"})
        token_a, uid_a = b["data"]["accessToken"], b["data"]["user"]["id"]
        ctx["token"] = token_a
        ctx["uid"] = uid_a
        board_id = call("GET", "/api/boards")["data"][0]["id"]

        title_p1 = "实测对比：虚拟线程在 IO 密集场景的两种写法"
        title_p2 = "实测对比：另一件完全无关的事"
        title_p3 = "配额与限流的区别到底在哪"
        title_p4 = "Java virtual thread 实战记录"
        title_p5 = "实测：一个完全不同的主题"
        query = title_p1[:10]

        def make_post(title: str, content: str) -> int:
            r = call("POST", "/api/posts",
                     {"title": title, "content": content, "boardId": board_id}, token=token_a)
            return r["data"]["id"]

        p1 = make_post(title_p1, "这是第一篇的正文，内容用于相似问题验收，长度足够十个字符。")
        p2 = make_post(title_p2, "这是第二篇的正文，与查询主题无关，长度足够十个字符。")
        p3 = make_post(title_p3, f"正文里提到过 {query} 这件事，但标题完全无关，长度足够十个字符。")
        p4 = make_post(title_p4, "英文标题用的大小写与查询不同，用于验证大小写不敏感。")
        p5 = make_post(title_p5, "第五篇的正文，与前几篇共享很短的标题前缀。")
        ctx.update({"p1": p1, "title_p1": title_p1, "query": query})
        check("准备语料：5 篇帖子创建成功", all(x > 0 for x in (p1, p2, p3, p4, p5)),
              [p1, p2, p3, p4, p5])

        # ---------------- M3-5：相似问题提示 ----------------
        print(f"\n[1] 相似提示：用标题前 10 字提问（{query}）")
        b = similar(query, token_a)
        got = ids(b)
        check("查询成功且返回非空", b.get("code") == 0 and len(got) >= 1, b.get("code"))
        check("目标帖排在第一位", got and got[0] == p1, f"期望 #{p1}，实际 {got}")
        top = (b.get("data") or [{}])[0]
        check("条目字段可用（标题一致 / 有命中片段 / 有板块名）",
              top.get("title") == title_p1 and bool(top.get("snippet")) and bool(top.get("boardName")),
              json.dumps(top, ensure_ascii=False)[:200])
        check("条目不含正文（精简 VO，不复用列表 VO）",
              "content" not in json.dumps(top, ensure_ascii=False), list(top.keys()))

        print("\n[2] 相似提示：标题命中优先于正文命中")
        check("P2（共享短前缀）排在 P1 之后", got.index(p2) > got.index(p1) if p2 in got else True, got)
        check("P3（只有正文命中）若出现也排在 P1 之后",
              got.index(p3) > got.index(p1) if p3 in got else True, got)
        check("默认返回不超过 3 条", len(got) <= 3, len(got))

        print("\n[3] 相似提示：大小写不敏感")
        b = similar("java virtual thread", token_a)
        check("全小写查询也能把该帖排到第一位", ids(b) and ids(b)[0] == p4, ids(b))

        print("\n[4] 相似提示：边界输入")
        b = similar("实", token_a)
        check("不足 2 字 → code 0 且空列表", b.get("code") == 0 and (b.get("data") or []) == [], b)
        check("空标题 → 40001", similar("", token_a).get("code") == 40001)
        check("纯空白标题 → 40001", similar("   ", token_a).get("code") == 40001)
        check("游客 → 40101（需登录）", similar(query, None).get("code") == 40101)
        b = similar("zzz这个词库里肯定不存在zzz", token_a)
        check("检索无结果 → code 0 且空列表", b.get("code") == 0 and (b.get("data") or []) == [], b)

        print("\n[5] 相似提示：条数上限")
        b = similar("实测", token_a)
        got = ids(b)
        check("宽泛查询最多 3 条且互不重复", len(got) == 3 and len(set(got)) == 3, got)

        print(f"\n[6] 相似提示：限流（阈值 {SIMILAR_PER_HOUR}/小时/用户）")
        check(f"第 {SIMILAR_PER_HOUR} 次调用仍通过", similar(query, token_a).get("code") == 0)
        check("超出阈值 → 42901", similar(query, token_a).get("code") == 42901)
        check("Redis 里出现该用户的计数键",
              redis_reply("EXISTS", f"codelkup:rate:similar:u:{uid_a}") == "1")

        # ---------------- M3-6：未启用降级 ----------------
        print("\n[7] RAG：未配置 key 时的降级（仍返回检索结果 + 可读提示）")
        question = f"虚拟线程在 IO 密集场景该怎么选 {ts}？"
        b = ask(question, token_a)
        data = b.get("data") or {}
        check("code 0 且标记为降级（不是报错）", b.get("code") == 0 and data.get("degraded") is True, b.get("code"))
        check("降级原因是 NO_KEY", data.get("degradeReason") == "NO_KEY", data.get("degradeReason"))
        check("仍返回可读提示文案", bool(data.get("answer")) and "AI" in data.get("answer", ""),
              data.get("answer"))
        cits = data.get("citations") or []
        check("未启用时 citations 仍非空（仅检索结果）", len(cits) >= 1, cits)
        check("citation 里的 postId 可 GET", cits and call("GET", f"/api/posts/{cits[0]['postId']}").get("code") == 0,
              cits[0] if cits else None)
        check("游客 → 40101", ask(question, None).get("code") == 40101)
        check("超长问题（>200 字）→ 40001", ask("很" * 201, token_a).get("code") == 40001)

        b = ask("一", token_a)
        check("单字问题 → NO_EVIDENCE（不检索、不调用）",
              b.get("data", {}).get("degradeReason") == "NO_EVIDENCE", b.get("data"))
        b = ask("qqzzxxvv", token_a)
        check("检索无依据（拉丁乱码）→ NO_EVIDENCE 且 citations 为空",
              b.get("data", {}).get("degradeReason") == "NO_EVIDENCE" and (b.get("data", {}).get("citations") or []) == [],
              b.get("data"))

        print("\n[7b] 摘要（TL;DR）：未启用降级与不可见帖子的口径")
        data = (summarize(p1, token_a).get("data") or {})
        check("未配置 key 时摘要降级为 NO_KEY（degraded=true 且 summary 为 null，不是报错）",
              data.get("degraded") is True and data.get("degradeReason") == "NO_KEY" and data.get("summary") is None,
              data)
        check("不存在的帖子 → 40402", summarize(999999999, token_a).get("code") == 40402)
        tmp_post = make_post("用于验证已删除帖子的摘要口径", "这篇帖子马上会被删除，正文长度足够十个字符。")
        call("DELETE", f"/api/posts/{tmp_post}", token=token_a)
        check("已删除的帖子 → 40402（与详情接口同一口径）", summarize(tmp_post, token_a).get("code") == 40402)
        code_only = make_post("只有代码块的帖子", "```java\nint a = 1;\n```")
        check("正文剥完为空（纯代码块）→ NO_CONTENT",
              (summarize(code_only, token_a).get("data") or {}).get("degradeReason") == "NO_CONTENT")
        check("游客 → 40101", summarize(p1, None).get("code") == 40101)

        # 日志表：降级也要留痕（degraded=1 / NO_KEY / 不写缓存）
        rows = db_rows("SELECT cache_hit, degraded, degrade_reason, total_tokens FROM t_ai_answer_log "
                       f"WHERE question_hash='{fingerprint(question)}' ORDER BY id DESC LIMIT 1")
        check("日志表留下降级记录（degraded=1 / NO_KEY / 无 token）",
              rows and rows[0][1] == "1" and rows[0][2] == "NO_KEY" and rows[0][3] == "NULL",
              rows)
        cache_exists = redis_reply("EXISTS", "codelkup:ai:cache:" + fingerprint(question))
        check("降级结果不写缓存（配好 key 后不会被旧降级文案挡 6 小时）", cache_exists == "0", cache_exists)
    finally:
        stop_app(proc, log)
    return ctx


# ==================== 阶段 B：stub 冒充上游，验正面链路 ====================

def phase_b(ctx: dict) -> None:
    print("\n" + "=" * 58)
    print("阶段 B：base-url 指向本地 stub —— RAG 正面链路全验")
    print("=" * 58)
    stub = StubUpstream()
    proc, log = start_app([
        f"--codelkup.ai.api-key={STUB_API_KEY}",
        f"--codelkup.ai.base-url={stub.base_url}",
        f"--codelkup.ai.daily-budget={DAILY_BUDGET}",
        f"--codelkup.rate-limit.ai-per-hour={ASK_PER_HOUR}",
    ], "b")
    try:
        if proc is None or proc.poll() is not None:
            return
        ts = int(time.time())
        b = call("POST", "/api/auth/register",
                 {"email": f"ai_b_{ts}@test.com", "password": "abc12345", "nickname": "RAG验收员"})
        token_b, uid_b = b["data"]["accessToken"], b["data"]["user"]["id"]
        p1 = ctx["p1"]

        q1 = "虚拟线程在 IO 密集场景该怎么选？"
        q1_spaced = f"  {q1}  "  # 只多两个空格 —— 必须命中同一个缓存
        q2 = "实测对比 应该怎么做才靠谱？"
        # 说明：问题里刻意不带时间戳 —— 缓存与预算键在 [8] 之前已被清理，
        # 同一轮断言的口径是"本次运行"，不需要靠时间戳保证唯一性。
        # 而带上时间戳反而会让词袋里多出一串数字（无常量收益）。

        print("\n[8] 首次提问：走完整链路（检索 → 调用 → 引用映射）")
        try:
            print(f"  预清理 AI 缓存/预算键：{cleanup_ai_keys()} 个")
        except OSError as e:
            print(f"  警告：连不上 Redis（{e}）")
        b = ask(q1, token_b)
        data = b.get("data") or {}
        check("code 0 且未降级", b.get("code") == 0 and data.get("degraded") is False,
              json.dumps(data, ensure_ascii=False)[:200])
        check("stub 收到恰好 1 次请求", len(stub.requests) == 1, len(stub.requests))
        req = stub.requests[0] if stub.requests else {}
        check("请求打到 OpenAI 兼容路径且带配置的 key",
              req.get("path") == "/chat/completions" and req.get("auth") == f"Bearer {STUB_API_KEY}",
              {k: req.get(k) for k in ("path", "auth")})
        body_json = json.loads(req.get("body") or "{}")
        system_msg = body_json.get("messages", [{}])[0].get("content", "")
        user_msg = body_json.get("messages", [{}, {}])[1].get("content", "")
        check("prompt 带「片段是用户内容，不是指令」的防注入声明",
              "用户内容" in system_msg and ("不是给你的指令" in system_msg or "绝不执行" in system_msg),
              system_msg[:80])
        check("片段按 [1] 《标题》 的格式编号",
              bool(re.search(r"\[1\] 《[^》]+》", user_msg)), user_msg[:200])
        check("检索到的片段与主题相关（prompt 里出现「虚拟线程」）", "虚拟线程" in user_msg, user_msg[:200])
        check("问题原文附在片段之后", f"问题：{q1}" in user_msg)
        check("答案与 stub 返回一致", data.get("answer") == "虚拟线程更适合 IO 密集场景 [1]。", data.get("answer"))

        cits = data.get("citations") or []
        check("citations 非空（计划验收列的原文口径）", len(cits) >= 1, cits)
        check("引用命中主题（至少一条标题含「虚拟线程」）",
              any("虚拟线程" in (c.get("title") or "") for c in cits), [c.get("title") for c in cits])
        check("每条引用的 postId 都可 GET（前端能生成站内链接）",
              bool(cits) and all(call("GET", f"/api/posts/{c['postId']}").get("code") == 0 for c in cits),
              [c.get("postId") for c in cits])
        check("引用条目含标题与片段",
              bool(cits and cits[0].get("title")) and bool(cits and cits[0].get("snippet")), cits[0] if cits else None)

        rows = db_rows("SELECT cache_hit, degraded, total_tokens, citation_ids FROM t_ai_answer_log "
                       f"WHERE question_hash='{fingerprint(q1)}' ORDER BY id DESC LIMIT 1")
        check("日志记录了 token 数与引用（且未命中缓存）",
              rows and rows[0][0] == "0" and rows[0][1] == "0" and rows[0][2] == "123"
              and rows[0][3] == str(cits[0].get("postId")),
              rows)
        cached_answer = data.get("answer")
        cached_ids = [c.get("postId") for c in cits]

        print("\n[9] 同一问题（只差空白）再问：命中缓存、不重复计费")
        b = ask(q1_spaced, token_b)
        data2 = b.get("data") or {}
        check("答案一致（来自缓存）", data2.get("answer") == cached_answer, data2.get("answer"))
        check("引用一致", [c.get("postId") for c in (data2.get("citations") or [])] == cached_ids,
              [c.get("postId") for c in (data2.get("citations") or [])])
        check("stub 请求数**没有增加**（缓存真的省下了调用）", len(stub.requests) == 1, len(stub.requests))
        rows = db_rows("SELECT cache_hit, degraded FROM t_ai_answer_log "
                       f"WHERE question_hash='{fingerprint(q1_spaced)}' ORDER BY id DESC LIMIT 1")
        check("日志标记 cache_hit=1", rows and rows[0][0] == "1", rows)

        print("\n[10] 无引用编号的答案：回填全部片段")
        b = ask(q2, token_b)
        data = b.get("data") or {}
        check("stub 收到第 2 次请求", len(stub.requests) == 2, len(stub.requests))
        check("答案里没有编号时 citations 回填全部检索片段（top-k=5 内有多少给多少）",
              len(data.get("citations") or []) >= 2 and data.get("degraded") is False,
              [c.get("postId") for c in (data.get("citations") or [])])
        check("该答案文本确实不带编号", data.get("answer") == "这段答案没有标注任何编号。", data.get("answer"))

        print(f"\n[11] 日预算（{DAILY_BUDGET} 次/天）：只对真调用计数")
        q3 = "配额和限流到底差在哪？"
        b = ask(q3, token_b)
        data = b.get("data") or {}
        check("超预算 → 降级 BUDGET 且给可读提示",
              data.get("degradeReason") == "BUDGET" and "额度" in (data.get("answer") or ""),
              json.dumps(data, ensure_ascii=False)[:160])
        check("超预算时 stub 没收到新请求（根本没调用）", len(stub.requests) == 2, len(stub.requests))
        check("超预算仍然带着检索结果（用户不至于空手）", len(data.get("citations") or []) >= 1)
        used = redis_reply("GET", "codelkup:ai:budget:" + time.strftime("%Y-%m-%d"))
        # 计数 = 预算 + 1：consumeBudget 是"先 INCR 再比较"，超预算那一次也计了数
        #（多算一次的代价远小于"先查后写"在并发下超发；接口本身不做退款）
        check(f"Redis 日预算计数 = {DAILY_BUDGET + 1}（两次真调用 + 超预算那次也计数；缓存命中没计数）",
              used == str(DAILY_BUDGET + 1), f"实际={used}")

        print(f"\n[12] 用户限流（阈值 {ASK_PER_HOUR}/小时）：第 {ASK_PER_HOUR + 1} 次被拒")
        b = ask(q3, token_b)
        check("超出阈值 → 42901", b.get("code") == 42901, b.get("code"))
        check("Redis 里出现该用户的 AI 计数键（问答与摘要共用同一个桶）",
              redis_reply("EXISTS", f"codelkup:rate:ai:u:{uid_b}") == "1")

        # ==================== [13] 长帖 TL;DR（M3-7） ====================
        print("\n[13] 长帖 TL;DR：截断 → 调用 → 缓存 → 编辑失效")
        b = call("POST", "/api/auth/register",
                 {"email": f"ai_c_{ts}@test.com", "password": "abc12345", "nickname": "摘要验收员"})
        token_c, uid_c = b["data"]["accessToken"], b["data"]["user"]["id"]
        # 预算键是全站的，前面几节已把它用满；摘要断言的口径同样是"本次运行"，清掉重算
        #（cleanup_ai_keys 只清 codelkup:ai:*，不碰 auth 命名空间）
        try:
            print(f"  预清理 AI 缓存/预算键：{cleanup_ai_keys()} 个")
        except OSError as e:
            print(f"  警告：连不上 Redis（{e}）")

        # 造一篇 >2 万字的帖子（验收明文要求的量级），并带上 Markdown 语法验证"先剥离再截断"
        long_content = "".join(
            f"## 第 {i} 节\n\n- 虚拟线程在高并发场景下的实测记录，包含压测数据、结论与复现步骤。\n\n"
            for i in range(800))
        board_id = call("GET", "/api/boards")["data"][0]["id"]
        long_post = call("POST", "/api/posts",
                         {"title": "超长帖：虚拟线程压测全记录", "content": long_content,
                          "boardId": board_id}, token=ctx["token"])["data"]["id"]
        check(f"准备超长帖（正文 {len(long_content)} 字，验收要求 >2 万字）",
              len(long_content) > 20000, len(long_content))

        stub_before = len(stub.requests)
        t0 = time.time()
        b = summarize(long_post, token_c)
        elapsed_ms = int((time.time() - t0) * 1000)
        data = b.get("data") or {}
        check("摘要成功且未降级", b.get("code") == 0 and data.get("degraded") is False, data)
        check("返回摘要与 stub 一致", data.get("summary") == stub.summary_content, data.get("summary"))
        check(f"超长帖不超时（实测 {elapsed_ms}ms，目标 <8000ms）", elapsed_ms < 8000, elapsed_ms)
        check("stub 收到摘要请求", len(stub.requests) == stub_before + 1, len(stub.requests))

        body_json = json.loads(stub.requests[-1].get("body") or "{}")
        system_msg = body_json.get("messages", [{}])[0].get("content", "")
        user_msg = body_json.get("messages", [{}, {}])[1].get("content", "")
        check("摘要 prompt 同样带「用户内容」防注入声明",
              "用户内容" in system_msg and "绝不执行" in system_msg, system_msg[:60])
        check("正文被先剥离、再截断（2 万字 → 6000 字 + 省略号）",
              6000 < len(user_msg) <= 6300 and "…" in user_msg, f"prompt 正文长度={len(user_msg)}")
        check("prompt 带了标题语境", "标题：超长帖：虚拟线程压测全记录" in user_msg, user_msg[:80])

        b2 = summarize(long_post, token_c)
        check("第二次摘要命中缓存（stub 请求数不变、摘要一致）",
              (b2.get("data") or {}).get("summary") == stub.summary_content
              and len(stub.requests) == stub_before + 1, len(stub.requests))
        rows = db_rows("SELECT cache_hit, degraded, total_tokens FROM t_ai_answer_log "
                       f"WHERE user_id={uid_c} ORDER BY id DESC LIMIT 2")
        check("日志表：最新一行 cache_hit=1、上一行 cache_hit=0（且 token 有落库）",
              len(rows) == 2 and rows[0][0] == "1" and rows[1][0] == "0" and rows[1][2] == "123", rows)
        keys = redis_cmd("KEYS", f"codelkup:ai:summary:{long_post}:*")
        check("Redis 里有该帖的摘要缓存键", f"codelkup:ai:summary:{long_post}:" in keys, keys[:120])

        # 编辑"开头"而不是结尾：摘要只喂正文前 6000 字（截断后的哈希就是缓存键），
        # 改结尾不会改变"进 prompt 的输入"，因此命中缓存是**正确行为**（相同输入不重复计费）；
        # 改开头才会真正让缓存失效。这条区分本身也值得被钉住。
        edited = "## 开头新增一节\n\n- 这一段位于开头，会进入被摘要的前 6000 字。\n\n" + long_content
        b = call("PUT", f"/api/posts/{long_post}", {"content": edited}, token=ctx["token"])
        check("编辑帖子成功", b.get("code") == 0, b.get("code"))
        check("编辑了被摘要的部分后缓存失效（又调用了一次，而不是拿旧摘要）",
              summarize(long_post, token_c).get("code") == 0
              and len(stub.requests) == stub_before + 2, len(stub.requests))
    finally:
        stub.stop()
        stop_app(proc, log)


# ==================== 阶段 C：P95 与超时边界（M3-9） ====================

P95_SAMPLES = 10
P95_PER_HOUR = 30
P95_BUDGET = 60


def phase_c() -> None:
    """M3-9：引用完整性 + P95（10 次采样）+ 超时边界（真延迟，不是嘴上说 8 秒）。

    <h2>P95 量的是什么，不量什么（先写清楚，免得数字被误读）</h2>
    这里的上游是**本地 stub**（毫秒级返回），所以测出来的是<b>本服务侧的端到端开销</b>
    （检索 + 组装 prompt + 解析 + 落库 + 网络往返），这正是"我方代码不拖后腿"的证据；
    <b>真实模型的推理耗时无法在本机验证</b>（没有 key）—— 但下游延迟的**两种边界**可以：
    3 秒延迟应正常返回、12 秒延迟应被 8 秒读超时掐断并降级，这两条都用真 sleep 验。
    """
    print("\n" + "=" * 58)
    print("阶段 C：P95 采样与超时边界（stub 可注入延迟）")
    print("=" * 58)
    stub = StubUpstream()
    proc, log = start_app([
        f"--codelkup.ai.api-key={STUB_API_KEY}",
        f"--codelkup.ai.base-url={stub.base_url}",
        f"--codelkup.ai.daily-budget={P95_BUDGET}",
        f"--codelkup.rate-limit.ai-per-hour={P95_PER_HOUR}",
    ], "c")
    try:
        if proc is None or proc.poll() is not None:
            return
        try:
            print(f"  预清理 AI 缓存/预算键：{cleanup_ai_keys()} 个")
        except OSError as e:
            print(f"  警告：连不上 Redis（{e}）")

        ts = int(time.time())
        b = call("POST", "/api/auth/register",
                 {"email": f"ai_d_{ts}@test.com", "password": "abc12345", "nickname": "P95验收员"})
        token_d, uid_d = b["data"]["accessToken"], b["data"]["user"]["id"]

        print(f"\n[14] P95：{P95_SAMPLES} 个**互不相同**的问题各打一次（必须真调用，不能命中缓存）")
        samples: list[int] = []
        first_citations: list = []
        all_have_citations = True
        for i in range(P95_SAMPLES):
            q = f"虚拟线程在 IO 密集场景的第 {i} 个疑问 {ts}"
            t0 = time.time()
            b = call("POST", "/api/ai/ask", {"question": q}, token=token_d, timeout=40)
            samples.append(int((time.time() - t0) * 1000))
            data = b.get("data") or {}
            if b.get("code") != 0 or data.get("degraded") is not False:
                check(f"第 {i + 1} 次采样走了完整链路", False, json.dumps(data, ensure_ascii=False)[:120])
                break
            cits = data.get("citations") or []
            all_have_citations = all_have_citations and bool(cits)
            if not first_citations:
                first_citations = cits
        check(f"{len(samples)}/{P95_SAMPLES} 次采样成功且未降级", len(samples) == P95_SAMPLES, samples)
        check("每次采样都带引用（不存在「有答案没依据」的情况）", all_have_citations)

        if len(samples) == P95_SAMPLES:
            ordered = sorted(samples)
            # nearest-rank：ceil(0.95 * n) —— n=10 时它就是最大值，所以同时给出中位数
            p95 = ordered[-(-95 * len(ordered) // 100) - 1]
            median = ordered[len(ordered) // 2]
            print(f"  各次耗时(ms)：{samples}")
            print(f"  中位数={median}ms | P95={p95}ms（n={len(samples)}，nearest-rank 口径）")
            check(f"端到端 P95 ≤ 8000ms（实测 {p95}ms）", p95 <= 8000, p95)

        print("\n[15] 引用完整性：引用条目 ≤ top-k，且每条 postId 真在库里可访问")
        check("引用条数不超过 top-k（5）", len(first_citations) <= 5, len(first_citations))
        reachable = all(call("GET", f"/api/posts/{c['postId']}").get("code") == 0 for c in first_citations)
        check("每条引用都可通过详情接口访问", reachable and bool(first_citations))
        ids = ",".join(str(c["postId"]) for c in first_citations) or "0"
        rows = db_rows(f"SELECT COUNT(*) FROM t_post WHERE id IN ({ids}) AND status=1 AND deleted=0")
        check("每条引用在库里都是已发布未删除（不是幻觉出来的 id）",
              rows and rows[0][0] == str(len(first_citations)), rows)

        print("\n[16] 上游慢（3 秒）：应正常返回，不该过早放弃")
        t0 = time.time()
        b = call("POST", "/api/ai/ask", {"question": f"慢上游：虚拟线程 {ts}"}, token=token_d, timeout=40)
        slow_ms = int((time.time() - t0) * 1000)
        data = b.get("data") or {}
        check("3 秒延迟仍返回完整答案（未降级）",
              b.get("code") == 0 and data.get("degraded") is False, data.get("degradeReason"))
        check(f"耗时与上游延迟相符（实测 {slow_ms}ms，应 ≥3000 且 <8000）", 3000 <= slow_ms < 8000, slow_ms)

        print("\n[17] 上游不响应（12 秒）：应在 8 秒读超时处掐断并降级为 TIMEOUT")
        t0 = time.time()
        b = call("POST", "/api/ai/ask", {"question": f"超时上游：虚拟线程 {ts}"}, token=token_d, timeout=40)
        timeout_ms = int((time.time() - t0) * 1000)
        data = b.get("data") or {}
        check("降级原因 = TIMEOUT 且仍给出检索结果兜底",
              data.get("degradeReason") == "TIMEOUT" and bool(data.get("citations")),
              json.dumps(data, ensure_ascii=False)[:140])
        check(f"超时耗时落在 8 秒附近（实测 {timeout_ms}ms，7500~9500）",
              7500 <= timeout_ms <= 9500, timeout_ms)

        rows = db_rows("SELECT degraded, degrade_reason FROM t_ai_answer_log "
                       f"WHERE user_id={uid_d} ORDER BY id DESC LIMIT 1")
        check("超时也留了日志（degraded=1 / TIMEOUT）—— 失败必须可复盘",
              rows and rows[0][0] == "1" and rows[0][1] == "TIMEOUT", rows)
    finally:
        stub.stop()
        stop_app(proc, log)


def main() -> int:
    ctx: dict = {}
    try:
        ctx = phase_a()
        if ctx:
            phase_b(ctx)
            phase_c()
    except Exception as e:  # 脚本自身异常也要给出可读输出与失败退出码
        check("脚本执行未抛异常", False, f"{type(e).__name__}: {e}")

    total = PASS + FAIL
    print("\n" + "=" * 58)
    print(f"AI 验收（相似问题 + RAG）：{PASS}/{total} 通过"
          + (f" | AI 实现 = {AI_IMPL}" if AI_IMPL else " | AI 实现 = rest（默认）"))
    if FAILURES:
        print("失败项：")
        for name in FAILURES:
            print(f"  - {name}")
    print("=" * 58)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())