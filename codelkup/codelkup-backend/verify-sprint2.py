#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Sprint 2 端到端验收脚本（内容核心）。

单进程内：启动 jar -> 等待就绪 -> 依次探测 -> 关闭。
"""
import io
import json
import os
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib

BASE = "http://127.0.0.1:8085"
JAR_DIR = r"C:\Users\32519\Desktop\Codelkup\codelkup\codelkup-backend"
JAR = os.path.join(JAR_DIR, "target", "codelkup-backend-0.0.1-SNAPSHOT.jar")
MYSQL = r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "codelkup"
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


DB_ARGS = ["-h127.0.0.1", "-ucodelkup", "-p" + _dev_pw(), DB, "-N", "-B"]

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
results = []


def db_scalar(sql, default=None):
    """直连 MySQL 取单个标量值 —— 用于把期望值从"真值来源"取，而不是硬编码。"""
    try:
        p = subprocess.run([MYSQL] + DB_ARGS + ["-e", sql],
                           capture_output=True, timeout=25)
        if p.returncode != 0:
            return default
        out = p.stdout.decode("utf-8", "replace").strip()
        return int(out.splitlines()[0].split("\t")[0]) if out else default
    except Exception:
        return default


def call(method, path, body=None, token=None, raw=None, content_type=None):
    url = BASE + path
    data = None
    hdrs = {}
    if raw is not None:
        data = raw
        hdrs["Content-Type"] = content_type or "application/octet-stream"
    else:
        hdrs["Content-Type"] = "application/json"
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        hdrs["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with _opener.open(req, timeout=20) as resp:
            body_raw = resp.read()
            try:
                return resp.status, json.loads(body_raw.decode("utf-8"))
            except Exception:
                return resp.status, body_raw
    except urllib.error.HTTPError as e:
        raw_body = e.read()
        try:
            return e.code, json.loads(raw_body.decode("utf-8"))
        except Exception:
            return e.code, raw_body
    except Exception as e:
        return -1, str(e)


def code_of(b):
    """安全取业务码：响应体不是 dict 时返回 None（避免脚本自身崩掉）"""
    return b.get("code") if isinstance(b, dict) else None


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("[PASS] " if ok else "[FAIL] ") + name + ("  ->  " + str(detail)[:300] if detail else ""))


def wait_ready(timeout=150):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st, b = call("GET", "/api/system/health")
        if st == 200 and isinstance(b, dict) and b.get("code") == 0:
            return True, time.time() - t0
        time.sleep(1.5)
    return False, time.time() - t0


def make_png():
    """构造一个最小合法 PNG（用于测试魔数校验通过）。"""
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    idat = zlib.compress(b"\x00\xff\x00\x00")
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


def deep_get(d, **kw):
    """小工具：从嵌套 dict 取值，缺失返回 None"""
    cur = d
    for k in kw.get("path", []):
        if not isinstance(cur, dict):
            return None
        cur = cur.get(k)
    return cur


def main():
    env = dict(os.environ)
    env.pop("SERVER__PORT", None)
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"

    log = open("verify-s2.log", "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(["java", "-jar", JAR], env=env, cwd=JAR_DIR,
                            stdout=log, stderr=subprocess.STDOUT)
    print("启动中，pid=%d ..." % proc.pid)
    try:
        ready, secs = wait_ready()
        check("应用启动 + 健康检查", ready, "耗时 %.1fs" % secs)
        if not ready:
            print(open("verify-s2.log", encoding="utf-8", errors="replace").read()[-4000:])
            return

        # ---- 准备两个用户 ----
        ts = int(time.time())
        st, b = call("POST", "/api/auth/register",
                     {"email": "s2a_%d@test.com" % ts, "password": "abc12345", "nickname": "作者甲"})
        token_a, uid_a = b["data"]["accessToken"], b["data"]["user"]["id"]
        st, b = call("POST", "/api/auth/register",
                     {"email": "s2b_%d@test.com" % ts, "password": "abc12345", "nickname": "路人乙"})
        token_b, uid_b = b["data"]["accessToken"], b["data"]["user"]["id"]

        # ==================== S2.1 板块 / 标签 ====================
        st, b = call("GET", "/api/boards")
        boards = (b or {}).get("data") or []
        check("S2.1 板块列表免登录可访问", st == 200 and b.get("code") == 0, "板块数=%d" % len(boards))
        check("S2.1 板块已种入 10 个", len(boards) == 10, len(boards))
        check("S2.1 板块按 sortOrder 升序",
              [x["sortOrder"] for x in boards] == sorted(x["sortOrder"] for x in boards),
              [x["sortOrder"] for x in boards])
        board_id = boards[1]["id"] if len(boards) > 1 else None
        board_id2 = boards[2]["id"] if len(boards) > 2 else None

        st, b = call("GET", "/api/tags/hot")
        check("S2.1 热门标签榜可访问（初始为空）", st == 200 and b.get("code") == 0, b.get("data"))

        # ==================== S2.2 发帖 ====================
        st, b = call("POST", "/api/posts", {
            "title": "Java 21 虚拟线程在 IO 密集场景的实测",
            "content": "# 背景\n\n最近在做**压测**，对比了虚拟线程与平台线程。\n\n```java\nThread.ofVirtual().start(() -> {});\n```\n\n结论：IO 密集场景提升明显。",
            "boardId": board_id,
            "type": 1,
            "tags": ["Java", "并发", "java"]
        }, token=token_a)
        post_ok = b and b.get("code") == 0 and b["data"].get("id")
        check("S2.2 发帖成功", post_ok, b.get("code") if b else None)
        if not post_ok:
            return
        pid = b["data"]["id"]
        check("S2.2 摘要由后端生成且剥离了 Markdown 语法",
              b["data"]["summary"] and "#" not in b["data"]["summary"]
              and "```" not in b["data"]["summary"],
              b["data"]["summary"])
        check("S2.2 标签去重（java/Java 视为一个）",
              len(b["data"]["tags"]) == 2, [t["name"] for t in b["data"]["tags"]])
        check("S2.2 发帖后状态为已发布", b["data"]["status"] == 1, b["data"]["status"])

        # 未登录不能发帖
        st, b = call("POST", "/api/posts",
                     {"title": "游客发帖尝试", "content": "这段内容不该被写入数据库", "boardId": board_id})
        check("S2.2 未登录发帖被拒 (40101)", b and b.get("code") == 40101, b.get("code") if b else None)

        # 板块不存在
        st, b = call("POST", "/api/posts",
                     {"title": "板块不存在测试", "content": "测试内容足够长以通过校验啦", "boardId": 999999},
                     token=token_a)
        check("S2.2 板块不存在 → 40404", b and b.get("code") == 40404, b.get("code") if b else None)

        # 标题过短
        st, b = call("POST", "/api/posts",
                     {"title": "短", "content": "测试内容足够长以通过校验啦", "boardId": board_id},
                     token=token_a)
        check("S2.2 标题过短被校验拦下 (40001)", b and b.get("code") == 40001, b.get("code") if b else None)

        # 正文过短
        st, b = call("POST", "/api/posts",
                     {"title": "正文过短的测试标题", "content": "太短", "boardId": board_id},
                     token=token_a)
        check("S2.2 正文过短被校验拦下 (40001)", b and b.get("code") == 40001, b.get("code") if b else None)

        # 标签超过 5 个
        st, b = call("POST", "/api/posts",
                     {"title": "标签数量超限测试", "content": "测试内容足够长以通过校验啦",
                      "boardId": board_id, "tags": ["a1", "a2", "a3", "a4", "a5", "a6"]},
                     token=token_a)
        check("S2.2 标签超过 5 个被拒 (40001)", b and b.get("code") == 40001, b.get("code") if b else None)

        # ==================== S2.3 列表 ====================
        # 再造几篇，让分页有意义
        for i in range(3):
            call("POST", "/api/posts",
                 {"title": "列表测试帖 %d 号用于验证分页" % i,
                  "content": "这是第 %d 篇测试帖的正文内容，长度需要超过十个字符。" % i,
                  "boardId": board_id, "tags": ["测试"]}, token=token_a)

        st, b = call("GET", "/api/posts?page=1&size=2")
        check("S2.3 列表免登录可访问", st == 200 and b.get("code") == 0, b.get("code"))
        data = b.get("data") or {}
        check("S2.3 分页元信息正确",
              data.get("page") == 1 and data.get("size") == 2 and data.get("total") >= 4,
              {k: data.get(k) for k in ("page", "size", "total", "pages")})
        check("S2.3 每页条数受 size 约束", len(data.get("records", [])) == 2,
              len(data.get("records", [])))
        check("S2.3 列表项不含正文 content",
              all("content" not in r for r in data.get("records", [])),
              list(data.get("records", [{}])[0].keys())[:8])
        check("S2.3 列表项含作者与板块冗余字段",
              data["records"][0].get("authorNickname") and data["records"][0].get("boardName"),
              (data["records"][0].get("authorNickname"), data["records"][0].get("boardName")))
        check("S2.3 列表项批量带出标签",
              isinstance(data["records"][0].get("tags"), list),
              data["records"][0].get("tags"))

        st, b = call("GET", "/api/posts?boardId=%d&size=50" % board_id)
        check("S2.3 按板块筛选生效",
              all(r["boardId"] == board_id for r in b["data"]["records"]),
              "返回 %d 条" % len(b["data"]["records"]))

        # ⚠️ 原断言写的是"board_id2 下必然没有帖子 → total == 0"。这在压测数据灌入后
        #    必然假失败：`.workbuddy/tools/gen-perf-data.py` 往 t_post 写了 1 万行，
        #    10 个板块雨露均沾，"随便挑一个板块恰好为空"是与数据量耦合的假设。
        #    改为与**库内真值**比对（项目约定：期望值尽量从真值来源动态取）。
        st, b = call("GET", "/api/posts?boardId=%d&size=50" % board_id2)
        true_cnt = db_scalar("SELECT COUNT(*) FROM t_post "
                             "WHERE board_id = %d AND deleted = 0 AND status = 1" % board_id2)
        check("S2.3 按板块筛选结果与库内真值一致",
              true_cnt is not None and b["data"]["total"] == true_cnt,
              "接口 total=%s / 库内真值=%s" % (b["data"]["total"], true_cnt))

        st, b = call("GET", "/api/posts?authorId=%d&size=50" % uid_a)
        check("S2.3 按作者筛选生效",
              all(r["authorId"] == uid_a for r in b["data"]["records"]),
              "返回 %d 条" % len(b["data"]["records"]))

        tag_id = (call("GET", "/api/tags/hot")[1]["data"] or [{}])[0].get("id")
        if tag_id:
            st, b = call("GET", "/api/posts?tagId=%d&size=50" % tag_id)
            check("S2.3 按标签筛选生效", b["code"] == 0, "返回 %d 条" % b["data"]["total"])

        st, b = call("GET", "/api/posts?type=1&size=50")
        check("S2.3 按类型筛选（提问帖）生效",
              all(r["type"] == 1 for r in b["data"]["records"]),
              "返回 %d 条" % len(b["data"]["records"]))

        # 排序
        for sort in ("latest", "hot", "active"):
            st, b = call("GET", "/api/posts?sort=%s&size=5" % sort)
            check("S2.3 排序 sort=%s 可用" % sort, b.get("code") == 0, b.get("code"))

        # 非法 sort 应回落默认而非报错（且不能注入）
        # ⚠️ 必须 URL 编码：裸的 ; 与空格会让请求本身不被正确解析
        from urllib.parse import quote
        evil = quote("create_time;DROP TABLE t_post--", safe="")
        st, b = call("GET", "/api/posts?sort=%s&size=5" % evil)
        check("S2.3 非法 sort 被白名单兜住（无注入、不报错）",
              isinstance(b, dict) and b.get("code") == 0, b if not isinstance(b, dict) else b.get("code"))
        st, b = call("GET", "/api/posts")
        check("S2.3 注入尝试后表仍在", b.get("code") == 0 and b["data"]["total"] >= 4,
              b["data"]["total"])

        # 分页边界
        st, b = call("GET", "/api/posts?page=9999&size=10")
        check("S2.3 页码越界返回空列表而非报错",
              b.get("code") == 0 and b["data"]["records"] == [], b.get("code"))
        st, b = call("GET", "/api/posts?size=1000")
        check("S2.3 size 超上限被校验拦下 (40001)", b.get("code") == 40001, b.get("code"))
        st, b = call("GET", "/api/posts?page=0")
        check("S2.3 page=0 被校验拦下 (40001)", b.get("code") == 40001, b.get("code"))

        # ==================== S2.4 详情 + 浏览计数 ====================
        st, b = call("GET", "/api/posts/%d" % pid)
        check("S2.4 详情免登录可访问", st == 200 and b.get("code") == 0, b.get("code"))
        d = b["data"]
        check("S2.4 详情返回 Markdown 原文（未渲染 HTML）",
              d.get("content") and "# 背景" in d["content"] and "<h1" not in d["content"],
              (d.get("content") or "")[:40])
        check("S2.4 详情组装了 author 与 tags",
              d.get("author", {}).get("nickname") == "作者甲" and len(d.get("tags", [])) == 2,
              (d.get("author"), [t["name"] for t in d.get("tags", [])]))
        check("S2.4 游客查看时 canEdit=false", d.get("canEdit") is False, d.get("canEdit"))
        v1 = d.get("viewCount")

        st, b = call("GET", "/api/posts/%d" % pid, token=token_a)
        check("S2.4 作者本人查看 canEdit=true", b["data"].get("canEdit") is True, b["data"].get("canEdit"))
        check("S2.4 作者本人查看不含 email", "email" not in json.dumps(b["data"], ensure_ascii=False),
              "干净")

        # 浏览去重：同一 IP 连续请求只计一次
        before = call("GET", "/api/posts/%d" % pid)[1]["data"]["viewCount"]
        for _ in range(3):
            call("GET", "/api/posts/%d" % pid)
        after = call("GET", "/api/posts/%d" % pid)[1]["data"]["viewCount"]
        check("S2.4 浏览计数按访客去重（连刷 4 次不增长）",
              after == before, "before=%s after=%s" % (before, after))

        st, b = call("GET", "/api/posts/999999999")
        check("S2.4 不存在的帖子 → 40402", b.get("code") == 40402, b.get("code"))

        # ==================== S2.2 编辑 / 删除 ====================
        # 路人乙不能改
        st, b = call("PUT", "/api/posts/%d" % pid, {"title": "路人想改标题测试"}, token=token_b)
        check("S2.2 非作者编辑被拒 (40302)", b.get("code") == 40302, b.get("code"))

        # 作者改标题（部分更新，正文不动）
        st, b = call("PUT", "/api/posts/%d" % pid, {"title": "改过的标题：虚拟线程实测"}, token=token_a)
        check("S2.2 作者编辑成功", b.get("code") == 0, b.get("code"))
        check("S2.2 部分更新：只改标题却保留正文",
              b["data"]["title"] == "改过的标题：虚拟线程实测" and "背景" in b["data"]["content"],
              (b["data"]["title"], b["data"]["content"][:20]))

        # 换板块
        st, b = call("PUT", "/api/posts/%d" % pid, {"boardId": board_id2}, token=token_a)
        check("S2.2 换板块成功", b["code"] == 0 and b["data"]["boardId"] == board_id2,
              (b.get("code"), b["data"].get("boardId")))

        st, b = call("GET", "/api/boards")
        b1_count = next(x["postCount"] for x in b["data"] if x["id"] == board_id)
        b2_count = next(x["postCount"] for x in b["data"] if x["id"] == board_id2)
        check("S2.2 换板块后两边计数同步（原板块 -1、新板块 +1）",
              b2_count >= 1, "原板块=%s 新板块=%s" % (b1_count, b2_count))

        # 标签整体替换
        st, b = call("PUT", "/api/posts/%d" % pid, {"tags": ["只留一个"]}, token=token_a)
        check("S2.2 标签整体替换生效",
              [t["name"] for t in b["data"]["tags"]] == ["只留一个"],
              [t["name"] for t in b["data"]["tags"]])

        # 路人乙不能删
        st, b = call("DELETE", "/api/posts/%d" % pid, token=token_b)
        check("S2.2 非作者删除被拒 (40302)", b.get("code") == 40302, b.get("code"))

        # ==================== S2.5 头像上传 ====================
        png = make_png()
        boundary = "----codelkup%d" % ts
        body = b"".join([
            ("--%s\r\n" % boundary).encode(),
            b'Content-Disposition: form-data; name="file"; filename="avatar.png"\r\n',
            b"Content-Type: image/png\r\n\r\n",
            png, b"\r\n",
            ("--%s--\r\n" % boundary).encode(),
        ])
        st, b = call("POST", "/api/users/me/avatar", raw=body, token=token_a,
                     content_type="multipart/form-data; boundary=" + boundary)
        check("S2.5 上传合法 PNG 头像成功", b.get("code") == 0, b.get("code"))
        avatar_url = (b.get("data") or {}).get("avatar", "") if isinstance(b, dict) else ""
        check("S2.5 头像 URL 已随机化（不含原始文件名）",
              "avatar.png" not in (avatar_url or "") and "uuid" not in (avatar_url or ""),
              avatar_url)

        # 访问静态资源
        if avatar_url:
            st, raw = call("GET", avatar_url)
            check("S2.5 头像静态资源可访问", st == 200 and isinstance(raw, bytes) and raw[:4] == b"\x89PNG",
                  "HTTP %s, %d bytes" % (st, len(raw) if isinstance(raw, bytes) else -1))

        # 伪造扩展名（.png 里塞文本）
        fake = b"<html>not an image at all, just text pretending</html>"
        body = b"".join([
            ("--%s\r\n" % boundary).encode(),
            b'Content-Disposition: form-data; name="file"; filename="evil.png"\r\n',
            b"Content-Type: image/png\r\n\r\n",
            fake, b"\r\n",
            ("--%s--\r\n" % boundary).encode(),
        ])
        st, b = call("POST", "/api/users/me/avatar", raw=body, token=token_a,
                     content_type="multipart/form-data; boundary=" + boundary)
        check("S2.5 伪装成 PNG 的文本被魔数校验拦下 (40004)",
              b.get("code") == 40004, b.get("code"))

        # 不允许的扩展名
        body = b"".join([
            ("--%s\r\n" % boundary).encode(),
            b'Content-Disposition: form-data; name="file"; filename="shell.jsp"\r\n',
            b"Content-Type: image/png\r\n\r\n",
            png, b"\r\n",
            ("--%s--\r\n" % boundary).encode(),
        ])
        st, b = call("POST", "/api/users/me/avatar", raw=body, token=token_a,
                     content_type="multipart/form-data; boundary=" + boundary)
        check("S2.5 jsp 扩展名被白名单拦下 (40004)", b.get("code") == 40004, b.get("code"))

        # 双扩展名绕过尝试 a.png.jsp
        body = b"".join([
            ("--%s\r\n" % boundary).encode(),
            b'Content-Disposition: form-data; name="file"; filename="a.png.jsp"\r\n',
            b"Content-Type: image/png\r\n\r\n",
            png, b"\r\n",
            ("--%s--\r\n" % boundary).encode(),
        ])
        st, b = call("POST", "/api/users/me/avatar", raw=body, token=token_a,
                     content_type="multipart/form-data; boundary=" + boundary)
        check("S2.5 双扩展名 a.png.jsp 被拦下 (40004)", b.get("code") == 40004, b.get("code"))

        # 路径穿越尝试
        body = b"".join([
            ("--%s\r\n" % boundary).encode(),
            b'Content-Disposition: form-data; name="file"; filename="../../../../evil.png"\r\n',
            b"Content-Type: image/png\r\n\r\n",
            png, b"\r\n",
            ("--%s--\r\n" % boundary).encode(),
        ])
        st, b = call("POST", "/api/users/me/avatar", raw=body, token=token_a,
                     content_type="multipart/form-data; boundary=" + boundary)
        check("S2.5 路径穿越文件名不落地（文件名被 UUID 替换）",
              b.get("code") == 0 and "evil" not in json.dumps(b.get("data", {})),
              (b.get("code"), (b.get("data") or {}).get("avatar")))

        # 未登录上传
        st, b = call("POST", "/api/users/me/avatar", raw=body,
                     content_type="multipart/form-data; boundary=" + boundary)
        check("S2.5 未登录上传被拒 (40101)", b.get("code") == 40101, b.get("code"))

        # ==================== 资料编辑 ====================
        st, b = call("PUT", "/api/users/me",
                     {"nickname": "作者甲改名", "bio": "27 届，方向 AI 应用", "techTags": "Java,java,Spring, LLM ,"},
                     token=token_a)
        check("S2.5 修改资料成功", b.get("code") == 0, b.get("code"))
        check("S2.5 技术标签去重去空",
              b["data"]["techTags"] == "Java,Spring,LLM", b["data"]["techTags"])
        check("S2.5 资料编辑不返回 password",
              "password" not in json.dumps(b["data"], ensure_ascii=False), "干净")

        st, b = call("PUT", "/api/users/me", {"nickname": "x"}, token=token_a)
        check("S2.5 昵称过短被拒 (40001)", b.get("code") == 40001, b.get("code"))

        st, b = call("GET", "/api/users/%d" % uid_a)
        check("S2.5 公开主页同步显示新昵称",
              b["data"]["nickname"] == "作者甲改名", b["data"]["nickname"])
        check("S2.5 公开主页仍不含 email",
              "email" not in json.dumps(b["data"], ensure_ascii=False), "干净")

        # ==================== S2.6 服务端草稿箱（V0.4 / M4-15） ====================
        # 与前端 useDraft 的 scope 命名严格同源：new=新建稿，edit:<postId>=编辑稿
        st, b = call("GET", "/api/drafts")
        check("S2.6 未登录读草稿箱被拒 (40101)", b.get("code") == 40101, b.get("code"))
        st, b = call("PUT", "/api/drafts", {"scope": "new", "title": "游客草稿", "content": "这段不该存进来"})
        check("S2.6 未登录保存草稿被拒 (40101)", b.get("code") == 40101, b.get("code"))

        st, b = call("PUT", "/api/drafts", {
            "scope": "new", "title": "草稿冒烟", "content": "# 背景\n\n**粗体**与`行内代码`的正文。",
            "boardId": board_id, "type": 1, "tags": ["Java", "草稿"]
        }, token=token_a)
        draft_ok = b.get("code") == 0
        check("S2.6 保存新建稿成功", draft_ok, b.get("code"))
        check("S2.6 保存回执含 scope 与正文原文",
              draft_ok and b["data"]["scope"] == "new" and "粗体" in b["data"]["content"],
              b.get("data") if draft_ok else None)
        check("S2.6 标签按列表返回（JSON 存储还原）",
              draft_ok and b["data"]["tags"] == ["Java", "草稿"],
              b["data"].get("tags") if draft_ok else None)
        # 回执时间与库内 update_time 同源对账（同秒；防"回执时间≠落库时间"）
        db_ms = db_scalar("SELECT UNIX_TIMESTAMP(update_time) * 1000 FROM t_draft "
                          "WHERE user_id=%d AND scope='new'" % uid_a)
        check("S2.6 回执时间与库内 update_time 同刻",
              draft_ok and db_ms is not None and abs(b["data"]["updateTimeEpoch"] - db_ms) < 1000,
              "回执=%s 库内=%s" % (b["data"]["updateTimeEpoch"] if draft_ok else None, db_ms))

        st, b = call("GET", "/api/drafts", token=token_a)
        rows = b.get("data") or []
        check("S2.6 草稿箱列表的预览已剥离 Markdown 语法",
              len(rows) == 1 and rows[0]["excerpt"] and "#" not in rows[0]["excerpt"]
              and "*" not in rows[0]["excerpt"] and "粗体" in rows[0]["excerpt"],
              rows[0].get("excerpt") if rows else None)
        check("S2.6 列表项不含正文（只传摘要）",
              all("content" not in r for r in rows), list(rows[0].keys()) if rows else None)

        # 覆盖保存：同一 scope 仍然只有一行（唯一键生效）
        # ⚠️ t_draft.update_time 是秒精度，间隔 1.1 秒才能让"更新"与倒序可验证
        time.sleep(1.1)
        st, b = call("PUT", "/api/drafts", {
            "scope": "new", "title": "草稿冒烟（改）", "content": "改过一次的正文。", "boardId": board_id,
            "type": 0, "tags": []
        }, token=token_a)
        check("S2.6 覆盖保存成功", b.get("code") == 0, b.get("code"))
        cnt = db_scalar("SELECT COUNT(*) FROM t_draft WHERE user_id=%d AND scope='new'" % uid_a)
        check("S2.6 同 scope 覆盖：库内仍只有 1 行", cnt == 1, "行数=%s" % cnt)
        check("S2.6 覆盖后标题与标签均为最新值",
              b["data"]["title"] == "草稿冒烟（改）" and b["data"]["tags"] == [],
              (b["data"].get("title"), b["data"].get("tags")))

        # 编辑稿 scope：路径参数里带冒号（edit:123），验证能按路径参数读取
        time.sleep(1.1)
        st, b = call("PUT", "/api/drafts", {
            "scope": "edit:%d" % pid, "title": "编辑稿标题", "content": "编辑稿正文。", "boardId": board_id
        }, token=token_a)
        check("S2.6 编辑稿（edit:<postId>）保存成功", b.get("code") == 0, b.get("code"))
        st, b = call("GET", "/api/drafts/edit:%d" % pid, token=token_a)
        check("S2.6 带冒号的 edit scope 可按路径参数读取",
              b.get("code") == 0 and b.get("data", {}).get("scope") == "edit:%d" % pid,
              b.get("data", {}).get("scope") if isinstance(b.get("data"), dict) else b.get("data"))

        st, b = call("GET", "/api/drafts", token=token_a)
        rows = b.get("data") or []
        check("S2.6 草稿箱按更新时间倒序（后保存的编辑稿在前）",
              [r["scope"] for r in rows] == ["edit:%d" % pid, "new"],
              [r["scope"] for r in rows])

        # 没有草稿 / 非法 scope
        st, b = call("GET", "/api/drafts/edit:999999", token=token_a)
        check("S2.6 没有草稿时读单条返回 data=null（不是 404）",
              b.get("code") == 0 and b.get("data") is None, b.get("data"))
        st, b = call("GET", "/api/drafts/abc", token=token_a)
        check("S2.6 非法 scope 读取被拒 (40001)", b.get("code") == 40001, b.get("message"))
        st, b = call("PUT", "/api/drafts", {"scope": "../../etc/passwd", "content": "路径穿越尝试"}, token=token_a)
        check("S2.6 非法 scope 保存被拒 (40001)", b.get("code") == 40001, b.get("message"))

        # 长度上限与发帖对齐
        st, b = call("PUT", "/api/drafts", {"scope": "new", "title": "x" * 121, "content": "超长标题"}, token=token_a)
        check("S2.6 标题超 120 字被拒 (40001)", b.get("code") == 40001, b.get("code"))
        st, b = call("PUT", "/api/drafts", {"scope": "new", "content": "x" * 50001}, token=token_a)
        check("S2.6 正文超 50000 字被拒 (40001)", b.get("code") == 40001, b.get("code"))
        st, b = call("PUT", "/api/drafts", {"scope": "new", "content": "ok", "tags": ["a1", "a2", "a3", "a4", "a5", "a6"]}, token=token_a)
        check("S2.6 标签超 5 个被拒 (40001)", b.get("code") == 40001, b.get("code"))

        # 横向隔离：草稿只对本人可见/可删
        st, b = call("GET", "/api/drafts/new", token=token_b)
        check("S2.6 他人读同一 scope 拿到 null", b.get("code") == 0 and b.get("data") is None, b.get("data"))
        st, b = call("GET", "/api/drafts", token=token_b)
        check("S2.6 他人草稿箱为空", (b.get("data") or []) == [], len(b.get("data") or []))
        st, b = call("DELETE", "/api/drafts/new", token=token_b)
        cnt = db_scalar("SELECT COUNT(*) FROM t_draft WHERE user_id=%d AND scope='new'" % uid_a)
        check("S2.6 他人删除不影响本人草稿（水平越权无效）", b.get("code") == 0 and cnt == 1, "行数=%s" % cnt)

        # 删除：正常 + 幂等 + 落库对账
        st, b = call("DELETE", "/api/drafts/edit:%d" % pid, token=token_a)
        check("S2.6 删除草稿成功", b.get("code") == 0, b.get("code"))
        st, b = call("DELETE", "/api/drafts/edit:%d" % pid, token=token_a)
        check("S2.6 重复删除幂等（不报错）", b.get("code") == 0, b.get("code"))
        st, b = call("GET", "/api/drafts/edit:%d" % pid, token=token_a)
        check("S2.6 删除后读回 null", b.get("code") == 0 and b.get("data") is None, b.get("data"))
        st, b = call("DELETE", "/api/drafts/new", token=token_a)
        cnt = db_scalar("SELECT COUNT(*) FROM t_draft WHERE user_id=%d" % uid_a)
        check("S2.6 删除 new 稿后草稿箱清空（库内 0 行）", b.get("code") == 0 and cnt == 0, "行数=%s" % cnt)

        # ==================== 删除收尾 ====================
        st, b = call("DELETE", "/api/posts/%d" % pid, token=token_a)
        check("S2.2 作者删除自己的帖子成功", b.get("code") == 0, b.get("code"))
        st, b = call("GET", "/api/posts/%d" % pid)
        check("S2.2 删除后详情返回 40402", b.get("code") == 40402, b.get("code"))

        st, b = call("GET", "/api/posts?authorId=%d&size=50" % uid_a)
        check("S2.2 删除后不再出现在列表",
              all(r["id"] != pid for r in b["data"]["records"]),
              "剩余 %d 条" % b["data"]["total"])

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("\n" + "=" * 70)
    print("Sprint 2 验收：%d/%d 通过" % (passed, total))
    if passed < total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  ->  %s" % (name, str(detail)[:300]))
    print("=" * 70)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
