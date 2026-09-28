#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
上线自检 —— 对着**部署后的公网地址**跑一遍关键路径。

和本地 verify-sprintN.py 的区别：
  - 那些测的是"功能对不对"（几百项断言，需要本机 MySQL/Redis）
  - 这个测的是"这台服务器上到底通不通"：域名解析 → nginx → 静态资源 → 反代 → 后端 → 数据库/Redis
    → 上传卷 → 检索 → 鉴权语义 → API 文档。部署完立刻跑一次，5 秒内知道能不能发到简历上。

用法：
    python3 verify-deploy.py --site https://demo.example.com
    python3 verify-deploy.py --site http://1.2.3.4:8090 --check-ratelimit

退出码：0 全通过；1 有失败项（可直接接部署流水线）
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
results = []


def call(url, method="GET", body=None, token=None, raw=None, content_type=None, timeout=25):
    data = None
    headers = {}
    if raw is not None:
        data, headers["Content-Type"] = raw, content_type or "application/octet-stream"
    elif body is not None:
        data, headers["Content-Type"] = json.dumps(body, ensure_ascii=False).encode("utf-8"), "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with _opener.open(req, timeout=timeout) as resp:
            body_raw = resp.read()
            try:
                return resp.status, json.loads(body_raw.decode("utf-8")), dict(resp.headers)
            except Exception:
                return resp.status, body_raw, dict(resp.headers)
    except urllib.error.HTTPError as e:
        body_raw = e.read()
        try:
            return e.code, json.loads(body_raw.decode("utf-8")), dict(e.headers)
        except Exception:
            return e.code, body_raw, dict(e.headers)
    except Exception as e:
        return -1, str(e), {}


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("[PASS] " if ok else "[FAIL] ") + name + ("  ->  " + str(detail)[:220] if detail else ""))


def code_of(b):
    return b.get("code") if isinstance(b, dict) else None


def stream_probe(url, timeout=12, nbytes=400):
    """读 SSE 流的**前一小段字节**。

    ⚠️ 关键在于"只读一小段就返回"：SSE 是长连接，`resp.read()` 会一直等到连接关闭 ——
    那等于用超时来测超时，永远得不出结论。`read1()` 拿到啥就先返回啥（一次 syscall）。
    nginx 若把流缓冲住了，这里在 timeout 内收不到任何字节（正是我们要的信号）。
    """
    req = urllib.request.Request(url, headers={
        "Accept": "text/event-stream",
        "Accept-Encoding": "identity",  # 压缩缓冲同样会憋住流，自检就别开
    })
    try:
        with _opener.open(req, timeout=timeout) as resp:
            reader = getattr(resp, "read1", None)
            raw = reader(nbytes) if reader else resp.read(1)
            return raw.decode("utf-8", "replace")
    except Exception:
        return ""


def make_png():
    """1×1 合法 PNG（用于同时验证"魔数校验通过"+"上传卷可写"+"静态可读"）"""
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00")) + chunk(b"IEND", b""))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", required=True, help="站点根地址，如 https://demo.example.com 或 http://1.2.3.4:8090")
    ap.add_argument("--demo-email", default="ab@codelkup.dev",
                    help="注册关闭时用于登录的演示账号（默认 seed-demo 的 ab@codelkup.dev）")
    ap.add_argument("--demo-password", default="Codelkup@Demo2026",
                    help="演示账号口令（默认与 seed-demo 默认一致）")
    ap.add_argument("--check-ratelimit", action="store_true",
                    help="是否验证邮箱查重限流（会占用本机 IP 的配额 30 次/10 分钟，默认关闭）")
    args = ap.parse_args()
    site = args.site.rstrip("/")
    # ⚠️ 本机（Windows + Docker Desktop + Python）用 `localhost` 会优先解析到 ::1（IPv6），
    #    那条 IPv6 回环转发链路会挂住（随机超时十几二十秒）—— 统一改走 127.0.0.1。
    #    Linux 服务器上两者等价，这个替换是空操作。
    if "//localhost" in site:
        site = site.replace("//localhost", "//127.0.0.1")
        print("提示：已把 localhost 换成 127.0.0.1（本机 Docker Desktop 的 IPv6 回环链路会挂住）")
    api = site + "/api"
    stamp = int(time.time())
    print("自检目标：%s\n" % site)

    # ---------- 1. 站点与静态资源 ----------
    st, body, hdrs = call(site + "/")
    html = body.decode("utf-8", "replace") if isinstance(body, bytes) else str(body)
    check("首页可达（HTTP 200 + 含 #app 挂载点）", st == 200 and 'id="app"' in html, "HTTP %s" % st)

    assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', html)
    if assets:
        st, _, h = call(site + assets[0])
        check("构建产物可访问（%s）" % assets[0][:34], st == 200, "HTTP %s" % st)
    else:
        check("首页含 /assets/ 构建产物引用", False, "未找到 assets 引用")

    # ---------- 2. 后端健康（经 nginx 反代） ----------
    st, b, _ = call(api + "/system/health")
    d = b.get("data") if isinstance(b, dict) else None
    check("健康检查：MySQL 与 Redis 均 UP",
          code_of(b) == 0 and isinstance(d, dict) and d.get("mysql") == "UP" and d.get("redis") == "UP",
          d)

    # ---------- 3. 免登录可读（博客的"公开面"）----------
    # ⚠️ 先把 data 取出来再判类型：后端没起时网关返回的是 HTML/bytes，
    #    直接 b.get("data") 会抛 AttributeError 把真实的失败原因（502）掩盖成 traceback。
    st, b, _ = call(api + "/boards")
    boards_data = b.get("data") if isinstance(b, dict) else None
    check("板块列表免登录可读（10 个）",
          code_of(b) == 0 and isinstance(boards_data, list) and len(boards_data) == 10,
          len(boards_data) if isinstance(boards_data, list) else b)

    # 站点汇总统计（V0.4 补充）：只断言"可达 + 六字段齐全"，不断言具体数值 ——
    # 演示站的数字随灌数变化，这条探针要验的是"经 nginx 的聚合接口活着"。
    st, b, _ = call(api + "/stats")
    d = b.get("data") if isinstance(b, dict) else None
    check("站点统计免登录可读（六字段齐全）",
          code_of(b) == 0 and isinstance(d, dict)
          and all(isinstance(d.get(k), int) for k in
                  ("boardCount", "boardPostCount", "tagCount",
                   "questionCount", "questionSolvedCount", "questionUnsolvedCount")),
          d)

    st, b, _ = call(api + "/posts?size=5")
    posts = (b.get("data") or {}).get("records") if isinstance(b, dict) else None
    check("帖子列表非空（演示数据已就位）", bool(posts), "返回 %s 条" % (len(posts) if posts else 0))

    # ---------- 4. 注册开关 → 注册/登录 → 我的资料 ----------
    # 先读站点 config（审计 F4）：它证明 `.env` 的 REGISTRATION_ENABLED 真的进了容器
    # （compose 的 environment 是显式白名单，不透传就静默无效）；再按开关分派注册断言。
    st, b, _ = call(api + "/system/config")
    cfg = b.get("data") if isinstance(b, dict) else None
    reg_open = cfg.get("registrationEnabled") if isinstance(cfg, dict) else None
    check("站点 config 可读且 registrationEnabled 为布尔（开关已在容器内生效）",
          code_of(b) == 0 and isinstance(reg_open, bool), cfg)

    email = "deploycheck_%d@example.com" % stamp
    password = "Check%dpass" % stamp
    st, b, _ = call(api + "/auth/register", "POST",
                    {"email": email, "password": password, "nickname": "自检账号"})
    if reg_open:
        token = (b.get("data") or {}).get("accessToken") if code_of(b) == 0 else None
        check("注册可用并返回双 token", bool(token), code_of(b))
    else:
        check("注册开关为关：注册被拒 40304（与 config 口径一致）", code_of(b) == 40304, code_of(b))
        st, b, _ = call(api + "/auth/login", "POST",
                        {"email": args.demo_email, "password": args.demo_password})
        token = (b.get("data") or {}).get("accessToken") if code_of(b) == 0 else None
        check("演示账号仍可登录（关的是注册，不是登录；需先跑过 seed-demo）",
              bool(token), code_of(b))

    st, b, _ = call(api + "/users/me", token=token)
    check("凭证可用（GET /users/me 拿到本人资料）", code_of(b) == 0, code_of(b))

    st, b, _ = call(api + "/users/me")
    check("未登录访问受保护接口 → 40101", code_of(b) == 40101, code_of(b))

    # ---------- 5. 内容闭环：发帖 → 详情 → 检索 → 删除 ----------
    mark = "自检%d" % stamp
    boards_body = call(api + "/boards")[1]
    boards = (boards_body.get("data") or [{}]) if isinstance(boards_body, dict) else [{}]
    board_id = boards[0].get("id")
    st, b, _ = call(api + "/posts", "POST", {
        "title": "%s 上线自检帖" % mark,
        "content": "# 自检\n\n这段文字只用于验证发帖链路与**中文全文检索**（关键词：%s）。" % mark,
        "boardId": board_id, "type": 0, "tags": ["自检"],
    }, token)
    post_id = (b.get("data") or {}).get("id") if code_of(b) == 0 else None
    check("发帖成功（Markdown 原样入库）", bool(post_id), code_of(b))

    if post_id:
        st, b, _ = call(api + "/posts/%d" % post_id)
        check("详情可读且返回 Markdown 原文",
              code_of(b) == 0 and mark in (b["data"].get("content") or ""), code_of(b))

        st, b, _ = call(api + "/search/posts?keyword=%s" % urllib.parse.quote(mark))
        hits = (b.get("data") or {}).get("records") if isinstance(b, dict) else None
        check("中文全文检索命中刚发的帖（ngram 生效）",
              bool(hits) and any(r.get("id") == post_id for r in hits),
              "命中 %s 条" % (len(hits) if hits else 0))

        st, b, _ = call(api + "/posts/%d" % post_id, "DELETE", token=token)
        check("删除自己的帖子成功（写权限闭环）", code_of(b) == 0, code_of(b))

    # ---------- 6. 上传卷（头像）----------
    boundary = "----codelkup" + uuid.uuid4().hex
    png = make_png()
    body_bytes = (
        ("--%s\r\n" % boundary).encode()
        + b'Content-Disposition: form-data; name="file"; filename="check.png"\r\n'
        + b"Content-Type: image/png\r\n\r\n" + png + b"\r\n"
        + ("--%s--\r\n" % boundary).encode()
    )
    st, b, _ = call(api + "/users/me/avatar", "POST", raw=body_bytes, token=token,
                    content_type="multipart/form-data; boundary=" + boundary)
    avatar_url = (b.get("data") or {}).get("avatar") if code_of(b) == 0 else None
    check("头像上传成功（扩展名+魔数校验通过）", bool(avatar_url), code_of(b))
    if avatar_url:
        st, _, _ = call(site + avatar_url)
        check("上传文件经 nginx 可读（数据卷挂载正确）", st == 200, "HTTP %s" % st)

    # ---------- 7. API 文档：公网必须**不可达**（第五轮审计 #2b）----------
    # 匿名可读的接口清单 + 字段模型是攻击者的"地图"；生产（prod profile）已由
    # springdoc.api-docs.enabled=false 关闭，这里断言它确实关着。
    # （此前这项断言的是"可达"——上线前把它当演示加分项；审计后改口径。）
    st, _, _ = call(site + "/v3/api-docs")
    check("API 文档未暴露到公网（/v3/api-docs → 404）", st == 404, "HTTP %s" % st)

    # ---------- 指标端点：公网必须不可达（M3-10）----------
    # 指标里有业务量级、错误分布与 AI 调用成本，抓取只应发生在**容器网络内**
    # （compose 不映射后端端口，抓取方直连 backend:8085）。
    # 公网这条路径由 nginx 的 `location /actuator/ { return 404; }` 挡掉；
    # 后端侧另有一道来源地址闸门（SecurityConfig：仅回环 + RFC1918）。
    st, body, _ = call(site + "/actuator/prometheus")
    raw = body.decode("utf-8", "replace") if isinstance(body, bytes) else str(body)
    check("指标端点未暴露到公网（/actuator/prometheus → 404 且不泄露样本）",
          st == 404 and "codelkup_" not in raw, "HTTP %s" % st)

    # ---------- 8. AI 能力：没配 key 也必须"降级不报错"（M3）----------
    if token:
        st, b, _ = call(api + "/search/posts?keyword=%s" % urllib.parse.quote("虚拟"), timeout=20)
        hits = (b.get("data") or {}).get("records") or []
        seed_title = (hits[0].get("title") if hits else "") or "虚拟线程"

        st, b, _ = call(api + "/ai/similar-questions", "POST", {"title": seed_title}, token)
        rows = b.get("data") if isinstance(b, dict) else None
        check("相似问题提示可用（复用检索，不调 LLM —— 没 key 也能用）",
              code_of(b) == 0 and isinstance(rows, list), code_of(b))

        st, b, _ = call(api + "/ai/ask", "POST", {"question": "虚拟线程和平台线程有什么区别？"}, token)
        d = b.get("data") or {}
        check("RAG 问答结构完整（answer + citations + degraded 三件套）",
              code_of(b) == 0 and bool(d.get("answer")) and isinstance(d.get("citations"), list)
              and "degraded" in d,
              {"degraded": d.get("degraded"), "reason": d.get("degradeReason"),
               "citations": len(d.get("citations") or [])})
        if d.get("degraded"):
            check("未配 key 时是**降级**而不是报错（原因 + 可读文案都在）",
                  bool(d.get("degradeReason")) and len(d.get("answer") or "") > 4, d.get("degradeReason"))

        st, b, _ = call(api + "/ai/ask", "POST", {"question": "这条要登录"}, None)
        check("AI 接口未登录 → 40101（不开放给游客）", code_of(b) == 40101, code_of(b))

    # ---------- 9. 实时通道：经 nginx 真拉一次流（M3）----------
    if token:
        st, b, _ = call(api + "/auth/sse-ticket", "POST", {}, token)
        ticket = (b.get("data") or {}).get("ticket") if code_of(b) == 0 else None
        check("SSE ticket 签发成功（一次性，60 秒有效）", bool(ticket), code_of(b))
        if ticket:
            frame = stream_probe(api + "/notifications/stream?ticket=" + ticket, timeout=12)
            check("经 nginx 收到流的第一帧（证明 proxy_buffering off 真的生效）",
                  "unread" in frame, (frame[:120] if frame else "12 秒内没收到任何字节（nginx 可能缓冲住了）"))

    # ---------- 10. 可选：邮箱查重限流 ----------
    if args.check_ratelimit:
        last = None
        for _ in range(31):
            st, b, _ = call(api + "/auth/check-email?email=probe@example.com")
            last = code_of(b)
        check("邮箱查重限流生效（第 31 次 42901）", last == 42901, last)

    # ---------- 11. 举报与审核（V0.5 / M5）：路由与权限边界 ----------
    # 这里只验"新路由经 nginx 已接通 + 权限边界还在"，不验业务逻辑
    # （业务逻辑由 verify-v05-reports.py 的 60 项断言负责，那个需要本机 MySQL/Redis）。
    # targetId 用字面量即可：未登录请求在鉴权层就被拦下，根本走不到目标校验。
    st, b, _ = call(api + "/reports", "POST", {"targetType": 1, "targetId": 1, "reason": "SPAM"})
    check("举报接口未登录 → 40101（新路由经 nginx 已接通）", code_of(b) == 40101, code_of(b))

    if token:
        st, b, _ = call(api + "/admin/reports", token=token)
        check("审核队列对普通用户 → 40301（仅管理员）", code_of(b) == 40301, code_of(b))
        st, b, _ = call(api + "/admin/reports/pending-count", token=token)
        check("待处理举报数对普通用户 → 40301", code_of(b) == 40301, code_of(b))

    # ---------- 汇总 ----------
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("\n" + "=" * 62)
    print("上线自检：%d/%d 通过" % (passed, total))
    for name, ok, detail in results:
        if not ok:
            print("  ✗ %s -> %s" % (name, str(detail)[:200]))
    print("=" * 62)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())