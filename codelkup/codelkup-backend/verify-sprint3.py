#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Sprint 3 端到端验收脚本（互动：评论 + 点赞收藏 + 计数一致性 + 重建）。

单进程内：启动 jar -> 等待就绪 -> 依次探测 -> 关闭。
复用 Sprint 1/2 的骨架，额外需要直连 MySQL 做两件验收脚本自己做不到的事：
  1) 造管理员账号（role=2）——注册接口只产生普通用户
  2) 对账 Redis/MySQL 计数真值、人为制造偏差以验证重建

设计原则：优先通过 HTTP 断言"对外可见的行为"，
只在必须读写数据库内部状态（对账/造偏差）时才用 mysql CLI。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8085"
JAR_DIR = r"C:\Users\32519\Desktop\Codelkup\codelkup\codelkup-backend"
JAR = os.path.join(JAR_DIR, "target", "codelkup-backend-0.0.1-SNAPSHOT.jar")
MYSQL = r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "codelkup"
DB_ARGS = ["-h127.0.0.1", "-ucodelkup", "-pchange-me-dev-password", DB, "-N", "-B"]

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
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode("utf-8"))
        except Exception:
            return e.code, raw
    except Exception as e:
        return -1, str(e)


def db(sql):
    """执行 SQL，返回行的列表（每行是字段字符串列表）。失败返回 None。"""
    try:
        p = subprocess.run([MYSQL] + DB_ARGS + ["-e", sql],
                           capture_output=True, timeout=25)
        if p.returncode != 0:
            return None
        out = p.stdout.decode("utf-8", "replace").strip()
        if not out:
            return []
        return [line.split("\t") for line in out.splitlines()]
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
    print(("[PASS] " if ok else "[FAIL] ") + name + ("  ->  " + str(detail)[:300] if detail else ""))


def wait_ready(timeout=180):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st, b = call("GET", "/api/system/health")
        if st == 200 and isinstance(b, dict) and b.get("code") == 0:
            return True, time.time() - t0
        time.sleep(1.5)
    return False, time.time() - t0


def make_png():
    """最小合法 PNG，用于发帖时上传配图（本脚本用于验证 image 占位不影响互动）。"""
    import struct
    import zlib

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    idat = zlib.compress(b"\x00\xff\x00\x00")
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


def main():
    env = dict(os.environ)
    env.pop("SERVER__PORT", None)
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"

    log = open("verify-s3.log", "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(["java", "-jar", JAR], env=env, cwd=JAR_DIR,
                            stdout=log, stderr=subprocess.STDOUT)
    print("启动中，pid=%d ..." % proc.pid)
    try:
        ready, secs = wait_ready()
        check("应用启动 + 健康检查", ready, "耗时 %.1fs" % secs)
        if not ready:
            print(open("verify-s3.log", encoding="utf-8", errors="replace").read()[-5000:])
            return

        # ==================== V3 迁移落库 ====================
        rows = db("SHOW TABLES LIKE 't\\_comment'")
        check("V3 迁移：t_comment 已建表", bool(rows), rows)
        rows = db("SHOW TABLES LIKE 't\\_user_action'")
        check("V3 迁移：t_user_action 已建表", bool(rows), rows)

        idx = db("SHOW INDEX FROM t_user_action WHERE Key_name='uk_user_target_action'")
        check("V3 迁移：唯一索引 uk_user_target_action 存在", bool(idx), (idx or [None])[0][2:4] if idx else None)

        # ---- 准备用户 ----
        ts = int(time.time())
        st, b = call("POST", "/api/auth/register",
                     {"email": "s3a_%d@test.com" % ts, "password": "abc12345", "nickname": "楼主甲"})
        if not (isinstance(b, dict) and b.get("code") == 0):
            check("准备用户 A", False, b)
            return
        token_a, uid_a = b["data"]["accessToken"], b["data"]["user"]["id"]

        st, b = call("POST", "/api/auth/register",
                     {"email": "s3b_%d@test.com" % ts, "password": "abc12345", "nickname": "路人乙"})
        token_b, uid_b = b["data"]["accessToken"], b["data"]["user"]["id"]

        st, b = call("POST", "/api/auth/register",
                     {"email": "s3c_%d@test.com" % ts, "password": "abc12345", "nickname": "路人丙"})
        token_c, uid_c = b["data"]["accessToken"], b["data"]["user"]["id"]
        check("准备三个普通用户", uid_a and uid_b and uid_c, (uid_a, uid_b, uid_c))

        # ---- 造管理员：直接把 role 改成 2，并重新登录换取带 ADMIN 的 token ----
        # 注意：JWT 里的权限来自签发时的用户状态，改库后必须重新登录。
        db("UPDATE t_user SET role=2 WHERE id=%d" % uid_a)
        st, b = call("POST", "/api/auth/login",
                     {"email": "s3a_%d@test.com" % ts, "password": "abc12345"})
        admin_token = b["data"]["accessToken"] if isinstance(b, dict) and b.get("code") == 0 else None
        check("管理员重新登录成功（role=2 后重新签发 token）", bool(admin_token), b.get("code"))
        # token_a 已被新 token 取代；统一用 admin_token 代表 A（既是楼主又是管理员）
        token_a = admin_token

        # ---- 发帖（提问帖，供采纳用）----
        st, b = call("GET", "/api/boards")
        board_id = b["data"][0]["id"]
        st, b = call("POST", "/api/posts", {
            "title": "Sprint3 互动验收帖：计数一致性怎么保证",
            "content": "# 问题\n\nRedis 计数和 MySQL 明细表**不一致**了怎么办？\n\n想听大家的方案。",
            "boardId": board_id, "type": 1, "tags": ["Redis", "一致性"]}, token=token_a)
        pid = b["data"]["id"] if isinstance(b, dict) and b.get("code") == 0 else None
        check("发帖（提问帖）成功", bool(pid), b.get("code") if isinstance(b, dict) else b)
        if not pid:
            return

        # ==================== S3 点赞：唯一索引防重 + 幂等 ====================
        st, b = call("POST", "/api/posts/%d/like" % pid, token=token_b)
        check("首次点赞成功，active=true", b.get("code") == 0 and b["data"]["active"] is True,
              b.get("data") if isinstance(b, dict) else b)
        check("点赞后计数为 1", b["data"]["count"] == 1, b["data"].get("count"))

        # 连点 4 次：不应重复计数，且必须返回成功态（幂等，而非 DuplicateKey 500）
        codes = []
        for _ in range(4):
            st, b = call("POST", "/api/posts/%d/like" % pid, token=token_b)
            codes.append((b.get("code"), b["data"].get("active"), b["data"].get("count")))
        check("重复点赞 4 次：全部返回 code=0（幂等，不抛 500）",
              all(c == 0 for c, _, _ in codes), codes)
        check("重复点赞 4 次：active 恒为 true", all(a is True for _, a, _ in codes), codes)
        check("重复点赞 4 次：计数恒为 1（唯一索引真正防住了）",
              all(n == 1 for _, _, n in codes), codes)

        # 数据库层面核对：只有 1 行
        cnt = db_scalar("SELECT COUNT(*) FROM t_user_action "
                        "WHERE user_id=%d AND target_type=1 AND target_id=%d AND action_type=1"
                        % (uid_b, pid))
        check("数据库层：t_user_action 只有 1 行记录", cnt == 1, cnt)

        # 取消点赞
        st, b = call("DELETE", "/api/posts/%d/like" % pid, token=token_b)
        check("取消点赞成功，active=false 计数归 0",
              b.get("code") == 0 and b["data"]["active"] is False and b["data"]["count"] == 0,
              b.get("data") if isinstance(b, dict) else b)

        # 未点赞时重复取消：不应报错，也不应把计数减成负数
        st, b = call("DELETE", "/api/posts/%d/like" % pid, token=token_b)
        check("未点赞时再取消：不报错、计数不为负",
              b.get("code") == 0 and b["data"]["count"] == 0,
              b.get("data") if isinstance(b, dict) else b)

        # 未登录点赞
        st, b = call("POST", "/api/posts/%d/like" % pid)
        check("未登录点赞被拒 (40101)", b.get("code") == 40101, b.get("code"))

        # 不存在的帖子
        st, b = call("POST", "/api/posts/999999999/like", token=token_b)
        check("对不存在的帖子点赞 → 40402", b.get("code") == 40402, b.get("code"))

        # ==================== S3 点赞计数聚合（多人）====================
        call("POST", "/api/posts/%d/like" % pid, token=token_b)
        st, b = call("POST", "/api/posts/%d/like" % pid, token=token_c)
        check("三人中两人点赞后计数为 2", b["data"]["count"] == 2, b["data"].get("count"))
        call("POST", "/api/posts/%d/like" % pid, token=token_a)
        st, b = call("GET", "/api/posts/%d" % pid, token=token_b)
        check("详情页计数与操作返回值一致（=3）", b["data"]["likeCount"] == 3, b["data"].get("likeCount"))
        check("已点赞者看详情 liked=true", b["data"]["liked"] is True, b["data"].get("liked"))
        st, b = call("GET", "/api/posts/%d" % pid, token=token_c)
        check("已点赞者（丙）liked=true", b["data"]["liked"] is True, b["data"].get("liked"))
        # 未登录看详情：liked 必须**存在且为 null**（不是 false，也不是键不存在）
        # 这是契约的一部分：null=未登录（不知道怎么答），false=登录了但没点过。
        # 全局 non_null 策略会删掉 null 字段，所以这两个字段加了 @JsonInclude(ALWAYS)。
        st, b = call("GET", "/api/posts/%d" % pid)
        check("未登录看详情：liked 键存在且为 null（区别于 false / 键缺失）",
              "liked" in b["data"] and b["data"]["liked"] is None,
              "keys 含 liked=%s, 值=%r" % ("liked" in b["data"], b["data"].get("liked")))
        check("未登录看详情：collected 键同样存在且为 null",
              "collected" in b["data"] and b["data"]["collected"] is None,
              "keys 含 collected=%s, 值=%r" % ("collected" in b["data"], b["data"].get("collected")))

        # ==================== S3 收藏 ====================
        st, b = call("POST", "/api/posts/%d/collect" % pid, token=token_c)
        check("收藏成功且独立于点赞", b["code"] == 0 and b["data"]["count"] == 1,
              b.get("data") if isinstance(b, dict) else b)
        # 收藏两次幂等
        st, b = call("POST", "/api/posts/%d/collect" % pid, token=token_c)
        check("重复收藏幂等（计数仍为 1）", b["data"]["count"] == 1, b["data"].get("count"))
        # 收藏不影响点赞数
        st, b = call("GET", "/api/posts/%d" % pid, token=token_c)
        check("收藏不污染点赞数（点赞仍为 3）", b["data"]["likeCount"] == 3, b["data"]["likeCount"])
        check("详情 collected=true", b["data"]["collected"] is True, b["data"].get("collected"))

        # ==================== S3 评论：发表 / 树形 / 计数 ====================
        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "一楼：先给个基线方案，明细表 COUNT(*) 就是真值。"}, token=token_b)
        root1 = b["data"]["id"] if b.get("code") == 0 else None
        check("发表根评论成功", bool(root1), b.get("code") if isinstance(b, dict) else b)
        check("根评论 parentId 归一化为 0", b["data"]["parentId"] == 0, b["data"].get("parentId"))

        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "二楼：我补充一点，重建要幂等，用 SET 不用 INCRBY。"}, token=token_c)
        root2 = b["data"]["id"] if b.get("code") == 0 else None
        check("发表第二条根评论成功", bool(root2), b.get("code"))

        # 回复一楼：parentId 应被归一化到根评论（root1），而 replyUserId 记录被回复者
        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "回复一楼：同意，但要注意并发窗口。",
                      "parentId": root1, "replyUserId": uid_b}, token=token_c)
        reply1 = b["data"]["id"] if b.get("code") == 0 else None
        check("发表回复成功", bool(reply1), b.get("code") if isinstance(b, dict) else b)
        check("回复的 parentId 指向根评论（非被回复的楼层）",
              b["data"]["parentId"] == root1, (b["data"].get("parentId"), root1))
        check("回复保留 replyUserId（展示'回复谁'）",
              b["data"].get("replyUserId") == uid_b, b["data"].get("replyUserId"))

        # 对回复再回复（三层）：parentId 必须仍归一化到 root1，不是 reply1
        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "三层回复：归一化到根，避免递归组件。",
                      "parentId": reply1, "replyUserId": uid_c}, token=token_b)
        check("对回复的回复：parentId 仍归一化到根评论",
              b["data"]["parentId"] == root1, (b["data"].get("parentId"), root1))

        # 列表：两层结构（根 + 回复挂在其下）
        st, b = call("GET", "/api/posts/%d/comments" % pid)
        check("评论列表免登录可访问", st == 200 and b.get("code") == 0, b.get("code"))
        lst = b.get("data") or []
        roots = [c for c in lst if c.get("parentId") == 0]
        check("列表返回 2 个根评论", len(roots) == 2, [c.get("id") for c in roots])
        r1 = next((c for c in lst if c.get("id") == root1), None)
        check("根评论下挂载 2 条回复（含三层回复已拍平）",
              r1 and len(r1.get("replies") or []) == 2,
              [c.get("id") for c in (r1.get("replies") or [])] if r1 else None)
        check("评论带作者昵称（JOIN 出来，无 N+1）",
              r1 and r1.get("authorNickname") == "路人乙", r1 and r1.get("authorNickname"))
        check("评论带内容原文", r1 and "基线方案" in (r1.get("content") or ""),
              (r1 or {}).get("content"))

        # 评论计数与详情一致
        st, b = call("GET", "/api/posts/%d" % pid)
        check("评论数计入详情（共 4 条）", b["data"]["commentCount"] == 4, b["data"].get("commentCount"))

        # ---- 评论校验 ----
        st, b = call("POST", "/api/posts/%d/comments" % pid, {"content": "   "}, token=token_b)
        check("空内容评论被校验拦下 (40001)", b.get("code") == 40001, b.get("code"))
        st, b = call("POST", "/api/posts/%d/comments" % pid, {"content": "x" * 1001}, token=token_b)
        check("超长评论被拦下 (40001)", b.get("code") == 40001, b.get("code"))
        st, b = call("POST", "/api/posts/%d/comments" % pid,
                     {"content": "未登录不能发。"})
        check("未登录发评论被拒 (40101)", b.get("code") == 40101, b.get("code"))

        # 跨帖回复拦截：parentId 指向别的帖子的评论
        st, b = call("POST", "/api/posts", {
            "title": "另一篇帖子用于验证跨帖回复被拦",
            "content": "内容长度需要超过十个字符才可以通过校验。", "boardId": board_id}, token=token_a)
        pid2 = b["data"]["id"]
        st, b = call("POST", "/api/posts/%d/comments" % pid2,
                     {"content": "试图挂到别的帖子的评论树上。",
                      "parentId": root1, "replyUserId": uid_b}, token=token_c)
        check("跨帖回复被拦下（parent 不属于本帖，40001/40004）",
              b.get("code") in (40001, 40004, 40404), b.get("code"))

        # ==================== S3 评论点赞 ====================
        st, b = call("POST", "/api/comments/%d/like" % root1, token=token_c)
        check("评论点赞成功", b.get("code") == 0 and b["data"]["count"] == 1,
              b.get("data") if isinstance(b, dict) else b)
        for _ in range(3):
            st, b = call("POST", "/api/comments/%d/like" % root1, token=token_c)
        check("评论重复点赞幂等（计数仍 1）", b["data"]["count"] == 1, b["data"].get("count"))
        st, b = call("DELETE", "/api/comments/%d/like" % root1, token=token_c)
        check("取消评论点赞后归 0", b["data"]["count"] == 0, b["data"].get("count"))

        # 评论点赞数与帖子点赞独立
        st, b = call("GET", "/api/posts/%d" % pid)
        check("评论点赞不影响帖子点赞数（仍 3）", b["data"]["likeCount"] == 3, b["data"]["likeCount"])

        # ==================== S3 删除评论 ====================
        # root2 的作者是「路人丙」(token_c)。
        # ① 用「路人乙」(token_b) 去删 → 非作者，必须被拒 40302
        st, b = call("DELETE", "/api/comments/%d" % root2, token=token_b)
        check("非作者删除他人评论被拒 (40302)", b.get("code") == 40302, b.get("code"))
        # ② 作者本人（丙）删自己的 → 成功
        st, b = call("DELETE", "/api/comments/%d" % root2, token=token_c)
        check("作者删除自己的评论成功", b.get("code") == 0, b.get("code"))
        st, b = call("GET", "/api/posts/%d" % pid)
        check("删除后评论数减少（4 -> 3）", b["data"]["commentCount"] == 3, b["data"].get("commentCount"))

        # 楼主删他人评论（版主/楼主权限策略：按实现为准，仅探测不强制）
        st, b = call("DELETE", "/api/comments/%d" % reply1, token=token_a)
        detail = "code=%s" % (b.get("code") if isinstance(b, dict) else b)
        check("楼主删除他人评论：返回明确的业务码（0 或 40302）",
              b.get("code") in (0, 40302), detail)

        # ==================== S3 采纳答案 ====================
        st, b = call("POST", "/api/posts/%d/accept/%d" % (pid, root1), token=token_b)
        check("非楼主采纳被拒 (40302)", b.get("code") == 40302, b.get("code"))

        st, b = call("POST", "/api/posts/%d/accept/%d" % (pid, root1), token=token_a)
        check("楼主采纳成功", b.get("code") == 0, b.get("code") if isinstance(b, dict) else b)

        st, b = call("GET", "/api/posts/%d/comments" % pid)
        acc = [c for c in (b.get("data") or []) if c.get("id") == root1]
        check("被采纳的评论 isAccepted=1 且排最前",
              acc and acc[0].get("isAccepted") == 1 and (b["data"][0].get("id") == root1),
              acc[0].get("isAccepted") if acc else None)

        # 非提问帖不能采纳
        st, b = call("POST", "/api/posts", {
            "title": "普通讨论帖不允许采纳答案",
            "content": "内容长度需要超过十个字符才可以通过校验。", "boardId": board_id}, token=token_a)
        pid3 = b["data"]["id"]
        st, b = call("POST", "/api/posts/%d/comments" % pid3, {"content": "一条普通评论内容。"}, token=token_b)
        cid3 = b["data"]["id"]
        st, b = call("POST", "/api/posts/%d/accept/%d" % (pid3, cid3), token=token_a)
        check("非提问帖采纳被拒 (40001)", b.get("code") == 40001, b.get("code"))

        # ==================== S3 计数一致性：Redis vs 明细表对账 ====================
        # 通过"详情页返回的计数"与"明细表 COUNT(*)"对账，
        # 两条路径分别读 Redis 与 MySQL，值相同才说明模型自洽。
        http_like = call("GET", "/api/posts/%d" % pid)[1]["data"]["likeCount"]
        db_like = db_scalar("SELECT COUNT(*) FROM t_user_action "
                            "WHERE target_type=1 AND target_id=%d AND action_type=1" % pid)
        check("点赞数对账：接口值与明细表 COUNT(*) 一致",
              http_like == db_like, "HTTP=%s DB=%s" % (http_like, db_like))

        http_collect = call("GET", "/api/posts/%d" % pid)[1]["data"]["collectCount"]
        db_collect = db_scalar("SELECT COUNT(*) FROM t_user_action "
                               "WHERE target_type=1 AND target_id=%d AND action_type=2" % pid)
        check("收藏数对账：接口值与明细表 COUNT(*) 一致",
              http_collect == db_collect, "HTTP=%s DB=%s" % (http_collect, db_collect))

        http_comment = call("GET", "/api/posts/%d" % pid)[1]["data"]["commentCount"]
        db_comment = db_scalar("SELECT COUNT(*) FROM t_comment "
                               "WHERE post_id=%d AND deleted=0" % pid)
        check("评论数对账：接口值与明细表 COUNT(*) 一致",
              http_comment == db_comment, "HTTP=%s DB=%s" % (http_comment, db_comment))

        # ==================== S3 计数重建：人为制造偏差 → 纠正 ====================
        # 1) 先把 MySQL 里的计数列改成一个错误值（模拟"Redis 丢失基线"或历史脏数据）
        db("UPDATE t_post SET like_count=999, collect_count=888, comment_count=777 WHERE id=%d" % pid)
        # 2) 清掉 Redis key，让下一次读取走"miss 回填"或直接读到脏 MySQL 值
        #    这里直接调重建接口，检查它能把 MySQL 计数列 + Redis 都拉回真值
        st, b = call("POST", "/api/admin/counters/rebuild/%d" % pid, token=token_a)
        ok = b.get("code") == 0 if isinstance(b, dict) else False
        check("管理员触发重建成功", ok, b.get("code") if isinstance(b, dict) else b)
        rep = b.get("data") or {}
        check("重建报告含前后对照（beforeLike=999）",
              rep.get("beforeLike") == 999, rep.get("beforeLike"))
        check("重建后 likeCount 修正为真值 3", rep.get("afterLike") == 3, rep.get("afterLike"))
        check("重建后 collectCount 修正为真值 1", rep.get("afterCollect") == 1, rep.get("afterCollect"))
        # 评论真值由对账得出（删除评论后已变化），不硬编码，避免脚本随实现演进而脆弱
        check("重建后 commentCount 修正为明细表真值",
              rep.get("afterComment") == db_comment,
              "after=%s 真值=%s" % (rep.get("afterComment"), db_comment))
        check("重建报告 driftCount 量化了偏差总量（>0）",
              isinstance(rep.get("driftCount"), int) and rep["driftCount"] > 0, rep.get("driftCount"))

        # 重建后接口返回的值应与真值一致（真值从明细表反查，不硬编码）
        d = call("GET", "/api/posts/%d" % pid)[1]["data"]
        check("重建后接口计数全部回到真值",
              d["likeCount"] == db_like and d["collectCount"] == db_collect
              and d["commentCount"] == db_comment,
              "HTTP=(%s,%s,%s) DB=(%s,%s,%s)" % (d["likeCount"], d["collectCount"], d["commentCount"],
                                                 db_like, db_collect, db_comment))

        # 幂等：再重建一次，drift 应为 0（因为已经是真值）
        st, b = call("POST", "/api/admin/counters/rebuild/%d" % pid, token=token_a)
        check("重复重建幂等：第二次 driftCount=0",
              b["data"].get("driftCount") == 0, b["data"].get("driftCount"))

        # 再制造一次偏差，然后重建，验证"可重复修正"
        db("UPDATE t_post SET like_count=0 WHERE id=%d" % pid)
        st, b = call("POST", "/api/admin/counters/rebuild/%d" % pid, token=token_a)
        check("二次偏差可再次被修正", b["data"]["afterLike"] == db_like,
              "after=%s 真值=%s" % (b["data"].get("afterLike"), db_like))

        # ==================== S3 重建权限 ====================
        st, b = call("POST", "/api/admin/counters/rebuild/%d" % pid, token=token_b)
        check("普通用户触发重建被拒 (40301)", b.get("code") == 40301, b.get("code"))
        st, b = call("POST", "/api/admin/counters/rebuild/%d" % pid)
        check("未登录触发重建被拒 (40101)", b.get("code") == 40101, b.get("code"))

        # 全量重建
        st, b = call("POST", "/api/admin/counters/rebuild", token=token_a)
        check("全量重建接口可用", b.get("code") == 0, b.get("code"))
        check("全量重建报告含扫描总数",
              isinstance(b["data"].get("totalPosts"), int) and b["data"]["totalPosts"] >= 3,
              b["data"].get("totalPosts"))

        # 全量重建后所有帖子计数都与明细表一致（抽样检查前 5 篇）
        st, b = call("GET", "/api/posts?size=5")
        mismatch = []
        for r in b["data"]["records"]:
            dl = db_scalar("SELECT COUNT(*) FROM t_user_action WHERE target_type=1 "
                           "AND target_id=%d AND action_type=1" % r["id"])
            if r["likeCount"] != dl:
                mismatch.append((r["id"], r["likeCount"], dl))
        check("全量重建后抽样帖子点赞数全部对账通过", not mismatch, mismatch or "5/5 一致")

        # ==================== S3 列表页互动状态 ====================
        st, b = call("GET", "/api/posts?size=50", token=token_b)
        rec = next((r for r in b["data"]["records"] if r["id"] == pid), None)
        check("列表页带出 liked 状态（乙点过赞 → true）",
              rec and rec.get("liked") is True, rec and rec.get("liked"))
        check("列表页带出 count 字段供卡片展示",
              rec and "likeCount" in rec and "commentCount" in rec,
              list(rec.keys()) if rec else None)
        st, b = call("GET", "/api/posts?size=50")
        rec = next((r for r in b["data"]["records"] if r["id"] == pid), None)
        check("未登录列表：liked 键存在且为 null（不误报为 false）",
              rec is not None and "liked" in rec and rec["liked"] is None,
              "keys 含 liked=%s, 值=%r" % (rec is not None and "liked" in rec,
                                          rec.get("liked") if rec else None))

        # ==================== 收尾：删除帖子后计数清零可重建 ====================
        st, b = call("DELETE", "/api/posts/%d" % pid, token=token_a)
        check("删除帖子成功（会连带清理 Redis 计数 key）", b.get("code") == 0, b.get("code"))
        st, b = call("GET", "/api/posts/%d" % pid)
        check("删除后详情 40402", b.get("code") == 40402, b.get("code"))

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
    print("Sprint 3 验收：%d/%d 通过" % (passed, total))
    if passed < total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  ->  %s" % (name, str(detail)[:300]))
    print("=" * 70)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
