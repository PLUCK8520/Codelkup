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
    ap.add_argument("--check-ratelimit", action="store_true",
                    help="是否验证邮箱查重限流（会占用本机 IP 的配额 30 次/10 分钟，默认关闭）")
    args = ap.parse_args()
    site = args.site.rstrip("/")
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
    st, b, _ = call(api + "/boards")
    check("板块列表免登录可读（10 个）",
          code_of(b) == 0 and isinstance(b.get("data"), list) and len(b["data"]) == 10,
          len(b["data"]) if isinstance(b.get("data"), list) else b)

    st, b, _ = call(api + "/posts?size=5")
    posts = (b.get("data") or {}).get("records") if isinstance(b, dict) else None
    check("帖子列表非空（演示数据已就位）", bool(posts), "返回 %s 条" % (len(posts) if posts else 0))

    # ---------- 4. 注册 → 登录 → 我的资料 ----------
    email = "deploycheck_%d@example.com" % stamp
    password = "Check%dpass" % stamp
    st, b, _ = call(api + "/auth/register", "POST",
                    {"email": email, "password": password, "nickname": "自检账号"})
    token = (b.get("data") or {}).get("accessToken") if code_of(b) == 0 else None
    check("注册可用并返回双 token", bool(token), code_of(b))

    st, b, _ = call(api + "/users/me", token=token)
    check("凭证可用（GET /users/me 拿到本人资料）",
          code_of(b) == 0 and (b.get("data") or {}).get("email") == email, code_of(b))

    st, b, _ = call(api + "/users/me")
    check("未登录访问受保护接口 → 40101", code_of(b) == 40101, code_of(b))

    # ---------- 5. 内容闭环：发帖 → 详情 → 检索 → 删除 ----------
    mark = "自检%d" % stamp
    boards = (call(api + "/boards")[1].get("data") or [{}])
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

    # ---------- 7. API 文档（面试官会点）----------
    st, b, _ = call(site + "/v3/api-docs")
    check("OpenAPI 文档可达（/v3/api-docs）",
          st == 200 and isinstance(b, dict) and b.get("openapi"), "HTTP %s" % st)

    # ---------- 8. 可选：邮箱查重限流 ----------
    if args.check_ratelimit:
        last = None
        for _ in range(31):
            st, b, _ = call(api + "/auth/check-email?email=probe@example.com")
            last = code_of(b)
        check("邮箱查重限流生效（第 31 次 42901）", last == 42901, last)

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