# Codelkup 开发文档（完整版）

> 版本：v1.0
> 日期：2026-09-24
> 作者：刘（27 届计算机科学与技术）
> 状态：**技术栈已按本机环境核实并修订，待你确认后开工**
> 本文档地位：**合并并取代** 原 `PRD-codelkup.md` + `TECH-DESIGN-codelkup.md` + `PRIORITY-codelkup.md` 三份草案。三者相互冲突处以本文档为准。

---

## 目录

1. [Summary 概述](#1-summary-概述)
2. [Contacts 干系人](#2-contacts-干系人)
3. [Background 背景](#3-background-背景)
4. [Objective 目标与关键结果](#4-objective-目标与关键结果)
5. [Market Segment 目标客群](#5-market-segment-目标客群)
6. [Value Proposition 价值主张](#6-value-proposition-价值主张)
7. [Solution 解决方案](#7-solution-解决方案)
8. [技术方案](#8-技术方案)
9. [数据库设计](#9-数据库设计)
10. [关键方案设计（面试重点）](#10-关键方案设计面试重点)
11. [API 接口设计](#11-api-接口设计)
12. [项目结构](#12-项目结构)
13. [Sprint 排期](#13-sprint-排期)
14. [Release 发布规划](#14-release-发布规划)
15. [风险与应对](#15-风险与应对)
16. [待确认清单](#16-待确认清单)
17. [附录：证据等级与修订记录](#17-附录证据等级与修订记录)

---

# 1. Summary 概述

Codelkup 是一个面向**在校计算机学生与初级开发者**的技术社区论坛。它与掘金 / V2EX / Stack Overflow 的差别只有一句：**不问"你是谁"，只问"你在学什么、卡在哪"**。用户发帖记录学习过程、提问具体报错、分享面试经历，通过回答问题积累声望。

本项目有两个目标，且**优先级明确**：

1. **首要目标（真实目标）**：作为个人全栈作品，服务于 27 届秋招求职叙事——证明作者能独立完成「内容型系统」，与已完成的「行云票务」（交易型）形成能力面差异化。
2. **次要目标**：做一个能真实运转、有人发帖、能搜到内容的社区。

> ⚠️ **这条优先级必须写死**。它决定了所有取舍：当"产品完整性"与"技术点可讲性"冲突时，**选后者**。

---

# 2. Contacts 干系人

| 姓名 | 角色 | 备注 |
|---|---|---|
| 刘（本人） | 产品 + 全栈开发 + 测试 + 运维 | 唯一负责人 |
| 潜在用户 | 在校 CS 学生 / 初级开发者 | 需求来源，也是本人自身场景 |
| 面试官 | **实际客户** | 项目最终要服务于求职叙事 |

---

# 3. Background 背景

## 3.1 为什么做这个

**个人背景**：作者是 27 届计算机本科，秋招冲刺期。已有项目「行云票务」是交易型系统（售票、订单、支付闭环）。

**再做一个票务或电商项目毫无意义**——简历上两个同类系统是减分项。需要一个**业务模型完全不同**的第二项目。

| 维度 | 交易型（行云票务） | 内容型（本项目） |
|---|---|---|
| 核心难点 | 库存一致性、订单状态机、支付对账 | 内容检索、Feed 流、互动计数、反垃圾 |
| 数据特征 | 强一致、事务密集 | 读多写多、热点分散、可最终一致 |
| 缓存策略 | 防超卖、库存预热 | 热点帖子、排行榜、Feed 折叠 |
| 典型技术 | 分布式锁、MQ 事务消息 | 全文检索、多级缓存、异步削峰 |

## 3.2 为什么是现在（时间窗口的真实约束）

1. **秋招进行中**：需要在简历投递前产出可讲的项目。中型单体规模可控。
2. **题材自洽**：作者本人就是目标用户，需求判断不需要臆测。
3. **技术栈可控**：不涉及 AI 大模型，避开"套壳质疑"，纯工程能力展示。

> ⚠️ **修正原文档的时间判断**：原文档只说"秋招进行中"，但没有算过一笔账。
> 今天是 2026-09-24。按真实产能估算（见 §13），完整 MVP 需约 **6.5 周**，做完已是 **11 月中旬 ≈ 秋招收尾**。
> **这直接导致 §13.5 的"分两段交付、4 周后即投递"策略**——这是本文档对原方案最重要的修正。

## 3.3 已排除的方案

- ❌ 票务 / 电商（与行云票务撞车）
- ❌ AI Agent 平台 / 低代码生成器（技术炫技但业务空，容易被问"这不就是套壳"）
- ❌ 纯博客系统（业务太浅，就是文章 CRUD）
- ❌ 微服务全家桶（超出"中型单体"范围）

---

# 4. Objective 目标与关键结果

## 4.1 产品目标

做一个**能真实运转、有人发帖、能搜到内容**的技术社区。不是演示 Demo。

## 4.2 对作者的价值

1. **简历叙事**：展示"内容型系统"工程能力，与行云票务形成差异化。
2. **技术深度**：把 Redis（缓存 + 计数 + 排行榜）、全文检索、异步削峰这些面试高频考点在真实场景落地。
3. **完整交付**：需求 → 设计 → 开发 → 部署 → 文档，走完完整流程。

## 4.3 Key Results（SMART 格式）

| KR | 指标 | 目标值 | 衡量方式 |
|---|---|---|---|
| KR1 | 核心功能闭环 | 发帖→评论→点赞→搜索→关注 全链路可用 | 端到端手工验收 |
| KR2 | 接口性能 | 帖子列表 P95 < 200ms（100 并发） | wrk / JMeter 压测报告 |
| KR3 | 检索能力 | 中文关键词能搜到正文内容，响应 < 500ms | 功能测试 + 压测 |
| KR4 | 代码质量 | 核心模块单元测试覆盖率 > 60% | JaCoCo 报告 |
| KR5 | 可部署性 | 全新环境 `docker compose up` 一键启动 | 干净机器验证 |
| KR6 | 文档完整 | README + 架构文档 + API 文档齐全 | 文档自查清单 |

> ⚠️ **以上 KR 是"工程可交付"标准，不是"用户量"标准**。
> 本项目不做真实运营指标承诺——没有推广预算，也无需假装有用户。
> **面试时被问"你的 DAU 多少"，标准回答是**："这是个人作品，目标不是运营数据，而是把内容型系统的技术难点跑通并量化。"

---

# 5. Market Segment 目标客群

> 原则：市场由「人的问题」定义，而不是由人口属性定义。

## 5.1 核心客群：在校计算机学生（尤其求职期）

**他们的 Job（要完成的事）**：
1. 遇到具体技术问题需要快速得到解答（而不是被搜索引擎带到两年前的博客）
2. 想知道同龄人 / 学长学姐的真实求职经历（面经、踩坑、公司评价）
3. 想记录自己的学习过程，获得反馈与正反馈
4. 想找同方向的学习伙伴

**他们的约束**：
- 预算为零（不可能付费）
- 时间碎片化（上课 / 实习间隙刷）
- 对门槛敏感（发帖要简单，不能像 Stack Overflow 那样"提问被打回"）

## 5.2 次要客群：初级开发者（1-3 年经验）

- Job：寻找有深度的技术讨论、记录踩坑经验、建立个人技术影响力
- 与核心客群的区别：更愿意输出长文，是内容供给方

## 5.3 明确不服务的客群

- ❌ 资深架构师（他们要的是深度技术社区，不是学习社区）
- ❌ 企业技术团队（内网知识库场景，不适用）

---

# 6. Value Proposition 价值主张

## 6.1 用户要完成的任务（JTBD）

> **When** 我在学习或求职中遇到具体问题，**I want to** 快速找到/问到答案并记录下来，**so I can** 推进进度，同时让这段经历沉淀为我的技术履历。

## 6.2 用户获得的收益

1. **快** —— 提问门槛低，不需要"完美的问题格式"就能发出去
2. **真** —— 内容来自同龄人，面试经验、踩坑记录是真实的一手信息
3. **沉淀** —— 发帖即建立个人主页，声望与内容可累积
4. **被看见** —— 优质回答能被推荐，获得曝光与认同

## 6.3 用户避免的痛点

| 痛点 | 现有平台的现状 | 我们的做法 |
|---|---|---|
| 提问被打回 | Stack Overflow 严格格式 + 英文门槛 | **宽松提问**，只需标题 + 描述 |
| 答案过时 | 搜索结果混杂，博客内容陈旧 | **按时间/热度排序**，帖子有"最后活跃时间" |
| 面经分散 | 散落在牛客、脉脉、B站评论区 | **面经专区**，结构化（公司 + 岗位 + 时间 + 结果）|
| 新人无曝光 | 大 V 垄断流量 | **新人帖推荐位** |
| 内容找不到 | 站内只支持标题模糊搜索 | **全文检索**（正文 + 标签 + 作者）|

## 6.4 相比竞品的差异（Value Curve）

| 维度 | Stack Overflow | 掘金 | V2EX | **Codelkup** |
|---|---|---|---|---|
| 提问门槛 | 高 | 中 | 低 | **低** |
| 内容深度 | 高 | 高 | 中 | **中（面向学习场景）** |
| 语言 | 英文 | 中文 | 中文 | **中文** |
| 学习过程记录 | 无 | 弱 | 弱 | **强（学习打卡）** |
| 求职信息 | 无 | 分散 | 无 | **强（面经专区）** |
| 新人友好度 | 低 | 低 | 中 | **高** |

**一句话定位**：*"You don't need to be an expert to belong here."*

---

# 7. Solution 解决方案

## 7.1 用户角色

| 角色 | 说明 | 核心权限 |
|---|---|---|
| 游客 | 未登录 | 浏览帖子、搜索、查看用户主页 |
| 注册用户 | 已登录 | 发帖、评论、点赞、收藏、关注 |
| 版主 | 从高声望用户中选拔 | 加精、置顶、删帖、禁言 |
| 管理员 | 系统运营 | 全部权限 + 用户管理 + 板块管理 |

## 7.2 核心用户流程

**主流程 A0：注册（前置）**
```
访问首页 → 点击注册 → 填写 邮箱 / 密码 / 昵称
  → 前端校验（邮箱格式、密码强度、两次密码一致）
  → 后端校验邮箱唯一性 → BCrypt 加密入库
  → 自动登录（返回 token）→ 引导填写技术标签
```
> **取舍说明**：不做邮箱验证码，注册即生效。用"降低注册摩擦"换"不做邮件服务"。

**主流程 A：提问并得到解答**
```
登录 → 选择板块 → 填写标题/正文/标签/代码块
  → 提交 → 反垃圾校验
  → 发布成功 → 进入 Feed + 触发订阅者通知
  → 他人浏览/回答 → 楼主收到通知
  → 采纳最佳答案 → 回答者声望 +N
```

**主流程 B：发现内容**
```
进入首页 → Feed 流（关注 / 最新 / 热门）
  → 或搜索（全文检索）→ 结果高亮
  → 帖子详情 → 阅读 → 点赞/收藏/评论 → 关注作者
```

**主流程 C：积累声望**
```
发帖 / 回答问题 / 被采纳 / 被点赞
  → 声望增加 → 等级提升 → 解锁权限
  → 个人主页展示声望、徽章、发帖史
```

## 7.3 功能模块清单

> **P0 = MVP 必须** / P1 = 第二版 / P2 = 未来

### 模块 1：用户与认证

| 功能 | 优先级 | 说明 |
|---|---|---|
| 注册 / 登录 | **P0** | **邮箱 + 密码**，JWT 双 token |
| 个人主页 | **P0** | 头像、昵称、简介、声望、发帖/回答列表 |
| 资料编辑 | **P0** | 修改头像、昵称、简介、技术标签 |
| 关注 / 粉丝 | **P0** | 关注作者，粉丝列表 |
| 第三方登录 | P1 | GitHub / Gitee OAuth2 |
| 邮箱验证 / 找回密码 | **P2（暂不做）** | 需 SMTP；MVP 走"联系管理员重置" |

> **登录方案决策（2026-09-24 已确认）**：采用**邮箱 + 密码**，邮箱直接作为登录凭证，**不设独立 username 字段**。
> - 少一个字段少一套唯一性约束；邮箱天然唯一、可联系、可找回
> - 显示名统一用 `nickname`，**允许重复**
> - MVP 不做邮箱验证码和找回密码（避免引入 SMTP）

### 模块 2：内容（帖子与评论）

| 功能 | 优先级 | 说明 |
|---|---|---|
| 发帖 / 编辑 / 删除 | **P0** | Markdown、代码块高亮、图片上传 |
| 帖子详情 | **P0** | 正文渲染、作者卡片、阅读数 |
| 评论 / 回复 | **P0** | 支持二级评论 |
| 点赞 / 收藏 | **P0** | 帖子与评论均可 |
| 板块分类 | **P0** | 后端 / 前端 / 算法 / 面经 / 学习打卡 |
| 标签系统 | **P0** | 一帖多标签，可按标签筛帖 |
| 采纳最佳答案 | P1 | 提问者标记某回答为采纳 |
| 草稿箱 | P1 | 自动保存未发布内容 |
| 富文本编辑器 | P2 | Markdown 已足够，前端富文本是巨坑 |

### 模块 3：搜索与发现

| 功能 | 优先级 | 说明 |
|---|---|---|
| 全文检索 | **P0** | 标题 + 正文 + 标签 + 作者，关键词高亮 |
| Feed 流 | **P0** | 关注流 + 最新 |
| 热门榜 | P1 | 按热度排序 |
| 推荐流 | P1 | 基于标签匹配 |
| 搜索历史 / 热搜词 | P2 | |

### 模块 4：通知与互动

| 功能 | 优先级 | 说明 |
|---|---|---|
| 站内通知 | **P0** | 被评论 / 被回复 / 被点赞 / 被关注 |
| 未读数角标 | **P0** | 顶部红点提示 |
| 私信 | P1 | 一对一聊天（WebSocket）|
| 邮件通知 | P2 | |

### 模块 5：声望与成长体系

| 功能 | 优先级 | 说明 |
|---|---|---|
| 声望值 | **P0** | 发帖、被赞、被采纳累积 |
| 等级 / 徽章 | P1 | 等级解锁权限 |
| 学习打卡 | P1 | **差异化功能**：连续打卡、打卡日历 |
| 排行榜 | P1 | 周榜 / 月榜 / 总榜 |

### 模块 6：管理后台

| 功能 | 优先级 | 说明 |
|---|---|---|
| 内容审核 | **P0** | 举报处理、删帖、加精、置顶 |
| 用户管理 | **P0** | 封禁、禁言、角色调整 |
| 板块管理 | **P0** | 增删改板块 |
| 数据看板 | P1 | 发帖量、活跃用户 |
| 敏感词管理 | P1 | 反垃圾规则配置 |

## 7.4 核心难点（面试可挖点）

1. **计数问题** —— 直接 `COUNT(*)` 会拖垮数据库，需 Redis 计数 + 异步落库
2. **Feed 流** —— 拉模式（读时聚合）还是推模式（写时扩散）
3. **热点帖子** —— 单个爆款帖的读压力，需多级缓存
4. **全文检索** —— MySQL `LIKE %%` 无法满足
5. **点赞并发** —— 如何保证不重复、不少计

## 7.5 关键设计决策（本文档代决）

原三份文档中以下各点存在矛盾或遗漏，此处**一次性定死**：

| # | 议题 | 原文档状况 | **最终决定** | 理由 |
|---|---|---|---|---|
| D1 | ES 是否进 MVP | PRD 说 V0.2 / PRIORITY 说 MVP / TECH-DESIGN 说 S4 | **不进 MVP**，用 MySQL ngram 兜底 | 零额外依赖即满足 MVP 需求；ES 留给 V0.2 作为"优化前后对比"的叙事素材 |
| D2 | 计数落库方式 | 同一节里写了"@Async 内存批量 flush"和"Redis List 缓冲"两套 | **`@Async` 事件直接落库，不做批量缓冲** | MVP 无并发压力；批量缓冲是过早优化，且留作 V0.2 优化素材 |
| D3 | Feed 流模式 | "推拉结合 + 大 V 阈值 1000" | **MVP 用拉模式** | 阈值 1000 在个人项目里永远触发不了，写推模式是纯浪费 |
| D4 | `t_post.content_html` | 设计为写入时存渲染后 HTML | **MVP 不落库**，改读时渲染 + Redis 缓存 | 存储层存未消毒 HTML 有 XSS 风险；渲染器升级后存量数据不一致 |
| D5 | 计数一致性表述 | "Redis 是权威值，MySQL 是快照" | 改为「**MySQL 提供可重建基线，Redis 提供实时增量**」+ **必须实现重建脚本** | 原表述在"Redis 挂了怎么办"的追问下会崩 |
| D6 | Sprint 表 | PRIORITY 与 TECH-DESIGN 两份冲突，PRIORITY 的 S5 整行复制错误 | **以本文档 §13 为唯一权威** | |
| D7 | 表行数估算 | 标"十万级 / 百万级" | 改**双栏**：设计目标量级 + 实际预期量级 | 个人项目真实数据是百级/千级，不标注会导致架构过度设计 |
| D8 | `idx_reputation (reputation DESC)` | 建了降序索引 | **删除** | 普通索引反向扫描即可，无实际收益且增加写成本 |
| D9 | 热度分含浏览量 | `viewCount * 0.1` | **浏览数不计分**，或加去重（同用户/同 IP 当日只计一次）| 刷 1000 次浏览 ≈ 3 个赞，刷榜成本过低 |

---

# 8. 技术方案

## 8.1 技术选型（已按本机实测环境修订）⚠️

**原文档写"Java 17 + Spring Boot 3.3.x"，经核实已失效**，本节为修订后的定版。

| 层 | 选型 | 版本 | 本机状态 | 说明 |
|---|---|---|---|---|
| 语言 | Java | **21** | ✅ 21.0.11 Temurin | LTS，`D:\jdk\jdk-21` |
| 构建 | Maven | 3.9.16 | ✅ | `D:\java_web\maven\apache-maven-3.9.16` |
| 框架 | Spring Boot | **3.5.16** | — | 3.x 末版，见下方论证 |
| 安全 | Spring Security | 6.5.x | — | 随 Boot 3.5 传递 |
| JWT | JJWT | 0.12.x | — | |
| ORM | MyBatis-Plus | **3.5.9+** | — | ⚠️ 需实测兼容性（R2）|
| 数据库 | MySQL | 8.0.37 | ✅ 已装 | 需建库建账号 |
| 连接池 | HikariCP | 内置 | — | |
| 缓存 | Redis | 7.x | ❌ 未装 | **Docker 起容器** |
| 迁移 | Flyway | 10.x | — | 随 Boot 3.5 BOM |
| API 文档 | SpringDoc OpenAPI | **2.8.x** | — | ⚠️ 需实测兼容性（R2）|
| 异步 | Spring `@Async` + 线程池 | 内置 | — | 计数落库 / 通知 |
| 定时任务 | Spring `@Scheduled` | 内置 | — | 热度分快照 |
| 前端 | Vue 3 + TypeScript + Vite | 3.4+ / 5.x / 6.x | ✅ Node 22.22.2 | |
| UI 库 | Element Plus | 2.x | — | 生态成熟 |
| 状态管理 | Pinia | 2.x | — | |
| 路由 | Vue Router | 4.x | — | |
| HTTP | Axios | 1.x | — | 需封装统一拦截器 |
| Markdown | markdown-it | 13.x | — | ⚠️ 必须做 XSS 消毒 |
| 代码高亮 | highlight.js | — | — | 程序员社区必需 |
| 部署 | Docker + Compose | 29.5.3 / v5.1.4 | ✅ | 守护进程已验证 |

## 8.2 为什么是 Spring Boot 3.5.16（不是 3.3，也不是 4.x）

**先说一个必须纠正的事实**：原文档称"3.3.x 是企业真实在用的版本线，不追 4.x"。
这句话在 **2026-09 已不成立**——Spring Boot 3.3.x 已于 **2025-06-30 OSS EOL**，一年多没有补丁。

版本支持时间线（① 来源：spring.io 官方支持页面）：

| 版本线 | 首发 | OSS 支持截止 | 状态（2026-09-24）|
|---|---|---|---|
| 3.3.x | 2024-05 | **2025-06-30** | ❌ 已 EOL |
| 3.4.x | 2024-11 | **2025-12-31** | ❌ 已 EOL |
| 3.5.x | 2025-05 | **2026-06-30** | ⚠️ OSS 刚结束（企业支持至 2032-06）|
| 4.0.x | 2025-11 | 2026-12-31 | ✅ 受支持 |
| 4.1.x | 2026-06 | 2027-07 | ✅ 当前主线 |

**决策：用 3.5.16。**

| 维度 | 3.5.16 | 4.1.1 |
|---|---|---|
| OSS 支持 | ❌ 已结束 | ✅ 至 2027-07 |
| 企业支持 | ✅ 至 2032-06 | ✅ 至 2028-07 |
| 基线 | Spring Framework 6.2 | **Spring Framework 7.0** |
| Jakarta EE | 9 | **11** |
| Jackson | 2.x | **3.x（默认）** |
| 生态资料 | 极多（5 年积累） | 少，中文资料尤其少 |
| 面试官熟悉度 | 高 | 中（4.x 才 10 个月）|

**理由**：

1. **3.5.16 是 3.x 唯一还在企业支持期内的版本**（到 2032-06），不是"没人管的旧版本"。
2. **4.x 的收益本项目一个都用不上**：4.x 带来 gRPC、OTel 改进、SSRF 防护、Jackson 3——本项目没有一项需要。
3. **4.x 的成本是实打实的**：基于 Spring Framework 7，Jakarta EE 11 + Jackson 3 的迁移会牵动 Hibernate、Jackson、Tomcat 及自定义自动配置。
4. **生态资料差距**：3.x 有 5 年中文资料积累，遇错能搜到；4.x 才 10 个月。

> 📌 **面试加分点**：主动讲"为什么不用 Spring Boot 4"能体现技术判断力。
> 反之，若简历写 3.3.13，面试官会问"你不知道 3.3 已经 EOL 了吗"——那是减分。

## 8.3 刻意不选的

| 不选 | 理由 |
|---|---|
| ❌ 微服务 / Spring Cloud | 单人项目上微服务是自杀，运维成本远超收益；且"一个人写 7 个微服务"反而容易被怀疑是抄的 |
| ❌ MQ（RocketMQ / Kafka）| 论坛量级用不上，Spring Event 足够，避免"为了用而用" |
| ❌ Elasticsearch（MVP）| 多一个约 1GB 堆的中间件；零额外依赖的 MySQL ngram 已覆盖 MVP 需求 |
| ❌ 富文本编辑器 | Markdown 已足够，前端富文本是巨坑 |
| ❌ 移动端 App | H5 响应式已覆盖 |
| ❌ 付费 / 会员体系 | 与"个人作品"目标无关，且引入合规负担 |

> ⚠️ **概念纠错**：原文档两处称 ES"需评估机器资源（4GB **显存**笔记本）"。
> **这是概念错误**——Elasticsearch 消耗的是 **JVM 堆内存和磁盘 IO**，与显存无关。
> 不上 ES 的结论不变，但理由必须改对。这句话若留在技术文档里会暴露概念混淆。

## 8.4 系统架构（逻辑层）

```
┌─────────────────────────────────────────────────────────┐
│                 浏览器 (Vue 3 SPA)                       │
│   Element Plus + Pinia + Vue Router + Axios              │
└───────────────────────┬─────────────────────────────────┘
                        │ HTTP / JSON (JWT in Header)
                        ▼
┌─────────────────────────────────────────────────────────┐
│              Nginx (静态资源 + 反向代理)                  │
└───────────────────────┬─────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────────┐
│         Spring Boot 3.5 单体应用 (端口 8080)             │
│  ┌──────────────────────────────────────────────────┐   │
│  │ Controller 层  — 参数校验、统一响应封装           │   │
│  ├──────────────────────────────────────────────────┤   │
│  │ Security 层    — JWT 过滤器、权限注解             │   │
│  ├──────────────────────────────────────────────────┤   │
│  │ Service 层     — 业务逻辑、事务边界               │   │
│  ├──────────────────────────────────────────────────┤   │
│  │ Mapper 层      — MyBatis-Plus + 手写 SQL          │   │
│  └──────────────────────────────────────────────────┘   │
│  横切：全局异常处理 / 日志 / 限流 / Actuator              │
└──────┬────────────────┬────────────────┬────────────────┘
       │                │                │
       ▼                ▼                ▼
   ┌───────┐      ┌──────────┐     ┌──────────────┐
   │ MySQL │      │  Redis   │     │ Caffeine     │
   │ 8.0   │      │  7.x     │     │ (V0.2 本地)  │
   │主存储 │      │缓存/计数 │     │              │
   └───────┘      └──────────┘     └──────────────┘
```

**为什么是单体？**
- 单人开发，调试链路短，改一个功能不用起三个服务
- 部署简单：一个 jar + 前端静态文件
- 面试时能讲清"为什么不上微服务"，反而是加分项

## 8.5 环境准备（执行清单）

### 实测环境

```
JDK        ✅ 21.0.11 Temurin        JAVA_HOME=D:\jdk\jdk-21
Maven      ✅ 3.9.16
Node       ✅ 22.22.2
Docker     ✅ 29.5.3 (daemon 运行中)  Compose v5.1.4
MySQL      ✅ 8.0.37                 root 有密码（需配置）
Redis      ❌ 未安装                 需 Docker 起
Git        ✅ 2.55.0.windows.3
```

### 四个准备动作

> ✅ **执行状态（2026-09-24 21:38）：动作 1、2、4 均已完成并验证通过。**
> 实际执行中发现的问题与修正记录见本节末「执行实录」。

**动作 1 — 起 Redis（Docker）**
```bash
docker run -d --name codelkup-redis -p 6379:6379 \
  --restart unless-stopped redis:7-alpine
# 验证
docker exec codelkup-redis redis-cli ping   # 期望 PONG
```

**动作 2 — 建库与专用账号**（不用 root 跑应用）
```sql
CREATE DATABASE codelkup DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'codelkup'@'localhost' IDENTIFIED BY '<自行设置>';
GRANT ALL PRIVILEGES ON codelkup.* TO 'codelkup'@'localhost';
FLUSH PRIVILEGES;
```

**动作 3 — 密钥落 `.env` 并 gitignore**
```
SPRING_DATASOURCE_PASSWORD=<密码>
JWT_SECRET=<至少 32 字节随机串>
JWT_ACCESS_TTL=1800
JWT_REFRESH_TTL=604800
```
> **密码不进版本库、不进对话回显。** `.gitignore` 必须含 `.env`。

**动作 4（关键）— MySQL ngram 前置校验**
```sql
SHOW VARIABLES LIKE 'ngram_token_size';
```
- 有值 → 按 ngram 方案走
- 空 / 报错 → 降级：`LIKE` + 应用层分词，或写入时预生成 `content_index` 字段

> ⚠️ **这条不验证就写代码，Sprint 4 检索会整体返工。**
> ✅ **已验证通过（2026-09-24）**：`ngram` 插件状态 `ACTIVE`，`ngram_token_size=2`（中文双字分词）。
> **无需降级方案**，§10.4 的方案 A 可直接实施。

### 执行实录（2026-09-24）

四个动作已全部执行完毕，过程中发现 **3 个必须在文档中固化的环境事实**：

| # | 发现 | 影响 | 处置 |
|---|---|---|---|
| E1 | **8080~8083 被 `vmnat.exe`（VMware NAT 系统服务，PID 5896）整段占用** | 原文档假定的 8080 端口不可用 | 本项目改用 **8085**；已写入 `application.yml` 注释 |
| E2 | **WorkBuddy 注入环境变量 `SERVER__PORT=65336`**，Spring Boot 松散绑定会解析为 `server.port`，优先级高于 yml | 改 yml 无效，启动反复报 65336 端口冲突 | 启动时必须 `env -u SERVER__PORT`；已提供 `start-backend.bat` 封装 |
| E3 | **JDBC 的 `characterEncoding` 必须填 `UTF-8`，不能填 `utf8mb4`** | 填 `utf8mb4` 会抛 `Unsupported character encoding`，Flyway 无法连库 | 已修正；`utf8mb4` 是 MySQL 服务端字符集名，两者不是一个概念 |

另有一处代码语义缺陷在执行中发现并修复：

| # | 问题 | 修复 |
|---|---|---|
| E4 | 访问不存在的路径返回 `50001 系统错误`（语义错误，应为 404） | 配置 `spring.mvc.throw-exception-if-no-handler-found=true` + `spring.web.resources.add-mappings=false`，使其抛 `NoHandlerFoundException` 并被全局处理器正确捕获 |


## 8.6 Assumptions（未验证的假设）

| 假设 | 风险 | 验证方式 | 状态 |
|---|---|---|---|
| MySQL ngram 插件在本机可用 | 中 | `SHOW VARIABLES` | ⏳ 待验证 |
| MyBatis-Plus 3.5.9+ 兼容 Boot 3.5.16 | 中 | 建工程后启动测试 | ⏳ 待验证 |
| SpringDoc 2.8.x 兼容 Boot 3.5.16 | 中 | `/swagger-ui.html` 可访问 | ⏳ 待验证 |
| Vue 3 + TS 学习成本可接受 | 中 | 前端骨架跑通 | ⏳ 待验证 |
| 单人能在合理周期完成 P0 | **高** | 每 Sprint 自评，偏离 >30% 砍范围 | ⏳ 持续 |
| 部署后有人愿意用 | 中 | **不强求**；KR 不依赖真实用户量 | — |

---

# 9. 数据库设计

## 9.1 表清单

> 修正原文档虚高的估算：**个人项目真实数据量远小于"设计目标量级"**。
> 两栏分开标注，避免据此做过度的架构设计。

| 表名 | 说明 | 设计目标量级 | **实际预期量级** |
|---|---|---|---|
| `t_user` | 用户表 | 万级 | **百级** |
| `t_user_follow` | 关注关系 | 十万级 | **千级** |
| `t_board` | 板块表 | 十级 | **十级** |
| `t_tag` | 标签表 | 百级 | **百级** |
| `t_post` | 帖子表 | 十万级 | **百级** |
| `t_post_tag` | 帖子-标签关联 | 十万级 | **千级** |
| `t_comment` | 评论表 | 百万级 | **千级** |
| `t_user_action` | 点赞/收藏记录 | 百万级 | **千级** |
| `t_notification` | 通知表 | 百万级 | **千级** |
| `t_user_stat` | 用户统计 | 万级 | **百级** |
| `t_checkin` | 学习打卡（V0.3）| 十万级 | **千级** |

> 📌 **这张表是面试防御的关键**。当面试官问"你的线上 QPS 多少"，标准回答：
> "这是个人作品，实际数据量是百级帖子。架构按十万级设计是为了演示方案的可扩展性，但我会明确区分'设计目标'和'实际量级'，不虚构运营数据。"

## 9.2 `t_user`（用户）

```sql
CREATE TABLE t_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  email         VARCHAR(100) NOT NULL                COMMENT '邮箱（登录凭证，唯一）',
  password      VARCHAR(100) NOT NULL                COMMENT 'BCrypt 加密密码',
  nickname      VARCHAR(50)  NOT NULL                COMMENT '昵称（可重复，展示用）',
  avatar        VARCHAR(255) DEFAULT NULL            COMMENT '头像URL',
  bio           VARCHAR(255) DEFAULT NULL            COMMENT '个人简介',
  tech_tags     VARCHAR(255) DEFAULT NULL            COMMENT '技术标签，逗号分隔',
  role          TINYINT      NOT NULL DEFAULT 0      COMMENT '0普通 1版主 2管理员',
  status        TINYINT      NOT NULL DEFAULT 0      COMMENT '0正常 1禁言 2封禁',
  reputation    INT          NOT NULL DEFAULT 0      COMMENT '声望值',
  level         TINYINT      NOT NULL DEFAULT 1      COMMENT '等级',
  last_active   DATETIME     DEFAULT NULL            COMMENT '最后活跃时间',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_email (email),
  KEY idx_nickname (nickname),
  KEY idx_reputation (reputation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

**设计说明**：
1. `reputation` 冗余在用户表——它是排行榜和高频展示字段，写少读多，适合冗余。
2. **`email` 是唯一登录凭证**（`uk_email`），不设 `username`。
3. `nickname` **允许重复**（普通索引而非唯一索引）—— 避免"昵称被占用"的注册摩擦。
4. `password` 用 **BCrypt**（自带盐值），不用 MD5/SHA（可被彩虹表破解）。
5. **修订**：原文档建了 `idx_reputation (reputation DESC)` 降序索引 —— 已改为普通索引。MySQL 普通索引可反向扫描，降序索引在此无实际收益且增加写成本。

## 9.3 `t_post`（帖子）

```sql
CREATE TABLE t_post (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
  title         VARCHAR(120) NOT NULL                COMMENT '标题',
  content       MEDIUMTEXT   NOT NULL                COMMENT '正文（Markdown 原文）',
  summary       VARCHAR(255) DEFAULT NULL            COMMENT '摘要（列表页用）',
  author_id     BIGINT       NOT NULL                COMMENT '作者ID',
  board_id      BIGINT       NOT NULL                COMMENT '板块ID',
  type          TINYINT      NOT NULL DEFAULT 0      COMMENT '0普通帖 1提问帖',
  status        TINYINT      NOT NULL DEFAULT 0      COMMENT '0待审 1已发布 2已删 3已封禁',
  is_top        TINYINT      NOT NULL DEFAULT 0      COMMENT '是否置顶',
  is_featured   TINYINT      NOT NULL DEFAULT 0      COMMENT '是否加精',
  accepted_comment_id BIGINT DEFAULT NULL            COMMENT '采纳的评论ID',
  view_count    INT          NOT NULL DEFAULT 0      COMMENT '浏览数（Redis 计数）',
  like_count    INT          NOT NULL DEFAULT 0      COMMENT '点赞数（Redis 计数）',
  comment_count INT          NOT NULL DEFAULT 0      COMMENT '评论数（Redis 计数）',
  collect_count INT          NOT NULL DEFAULT 0      COMMENT '收藏数（Redis 计数）',
  score         DOUBLE       NOT NULL DEFAULT 0      COMMENT '热度分（定时算）',
  last_active   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_author (author_id),
  KEY idx_board_time (board_id, create_time DESC),
  KEY idx_score (score DESC),
  KEY idx_status_time (status, create_time DESC),
  FULLTEXT KEY ft_title_content (title, content) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子表';
```

**设计说明**：

1. **修订 D4**：原文档设计了 `content_html` 字段，在**写入时**存渲染后 HTML。**已删除**，改为读时渲染 + Redis 缓存。理由：
   - 存储层保存未经消毒的 HTML → XSS 风险被固化进数据库
   - Markdown 渲染器升级后，存量 HTML 与新渲染结果不一致，无法批量修正
   - 读时渲染 + 缓存同样能避免重复渲染，收益相同而风险更低

2. `FULLTEXT ... WITH PARSER ngram` —— 中文全文检索的兜底方案。**需先验证本机可用性**（§8.5 动作 4）。

3. `score` 是热度分，由定时任务计算写入，避免实时计算。

## 9.4 `t_comment`（评论）

```sql
CREATE TABLE t_comment (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  post_id       BIGINT       NOT NULL                COMMENT '帖子ID',
  author_id     BIGINT       NOT NULL                COMMENT '作者ID',
  content       TEXT         NOT NULL                COMMENT '评论内容',
  parent_id     BIGINT       NOT NULL DEFAULT 0      COMMENT '父评论ID，0为一级',
  reply_user_id BIGINT       DEFAULT NULL            COMMENT '被回复者ID',
  like_count    INT          NOT NULL DEFAULT 0,
  is_accepted   TINYINT      NOT NULL DEFAULT 0      COMMENT '是否被采纳',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_post (post_id, create_time),
  KEY idx_author (author_id),
  KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表';
```

**设计说明**：`parent_id + reply_user_id` 组合支持"回复某条评论"。**只做二级评论**——无限嵌套在前端渲染和查询上都是灾难，且实际体验无提升。

## 9.5 `t_user_action`（点赞/收藏统一表）

```sql
CREATE TABLE t_user_action (
  id            BIGINT   NOT NULL AUTO_INCREMENT,
  user_id       BIGINT   NOT NULL          COMMENT '操作者ID',
  target_type   TINYINT  NOT NULL          COMMENT '1帖子 2评论',
  target_id     BIGINT   NOT NULL          COMMENT '目标ID',
  action_type   TINYINT  NOT NULL          COMMENT '1点赞 2收藏',
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_target_action (user_id, target_type, target_id, action_type),
  KEY idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户互动记录表';
```

**设计说明**：`uk_user_target_action` 唯一索引是**防重复点赞的核心保障** —— 靠数据库约束而非业务代码判断，天然幂等。**这是一个能讲 10 分钟的点**。

## 9.6 `t_user_follow`（关注关系）

```sql
CREATE TABLE t_user_follow (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  follower_id BIGINT   NOT NULL COMMENT '关注者',
  followee_id BIGINT   NOT NULL COMMENT '被关注者',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_follow (follower_id, followee_id),
  KEY idx_followee (followee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注关系表';
```

## 9.7 `t_notification`（通知）

```sql
CREATE TABLE t_notification (
  id           BIGINT   NOT NULL AUTO_INCREMENT,
  receiver_id  BIGINT   NOT NULL           COMMENT '接收者',
  sender_id    BIGINT   DEFAULT NULL       COMMENT '触发者',
  type         TINYINT  NOT NULL           COMMENT '1评论 2回复 3点赞 4关注 5采纳 6系统',
  target_type  TINYINT  DEFAULT NULL       COMMENT '1帖子 2评论',
  target_id    BIGINT   DEFAULT NULL,
  content      VARCHAR(255) DEFAULT NULL,
  is_read      TINYINT  NOT NULL DEFAULT 0,
  create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_receiver_read (receiver_id, is_read, create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知表';
```

## 9.8 索引设计原则

- **列表页查询**优先建复合索引，**顺序遵循最左前缀**：`(board_id, create_time DESC)` 支撑"某板块按时间倒序"
- **高选择性字段前置**：`(status, create_time)` 优于 `(create_time, status)`
- **避免冗余索引**：已有 `uk_email` 就不需要再建 `idx_email`
- **深分页问题**：`LIMIT 100000, 20` 会全表扫 —— 本项目量级不构成问题，但面试会问，需准备答案（游标分页 / 延迟关联）

---

# 10. 关键方案设计（面试重点）

## 10.1 计数方案：Redis 计数 + 异步落库

**问题**：点赞数、评论数、浏览数如果每次都 `UPDATE t_post SET like_count = like_count + 1`，高并发下行锁竞争；读时 `COUNT(*)` 更会拖垮数据库。

**方案（修订后）**：
```
写路径：
  用户点赞 → 写 t_user_action（唯一索引防重，失败即已点赞）
           → Redis INCR post:like:{postId}
           → 发 Spring Event（@Async）
           → 异步直接落库 MySQL

读路径：
  查帖子 → 先读 Redis 计数
         → Redis 无数据则读 MySQL 并回填 Redis

重建路径（关键补充）：
  定时 / 手动触发 → SELECT COUNT(*) FROM t_user_action
                    WHERE target_type=1 AND target_id=? AND action_type=1
                  → 覆盖写入 Redis
```

**一致性表述（修订 D5）**：

原文档写"Redis 的计数是权威值，MySQL 是快照"——**这个表述有隐患**，面试官会追问"Redis 挂了计数就永久错了怎么办"。

改为：
> **MySQL 提供可重建的基线，Redis 提供实时增量。** 两者是"基线 + 增量"关系，不是"快照 + 权威"关系。
> 因此 Redis 数据丢失时，**可以从 `t_user_action` 反查重建**，不存在"永久错误"。

**为什么不做批量缓冲（修订 D2）**：
原文档同一节里既写"`@Async` 内存批量 flush"，又写"Redis List 做写缓冲"——两套方案自相矛盾。
**MVP 决定：`@Async` 事件直接落库，不做批量缓冲。** 理由：
- MVP 无并发压力，批量缓冲是过早优化
- 批量缓冲会引入"进程重启丢内存增量"的一致性问题，反而更复杂
- **留下优化空间正是 V0.2 的叙事素材**（"优化前的版本本身就是素材"）

**面试追问预案**：
| 问题 | 回答 |
|---|---|
| Redis 挂了怎么办？ | 计数降级为直接读写 MySQL（加锁），同时告警；恢复后跑重建脚本 |
| 为什么用 `INCR` 不用 `SET`？ | `INCR` 是原子操作，天然并发安全 |
| 计数错了怎么修？ | 从 `t_user_action` 反查 `COUNT(*)` 重建——这就是防重表存在的额外价值 |
| 为什么不做批量缓冲？ | 量级不需要；先用真实压测暴露瓶颈，再决定是否需要——避免过早优化 |

## 10.2 多级缓存（V0.2）

```
请求 → Caffeine（进程内，毫秒级）→ 命中则返回
     → 未命中 → Redis → 命中则回填 Caffeine
     → 未命中 → MySQL → 回填 Redis + Caffeine
```

**参数**：
- Caffeine：`maximumSize=10000`，`expireAfterWrite=5min`
- Redis：`TTL=30min` + 随机偏移防雪崩（`TTL + random(0, 300s)`）

| 问题 | 应对 |
|---|---|
| 缓存穿透 | 空值也缓存（短 TTL），或布隆过滤器 |
| 缓存击穿 | 热点 key 加互斥锁（Redisson）|
| 缓存雪崩 | TTL 加随机偏移；Caffeine 兜底 |
| 数据一致性 | **先更新 DB，再删除缓存**（Cache Aside）|

> ⚠️ **修订**：原文档在 Cache Aside 后追加"延迟双删兜底"。
> 延迟双删是**有争议的方案**（延迟多久？第二次删除失败怎么办？），容易在面试中被反问。
> **MVP 只保留 Cache Aside + 合理的 TTL**，不写延迟双删。若面试被问到，可以主动讨论其争议——比写在文档里当"标准答案"更安全。

## 10.3 Feed 流（修订 D3）

**MVP 用拉模式**：
```
读 Feed：
  1. 查我关注的人（t_user_follow）
  2. 按 (author_id IN (...)) 查最新帖子，时间倒序，分页
  3. 返回
```

**为什么不写推拉结合（原文档方案）**：
原文档设计了"粉丝 < 1000 走推模式，≥ 1000 走拉模式"。但**阈值 1000 在个人项目里永远触发不了**——真实粉丝数是两位数。写一套用不上的推模式，是纯粹的浪费。

**拉模式的代价与应对**：
- 代价：关注 500 人时查询会变慢 → **但个人项目关注数不会到 500**
- 应对：若关注数增长，用 `t_user_follow` 的索引 + 分页限制（`LIMIT 20`）控制

**V0.2 再做推拉结合**——届时正好有"朴素实现 → 发现瓶颈 → 优化"的完整叙事。

## 10.4 全文检索（修订 D1）

**方案 A：MySQL ngram 全文索引（MVP 采用）**
```sql
-- 建表时已加（§9.3）
FULLTEXT KEY ft_title_content (title, content) WITH PARSER ngram
```
```sql
SELECT *, MATCH(title, content) AGAINST(? IN BOOLEAN MODE) AS relevance
FROM t_post
WHERE MATCH(title, content) AGAINST(? IN BOOLEAN MODE)
ORDER BY relevance DESC;
```
- ✅ 零额外依赖
- ❌ 无高亮（需应用层自己标红）、无同义词、无权重微调

**方案 B：Elasticsearch（V0.2 评估）**
- 帖子发布/更新时异步同步到 ES
- 索引设计：`title`（权重 3）+ `content`（权重 1）+ `tags`（权重 2）
- 中文分词：`ik_max_word`；高亮：ES 的 `highlight`
- ❌ 代价：约 1GB JVM 堆 + 磁盘 IO（**与显存无关**，原文档此处概念错误）

**为什么 MVP 选 A**：
1. 检索能力本身是 MVP 需求（"内容找不到"是核心痛点）
2. 但实现方式可选——ngram 零依赖即可满足
3. ES 是**性能优化**而非**功能补全**，放 V0.2 作为优化素材更合适：
   *"V1 用 MySQL ngram，发现中文分词效果与性能瓶颈后迁移到 ES"* —— 这是能讲的优化叙事

## 10.5 热度分算法（V0.2）

```java
// 简化版 Hacker News 算法
score = (likeCount * 3 + commentCount * 5 + viewCount * 0.1)
        / pow(hoursSincePost + 2, 1.5)
```

> ⚠️ **修订 D9**：原公式给浏览量 0.1 权重，意味着**刷 1000 次浏览 ≈ 3 个赞**，刷榜成本过低。
> **改为：浏览数不计分**（或加去重：同用户/同 IP 当日只计一次）。

- 评论权重 > 点赞（评论成本更高，代表更深参与）
- 分母时间衰减保证新帖有机会上榜
- `@Scheduled` 每 10 分钟遍历计算，写入 `t_post.score`
- 排行榜用 Redis ZSet 维护（`ZADD post:hot {score} {postId}`）

---

# 11. API 接口设计

## 11.1 统一响应格式

```json
{
  "code": 0,
  "message": "success",
  "data": { }
}
```
- `code`：0 成功，非 0 为业务错误码（如 40001 参数错误、40101 未登录）
- HTTP 状态码与业务码分离（便于前端统一处理）

## 11.2 接口清单（MVP 范围）

### 认证 `/api/auth`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/register` | 注册（email + password + nickname），返回 token |
| POST | `/login` | 邮箱+密码登录，返回 access + refresh token |
| POST | `/refresh` | 刷新 token |
| POST | `/logout` | 登出（token 加入黑名单）|
| GET | `/check-email?email=` | 注册前校验邮箱是否已占用 |

**注册请求体**：
```json
{
  "email": "user@example.com",
  "password": "明文密码（HTTPS 传输，后端 BCrypt 加密）",
  "nickname": "昵称"
}
```

**校验规则**：

| 字段 | 规则 |
|---|---|
| email | 必填、格式校验、唯一性校验、长度 ≤ 100 |
| password | 必填、长度 8~32、至少含字母和数字 |
| nickname | 必填、长度 2~20、不能全是空格 |

**登录失败处理**：邮箱不存在与密码错误**返回同一错误信息**（"邮箱或密码错误"），避免账号枚举攻击。同时按 IP + 邮箱维度做失败限制（5 次失败锁定 15 分钟）。

### 用户 `/api/users`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/{id}` | 用户主页信息（V0.3 追加只读统计：`askedCount` / `acceptedCount` / `acceptRate`，均按"已发布提问帖"口径；一次都没提问过时 `acceptRate` 为 `null`）|
| GET | `/me` | 本人资料（含邮箱）|
| PUT | `/me` | 修改自己的资料 |
| PUT | `/me/password` | 修改密码（验旧密码 → 清 refresh 白名单并拉黑当前 accessToken，需重新登录）|
| POST | `/me/avatar` | 上传头像（jpg/png/webp ≤2MB；替换成功后清理旧文件，与正文配图共用上传限流桶）|
| GET | `/me/collections` | 我的收藏（分页，按收藏时间倒序；已被删除/下架的帖子不返回）|
| GET | `/{id}/posts` | 某用户的帖子列表 |
| GET | `/{id}/followers` | 粉丝列表 |
| GET | `/{id}/followees` | 关注列表 |
| POST | `/{id}/follow` | 关注 |
| DELETE | `/{id}/follow` | 取关 |

### 上传 `/api/uploads`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/images` | 通用图片上传（正文配图）。jpg/jpeg/png/webp、≤5MB、需登录；扩展名白名单 + 魔数校验 + 单用户文件数配额 + 30 次/小时限流，返回 `{url}` |

### 帖子 `/api/posts`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 帖子列表（boardId / tag / type / sort / 分页）|
| GET | `/?type=1&solved=false` | 提问闭环筛选：`solved=true` 只看已采纳、`false` 只看未采纳（**仅 `type=1` 生效**，其他类型传了当没传）|
| GET | `/{id}` | 帖子详情（浏览量 +1）|
| POST | `/` | 发帖 |
| PUT | `/{id}` | 编辑帖子 |
| DELETE | `/{id}` | 删除帖子 |
| POST | `/{id}/like` | 点赞 |
| DELETE | `/{id}/like` | 取消点赞 |
| POST | `/{id}/collect` | 收藏 |
| DELETE | `/{id}/collect` | 取消收藏 |
| POST | `/{id}/accept/{commentId}` | 采纳答案（仅楼主）|

### 评论 `/api/posts/{postId}/comments`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 评论列表（树形）|
| POST | `/` | 发表评论 |
| DELETE | `/{commentId}` | 删除评论 |
| POST | `/{commentId}/like` | 评论点赞 |

### Feed `/api/feed`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/follow` | 关注流（MVP 拉模式）|
| GET | `/latest` | 最新 |
| GET | `/recommend` | 推荐流（V0.2）|
| GET | `/hot` | 热门榜（V0.2）|

### 搜索 `/api/search`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/posts?keyword=` | 全文检索帖子 |

### 通知 `/api/notifications`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 通知列表 |
| GET | `/unread-count` | 未读数 |
| PUT | `/{id}/read` | 标记已读 |
| PUT | `/read-all` | 全部已读 |

### 管理 `/api/admin`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/PUT/DELETE | `/posts/**` | 帖子管理（审核/加精/置顶/删）|
| GET/PUT | `/users/**` | 用户管理（禁言/封禁/角色）|
| GET/POST/PUT/DELETE | `/boards/**` | 板块管理 |

---

# 12. 项目结构

## 12.1 后端（Maven 单模块）

```
codelkup-backend/
├── pom.xml
├── Dockerfile
├── src/main/java/com/codelkup/
│   ├── CodelkupApplication.java
│   ├── common/                    # 通用
│   │   ├── result/                # 统一响应 Result、错误码
│   │   ├── exception/             # 全局异常 + 自定义异常
│   │   ├── constant/              # 常量（Redis key 前缀等）
│   │   └── util/                  # 工具类
│   ├── config/                    # 配置
│   │   ├── SecurityConfig.java
│   │   ├── RedisConfig.java
│   │   ├── MybatisPlusConfig.java
│   │   ├── AsyncConfig.java
│   │   ├── WebMvcConfig.java
│   │   └── OpenApiConfig.java
│   ├── security/                  # 认证授权
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── JwtTokenProvider.java
│   │   └── UserDetailsServiceImpl.java
│   ├── module/                    # 业务模块（按领域分包）
│   │   ├── user/
│   │   │   ├── controller/
│   │   │   ├── service/ + impl/
│   │   │   ├── mapper/
│   │   │   ├── entity/
│   │   │   └── dto/ + vo/
│   │   ├── post/
│   │   ├── comment/
│   │   ├── follow/
│   │   ├── feed/
│   │   ├── search/
│   │   ├── notification/
│   │   └── admin/
│   └── infrastructure/            # 基础设施
│       ├── cache/                 # 多级缓存封装（V0.2）
│       ├── counter/               # 计数服务
│       ├── event/                 # 事件定义 + 监听器
│       └── storage/               # 文件存储抽象
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   ├── mapper/                    # MyBatis XML
│   └── db/migration/              # Flyway 迁移脚本
└── src/test/java/                 # 测试
```

## 12.2 前端（Vue 3 + Vite）

```
codelkup-frontend/
├── package.json
├── vite.config.ts
├── Dockerfile
├── nginx.conf
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── api/                       # 接口封装（按模块）
│   ├── assets/
│   ├── components/                # 通用组件
│   │   ├── MarkdownEditor.vue
│   │   ├── MarkdownViewer.vue
│   │   ├── PostCard.vue
│   │   ├── CommentTree.vue
│   │   └── UserAvatar.vue
│   ├── composables/
│   ├── layouts/
│   ├── router/                    # 路由 + 守卫
│   ├── stores/                    # Pinia
│   ├── types/                     # TS 类型定义
│   ├── utils/                     # request.ts（axios 封装）
│   └── views/                     # 页面
│       ├── home/
│       ├── post/
│       ├── user/
│       ├── search/
│       ├── notification/
│       └── admin/
└── public/
```

## 12.3 部署（Docker Compose）

```
deploy/
├── docker-compose.yml
├── .env.example
├── mysql/
│   └── init.sql
└── nginx/
    └── nginx.conf
```

```yaml
services:
  mysql:      # 8.0，挂载 init.sql
  redis:      # 7-alpine
  backend:    # Spring Boot jar
  frontend:   # Nginx + 静态文件
```

---

# 13. Sprint 排期

> **本文档 §13 为唯一权威排期**。原 PRIORITY 与 TECH-DESIGN 两份 Sprint 表互相冲突（且 PRIORITY 的 S5 整行复制错误），一律以本节为准。

## 13.1 容量估算（个人项目版）

原 sprint-plan 方法论假设"团队 + 历史 velocity"，本项目是**单人 + 无历史数据**，需换算口径。

| 项 | 取值 | 依据 |
|---|---|---|
| 每周可投入 | **15 小时**（工作日 1.5h × 5 + 周末 4h）| 秋招期需同时投递/面试 |
| 有效编码率 | **70%**（扣除调试环境、查文档、返工）| 单人无结对评审，返工率高于团队 |
| 每周净产出 | **≈ 10.5 小时** | 15 × 70% |
| 缓冲 | **−20%**（应对面试突发、环境问题）| sprint-plan 要求留 15~20% |
| **每周最终可用** | **≈ 8.5 小时** | |

## 13.2 Sprint 0 — 环境与骨架

| 项 | 内容 |
|---|---|
| **Sprint Goal** | 打开浏览器能看到后端真实返回的 JSON，且依赖服务全部可启动 |
| **周期** | 3~5 天（≈ 5h）|
| **容量** | 5h |

| # | 任务 | 估时 | 依赖 | 验收标准 |
|---|---|---|---|---|
| 0.1 | 起 Redis 容器 + 建库建账号 | 1h | 无 | `redis-cli ping` → PONG；账号能连库 |
| 0.2 | **校验 MySQL ngram 可用性** | 0.5h | 0.1 | `ngram_token_size` 有值，或已定降级方案 |
| 0.3 | Spring Boot 3.5.16 + Java 21 工程初始化 | 1h | 无 | `mvn spring-boot:run` 启动无错 |
| 0.4 | 统一响应 + 全局异常 + **curl 端到端验证** | 1h | 0.3 | 异常返回统一 JSON |
| 0.5 | SpringDoc 接入 | 0.5h | 0.3 | `/swagger-ui.html` 可访问 |
| 0.6 | Flyway + `t_user` 首张表迁移 | 1h | 0.1, 0.3 | 启动自动建表 |

**承诺 5h / 容量 5h** → 缓冲靠"3~5 天"的天数弹性吸收。

> 📌 **S0 硬性要求**：0.4 必须**端到端 curl 验证过**再进 S1。
> 不追求一次性写完所有配置——逐个跑通，出问题才能定位。

## 13.3 Sprint 1 — 认证与用户

| 项 | 内容 |
|---|---|
| **Sprint Goal** | 能用邮箱注册、登录拿到 token、带 token 访问受保护接口 |
| **周期** | 1 周（≈ 8.5h）|

| # | 任务 | 估时 | 依赖 |
|---|---|---|---|
| 1.1 | `t_user` 实体 + Mapper + 注册接口（BCrypt）| 2h | S0 |
| 1.2 | 登录 + JWT 签发（access + refresh）| 2.5h | 1.1 |
| 1.3 | Spring Security 配置 + JWT 过滤器 + 权限注解 | 2.5h | 1.2 |
| 1.4 | 全局获取当前登录用户（SecurityContext 封装）| 1h | 1.3 |
| 1.5 | 个人主页基础接口 | 0.5h | 1.1 |

> **明确移出 S1**：头像上传 → 推迟到 S2 末尾。
> 理由：头像上传要引入文件存储抽象、路径穿越防护、静态资源映射，是独立的一块，塞在认证周会挤爆容量。

## 13.4 Sprint 2 — 内容核心

| 项 | 内容 |
|---|---|
| **Sprint Goal** | 登录用户能发 Markdown 帖子，游客能在列表看到并打开详情 |
| **周期** | 1.5 周（≈ 12.5h）|

| # | 任务 | 估时 | 依赖 |
|---|---|---|---|
| 2.1 | `t_board` / `t_tag` / `t_post_tag` 建表 + 接口 | 2h | S1 |
| 2.2 | 发帖（Markdown 存原文）| 2h | 2.1 |
| 2.3 | 帖子列表（分页 + 板块筛选 + 排序）| 2.5h | 2.2 |
| 2.4 | 帖子详情 + 浏览计数 | 2h | 2.2 |
| 2.5 | Markdown 渲染前端接入（**含 XSS 消毒**）| 2h | 2.2 |
| 2.6 | 头像上传（从 S1 移入）| 2h | S1 |

> ⚠️ **XSS 硬性要求**：Markdown 渲染必须对 HTML 做消毒（如 DOMPurify）。
> 配合修订 D4（`content_html` 不落库），XSS 风险不进入存储层。

## 13.5 Sprint 3 — 互动（核心卖点周）

| 项 | 内容 |
|---|---|
| **Sprint Goal** | 点赞不会重复、不会少计；Redis 实时计数，且能重建回 MySQL |
| **周期** | 1 周（≈ 8.5h）|

| # | 任务 | 估时 | 依赖 |
|---|---|---|---|
| 3.1 | 评论 + 二级回复 | 3h | S2 |
| 3.2 | `t_user_action` + 点赞/收藏（唯一索引防重）| 2h | S2 |
| 3.3 | Redis 计数（INCR / DECR）+ 读取回填 | 2h | 3.2 |
| 3.4 | **计数重建脚本** | 1.5h | 3.3 |

> ⚠️ **3.4 是原文档遗漏的必需项**。没有它，"最终一致"在"Redis 挂了怎么办"的追问下会崩。
> **这也是本 Sprint 最值得在面试里讲的一块。**

## 13.6 Sprint 4 — 检索与发现

| 项 | 内容 |
|---|---|
| **Sprint Goal** | 搜索框输入中文关键词能搜到正文内容，响应 < 500ms |
| **周期** | 1.5 周（≈ 12.5h）|

| # | 任务 | 估时 | 依赖 |
|---|---|---|---|
| 4.1 | 全文检索（ngram 或降级方案，按 0.2 结论）| 4h | S0.2 |
| 4.2 | 搜索结果高亮 | 2h | 4.1 |
| 4.3 | 关注 / 粉丝 + 关注流（**拉模式**）| 4h | S1 |
| 4.4 | 站内通知 + 未读数 | 2.5h | S3 |

## 13.7 Sprint 5 — 收尾与可交付

| 项 | 内容 |
|---|---|
| **Sprint Goal** | 换台干净机器能 `docker compose up` 跑起来，README 让陌生人看懂 |
| **周期** | 1 周（≈ 8.5h）|

| # | 任务 | 估时 | 依赖 |
|---|---|---|---|
| 5.1 | Dockerfile（前后端）+ compose 编排 | 3h | 全部 |
| 5.2 | 核心模块单元测试（覆盖率 > 60%）| 3h | 全部 |
| 5.3 | README + API 文档 | 1.5h | 全部 |
| 5.4 | 压测 + **优化前后对比数据** | 1h | 5.1 |

> 📌 **5.4 是简历最关键的一小时**。没有这个数字，简历只能写"做了个论坛"；有了它才能写"P95 从 Xms 优化到 Yms"。

## 13.8 总周期与现场实情 ⚠️

**S0~S5 合计约 6.5 周 × 8.5h ≈ 55 有效小时。**

**但这里有个必须说清的问题**：

> 今天是 **2026-09-24**。6.5 周后是 **11 月中旬**。
> 27 届秋招核心投递窗口就在 9~11 月，多数公司 10 月底进入收尾。
> **按计划完整做完，项目上线时间 ≈ 秋招结束时间。**

这不是排期问题，是"按完整产品思路规划一个求职作品"导致的错位。

### 建议：分两段交付，提前投递

| 阶段 | 内容 | 时间点 | 简历可写内容 |
|---|---|---|---|
| **第一段（S0~S3）** | 骨架 + 认证 + 内容 + 互动计数 | **约 4 周 → 10 月下旬** | "完成核心闭环，含 Redis 计数与防重设计" |
| **投递** | **不等全部做完，立即投递** | 10 月下旬 | 用已完成部分写简历 |
| 第二段（S4~S5）| 检索 + 关注 + 容器化 + 压测 | 11 月中 | 更新"追加全文检索与压测报告" |

**理由**：
1. 面试官不要求作品完整，要求**有能讲深的技术点**。S3 结束时已有 2 个（唯一索引防重、Redis 计数重建）。
2. 若 11 月才做完，10 月投的简历没有这个项目，等于白做。
3. 边投边做，面试反馈能修正开发重点。

### 砍单顺序（从低到高）

头像上传 → 收藏 → 通知 → 关注/粉丝 → 搜索高亮

**绝不砍**：注册登录、发帖看帖、评论、点赞防重、Redis 计数、全文检索、Docker 部署

## 13.9 关键路径与卡点

```
S0.1 起 Redis ──┐
S0.3 建工程 ────┼──► S1 认证 ──► S2 发帖 ──► S3 点赞+计数 ──► S4 检索 ──► S5 容器化
S0.2 ngram校验 ─┘
```

| 卡点 | 为什么卡 | 缓冲方案 |
|---|---|---|
| **S0.1 Redis 起不来** | 后续所有计数功能依赖 | 进程内 `ConcurrentHashMap` + `@Scheduled` 落库顶替（牺牲 Redis 卖点，但不阻塞进度）|
| **S1 JWT 安全配置** | Spring Security 是全项目最易卡住的一块 | 先做"放行所有接口 + 手动解析 token"，跑通业务后再收紧安全链 |
| **S0.2 ngram 不可用** | S4 检索会整体返工 | 已在 S0 提前验证；降级走应用层分词 |

---

# 14. Release 发布规划

> 不使用绝对日期，用相对周期。按"一个人、业余时间"的产能估算。

## V0.1 — MVP 内核（14 项，约 4~6 周）

**目标**：跑通"注册 → 发帖 → 看帖 → 评论 → 点赞 → 搜索"闭环

**必做（14 项）**：
1. 注册/登录（邮箱 + 密码，JWT 双 token）
2. 个人主页 + 资料编辑
3. 关注/粉丝
4. 板块 + 标签
5. 发帖/编辑/删除（Markdown + 代码高亮）
6. 帖子列表（分页 + 筛选）
7. 帖子详情（浏览计数）
8. 评论 + 二级回复
9. 点赞/收藏（唯一索引防重）
10. 全文检索（MySQL ngram）
11. 站内通知 + 未读数
12. 管理后台（简版）
13. 统一响应 + 全局异常
14. Docker Compose 部署

**交付标准**：本地跑通全流程，Docker Compose 一键启动

## V0.2 — 检索与性能优化（约 2~3 周）

- 计数优化（Redis List 批量落库）
- 多级缓存（Caffeine + Redis）
- 热门排行榜（Redis ZSet + 热度分）
- Feed 流推拉结合
- Elasticsearch 全文检索（评估）
- 压测报告 + 优化前后对比

## V0.3 — 成长体系（约 2 周）

- 声望值 + 等级 + 徽章
- 学习打卡（**差异化功能**）
- 采纳最佳答案
- 站内通知完善

## V0.4 — 体验完善（约 2 周）

- 第三方登录（GitHub）
- 私信（WebSocket）
- 数据看板
- 性能优化与文档补全

## 不做什么（明确排除）

- ❌ 移动端 App（H5 响应式即可）
- ❌ 实时协同编辑
- ❌ 视频内容
- ❌ 商业化 / 付费体系

---

# 15. 风险与应对

| # | 风险 | 概率 | 影响 | 应对 |
|---|---|---|---|---|
| R1 | **秋招时间冲突导致停滞**（面试/笔试挤占周末）| **高** | **高** | 按 §13.8 分两段交付，4 周后即投递；每周只承诺 8.5h |
| R2 | **依赖版本不兼容**（MyBatis-Plus / SpringDoc 对 Boot 3.5.16）| **中高** | 中 | S0.3 建工程后**第一件事就是验证这两个依赖能启动** |
| R3 | Spring Security 配置卡壳 | 中 | 中 | 降级：先放开鉴权跑通业务，后补安全链（§13.9）|
| R4 | Redis 容器不稳定 / 端口冲突 | 中 | 中 | `docker logs` 排查；6379 被占用则换 6380 并同步配置 |
| R5 | ngram 中文分词效果差 | 中 | 低 | 效果差不是 bug 而是优化素材：V0.2 迁移 ES 正好有对比数据 |
| R6 | 前端 Vue3 + TS 学习成本超预期 | 中 | 中 | 降级：**TS 只写接口类型定义，其余用 JS** |
| R7 | 范围蔓延（想加 P1/P2 功能）| **高** | **高** | §13.8 已列砍单顺序；每 Sprint 结束自评，偏离 > 30% 立即砍 |

---

# 16. 待确认清单

## ✅ 已确认

| 项 | 决策 | 日期 |
|---|---|---|
| 登录方式 | **邮箱 + 密码**，邮箱即登录凭证，不设 username | 2026-09-24 |
| 邮箱验证码 / 找回密码 | **MVP 不做**（暂不引入邮件服务）| 2026-09-24 |
| 技术栈 | **Java 21 + Spring Boot 3.5.16**（原 3.3 已 EOL）| 2026-09-24 |
| ES 是否进 MVP | **不进**，MySQL ngram 兜底 | 2026-09-24 |
| 计数落库方式 | `@Async` 直接落库，不做批量缓冲 | 2026-09-24 |
| Feed 流 | MVP 拉模式 | 2026-09-24 |
| `t_post.content_html` | MVP 不落库 | 2026-09-24 |

## ⏳ 待你确认

1. **产品名**：`Codelkup` 是占位名，要改吗？
2. **工程目录**：`C:\Users\32519\Desktop\Codelkup\codelkup\` 是否可以？（可放后端 + 前端两个子目录）
3. **MySQL 凭据**：root 密码，或是否用 §8.5 动作 2 建独立账号
4. **Redis**：是否用 §8.5 动作 1 的 Docker 命令直接起？
5. **前端 UI 库**：Element Plus（推荐，生态最成熟）/ Naive UI / Ant Design Vue？
6. **前端语言**：TypeScript（推荐，可降级）还是 JavaScript？
7. **数据库迁移**：Flyway（推荐）/ 手写 SQL 脚本？
8. **是否采纳 §13.8 的"4 周后即投递"策略**？

## 📌 因邮箱登录方案产生的后续改动点

- 若将来做"找回密码"：需引入 SMTP（Spring Mail + 邮件推送），`t_user` 增 `email_verified` 字段
- 若将来做"邮箱验证"：注册流程改为"先发码后设密码"两段式
- 邮箱字段长度 100 是常规值，若需支持超长企业邮箱可扩到 255

---

# 17. 附录：证据等级与修订记录

## 17.1 证据等级说明

| 标记 | 含义 |
|---|---|
| ① 官方 | spring.io 官方支持页面、官方文档 |
| ② 官方摘要 | 官方 release notes 的二手转述 |
| ③ 第三方 | 技术博客、社区文章 |
| ④ 未核 | 本文档内的推断，尚未验证 |

## 17.2 本文档的关键事实来源

**标记 ①（已核实）**：
- Spring Boot 各版本 OSS 支持起止日期 —— 来源：spring.io 官方支持表
- 3.5.16 为 3.x 最后补丁版（2026-06-25 发布）
- 4.1.1 为当前最新版（2026-08-20 发布）
- Spring Boot 3.x 支持 Java 17~25；4.x 支持 Java 17~26
- 本机环境版本（JDK 21.0.11 / Maven 3.9.16 / MySQL 8.0.37 / Docker 29.5.3 / Node 22.22.2）

**标记 ④（待验证 —— 必须在 S0 完成）**：
- MySQL `ngram_token_size` 在本机是否可用
- MyBatis-Plus 3.5.9+ 与 SpringDoc 2.8.x 对 Boot 3.5.16 的兼容性
- §13.1 工时估算（15h/周、70% 有效率）—— 主观推断，需按实际进度校正

## 17.3 相对原三份文档的修订清单

| # | 原文档问题 | 修订 |
|---|---|---|
| 1 | Spring Boot 3.3.x（已于 2025-06-30 EOL）| 改为 **3.5.16**，并论证为何不上 4.x |
| 2 | Java 17 | 改为 **Java 21**（本机实际版本）|
| 3 | 计数器方案自相矛盾（两套方案并存）| 明确定为 `@Async` 直接落库 |
| 4 | 两份 Sprint 表冲突，PRIORITY 的 S5 整行复制错误 | 统一为 §13 一套 |
| 5 | 全文检索归属三种说法 | 明确 **MVP 用 ngram**，Sprint 4 实现 |
| 6 | 表行数估算虚高（十万/百万）| 改双栏：设计目标 + 实际预期 |
| 7 | "4GB **显存**笔记本"（概念错误）| 纠正为「JVM 堆内存与磁盘 IO」|
| 8 | Redis 计数称"权威值"，无重建机制 | 改为"基线 + 增量"，并**补重建脚本**（S3.4）|
| 9 | Feed 流推拉结合（阈值 1000 永不触发）| MVP 改拉模式 |
| 10 | `t_post.content_html` 写入时存 HTML（XSS 风险）| MVP 不落库，改读时渲染 + 缓存 |
| 11 | `idx_reputation (reputation DESC)` 降序索引 | 删除 |
| 12 | 热度分含浏览量（刷榜成本过低）| 浏览数不计分或加去重 |
| 13 | 延迟双删（争议方案当标准答案）| 移除，只保留 Cache Aside + TTL |
| 14 | 未识别秋招时间窗口的真实约束 | 新增 §13.8 分两段交付策略 |

---

**（文档结束）**
