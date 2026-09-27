#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Sprint 1 端到端验收脚本。

单进程内：启动 jar -> 等待就绪 -> 依次探测所有接口 -> 关闭。
避免后台进程被沙箱回收的问题。
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8085"
JAR = r"C:\Users\32519\Desktop\Codelkup\codelkup\codelkup-backend\target\codelkup-backend-0.0.1-SNAPSHOT.jar"

# 绕开系统代理：系统代理会劫持 localhost 请求返回 502
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

results = []


def call(method, path, body=None, token=None, headers=None):
    url = BASE + path
    data = None
    hdrs = {"Content-Type": "application/json"}
    if headers:
        hdrs.update(headers)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        hdrs["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with _opener.open(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw
    except Exception as e:
        return -1, str(e)


def check(name, ok, detail):
    results.append((name, ok, detail))
    print(("[PASS] " if ok else "[FAIL] ") + name + "  ->  " + str(detail)[:400])


def wait_ready(timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st, body = call("GET", "/api/system/health")
        if st == 200 and isinstance(body, dict) and body.get("code") == 0:
            return True, time.time() - t0
        time.sleep(1.5)
    return False, time.time() - t0


def main():
    env = dict(os.environ)
    env.pop("SERVER__PORT", None)          # ← 关键：剔除 WorkBuddy 注入的端口变量
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"

    log = open("verify-s1.log", "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(
        ["java", "-jar", JAR],
        env=env, cwd=os.path.dirname(JAR),
        stdout=log, stderr=subprocess.STDOUT,
    )
    print("启动中，pid=%d ..." % proc.pid)
    try:
        ready, secs = wait_ready()
        check("应用启动 + 健康检查", ready, "耗时 %.1fs" % secs)
        if not ready:
            return

        # ==================== S1.1 注册 ====================
        email = "s1_%d@test.com" % int(time.time())
        st, b = call("POST", "/api/auth/register",
                     {"email": email, "password": "abc12345", "nickname": "阿布测试"})
        reg_ok = st == 200 and b and b.get("code") == 0 and b["data"].get("accessToken")
        check("S1.1 注册成功（自动登录返回双 token）", reg_ok, b)
        if not reg_ok:
            return

        access = b["data"]["accessToken"]
        refresh = b["data"]["refreshToken"]
        uid = b["data"]["user"]["id"]

        # 注册重复邮箱
        st, b = call("POST", "/api/auth/register",
                     {"email": email, "password": "abc12345", "nickname": "另一个"})
        check("S1.1 重复邮箱被拒 (40901)",
              b and b.get("code") == 40901, b)

        # 弱密码被校验拦下
        st, b = call("POST", "/api/auth/register",
                     {"email": "weak@test.com", "password": "12345678", "nickname": "弱密码"})
        check("S1.1 纯数字密码被拒 (40001)",
              b and b.get("code") == 40001, b)

        # 注册响应不含 password
        st, b = call("POST", "/api/auth/register",
                     {"email": "leak_%d@test.com" % int(time.time()),
                      "password": "abc12345", "nickname": "泄漏检查"})
        leak = "password" in json.dumps(b, ensure_ascii=False).lower()
        check("S1.1 注册响应不含 password 字段", not leak,
              "含 password" if leak else "干净")

        # ==================== S1.2 登录 ====================
        st, b = call("POST", "/api/auth/login", {"email": email, "password": "abc12345"})
        login_ok = st == 200 and b and b.get("code") == 0 and b["data"].get("accessToken")
        check("S1.2 登录成功", login_ok, b)
        if login_ok:
            access = b["data"]["accessToken"]
            refresh = b["data"]["refreshToken"]

        # 大小写不敏感（注册时转小写）
        st, b = call("POST", "/api/auth/login",
                     {"email": email.upper(), "password": "abc12345"})
        check("S1.2 邮箱大小写不敏感", b and b.get("code") == 0, b.get("code"))

        # 错密码
        st, b = call("POST", "/api/auth/login", {"email": email, "password": "wrong12345"})
        check("S1.2 错误密码被拒 (40104)", b and b.get("code") == 40104, b)

        # 不存在的邮箱 —— 必须与错密码同码，防枚举
        st, b = call("POST", "/api/auth/login",
                     {"email": "no_such_user@test.com", "password": "abc12345"})
        check("S1.2 不存在邮箱与错密码同码（防枚举）",
              b and b.get("code") == 40104, b)

        # 邮箱查重
        st, b = call("GET", "/api/auth/check-email?email=" + email)
        check("S1.2 邮箱查重（已注册 → taken=true）",
              b and b.get("code") == 0 and b["data"]["taken"] is True, b)

        st, b = call("GET", "/api/auth/check-email?email=fresh_%d@test.com" % int(time.time()))
        check("S1.2 邮箱查重（未注册 → taken=false）",
              b and b.get("code") == 0 and b["data"]["taken"] is False, b)

        # ==================== S1.3 鉴权 ====================
        st, b = call("GET", "/api/users/me")
        check("S1.3 无 token 访问受保护接口 → 401",
              b and b.get("code") in (40101, 40102), b)

        # 伪造 token 保持匿名继续过滤链（设计如此，不误拒公开接口），
        # 因此落到 anyRequest().authenticated() → 40101
        st, b = call("GET", "/api/users/me", token="garbage.token.here")
        check("S1.3 伪造 token 被拒 (40101/40102)",
              b and b.get("code") in (40101, 40102), b)

        # 伪造 token 访问**公开**接口应当仍可读（不能因坏 token 误拒游客）
        st, b = call("GET", "/api/users/%d" % uid, token="garbage.token.here")
        check("S1.3 坏 token 不误拒公开接口",
              st == 200 and b and b.get("code") == 0, (st, b))

        st, b = call("GET", "/api/users/me", token=access)
        check("S1.3 合法 token 通过鉴权", st == 200 and b and b.get("code") == 0, (st, b))

        # 公开接口无需登录
        st, b = call("GET", "/api/users/%d" % uid)
        check("S1.3 公开主页接口免登录可访问", st == 200 and b and b.get("code") == 0, (st, b))

        # ==================== S1.4 当前用户解析 ====================
        st, b = call("GET", "/api/users/me", token=access)
        me = (b or {}).get("data") or {}
        check("S1.4 @CurrentUser 注入正确用户（id 匹配）",
              me.get("id") == uid, (me.get("id"), uid))
        check("S1.4 本人资料含 email",
              me.get("email") == email, me.get("email"))

        # ==================== S1.5 个人主页脱敏 ====================
        st, b = call("GET", "/api/users/%d" % uid)
        pub = (b or {}).get("data") or {}
        pub_json = json.dumps(pub, ensure_ascii=False).lower()
        check("S1.5 公开主页不含 email", "email" not in pub_json, list(pub.keys()))
        check("S1.5 公开主页不含 password", "password" not in pub_json, "干净")
        check("S1.5 公开主页昵称正确", pub.get("nickname") == "阿布测试", pub.get("nickname"))

        st, b = call("GET", "/api/users/999999999")
        check("S1.5 不存在的用户 → 40401", b and b.get("code") == 40401, b)

        # ==================== S1.6 声望榜（V0.4）====================
        # 公开只读接口，游客可见（与 /api/users/{id} 同一类）。
        # ⚠️ 本脚本没有数据库通道，所以这里全部用"接口对接口"对账：
        #    榜单 ↔ 公开主页必须同源（昵称/等级/声望），名单的**单调性与名次连续性**
        #    也能纯靠响应验证。写死"榜首必须是某人/某分"会随数据变化而红。
        st, b = call("GET", "/api/users/ranking")
        board = (b or {}).get("data") or []
        check("S1.6 声望榜游客可读且非空", b and b.get("code") == 0 and len(board) > 0, len(board))
        check("S1.6 名次从 1 起连续编号",
              [x["rank"] for x in board] == list(range(1, len(board) + 1)),
              [x["rank"] for x in board][:5])
        check("S1.6 按声望倒序（单调不增）",
              all(board[i]["reputation"] >= board[i + 1]["reputation"] for i in range(len(board) - 1)),
              [x["reputation"] for x in board][:5])
        check("S1.6 声望为 0 的账号不上榜（榜尾也不该出现 0）",
              all(x["reputation"] > 0 for x in board), [x["reputation"] for x in board][-3:])
        board_json = json.dumps(board, ensure_ascii=False).lower()
        check("S1.6 榜单不含 email / password（与公开主页同一脱敏口径）",
              "email" not in board_json and "password" not in board_json,
              sorted(board[0].keys()) if board else None)

        # 跨接口对账：榜首那条与它自己的公开主页必须给出同一个昵称/等级/声望
        top = board[0]
        st, b2 = call("GET", "/api/users/%d" % top["id"])
        prof = (b2 or {}).get("data") or {}
        check("S1.6 榜首条目与公开主页一致（昵称 / 等级 / 声望同源）",
              prof.get("nickname") == top["nickname"]
              and prof.get("reputation") == top["reputation"]
              and prof.get("level") == top["level"],
              (top["nickname"], top["reputation"], prof.get("reputation"), prof.get("level")))

        # 参数边界：limit 是 @RequestParam，走的是"方法级参数校验"那套（40001）
        # 与"类型转换失败"那套（40003）—— 两条路径都要在
        st, b = call("GET", "/api/users/ranking?limit=3")
        check("S1.6 limit 生效", b and b.get("code") == 0 and len(b["data"]) == 3, len(b.get("data") or []))
        st, b = call("GET", "/api/users/ranking?limit=999")
        check("S1.6 limit 超上限被拦下 (40001)", b and b.get("code") == 40001, b)
        st, b = call("GET", "/api/users/ranking?limit=0")
        check("S1.6 limit=0 被拦下 (40001)", b and b.get("code") == 40001, b)
        st, b = call("GET", "/api/users/ranking?limit=abc")
        check("S1.6 非法 limit 类型被拦下 (40003)", b and b.get("code") == 40003, b)

        # ==================== 刷新 / 登出 ====================
        # ⚠️ 注意：refreshToken 白名单是"单槽位"设计（一个用户只保留最新一个），
        #    上面任何一次成功登录都会顶掉旧值。所以这里必须重新登录拿一对"新鲜"的。
        st, b = call("POST", "/api/auth/login", {"email": email, "password": "abc12345"})
        fresh_refresh = b["data"]["refreshToken"]
        fresh_access = b["data"]["accessToken"]

        # 先验证单槽位语义：连续两次登录，第一次的 refreshToken 应作废
        st, b = call("POST", "/api/auth/login", {"email": email, "password": "abc12345"})
        newest_refresh = b["data"]["refreshToken"]

        st, b = call("POST", "/api/auth/refresh", {"refreshToken": fresh_refresh})
        check("单槽位生效：旧 refreshToken 被新登录顶掉后失效",
              b and b.get("code") == 40102, b.get("code") if b else None)

        st, b = call("POST", "/api/auth/refresh", {"refreshToken": newest_refresh})
        new_ok = b and b.get("code") == 0 and b["data"].get("accessToken")
        check("refreshToken 换取新 accessToken", new_ok, b.get("code") if b else None)
        if new_ok:
            access = b["data"]["accessToken"]
            refresh = b["data"]["refreshToken"]

        st, b = call("POST", "/api/auth/refresh", {"refreshToken": "bad.token.xx"})
        check("无效 refreshToken 被拒", b and b.get("code") == 40102, b)

        # 给登出准备一个独立用户的干净 token，避免受单槽位轮换干扰
        lo_email = "logout_%d@test.com" % int(time.time())
        st, b = call("POST", "/api/auth/register",
                     {"email": lo_email, "password": "abc12345", "nickname": "登出测试"})
        lo_access = b["data"]["accessToken"]
        lo_refresh = b["data"]["refreshToken"]

        st, b = call("POST", "/api/auth/logout", token=lo_access)
        check("登出成功", b and b.get("code") == 0, b)

        # 登出后 accessToken 应进黑名单
        st, b = call("GET", "/api/users/me", token=lo_access)
        check("登出后原 accessToken 失效",
              b and b.get("code") in (40101, 40102, 40103), b)

        # 登出后 refreshToken 应被移除
        st, b = call("POST", "/api/auth/refresh", {"refreshToken": lo_refresh})
        check("登出后 refreshToken 失效", b and b.get("code") != 0, b.get("code") if b else None)

        # ==================== 登录失败锁定 ====================
        target = "lock_%d@test.com" % int(time.time())
        call("POST", "/api/auth/register",
             {"email": target, "password": "abc12345", "nickname": "锁定测试"})
        codes = []
        for i in range(7):
            st, b = call("POST", "/api/auth/login",
                         {"email": target, "password": "definitelywrong1"})
            codes.append(b.get("code") if b else st)
        check("登录失败 5 次后触发锁定 (42902)",
              42902 in codes, codes)

        # ==================== 邮箱查重限流（防批量枚举）====================
        # /api/auth/check-email 按设计就要回答"某邮箱是否已注册"（注册表单失焦提示），
        # 等于一个可查询的枚举口子，因此加了按 IP 的固定窗口限流（30 次 / 10 分钟）。
        #
        # ⚠️ 这里用 X-Forwarded-For 指向一个**专属测试 IP**，把 31 次流量与
        #    本机真实 IP 的桶隔离开，避免污染后续断言与重复运行。
        #    直连对端是 127.0.0.1（在 internal-proxies 里 = 可信代理），所以该头会被
        #    Tomcat 的 RemoteIpValve 采纳；换成不受信的公网对端则不会被采纳 ——
        #    那条边界由容器配置保证，本机脚本无法模拟公网对端。
        spam_ip = "203.0.113.%d" % (int(time.time()) % 200 + 1)  # TEST-NET-3，语义上不可路由
        last_code = None
        for _ in range(31):
            st, b = call("GET", "/api/auth/check-email?email=probe@test.com",
                         headers={"X-Forwarded-For": spam_ip})
            last_code = b.get("code") if isinstance(b, dict) else st
        check("邮箱查重超过配额后被限流 (42901)", last_code == 42901, last_code)

        # 限流必须按维度隔离：打满的是 spam_ip 的桶，本机真实 IP 不该被误伤
        st, b = call("GET", "/api/auth/check-email?email=probe@test.com")
        check("邮箱查重限流按 IP 隔离（不误伤正常来源）",
              b and b.get("code") == 0, b.get("code") if isinstance(b, dict) else st)

        # ==================== 404 兜底 ====================
        # 未认证时不泄露"路径是否存在" → 40001 只在已认证后才可能出现。
        # 这里用带合法 token 的请求验证 404 兜底链路本身仍然有效。
        st, b = call("GET", "/api/no-such-endpoint", token=access)
        check("已认证访问不存在接口 → 40001 接口不存在",
              b and b.get("code") == 40001, b)

        st, b = call("GET", "/api/no-such-endpoint")
        check("未认证访问不存在接口 → 40101（不泄露路径存在性）",
              b and b.get("code") == 40101, b)

        # ==================== 参数校验 ====================
        st, b = call("POST", "/api/auth/register",
                     {"email": "bad-email", "password": "abc12345", "nickname": "x"})
        check("非法邮箱 + 过短昵称被校验拦下",
              b and b.get("code") == 40001 and "邮箱" in json.dumps(b, ensure_ascii=False),
              b)

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()

    # ==================== 汇总 ====================
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("\n" + "=" * 70)
    print("Sprint 1 验收：%d/%d 通过" % (passed, total))
    if passed < total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  ->  %s" % (name, str(detail)[:300]))
    print("=" * 70)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
