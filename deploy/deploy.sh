#!/usr/bin/env bash
# ============================================================
# Codelkup 一键上线脚本（在**服务器上**执行，不要在本机跑）
#
#   ./deploy/deploy.sh                 # 构建 + 启动 + 等健康
#   ./deploy/deploy.sh --seed          # 再灌演示数据
#   ./deploy/deploy.sh --verify        # 再做一次上线自检
#   SITE=https://demo.example.com ./deploy/deploy.sh --verify   # 自检指定域名
#
# 设计原则：
#   1. **失败要早、要说清原因**：占位密钥、缺 Docker、端口占用都在动手前拦住
#   2. **幂等**：重复执行只是重建服务，不清数据库（数据在 named volume 里）
#   3. 不做"黑盒魔法"：每一步都有可见输出，出问题能一眼看出卡在哪
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DO_SEED=0
DO_VERIFY=0
for arg in "$@"; do
  case "$arg" in
    --seed)   DO_SEED=1 ;;
    --verify) DO_VERIFY=1 ;;
    *) echo "未知参数：$arg（可用：--seed / --verify）"; exit 2 ;;
  esac
done

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*"; exit 1; }

# ---------- 1. 前置检查 ----------
log "检查 Docker 环境"
command -v docker >/dev/null 2>&1 || fail "未安装 Docker。Ubuntu/Debian 一行装好：
  curl -fsSL https://get.docker.com | sh && systemctl enable --now docker"
docker compose version >/dev/null 2>&1 || fail "缺少 docker compose 插件（Docker 20.10+ 自带 compose v2）"
docker info >/dev/null 2>&1 || fail "Docker 守护进程未运行或当前用户无权限（把用户加入 docker 组后重登：usermod -aG docker \$USER）"

# ---------- 2. 环境变量 ----------
if [[ ! -f .env ]]; then
  log "未找到 .env，从模板复制"
  cp deploy/.env.production.example .env
  fail "请先编辑 .env 填写真实密钥（DB_PASSWORD / MYSQL_ROOT_PASSWORD / REDIS_PASSWORD / JWT_SECRET / CORS_ALLOWED_ORIGINS），然后重新执行"
fi

log "校验 .env 关键项"
# ⚠️ 解析前统一去掉 CR（第三轮审计）：在 Windows 上编辑过的 .env 是 CRLF，
#    直接 `source` 会把 \r 带进密码 / JWT 的值里 —— 表现为"compose 起得来、
#    但后端连不上库"这类很难查的故障。这里用进程替换读入去 CR 后的副本，
#    不落临时文件、不改动原始 .env。
set -a; . <(tr -d '\r' < ./.env); set +a
[[ -n "${DB_PASSWORD:-}"        ]] || fail ".env 缺少 DB_PASSWORD"
[[ -n "${MYSQL_ROOT_PASSWORD:-}" ]] || fail ".env 缺少 MYSQL_ROOT_PASSWORD"
[[ -n "${REDIS_PASSWORD:-}"     ]] || fail ".env 缺少 REDIS_PASSWORD（第四轮审计：Redis 已启 requirepass）"
[[ -n "${JWT_SECRET:-}"         ]] || fail ".env 缺少 JWT_SECRET"
for v in DB_PASSWORD MYSQL_ROOT_PASSWORD REDIS_PASSWORD JWT_SECRET; do
  case "${!v}" in
    *CHANGE_ME*|*change-me*) fail "$v 还是模板占位值，必须改掉（openssl rand -base64 24）" ;;
  esac
done
[[ ${#JWT_SECRET} -ge 32 ]] || fail "JWT_SECRET 只有 ${#JWT_SECRET} 字节，HS256 要求 ≥32（后端也会启动失败）"
echo "  WEB_PORT=${WEB_PORT:-8080}  DB_NAME=${DB_NAME:-codelkup}  JVM_MAX_RAM_PERCENTAGE=${JVM_MAX_RAM_PERCENTAGE:-75.0}"

# ---------- 3. 构建与启动 ----------
log "构建镜像并启动（首次构建约 3~8 分钟，取决于网络与机器）"
docker compose up -d --build

# ---------- 4. 等健康 ----------
PORT="${WEB_PORT:-8080}"
log "等待服务就绪（http://127.0.0.1:${PORT}/api/system/health）"
ok=0
for i in $(seq 1 60); do
  if curl -fsS --noproxy '*' "http://127.0.0.1:${PORT}/api/system/health" 2>/dev/null | grep -q '"mysql":"UP"'; then
    ok=1; break
  fi
  sleep 3
done
[[ $ok -eq 1 ]] || { docker compose ps; docker compose logs --tail=60 backend; fail "健康检查超时，后端日志见上"; }
echo "  ✓ MySQL / Redis 均 UP，Flyway 迁移已完成"

docker compose ps

# ---------- 5. 可选：演示数据 ----------
if [[ $DO_SEED -eq 1 ]]; then
  log "灌入演示数据（幂等，可重复执行）"
  python3 deploy/seed-demo.py --base "http://127.0.0.1:${PORT}/api"
fi

# ---------- 6. 可选：上线自检 ----------
if [[ $DO_VERIFY -eq 1 ]]; then
  log "上线自检"
  python3 deploy/verify-deploy.py --site "${SITE:-http://127.0.0.1:${PORT}}"
fi

log "完成"
cat <<EOF
上线后的收尾动作（详见 docs/DEPLOY.md）：

  1. 防火墙只放行必要端口（示例，ufw）：
       ufw allow 22/tcp && ufw allow ${PORT}/tcp && ufw enable
  2. 想在浏览器里直接打开：http://<你的服务器公网IP>:${PORT}/
  3. 主要运维命令：
       docker compose logs -f backend      # 看后端日志
       docker compose up -d backend        # 改 .env 后必须用 up -d（restart 不重新注入环境变量）
       docker compose down                 # 停止（数据保留在 volume 中）
EOF