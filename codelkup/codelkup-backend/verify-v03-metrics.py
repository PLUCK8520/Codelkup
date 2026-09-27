#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
V0.3 M3-10 验收 —— 指标接入（Prometheus / Micrometer）。

覆盖 TASKS M3-10 的验收列，并把「仅容器网络可达」拆成可证伪的断言：
    [1] /actuator/prometheus 可达，且自定义指标都带 application=codelkup 标签
    [2] 来源地址闸门：公网来源被拒（40101），私网/容器网络来源放行
    [3] 四组自定义指标都真的会「动」（不是只注册了个空壳）：
        score   计时样本 + 最近影响行数 + 开关
        ai      调用结果分布（result=ok|cache_hit|degraded，reason=降级原因）
        sse     连接数 / 在线用户数：连上 +1，断开回落
        counter 最近一次重建的偏差总数：-1（没跑过）→ 报告里的 driftCount

前提（与 verify-v03-score.py 同一约定）：附着型脚本，需开发后端已在跑。
    建议启动参数（把两个「默认要等几十秒」的窗口压到秒级）：
        java -jar target/codelkup-backend-0.0.1-SNAPSHOT.jar \
            --codelkup.score.initial-delay-ms=2000 --codelkup.score.interval-ms=5000 \
            --codelkup.sse.heartbeat-ms=2000

    ⚠️ 为什么 SSE 也要调心跳：客户端**异常断开**（拔网线/进程被杀）时服务端不会立刻
    知道 —— 半开连接只有在「下一次往里写」才会暴露，而平时唯一的写就是心跳。
    因此连接数回落要等到下一拍心跳（默认 25s），这是设计如此、不是泄漏；
    生产上正因如此才必须有心跳（见 SseService.heartbeat 的注释）。
    默认心跳下本脚本第 [7] 步会等满 --sse-timeout 才通过。

为什么用 X-Forwarded-For 模拟「来源」：本机 127.0.0.1 正好落在 application.yml 的
    remoteip.internal-proxies 里，Tomcat 会采信我们发的 XFF —— 于是「公网客户端」与
    「容器网络客户端」两种来源都能在开发机上真实复现（当初验证「XFF 伪造会不会绕过
    登录锁定」用的也是同一手法，见 verify-v03-security.py）。

需要 mysql CLI（把注册出来的普通用户改成管理员，注册接口只产生普通用户）——
    与 verify-sprint3.py 同一做法，同一个绝对路径。

用法：
    python verify-v03-metrics.py
    python verify-v03-metrics.py --base http://127.0.0.1:8085 --timeout 60
退出码：0 = 全通过，1 = 有失败
"""
from __future__ import annotations

import argparse
import json
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8085"
MYSQL = r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB_ARGS = ["-h127.0.0.1", "-ucodelkup", "-pchange-me-dev-password", "codelkup", "-N", "-B"]
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


def call(method: str, path: str, body: dict | None = None, token: str | None = None,
         headers: dict | None = None, timeout: int = 30) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"raw": raw, "status": e.code}


# ==================== Prometheus 文本解析 ====================

def scrape(headers: dict | None = None) -> str:
    """抓一次指标文本。失败返回空串。"""
    req = urllib.request.Request(BASE + "/actuator/prometheus")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with OPENER.open(req, timeout=15) as resp:
            return resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.read().decode("utf-8", "replace")
    except Exception:
        return ""


def samples(text: str, name: str, **labels: str) -> list[float]:
    """取某个指标族的所有样本值；给了 labels 就按标签过滤（子串匹配 `<k>="<v>"`）。"""
    out: list[float] = []
    for line in text.splitlines():
        if line.startswith("#"):
            continue
        parts = line.rsplit(" ", 1)
        if len(parts) != 2:
            continue
        series = parts[0]
        base = series.split("{", 1)[0]
        if base != name:
            continue
        label_str = series[len(base):]
        if labels and not all(f'{k}="{v}"' in label_str for k, v in labels.items()):
            continue
        try:
            out.append(float(parts[1]))
        except ValueError:
            continue
    return out


def value_of(text: str, name: str, default: float | None = None) -> float | None:
    vals = samples(text, name)
    return vals[0] if vals else default


CUSTOM_FAMILIES = [
    "codelkup_score_enabled",
    "codelkup_score_last_affected_rows",
    "codelkup_score_rebuild_seconds_count",
    "codelkup_counter_last_drift",
    "codelkup_sse_connections",
    "codelkup_sse_online_users",
]


# ==================== Redis（只用于清理注册限流桶）====================

def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def cleanup_rate_buckets() -> int:
    """只清注册桶（⚠️ 绝不碰 codelkup:auth:* —— 那是 refresh 白名单）。"""
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


# ==================== MySQL（造管理员）====================

def db(sql: str) -> bool:
    try:
        p = subprocess.run([MYSQL] + DB_ARGS + ["-e", sql], capture_output=True, timeout=25)
        return p.returncode == 0
    except Exception:
        return False


# ==================== 用例 ====================

def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT
    parser = argparse.ArgumentParser(description="V0.3 指标（M3-10）验收")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    parser.add_argument("--timeout", type=int, default=60,
                        help="等待热度分首轮重算的最长秒数（默认 30s 首跑，调到秒级更快）")
    parser.add_argument("--sse-timeout", type=int, default=35,
                        help="等待断开后的连接数回落的最长秒数（要盖过一拍心跳，默认心跳 25s）")
    args = parser.parse_args()
    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port

    print("=" * 58)
    print(f"V0.3 指标验收（M3-10）| base={BASE} | 等待上限={args.timeout}s")
    print("=" * 58)

    if call("GET", "/api/system/health").get("code") != 0:
        print("后端不可用")
        return 1

    print("\n[0] 预清理注册限流桶（保证脚本可重复执行）")
    try:
        print(f"  已清理 {cleanup_rate_buckets()} 个残留计数键")
    except OSError as e:
        print(f"  警告：连不上 Redis，跳过预清理（{e}）")

    # ==================== [1] 端点可达 + 标签 + 指标族 ====================
    print("\n[1] 指标端点与自定义指标族")
    text = scrape()
    check("GET /actuator/prometheus 可达（本机来源）", len(text) > 1000, f"字节数={len(text)}")
    check("JVM/进程等标准指标同时在（说明走的是标准 registry）",
          "jvm_memory_used_bytes" in text,
          "未找到 jvm_memory_used_bytes")

    missing = [f for f in CUSTOM_FAMILIES if not samples(text, f)]
    check("六条自定义时间序列齐全（score×3 / counter / sse×2）", not missing, f"缺失={missing}")
    untagged = [f for f in CUSTOM_FAMILIES if f'{f}{{application="codelkup"}}' not in text]
    check("自定义指标都带 application=codelkup 标签", not untagged,
          f"未带标签={untagged}（统一标签是为了多服务共用一个 Prometheus 时可区分）")

    # ==================== [2] 来源地址闸门 ====================
    print("\n[2] 来源地址闸门（「仅容器网络可达」的代码级证明）")
    public_body = scrape({"X-Forwarded-For": "8.8.8.8"})
    check("公网来源被拒（XFF=8.8.8.8 → 40101，拿不到任何指标）",
          '"code":40101' in public_body, public_body[:80])

    private_body = scrape({"X-Forwarded-For": "172.18.0.5"})
    check("容器网络来源放行（XFF=172.18.0.5 → 指标正常返回）",
          "codelkup_sse_connections" in private_body, f"字节数={len(private_body)}")

    lan_body = scrape({"X-Forwarded-For": "192.168.1.20"})
    check("局域网来源放行（XFF=192.168.1.20）",
          "codelkup_sse_connections" in lan_body, f"字节数={len(lan_body)}")

    # ==================== [3] score 指标 ====================
    print(f"\n[3] 热度分指标（等首轮重算，最长 {args.timeout}s）")
    deadline = time.time() + args.timeout
    count = rows = None
    while time.time() < deadline:
        text = scrape()
        count = value_of(text, "codelkup_score_rebuild_seconds_count")
        rows = value_of(text, "codelkup_score_last_affected_rows")
        if (count or 0) >= 1 and (rows or -1) >= 0:
            break
        time.sleep(3)
    check("重算计时样本已产生（codelkup_score_rebuild_seconds_count ≥ 1）",
          (count or 0) >= 1, f"count={count}")
    check("最近影响行数已写入（≥ 0，-1 表示还没跑过）", (rows or -1) >= 0, f"rows={rows}")
    check("开关指标为 1（codelkup.score.enabled=true）",
          value_of(text, "codelkup_score_enabled") == 1.0,
          f"enabled={value_of(text, 'codelkup_score_enabled')}")

    # ==================== 造一个（管理员）用户 ====================
    print("\n[4] 准备用户（注册 → 改 role=2 → 重新登录）")
    ts = int(time.time())
    email = f"metrics_{ts}@test.com"
    reg = call("POST", "/api/auth/register",
               {"email": email, "password": "abc12345", "nickname": "指标探针"})
    if reg.get("code") != 0:
        print(f"  注册失败：{reg}")
        return 1
    uid = reg["data"]["user"]["id"]
    promoted = db(f"UPDATE t_user SET role=2 WHERE id={uid}")
    login = call("POST", "/api/auth/login", {"email": email, "password": "abc12345"})
    token = (login.get("data") or {}).get("accessToken")
    check("管理员 token 已取得（mysql CLI 可用且改库成功）",
          bool(token) and promoted, f"promoted={promoted} login={login.get('code')}")

    posts = ((call("GET", "/api/posts?size=1").get("data") or {}).get("records") or [])
    if not posts:
        print("  拿不到帖子，无法继续")
        return 1
    post_id = posts[0]["id"]

    # ==================== [5] AI 指标 ====================
    print("\n[5] AI 指标（codelkup_ai_calls_total 的结果分布）")
    before = sum(samples(scrape(), "codelkup_ai_calls_total"))
    ai = call("POST", f"/api/ai/summarize/{post_id}", token=token)
    ai_data = ai.get("data") or {}
    text = scrape()
    after = sum(samples(text, "codelkup_ai_calls_total"))
    check("一次 AI 调用后调用计数器 +1（≥1）", after >= before + 1,
          f"before={before} after={after} resp={ai.get('code')}")
    ai_lines = [l for l in text.splitlines()
                if l.startswith("codelkup_ai_calls_total")]
    print("  当前分布：" + (" | ".join(ai_lines) if ai_lines else "（无样本）"))
    if ai_data.get("degraded"):
        reason = ai_data.get("degradeReason")
        hit = samples(text, "codelkup_ai_calls_total", result="degraded", reason=str(reason))
        check(f"降级原因打进了标签（result=degraded, reason={reason}）", bool(hit),
              f"没有 reason={reason} 的样本")
    else:
        check("正常返回时样本落在 ok（而非 degraded）",
              bool(samples(text, "codelkup_ai_calls_total", result="ok")),
              "没有 result=ok 的样本")

    # ==================== [6] 计数重建指标 ====================
    print("\n[6] 计数重建指标（codelkup_counter_last_drift）")
    before_drift = value_of(scrape(), "codelkup_counter_last_drift")
    report = call("POST", f"/api/admin/counters/rebuild/{post_id}", token=token)
    rep = report.get("data") or {}
    text = scrape()
    drift = value_of(text, "codelkup_counter_last_drift")
    check("重建接口可调（管理员）", report.get("code") == 0, report.get("code"))
    check("偏差数已从 -1（没跑过）变为报告值",
          before_drift == -1.0 and drift is not None and drift >= 0,
          f"before={before_drift} after={drift}")
    check("Gauge 与重建报告一致（同一份 driftCount）",
          drift is not None and rep.get("driftCount") is not None
          and int(drift) == int(rep["driftCount"]),
          f"gauge={drift} report={rep.get('driftCount')}")

    # ==================== [7] SSE 指标 ====================
    print("\n[7] SSE 指标（连上 +1，断开回落）")
    base_conn = value_of(scrape(), "codelkup_sse_connections", 0.0) or 0.0
    base_users = value_of(scrape(), "codelkup_sse_online_users", 0.0) or 0.0

    ticket = ((call("POST", "/api/auth/sse-ticket", token=token).get("data")) or {}).get("ticket")
    check("拿到 SSE ticket", bool(ticket), "签票失败")

    sock = socket.create_connection(("127.0.0.1", 8085), timeout=10)
    sock.sendall((
        f"GET /api/notifications/stream?ticket={ticket} HTTP/1.1\r\n"
        f"Host: 127.0.0.1:8085\r\nAccept: text/event-stream\r\n\r\n"
    ).encode())
    first = sock.recv(4096).decode("utf-8", "replace")
    check("流已建立且首帧是 unread 事件（说明连接真的进了注册表）",
          "event:unread" in first.replace(" ", ""), first[:120].replace("\r\n", "\\n"))

    text = scrape()
    conn = value_of(text, "codelkup_sse_connections", 0.0)
    users = value_of(text, "codelkup_sse_online_users", 0.0)
    check("连接数 +1", conn == base_conn + 1, f"{base_conn} -> {conn}")
    check("在线用户数 +1", users == base_users + 1, f"{base_users} -> {users}")

    sock.close()
    # 半开连接要等下一拍心跳（服务端下一次写失败）才会被摘掉，因此这里必须等过一拍心跳
    deadline = time.time() + args.sse_timeout
    back = -1.0
    while time.time() < deadline:
        time.sleep(1)
        back = value_of(scrape(), "codelkup_sse_connections", -1.0)
        if back == base_conn:
            break
    check("断开后连接数回落（不泄漏）", back == base_conn,
          f"回落值={back} 期望={base_conn}，等待上限={args.sse_timeout}s")

    # ==================== [8] 自清理 ====================
    print("\n[8] 自清理")
    try:
        cleanup_rate_buckets()
        left = len([line for line in redis_cmd("KEYS", "codelkup:rate:register:*").split("\r\n")
                    if line.startswith("codelkup:")])
        check("注册限流桶已清空（不占用后续脚本的额度）", left == 0, f"剩余={left}")
    except OSError as e:
        check("自清理成功", False, str(e))

    print("\n" + "=" * 58)
    print(f"指标验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())