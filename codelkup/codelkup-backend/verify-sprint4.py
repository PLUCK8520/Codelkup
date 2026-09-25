#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Sprint 4 端到端验收脚本（检索与发现）。

覆盖：
  S4.0 V4 迁移（t_user_follow / t_notification）
  S4.1 全文检索（ngram + 输入清洗 + 单字降级 + snippet）
  S4.3 关注/粉丝 + 关注流（拉模式）
  S4.4 站内通知 + 未读数

单进程：启动 jar -> 等待就绪 -> 逐项断言 -> 关闭。
通知是 @Async 落库的，所以涉及通知的断言一律走 wait_until 轮询，
不能假设"发完请求通知立刻就在"。这是本脚本最容易写成 flaky 的地方。
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
MYSQL = r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "codelkup"
DB_ARGS = ["-h127.0.0.1", "-ucodelkup", "-pchange-me-dev-password", DB, "-N", "-B"]

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
results = []

# 通知类型（与 Notification.java 保持一致）
N_COMMENT, N_REPLY, N_LIKE, N_FOLLOW, N_ACCEPT = 1, 2, 3, 4, 5
T_POST = 1


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
        with _opener.open(req, timeout=25) as resp:
            raw = resp.read()
            try:
                return resp.status, json.loads(raw.decode("utf-8"))
            except Exception:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode("utf-8"))
        except Exception:
            return e.code, raw
    except Exception as e:
        return -1, str(e)


def db(sql):
    try:
        p = subprocess.run([MYSQL, "--default-character-set=utf8mb4"] + DB_ARGS + ["-e", sql],
                           capture_output=True, timeout=25)
        if p.returncode != 0:
            return None
        out = p.stdout.decode("utf-8", "replace").strip()
        return [ln.split("\t") for ln in out.splitlines()] if out else []
    except Exception:
        return None


def db_scalar(sql, default=None):
    rows = db(sql)
    if not rows or not rows[0]:
        return default
    try:
        return int(rows[0][0])
    except (ValueError, TypeError):
        return default


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("[PASS] " if ok else "[FAIL] ") + name + ("  ->  " + str(detail)[:280] if detail else ""))


def wait_ready(timeout=180):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st, b = call("GET", "/api/system/health")
        if st == 200 and isinstance(b, dict) and b.get("code") == 0:
            return True, time.time() - t0
        time.sleep(1.5)
    return False, time.time() - t0


def wait_until(fn, timeout=15, interval=0.4):
    """轮询直到 fn 返回真值。用于等待异步落库的通知。"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = fn()
        if v:
            return v
        time.sleep(interval)
    return None


def search(kw, token=None, **extra):
    qs = "keyword=" + quote(kw, safe="")
    for k, v in extra.items():
        qs += "&%s=%s" % (k, quote(str(v), safe=""))
    return call("GET", "/api/search/posts?" + qs, token=token)[1]


def notifications(token, **extra):
    qs = "&".join("%s=%s" % (k, v) for k, v in extra.items())
    return call("GET", "/api/notifications" + ("?" + qs if qs else ""), token=token)[1]


def unread(token):
    return call("GET", "/api/notifications/unread-count", token=token)[1]["data"]


def notify_count_of(receiver_id, ntype=None):
    """直连数据库数某人的通知条数（比接口更直接，用于对账）"""
    sql = "SELECT COUNT(*) FROM t_notification WHERE receiver_id=%d" % receiver_id
    if ntype is not None:
        sql += " AND type=%d" % ntype
    return db_scalar(sql, -1)


def main():
    env = dict(os.environ)
    env.pop("SERVER__PORT", None)
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"

    log = open("verify-s4.log", "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(["java", "-jar", JAR], env=env, cwd=JAR_DIR,
                            stdout=log, stderr=subprocess.STDOUT)
    print("启动中 pid=%d ..." % proc.pid)
    try:
        ready, secs = wait_ready()
        check("应用启动 + 健康检查", ready, "耗时 %.1fs" % secs)
        if not ready:
            print(open("verify-s4.log", encoding="utf-8", errors="replace").read()[-5000:])
            return

        # ==================== S4.0 V4 迁移 ====================
        check("V4 迁移：t_user_follow 已建表", bool(db("SHOW TABLES LIKE 't\\_user\\_follow'")),
              db("SHOW TABLES LIKE 't\\_user\\_follow'"))
        check("V4 迁移：t_notification 已建表", bool(db("SHOW TABLES LIKE 't\\_notification'")),
              db("SHOW TABLES LIKE 't\\_notification'"))
        idx = db("SHOW INDEX FROM t_user_follow WHERE Key_name='uk_follow'")
        check("V4 迁移：唯一索引 uk_follow 存在（关注防重的依据）", bool(idx),
              idx[0][2:5] if idx else None)
        idx = db("SHOW INDEX FROM t_notification WHERE Key_name='idx_receiver_read'")
        check("V4 迁移：idx_receiver_read 存在（支撑未读数）", bool(idx),
              idx[0][2:5] if idx else None)

        # ==================== 准备用户 ====================
        ts = int(time.time())
        users = {}
        for key, nick in (("A", "楼主阿甲"), ("B", "互动阿乙"), ("C", "路人阿丙")):
            st, b = call("POST", "/api/auth/register",
                         {"email": "s4%s_%d@test.com" % (key.lower(), ts),
                          "password": "abc12345", "nickname": nick})
            users[key] = (b["data"]["accessToken"], b["data"]["user"]["id"])
        tk_a, uid_a = users["A"]
        tk_b, uid_b = users["B"]
        tk_c, uid_c = users["C"]
        check("准备三个用户", uid_a and uid_b and uid_c, (uid_a, uid_b, uid_c))

        st, b = call("GET", "/api/boards")
        board_id = b["data"][0]["id"]

        # 一篇提问帖（供采纳用），正文里埋一个只出现在正文深处的特征词
        deep_body = ("前情提要。" * 40) + "这里藏着 xyzzy 这个罕见词。" + ("后记补充。" * 40)
        st, b = call("POST", "/api/posts", {
            "title": "Sprint4 验收提问帖：检索与通知",
            "content": deep_body, "boardId": board_id, "type": 1,
            "tags": ["验收", "检索"]}, token=tk_a)
        pid = b["data"]["id"]
        check("发帖（提问帖）成功", bool(pid), b.get("code") if isinstance(b, dict) else b)
        if not pid:
            return

        # ==================== S4.1 全文检索 ====================
        r = search("xyzzy", token=tk_b)
        check("S4.1 中文/英文关键词命中正文（ngram 全文索引）",
              r["code"] == 0 and r["data"]["total"] >= 1, "total=%s" % r["data"]["total"])

        rec = next((x for x in r["data"]["records"] if x["id"] == pid), None)
        check("S4.1 snippet 包含关键词且来自正文深处",
              rec and rec.get("snippet") and "xyzzy" in rec["snippet"],
              (rec or {}).get("snippet", "")[:70])
        check("S4.1 snippet 两侧带省略号（居中截取而非取开头）",
              rec and (rec.get("snippet") or "").startswith("…") and (rec.get("snippet") or "").endswith("…"),
              (rec or {}).get("snippet", "")[:40])
        check("S4.1 snippet 是纯文本（后端不产生高亮 HTML）",
              rec and "<mark" not in (rec.get("snippet") or "") and "<em" not in (rec.get("snippet") or ""),
              "clean")
        check("S4.1 搜索响应不含正文 content（不泄露大字段）",
              rec is not None and "content" not in rec, list(rec.keys())[:6] if rec else None)
        check("S4.1 搜索结果带 liked/collected 互动状态（登录用户）",
              rec is not None and rec.get("liked") is False, repr(rec.get("liked")) if rec else None)

        # 游客搜索 → liked 为 null（键必须存在）
        r_guest = search("xyzzy")
        rec_g = next((x for x in r_guest["data"]["records"] if x["id"] == pid), None)
        check("S4.1 游客搜索：liked 键存在且为 null",
              rec_g is not None and "liked" in rec_g and rec_g["liked"] is None,
              repr(rec_g.get("liked")) if rec_g else None)

        # 单字降级
        r1 = search("帖")
        check("S4.1 单字查询降级为 LIKE（ngram_token_size=2 搜不到单字）",
              r1["code"] == 0 and r1["data"]["total"] >= 1, "total=%s" % r1["data"]["total"])

        # BOOLEAN 操作符清洗 —— 这是"搜索看起来坏了"的根源
        base = search("验收")["data"]["total"]
        check("S4.1 前置：'验收' 有命中", base >= 1, base)
        check("S4.1 '-' 前缀被剥离（不再变成排除语义）",
              search("-验收")["data"]["total"] == base,
              "'-验收'=%s vs '验收'=%s" % (search("-验收")["data"]["total"], base))
        check("S4.1 '+' 前缀被剥离（不再变成必须包含）",
              search("+验收")["data"]["total"] == base,
              "'+验收'=%s vs '验收'=%s" % (search("+验收")["data"]["total"], base))
        check("S4.1 '~' '>' '(' 等操作符被剥离",
              search("~验收")["data"]["total"] == base
              and search("验收>")["data"]["total"] == base,
              "'~验收'=%s '验收>'=%s" % (search("~验收")["data"]["total"], search("验收>")["data"]["total"]))
        check("S4.1 纯操作符输入返回空结果而非全表",
              search("+++")["data"]["total"] == 0, search("+++")["data"]["total"])

        # LIKE 通配符转义（单字才走 LIKE，这里用 "%%" 验证不匹配全表）
        check("S4.1 LIKE 通配符 '%' 被转义（不返回全表）",
              search("%")["data"]["total"] == 0, search("%")["data"]["total"])

        # 相关性排序 & 注入防护
        check("S4.1 默认按相关性排序可用", search("验收", sort="relevance")["code"] == 0, "")
        check("S4.1 sort=latest 可用", search("验收", sort="latest")["code"] == 0, "")
        r_evil = search("验收", sort="'; DROP TABLE t_post--")
        check("S4.1 非法 sort 被白名单兜住（无注入、不报错）", r_evil["code"] == 0, r_evil.get("code"))
        check("S4.1 注入尝试后 t_post 表仍在",
              call("GET", "/api/posts?size=1")[1]["code"] == 0, "表完好")

        # 分页与校验
        r_p = search("验收", page=1, size=2)
        check("S4.1 分页参数生效", r_p["code"] == 0 and r_p["data"]["size"] == 2,
              {k: r_p["data"].get(k) for k in ("page", "size", "total")})
        check("S4.1 size 超上限被拦下 (40001)", search("验收", size=999).get("code") == 40001,
              search("验收", size=999).get("code"))
        st, b = call("GET", "/api/search/posts?keyword=")
        check("S4.1 空关键词被校验拦下 (40001)", b.get("code") == 40001, b.get("code"))
        st, b = call("GET", "/api/search/posts?keyword=" + quote("很长的词" * 20, safe=""))
        check("S4.1 超长关键词被拦下 (40001)", b.get("code") == 40001, b.get("code"))

        # 筛选
        r_f = search("验收", boardId=board_id)
        check("S4.1 按板块筛选生效",
              r_f["code"] == 0 and all(x["boardId"] == board_id for x in r_f["data"]["records"]),
              "total=%s" % r_f["data"]["total"])
        r_f = search("验收", authorId=uid_a)
        check("S4.1 按作者筛选生效",
              r_f["code"] == 0 and all(x["authorId"] == uid_a for x in r_f["data"]["records"]),
              "total=%s" % r_f["data"]["total"])

        # ==================== S4.3 关注 ====================
        st, b = call("POST", "/api/users/%d/follow" % uid_a, token=tk_b)
        check("S4.3 B 关注 A 成功", b.get("code") == 0 and b["data"]["following"] is True,
              b.get("data") if isinstance(b, dict) else b)
        check("S4.3 A 的粉丝数为 1", b["data"]["followerCount"] == 1, b["data"].get("followerCount"))
        # ⚠️ follow 接口返回的是【目标用户 A】的计数，不是操作者 B 的。
        #    想验 B 自己的关注数必须另查 B 的 follow-state —— 这里踩过一次坑，
        #    把 followeeCount 当成了"操作者的关注数"。
        st2, b2 = call("GET", "/api/users/%d/follow-state" % uid_b, token=tk_b)
        check("S4.3 B 自己的关注数为 1（查 B 的 follow-state）",
              b2["data"]["followeeCount"] == 1, b2["data"].get("followeeCount"))

        st, b = call("POST", "/api/users/%d/follow" % uid_a, token=tk_b)
        check("S4.3 重复关注幂等（不报错、计数仍为 1）",
              b.get("code") == 0 and b["data"]["followerCount"] == 1,
              (b.get("code"), b["data"].get("followerCount")))
        cnt = db_scalar("SELECT COUNT(*) FROM t_user_follow WHERE follower_id=%d AND followee_id=%d"
                        % (uid_b, uid_a))
        check("S4.3 数据库层：t_user_follow 只有 1 行（唯一索引真正防住）", cnt == 1, cnt)

        st, b = call("POST", "/api/users/%d/follow" % uid_b, token=tk_b)
        check("S4.3 关注自己被拒 (40908)", b.get("code") == 40908, b.get("code"))
        st, b = call("POST", "/api/users/999999999/follow", token=tk_b)
        check("S4.3 关注不存在的用户 → 40401", b.get("code") == 40401, b.get("code"))
        st, b = call("POST", "/api/users/%d/follow" % uid_a)
        check("S4.3 未登录关注被拒 (40101)", b.get("code") == 40101, b.get("code"))

        # 关注状态（游客也可见计数）
        st, b = call("GET", "/api/users/%d/follow-state" % uid_a, token=tk_b)
        check("S4.3 关注状态：B 视角 following=true",
              b["code"] == 0 and b["data"]["following"] is True, b["data"])
        st, b = call("GET", "/api/users/%d/follow-state" % uid_a, token=tk_c)
        check("S4.3 关注状态：C 视角 following=false（登录但没关注）",
              b["code"] == 0 and b["data"]["following"] is False, b["data"])
        st, b = call("GET", "/api/users/%d/follow-state" % uid_a)
        check("S4.3 关注状态：游客 following=null（键存在）",
              b["code"] == 0 and "following" in b["data"] and b["data"]["following"] is None,
              b["data"])

        # 粉丝/关注列表
        st, b = call("GET", "/api/users/%d/followers" % uid_a)
        check("S4.3 粉丝列表：A 有 1 个粉丝（B）",
              b["code"] == 0 and b["data"]["total"] == 1
              and b["data"]["records"][0]["id"] == uid_b,
              [x["id"] for x in b["data"]["records"]])
        check("S4.3 粉丝列表：游客视角 following=null（键存在）",
              "following" in b["data"]["records"][0]
              and b["data"]["records"][0]["following"] is None,
              repr(b["data"]["records"][0].get("following")))
        st, b = call("GET", "/api/users/%d/followers" % uid_a, token=tk_c)
        check("S4.3 粉丝列表：C 视角 following=false",
              b["data"]["records"][0]["following"] is False,
              repr(b["data"]["records"][0].get("following")))
        st, b = call("GET", "/api/users/%d/followees" % uid_b)
        check("S4.3 关注列表：B 关注了 1 人（A）",
              b["code"] == 0 and b["data"]["total"] == 1
              and b["data"]["records"][0]["id"] == uid_a,
              [x["id"] for x in b["data"]["records"]])
        st, b = call("GET", "/api/users/999999999/followers")
        check("S4.3 不存在用户的粉丝列表 → 40401", b.get("code") == 40401, b.get("code"))

        # ==================== S4.3 关注流（拉模式）====================
        st, b = call("GET", "/api/feed/following", token=tk_b)
        check("S4.3 B 的关注流包含 A 的帖子",
              b["code"] == 0 and any(x["id"] == pid for x in b["data"]["records"]),
              "total=%s" % b["data"]["total"])
        check("S4.3 关注流只含关注者的帖子（不含 C 的）",
              all(x["authorId"] == uid_a for x in b["data"]["records"]),
              sorted({x["authorId"] for x in b["data"]["records"]}))
        st, b = call("GET", "/api/feed/following", token=tk_c)
        check("S4.3 C 没关注任何人 → 关注流为空列表且不报错（关键边界：IN () 语法错误）",
              b["code"] == 0 and b["data"]["total"] == 0 and b["data"]["records"] == [],
              (b.get("code"), b["data"].get("total") if isinstance(b["data"], dict) else b.get("data")))
        st, b = call("GET", "/api/feed/following")
        check("S4.3 未登录访问关注流被拒 (40101)", b.get("code") == 40101, b.get("code"))

        # ==================== S4.4 通知 ====================
        # 清空历史：A/B/C 目前只有"B 关注 A"这一条通知（发给 A）
        wait_until(lambda: notify_count_of(uid_a, N_FOLLOW) >= 1)
        check("S4.4 被关注触发通知（B 关注 A → A 收到 FOLLOW）",
              notify_count_of(uid_a, N_FOLLOW) == 1, notify_count_of(uid_a, N_FOLLOW))

        n_a0 = notify_count_of(uid_a)

        # B 评论 A 的帖子 → A 收到 COMMENT
        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "B 的一级评论：这个设计我有个疑问。"}, token=tk_b)
        cmt_b = b["data"]["id"]
        check("S4.4 前置：B 发表一级评论成功", bool(cmt_b), b.get("code"))
        wait_until(lambda: notify_count_of(uid_a, N_COMMENT) >= 1)
        check("S4.4 帖子被评论 → 楼主收到 COMMENT 通知",
              notify_count_of(uid_a, N_COMMENT) == 1, notify_count_of(uid_a, N_COMMENT))

        # A 回复 B 的评论 → B 收到 REPLY（被回复者，不是楼主）
        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "A 回复 B：这里我解释一下。", "parentId": cmt_b}, token=tk_a)
        check("S4.4 前置：A 回复 B 成功", b.get("code") == 0, b.get("code"))
        wait_until(lambda: notify_count_of(uid_b, N_REPLY) >= 1)
        check("S4.4 被回复 → 被回复者 B 收到 REPLY 通知",
              notify_count_of(uid_b, N_REPLY) == 1, notify_count_of(uid_b, N_REPLY))
        check("S4.4 回复不会误发给楼主（A 没多一条 REPLY）",
              notify_count_of(uid_a, N_REPLY) == 0, notify_count_of(uid_a, N_REPLY))

        # C 评论 A 的帖子；B 回复 C → C 收到 REPLY（关键：不是楼主 A）
        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "C 的一级评论：我也来说两句。"}, token=tk_c)
        cmt_c = b["data"]["id"]
        wait_until(lambda: notify_count_of(uid_a, N_COMMENT) >= 2)
        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "B 回复 C：同意你的看法。", "parentId": cmt_c}, token=tk_b)
        wait_until(lambda: notify_count_of(uid_c, N_REPLY) >= 1)
        check("S4.4 回复第三方评论 → 通知发给被回复者 C（而非楼主 A）",
              notify_count_of(uid_c, N_REPLY) == 1 and notify_count_of(uid_a, N_REPLY) == 0,
              "C=%s A=%s" % (notify_count_of(uid_c, N_REPLY), notify_count_of(uid_a, N_REPLY)))

        # B 点赞 A 的帖子 → A 收到 LIKE
        call("POST", "/api/posts/%d/like" % pid, token=tk_b)
        wait_until(lambda: notify_count_of(uid_a, N_LIKE) >= 1)
        check("S4.4 帖子被点赞 → 作者收到 LIKE 通知",
              notify_count_of(uid_a, N_LIKE) == 1, notify_count_of(uid_a, N_LIKE))

        # ① 纯重复点赞（不取消）：走幂等路径，不该产生新通知
        for _ in range(3):
            call("POST", "/api/posts/%d/like" % pid, token=tk_b)
        time.sleep(1.0)
        check("S4.4 重复点赞（不取消）不重复发通知",
              notify_count_of(uid_a, N_LIKE) == 1, notify_count_of(uid_a, N_LIKE))

        # ② 取消 + 重新点赞：这是"来回操作"路径，靠"同源未读通知合并"挡住刷屏。
        #    若没做合并，对方会收到第二条一模一样的"赞了你的帖子"。
        call("DELETE", "/api/posts/%d/like" % pid, token=tk_b)
        call("POST", "/api/posts/%d/like" % pid, token=tk_b)
        time.sleep(1.2)
        check("S4.4 取消后重新点赞：同源未读通知被合并（仍只有 1 条，防刷屏）",
              notify_count_of(uid_a, N_LIKE) == 1, notify_count_of(uid_a, N_LIKE))

        # A 采纳 B 的评论 → B 收到 ACCEPT
        st, b = call("POST", "/api/posts/%d/accept/%d" % (pid, cmt_b), token=tk_a)
        check("S4.4 前置：A 采纳 B 的评论成功", b.get("code") == 0, b.get("code"))
        wait_until(lambda: notify_count_of(uid_b, N_ACCEPT) >= 1)
        check("S4.4 被采纳 → 回答者 B 收到 ACCEPT 通知",
              notify_count_of(uid_b, N_ACCEPT) == 1, notify_count_of(uid_b, N_ACCEPT))

        # ---- 不给自己发通知 ----
        a_before = notify_count_of(uid_a)
        call("POST", "/api/posts/%d/comments" % pid,
             {"content": "A 自己评论自己的帖子。"}, token=tk_a)
        call("POST", "/api/posts/%d/like" % pid, token=tk_a)
        call("POST", "/api/posts/%d/collect" % pid, token=tk_a)
        time.sleep(1.2)
        check("S4.4 自己评论/点赞/收藏自己的帖子 → 不给自己发通知",
              notify_count_of(uid_a) == a_before,
              "before=%s after=%s" % (a_before, notify_count_of(uid_a)))

        # ---- 通知列表内容与结构 ----
        st, b = call("GET", "/api/notifications?size=50", token=tk_a)
        check("S4.4 通知列表可读", b["code"] == 0 and b["data"]["total"] >= 3,
              "total=%s" % b["data"]["total"])
        recs = b["data"]["records"]
        check("S4.4 通知带发送者昵称（LEFT JOIN 出来）",
              any(x.get("senderNickname") for x in recs),
              [x.get("senderNickname") for x in recs][:4])
        check("S4.4 通知 content 是纯文本快照（不含 HTML）",
              all("<" not in (x.get("content") or "") for x in recs),
              recs[0].get("content") if recs else None)
        check("S4.4 通知指向帖子（targetType=1, targetId=帖子ID）",
              all(x.get("targetType") == T_POST for x in recs if x.get("targetType") is not None),
              [(x.get("targetType"), x.get("targetId")) for x in recs][:3])
        check("S4.4 通知按时间倒序",
              [x["id"] for x in recs] == sorted([x["id"] for x in recs], reverse=True),
              [x["id"] for x in recs][:5])

        # ---- 未读数 ----
        st, b = call("GET", "/api/notifications/unread-count", token=tk_a)
        unread_a = b["data"]
        check("S4.4 未读数等于未读通知条数",
              unread_a == db_scalar("SELECT COUNT(*) FROM t_notification WHERE receiver_id=%d AND is_read=0" % uid_a),
              unread_a)

        # ---- 标记单条已读 ----
        first_id = recs[0]["id"]
        st, b = call("POST", "/api/notifications/%d/read" % first_id, token=tk_a)
        check("S4.4 标记单条已读成功", b["code"] == 0 and b["data"] is True, b.get("data"))
        check("S4.4 已读后未读数减少 1",
              call("GET", "/api/notifications/unread-count", token=tk_a)[1]["data"] == unread_a - 1,
              call("GET", "/api/notifications/unread-count", token=tk_a)[1]["data"])

        # ---- 越权：B 尝试标记 A 的一条【未读】通知 ----
        # 刻意挑未读的：如果挑一条已经读过的，就算越权更新成功也看不出来
        # （is_read 从 1 改成 1），这个测试就失去了区分度。
        unread_id = db_scalar("SELECT id FROM t_notification WHERE receiver_id=%d AND is_read=0 LIMIT 1" % uid_a)
        check("S4.4 前置：A 还有未读通知可供测试越权", unread_id is not None, unread_id)
        st, b = call("POST", "/api/notifications/%d/read" % unread_id, token=tk_b)
        check("S4.4 越权标记他人通知：不报错但也不生效（受影响 0 行）",
              b.get("code") == 0 and b["data"] is False, (b.get("code"), b.get("data")))
        check("S4.4 越权尝试后该通知仍是未读（receiver_id 条件挡住了水平越权）",
              db_scalar("SELECT is_read FROM t_notification WHERE id=%d" % unread_id) == 0,
              "is_read=%s" % db_scalar("SELECT is_read FROM t_notification WHERE id=%d" % unread_id))

        # ---- 只看未读 ----
        st, b = call("GET", "/api/notifications?onlyUnread=true&size=50", token=tk_a)
        check("S4.4 onlyUnread=true 只返回未读",
              all(x["isRead"] == 0 for x in b["data"]["records"]),
              [x["isRead"] for x in b["data"]["records"]][:5])

        # ---- 全部已读 ----
        st, b = call("POST", "/api/notifications/read-all", token=tk_a)
        check("S4.4 全部标记已读成功", b["code"] == 0 and b["data"] >= 1, b.get("data"))
        check("S4.4 全部已读后未读数为 0",
              call("GET", "/api/notifications/unread-count", token=tk_a)[1]["data"] == 0,
              call("GET", "/api/notifications/unread-count", token=tk_a)[1]["data"])

        # ---- 权限 ----
        st, b = call("GET", "/api/notifications")
        check("S4.4 未登录读通知被拒 (40101)", b.get("code") == 40101, b.get("code"))
        st, b = call("GET", "/api/notifications/unread-count")
        check("S4.4 未登录读未读数被拒 (40101)", b.get("code") == 40101, b.get("code"))

        # 接收者只可能来自登录态：B 的通知里绝不该出现"A 的帖子被评论"这类
        # 只发给楼主的通知。这条断言验证的是"接收者取自登录态而非请求参数"。
        st, b = call("GET", "/api/notifications?size=50", token=tk_b)
        b_records = b["data"]["records"]
        check("S4.4 通知接收者只来自登录态：B 的通知里没有'评论 A 帖子'那条",
              all(not (x["type"] == N_COMMENT and x["targetId"] == pid) for x in b_records),
              "B 的通知=(type,target)=%s" % [(x["type"], x["targetId"]) for x in b_records])

        # ==================== 取关后关注流同步 ====================
        st, b = call("DELETE", "/api/users/%d/follow" % uid_a, token=tk_b)
        check("S4.3 取消关注成功且计数归 0",
              b["code"] == 0 and b["data"]["following"] is False and b["data"]["followerCount"] == 0,
              (b["data"].get("following"), b["data"].get("followerCount")))
        st, b = call("DELETE", "/api/users/%d/follow" % uid_a, token=tk_b)
        check("S4.3 未关注时再取关：不报错（幂等）", b.get("code") == 0, b.get("code"))
        st, b = call("GET", "/api/feed/following", token=tk_b)
        check("S4.3 取关后关注流变空",
              b["code"] == 0 and b["data"]["total"] == 0, b["data"].get("total"))

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
    print("Sprint 4 验收：%d/%d 通过" % (passed, total))
    if passed < total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  ->  %s" % (name, str(detail)[:280]))
    print("=" * 70)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
