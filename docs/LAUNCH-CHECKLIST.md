# 上线执行 Checklist — Codelkup 公网演示站（2026-09-27）

> 配套：[DEPLOY.md](./DEPLOY.md)（怎么做）· [ASSESS.md](./ASSESS.md)（已验证什么）· [PRE-MORTEM.md](./PRE-MORTEM.md)（风险与拍板项）
> 用法：部署当天从 P0 往下逐项打勾；**任何 P0 未勾 → 停**。
> 形态三选一见 DEPLOY §1（推荐 B：香港优化线路 + 域名 + Caddy HTTPS）。

## P0 · 部署物一致性（硬门槛）
- [ ] git 提交 + 推送 → CI 绿灯 —— 或明确选择 scp 口径并在服务器复跑验证
- [ ] 本地最后一次全绿留档：`mvn -B verify`（100）+ 后端脚本（747）+ 前端契约（266）
- [ ] `docker compose build backend frontend` 镜像含最新修复
- [ ] 抽查镜像新代码在位：JWT alg=HS256 / 公开主页无 role / `/api/stats` 可达 / **注册开关已进容器（`/api/system/config` 与 `.env` 口径一致 —— F4）**

## P0 · 拍板项（PRE-MORTEM T0-2 / T0-3）
- [ ] 注册策略：注册开关（**已实现**：`REGISTRATION_ENABLED`，`.env` 设 false 即关）/ 邀请码 / 开放+重置　← 三选一
      **推荐：注册开关 + 生产默认关**（模板已是 `false`）。面试当天想让人试用 → 临时改 `true` + `docker compose up -d`（30 秒生效），结束改回、再 `up -d`。
- [ ] AI key：充值（含日预算值）/ 维持降级当演示点　← 二选一
      **推荐：先维持降级**（`NO_KEY` 的降级路径本身就是设计好的演示点：0 成本、不会被刷、文案完整）。想升级成"真回答 + 引用可点"只需填 `AI_API_KEY` + `AI_DAILY_BUDGET`（建议 500）后重启 backend —— 随时可切，不是一次性决定。
- [ ] 演示话术过一遍：[DEMO-3min.md](./DEMO-3min.md)

## P0 · git 提交分组（分逻辑提交，便于 review / 回滚 / 二分定位）
- [ ] ① 文档与契约：README / docs（6A 产物、DEV-DOC、PLAN、DEPLOY、ASSESS、上线三件套）/ design-system
- [ ] ② 后端主功能：`codelkup/codelkup-backend/src`（Java + 资源 + Flyway 迁移 V1~V11）
- [ ] ③ 前端：`codelkup/codelkup-frontend/src` + vite/tsconfig 配置
- [ ] ④ 验收资产：19 个后端脚本 + 9 套前端契约 + `clear-rate-buckets.py` / `serve-backend.py`
- [ ] ⑤ 部署交付包：`deploy/`（deploy.sh / seed-demo / verify-deploy / verify-demo-path / backup / .env 模板）+ `docker-compose.yml` + 两个 Dockerfile + nginx.conf
- [ ] ⑥ CI：`.github/workflows/ci.yml`
- [ ] ⑦ 提交前自查：`git status` 里**不该出现** `.env` / `deploy/.env*` / `backups/` / `target/` / `node_modules/` / `uploads/`（均已 gitignore）

## P0 · 服务器与网络（DEPLOY §1 / §3 / §5）
- [ ] 购机：2C4G（2C2G 则 `JVM_MAX_RAM_PERCENTAGE=35~40`），香港 / 海外**优化线路**
- [ ] 防火墙：`ufw allow 22,80,8090` → `ufw enable`；SSH 用密钥、关密码登录
- [ ] Docker 安装；代码就位（clone / scp）
- [ ] `.env`：`MYSQL_ROOT_PASSWORD`、`DB_PASSWORD`（openssl 生成）、`JWT_SECRET`≥32B、
      `CORS_ALLOWED_ORIGINS`=真实 origin、`WEB_PORT`、`JVM_MAX_RAM_PERCENTAGE`、
      **`RATE_LIMIT_REGISTER_PER_HOUR` 保持模板的 10（首次 seed 要建 6 个账号，默认 5 会撞 42901）**、AI 相关按拍板填
- [ ] （方案 B）域名解析 → Caddyfile → reload → `https` 可达

## P0 · 部署与自检
- [ ] **首次部署顺序（真机复验实测，别漏）**：① `.env` 先 `REGISTRATION_ENABLED=true` → ② `./deploy/deploy.sh --seed --verify`（开态 28/28）→ ③ 改回 `false` + **`docker compose up -d backend`**（⚠️ **不能用 `restart`** —— 它只重启容器、不重新注入环境变量，注册开关不会生效；本地复验实测 `restart` 后 `registrationEnabled` 仍为 `true`）→ ④ 复跑 `verify-deploy.py`（关态 **29/29**）
- [ ] `docker compose ps`：mysql / redis / backend / frontend 全部 healthy
- [ ] 管理员临时提权（DEPLOY §5，改完**重新登录**才生效）→ 试管理功能 → 降权

## P0 · 上线后金路径走查（用手机 4G！）
- [ ] **先跑脚本化金路径**：`python3 deploy/verify-demo-path.py --site <你的域名>`（44 项：登录→发帖→回答→点赞→收藏→关注→通知+SSE→采纳→检索→草稿→AI→清理）
- [ ] 首页 / 列表 / 详情 / 中文搜索在 4G 下可开、首屏可接受（脚本证不了"网速观感"，这条只能人点）
- [ ] 注册 → 登录 → 发帖 → 评论 → 点赞（**自己帖子看不到点赞按钮**，L-1 口径）→ 收藏
- [ ] 通知实时：另一设备关注 / 评论你 → 角标 1.5s 内变
- [ ] AI 面板：降级文案不报错（或充值后正常回答 + 引用可点）
- [ ] Swagger `/swagger-ui/` 可开（有意公开；想收就删 nginx 两条 location）

## P0 · 备份与回滚
- [ ] crontab：`30 3 * * * cd /opt/codelkup && /usr/bin/python3 deploy/backup.py >> /var/log/codelkup-backup.log 2>&1`（自动轮换保留 7 份）
- [ ] **恢复演练**：`python3 deploy/backup.py --restore-to codelkup_restoretest`（恢复到临时库核对行数；拒绝落到线上库名）
- [ ] 完整回滚演练：`compose down` → 旧镜像回换 → `backup.py --restore-to` 验证 dump 可读 → 需要真回滚时才落到线上库 → `verify-deploy.py`
- [ ] 演示数据被污染时：`seed-demo.py` 重灌（幂等）

## P1 · 上线后 7 天
- [ ] 每天跑一次 `verify-deploy.py`（或进 crontab）
- [ ] 用量对账：AI（`t_ai_answer_log`）、举报队列、慢接口日志
- [ ] 把真实域名填进简历 / README 顶部 / GitHub About
- [ ] 按 [GTM.md](./GTM.md) 发第一篇复盘文