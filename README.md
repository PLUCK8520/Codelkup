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

## 快速启动（Docker，推荐）

```bash
cp .env.example .env        # 按需修改端口与密码（.env 不入库）
docker compose up -d --build
# 打开 http://localhost:8080
```

- 唯一暴露端口默认 `8080`（可经 `WEB_PORT` 改，如被 VMware `vmnat.exe` 占用则改 `8090`）
- MySQL / Redis **不暴露宿主机端口**，避免与本地开发环境抢 3306 / 6379
- 前端 nginx 反代 `/api` 与 `/uploads`，前后端同源、零 CORS

## 本地开发

依赖：本机 MySQL 8.0 + Redis（详见 `codelkup-backend/README.md` 的端口与凭据说明）。

```bash
# 后端（8085）
cd codelkup-backend
env -u SERVER__PORT mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8085

# 前端（5173，dev server 已配 /api 代理）
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
| `verify-sprint1.py` | 注册 / 登录 / JWT 双 token | 32/32 |
| `verify-sprint2.py` | 内容核心（发帖 / 列表 / 详情） | 65/65 |
| `verify-sprint3.py` | 互动 / 计数 / 重建 | 78/78 |
| `verify-sprint4.py` | 检索 / 关注 / 通知 | 84/84 |
| `verify-sprint2-frontend.mjs` | Markdown 渲染 + XSS 消毒 | 38/38 |
| `verify-sprint4-frontend.mjs` | 高亮切分 + 架构约束 | 38/38 |
| 单元测试 | `MarkdownUtils` / 搜索输入清洗 | `mvn test` |

## 目录结构

```
codelkup/
├── docker-compose.yml        # 一键编排
├── codelkup-backend/         # Spring Boot（Dockerfile + README + 验收脚本）
├── codelkup-frontend/        # Vue 3（Dockerfile + nginx.conf + 验收脚本）
├── docs/                     # PRD / 技术设计 / 优先级
└── design-system/            # UI 设计规范
```
