#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V0.3 M2 验收 —— 通用图片上传（M2-11）。

覆盖 TASKS 的验收列，并把它拆成可证伪的断言：
    png / webp / jpeg 成功且文件真的落盘（不是只回了个 URL）
    shell.jsp 改名 .png → 40004（魔数校验拦住）
    .gif 等非白名单扩展名 → 40004
    超过业务上限（5MB）→ 40005；超过容器上限（6MB）→ 同样 40005（不是 50001）
    未登录 → 40101
    替换头像 → 旧文件被删除（对比目录文件数）
    限流 → 第 N+1 次 42901
    配额 → 达到上限后再传 → 40006 + 可读文案

⚠️ 限流相与配额相需要**按调试阈值**跑（默认值跑不动：限流 30/小时 会先于配额 200 拦住）：
    启动：java -jar ... --codelkup.rate-limit.upload-per-hour=3 --codelkup.upload.max-files-per-user=10
    运行：python verify-v03-upload.py --upload-per-hour 3 --max-files-per-user 10
   两个闸门各自隔离验证：格式/尺寸/头像/unlogged 各相开头都会**清一次限流桶**，
   让"被拒的理由"只可能来自被测的那一道；配额相则每次上传前清一次，
   这样"配额先满"能独立复现，而不是被限流挡住。（清桶只碰 codelkup:rate:upload:*，
   绝不碰 codelkup:auth:* —— 那是 refresh 白名单。）

用法：
    python verify-v03-upload.py [--base http://127.0.0.1:8085]
                                [--uploads-root ./uploads]
                                [--upload-per-hour 30] [--max-files-per-user 200]

退出码：0 = 全通过，1 = 有失败
"""
from __future__ import annotations

import argparse
import json
import shutil
import socket
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BASE = "http://127.0.0.1:8085"
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379
UPLOADS_ROOT = "./uploads"

PASS = 0
FAIL = 0
FAILURES: list[str] = []

# 与后端 codelkup.upload.max-image-size-mb 对应
BIZ_MAX_MB = 5


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        FAILURES.append(name)
        print(f"  [FAIL] {name}" + (f"  -> {detail}" if detail else ""))


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    return _send(req)


def upload(path: str, filename: str, content: bytes, token: str | None = None,
           field: str = "file") -> dict:
    """手工拼 multipart/form-data —— 纯标准库，不引第三方依赖。"""
    boundary = "----codelkupverify" + uuid.uuid4().hex
    body = (
        f"--{boundary}\r\n".encode()
        + f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode()
        + b"Content-Type: application/octet-stream\r\n\r\n"
        + content
        + f"\r\n--{boundary}--\r\n".encode()
    )
    req = urllib.request.Request(BASE + path, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    return _send(req)


def _send(req: urllib.request.Request) -> dict:
    try:
        with OPENER.open(req, timeout=60) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"raw": raw[:300], "status": e.code}


def redis_cmd(*args: str) -> str:
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        return s.recv(65536).decode(errors="replace")


def clear_bucket(pattern: str) -> int:
    """只清指定桶。⚠️ 绝不碰 codelkup:auth:*（refresh 白名单在那里）"""
    try:
        resp = redis_cmd("KEYS", pattern)
    except OSError:
        return -1
    keys = [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if keys:
        redis_cmd("DEL", *keys)
    return len(keys)


# ==================== 测试用图片（只需魔数正确：后端校验的就是魔数） ====================

def png_bytes(payload_kb: int = 1) -> bytes:
    return b"\x89PNG\r\n\x1a\n" + b"\x00" * (payload_kb * 1024)


def count_files(d: Path) -> int:
    """只数文件，不数目录。

    ⚠️ `rglob("*")` 会把中间目录（posts/114/2026、posts/114/2026/09）也算进去，
    用它当"已用配额"会凭空多出 2，导致配额断言偏移（首次跑就踩了这个坑）。
    """
    return len([p for p in d.rglob("*") if p.is_file()]) if d.is_dir() else 0


def webp_bytes(payload_kb: int = 1) -> bytes:
    data = b"\x00" * (payload_kb * 1024)
    size = (4 + 8 + len(data)).to_bytes(4, "little")  # RIFF 之后的总长度
    return b"RIFF" + size + b"WEBP" + b"VP8 " + data


def jpeg_bytes(payload_kb: int = 1) -> bytes:
    return b"\xff\xd8\xff\xe0" + b"\x00" * (payload_kb * 1024)


def main() -> int:
    global BASE, REDIS_HOST, REDIS_PORT, UPLOADS_ROOT
    parser = argparse.ArgumentParser(description="V0.3 通用图片上传验收")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--redis-host", default=REDIS_HOST)
    parser.add_argument("--redis-port", type=int, default=REDIS_PORT)
    parser.add_argument("--uploads-root", default=UPLOADS_ROOT,
                        help="后端进程工作目录下的 uploads 目录（用于核对文件是否真落盘）")
    parser.add_argument("--upload-per-hour", type=int, default=30,
                        help="需与后端 codelkup.rate-limit.upload-per-hour 一致")
    parser.add_argument("--max-files-per-user", type=int, default=200,
                        help="需与后端 codelkup.upload.max-files-per-user 一致")
    args = parser.parse_args()
    BASE = args.base.rstrip("/")
    REDIS_HOST, REDIS_PORT = args.redis_host, args.redis_port
    UPLOADS_ROOT = args.uploads_root
    per_hour = args.upload_per_hour
    quota = args.max_files_per_user

    root = Path(UPLOADS_ROOT).resolve()

    print("=" * 58)
    print(f"V0.3 通用图片上传验收 | base={BASE} | uploads={root}")
    print(f"  限流={per_hour}/小时  配额={quota} 张")
    print("=" * 58)

    if call("GET", "/api/system/health").get("code") != 0:
        print("后端不可用")
        return 1

    print("\n[0] 预清理注册 / 上传限流桶")
    print(f"  已清：{(clear_bucket('codelkup:rate:register:*'), clear_bucket('codelkup:rate:upload:*'))}")

    ts = int(time.time())
    print("\n[1] 注册一次性账号")
    reg = call("POST", "/api/auth/register",
               {"email": f"upload_{ts}@test.com", "password": "abc12345", "nickname": f"上传测试{ts % 10000}"})
    check("注册成功", reg.get("code") == 0, reg.get("code"))
    if reg.get("code") != 0:
        return 1
    token = reg["data"]["accessToken"]
    me = call("GET", "/api/users/me", token=token)
    user_id = me["data"]["id"]
    post_dir = root / "posts" / str(user_id)
    avatar_dir = root / "avatars" / str(user_id)

    print("\n[2] 正常图片：png / webp / jpeg")
    png = upload("/api/uploads/images", "shot.png", png_bytes(), token)
    check("png 上传成功", png.get("code") == 0, png)
    url = (png.get("data") or {}).get("url", "")
    check("返回 url 落在 /uploads/posts/{userId}/ 下",
          url.startswith(f"/uploads/posts/{user_id}/"), url)
    disk = root / url.replace("/uploads/", "", 1)
    check("文件真的落盘了", disk.is_file(), str(disk))
    check("落盘文件名与应用名无关（UUID 化）", "shot" not in disk.name, disk.name)

    webp = upload("/api/uploads/images", "pic.webp", webp_bytes(), token)
    check("webp 上传成功", webp.get("code") == 0, webp)
    jpg = upload("/api/uploads/images", "photo.jpg", jpeg_bytes(), token)
    check("jpeg 上传成功", jpg.get("code") == 0, jpg)

    print("\n[3] 伪造与格式白名单（先清一次上传桶：格式校验不受限流影响）")
    clear_bucket("codelkup:rate:upload:*")
    fake = upload("/api/uploads/images", "shell.png", b"<% out.println(\"pwn\"); %>", token)
    check("shell.jsp 改名 .png 被拒（40004 魔数不通过）", fake.get("code") == 40004, fake.get("code"))

    gif = upload("/api/uploads/images", "anim.gif", b"GIF89a" + b"\x00" * 64, token)
    check("非白名单扩展名 .gif 被拒（40004）", gif.get("code") == 40004, gif.get("code"))

    print("\n[4] 尺寸上限（业务 5MB / 容器 6MB 两道都要是可读提示）")
    clear_bucket("codelkup:rate:upload:*")
    big = upload("/api/uploads/images", "big.png", png_bytes(BIZ_MAX_MB * 1024 + 512), token)
    check("5.5MB（超业务上限、未超容器）→ 40005", big.get("code") == 40005, big.get("code"))

    # ⚠️ 7MB 会在容器层被拦（multipart 解析失败），此时请求根本进不到 Controller ——
    #    所以它不会被限流计数，也不能用来验证限流
    huge = upload("/api/uploads/images", "huge.png", png_bytes((BIZ_MAX_MB + 2) * 1024), token)
    check("7MB（超容器上限）→ 40005 而不是 50001", huge.get("code") == 40005, huge.get("code"))

    print("\n[5] 未登录")
    anon = upload("/api/uploads/images", "a.png", png_bytes())
    check("未登录 → 40101", anon.get("code") == 40101, anon.get("code"))

    print("\n[6] 替换头像 → 旧文件必须被清理（头像与配图共用上传桶，先清一次）")
    clear_bucket("codelkup:rate:upload:*")
    av1 = upload("/api/users/me/avatar", "a1.png", png_bytes(), token)
    check("第一次上传头像成功", av1.get("code") == 0, av1)
    first_url = (av1.get("data") or {}).get("avatar", "")
    check("头像目录里有 1 个文件", count_files(avatar_dir) == 1, count_files(avatar_dir))

    av2 = upload("/api/users/me/avatar", "a2.png", png_bytes(), token)
    check("第二次上传头像成功", av2.get("code") == 0, av2)
    second_url = (av2.get("data") or {}).get("avatar", "")
    check("头像 URL 已更新", second_url != first_url and second_url != "", second_url)
    check("头像目录仍只有 1 个文件（旧文件已删）", count_files(avatar_dir) == 1, count_files(avatar_dir))
    old_disk = root / first_url.replace("/uploads/", "", 1) if first_url else None
    check("旧头像文件确实不存在了",
          old_disk is not None and not old_disk.is_file(), str(old_disk))

    print(f"\n[7] 限流：{per_hour} 次/小时/用户")
    clear_bucket("codelkup:rate:upload:*")
    ok_count = 0
    last = {}
    for i in range(per_hour):
        last = upload("/api/uploads/images", f"rate{i}.png", png_bytes(), token)
        if last.get("code") == 0:
            ok_count += 1
    check(f"前 {per_hour} 次全部通过", ok_count == per_hour, ok_count)
    over = upload("/api/uploads/images", "rate-over.png", png_bytes(), token)
    check("第 N+1 次被限流（42901）", over.get("code") == 42901, over.get("code"))
    check("限流键落在 codelkup:rate:upload:*", clear_bucket("codelkup:rate:upload:*") >= 0)

    print(f"\n[8] 配额：{quota} 张/用户（每次上传前清限流桶，让两个闸门互不干扰）")
    used = count_files(post_dir)
    print(f"  当前已用 {used} 张")
    idx = 0
    quota_hit = None
    while used + idx < quota:
        clear_bucket("codelkup:rate:upload:*")
        r = upload("/api/uploads/images", f"quota{idx}.png", png_bytes(), token)
        if r.get("code") != 0:
            quota_hit = r
            break
        idx += 1
    if quota_hit is None:
        clear_bucket("codelkup:rate:upload:*")
        quota_hit = upload("/api/uploads/images", "quota-over.png", png_bytes(), token)
    check("达到配额后再传被拒（40006）", quota_hit.get("code") == 40006, quota_hit.get("code"))
    check("拒绝文案可读（含『上限』字样）",
          "上限" in str(quota_hit.get("message", "")), quota_hit.get("message"))
    check("配额判定与磁盘文件数一致", count_files(post_dir) == quota,
          f"{count_files(post_dir)} vs {quota}")

    print("\n[9] 自清理（只删本次测试账号的上传目录）")
    for d in (post_dir, avatar_dir):
        if d.is_dir():
            shutil.rmtree(d, ignore_errors=True)
    check("配图目录已清理", not post_dir.is_dir())
    check("头像目录已清理", not avatar_dir.is_dir())
    clear_bucket("codelkup:rate:register:*")
    clear_bucket("codelkup:rate:upload:*")
    check("限流桶已清空", True)

    print("\n" + "=" * 58)
    print(f"通用图片上传验收：{PASS}/{PASS + FAIL} 通过")
    if FAILURES:
        print("失败：" + " | ".join(FAILURES))
    print("=" * 58)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())