# Codelkup — 程序员技术社区

> 面向程序员的社区论坛：Markdown 发帖、中文全文检索、点赞收藏、关注流与站内通知。
> 内容型系统的完整实践 —— 认证、内容、互动、检索、社交、通知六域齐全。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5 · Java 21 · MyBatis-Plus · Flyway |
| 数据库 | MySQL 8.0（ngram 全文索引）· Redis 7（计数缓存） |
| 前端 | Vue 3 · TypeScript · Vite · Pinia · Element Plus |
| 安全 | Spring Security · JWT 双 token · DOMPurify XSS 消毒 |
| 部署 | Docker Compose（前端 / 后端 / MySQL / Redis 四容器） |

## 功能特性

- **认证**：JWT `accessToken`（30min，jti 黑名单）+ `refreshToken`（7d，Redis 白名单单槽位），401 并发刷新串行化
- **内容**：板块 / 标签 / 发帖（Markdown 存原文），列表分页 + 多条件筛选 + 排序字段白名单
- **检索**：MySQL ngram 全文索引 + BOOLEAN MODE，单字 LIKE 降级，输入清洗防语义漂移，结果 snippet + 前端纯文本高亮
- **互动**：两级评论（`parentId` 归一化到根）、采纳答案、点赞 / 收藏
- **计数**：Redis 实时增量 + MySQL 可重建基线，Lua 原子初始化，幂等重建脚本
- **社交**：关注 / 粉丝、关注流（拉模式）、站内通知（异步落库 + 未读数 + 同源去重）
- **成长（V0.3）**：声望与等级（1~5，采纳 / 被赞 / 被收藏结算）+ 提问采纳率；我的收藏；草稿自动保存（localStorage，恢复 / 丢弃 / 发布后清理）
- **创作（V0.3）**：Markdown 编辑器支持粘贴或选图上传（前端自适应压缩到长边 ≤1600 的 WebP，通常 <400KB）；代码块带语言标签 + 一键复制 + 行号
- **问答闭环（V0.3）**：首页「待解决」筛选、详情页「已解决 / 待解决」徽标、采纳后自动从待解决列表消失
- **站点入口（V0.4）**：顶栏一级入口 5 个（首页 / 问答 / 板块 / 标签 / 关注），每个入口都有独立落点 —— 问答页（待解决 / 最新 / 已解决三态）、板块总览、标签总览与标签详情、关注流；「创作」下拉把「发帖」与「提问」提到同级（`/publish?type=1` 预选类型）；头像菜单按内容 / 设置 / 退出分组

## 快速启动（Docker，推荐）

```bash
cp .env.example .env        # 按需修改端口与密码（.env 不入库）
docker compose up -d --build
# 打开 http://localhost:8080
```

- 唯一暴露端口默认 `8080`（可经 `WEB_PORT` 改，如被 VMware `vmnat.exe` 占用则改 `8090`）
- MySQL / Redis **不暴露宿主机端口**，避免与本地开发环境抢 3306 / 6379
- 前端 nginx 反代 `/api`、`/uploads` 与 API 文档，前后端同源、零 CORS

## 部署到公网（演示站）

```bash
cp deploy/.env.production.example .env   # 填强随机密钥（deploy.sh 会拒绝占位值）
./deploy/deploy.sh --seed --verify       # 构建启动 → 灌演示数据 → 上线自检
```

- 完整手册（形态选型 / HTTPS / 备案提醒 / 运维 / 暴露面清单）：[`docs/DEPLOY.md`](docs/DEPLOY.md)
- `deploy/seed-demo.py` —— 演示数据（**内容即本项目的技术决策复盘**，幂等可重复跑）
- `deploy/verify-deploy.py` —— 上线自检 16 项（健康 / 静态资源 / 注册登录 / 发帖检索 / 上传卷 / 401 语义 / API 文档 / 限流）

## 本地开发

依赖：本机 MySQL 8.0 + Redis（详见 `codelkup-backend/README.md` 的端口与凭据说明）。

```bash
# 后端（8085）
cd codelkup-backend
env -u SERVER__PORT mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8085

# 前端（5273，dev server 已配 /api 代理）
cd codelkup-frontend
npm i
npm run dev
```

## 架构亮点

| 问题 | 方案 |
|---|---|
| 点赞 / 收藏防重 | 数据库唯一索引 `uk_user_target_action` 兜底（而非"先查再写"），捕获 `DuplicateKeyException` 幂等返回 |
| "Redis 挂了怎么办" | 计数 = **MySQL 可重建基线 + Redis 实时增量**；Lua 脚本原子处理"key 不存在先 SET 基线再 INCR" |
| 计数漂移 | 幂等重建脚本（从明细表 `COUNT(*)` 反查，全用 SET/UPDATE 覆盖不用增量），`driftCount` 量化偏差 |
| 中文检索 | `ngram_token_size=2` 硬约束：单字无法成 token → LIKE 降级；BOOLEAN 操作符会语义漂移 → 输入清洗 |
| 评论树 | 两级设计：`parentId` 归一化到根 + `reply_user_id` 保留"回复谁"，一次查询 O(n) 分组，无递归无 N+1 |
| XSS | 后端只返回 Markdown 原文；前端 DOMPurify 消毒 + 纯文本切片高亮（不用 `v-html`） |

## 安全设计

| 风险面 | 措施 |
|---|---|
| 客户端 IP 可信边界 | nginx 用 `$remote_addr` **覆盖**（而非追加）`X-Forwarded-For`；后端 `forward-headers-strategy=native` + `remoteip.internal-proxies` 只采信可信代理写入的值；代码一律用 `getRemoteAddr()`，不再手写解析请求头 |
| 邮箱枚举 | 登录对"邮箱不存在"与"密码错误"返回同一错误；`/api/auth/check-email` 与 `/api/auth/register` 均按 IP 限流（否则后者会成为绕道枚举的出口） |
| 口令喷洒 | 登录失败**双维度**计数：`IP + 邮箱`（5 次 / 15 分钟锁定单账号）+ **纯 IP**（默认 30 次 / 10 分钟）拦住"每个邮箱只试 4 次"的分片打法；阈值可配（`codelkup.rate-limit.*`） |
| 上传 | 扩展名白名单 + 文件头魔数 + 服务端 UUID 命名 + `startsWith(root)` 双防线；`/uploads` 只服务图片 |
| SQL 注入 | 全部参数化；仅排序片段 `ORDER BY ${}` 走 Service 层白名单 Map；LIKE 通配符转义（`ESCAPE '!'`） |
| XSS | 前端唯一 `v-html` 走 marked → DOMPurify 白名单 + `afterSanitizeAttributes` 协议白名单；`\` 归一为 `/`，堵住协议相对 URL（`/\evil.com`）绕过 |
| 越权 | 白名单式 `authorizeHttpRequests` + `@PreAuthorize("hasRole('ADMIN')")`；归属校验（作者 / 管理员）落在 Service 层，前端隐藏入口只当体验优化 |
| 反序列化 | Redis 不启用 `activateDefaultTyping`（关闭多态反序列化的 RCE 面） |
| 凭证生命周期 | refreshToken 单槽位白名单（重新登录即失效）、accessToken jti 黑名单（登出即失效）；前端 401 刷新按业务码判断 |

## 压测数据（1 万行 t_post，关键词命中 105 行）

**数据库层 —— ngram 全文索引 vs LIKE 全表扫描**

| 查询方式 | 扫描行数 | 中位延迟 | P95 |
|---|---|---|---|
| `MATCH ... AGAINST` | **1** | **0.84 ms** | 3.53 ms |
| `LIKE '%kw%'` | 9672 | 2.53 ms | 8.20 ms |

扫描行数相差 **9672 倍**（结构性证据，差距随数据量线性放大）。

**接口层（单机自压，10 并发 × 400 请求）—— 列表页优化前后对比**

| 接口 | 优化前吞吐 | 优化前 P95 | 优化后吞吐 | 优化后 P95 |
|---|---|---|---|---|
| 帖子详情 | 549 req/s | 29.4 ms | 547 req/s | 32.6 ms |
| 全文检索 | 277 req/s | 45.0 ms | 186 req/s | 59.6 ms |
| 帖子列表 | **71 req/s** | **187.2 ms** | **134 req/s** | **90.0 ms** |

列表页瓶颈与修复：`EXPLAIN ANALYZE` 定位到排序触发 **filesort**（`idx_status_time` 不含 `is_top`，且 `is_top` 排序方向错误）。
加索引（`V6__fix_sort_index_desc.sql`：`(status, is_top DESC, create_time DESC)`）后 filesort 消失，
列表页**只读 20 行**（vs 10025 行），**吞吐 +89%（71→134）、P95 −52%（187→90ms）**。

> 详情 / 检索的吞吐波动是单机自压噪声（客户端与后端同机争 CPU）；列表页的优化是确定性的（filesort 消除、读行数 10025→20）。

> ⚠️ 单机自压：客户端与后端同机共享 CPU，绝对数值不代表生产性能，仅同环境横向比较有效。

## 测试与验收

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `verify-sprint1.py` | 注册 / 登录 / JWT 双 token / 邮箱查重限流 | 34/34 |
| `verify-sprint2.py` | 内容核心（发帖 / 列表 / 详情） | 65/65 |
| `verify-sprint3.py` | 互动 / 计数 / 重建 | 78/78 |
| `verify-sprint4.py` | 检索 / 关注 / 通知 | 84/84 |
| `verify-sprint2-frontend.mjs` | Markdown 渲染 + XSS 消毒 | 38/38 |
| `verify-sprint4-frontend.mjs` | 高亮切分 + 架构约束 | 38/38 |
| `verify-refresh-frontend.mjs` | 凭证失效自动刷新 + 并发串行化（mock 后端） | 13/13 |
| `verify-v03-security.py` | 注册 / 登录双维度限流、伪造 XFF 不产生计数键（跑完自清理限流额度） | 10/10 |
| `verify-v03-collections.py` | 我的收藏（倒序 / 分页 / 未登录 40101 / 取消后消失） | 23/23 |
| `verify-v03-password.py` | 改密与凭证失效链（旧 access 40101、旧 refresh 40102、旧密码 40104） | 12/12 |
| `verify-v03-reputation.py` | 声望结算对称性、自赞不给分、采纳幂等、升级阈值 | 16/16 |
| `verify-v03-score.py` | 热度分（死字段写入、时间衰减、hot ≠ latest） | 9/9 |
| `verify-v03-upload.py` | 图片上传（魔数 / 尺寸双上限 / 配额 / 限流 / 替换旧头像清理） | 27/27 |
| `verify-v03-questions.py` | 提问闭环（`solved` 筛选、采纳后流转、采纳率与手算一致） | 21/21 |
| `verify-v03-experience.py` | 跨功能串联（上传→正文接缝、改密后新会话仍能读到旧数据） | 27/27 |
| `verify-v03-markdown-uri.mjs` | Markdown URL 白名单（反斜杠 / 伪协议 / 协议相对 URL / 正常输入不误杀） | 13/13 |
| `verify-v03-draft.mjs` | 草稿自动保存（版本自愈 / scope 隔离 / 节流与 flush / clear 后不复活） | 30/30 |
| `verify-v03-codeblock.mjs` | 代码块增强（复制内容逐字一致且不含行号、退化路径、幂等） | 28/28 |
| 单元测试 | `MarkdownUtils` / 搜索输入清洗 / `LoginAttemptService` / 计数 / 热度分 / 声望 / 改密等 | 54/54（`mvn verify`） |
| CI | `.github/workflows/ci.yml`：后端 `mvn verify` + 前端 `npm run build` + Markdown 契约测试 | GitHub Actions |

## 目录结构

```
codelkup/
├── docker-compose.yml        # 一键编排（四容器）
├── deploy/                   # 上线交付包：deploy.sh / seed-demo.py / verify-deploy.py / .env 模板
├── codelkup-backend/         # Spring Boot（Dockerfile + README + 验收脚本）
├── codelkup-frontend/        # Vue 3（Dockerfile + nginx.conf + 验收脚本）
├── docs/                     # DEV-DOC（技术权威）/ PLAN（排期）/ DEPLOY（上线手册）
└── design-system/            # UI 设计规范
```
