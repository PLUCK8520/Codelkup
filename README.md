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
- **社交**：关注 / 粉丝、关注流（拉模式）、站内通知（异步落库 + 未读数 + 同源去重）；未读数经 SSE 实时推送（一次性 ticket 鉴权、25s 心跳、断线指数退避重连，连不上自动转轮询兜底），通知中心**列表**随推送静默实时刷新（不打断阅读）
- **成长（V0.3）**：声望与等级（1~5，采纳 / 被赞 / 被收藏结算）+ 提问采纳率；我的收藏；草稿自动保存（本地 localStorage 写前日志 + 服务端草稿箱跨设备副本，恢复 / 丢弃 / 发布后清理）
- **创作（V0.3）**：Markdown 编辑器支持粘贴或选图上传（前端自适应压缩到长边 ≤1600 的 WebP，通常 <400KB）；代码块带语言标签 + 一键复制 + 行号
- **问答闭环（V0.3）**：首页「待解决」筛选、详情页「已解决 / 待解决」徽标、采纳后自动从待解决列表消失
- **站点入口（V0.4）**：顶栏一级入口 6 个（首页 / 问答 / 板块 / 标签 / 排行榜 / 关注），每个入口都有独立落点 —— 问答页（待解决 / 最新 / 已解决三态）、板块总览、标签总览与标签详情、声望榜、关注流；通知中心按类型分组（5 个 tab + 未读角标）；搜索支持高级筛选（板块 / 标签 / 作者，状态全在 URL）；个人主页的粉丝/关注数字可点进列表页；「创作」下拉把「发帖」与「提问」提到同级（`/publish?type=1` 预选类型）；头像菜单按内容 / 设置 / 退出分组，含「我的草稿」草稿箱（服务端草稿可续写 / 删除，点「继续编辑」直接带着内容回到编辑器）
- **站点统计（V0.4 补充）**：`GET /api/stats` 服务端聚合（板块 / 标签 / 提问三态；只读事务同一快照、刻意不缓存），问答页 / 标签页 / 首页 Hero / 板块页四处计数收敛到唯一口径，并修掉"共 N 个提问随 tab 漂移"的显示缺陷
- **AI（V0.3）**：`问 Codelkup` 顶栏抽屉 —— 检索站内 top-k 片段交 LLM 作答并标注引用（角标可点、跳对应帖子，答案**纯文本分段渲染**，全程无 `v-html`）；帖子详情页正文上方「AI 摘要」折叠区（长帖先剥离 Markdown 再截断 6000 字，3~5 条要点）；发帖编辑器里"边写边问"的**相似讨论提示**（打字去抖，点条目开新标签页、草稿不受影响）。三重成本闸门（用户 10 次/小时 → 结果缓存 → 全站日预算），未配置密钥 / 超预算 / 上游超时（8 秒）时**降级为"仅检索结果 + 一句人话"**，不报错也不假装有答案
- **AI 客户端可切换实现（M3-11）**：`AiClient` 接口下两个实现 —— 默认 `rest`（Spring 6.1 `RestClient` 手写，零框架依赖）与 `spring-ai`（Spring AI `OpenAiChatModel`），配置一键切换（`AI_IMPL`）；业务层一行未改，**两个实现跑同一套 87 项验收断言都全绿**（框架自带重试被显式关成 `maxAttempts=1`，与"不重试"契约一致）
- **举报与审核（V0.5）**：帖子详情页与评论区（含回复）都有「举报」入口（游客与自己的内容不显示），弹窗选原因（4 项）+ 可选说明；同一人对同一目标重复举报**幂等**（返回 40909，靠唯一索引而不是"先查再写"）；管理端 `/admin` 的「举报队列」按待处理 / 已驳回 / 已删除内容三态筛选，**逐条**驳回或删除内容并留痕（谁、何时、备注）；「删除内容」复用作者删除的同一套路径（板块计数、标签引用、Redis 计数一并同步），并自动关闭同一目标的其它待处理举报。**刻意不做**：不通知被举报人、不给举报人回执、不自动封禁、不批量处理（理由见 TASKS.md 备注 34）

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
# ⚠️ 首次部署：先把 .env 的 REGISTRATION_ENABLED 改为 true（seed 要建 6 个演示账号，
#    注册关闭会全部 40304）；灌完再改回 false + docker compose up -d backend（restart 不重新注入环境变量）
./deploy/deploy.sh --seed --verify       # 构建启动 → 灌演示数据 → 上线自检
```

- 完整手册（形态选型 / HTTPS / 备案提醒 / 运维 / 暴露面清单 / **AI、SSE 与指标运行面**）：[`docs/DEPLOY.md`](docs/DEPLOY.md)
- **演示话术与时间轴**：[`docs/DEMO-3min.md`](docs/DEMO-3min.md) —— 3 分钟讲完"数据是真的、降级不报错、实时靠 ticket"
- **上线三件套（PM 视角，2026-09-27）**：[`docs/PRE-MORTEM.md`](docs/PRE-MORTEM.md)（风险预演与拍板项）· [`docs/LAUNCH-CHECKLIST.md`](docs/LAUNCH-CHECKLIST.md)（可勾选执行清单）· [`docs/GTM.md`](docs/GTM.md)（上市方案：面试主轨 + 用户辅轨）
- `deploy/seed-demo.py` —— 演示数据（**内容即本项目的技术决策复盘**，幂等可重复跑）
- `deploy/verify-deploy.py` —— 上线自检 27 项（含注册开关两态分支：开态 27 / 关态 28；健康 / 静态资源 / 注册登录 / 发帖检索 / 上传卷 / 401 语义 / API 文档 / 限流 / **AI 降级不报错** / **SSE 经 nginx 收到首帧** / **指标不暴露到公网** / **举报路由与管理员边界（未登录 40101、普通用户 40301）** / **站点统计可达** / **站点 config 可读（证明 `.env` 的注册开关真的进了容器）**）
- `deploy/verify-demo-path.py` —— **金路径端到端走查 44 项**（登录→发帖（提问）→回答→点赞→收藏→关注→通知未读+**SSE 实时推送**→采纳闭环→检索→草稿→排行→AI→清理现场；带 `--site`，本地 compose 与公网域名同一套）
- `deploy/backup.py` —— 数据库备份（dump+gzip+**轮换 7 份**，密码不进命令行）与**恢复演练**（`--restore-to <临时库>` 核对行数，拒绝落到线上库名）

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
| 中文检索 | `ngram_token_size=2` 硬约束：单字无法成 token → LIKE 降级；**停用词会吞"含字母 a 的英文短词"（java/kafka/data → MATCH 恒 0 条）→ 关停用词 + V13 重建索引 + 0 条兜底**；BOOLEAN 操作符会语义漂移 → 输入清洗 |
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

> **跑全量回归的顺序**（19 个后端脚本分两类，先后不能反）：
> 先**停掉开发后端**跑 9 个"自起 jar 型"（`verify-sprint1/2/3/4.py`、`verify-v03-sse.py`、`verify-v03-ai.py`、`verify-v05-reports.py`、`verify-v04-ratelimit.py`、`verify-v04-regswitch.py`，它们会独占 8085），
> 再**拉起开发后端**跑 10 个"附着型"（`verify-v03-score / reputation / upload / password / collections / questions / experience / security / metrics.py`、`verify-v04-stats.py`）。
> 顺序反了会是 `URLError: 连接被拒绝`（详见 TASKS.md 备注 30）。
> ⚠️ 每个脚本之前先跑一次 `python clear-rate-buckets.py` —— 脚本各自注册用户，而注册限流是 **5 次/小时/IP**，不清就会出现"上一个脚本把下一个挡在 42901"的假失败（见备注 33）。
> 附着型里 `score` / `metrics` 建议用秒级定时窗口启动后端：`python serve-backend.py --codelkup.score.initial-delay-ms=2000 --codelkup.score.interval-ms=5000 --codelkup.sse.heartbeat-ms=2000`。

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `verify-sprint1.py` | 注册 / 登录 / JWT 双 token / 邮箱查重限流 / 声望榜 | 44/44 |
| `verify-sprint2.py` | 内容核心（发帖 / 列表 / 详情 / 服务端草稿箱） | 92/92 |
| `verify-sprint3.py` | 互动 / 计数 / 重建 / **自赞拦截（作者自赞 40001）** | 79/79 |
| `verify-sprint4.py` | 检索 / 关注 / 通知（含通知类型分组、自赞被拒补充断言、**评论点赞通知独立成条且带 targetPostId（第三轮审计）**） | 105/105 |
| `verify-sprint2-frontend.mjs` | Markdown 渲染 + XSS 消毒 | 38/38 |
| `verify-sprint4-frontend.mjs` | 高亮切分 + 架构约束 | 38/38 |
| `verify-refresh-frontend.mjs` | 凭证失效自动刷新 + 并发串行化 + `silent` 请求失败不弹提示（mock 后端） | 18/18 |
| `verify-v03-security.py` | 注册 / 登录双维度限流、XFF 采信语义（本机直连=可信代理会被采信；**生产的不可伪造由 nginx 覆盖保证**，compose 栈实测）、**公开主页不暴露 role（L-2 修复，防管理员枚举）**、**JWT 算法钉死 HS256 + refresh TTL 读配置（informational 加固）** | 18/18 |
| `verify-v03-collections.py` | 我的收藏（倒序 / 分页 / 未登录 40101 / 取消后消失） | 23/23 |
| `verify-v03-password.py` | 改密与凭证失效链（旧 access 40101、旧 refresh 40102、旧密码 40104）+ **失败锁定（M-1）**：错 5 次 → 42902、锁定期正确原密码也不放行 + **他设备旧 access 一并撤销（L-3，用户级水位）+ refresh 单次消费语义（F1：同一枚二次使用 40102、换发的仍可轮换）** | 25/25 |
| `verify-v03-reputation.py` | 声望结算对称性、**自赞被拒（40001，取代旧的"静默不给分"）**、采纳幂等、升级阈值 | 17/17 |
| `verify-v03-score.py` | 热度分（死字段写入、时间衰减、hot ≠ latest） | 9/9 |
| `verify-v03-upload.py` | 图片上传（魔数 / 尺寸双上限 / 配额 / 限流 / 替换旧头像清理） | 27/27 |
| `verify-v03-questions.py` | 提问闭环（`solved` 筛选、采纳后流转、采纳率与手算一致） | 21/21 |
| `verify-v03-experience.py` | 跨功能串联（上传→正文接缝、改密后新会话仍能读到旧数据） | 27/27 |
| `verify-v03-sse.py` | SSE 实时通道（ticket 一次性 / 心跳 / 3 秒内推送 / 多标签 / 连接上限淘汰）+ **ticket 绑定签发端 IP（informational 加固：异地消费 40101）** + **改密即断流（F2：会话撤销后服务端关闭该用户全部长连接）+ 改密前签发的票改密后消费被拒（第三轮审计：撤销水位）** | 35/35 |
| `verify-v03-sse-frontend.mjs` | 实时通道前端状态机（换新票重连 / 退避不放打 / 3 次失败转轮询 / stop 幂等） | 19/19 |
| `verify-v04-notify-live.mjs` | 通知列表实时刷新的触发契约（V0.4 补充）：`pushedAt` 只认推送、不认本地已读操作（否则"点一下、列表整体重拉"），负值钳制仍算一次推送 | 10/10 |
| `verify-v03-ai.py` | 相似问题提示 + RAG 问答 + 长帖 TL;DR（词袋检索 / 引用映射 / 双重缓存 / 日预算 / 限流 / **P95 采样与 8 秒超时边界**；**stub 冒充上游**）。`--ai-impl spring-ai` 可切到 Spring AI 实现（M3-11），**两种实现都 87/87** | 87/87 × 2 |
| `verify-v03-ai-frontend.mjs` | AI 面板契约（答案分段与越界编号 / 降级文案口径 / 不拼 HTML 的渲染边界 / 三个入口的挂载落点） | 38/38 |
| `verify-v03-markdown-uri.mjs` | Markdown URL 白名单（反斜杠 / 伪协议 / 协议相对 URL / 正常输入不误杀）+ **非外链不残留 target（informational 加固）** | 17/17 |
| `verify-v03-draft.mjs` | 草稿自动保存（版本自愈 / scope 隔离 / 节流与 flush / clear 后不复活）+ 本地与云端合并规则、推送节流与失败降级 + 恢复询问会话态（同一版草稿本会话只问一次：kept / restored，指纹含 updatedAt） | 60/60 |
| `verify-v03-codeblock.mjs` | 代码块增强（复制内容逐字一致且不含行号、退化路径、幂等） | 28/28 |
| `verify-v03-metrics.py` | 指标（M3-10）：端点与统一标签 / **来源闸门（公网 XFF → 40101、容器网络 XFF → 放行）** / 四组指标真的会动（score 计时与影响行数、AI 结果分布、SSE 连接数涨落、重建 driftCount） | 22/22 |
| `verify-v05-reports.py` | 举报与审核（V0.5，**自起 jar**，限流压到 3）：幂等 40909 且库内仍 1 行 / 第 4 次 42901 且不落库 / 举报自己 40001 / 目标不存在 40402・40403 / 队列三态与库对账 + 倒序 + 目标摘要与被举报次数 / 驳回后目标仍在 / 删除内容后 40402 且列表消失 / 重复处理 40910 / 兄弟举报自动关闭与留痕 | 60/60 |
| `verify-v04-stats.py` | 站点汇总统计（V0.4 补充，**附着型**）：与 DB 真值逐字段对账 / `questionCount ≡ solved + unsolved` / 与旧"列表探针"口径一致 / 发帖删帖后计数 +1 回落 / POST 未登录 40101 | 23/23 |
| `verify-v04-ratelimit.py` | **限流补桶（L-4，自起 jar，阈值压到 2~4）**：点赞 / 收藏 / 评论点赞 / 关注 / 刷新 / 资料 / 编辑帖子 七族"前 N 次通过、第 N+1 次 42901"；幂等重复同样消耗额度、取消类与正向共桶同拦 + **无 TTL 残留键自愈（F3：异常残留桶在下次请求时被补上 TTL，不再永久限流）** | 25/25 |
| `verify-v04-regswitch.py` | **注册开关（PRE-MORTEM T0-2，自起 jar，两段启动）**：开态 `config=true` + 注册可用；关态 `config=false`、新/旧邮箱一律 40304（拒绝在业务之前）、既有账号仍可登录 | 8/8 |
| 单元测试 | `MarkdownUtils` / 搜索输入清洗 / `LoginAttemptService`（登录双维度 + 改密 userId 维度）/ 计数 / 热度分 / 声望 / 改密 / AI 客户端（两个实现各一份测试，均以 stub 为上游）/ RAG 词袋与引用映射 / TL;DR 截断 | 100/100（`mvn verify`） |
| `deploy/verify-deploy.py` | **上线自检（对部署后的站点/ compose 栈）**：静态资源 / 反代 / 鉴权语义 / 上传卷 / 中文检索 / 401 / AI 降级不报错 / **SSE 经 nginx 收到首帧** / **指标不暴露到公网** / **举报路由与管理员边界** / **站点统计可达** / **站点 config 可读 + 注册开关两态分支（F4：证明 `.env` 的开关真的进了容器）** | 28/28（2026-09-27 晚关态实测；开态 27/27） |
| CI | `.github/workflows/ci.yml`：后端 `mvn verify` + 前端 `npm run build` + 前端契约测试（Markdown / 草稿 / 代码块 / 凭证刷新 / SSE 状态机 / 通知列表实时刷新 / AI 面板 等） | GitHub Actions |

## 目录结构

```
codelkup/
├── docker-compose.yml        # 一键编排（四容器）
├── deploy/                   # 上线交付包：deploy.sh / seed-demo.py / verify-deploy.py / .env 模板
├── codelkup-backend/         # Spring Boot（Dockerfile + README + 验收脚本）
├── codelkup-frontend/        # Vue 3（Dockerfile + nginx.conf + 验收脚本）
├── docs/                     # DEV-DOC（技术权威）/ PLAN（排期）/ DEPLOY（上线手册）/ ASSESS（终检报告）
│                             # + PRE-MORTEM · LAUNCH-CHECKLIST · GTM（上线三件套，PM 视角）
└── design-system/            # UI 设计规范
```
