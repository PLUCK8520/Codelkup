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
import io
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
        # ⚠️ 这里**不能**断言 `== 0`：库里只要有一篇帖子正文里出现字面的 '%'
        #    （实测：演示帖 20102 的技术复盘里写了 "P95 187→90ms（-52%）"），
        #    它就会被**正确命中** —— 那恰恰说明转义生效了。
        #    要验的是"没退化成全表扫描"，所以拿全表总数当上界，并和"库里真含字面 %"
        #    的帖子数逐一对账（用 INSTR 而不是 LIKE：避开通配符自身）。
        pct_hits = search("%")["data"]["total"]
        all_posts = call("GET", "/api/posts?size=1")[1]["data"]["total"]
        db_pct = db_scalar("SELECT COUNT(*) FROM t_post WHERE status=1 AND "
                           "(INSTR(title, CHAR(37))>0 OR INSTR(content, CHAR(37))>0)")
        check("S4.1 LIKE 通配符 '%' 被转义：只精确命中字面量、不退化成全表",
              pct_hits == db_pct and pct_hits < all_posts,
              "命中=%s 库含字面%%=%s 全表=%s" % (pct_hits, db_pct, all_posts))

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
        # L-1 修复：作者自赞已被后端拦截（40001）；自藏保留（书签语义）——
        # 两条路径都不该产生通知（下面是拦截后的补充断言）
        st, b = call("POST", "/api/posts/%d/like" % pid, token=tk_a)
        check("S4.4 作者自赞被拒（L-1 修复，40001）", b.get("code") == 40001, b.get("code"))
        call("POST", "/api/posts/%d/collect" % pid, token=tk_a)
        time.sleep(1.2)
        check("S4.4 自己评论 / 自赞(被拒) / 自藏 → 都不给自己发通知",
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

        # ---- V0.4：类型分组筛选（通知中心的 tab） ----
        # ⚠️ 期望值一律从数据库现算，不写死数字：上面的断言已经插了若干通知，
        #    写死的数字会随断言顺序变化，让脚本自己变脆
        #    （项目约定：期望值尽量从"真值来源"动态取）。
        db_comment = db_scalar("SELECT COUNT(*) FROM t_notification WHERE receiver_id=%d AND type=%d" % (uid_a, N_COMMENT))
        st, b = call("GET", "/api/notifications?type=1&size=50", token=tk_a)
        check("S4.4/V0.4 按类型筛选 type=1 只返回评论，条数与库一致",
              b["code"] == 0 and b["data"]["total"] == db_comment
              and all(x["type"] == N_COMMENT for x in b["data"]["records"]),
              "api=%s db=%s" % (b["data"].get("total"), db_comment))

        db_cr = db_scalar("SELECT COUNT(*) FROM t_notification WHERE receiver_id=%d AND type IN (%d,%d)"
                          % (uid_a, N_COMMENT, N_REPLY))
        st, b = call("GET", "/api/notifications?type=1,2&size=50", token=tk_a)
        check("S4.4/V0.4 多类型 IN：type=1,2（「评论与回复」合成一个 tab）",
              b["code"] == 0 and b["data"]["total"] == db_cr
              and all(x["type"] in (N_COMMENT, N_REPLY) for x in b["data"]["records"]),
              "api=%s db=%s" % (b["data"].get("total"), db_cr))

        st, b = call("GET", "/api/notifications?type=1&type=2&size=50", token=tk_a)
        check("S4.4/V0.4 重复键写法（type=1&type=2）与逗号写法等价",
              b["code"] == 0 and b["data"]["total"] == db_cr, b["data"].get("total"))

        st, b = call("GET", "/api/notifications?type=3&size=50", token=tk_a)
        check("S4.4/V0.4 类型筛选与已读状态正交（type=3 仍能查到已读过的那条点赞）",
              b["code"] == 0 and any(x["isRead"] == 1 for x in b["data"]["records"]),
              [(x["type"], x["isRead"]) for x in b["data"]["records"]])

        st, b = call("GET", "/api/notifications?onlyUnread=true&type=1&size=50", token=tk_a)
        check("S4.4/V0.4 类型筛选与 onlyUnread 可叠加",
              b["code"] == 0 and all(x["isRead"] == 0 and x["type"] == N_COMMENT for x in b["data"]["records"]),
              [(x["type"], x["isRead"]) for x in b["data"]["records"]])

        st, b = call("GET", "/api/notifications?type=99&size=50", token=tk_a)
        check("S4.4/V0.4 未知类型返回空列表而不是报错",
              b["code"] == 0 and b["data"]["total"] == 0, b.get("code"))

        st, b = call("GET", "/api/notifications?type=abc", token=tk_a)
        check("S4.4/V0.4 非法类型被拦下（40003，而不是兜底的 50001）", b.get("code") == 40003, b.get("code"))

        db_all = db_scalar("SELECT COUNT(*) FROM t_notification WHERE receiver_id=%d" % uid_a)
        st, b = call("GET", "/api/notifications?type=&size=50", token=tk_a)
        check("S4.4/V0.4 空类型参数被忽略（等同全部类型，不报错）",
              b["code"] == 0 and b["data"]["total"] == db_all, "api=%s db=%s" % (b["data"].get("total"), db_all))

        # ---- V0.4：各类型未读数（分组角标） ----
        st, b = call("GET", "/api/notifications/unread-counts", token=tk_a)
        counts = {x["type"]: x["count"] for x in b["data"]}
        check("S4.4/V0.4 分组角标可读", b["code"] == 0, b.get("code"))
        check("S4.4/V0.4 各类型未读数逐类与数据库对账",
              all(v == db_scalar("SELECT COUNT(*) FROM t_notification WHERE receiver_id=%d AND is_read=0 AND type=%d"
                                 % (uid_a, k)) for k, v in counts.items()),
              counts)
        check("S4.4/V0.4 分组角标之和 == 总未读数（同一套数据的两个口径）",
              sum(counts.values()) == unread(tk_a),
              "sum=%s total=%s" % (sum(counts.values()), unread(tk_a)))
        check("S4.4/V0.4 已全部读过的类型不出现在角标里（type=3 无未读）",
              N_LIKE not in counts, sorted(counts.keys()))

        # ---- 全部已读 ----
        st, b = call("POST", "/api/notifications/read-all", token=tk_a)
        check("S4.4 全部标记已读成功", b["code"] == 0 and b["data"] >= 1, b.get("data"))
        check("S4.4 全部已读后未读数为 0",
              call("GET", "/api/notifications/unread-count", token=tk_a)[1]["data"] == 0,
              call("GET", "/api/notifications/unread-count", token=tk_a)[1]["data"])

        # ==================== 评论点赞的通知语义（第三轮审计修正） ====================
        # 修正两个问题：① 评论点赞此前与帖子点赞发出**完全相同**的通知元组，
        # 会被"同源未读去重"当成重复吃掉（先赞帖子再赞评论 → 第二条静默消失）；
        # ② 文案一律写"赞了你的帖子"，对评论点赞是事实错误。
        # 现在：目标记为评论（targetType=2），所属帖子由服务端 JOIN 出（targetPostId），跳转不受影响。
        likes_before = notify_count_of(uid_b, N_LIKE)

        st, b = call("POST", "/api/posts", {
            "title": "B 的验收帖 %d" % ts,
            "content": "这帖用于验证评论点赞的通知语义，正文够十个字。",
            "boardId": board_id, "type": 0,
        }, token=tk_b)
        check("[评论点赞] 准备：B 发帖成功", b.get("code") == 0, b.get("code"))
        pid_b = b["data"]["id"]

        st, b = call("POST", "/api/posts/%d/comments" % pid_b,
                     {"content": "B 自己帖下的一条评论，用来被点赞。"}, token=tk_b)
        check("[评论点赞] 准备：B 的评论创建成功", b.get("code") == 0, b.get("code"))
        cmt_b = b["data"]["id"]

        st, b = call("POST", "/api/posts/%d/like" % pid_b, token=tk_a)
        check("[评论点赞] 准备：A 先赞 B 的帖子（制造一条同源未读点赞通知）",
              b.get("code") == 0, b.get("code"))
        wait_until(lambda: notify_count_of(uid_b, N_LIKE) >= likes_before + 1)

        st, b = call("POST", "/api/comments/%d/like" % cmt_b, token=tk_a)
        check("[评论点赞] A 再赞 B 的评论", b.get("code") == 0, b.get("code"))
        wait_until(lambda: notify_count_of(uid_b, N_LIKE) >= likes_before + 2)
        check("[评论点赞] 两条点赞通知并存（评论点赞不再被帖子点赞的去重吃掉）",
              notify_count_of(uid_b, N_LIKE) == likes_before + 2,
              notify_count_of(uid_b, N_LIKE))

        st, b = call("GET", "/api/notifications?type=3&size=10", token=tk_b)
        recs_like = b["data"]["records"]
        cmt_notif = next((x for x in recs_like if x.get("targetType") == 2), None)
        check("[评论点赞] 目标记为评论且带所属帖子 ID（targetType=2 + targetPostId）",
              cmt_notif is not None and cmt_notif.get("targetId") == cmt_b
              and cmt_notif.get("targetPostId") == pid_b,
              [(x.get("targetType"), x.get("targetId"), x.get("targetPostId")) for x in recs_like][:4])
        check("[评论点赞] 文案是「赞了你的评论」（不再是「赞了你的帖子」）",
              cmt_notif is not None and "赞了你的评论" in (cmt_notif.get("content") or ""),
              cmt_notif.get("content") if cmt_notif else None)
        check("[评论点赞] 帖子类点赞通知的 targetPostId 回落为 targetId（JOIN 不影响既有语义）",
              all(x.get("targetPostId") == x.get("targetId")
                  for x in recs_like if x.get("targetType") == 1),
              [(x.get("targetType"), x.get("targetId"), x.get("targetPostId")) for x in recs_like][:4])

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
