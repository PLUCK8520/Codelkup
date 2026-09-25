#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Sprint 4 检索功能冒烟测试（开发期快速验证，非最终验收）。

只覆盖 SearchService 的三项实测得出的防御逻辑：
  1. 正常中文词走 ngram 全文索引
  2. 单字降级为 LIKE
  3. BOOLEAN 操作符剥离（防语义漂移）
  4. snippet 生成（关键词在正文深处时也能拿到上下文）

最终验收见 verify-sprint4.py。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://127.0.0.1:8085"
JAR_DIR = r"C:\Users\32519\Desktop\Codelkup\codelkup\codelkup-backend"
JAR = os.path.join(JAR_DIR, "target", "codelkup-backend-0.0.1-SNAPSHOT.jar")

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
results = []


def call(method, path, body=None, token=None):
    url = BASE + path
    data = None
    hdrs = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        hdrs["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with _opener.open(req, timeout=20) as resp:
            raw = resp.read()
            try:
                return resp.status, json.loads(raw.decode("utf-8"))
            except Exception:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))
    except Exception as e:
        return -1, str(e)


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("[PASS] " if ok else "[FAIL] ") + name + ("  ->  " + str(detail)[:300] if detail else ""))


def wait_ready(timeout=180):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st, b = call("GET", "/api/system/health")
        if st == 200 and isinstance(b, dict) and b.get("code") == 0:
            return True, time.time() - t0
        time.sleep(1.5)
    return False, time.time() - t0


def search(kw, **extra):
    qs = "keyword=" + quote(kw, safe="")
    for k, v in extra.items():
        qs += "&%s=%s" % (k, quote(str(v), safe=""))
    st, b = call("GET", "/api/search/posts?" + qs)
    return b


def main():
    env = dict(os.environ)
    env.pop("SERVER__PORT", None)
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"

    log = open("smoke-search.log", "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(["java", "-jar", JAR], env=env, cwd=JAR_DIR,
                            stdout=log, stderr=subprocess.STDOUT)
    print("启动中 pid=%d ..." % proc.pid)
    try:
        ready, secs = wait_ready()
        check("应用启动（触发 V4 迁移）", ready, "%.1fs" % secs)
        if not ready:
            print(open("smoke-search.log", encoding="utf-8", errors="replace").read()[-4000:])
            return

        ts = int(time.time())
        st, b = call("POST", "/api/auth/register",
                     {"email": "smoke_%d@test.com" % ts, "password": "abc12345", "nickname": "冒烟用户"})
        token = b["data"]["accessToken"]
        st, b = call("GET", "/api/boards")
        board_id = b["data"][0]["id"]

        # 一个有特征词的帖子：关键词埋在正文极深处（> 摘要的 200 字上限），
        # 强制走 snippet 的慢路径 —— 若关键词只在开头，会命中"快路径返回摘要"，
        # 慢路径（剥离 Markdown + 定位 + 居中截取）就完全没被验证到。
        deep = ("开头是一段无关的铺垫内容。" * 30
                + "这里才是 quokka 关键字所在的位置。"
                + "结尾补充说明。" * 30)
        st, b = call("POST", "/api/posts", {
            "title": "冒烟测试：检索关键字位于正文深处",
            "content": deep, "boardId": board_id, "tags": ["冒烟"]}, token=token)
        pid = b["data"]["id"]
        check("造数据：发一篇关键词在正文深处的帖子", bool(pid), b.get("code") if isinstance(b, dict) else b)

        # ---- 1. 正常英文/中文词 ----
        r = search("quokka")
        check("正常词命中（ngram 全文索引）", r["code"] == 0 and r["data"]["total"] >= 1,
              "total=%s" % r["data"]["total"])

        # ---- 2. snippet 必须包含命中词（且来自正文深处）----
        rec = next((x for x in r["data"]["records"] if x["id"] == pid), None)
        check("snippet 非空且包含关键词", rec and rec.get("snippet") and "quokka" in rec["snippet"],
              (rec or {}).get("snippet", "")[:80])
        check("snippet 是纯文本（不含 HTML 高亮标签）",
              rec and "<mark" not in (rec.get("snippet") or "") and "<em" not in (rec.get("snippet") or ""),
              (rec or {}).get("snippet", "")[:50])
        check("snippet 走慢路径：两侧都有省略号（说明是居中截取而非取开头）",
              rec and (rec.get("snippet") or "").startswith("…") and (rec.get("snippet") or "").endswith("…"),
              (rec or {}).get("snippet", "")[:60] + " ... " + (rec or {}).get("snippet", "")[-30:])
        check("响应体不含正文 content 字段（不泄露大字段）",
              rec is not None and "content" not in rec, list(rec.keys()) if rec else None)

        # ---- 3. 单字降级 LIKE ----
        r1 = search("帖")
        check("单字查询降级为 LIKE 后有结果", r1["code"] == 0 and r1["data"]["total"] >= 1,
              "total=%s" % r1["data"]["total"])

        # ---- 4. BOOLEAN 操作符剥离 ----
        r_base = search("冒烟")
        base_total = r_base["data"]["total"]
        r_neg = search("-冒烟")
        check("前缀 '-' 被剥离（不再变成排除语义）",
              r_neg["data"]["total"] == base_total,
              "'-冒烟'=%s vs '冒烟'=%s" % (r_neg["data"]["total"], base_total))
        r_plus = search("+冒烟")
        check("前缀 '+' 被剥离（不再变成必须包含）",
              r_plus["data"]["total"] == base_total,
              "'+冒烟'=%s vs '冒烟'=%s" % (r_plus["data"]["total"], base_total))

        # 纯操作符 → 清洗后为空 → 明确返回空结果而非全表
        r_only = search("+++")
        check("纯操作符输入返回空结果（不返回全表）",
              r_only["code"] == 0 and r_only["data"]["total"] == 0,
              "total=%s" % r_only["data"]["total"])

        # LIKE 通配符转义：% 不应匹配全表
        r_pct = search("%")
        check("'%' 被转义（不匹配全表）",
              r_pct["code"] == 0 and r_pct["data"]["total"] == 0,
              "total=%s" % r_pct["data"]["total"])

        # ---- 5. 参数校验 ----
        st, b = call("GET", "/api/search/posts?keyword=")
        check("空关键词被校验拦下 (40001)", b.get("code") == 40001, b.get("code") if isinstance(b, dict) else b)

        # ---- 6. 排序白名单 & 分页 ----
        r = search("冒烟", sort="latest")
        check("sort=latest 可用", r["code"] == 0, r.get("code"))
        r = search("冒烟", sort="'; DROP TABLE t_post--")
        check("非法 sort 被白名单兜住", r["code"] == 0, r.get("code") if isinstance(r, dict) else r)
        r = search("冒烟", size=999)
        check("size 超上限被拦下 (40001)", r.get("code") == 40001, r.get("code"))

        # ---- 7. V4 建表 ----
        r = search("冒烟")
        check("搜索接口在 V4 迁移后正常工作", r["code"] == 0, r.get("code"))

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("\n" + "=" * 60)
    print("检索冒烟：%d/%d 通过" % (passed, total))
    if passed < total:
        for name, ok, detail in results:
            if not ok:
                print("  - [FAIL] %s -> %s" % (name, str(detail)[:200]))
    print("=" * 60)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
