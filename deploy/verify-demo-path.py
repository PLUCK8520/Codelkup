#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
金路径端到端走查 —— 把"面试官/真实用户会走的那条路"跑成一条链。

与 verify-deploy.py 的分工（两者互补，不互相冒充）：
  - verify-deploy.py = **站点级探针**：能不能开、反代通不通、鉴权语义对不对。故意不依赖业务数据。
  - 本脚本 = **人的一条完整路径**：登录 → 发帖（提问）→ 回答 → 点赞 → 收藏 → 关注
    → 通知（列表 / 未读 / **SSE 实时推送**）→ 采纳闭环 → 检索 → 草稿 → 收藏页 → 排行 → AI。
    每一步都是上一步的自然延续，任何一步断链都能立刻看出"用户在这里卡住了"。

为什么值得单独成脚本（PRE-MORTEM T1-1）：
  本地 700+ 断言证的是"技术面"，而"注册→发帖→点赞→通知→AI"这条**人走的路**
  原先只在 checklist 里靠人工点。脚本化后：上线当天、以及之后每次改动，
  都能一条命令复跑同一条路径。

用法：
    python3 verify-demo-path.py --site http://localhost:8090        # 本地 compose 栈
    python3 verify-demo-path.py --site https://demo.example.com     # 部署后（公网）

账号策略（自动选择，避免污染与配额压力）：
    1) 优先**登录演示账号**（默认 ab@ / xiaoman@，密码 Codelkup@Demo2026）——
       公开站通常关着注册，这是唯一可行路径；
    2) 演示账号不存在但注册可用时，注册两个一次性账号（goldenpath_a@ / goldenpath_b@）。

副作用的如实说明（走查会留下什么）：
    - 创建的帖子 / 评论 / 草稿在结尾**全部清理**；点赞 / 收藏 / 关注**全部还原**；
    - 不可逆的小痕迹：被采纳方声望 +15、A 的通知箱多 3~4 条（评论/点赞/关注）。
      这两项是"人真的来过"的一部分，与 verify-deploy 留下的自检账号同类，不单独清理。

退出码：0 全通过；1 有失败
"""
from __future__ import annotations

import argparse
import json
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
results = []


def _call_once(url, method, body, token, timeout):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with _opener.open(req, timeout=timeout) as resp:
            raw = resp.read()
            try:
                return resp.status, json.loads(raw.decode("utf-8") or "{}")
            except Exception:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            return e.code, raw
    except Exception as e:
        return -1, str(e)


def call(url, method="GET", body=None, token=None, timeout=20):
    """HTTP 调用（带连接级重试）。

    ⚠️ 本机（Windows + Docker Desktop）的宿主端口转发存在**瞬时断流**：容器一直 healthy，
    但 127.0.0.1:8090 会拒连/超时十几秒（Linux 服务器上不存在这一层）。
    所以 GET / PUT / DELETE（幂等语义）遇连接级失败自动重试一次；
    **POST 不重试** —— 超时可能意味着"其实已经写进去了"，重试等于写两条评论/两个关注。
    """
    attempts = 3 if method in ("GET", "PUT", "DELETE") else 1
    last = ""
    for i in range(attempts):
        st, b = _call_once(url, method, body, token, timeout)
        if st != -1:
            return st, b
        last = b
        if i + 1 < attempts:
            time.sleep(1.2)
    return -1, last


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("[PASS] " if ok else "[FAIL] ") + name + ("  ->  " + str(detail)[:200] if detail else ""))


def code_of(b):
    return b.get("code") if isinstance(b, dict) else None


def data_of(b):
    return b.get("data") if isinstance(b, dict) else None


def ids_of(body):
    d = data_of(body) or {}
    recs = d.get("records") if isinstance(d, dict) else None
    return {r.get("id") for r in (recs or [])}


# ==================== 账号 ====================

def login_or_register(base, email, password, nickname, fallback_email):
    """先登录演示账号；失败再尝试注册一次性账号；注册被拒（已存在）则回登兜底账号。

    返回 (token, uid, 来源说明) 或 (None, None, 原因)。

    ⚠️ 第三步是"可复跑"的关键（脚本承诺"对任意 --site 可复跑"）：上一次走查
    注册的 goldenpath_* 账号会留在库里，第二次走查必然 40901 ——
    用同一口令回登即可，脚本因此可以重复执行，而不是每跑一次就多建两个账号。
    """
    st, b = call(base + "/api/auth/login", "POST", {"email": email, "password": password})
    if code_of(b) == 0:
        d = data_of(b) or {}
        return d.get("accessToken"), (d.get("user") or {}).get("id"), "演示账号登录"
    login_code = code_of(b)

    st, b = call(base + "/api/auth/register", "POST",
                 {"email": fallback_email, "password": password, "nickname": nickname})
    if code_of(b) == 0:
        d = data_of(b) or {}
        return d.get("accessToken"), (d.get("user") or {}).get("id"), "注册一次性账号(%s)" % fallback_email
    register_code = code_of(b)

    st, b2 = call(base + "/api/auth/login", "POST",
                  {"email": fallback_email, "password": password})
    if code_of(b2) == 0:
        d = data_of(b2) or {}
        return d.get("accessToken"), (d.get("user") or {}).get("id"), \
            "复用上一次走查账号(%s)" % fallback_email
    return None, None, "登录失败(%s) 且注册失败(%s)" % (login_code, register_code)


# ==================== SSE（只读一条推送，不做完整客户端）====================

def sse_connect(base, token, timeout=15):
    """换票 → 建流。返回（响应对象, 说明）。失败时响应为 None。"""
    st, b = call(base + "/api/auth/sse-ticket", "POST", token=token)
    ticket = (data_of(b) or {}).get("ticket") if code_of(b) == 0 else None
    if not ticket:
        return None, "签票失败 code=%s" % code_of(b)
    req = urllib.request.Request(base + "/api/notifications/stream?ticket=" + ticket,
                                 headers={"Accept": "text/event-stream",
                                          "Accept-Encoding": "identity"})
    try:
        return _opener.open(req, timeout=timeout), "ok"
    except Exception as e:
        return None, str(e)


def sse_wait_event(resp, want="unread", seconds=6.0):
    """按行读直到出现 `event: unread` + `data:`（或超时）。返回 data 文本或 None。"""
    deadline = time.time() + seconds
    event_name = None
    while time.time() < deadline:
        try:
            line = resp.readline()
        except (socket.timeout, TimeoutError):
            return None
        except Exception:
            return None
        if not line:
            return None
        text = line.decode("utf-8", "replace").rstrip("\r\n")
        if text.startswith("event:"):
            event_name = text[6:].strip()
        elif text.startswith("data:"):
            if event_name == want:
                return text[5:].strip()
            event_name = None
    return None


# ==================== 主流程 ====================

def main():
    ap = argparse.ArgumentParser(description="金路径端到端走查（对部署后的站点 / compose 栈）")
    ap.add_argument("--site", required=True, help="站点根地址，如 https://demo.example.com 或 http://localhost:8090")
    ap.add_argument("--user-a", default="ab@codelkup.dev", help="主账号（发帖/采纳/收通知）")
    ap.add_argument("--user-b", default="xiaoman@codelkup.dev", help="对手账号（评论/点赞/收藏/关注）")
    ap.add_argument("--password", default="Codelkup@Demo2026", help="演示账号统一口令")
    ap.add_argument("--register-domain", default="codelkup.dev", help="注册兜底账号用的邮箱域名")
    ap.add_argument("--keep", action="store_true", help="保留现场（不清理帖子/评论/草稿与互动），用于人工查看")
    args = ap.parse_args()
    base = args.site.rstrip("/")
    # ⚠️ 本机（Windows + Docker Desktop）用 `localhost` 时，Python 会优先解析到 ::1（IPv6），
    #    而那条 IPv6 回环转发链路会**挂住**（TCP 连上但隧道不建，表现为随机超时十几二十秒）——
    #    今天所有"端口转发瞬时断流"的假象都是它。统一改走 127.0.0.1 即稳定；
    #    Linux 服务器上两者等价，这个替换是空操作。
    if "//localhost" in base:
        base = base.replace("//localhost", "//127.0.0.1")
        print("提示：已把 localhost 换成 127.0.0.1（本机 Docker Desktop 的 IPv6 回环链路会挂住）")
    mark = "金路径%d" % int(time.time())
    print("金路径走查：%s（标记 %s）\n" % (base, mark))

    # ---------- [0] 站点在线（含预热）----------
    # 上面的 localhost→127.0.0.1 规范化已消掉今天反复出现的"随机超时"；
    # 这里再留一层短超时重试，兜住"引擎刚起来/容器刚重建"时的短暂不可达 ——
    # 别让环境唤醒算进业务断言，否则会得到一个假失败。
    st, b = call(base + "/api/system/health", timeout=8)
    tries = 1
    while code_of(b) != 0 and tries < 5:
        tries += 1
        time.sleep(2)
        st, b = call(base + "/api/system/health", timeout=8)
    if tries > 1:
        print("     （站点预热：第 %d 次才通）" % tries)
    d = data_of(b)
    check("[0] 站点在线（经 nginx 的健康检查）",
          code_of(b) == 0 and isinstance(d, dict) and d.get("mysql") == "UP" and d.get("redis") == "UP", d)
    st, b = call(base + "/api/system/config")
    reg_open = (data_of(b) or {}).get("registrationEnabled") if isinstance(data_of(b), dict) else None
    print("     （注册开关：%s —— 账号策略据此自动选择）" % reg_open)

    # ---------- [1] 两个账号就绪 ----------
    tk_a, uid_a, how_a = login_or_register(base, args.user_a, args.password, "金路径A",
                                           "goldenpath_a@%s" % args.register_domain)
    tk_b, uid_b, how_b = login_or_register(base, args.user_b, args.password, "金路径B",
                                           "goldenpath_b@%s" % args.register_domain)
    check("[1] 主账号就绪（A）", bool(tk_a), how_a)
    check("[1] 对手账号就绪（B）", bool(tk_b), how_b)
    if not (tk_a and tk_b):
        check("[1] 账号未就绪，后续步骤无法继续", False, "请用 --user-a/--user-b/--password 指定可用账号")
        return 1

    st, b = call(base + "/api/users/me")
    check("[1] 未登录读本人资料 → 40101（金路径上的第一道墙）", code_of(b) == 40101, code_of(b))

    # 清理现场：正常路径在结尾调用；中途失败也要调用一次 —— 别把演示站留下半截脏数据。
    # 只清理**本脚本创建的**东西（按记录下来的 id / scope），绝不碰演示数据本身。
    qid = None
    root_cmt = None
    reply_cmt = None
    cleaned = False

    def do_cleanup():
        nonlocal cleaned
        if cleaned:
            return
        cleaned = True
        if args.keep:
            print("\n[9] --keep：保留现场，跳过清理")
            return
        print("\n[9] 清理现场")
        if not qid:
            print("     （没有创建过帖子，跳过）")
            return
        st, b = call(base + "/api/users/%d/follow" % uid_a, "DELETE", token=tk_b)
        check("[9] 取消关注（还原）", code_of(b) == 0, code_of(b))
        st, b = call(base + "/api/posts/%d/like" % qid, "DELETE", token=tk_b)
        check("[9] 取消点赞（还原）", code_of(b) == 0, code_of(b))
        st, b = call(base + "/api/posts/%d/collect" % qid, "DELETE", token=tk_b)
        check("[9] 取消收藏（还原）", code_of(b) == 0, code_of(b))
        if reply_cmt:
            call(base + "/api/comments/%d/like" % reply_cmt, "DELETE", token=tk_b)  # 评论点赞也还原
        st, b = call(base + "/api/posts/%d" % qid, "DELETE", token=tk_a)
        check("[9] A 删帖成功（评论随帖一并不可见）", code_of(b) == 0, code_of(b))
        st, b = call(base + "/api/posts/%d" % qid)
        check("[9] 删除后详情不可读（40402）", code_of(b) == 40402, code_of(b))
        st, b = call(base + "/api/users/me/collections", token=tk_b)
        check("[9] 取消收藏后收藏页不再含该帖", qid not in ids_of(b), code_of(b))
        # 中途失败时草稿可能还没删 —— 接口幂等，补一刀
        call(base + "/api/drafts/edit:%d" % qid, "DELETE", token=tk_a)

    boards_st, boards_body = call(base + "/api/boards")
    boards = data_of(boards_body)
    board_id = boards[0].get("id") if isinstance(boards, list) and boards else None
    if not board_id:
        # 打印原始返回：自检的失败信息必须能自证（否则"data 为空"和"根本没通"看起来一模一样）
        check("[1] 板块列表可读（发帖需要它的 boardId）", False,
              "status=%s body=%s" % (boards_st, str(boards_body)[:180]))
        do_cleanup()
        return 1

    # ---------- [2] 内容链：A 提问，B 回答 ----------
    st, b = call(base + "/api/posts", "POST", {
        "title": "%s：如何验证一条完整的金路径？" % mark,
        "content": "## 背景\n\n这条提问用于 **%s** 走查：验证「发文→回答→互动→通知→采纳」整条链路。\n\n"
                   "关键词：%s" % (mark, mark),
        "boardId": board_id, "type": 1, "tags": ["金路径", "走查"],
    }, token=tk_a)
    qid = data_of(b).get("id") if code_of(b) == 0 else None
    check("[2] A 发提问帖成功（type=1 + 标签 + Markdown）", bool(qid), code_of(b))
    if not qid:
        do_cleanup()
        return 1

    st, b = call(base + "/api/posts/%d" % qid)
    check("[2] 详情免登录可读且正文原样（Markdown 不被转义）",
          code_of(b) == 0 and mark in ((data_of(b) or {}).get("content") or ""), code_of(b))

    st, b = call(base + "/api/posts/%d/comments" % qid, "POST",
                 {"content": "答：先跑通 %s 的链路，再看通知与采纳是否闭环。" % mark}, token=tk_b)
    root_cmt = data_of(b).get("id") if code_of(b) == 0 else None
    check("[2] B 回答（一级评论）成功", bool(root_cmt), code_of(b))
    if not root_cmt:
        do_cleanup()
        return 1

    st, b = call(base + "/api/posts/%d/comments" % qid, "POST",
                 {"content": "追问：%s 的清理现场要不要保留？" % mark, "parentId": root_cmt}, token=tk_a)
    reply_cmt = data_of(b).get("id") if code_of(b) == 0 else None
    check("[2] A 回复该评论（二级）成功", bool(reply_cmt), code_of(b))
    if not reply_cmt:
        do_cleanup()
        return 1

    st, b = call(base + "/api/posts/%d/comments" % qid)
    cmts = data_of(b) or []
    # ⚠️ 树形字段叫 replies（不是 children）—— 后端 CommentVO 的定义
    check("[2] 评论树形返回：一级含回复",
          len(cmts) >= 1 and any(child.get("id") == reply_cmt
                                 for c in cmts for child in (c.get("replies") or [])),
          [(c.get("id"), len(c.get("replies") or [])) for c in cmts][:3])

    # ---------- [3] 互动链：B → A ----------
    st, b = call(base + "/api/posts/%d/like" % qid, "POST", token=tk_b)
    check("[3] B 点赞成功", code_of(b) == 0, code_of(b))
    st, b = call(base + "/api/posts/%d" % qid)
    check("[3] 详情 likeCount=1（计数写穿）", (data_of(b) or {}).get("likeCount") == 1,
          (data_of(b) or {}).get("likeCount"))

    st, b = call(base + "/api/posts/%d/collect" % qid, "POST", token=tk_b)
    check("[3] B 收藏成功", code_of(b) == 0, code_of(b))
    st, b = call(base + "/api/users/me/collections", token=tk_b)
    check("[3] 收藏页出现该帖（收藏闭环）", qid in ids_of(b), code_of(b))

    # 粉丝数在 follow-state 里（公开主页 VO 不含计数 —— L-2 收过暴露面）
    fs_st, fs_body = call(base + "/api/users/%d/follow-state" % uid_a, token=tk_b)
    before_followers = (data_of(fs_body) or {}).get("followerCount")
    st, b = call(base + "/api/users/%d/follow" % uid_a, "POST", token=tk_b)
    fs_st2, fs_body2 = call(base + "/api/users/%d/follow-state" % uid_a, token=tk_b)
    after_followers = (data_of(fs_body2) or {}).get("followerCount")
    check("[3] B 关注 A → 粉丝数 +1",
          code_of(b) == 0 and isinstance(before_followers, int) and after_followers == before_followers + 1,
          "before=%s after=%s (body=%s)" % (before_followers, after_followers, str(fs_body2)[:80]))

    st, b = call(base + "/api/posts/%d/like" % qid, "POST", token=tk_a)
    check("[3] 自赞被拒 40001（体验边界：后端硬拦，不只藏按钮）", code_of(b) == 40001, code_of(b))

    # ---------- [4] 通知链（A 视角）----------
    # ⚠️ 不要用 `... or 0` 兜底：瞬时失败会被掩成 0，断言失败时也看不出"是没通还是在真的 0"
    uc_st, uc_body = call(base + "/api/notifications/unread-count", token=tk_a)
    unread_before = data_of(uc_body) if code_of(uc_body) == 0 else None
    st, b = call(base + "/api/notifications?size=20", token=tk_a)
    recs = (data_of(b) or {}).get("records") or []
    check("[4] 通知列表可读且带发送者昵称", code_of(b) == 0 and any(r.get("senderNickname") for r in recs),
          [r.get("senderNickname") for r in recs][:3])
    check("[4] 未读数至少 +3（评论 / 点赞 / 关注三条都到了）",
          isinstance(unread_before, int) and unread_before >= 3,
          "status=%s body=%s" % (uc_st, str(uc_body)[:120]))

    st, b = call(base + "/api/notifications/unread-counts", token=tk_a)
    grouped = {x.get("type"): x.get("count") for x in (data_of(b) or [])}
    check("[4] 分组角标之和 == 总未读数（同一份数据两个口径）",
          sum(grouped.values()) == unread_before, "sum=%s total=%s" % (sum(grouped.values()), unread_before))

    stream, why = sse_connect(base, tk_a)
    check("[4] A 的 SSE 长连接建立（一次性 ticket 换票）", stream is not None, why)
    if stream is not None:
        snap = sse_wait_event(stream, "unread", 5)
        check("[4] 连上立刻收到当前未读快照（event: unread）", snap is not None, snap)
        # 触发一条**不会被去重吞掉**的新通知：点赞 A 的那条回复（评论点赞）。
        # ⚠️ 不要用"B 再评论一次"做触发 —— 通知去重的口径是"同一人对同一帖的同类未读只留一条"，
        #    第二次评论会被有意合并掉、压根不产生推送（那不是 bug，是设计）。
        st, b = call(base + "/api/comments/%d/like" % reply_cmt, "POST", token=tk_b)
        check("[4] B 点赞 A 的回复（触发一条新通知）", code_of(b) == 0, code_of(b))
        pushed = sse_wait_event(stream, "unread", 6)
        pushed_count = json.loads(pushed).get("count") if pushed else None
        check("[4] B 点赞后 A 在 6 秒内收到推送（经 nginx 未被缓冲）",
              pushed is not None and pushed_count is not None, pushed)
        poll_st, poll_body = call(base + "/api/notifications/unread-count", token=tk_a)
        poll_count = data_of(poll_body) if code_of(poll_body) == 0 else None
        check("[4] 推送值 == 轮询接口的未读数（两条路径同一份 COUNT）",
              poll_count is not None and poll_count == pushed_count,
              "推送=%s 轮询=%s status=%s body=%s" % (pushed_count, poll_count, poll_st, str(poll_body)[:100]))
        # 评论点赞的还原已在清理阶段处理（见 do_cleanup）
        try:
            stream.close()
        except Exception:
            pass

    # ---------- [5] 采纳闭环 ----------
    # 带 sort=latest：演示账号可能有几十条历史提问，不排序会被分页截断成"不在这页"的假失败
    st, b = call(base + "/api/posts?type=1&solved=false&authorId=%d&sort=latest&size=50" % uid_a)
    check("[5] 采纳前：该提问在「待解决」里", qid in ids_of(b), "waiting_ids=%s" % sorted(ids_of(b))[:5])
    rep_b_before_raw = call(base + "/api/users/%d" % uid_b)
    rep_b_before = (data_of(rep_b_before_raw[1]) or {}).get("reputation")

    st, b = call(base + "/api/posts/%d/accept/%d" % (qid, root_cmt), "POST", token=tk_a)
    check("[5] A 采纳 B 的回答成功", code_of(b) == 0, code_of(b))
    st, b = call(base + "/api/posts?type=1&solved=true&authorId=%d&sort=latest&size=50" % uid_a)
    check("[5] 采纳后：从「待解决」流转到「已解决」", qid in ids_of(b), "solved_ids=%s" % sorted(ids_of(b))[:5])
    rep_b_after_raw = call(base + "/api/users/%d" % uid_b)
    rep_b_after = (data_of(rep_b_after_raw[1]) or {}).get("reputation")
    check("[5] 回答被采纳 → B 声望 +15",
          isinstance(rep_b_before, int) and rep_b_after == rep_b_before + 15,
          "before=%s after=%s body=%s" % (rep_b_before, rep_b_after, str(rep_b_after_raw[1])[:100]))

    # ---------- [6] 检索与浏览 ----------
    st, b = call(base + "/api/search/posts?keyword=%s" % urllib.parse.quote(mark))
    check("[6] 中文检索命中刚发的帖（ngram 生效）", qid in ids_of(b), "命中 %s 条" % ((data_of(b) or {}).get("total")))
    st, b = call(base + "/api/search/posts?keyword=%s&authorId=%d" % (urllib.parse.quote("金路径"), uid_a))
    check("[6] 按作者过滤检索命中", qid in ids_of(b), code_of(b))

    st, b = call(base + "/api/users/ranking?limit=10")
    rank_rows = data_of(b) or []
    reps = [r.get("reputation") for r in rank_rows]
    check("[6] 排行榜可读：名次从 1 开始且声望非增",
          bool(rank_rows) and [r.get("rank") for r in rank_rows][0] == 1
          and all(reps[i] >= reps[i + 1] for i in range(len(reps) - 1)), reps[:5])

    st, b = call(base + "/api/users/%d" % uid_a)
    prof = data_of(b) or {}
    check("[6] 公开主页可读且不含 role（管理员不可枚举）", code_of(b) == 0 and "role" not in prof,
          list(prof.keys())[:8])

    # ---------- [7] 草稿链（scope 用 edit:<本帖>，绝不碰别人的 new 草稿）----------
    scope = "edit:%d" % qid
    st, b = call(base + "/api/drafts", "PUT",
                 {"scope": scope, "title": "%s 草稿" % mark,
                  "content": "草稿正文 %s（走查用，读完即删）" % mark,
                  "boardId": board_id, "type": 0}, token=tk_a)
    check("[7] 保存草稿（upsert）成功且带服务端时间",
          code_of(b) == 0 and (data_of(b) or {}).get("updateTime"), code_of(b))
    st, b = call(base + "/api/drafts/" + scope, token=tk_a)
    check("[7] 读回草稿内容一致", mark in ((data_of(b) or {}).get("content") or ""), code_of(b))
    st, b = call(base + "/api/drafts/" + scope, "DELETE", token=tk_a)
    check("[7] 删除草稿成功", code_of(b) == 0, code_of(b))
    st, b = call(base + "/api/drafts/" + scope, token=tk_a)
    check("[7] 删除后读回为 null（幂等清理）", code_of(b) == 0 and data_of(b) is None, data_of(b))

    # ---------- [8] AI 链（没配 key 时降级也算过）----------
    st, b = call(base + "/api/ai/similar-questions", "POST", {"title": "%s 如何验证" % mark}, token=tk_a)
    check("[8] 相似问题可读（复用检索、不调 LLM）", code_of(b) == 0 and isinstance(data_of(b), list), code_of(b))
    st, b = call(base + "/api/ai/ask", "POST", {"question": "%s 这条链路验证了什么？" % mark}, token=tk_a)
    ans = data_of(b) or {}
    check("[8] RAG 问答结构完整（answer + citations + degraded 三件套）",
          code_of(b) == 0 and "answer" in ans and "citations" in ans and "degraded" in ans,
          {"degraded": ans.get("degraded"), "reason": ans.get("reason")})
    check("[8] 未配 key 时是降级而不是报错（degraded=true 且无 50001）",
          code_of(b) == 0 and (ans.get("degraded") is True or ans.get("answer")), ans.get("degraded"))

    # ---------- [9] 清理现场（把"人走过"的痕迹还原）----------
    do_cleanup()

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("\n" + "=" * 62)
    print("金路径端到端走查：%d/%d 通过" % (passed, total))
    if passed < total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  ->  %s" % (name, str(detail)[:260]))
    print("=" * 62)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())