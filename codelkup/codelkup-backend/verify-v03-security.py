#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 P0 安全验收 —— 现场验证三条 low 是否真的关闭。

覆盖（对应 ALIGNMENT 的 S9）：
  1. /api/auth/check-email 的防枚举限流仍生效（第 31 次被限流）
  2. /api/auth/register 也按 IP 限流（第 6 次被限流）—— 修掉"绕道注册接口枚举"
  3. 登录失败按纯 IP 维度累计（同 IP 换邮箱打到第 31 次被拒）—— 修掉"分片喷洒"
  4. 伪造 X-Forwarded-For 的语义：本机直连（= 可信代理）会被采信，而**生产侧的不可伪造**
     由 nginx 用 $remote_addr 覆盖该头保证（已在 compose 栈实测，见 TASKS 备注 31）

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
    """发一条命令，把 RESP 回复解析成"元素列表"。

    ⚠️ 为什么不能"读到不满 64KB 就认为读完了"（本脚本第一版就是这么写的）：
    `KEYS *` 在 dev Redis（700+ 键）上的回复会被 TCP 切成多段，中间某一段
    完全可能小于 64KB —— 那时循环提前收手，**后面的键全丢**。表现不是报错，
    而是"明明存在的键，断言查不到"，也就是一次**假绿**。
    M3-10 实测踩到：伪造 XFF 的计数键确实建了出来（后端日志 `ip=203.0.113.99`、
    按前缀查得到），而 [4] 用 `KEYS *` 查却什么都看不见。

    正确做法是按 RESP 的**自描述长度**读完：数组头 `*N` 之后逐个读 `$len` 载荷。
    """
    def read_line(f) -> str:
        return f.readline().decode("utf-8", "replace").rstrip("\r\n")

    payload = f"*{len(args)}\r\n" + "".join(
        f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=5) as s:
        s.sendall(payload.encode())
        f = s.makefile("rb")
        head = read_line(f)
        if head.startswith("*"):
            items = []
            for _ in range(int(head[1:])):
                mark = read_line(f)
                if mark.startswith("$"):
                    size = int(mark[1:])
                    body = f.read(size).decode("utf-8", "replace")
                    f.read(2)          # 跳过尾随 CRLF
                    items.append(body)
                else:                  # 数组里也可能是整数（本脚本用不到，按原样收下）
                    items.append(mark)
            return items
        if head.startswith("$"):
            size = int(head[1:])
            body = f.read(size).decode("utf-8", "replace")
            f.read(2)
            return [body]
        return [head[1:] if head[:1] in "+-:" else head]


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


def case_forged_xff_semantics() -> None:
    print("\n[4] 伪造 X-Forwarded-For：本机直连（可信代理）会被采信，生产由 nginx 覆盖挡住")
    forged = "203.0.113.99"
    email = f"xff-{int(time.time())}@example.com"
    call("POST", "/api/auth/login",
         {"email": email, "password": "wrong-pass-1"},
         headers={"X-Forwarded-For": forged})

    # ⚠️ 这一节的"正确期望"在 M3-10 被**实测纠正**过，别按直觉改回去：
    #    · 本机直连 8085：127.0.0.1 落在 remoteip.internal-proxies 里 ⇒ Tomcat 采信我们发的
    #      XFF，计数落在**伪造 IP** 上。这不是缺陷 —— 阀的语义就是"可信代理转发的 XFF
    #      才是真实客户端地址"，而本机正是那个可信代理（后端日志实测 `ip=203.0.113.99`）。
    #    · 公网经 nginx：nginx 用 $remote_addr **覆盖**（而非追加）该头 ⇒ 后端看到的永远是真实来源。
    #      compose 栈实测：带伪造 XFF 的登录失败，键是 `login_fail_ip:172.21.0.1`（真实来源），
    #      Redis 里**没有**任何 203.0.113.99 的键。
    #    所以"防爆破不可绕"这条生产口径成立；而本脚本跑在开发栈、直连后端，能钉住的是**阀语义**本身。
    #    （旧版断言写的是"伪造不产生新计数键"，它之所以看着通过，是因为 KEYS * 的回复被读漏了
    #      —— 见 redis_cmd 的注释，那是一次假绿。）
    forged_keys = [k for k in keys("codelkup:auth:login_fail*") if forged in k]
    check("本机直连时 XFF 被采信（阀语义：可信代理的 XFF 才是真实客户端）",
          len(forged_keys) >= 1, f"伪造 IP 的键={forged_keys}")

    # ⚠️ 第五轮审计 #1 起多了"纯邮箱维度"键（login_fail:email:{email}）：它**故意**
    #    不含任何 IP（跨 IP 累计，是伪造 XFF 场景下的兜底防线），不是"记到了真实对端"。
    misattributed = [k for k in keys("codelkup:auth:login_fail*")
                     if f":{email}" in k and forged not in k
                     and not k.startswith("codelkup:auth:login_fail:email:")]
    check("该次失败只记在伪造 IP 维度（没有同时写进真实对端维度）",
          not misattributed, f"异常键={misattributed}")


def case_public_profile_role() -> None:
    print("\n[5] 公开主页不暴露角色（L-2 修复：管理员不可被遍历枚举）")
    # 本用例自己注册一个探测账号；先清注册桶，避免前面 register 限流用例占住的额度
    try:
        cleanup()
    except OSError:
        pass
    email = f"role-probe-{int(time.time())}@example.com"
    _, body = call("POST", "/api/auth/register",
                   {"email": email, "password": "abc12345", "nickname": "角色探针"})
    check("探测账号注册成功", body.get("code") == 0, body.get("code"))
    if body.get("code") != 0:
        return
    uid = body["data"]["user"]["id"]
    token = body["data"]["accessToken"]

    _, guest_view = call("GET", f"/api/users/{uid}")
    guest_data = guest_view.get("data") or {}
    check("游客读公开主页：role 字段不存在（不是 null，是根本没有）",
          guest_view.get("code") == 0 and "role" not in guest_data,
          f"code={guest_view.get('code')} keys={sorted(guest_data.keys())[:12]}")

    _, authed_view = call("GET", f"/api/users/{uid}",
                          headers={"Authorization": "Bearer " + token})
    authed_data = authed_view.get("data") or {}
    check("登录后读公开主页：role 同样不存在（公开面口径不因登录而变）",
          authed_view.get("code") == 0 and "role" not in authed_data,
          f"keys={sorted(authed_data.keys())[:12]}")

    _, me = call("GET", "/api/users/me", headers={"Authorization": "Bearer " + token})
    me_data = me.get("data") or {}
    check("本人的 /users/me 仍返回 role（前端管理入口依赖它，回归护栏）",
          me.get("code") == 0 and isinstance(me_data.get("role"), int),
          me_data.get("role"))


def case_auth_hardening() -> None:
    print("\n[6] 认证加固（审计 informational）：JWT 算法钉死 + refresh TTL 读配置")
    try:
        cleanup()
    except OSError:
        pass
    email = f"harden-{int(time.time())}@example.com"
    _, body = call("POST", "/api/auth/register",
                   {"email": email, "password": "abc12345", "nickname": "加固探针"})
    check("探测账号注册成功", body.get("code") == 0, body.get("code"))
    if body.get("code") != 0:
        return
    uid = body["data"]["user"]["id"]
    access = body["data"]["accessToken"]

    import base64
    header_b64 = access.split(".")[0]
    header = json.loads(base64.urlsafe_b64decode(header_b64 + "=" * (-len(header_b64) % 4)))
    check("JWT 算法显式钉死为 HS256（修复前随密钥长度自动漂移为 HS384）",
          header.get("alg") == "HS256", header.get("alg"))

    ttl = int(redis_cmd("TTL", f"codelkup:auth:refresh:{uid}")[0])
    check("refresh 白名单 TTL 读配置（默认 7 天 = 604800s，允许 ±60s 边界）",
          604740 <= ttl <= 604800, ttl)


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
    case_forged_xff_semantics()
    case_public_profile_role()
    case_auth_hardening()

    print("\n[7] 自清理（避免限流额度留给后续脚本）")
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