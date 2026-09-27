#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""批次之间清一次"按 IP 计"的限流桶（全量回归的前置动作）。

为什么需要它：验收脚本各自注册用户，而**注册限流是按 IP 5 次/小时**、查重限流是
30 次/10 分钟 —— 不清就会出现"上一个脚本把下一个脚本挡在 42901"的**假失败**
（看起来像功能坏了，实际是我们自己把额度用完了，见 TASKS 备注 10 / 33）。

⚠️ 只清两类前缀：`codelkup:rate:register:*` 与 `codelkup:rate:check_email:*`。
**绝不碰 `codelkup:auth:*`** —— 那里是 refresh 白名单与登录失败锁（误删会把所有
已登录会话的刷新能力清掉，本机踩过一次，见 TASKS 备注 7）。

用法（跑在每个 verify-*.py 之前）：
    python clear-rate-buckets.py
"""
import socket

REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379


def redis_cmd(*args: str) -> str:
    """发一条命令并读回整段响应（KEYS 的回复按 RESP 长度读完，别"猜读完没有"）。"""
    payload = f"*{len(args)}\r\n" + "".join(
        f"${len(a.encode())}\r\n{a}\r\n" for a in args)
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as s:
        s.sendall(payload.encode())
        chunks = []
        head = s.recv(64).decode(errors="replace")
        chunks.append(head)
        total = 0
        if head.startswith("*"):
            total = int(head[1:].split("\r\n")[0])
        while True:
            # 数组回复按元素个数收；其余按"读到不满 64KB"收
            joined = "".join(chunks)
            if total and joined.count("\r\n") >= (total * 2 + 1):
                break
            if not total and len(chunks[-1]) < 65536:
                break
            buf = s.recv(65536)
            if not buf:
                break
            chunks.append(buf.decode(errors="replace"))
    return "".join(chunks)


def clear() -> int:
    victims = []
    for pattern in ("codelkup:rate:register:*", "codelkup:rate:check_email:*"):
        resp = redis_cmd("KEYS", pattern)
        victims += [line for line in resp.split("\r\n") if line.startswith("codelkup:")]
    if victims:
        redis_cmd("DEL", *victims)
    return len(victims)


if __name__ == "__main__":
    try:
        print(f"已清理按 IP 计的限流桶：{clear()} 个（register / check_email）")
    except OSError as e:
        print(f"连不上 Redis，跳过（{e}）")