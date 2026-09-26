#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 P0 安全验收 —— 现场验证三条 low 是否真的关闭。

覆盖（对应 ALIGNMENT 的 S9）：
  1. /api/auth/check-email 的防枚举限流仍生效（第 31 次被限流）
  2. /api/auth/register 也按 IP 限流（第 6 次被限流）—— 修掉"绕道注册接口枚举"
  3. 登录失败按纯 IP 维度累计（同 IP 换邮箱打到第 31 次被拒）—— 修掉"分片喷洒"
  4. 伪造 X-Forwarded-For 不会产生新的计数键（回归上一轮的修复）

设计取舍：
  - **只走 HTTP + 原生 socket**，不依赖 requests / redis 客户端 —— 服务器与 CI 上都能直接跑
  - **跑完自清理**：限流是有"额度"的，不清掉的话一小时内再跑 verify-sprint*.py
    （单次就要注册 5 个用户）会被我们自己加的限流挡住。清理走 Redis RESP，
    只删本脚本涉及的几个前缀。
  - 阈值口径与 application.yml 的默认值一致（register 5/小时、login-ip 30/10 分钟）。
    若你把 codelkup.rate-limit.* 调大，请同步改这里的断言数字。

用法：
    python3 verify-v03-security.py
    python3 verify-v03-security.py --base http://127.0.0.1:8090 --redis-host 127.0.0.1

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

# 与 application.yml 的默认值保持一致
REGISTER_PER_HOUR = 5
LOGIN_IP_MAX = 30
CHECK_EMAIL_MAX = 30

BASE = "http://127.0.0.1:8085"
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379

# 统计
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


def opener() -> urllib.request.OpenerDirector:
    """绕开系统代理：本机开发常配了代理，走代理会打不到 127.0.0.1"""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


OPENER = opener()


def call(method: str, path: str, body: dict | None = None,
         headers: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with OPENER.open(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


# ==================== Redis（RESP over socket，零依赖）====================

def redis_cmd(*args: str) -> list[str]:
    """发一条命令，返回按行切分的响应（够用即可，不做完整 RESP 解析）"""
    payload = f"*{len(args)}\r\n" + "".join(
        f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        chunks = []
        while True:
            buf = s.recv(65536)
            if not buf:
                break
            chunks.append(buf)
            # KEYS 的多行响应长度不定，这里按"读到不再有数据"判断
            if len(buf) < 65536:
                break
    return b"".join(chunks).decode(errors="replace").split("\r\n")


def keys(pattern: str) -> list[str]:
    return [line for line in redis_cmd("KEYS", pattern) if line.startswith("codelkup:")]


def cleanup() -> list[str]:
    """删掉本脚本产生的计数键，避免污染后续脚本与手工测试"""
    victims: list[str] = []
    for pattern in ("codelkup:rate:register:*", "codelkup:rate:check_email:*",
                    "codelkup:auth:login_fail_ip:*", "codelkup:auth:login_fail:*"):
        victims.extend(keys(pattern))
    if victims:
        redis_cmd("DEL", *victims)
    return victims


# ==================== 用例 ====================

def case_check_email_limit() -> None:
    print("\n[1] check-email 防枚举限流（回归项）")
    email = f"probe-{int(time.time())}@example.com"
    codes = []
    for _ in range(CHECK_EMAIL_MAX + 1):
        _, body = call("GET", f"/api/auth/check-email?email={email}")
        codes.append(body.get("code"))
    blocked = [c for c in codes[:CHECK_EMAIL_MAX] if c == 42901]
    check(f"前 {CHECK_EMAIL_MAX} 次不限流", not blocked, f"提前被限流的次数={len(blocked)}")
    check(f"第 {CHECK_EMAIL_MAX + 1} 次返回 42901",
          codes[CHECK_EMAIL_MAX] == 42901, f"实际 code={codes[CHECK_EMAIL_MAX]}")


def case_register_limit() -> None:
    print("\n[2] register 限流（本次新增：堵住「绕道注册接口枚举邮箱」）")
    # 同一个邮箱反复提交即可：存在则 40901、不存在则首次建号，两种结果都不影响计数
    email = f"verify-v03-probe-{int(time.time())}@example.com"
    codes = []
    for _ in range(REGISTER_PER_HOUR + 1):
        _, body = call("POST", "/api/auth/register",
                       {"email": email, "password": "abc12345", "nickname": "限流探针"})
        codes.append(body.get("code"))
    check(f"前 {REGISTER_PER_HOUR} 次未被限流",
          all(c != 42901 for c in codes[:REGISTER_PER_HOUR]),
          f"codes={codes[:REGISTER_PER_HOUR]}")
    check(f"第 {REGISTER_PER_HOUR + 1} 次返回 42901",
          codes[REGISTER_PER_HOUR] == 42901, f"实际 code={codes[REGISTER_PER_HOUR]}")

    bucket = [k for k in keys("codelkup:rate:register:*")]
    check("Redis 中存在 register 限流计数键", len(bucket) >= 1, f"keys={bucket}")


def case_login_spray_limit() -> None:
    print(f"\n[3] 登录失败按纯 IP 维度累计（打散到 {LOGIN_IP_MAX} 个邮箱也不放过）")
    codes = []
    for i in range(LOGIN_IP_MAX + 1):
        # 每个邮箱只错一次 —— 正是"分片喷洒"的形态，IP + 邮箱 维度永远不会触发
        _, body = call("POST", "/api/auth/login",
                       {"email": f"spray-{i}-{int(time.time())}@example.com",
                        "password": "wrong-pass-1"})
        codes.append(body.get("code"))
    early_blocked = [i for i, c in enumerate(codes[:LOGIN_IP_MAX]) if c == 42901]
    check(f"前 {LOGIN_IP_MAX} 次不被 IP 维度拦截", not early_blocked,
          f"提前被拦的下标={early_blocked}")
    check(f"第 {LOGIN_IP_MAX + 1} 次返回 42901（IP 维度上限）",
          codes[LOGIN_IP_MAX] == 42901, f"实际 code={codes[LOGIN_IP_MAX]}")

    ip_keys = keys("codelkup:auth:login_fail_ip:*")
    check("Redis 中存在纯 IP 维度计数键", len(ip_keys) >= 1, f"keys={ip_keys}")


def case_forged_xff_ignored() -> None:
    print("\n[4] 伪造 X-Forwarded-For 不产生新计数键（上一轮修复的回归）")
    forged = "203.0.113.99"
    call("POST", "/api/auth/login",
         {"email": f"xff-{int(time.time())}@example.com", "password": "wrong-pass-1"},
         headers={"X-Forwarded-For": forged})
    leaked = [k for k in keys("*") if forged in k]
    check("Redis 中没有任何以伪造 IP 为维度的键", not leaked, f"keys={leaked}")


def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT

    parser = argparse.ArgumentParser(description="V0.3 P0 安全验收")
    parser.add_argument("--base", default=BASE, help="后端地址（默认本机 8085）")
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    args = parser.parse_args()

    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port

    print("=" * 58)
    print(f"V0.3 安全验收 | base={BASE} | redis={REDIS_HOST}:{REDIS_PORT}")
    print("=" * 58)

    _, health = call("GET", "/api/system/health")
    if health.get("code") != 0:
        print(f"后端不可用：{health}")
        return 1

    # 预清理：限流是**固定窗口计数**，上一次跑验收/手工测试留下的计数会让"前 N 次不限流"
    # 这类断言假失败（实测踩过：放宽阈值跑完 verify-sprint 后，同一 IP 的注册桶已累计十几）。
    # 本脚本自己掌管这几个桶：开跑前清空、跑完再清空。
    print("\n[0] 预清理（清空本脚本要用的限流桶，保证断言可重复）")
    try:
        stale = cleanup()
        print(f"  已清理 {len(stale)} 个残留计数键")
    except OSError as e:
        check("预清理成功", False, f"连不上 Redis({REDIS_HOST}:{REDIS_PORT})：{e}")
        return 1

    case_check_email_limit()
    case_register_limit()
    case_login_spray_limit()
    case_forged_xff_ignored()

    print("\n[5] 自清理（避免限流额度留给后续脚本）")
    try:
        deleted = cleanup()
        remain = (keys("codelkup:rate:register:*") + keys("codelkup:auth:login_fail_ip:*"))
        check(f"已清理 {len(deleted)} 个计数键，且无残留", not remain, f"残留={remain}")
    except OSError as e:
        check("自清理成功", False, f"连不上 Redis({REDIS_HOST}:{REDIS_PORT})：{e}")

    print("\n" + "=" * 58)
    print(f"安全验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())