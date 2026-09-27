# ALIGNMENT — Codelkup V0.3（6A 阶段 1 产物）

> 日期：2026-09-25（2026-09-27 状态同步）· 状态：**已批准**（阶段 4：[APPROVAL.md](./APPROVAL.md)）· **已执行并按 S1~S11 完成终检**（阶段 5/6：[ASSESS.md](./ASSESS.md)）
> 配套产物：[ARCHITECTURE.md](./ARCHITECTURE.md)、[TASKS.md](./TASKS.md)、[APPROVAL.md](./APPROVAL.md)（批准）、[ASSESS.md](./ASSESS.md)（终检）
> 与既有文档的关系：本文只界定 V0.3 的范围与验收标准，不重述 Sprint 0~5 的历史结论（见 docs/PLAN-codelkup.md、docs/DEV-DOC-codelkup.md）。

---

## 1. 背景：三类"看起来做完了、其实没闭环"的问题（均有代码证据）

| 类型 | 事实 | 证据 |
|---|---|---|
| 死字段 | `t_post.score` 仅创建时写 0，之后再无写入；而 `hot` 排序按 `p.score DESC` → **热度排序实际等同于时间排序** | PostServiceImpl.java:62-66、:222；PostMapper.java:51 |
| 死字段 | `t_user.reputation` / `level` 仅注册时写 0/1，全仓无更新逻辑 | UserServiceImpl.java:62-63 |
| 断掉的闭环 | 收藏无法回看（`selectLikedPostIds` 无调用者，前端无 `/collections` 路由） | UserActionMapper.java:90-94；router/index.ts:14-87 |
| 断掉的闭环 | 编辑器无草稿/自动保存（50000 字正文刷新即丢） | PostEditorView.vue（textarea 直连 model） |
| 断掉的闭环 | 帖子图片只能外链，上传能力只服务头像 | FileStorageService.java:72-107 |
| 断掉的闭环 | 管理端零入口：`requiresAdmin` 守卫已就绪，但 admin 路由为空；feature 与计数重建接口无 UI | router/index.ts:111-114；ContentController.java:117-125；InteractionController.java:136-149 |
| 交付 | 无 CI；单测仅 2 个类（MarkdownUtilsTest、SearchServiceSanitizeTest）；通知只能轮询 | 仓库无 .github/；NotificationController.java:54-58 |
| 安全（上一轮审计遗留 low） | ① 注册无限流 → 绕过 check-email 的防枚举限流；② 登录锁定可按邮箱分片绕过（每邮箱试 4 次永不触发）；③ 前端 `SAFE_LINK_URI` 可被 `/\evil.com` 绕过 | AuthController.java:46-49/95；LoginAttemptService.java:32-53；frontend/src/utils/markdown.ts:60/68/86-98 |

**一句话结论**：功能数量够了，缺的是"闭环 + 数据结构兑现 + 一条可深挖的技术主线"。

## 2. 本轮唯一目标与用途

把 Codelkup 从「功能齐但半成品感」推到「每个入口都有闭环，并有一条可被追问的 AI 差异化线」，用途明确为：**简历/面试演示 + 面试官 3 分钟看懂 + 30 分钟追问不露怯**。

## 3. 成功标准（全部可验证，编号供 ASSESS 阶段逐项对照）

| # | 标准 | 验证方式 |
|---|---|---|
| S1 | 热度排序真实生效：高互动帖排在低互动旧帖之前 | 脚本构造互动数据后对比 `?sort=hot` 顺序；`EXPLAIN` 中无 filesort |
| S2 | 死字段清零：`score` / `reputation` / `level` 都有写入路径且前端可见 | 脚本断言：采纳后作者 `reputation` 变化、帖子 `score > 0`、主页展示等级 |
| S3 | 收藏闭环：可从「我的收藏」进入并回到原帖 | 前端验收脚本 3 步走（收藏→进列表→点回详情） |
| S4 | 草稿不丢：编辑中刷新浏览器，正文自动恢复 | 前端脚本：写入→reload→断言 textarea 内容一致 |
| S5 | 发帖支持粘贴截图：粘贴后 ≤3s 出图并插入 Markdown | 前端脚本（模拟 paste File）+ 后端断言文件落盘且 URL 可访问 |
| S6 | 提问闭环：可筛「待解决」，个人主页可见采纳率 | 脚本：`?type=1&solved=false` 只返回未采纳提问帖 |
| S7 | 通知实时：SSE 连接下未读数 ≤3s 更新；断线自动回退轮询 | 脚本：两个会话（A 评论 B 的帖子 → B 的 SSE 收到事件） |
| S8 | AI 回答：带 ≥1 条站内引用且可跳转；无依据时明确说「未找到依据」；P95 ≤ 8s；超配额有可读提示 | 脚本断言 citations 非空 + 引用 postId 可 GET 到；压 10 次测 P95 |
| S9 | 3 条 low 安全项关闭 | verify-v03-security.py：第 6 次注册被限流；同 IP 换邮箱 31 次被拒；`/\evil.com` 的 href 被移除 |
| S10 | CI 绿灯：后端 `mvn verify`（含新增单测）+ 前端 `npm run build` | GitHub Actions 状态 |
| S11 | 无回归：既有验收脚本全绿（后端 34/65/78/84、前端 38/38、13/13） | 原脚本重跑 |

## 4. 范围

**本轮要做（In）**
- P0 顺手做：3 条安全 low 修复、管理端极简页（点亮已有接口）、CI
- M2 体验闭环：score 热度分、声望/等级、改密、我的收藏、草稿自动保存、粘贴传图、代码块增强、提问闭环
- M3 AI 线：相似问题提示、长帖 TL;DR、站内 RAG 问答（带引用）、SSE 实时通知、基础指标

**本轮明确不做（Out，及理由）**
| 不做项 | 理由 |
|---|---|
| 邮箱验证 / 找回密码 | 需要邮件服务与域名信誉，演示站收益低；用"改密（验旧密码）"覆盖账号自维护 |
| 管理员用户管理 / 禁言强制落地 | 涉及新表与权限矩阵，本轮只做内容侧管理；禁言仍保持"无写入口"状态 |
| Elasticsearch / pgvector | 违反"不新增中间件"约束；向量能力先在 MySQL 内做（V0.4 再评估） |
| 多实例 SSE（Redis pub/sub） | 当前单实例部署；只留扩展点不实现 |
| GitHub OAuth | 依赖第三方应用审核与密钥管理，演示当天可用性不可控 |
| 内容审核队列 / 举报流 / AI 审核 | 无真实垃圾流量，收益最低 |
| 移动端 App、i18n、主题切换 | 与目标无关 |
| 服务端草稿表 | 本地草稿已满足 S4，落库会引入"多端冲突"这类真问题，得不偿失 |

## 5. 非功能需求（可量化）

| 维度 | 要求 |
|---|---|
| 性能 | score 全量重算单轮 < 1s（1 万帖量级）；列表接口 P95 不劣于现状基线（187→90ms 已实测）；AI 回答 P95 ≤ 8s |
| 安全 | 新增写接口必须挂限流；上传沿用"扩展名白名单 + 魔数"双校验；SSE 用一次性 ticket（token 不进 URL/日志）；actuator 不对外反代 |
| 成本 | LLM 单日预算可配（默认 ￥3/天），达上限时降级为纯检索；相同问题命中缓存不重复调用 |
| 可观测 | score 重算与计数重建记录"耗时 + 影响行数"；新增 http/ai 指标（QPS、P95、缓存命中、降级次数） |
| 兼容 | 既有接口**只新增不改签名**；前端旧路由不变；keep `verify-sprint*` 全绿 |

## 6. 约束与假设

**约束**：单实例部署（2C2G 香港轻量，备案限制见 DEPLOY.md）；MySQL 8 + Redis 7，不新增中间件；不引入需 native 编译的依赖；`JWT_SECRET`/LLM key 一律环境变量注入，代码内无默认值。

**假设（需在实现前/中验证）**
- A1：1 万帖、ngram 检索出的 top-k 足以支撑 RAG 质量（若不足，先补"标题+摘要加权"，不上向量库）
- A2：指数衰减参数（λ）在千帖量级下排序差异肉眼可见
- A3：SSE 经 nginx 需 `proxy_buffering off` + 延长 `proxy_read_timeout`，且前端 `EventSource` 无法带 Header
- A4：DeepSeek 提供 OpenAI 兼容端点，服务器可直连；未配置 key 时功能必须整体降级而非报错

## 7. 验收方式（阶段 6 用）

1. **脚本**：新增 `verify-v03-*.py`（score / collections / upload / sse / ai / security），沿用现有 verify 脚本风格（纯标准库 + 绕系统代理）
2. **单测**：ScoreService、改密、图片校验、Markdown 反斜杠（补进现有 2 个单测类之外）
3. **人眼**：3 分钟演示路径（首页→待解决筛选→详情 AI 摘要→问 Codelkup→粘贴传图→通知实时到达）