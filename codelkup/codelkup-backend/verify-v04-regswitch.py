#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.4 上线准备验收 —— 注册开关（PRE-MORTEM T0-2，自起 jar 型）。

为什么分两段启动：两个状态都必须验证，而且第二段要证明"关的是注册、不是登录" ——
先以默认（开）创建账号；再以 `--codelkup.auth.registration-enabled=false` 重启，断言：
注册被封（40304）、/api/system/config 如实上报、既有账号仍能登录。
（"配置项真的被读到"不能靠读代码证明 —— 与 L-4 同一验收哲学。）

⚠️ 运行前需停掉开发后端（自起 jar 独占 8085）。
用法：python verify-v04-regswitch.py
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


def call(method: str, path: str, body: dict | None = None) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with OPENER.open(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8", "replace") or "{}")
        except json.JSONDecodeError:
            return {"status": e.code}


def cleanup_register_bucket() -> int:
    """清注册限流桶：让第一段的注册不被历史额度挡住（与其它脚本同一手法）"""
    def redis_cmd(*args: str) -> str:
        payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
        with socket.create_connection(("127.0.0.1", 6379), timeout=3) as s:
            s.sendall(payload.encode())
            return s.recv(65536).decode(errors="replace")

    resp = redis_cmd("KEYS", "codelkup:rate:register:*")
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


def start_app(extra_args: list[str], log_name: str, label: str):
    try:
        if call("GET", "/api/system/health").get("code") == 0:
            check(label, False, "8085 已有服务在监听：验收脚本必须独占后端，请先停掉它再运行")
            return None, None
    except Exception:
        pass
    env = dict(os.environ)
    env.pop("SERVER__PORT", None)
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"
    log = open(log_name, "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(["java", "-jar", JAR] + extra_args,
                            env=env, cwd=JAR_DIR, stdout=log, stderr=subprocess.STDOUT)
    ready, secs = wait_ready()
    check(label, ready, "耗时 %.1fs" % secs)
    if not ready:
        print(open(log_name, encoding="utf-8", errors="replace").read()[-4000:])
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


def main() -> int:
    print("=" * 62)
    print(f"V0.4 注册开关验收 | base={BASE}")
    print("=" * 62)

    try:
        print(f"  预清理注册桶：{cleanup_register_bucket()} 个键")
    except OSError as e:
        print(f"  警告：连不上 Redis（{e}）")

    ts = int(time.time())
    email = f"regsw_{ts}@test.com"

    # ---------- 第一段：默认（开关开） ----------
    print("\n[1] 开关默认（关=未设置）—— 注册应可用")
    proc, log = start_app([], "verify-regswitch-boot1.log", "[0] 启动#1 + 健康检查")
    try:
        if proc is None or proc.poll() is not None:
            return 1
        cfg = call("GET", "/api/system/config")
        data = cfg.get("data") or {}
        check("config 游客可读且 registrationEnabled=true",
              cfg.get("code") == 0 and data.get("registrationEnabled") is True, data)
        reg = call("POST", "/api/auth/register",
                   {"email": email, "password": "abc12345", "nickname": "开关探针"})
        check("注册成功并直接拿到 token",
              reg.get("code") == 0 and bool((reg.get("data") or {}).get("accessToken")),
              reg.get("code"))
    finally:
        stop_app(proc, log)

    # ---------- 第二段：开关关 ----------
    print("\n[2] 开关关闭（--codelkup.auth.registration-enabled=false）")
    proc, log = start_app(["--codelkup.auth.registration-enabled=false"],
                          "verify-regswitch-boot2.log", "[0] 启动#2 + 健康检查")
    try:
        if proc is None or proc.poll() is not None:
            return 1
        cfg2 = call("GET", "/api/system/config")
        data2 = cfg2.get("data") or {}
        check("config 如实上报 registrationEnabled=false",
              cfg2.get("code") == 0 and data2.get("registrationEnabled") is False, data2)
        reg2 = call("POST", "/api/auth/register",
                    {"email": f"regsw_blocked_{ts}@test.com", "password": "abc12345", "nickname": "不该成功"})
        check("新邮箱注册被拒 40304", reg2.get("code") == 40304, reg2.get("code"))
        reg3 = call("POST", "/api/auth/register",
                    {"email": email, "password": "abc12345", "nickname": "不该成功2"})
        check("已存在邮箱同样 40304（拒绝发生在业务之前，与邮箱无关）",
              reg3.get("code") == 40304, reg3.get("code"))
        login = call("POST", "/api/auth/login", {"email": email, "password": "abc12345"})
        check("既有账号仍可登录（关的是注册，不是登录）", login.get("code") == 0, login.get("code"))
    finally:
        stop_app(proc, log)

    print("\n" + "=" * 62)
    print(f"注册开关验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 62)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())