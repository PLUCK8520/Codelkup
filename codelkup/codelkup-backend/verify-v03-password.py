#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 M2 验收 —— 修改密码（后端语义 + 凭证失效链）。

对应 ALIGNMENT 的 S9 同批次完整性目标，覆盖 M2-6 的验收：
    改密成功 → 旧 refreshToken 失效（白名单被清）→ 旧 accessToken 被拉黑（401）
    且：原密码错拒绝、新旧相同拒绝、改密后必须用新密码才能登录。
    M-1 修复（安全审计，2026-09-27）追加：原密码错 5 次 → 锁定 15 分钟（42902，与登录同一口径）；
    锁定期间正确原密码也不放行；清除计数后恢复可用。
    F1 修复（第二轮安全审计，2026-09-27）追加：refresh 轮换的单次消费语义 ——
    第一枚换发成功、同一枚二次使用 40102（CAS 原子消费）、新换发的仍可继续轮换。

设计原则：**只测"改完有没有真的锁住旧凭证"**，不测"接口返回 200" ——
后者说明不了任何事，前者才是用户说"改密=把别人踢下线"时的真实预期。

用法：
    python3 verify-v03-password.py
    python3 verify-v03-password.py --base http://127.0.0.1:8085

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

OLD_PWD = "abc12345"
NEW_PWD = "newpass456"


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
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


def count_rate_buckets() -> int:
    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
    return len([line for line in resp.split("\r\n") if line.startswith("codelkup:")])


def redis_int(resp: str) -> int:
    """解析 RESP 整数应答（":1\\r\\n" / ":-1\\r\\n"）—— 用于 EXISTS / TTL"""
    try:
        return int(resp.strip().lstrip(":").split("\r\n")[0])
    except ValueError:
        return -1


def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT
    parser = argparse.ArgumentParser(description="V0.3 改密验收")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    args = parser.parse_args()
    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port

    print("=" * 58)
    print(f"V0.3 改密验收 | base={BASE}")
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

    print("\n[1] 注册一个一次性账号（注册即登录，直接拿到双 token）")
    email = f"pwd_{int(time.time())}@test.com"
    reg = call("POST", "/api/auth/register",
               {"email": email, "password": OLD_PWD, "nickname": "改密测试"})
    check("注册成功", reg.get("code") == 0, reg.get("code"))
    access, refresh = reg["data"]["accessToken"], reg["data"]["refreshToken"]
    uid = reg["data"]["user"]["id"]

    me = call("GET", "/api/users/me", token=access)
    check("基线：旧 accessToken 可用", me.get("code") == 0, me.get("code"))

    print("\n[2] 两道防护：原密码错 / 新旧相同")
    wrong = call("PUT", "/api/users/me/password",
                 {"oldPassword": "wrong-pass-1", "newPassword": NEW_PWD}, token=access)
    check("原密码不正确被拒（40104）", wrong.get("code") == 40104, wrong.get("code"))

    same = call("PUT", "/api/users/me/password",
                {"oldPassword": OLD_PWD, "newPassword": OLD_PWD}, token=access)
    check("新密码与原密码相同被拒（40001）", same.get("code") == 40001, same.get("code"))

    weak = call("PUT", "/api/users/me/password",
                {"oldPassword": OLD_PWD, "newPassword": "12345678"}, token=access)
    check("弱密码（纯数字）被参数校验拦下（40001）", weak.get("code") == 40001, weak.get("code"))

    print("\n[3] 正式改密")
    # 再登录一次，模拟"另一台设备"的会话：改密后它手里的 access 也必须失效（L-3 修复）
    other_login = call("POST", "/api/auth/login", {"email": email, "password": OLD_PWD})
    other_access = other_login["data"]["accessToken"] if other_login.get("code") == 0 else None
    check("准备：另一台设备的 access 就绪", bool(other_access), other_login.get("code"))
    # ⚠️ iat 是秒精度：必须跨过一秒，保证"他设备的 token"签发时刻**严格早于**撤销水位，
    #    否则同秒边界会让下面的撤销断言偶发失败（与 verify-v03-score 的 1.1s 间隔同一手法）
    time.sleep(1.1)

    ok = call("PUT", "/api/users/me/password",
              {"oldPassword": OLD_PWD, "newPassword": NEW_PWD}, token=access)
    check("改密成功", ok.get("code") == 0, ok.get("code"))

    print("\n[4] 旧凭证必须全部失效（这才是「改密」的实际含义）")
    after = call("GET", "/api/users/me", token=access)
    check("旧 accessToken 已被拉黑（40101）", after.get("code") == 40101, after.get("code"))

    if other_access:
        check("另一台设备的旧 accessToken 也被撤销（40101，L-3 修复）",
              call("GET", "/api/users/me", token=other_access).get("code") == 40101)

    refreshed = call("POST", "/api/auth/refresh", {"refreshToken": refresh})
    check("旧 refreshToken 已失效（40102）", refreshed.get("code") == 40102, refreshed.get("code"))

    old_login = call("POST", "/api/auth/login", {"email": email, "password": OLD_PWD})
    check("旧密码无法登录（40104）", old_login.get("code") == 40104, old_login.get("code"))

    new_login = call("POST", "/api/auth/login", {"email": email, "password": NEW_PWD})
    check("新密码可以登录", new_login.get("code") == 0, new_login.get("code"))
    new_access = new_login["data"]["accessToken"] if new_login.get("code") == 0 else None
    if new_access:
        check("新登录的 accessToken 可用",
              call("GET", "/api/users/me", token=new_access).get("code") == 0)

    print("\n[5] 失败锁定（M-1 修复：与登录同一口径，5 次 / 15 分钟）")
    # 此刻账号密码已是 NEW_PWD，用上一步新登录的 new_access。
    # 注意 [2] 的那次错误原密码与 [3] 成功改密已把计数清零，这里从 0 开始。
    wrong_codes = []
    for _ in range(5):
        r = call("PUT", "/api/users/me/password",
                 {"oldPassword": "wrong-pass-1", "newPassword": "another789"}, token=new_access)
        wrong_codes.append(r.get("code"))
    check("前 5 次错误原密码均为 40104（每次都被计数）",
          all(c == 40104 for c in wrong_codes), wrong_codes)

    sixth = call("PUT", "/api/users/me/password",
                 {"oldPassword": "wrong-pass-1", "newPassword": "another789"}, token=new_access)
    check("第 6 次触发锁定（42902）", sixth.get("code") == 42902, sixth.get("code"))

    during = call("PUT", "/api/users/me/password",
                  {"oldPassword": NEW_PWD, "newPassword": "another789"}, token=new_access)
    check("锁定期间即使原密码正确也不放行（锁定优先于比对）",
          during.get("code") == 42902, during.get("code"))

    lock_key = "codelkup:auth:login_fail:pwd:" + str(uid)
    ttl = redis_int(redis_cmd("TTL", lock_key))
    check("锁计数键存在且在 15 分钟窗口内",
          redis_int(redis_cmd("EXISTS", lock_key)) == 1 and 0 < ttl <= 15 * 60,
          f"ttl={ttl}")

    redis_cmd("DEL", lock_key)  # 等价于窗口自然过期
    recovered = call("PUT", "/api/users/me/password",
                     {"oldPassword": NEW_PWD, "newPassword": "final321"}, token=new_access)
    check("清除计数后恢复可用（正确原密码改密成功）", recovered.get("code") == 0, recovered.get("code"))
    check("成功后被计数清零",
          redis_int(redis_cmd("EXISTS", lock_key)) == 0,
          redis_int(redis_cmd("EXISTS", lock_key)))

    final_login = call("POST", "/api/auth/login", {"email": email, "password": "final321"})
    check("锁定恢复后的改密生效：新密码可登录", final_login.get("code") == 0, final_login.get("code"))

    print("\n[6] refresh 轮换的单次消费语义（F1 修复：CAS 原子检查并换发）")
    # 与改密并行时"删除白名单后旧 refresh 又被写回"的 TOCTOU 已在服务端用 Lua CAS 关闭；
    # 这里从客户端侧验证可观察到的语义：一枚 refresh 只能消费一次，且消费后接续的
    # 新 refresh 仍可继续轮换（不是"用过就废掉整条链"，否则正常前端会莫名掉线）。
    pair = call("POST", "/api/auth/login", {"email": email, "password": "final321"})
    r0 = pair["data"]["refreshToken"] if pair.get("code") == 0 else None
    check("准备：拿到一枚有效 refreshToken", bool(r0), pair.get("code"))

    first = call("POST", "/api/auth/refresh", {"refreshToken": r0})
    r1 = first["data"]["refreshToken"] if first.get("code") == 0 else None
    check("第一次轮换成功且换发新 refreshToken（与旧值不同）",
          first.get("code") == 0 and bool(r1) and r1 != r0, first.get("code"))

    replay = call("POST", "/api/auth/refresh", {"refreshToken": r0})
    check("同一枚 refreshToken 二次使用被拒（40102，已被 CAS 消费）",
          replay.get("code") == 40102, replay.get("code"))

    second = call("POST", "/api/auth/refresh", {"refreshToken": r1})
    check("换发出的新 refreshToken 仍可继续轮换（链路未被误伤）",
          second.get("code") == 0, second.get("code"))

    print("\n[7] 自清理")
    try:
        cleanup_rate_buckets()
        left = count_rate_buckets()
        check("注册限流桶已清空", left == 0, f"剩余={left}")
    except OSError as e:
        check("自清理成功", False, str(e))

    print("\n" + "=" * 58)
    print(f"改密验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())