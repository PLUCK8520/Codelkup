#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数据库备份 —— 一条命令把 compose 栈里的 MySQL 导出成带时间戳的 .sql.gz，并只保留最近 N 份。

为什么是脚本而不是一行 crontab（PRE-MORTEM T1-2）：
  "记得敲 mysqldump" 这种事，第一次忘就会在出事时才发现。
  脚本化后：**轮换有保证**（不会攒满磁盘）、**恢复命令打印在输出里**（出事时不用回忆）、
  **密码不进命令行**（`-e MYSQL_PWD` 的 name-only 形式 + 进程环境传递 ——
  写成 `-e MYSQL_PWD=值` 会暴露在宿主 `ps` 里，2026-09-28 审计修正）。

用法：
    python3 deploy/backup.py                    # 写到 ./backups/，保留最近 7 份
    python3 deploy/backup.py --dir /root/bak --keep 14
    python3 deploy/backup.py --list             # 只列出现有备份（不导出）

加进 crontab（服务器上，每天 03:30）：
    30 3 * * * cd /opt/codelkup && /usr/bin/python3 deploy/backup.py >> /var/log/codelkup-backup.log 2>&1

恢复（脚本不自动恢复 —— 恢复是破坏性动作，必须人手确认）：
    gunzip -c backups/codelkup-<时间戳>.sql.gz | \\
      MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker compose exec -T -e MYSQL_PWD mysql \\
      mysql -uroot "$DB_NAME"

退出码：0 成功；1 失败（缺 .env / 容器没起 / 导出为空等）
"""
from __future__ import annotations

import argparse
import gzip
import os
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = REPO_ROOT / ".env"
NAME_PATTERN = re.compile(r"^codelkup-(\d{8}-\d{6})\.sql\.gz$")


def read_env() -> dict:
    """从 .env 读两个键（不 source、不 eval —— 只做 KEY=VALUE 解析；顺带去掉 CRLF 的 CR）"""
    if not ENV_FILE.exists():
        print("✗ 找不到 %s（备份需要 MYSQL_ROOT_PASSWORD 与 DB_NAME）" % ENV_FILE)
        sys.exit(1)
    env = {}
    for line in ENV_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")
    if not env.get("MYSQL_ROOT_PASSWORD"):
        print("✗ .env 里没有 MYSQL_ROOT_PASSWORD")
        sys.exit(1)
    return env


def list_backups(out_dir: Path) -> list:
    if not out_dir.exists():
        return []
    files = [p for p in out_dir.iterdir() if p.is_file() and NAME_PATTERN.match(p.name)]
    return sorted(files, key=lambda p: p.name)


def restore_to_target(args, out_dir: Path, env: dict) -> int:
    """把一份备份恢复到**临时库**并核对行数 —— "备份可恢复性"的实演练。

    安全约束：**拒绝恢复到线上库名**。恢复是破坏性动作，演练必须落在一次性临时库上；
    核对完默认把临时库删掉，不留下任何东西。
    """
    live_db = env.get("DB_NAME") or "codelkup"
    name = args.restore_to
    if name == live_db:
        print("✗ 拒绝恢复到线上库 %s —— 这是演练命令，请给一个临时库名（如 codelkup_restoretest）" % live_db)
        return 1
    files = list_backups(out_dir)
    src = Path(args.file).expanduser() if args.file else (files[-1] if files else None)
    if not src or not Path(src).exists():
        print("✗ 没有可用的备份文件（先不带参数跑一次导出）")
        return 1

    def mysql(extra: list, stdin_bytes=None):
        # ⚠️ `-e MYSQL_PWD` 是 **name-only** 形式：值从宿主进程环境读入，不进 argv。
        #    写成 "-e MYSQL_PWD=<值>" 会把 root 口令暴露在宿主 `ps`（2026-09-28 审计项）。
        cmd = ["docker", "compose", "exec", "-T", "-e", "MYSQL_PWD",
               "mysql", "mysql", "-uroot"] + extra
        run_env = {**os.environ, "MYSQL_PWD": env["MYSQL_ROOT_PASSWORD"]}
        p = subprocess.run(cmd, cwd=str(REPO_ROOT), input=stdin_bytes, env=run_env,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")

    print("演练：把 %s 恢复到临时库 %s" % (Path(src).name, name))
    code, _, err = mysql(["-e", "CREATE DATABASE IF NOT EXISTS `%s`" % name])
    if code != 0:
        print("✗ 建临时库失败：%s" % err[:200])
        return 1
    try:
        with gzip.open(src, "rb") as f:
            dump = f.read()
        code, _, err = mysql([name], stdin_bytes=dump)
        if code != 0:
            print("✗ 恢复失败：%s" % err[:300])
            return 1
        probe = ("SELECT (SELECT COUNT(*) FROM t_post), (SELECT COUNT(*) FROM t_user), "
                 "(SELECT COUNT(*) FROM t_notification) FROM DUAL")
        _, restored, err = mysql(["-N", "-B", "-e", probe, name])
        if not restored.strip():
            print("✗ 恢复后查不到行数（dump 可能不完整）：%s" % err[:200])
            return 1
        _, live, _ = mysql(["-N", "-B", "-e", probe, live_db])
        print("  临时库行数（帖子 / 用户 / 通知）：%s" % restored.strip())
        print("  线上库行数（同一时刻）：%s" % live.strip())
        print("  （备份是**某个时间点**的快照，条数略少于现在属正常；重点是这个 dump 能被完整读入）")
    finally:
        if not args.keep_db:
            mysql(["-e", "DROP DATABASE IF EXISTS `%s`" % name])
            print("  已清理临时库")
    print("✓ 恢复演练通过")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Codelkup 数据库备份（mysqldump + gzip + 轮换）")
    ap.add_argument("--dir", default=str(REPO_ROOT / "backups"), help="备份目录（默认 ./backups）")
    ap.add_argument("--keep", type=int, default=7, help="保留最近几份（默认 7）")
    ap.add_argument("--list", action="store_true", help="只列出现有备份")
    ap.add_argument("--restore-to", metavar="DB", help="把某份备份恢复到**临时库**并核对行数（演练/巡检用）")
    ap.add_argument("--file", help="配合 --restore-to 指定备份文件（默认取最新一份）")
    ap.add_argument("--keep-db", action="store_true", help="演练后保留临时库（默认核对完就删）")
    args = ap.parse_args()
    out_dir = Path(args.dir).expanduser()

    if args.list:
        files = list_backups(out_dir)
        if not files:
            print("（%s 下还没有备份）" % out_dir)
            return 0
        for p in files:
            print("  %s  %8.1f KB" % (p.name, p.stat().st_size / 1024))
        print("共 %d 份" % len(files))
        return 0

    if args.restore_to:
        return restore_to_target(args, out_dir, read_env())

    env = read_env()
    db = env.get("DB_NAME") or "codelkup"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    target = out_dir / ("codelkup-%s.sql.gz" % stamp)

    print("导出 %s（经 docker compose exec，密码走进程环境、不进命令行）..." % db)
    t0 = time.time()
    cmd = [
        "docker", "compose", "exec", "-T",
        # ⚠️ name-only：argv 里只有变量名，值由下面的 env 传入（2026-09-28 审计项）
        "-e", "MYSQL_PWD",
        "mysql",
        "mysqldump", "-uroot",
        "--single-transaction",   # InnoDB 一致性快照，不锁表（备份时站点照常可用）
        "--routines", "--triggers",
        db,
    ]
    try:
        proc = subprocess.run(cmd, cwd=str(REPO_ROOT), env={**os.environ, "MYSQL_PWD": env["MYSQL_ROOT_PASSWORD"]},
                              stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=600)
    except FileNotFoundError:
        print("✗ 找不到 docker 命令（备份要在部署机 / 本机 compose 环境执行）")
        return 1
    except subprocess.TimeoutExpired:
        print("✗ 导出超时（600s）—— 数据量或机器状态异常，请人工确认")
        return 1

    if proc.returncode != 0:
        print("✗ mysqldump 失败（退出码 %s）：%s" % (proc.returncode,
                                                proc.stderr.decode("utf-8", "replace")[:300]))
        print("  常见原因：容器没起（docker compose ps）、.env 密码与容器实际不符")
        return 1
    if not proc.stdout or len(proc.stdout) < 1024:
        # 空/极小的输出几乎一定是失败而不是"空库"（mysqldump 有表结构注释，正常都 >1KB）
        print("✗ 导出结果异常小（%d 字节），按失败处理、不落盘" % len(proc.stdout or b""))
        return 1

    # 权限收紧（安全审计 run-1 加固）：dump 含全库口令哈希 / 邮箱 / 通知等，
    # 显式 0600 落盘，不依赖宿主 umask（常见 0644 会让同机其他用户可读）。
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(gzip.compress(proc.stdout, 6))
    size_kb = target.stat().st_size / 1024
    print("✓ %s（%.1f KB，原始 %.1f KB，耗时 %.1fs）"
          % (target, size_kb, len(proc.stdout) / 1024, time.time() - t0))

    # 轮换：只保留最近 --keep 份（按文件名里的时间戳排序）
    files = list_backups(out_dir)
    while len(files) > args.keep:
        old = files.pop(0)
        old.unlink()
        print("  轮换：删除旧备份 %s" % old.name)
    print("当前共 %d 份（保留上限 %d）" % (len(files), args.keep))

    print("\n恢复命令（破坏性动作，确认后再执行）：")
    print('  gunzip -c "%s" | docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" \\' % target)
    print('    mysql mysql -uroot "%s"' % db)
    return 0


if __name__ == "__main__":
    sys.exit(main())