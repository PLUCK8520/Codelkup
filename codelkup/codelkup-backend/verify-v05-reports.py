#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
V0.5 M5 验收 —— 举报与审核队列（自起 jar 型）。

覆盖 TASKS M5-1 / M5-2 / M5-3 三行验收列，一次跑完：
    [1] 提交举报的边界：未登录 40101、目标不存在 40402/40403、
        「举报自己」40001（帖子与评论各一次）、原因非法 40001
    [2] 幂等：同一人对同一目标二次举报 → 40909，且库内仍只有 1 行
        （靠 uk_reporter_target 唯一索引，不是"先查再写"）
    [3] 限流：把 report-per-hour 压到 3 → 第 4 次提交 42901，且不落库
    [4] 管理端权限：未登录 40101、普通用户 40301（队列 / 计数 / 处理三处都要拦）
    [5] 队列：条数与库对账（状态 0/1/2 各一次）、倒序、字段齐全
        （目标摘要 + 作者 + 同目标被举报次数 + 举报人昵称 + 处理留痕）
    [6] 处理：驳回 → 目标仍在；删除内容 → 帖子 40402 且作者列表里消失、
        评论从列表消失；重复处理 → 40910；handled_by/at/note 落痕；
        同目标兄弟单被自动关闭（closedSiblings 与库内备注都要对得上）

<h2>为什么是"自起 jar"而不是附着型</h2>
    限流断言必须把 report-per-hour 压到 3 才验得到（默认 20 跑不出 42901），
    而限流阈值是启动参数 —— 附着型脚本无法替已经在跑的进程改参数。
    ⚠️ 端口被占时必须拒绝运行（见 start_app 注释）：否则断言会跑在
    "别人那个默认阈值的进程"上，表现为限流永远不触发，比直接报错难查得多。

<h2>文本断言为什么都交给 SQL 比</h2>
    Windows 上 mysql CLI 的输出编码受控制台影响，把中文读回 Python 再比较
    容易得到"看起来不一致"的假失败。因此凡是"某列是不是这几个中文字"的断言，
    统一写成 SQL 里的 = 比较、只把计数读回 Python（连接字符集显式指定 utf8mb4）。

用法：
    python verify-v05-reports.py
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
MYSQL = r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB_ARGS = ["-h127.0.0.1", "-ucodelkup", "-pchange-me-dev-password", "codelkup", "-N", "-B",
           "--default-character-set=utf8mb4"]
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379

# 调试阈值：举报压到 3 次/小时才验得到 42901（默认 20）
REPORT_PER_HOUR = 3

# 与 ReportService.SIBLING_CLOSE_NOTE 同一口径 —— 这里必须写死字符串才能验到
# "库里的备注到底是系统文案还是管理员手填"（后端改了文案而脚本没改 → 断言失败是对的）
SIBLING_CLOSE_NOTE = "同目标内容已删除，自动关闭"

PASS = 0
FAIL = 0
FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"[PASS] {name}")
    else:
        FAIL += 1
        FAILURES.append(name)
        print(f"[FAIL] {name}" + (f"  -> {detail}" if detail else ""))


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method: str, path: str, body: dict | None = None, token: str | None = None,
         timeout: int = 30) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"raw": raw, "status": e.code}


# ==================== Redis（只清限流桶）====================

def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def cleanup_rate_buckets() -> int:
    """只清注册桶与举报桶（⚠️ 绝不碰 codelkup:auth:* —— 那是 refresh 白名单）"""
    keys: list[str] = []
    for pattern in ("codelkup:rate:register:*", "codelkup:rate:report:*"):
        resp = redis_cmd("KEYS", pattern)
        keys += [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


# ==================== MySQL（造管理员 + 真值对账）====================

def db_rows(sql: str) -> list[list[str]]:
    try:
        p = subprocess.run([MYSQL] + DB_ARGS + ["-e", sql], capture_output=True, timeout=25)
        if p.returncode != 0:
            return []
        out = p.stdout.decode("utf-8", "replace").strip()
        return [line.split("\t") for line in out.splitlines()] if out else []
    except Exception:
        return []


def db_scalar(sql: str, default: int = -1) -> int:
    """取第一行第一列的整数（真值来源，不硬编码期望值）"""
    rows = db_rows(sql)
    if not rows or not rows[0]:
        return default
    try:
        return int(rows[0][0])
    except ValueError:
        return default


def db_exec(sql: str) -> bool:
    try:
        p = subprocess.run([MYSQL] + DB_ARGS + ["-e", sql], capture_output=True, timeout=25)
        return p.returncode == 0
    except Exception:
        return False


# ==================== 应用进程管理 ====================

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


def start_app(extra_args: list[str]):
    """拉起一个**独占**的后端实例。

    ⚠️ 先确认 8085 上没有别的服务：本脚本必须独占后端。
    踩过的坑（2026-09-26）：开发后端还在跑时启动脚本 → 自己的 jar 绑端口失败
    直接退出，而 wait_ready 仍在**旧实例**上通过 —— 断言全部跑在错误进程上。
    宁可拒绝运行，也不能在错误的进程上取证。
    """
    try:
        if call("GET", "/api/system/health").get("code") == 0:
            check("[0] 应用启动 + 健康检查", False,
                  "8085 端口已有服务在监听：验收脚本必须独占后端，请先停掉它再运行")
            return None, None
    except Exception:
        pass  # 连不上 = 端口空闲 ✓

    env = dict(os.environ)
    env.pop("SERVER__PORT", None)
    env["SERVER_PORT"] = "8085"
    env["SPRING_PROFILES_ACTIVE"] = "dev"
    log = open("verify-v05-reports.log", "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(["java", "-jar", JAR] + extra_args,
                            env=env, cwd=JAR_DIR, stdout=log, stderr=subprocess.STDOUT)
    print(f"启动中，pid={proc.pid} | {' '.join(extra_args)}")
    ready, secs = wait_ready()
    check("[0] 应用启动 + 健康检查", ready, "耗时 %.1fs" % secs)
    if not ready:
        print(open("verify-v05-reports.log", encoding="utf-8", errors="replace").read()[-4000:])
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


# ==================== 业务小工具 ====================

def register(email: str, nickname: str) -> tuple[int, str]:
    """注册并登录，返回 (uid, accessToken)。失败返回 (0, "")"""
    reg = call("POST", "/api/auth/register",
               {"email": email, "password": "abc12345", "nickname": nickname})
    if reg.get("code") != 0:
        print(f"  注册失败：{reg}")
        return 0, ""
    uid = reg["data"]["user"]["id"]
    login = call("POST", "/api/auth/login", {"email": email, "password": "abc12345"})
    return uid, (login.get("data") or {}).get("accessToken", "")


def report(target_type: int, target_id: int, reason: str, token: str,
           detail: str | None = None) -> dict:
    body: dict = {"targetType": target_type, "targetId": target_id, "reason": reason}
    if detail is not None:
        body["detail"] = detail
    return call("POST", "/api/reports", body, token=token)


def queue(status: int | None, token: str, size: int = 50) -> dict:
    path = f"/api/admin/reports?size={size}"
    if status is not None:
        path += f"&status={status}"
    return call("GET", path, token=token)


def pending_count(token: str) -> int:
    return call("GET", "/api/admin/reports/pending-count", token=token).get("data", -1)


def handle(report_id: int, action: str, token: str, note: str | None = None) -> dict:
    body: dict = {"action": action}
    if note is not None:
        body["note"] = note
    return call("POST", f"/api/admin/reports/{report_id}/handle", body, token=token)


def find_row(rows: list[dict], report_id: int) -> dict | None:
    return next((r for r in rows if r.get("id") == report_id), None)


def ids_of(payload: dict | None) -> list:
    return [item.get("id") for item in (payload or [])]


# ==================== 用例 ====================

def main() -> int:
    print("=" * 62)
    print(f"V0.5 举报与审核验收（M5）| base={BASE} | report-per-hour={REPORT_PER_HOUR}")
    print("=" * 62)

    print("\n[0] 预清理注册 / 举报限流桶")
    print(f"  清理 {cleanup_rate_buckets()} 个 key")

    proc, log = start_app([f"--codelkup.rate-limit.report-per-hour={REPORT_PER_HOUR}"])
    try:
        if proc is None or proc.poll() is not None:
            return 1

        # ---------- 迁移就位 ----------
        table_ok = db_scalar("SELECT COUNT(*) FROM information_schema.tables "
                             "WHERE table_schema='codelkup' AND table_name='t_report'") == 1
        check("[1] V11 迁移已应用（t_report 存在）", table_ok)

        # ---------- 准备用户 ----------
        print("\n[1] 准备用户：作者 / 举报人×3 / 管理员")
        ts = int(time.time())
        admin_id, admin_tk = register(f"m5_admin_{ts}@test.com", "审核员")
        author_id, author_tk = register(f"m5_author_{ts}@test.com", "内容作者")
        r1_id, r1_tk = register(f"m5_r1_{ts}@test.com", "举报人甲")
        r2_id, r2_tk = register(f"m5_r2_{ts}@test.com", "举报人乙")
        r3_id, r3_tk = register(f"m5_r3_{ts}@test.com", "举报人丙")
        check("[1] 5 个账号注册并登录（注册限流默认 5/小时/IP）",
              all([admin_id, author_id, r1_id, r2_id, r3_id]),
              (admin_id, author_id, r1_id, r2_id, r3_id))

        # 注册接口只产生普通用户 → 用 mysql CLI 提权后**重新登录**（token 里带 role）
        promoted = db_exec(f"UPDATE t_user SET role=2 WHERE id={admin_id}")
        relogin = call("POST", "/api/auth/login",
                       {"email": f"m5_admin_{ts}@test.com", "password": "abc12345"})
        admin_tk = (relogin.get("data") or {}).get("accessToken", "")
        check("[1] 管理员已提权并重新登录（mysql CLI 可用）",
              promoted and bool(admin_tk), f"promoted={promoted}")

        # ---------- 造内容 ----------
        print("\n[2] 造内容：P1、P2（作者）+ C1（挂在 P2 上）")
        board_id = ((call("GET", "/api/boards").get("data") or [{}])[0]).get("id")
        p1_title, p2_title = f"举报验收帖P1 {ts}", f"举报验收帖P2 {ts}"
        p1 = call("POST", "/api/posts",
                  {"title": p1_title, "content": "这是 P1 的正文，用于验证驳回后内容仍在。",
                   "boardId": board_id, "type": 0}, token=author_tk)
        p2 = call("POST", "/api/posts",
                  {"title": p2_title, "content": "这是 P2 的正文，用来承载被举报的评论。",
                   "boardId": board_id, "type": 0}, token=author_tk)
        p1_id, p2_id = (p1.get("data") or {}).get("id"), (p2.get("data") or {}).get("id")
        c1 = call("POST", f"/api/posts/{p2_id}/comments",
                  {"content": "这条评论应该被删除：加群领优惠，联系方式见主页。"}, token=author_tk)
        c1_id = (c1.get("data") or {}).get("id")
        check("[2] P1 / P2 / C1 创建成功",
              p1.get("code") == 0 and p2.get("code") == 0 and c1.get("code") == 0,
              (p1.get("code"), p2.get("code"), c1.get("code")))

        # ---------- 提交举报的边界 ----------
        # ⚠️ 配额账要算清：限流在 Controller 里、**DTO 校验之后**执行，
        #    所以"过了 DTO 校验但业务失败"的请求（404/40001/40909）同样消耗配额。
        #    边界用例因此分散给三个不同用户，免得把后面的主流程配额吃光。
        print("\n[3] 提交举报：权限与目标校验")
        anon = report(1, p1_id, "SPAM", "")
        check("[3] 未登录提交举报 → 40101", anon.get("code") == 40101, anon.get("code"))

        # 丙：专职跑"目标不存在 + 原因非法"（已用 3/3 配额）
        ghost_post = report(1, 99999999, "SPAM", r3_tk)
        check("[3] 举报不存在的帖子 → 40402", ghost_post.get("code") == 40402, ghost_post.get("code"))
        ghost_comment = report(2, 99999999, "ABUSE", r3_tk)
        check("[3] 举报不存在的评论 → 40403", ghost_comment.get("code") == 40403, ghost_comment.get("code"))
        bad_reason = report(1, p1_id, "NOT_A_REASON", r3_tk)
        check("[3] 举报原因非法 → 40001", bad_reason.get("code") == 40001, bad_reason.get("code"))

        # 作者：自举报两条（已用 2/3 配额，第 3 次留给 [9] 举报已删评论）
        self_post = report(1, p1_id, "OTHER", author_tk)
        check("[3] 举报自己的帖子 → 40001", self_post.get("code") == 40001, self_post.get("code"))
        self_comment = report(2, c1_id, "OTHER", author_tk)
        check("[3] 举报自己的评论 → 40001", self_comment.get("code") == 40001, self_comment.get("code"))

        # 甲：这两条被 DTO 校验直接拦下（字段级），**不消耗配额**，
        #     后面的主流程仍然拿得到 3 次配额
        long_detail = report(1, p1_id, "SPAM", r1_tk, detail="长" * 201)
        check("[3] 说明超 200 字 → 40001", long_detail.get("code") == 40001, long_detail.get("code"))
        bad_type = call("POST", "/api/reports",
                        {"targetType": 9, "targetId": p1_id, "reason": "SPAM"}, token=r1_tk)
        check("[3] 对象类型非法 → 40001", bad_type.get("code") == 40001, bad_type.get("code"))

        pending_base = pending_count(admin_tk)
        print(f"  提交前的待处理数基线 = {pending_base}")

        # ---------- 幂等 + 限流 ----------
        print("\n[4] 正常举报 + 幂等 + 限流（每人 3 次/小时）")
        r_p1_1 = report(1, p1_id, "SPAM", r1_tk, detail="疑似广告，正文带推广链接")
        r_p1_1_id = (r_p1_1.get("data") or {}).get("id")
        check("[4] 举报帖子成功（code=0，status=0）",
              r_p1_1.get("code") == 0 and (r_p1_1.get("data") or {}).get("status") == 0,
              r_p1_1)

        dup = report(1, p1_id, "SPAM", r1_tk)
        check("[4] 重复举报同一目标 → 40909", dup.get("code") == 40909, dup.get("code"))
        dup_rows = db_scalar(f"SELECT COUNT(*) FROM t_report WHERE reporter_id={r1_id} "
                             f"AND target_type=1 AND target_id={p1_id}")
        check("[4] 重复举报没有新增行（库内仍 1 行）", dup_rows == 1, f"rows={dup_rows}")

        r_p1_2 = report(1, p1_id, "ILLEGAL", r2_tk)
        r_p1_2_id = (r_p1_2.get("data") or {}).get("id")
        check("[4] 另一个人举报同一帖子也成功（幂等只针对同一个人）",
              r_p1_2.get("code") == 0, r_p1_2)

        r_c1_1 = report(2, c1_id, "SPAM", r1_tk, detail="评论里留联系方式")
        r_c1_1_id = (r_c1_1.get("data") or {}).get("id")
        check("[4] 举报评论成功", r_c1_1.get("code") == 0, r_c1_1)

        # 至此 r1 已用掉 3 次配额（含那次重复提交：限流在业务之前计数）
        limited = report(1, p2_id, "OTHER", r1_tk)
        check("[4] 第 4 次提交 → 42901（限流在业务之前生效）",
              limited.get("code") == 42901, limited.get("code"))
        limited_rows = db_scalar(f"SELECT COUNT(*) FROM t_report WHERE reporter_id={r1_id} "
                                 f"AND target_id={p2_id}")
        check("[4] 被限流的请求没有落库", limited_rows == 0, f"rows={limited_rows}")

        r_c1_2 = report(2, c1_id, "ABUSE", r2_tk)
        r_c1_2_id = (r_c1_2.get("data") or {}).get("id")
        check("[4] 第二个人举报同一条评论成功", r_c1_2.get("code") == 0, r_c1_2)

        # 库内 4 条举报的原始字段（reason / detail / status）
        stored = db_rows(f"SELECT id, reason, detail, status FROM t_report WHERE id IN "
                         f"({r_p1_1_id},{r_p1_2_id},{r_c1_1_id},{r_c1_2_id}) ORDER BY id")
        check("[4] 4 条举报都落库且 reason/detail/status 正确",
              len(stored) == 4
              and all(row[3] == "0" for row in stored)
              and any(row[1] == "SPAM" and row[2] == "疑似广告，正文带推广链接" for row in stored),
              stored)

        # ---------- 管理端权限 ----------
        print("\n[5] 管理端权限：未登录 40101 / 普通用户 40301")
        check("[5] 未登录读队列 → 40101",
              call("GET", "/api/admin/reports").get("code") == 40101)
        check("[5] 未登录读待处理数 → 40101",
              call("GET", "/api/admin/reports/pending-count").get("code") == 40101)
        check("[5] 普通用户读队列 → 40301", queue(0, r1_tk).get("code") == 40301)
        check("[5] 普通用户读待处理数 → 40301",
              call("GET", "/api/admin/reports/pending-count", token=r1_tk).get("code") == 40301)
        check("[5] 普通用户处理举报 → 40301",
              handle(r_p1_1_id, "dismiss", r1_tk).get("code") == 40301)

        # ---------- 队列 ----------
        print("\n[6] 队列：与库对账 + 倒序 + 字段齐全")
        for st in (0, 1, 2):
            api_total = (queue(st, admin_tk).get("data") or {}).get("total")
            db_total = db_scalar(f"SELECT COUNT(*) FROM t_report WHERE status={st}")
            check(f"[6] status={st} 队列条数与库一致（api={api_total} db={db_total}）",
                  api_total == db_total, (api_total, db_total))

        all_q = queue(None, admin_tk).get("data") or {}
        api_all = all_q.get("total")
        db_all = db_scalar("SELECT COUNT(*) FROM t_report")
        check(f"[6] 不带 status 时返回全部（api={api_all} db={db_all}）", api_all == db_all,
              (api_all, db_all))

        rows = all_q.get("records") or []
        row_ids = [r.get("id") for r in rows]
        check("[6] 队列按 id 倒序（create_time 秒级并列时靠 id 稳定）",
              row_ids == sorted(row_ids, reverse=True), row_ids[:8])

        now_pending = pending_count(admin_tk)
        check(f"[6] 待处理数 = 基线 + 4（{pending_base} → {now_pending}）",
              now_pending == pending_base + 4, (pending_base, now_pending))
        check("[6] 待处理数与库内 status=0 一致",
              now_pending == db_scalar("SELECT COUNT(*) FROM t_report WHERE status=0"), now_pending)

        row_p1_1 = find_row(rows, r_p1_1_id)
        if row_p1_1 is None:
            check("[6] 队列里能找到刚提交的帖子举报", False, "row 缺失")
        else:
            check("[6] 帖子举报行：目标摘要/作者/被举报次数/举报人齐全",
                  row_p1_1.get("targetType") == 1
                  and row_p1_1.get("targetTitle") == p1_title
                  and row_p1_1.get("targetAuthorId") == author_id
                  and row_p1_1.get("targetAuthorNickname") == "内容作者"
                  and row_p1_1.get("targetExists") is True
                  and row_p1_1.get("reportedCount") == 2
                  and row_p1_1.get("reporterId") == r1_id
                  and row_p1_1.get("reporterNickname") == "举报人甲"
                  and row_p1_1.get("reason") == "SPAM"
                  and row_p1_1.get("status") == 0
                  and row_p1_1.get("targetPostId") in (None, 0),
                  {k: row_p1_1.get(k) for k in
                   ("targetTitle", "targetAuthorNickname", "targetExists",
                    "reportedCount", "reporterNickname", "reason", "status")})

        row_c1 = find_row(rows, r_c1_1_id)
        if row_c1 is None:
            check("[6] 队列里能找到刚提交的评论举报", False, "row 缺失")
        else:
            check("[6] 评论举报行：摘要截断/所属帖子/被举报次数齐全",
                  row_c1.get("targetType") == 2
                  and row_c1.get("targetPostId") == p2_id
                  and "这条评论应该被删除" in (row_c1.get("targetExcerpt") or "")
                  and row_c1.get("targetAuthorNickname") == "内容作者"
                  and row_c1.get("reportedCount") == 2
                  and row_c1.get("targetTitle") in (None, ""),
                  {k: row_c1.get(k) for k in
                   ("targetPostId", "targetExcerpt", "reportedCount", "targetTitle")})

        check("[6] 参数校验：status=5 → 40001",
              queue(5, admin_tk).get("code") == 40001, queue(5, admin_tk).get("code"))
        check("[6] 参数校验：size=999 → 40001",
              call("GET", "/api/admin/reports?size=999", token=admin_tk).get("code") == 40001)
        check("[6] 参数校验：page=0 → 40001",
              call("GET", "/api/admin/reports?page=0", token=admin_tk).get("code") == 40001)

        check("[6] 处理不存在的举报 → 40407",
              handle(99999999, "dismiss", admin_tk).get("code") == 40407)
        check("[6] 非法 action → 40001",
              handle(r_p1_1_id, "ban_user", admin_tk).get("code") == 40001)

        # ---------- 驳回 ----------
        print("\n[7] 处理一：驳回（目标必须仍在）")
        dismiss = handle(r_p1_1_id, "dismiss", admin_tk, note="举报材料不足，驳回")
        check("[7] 驳回成功（status=1，无兄弟单被牵连）",
              dismiss.get("code") == 0 and (dismiss.get("data") or {}).get("status") == 1
              and (dismiss.get("data") or {}).get("closedSiblings") == 0, dismiss)
        check("[7] 驳回后帖子仍可访问", call("GET", f"/api/posts/{p1_id}").get("code") == 0)
        trace = db_scalar(f"SELECT COUNT(*) FROM t_report WHERE id={r_p1_1_id} AND status=1 "
                          f"AND handled_by={admin_id} AND handled_at IS NOT NULL "
                          f"AND handle_note='举报材料不足，驳回'")
        check("[7] 留痕落库：status=1 / handled_by=管理员 / handled_at / 备注", trace == 1)
        sibling_still_pending = db_scalar(
            f"SELECT status FROM t_report WHERE id={r_p1_2_id}")
        check("[7] 同目标的另一条举报仍是待处理（驳回不牵连）",
              sibling_still_pending == 0, sibling_still_pending)

        again = handle(r_p1_1_id, "delete_target", admin_tk, note="重复处理试试")
        check("[7] 已处理的举报再处理 → 40910", again.get("code") == 40910, again.get("code"))

        row_handled = find_row(queue(1, admin_tk).get("data", {}).get("records") or [], r_p1_1_id)
        check("[7] 已驳回行在 status=1 队列里带处理人与备注",
              row_handled is not None
              and row_handled.get("handledBy") == admin_id
              and row_handled.get("handledByName") == "审核员"
              and row_handled.get("handleNote") == "举报材料不足，驳回",
              row_handled)

        # ---------- 删帖 ----------
        print("\n[8] 处理二：删除被举报的帖子")
        del_post = handle(r_p1_2_id, "delete_target", admin_tk, note="确认广告，删除")
        check("[8] 删帖处理成功（status=2，closedSiblings=0）",
              del_post.get("code") == 0 and (del_post.get("data") or {}).get("status") == 2
              and (del_post.get("data") or {}).get("closedSiblings") == 0, del_post)
        post_after = call("GET", f"/api/posts/{p1_id}")
        check("[8] 被举报帖子已不可访问（40402）", post_after.get("code") == 40402,
              post_after.get("code"))
        author_posts = ids_of((call("GET", f"/api/posts?authorId={author_id}&size=50")
                               .get("data") or {}).get("records"))
        check("[8] 帖子从作者的列表里消失", p1_id not in author_posts, author_posts)
        # 乙的第 3 次配额用在这里
        gone = report(1, p1_id, "SPAM", r2_tk)
        check("[8] 再举报这个已删帖子 → 40402", gone.get("code") == 40402, gone.get("code"))
        check("[8] 管理端删除内容与作者自己删帖副作用一致（status=3 已封禁）",
              db_scalar(f"SELECT status FROM t_post WHERE id={p1_id}") == 3,
              db_scalar(f"SELECT status FROM t_post WHERE id={p1_id}"))

        # ---------- 删评论 ----------
        print("\n[9] 处理三：删除被举报的评论（顺带关闭同目标兄弟单）")
        del_comment = handle(r_c1_1_id, "delete_target", admin_tk, note="确认引流，删除评论")
        check("[9] 删评论处理成功（status=2，closedSiblings=1）",
              del_comment.get("code") == 0 and (del_comment.get("data") or {}).get("status") == 2
              and (del_comment.get("data") or {}).get("closedSiblings") == 1, del_comment)
        comments_left = ids_of(call("GET", f"/api/posts/{p2_id}/comments").get("data"))
        check("[9] 被举报评论已从列表消失", c1_id not in comments_left, comments_left)
        # 作者的第 3 次配额用在这里（目标已删 → 服务层先报 40403，早于"举报自己"的判断）
        gone_comment = report(2, c1_id, "SPAM", author_tk)
        check("[9] 再举报这个已删评论 → 40403", gone_comment.get("code") == 40403,
              gone_comment.get("code"))
        sibling_closed = db_scalar(
            f"SELECT COUNT(*) FROM t_report WHERE id={r_c1_2_id} AND status=2 "
            f"AND handled_by={admin_id} AND handled_at IS NOT NULL "
            f"AND handle_note='{SIBLING_CLOSE_NOTE}'")
        check("[9] 兄弟单被系统代关（status=2 + 管理员留痕 + 系统备注）", sibling_closed == 1)
        stale = find_row(queue(2, admin_tk).get("data", {}).get("records") or [], r_c1_1_id)
        check("[9] 已删内容的举报行标出 targetExists=false（队列不再指向幽灵内容）",
              stale is not None and stale.get("targetExists") is False, stale)

        # ---------- 收尾 ----------
        print("\n[10] 收尾：待处理数回到基线")
        final_pending = pending_count(admin_tk)
        check(f"[10] 待处理数回到基线（{final_pending} == {pending_base}）",
              final_pending == pending_base, (final_pending, pending_base))
        check("[10] 本次 4 条举报全部已处理（无遗留待处理）",
              db_scalar(f"SELECT COUNT(*) FROM t_report WHERE id IN "
                        f"({r_p1_1_id},{r_p1_2_id},{r_c1_1_id},{r_c1_2_id}) AND status=0") == 0)
        check("[10] 举报记录不删（审计材料保留，仍能查到这 4 条）",
              db_scalar(f"SELECT COUNT(*) FROM t_report WHERE id IN "
                        f"({r_p1_1_id},{r_p1_2_id},{r_c1_1_id},{r_c1_2_id})") == 4)
    finally:
        stop_app(proc, log)

    print("\n" + "=" * 62)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    if FAILURES:
        print("失败项：")
        for name in FAILURES:
            print(f"  - {name}")
    print("=" * 62)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())