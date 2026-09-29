#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
演示数据种子 —— 让公网 demo 一打开就是"有人在用的技术社区"。

设计取舍（针对"给面试官看的演示站"这个唯一目标）：
  1. **内容全部围绕本项目自己的技术决策**（索引、计数一致性、检索、XSS、部署……）。
     面试官点进来读到的就是可以提问的素材，而不是"测试帖子 1 / 测试帖子 2"。
  2. **走公开 HTTP 接口而非直插数据库**：种子脚本本身就是一次全链路冒烟
     （注册 → 登录 → 发帖 → 评论 → 点赞 → 收藏 → 采纳 → 关注 → 通知）。
  3. **幂等**：用户已存在则登录，帖子标题已存在则跳过。可反复执行，不会产生重复数据。
  4. 只依赖标准库（服务器上无需 pip install）。

用法：
    python3 seed-demo.py --base http://127.0.0.1:8090/api
    python3 seed-demo.py --base https://demo.example.com/api --password 'YourStrongPass1'

输出：末尾打印演示账号与密码（可据此在简历/README 里写"演示账号"）。
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

# ============================================================
# 演示账号（密码由 --password 统一指定）
# ============================================================
USERS = [
    # key, 昵称, 技术标签, 个人简介
    ("ab", "阿布", "Java,MySQL,Redis", "五年 CRUD 老兵，正在补课分布式与数据库内核"),
    ("xiaoman", "小满", "TypeScript,Vue,工程化", "把设计稿还原到像素级，顺带研究前端安全"),
    ("qiyu", "齐玉", "算法,NLP,Python", "做过检索增强，最近在研究中文分词"),
    ("lanke", "蓝可", "Docker,K8s,监控", "一个人也能把 CI/CD 和告警搭起来"),
    ("tangzhu", "汤煮", "全栈,独立开发,产品", "独立开发过三个小产品，喜欢砍需求"),
    ("mumu", "木木", "计算机基础,操作系统", "还在啃 CSAPP，乐于刨根问底"),
]

# ============================================================
# 帖子：内容是项目本身的技术决策，面试官可直接就着内容提问
#   board 用 slug；type 1=提问帖（可采纳答案）；accept=采纳谁的评论
# ============================================================
POSTS = [
    dict(
        by="ab", board="backend", type=1,
        title="点赞防重为什么要交给唯一索引，而不是「先查再写」？",
        tags=["MySQL", "并发", "幂等"],
        likes=["xiaoman", "qiyu", "lanke", "mumu"],
        collects=["qiyu", "mumu"],
        accept="lanke",
        content=r"""我们的点赞表是 `t_user_action(user_id, target_type, target_id, action_type)`，
上面对这四个字段建了唯一索引 `uk_user_target_action`。

先说结论：**幂等性交给数据库约束，而不是应用层 `if` 判断**。

先查再写的竞态窗口长这样：

```text
线程A: SELECT → 不存在
线程B: SELECT → 不存在      ← 竞态窗口就这两行之间
线程A: INSERT → 成功
线程B: INSERT → 成功        ← 重复记录，计数 +2
```

加锁能解决，但引入锁开销、分布式下还得上分布式锁。而唯一索引的「检查 + 插入」
在数据库内部是同一个原子操作，压根不存在窗口。

## 冲突了怎么办

捕获 `DuplicateKeyException` 之后**返回成功态**而不是报错：

| 场景 | 返回 | 计数 |
|---|---|---|
| 首次点赞 | active=true | +1 |
| 重复点赞（网络重试/连点） | active=true | 不变 |

用户意图（点赞）已经达成，此时弹"你已经点过赞了"体验很差。
真正的错误只留给"帖子不存在"这类。

> 另外一个细节：在 `@Transactional` 方法里捕获 DuplicateKeyException ，
> **MySQL 是安全的**（只让该条语句失败），PostgreSQL 会把整个事务标记为 aborted，
> 那种情况下必须用 savepoint。""",
        comments=[
            dict(by="lanke", text="补一个坑：如果唯一索引里含可空列，MySQL 允许重复 NULL，防重会静默失效。我们这里四个字段都是 NOT NULL，所以没问题。"),
            dict(by="mumu", reply_to="lanke", text="这个点很关键，我第一次写的时候就是用可空列踩的，查了半天。"),
            dict(by="xiaoman", text="前端这边配合的是乐观更新：先改 UI，请求失败再回滚。配合幂等返回，连点也不会闪。"),
        ],
    ),
    dict(
        by="ab", board="database", type=0,
        title="ngram 中文全文检索实测：同样的查询，扫描行数差了 9672 倍",
        tags=["MySQL", "全文检索", "性能"],
        likes=["xiaoman", "qiyu", "mumu", "lanke", "tangzhu"],
        collects=["qiyu"],
        content=r"""在 1 万行 `t_post`（关键词命中 105 行）上对比两种写法：

| 查询方式 | 访问方式 | 扫描行数 | 中位延迟 | P95 |
|---|---|---|---|---|
| `MATCH ... AGAINST` | fulltext | **1** | **0.84 ms** | 3.53 ms |
| `LIKE '%kw%'` | ALL（全表） | 9672 | 2.53 ms | 8.20 ms |

延迟只差 3 倍，但**扫描行数差 9672 倍** —— 后者才是结构性证据：`LIKE '%kw%'`
无法走索引，差距会随数据量线性放大；而全文索引只取命中的行。

## 三个实测出来的硬约束

1. **`ngram_token_size=2` 是硬约束**：索引最小单位是双字，所以搜**单个汉字**
   必然返回 0 条。解法是长度 < 2 时降级为 LIKE 扫描。
2. **BOOLEAN MODE 有语义漂移**：`+ - > < ( ) ~ * " @` 都有语法含义。
   用户搜 `-线程` 会被解释成"排除含线程的文档"→ 返回 0 条，看起来就像搜索坏了。
   参数化绑定挡的是**注入**，挡不住**语义漂移**，所以必须在拼 SQL 前清洗输入。
3. **清洗要替换成空格，不能直接删**：删掉会把「线程-并发」粘成「线程并发」，
   一个语料里不存在的词，反而搜不到。

> 选型结论：MVP 不上 Elasticsearch。多一个 1GB 堆的中间件，收益在这个量级不成立；
> 但检索独立成 Service，V0.2 换实现时 Controller 和前端一行都不用改。""",
        comments=[
            dict(by="qiyu", text="中文检索还有个隐性成本：ngram 的索引膨胀。双字粒度下索引体积大概是正文的 1.5~2 倍，写入放大要算进容量规划。"),
            dict(by="ab", reply_to="qiyu", text="对，这个我在容量估算里写了但没实测。等数据量上来我补一组索引体积的对比。"),
        ],
    ),
    dict(
        by="ab", board="database", type=0,
        title="加了索引还是慢？——降序索引每一列方向都要和 ORDER BY 对齐",
        tags=["MySQL", "索引", "性能"],
        likes=["lanke", "mumu", "qiyu"],
        collects=["lanke", "mumu"],
        content=r"""列表页排序是 `ORDER BY is_top DESC, create_time DESC`，
我加了索引 `(status, is_top, create_time DESC)` —— **结果 filesort 依然在**，
还是扫 10025 行。

排查过程（逐项 bisect，不要凭单一现象下结论）：

1. 怀疑 `deleted = 0` 不在索引里导致优化器不选新索引 → `ANALYZE TABLE` + 去掉该条件
   重试，优化器确实改选了新索引，**但 filesort 仍在**
2. `FORCE INDEX` 强制走新索引 → **filesort 依然在**
3. 真因：索引里 `is_top` 是 **ASC**，而 ORDER BY 是 `is_top DESC` —— 排序方向不匹配，
   MySQL 无法利用索引的有序性

MySQL 8.0 的降序索引是为「混合方向排序」设计的，要支持
`is_top DESC, create_time DESC`，两列必须**都显式 DESC**。

| | filesort | 读行数 | 耗时 |
|---|---|---|---|
| `(status, is_top, create_time DESC)` | 有 | 10025 | ~20.5 ms |
| `(status, is_top DESC, create_time DESC)` | **无** | **20** | **~0.76 ms** |

接口层：P95 **187 → 90 ms（-52%）**，吞吐 **71 → 134 req/s（+89%）**。

**教训：「加了索引」≠「索引生效」，必须 `EXPLAIN ANALYZE` 确认 Sort 节点真的消失。**""",
        comments=[
            dict(by="lanke", text="方向这一条太容易漏了。我习惯建完索引先 EXPLAIN ANALYZE 一遍，看到 Sort 节点才算完。"),
        ],
    ),
    dict(
        by="ab", board="backend", type=1,
        title="Redis 挂了计数会不会永久错？说说「可重建基线 + 实时增量」",
        tags=["Redis", "一致性", "架构"],
        likes=["xiaoman", "lanke", "tangzhu", "qiyu"],
        collects=["tangzhu", "lanke"],
        accept="tangzhu",
        content=r"""面试被问到"Redis 挂了怎么办"，如果回答"Redis 是权威值"，下一句一定是
"那它挂了你计数就永久错了"。所以我一开始就没这么设计。

## 模型：MySQL 提供**可重建的基线**，Redis 提供**实时增量**

- 明细表 `t_user_action` / `t_comment` 是**事实来源**，`COUNT(*)` 就是真值
- Redis 只是「基线 + 增量」的加速层，丢了不算数据丢失，只丢实时性
- 重建脚本从明细表反查真值，**全用 SET/UPDATE 覆盖，不用增量** → 幂等，跑几次结果都一样

## 两个容易写错的点

1. **写入顺序必须「先 MySQL 后 Redis」**。反过来写，中间窗口里的增量会加在旧值上，
   重建反而把偏差固化。
2. **计数落库用 `@TransactionalEventListener(phase = AFTER_COMMIT)`**，
   不是 `@EventListener`：主事务回滚时事件要丢弃，否则会出现"业务失败但计数已加"。

还有个 Lua 细节：key 不存在时 `DECR` 会从 0 减到 -1，而真值应该是「基线 - 1」。
所以用一段 Lua 把「EXISTS 判断 → SET 基线 → INCRBY」做成原子操作：

```lua
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SET', KEYS[1], ARGV[2])   -- 基线
end
return redis.call('INCRBY', KEYS[1], ARGV[1])
```

重建报告里带 `driftCount`（本次修正的偏差总量），可以直接量化一致性问题的规模。""",
        comments=[
            dict(by="tangzhu", text="这个拆分法比“快照 + 权威”好讲太多了：任何“丢了怎么办”的问题，答案都落回“跑一遍重建”。"),
            dict(by="mumu", reply_to="tangzhu", text="+1，而且 driftCount 让“最终一致”从口号变成可观测指标。"),
        ],
    ),
    dict(
        by="ab", board="devops", type=0,
        title="伪造一个请求头就能绕过登录锁定：IP 是安全参数，不是展示字段",
        tags=["安全", "nginx", "Docker"],
        likes=["lanke", "mumu", "xiaoman"],
        collects=["lanke"],
        content=r"""登录失败 5 次锁定 15 分钟，是按 `IP + 邮箱` 计数的。看着挺合理，
直到我意识到：**那个 IP 是从请求头里取的**。

```text
客户端发送  X-Forwarded-For: 1.2.3.4
nginx 追加 → "1.2.3.4, <真实IP>"          （$proxy_add_x_forwarded_for）
后端取第一段 → 1.2.3.4                     ← 伪造成功
```

后果：每次请求换一个伪造 IP，就换一个计数键，5 次锁定永远不会触发 ——
**防爆破形同不存在**。

## 修法（两层，缺一不可）

1. **nginx 覆盖而非追加**：`proxy_set_header X-Forwarded-For $remote_addr;`
   —— 本项目只有一层代理，覆盖后头里永远是真实客户端地址
2. **后端只信"可信代理写入的值"**：交给容器层，不手写解析

```yaml
server:
  forward-headers-strategy: native          # 启用 Tomcat RemoteIpValve
  tomcat:
    remoteip:
      remote-ip-header: X-Forwarded-For
      internal-proxies: '10\..*|192\.168\..*|172\.(1[6-9]|2\d|3[01])\..*|127\..*'
```

语义：**只有直连对端落在可信网段里时才采信 XFF**。公网直连的伪造头一律不认，
`getRemoteAddr()` 拿到的要么是真实 socket 地址、要么是可信代理写入的地址。

验证方式：带伪造头打一次限流接口，看 Redis 里的计数桶名 ——
如果桶名是真实网关地址而不是伪造值，说明伪造值被丢弃了。

> 顺手发现的同类问题：**浏览数按 IP 去重**也用同一个不可信来源，
> 刷浏览量成本为零。同一个修复覆盖它。""",
        comments=[
            dict(by="mumu", text="最反直觉的是“第一段才是客户端填的”，我一开始也以为应该取第一段……"),
            dict(by="lanke", reply_to="mumu", text="因为 XFF 是“从左往右追加”的：左边越靠前越接近客户端，也就是越不可信。"),
        ],
    ),
    dict(
        by="xiaoman", board="frontend", type=0,
        title="搜索高亮坚决不用 v-html：纯文本切片 + Vue 插值",
        tags=["前端安全", "XSS", "Vue"],
        likes=["ab", "qiyu", "mumu"],
        collects=["ab"],
        content=r"""搜到的关键词要标红，最直觉的写法是拼字符串：

```js
`${before}<mark>${kw}</mark>${after}`   // 然后 v-html
```

关键词来自用户输入，这一步等于把用户输入交给 HTML 解析器。要安全就得对
每个片段正确转义，**漏一处就是 XSS**。

我改成完全不产生 HTML 字符串：切分成片段数组，交给模板渲染：

```vue
<span v-for="(seg, i) in segments" :key="i">
  <mark v-if="seg.hit">{{ seg.text }}</mark>
  <template v-else>{{ seg.text }}</template>
</span>
```

Vue 的插值天然转义，安全性由框架保证，而不是靠我记得转义。

配套两条：

- **切分用 `indexOf` 不用正则**：关键词可能含正则元字符（`(`、`*`、`[`），
  拼进 `new RegExp` 要么报错要么语义跑偏
- **大小写不敏感但保留原文大小写**：命中的片段按原文本输出，保证检索结果和原文一致

这条约定有测试钉着：验收脚本会**先剥离注释**再断言源码里没有 `v-html`/`innerHTML`
（第一版没剥注释，匹配到了注释里"不使用 v-html"的说明文字，假阳性）。""",
        comments=[
            dict(by="ab", text="“先剥离注释再断言”这条太真实了，我扫代码做结构断言也踩过一模一样的假阳性。"),
        ],
    ),
    dict(
        by="xiaoman", board="frontend", type=0,
        title="设计令牌为什么要拆成两个强调色：一组 WCAG 对比度算出来的结论",
        tags=["设计系统", "无障碍", "CSS"],
        likes=["tangzhu", "ab"],
        collects=["tangzhu"],
        content=r"""原来只有一个强调色 `#2563eb`，后来为了让链接更亮，我把它调成了 `#3b82f6`。
结果派生出一个新问题：**白字压在 `#3b82f6` 上只有 3.68:1，达不到 AA(4.5:1)**。

同一个色值不可能同时满足两件事：

- 当**文字/描边**放在深色底上 → 要够亮
- 当**实心按钮底**衬托白字 → 要够暗

所以拆成两个令牌，并用 WCAG 公式逐组校验：

| 令牌 | 值 | 用途 | 对比度 |
|---|---|---|---|
| `--color-accent` | `#3b82f6` | 链接、图标、描边 | 4.67:1 ✓ |
| `--color-accent-solid` | `#2563eb` | 实心按钮底 | 5.17:1 ✓ |

另外两条硬约束：

- **组件里禁止写死色值**（含 `rgba()`）。旧版散落 44 处，导致换色后
  "新蓝的文字配旧蓝的底"——色相不一致是最廉价感的来源
- **`border-color: var(--color-accent)` 有两种语义**，批量替换前必须分类：
  ①实心按钮的边（应跟底色）②描边式组件（应跟文字）。一刀切会改坏后者，
  我第一次脚本就误伤了 6 处""",
        comments=[
            dict(by="tangzhu", text="“用公式算而不是靠眼睛”是这条的关键。审美可以吵，对比度不能吵。"),
        ],
    ),
    dict(
        by="lanke", board="devops", type=0,
        title="四容器一键起：为什么 MySQL 和 Redis 坚决不暴露宿主机端口",
        tags=["Docker", "部署", "Compose"],
        likes=["ab", "tangzhu", "mumu"],
        collects=["ab"],
        content=r"""`docker compose up -d --build` 一条命令起四个容器：mysql / redis / backend / frontend。
只对宿主机暴露**一个**端口（前端 nginx）。

## 三条刻意的设计

1. **MySQL / Redis 不映射端口**。它们只活在 compose 内部网络里。
   好处：不会和本地已装的 MySQL(3306)、已有的 redis 容器(6379)抢端口 ——
   本地开发用的是宿主机服务，如果 compose 也去绑 3306 就直接冲突起不来。
   要进去调试用 `docker compose exec mysql mysql -u...` 即可。
2. **前端 nginx 反代 `/api` 与 `/uploads`**：前后端同源，**零 CORS**，
   也避免了在前端注入后端地址（本地 dev 由 vite proxy 承担同一角色，行为一致）。
3. **用 `service_healthy` 而不是裸 `depends_on`**：后者只保证"容器启动了"，
   不保证"服务能用了"。Spring Boot 比 MySQL 启动快得多，不等它 ready，
   后端首次连接就会失败 —— Flyway 迁移直接报错退出。

## 两个容器内的坑

- **时区**：alpine 基础镜像默认 UTC，与 JDBC 的 `serverTimezone=Asia/Shanghai`
  不一致会导致时间整体差 8 小时。装 tzdata → 设时区 → 删 tzdata（不留在镜像里）
- **JVM 堆**：容器里默认按宿主机物理内存算堆，容易被 OOM Kill。
  必须用 `-XX:MaxRAMPercentage` 按 cgroup 限制计算（小内存 VPS 上还要再往下压）""",
        comments=[
            dict(by="mumu", text="service_healthy 这条我吃过亏：口罩期间本地起 compose，后端一直起不来，日志里全是连接被拒。"),
        ],
    ),
    dict(
        by="lanke", board="backend", type=0,
        title="上传头像的三道防线：扩展名白名单 + 魔数校验 + 服务端重命名",
        tags=["安全", "文件上传"],
        likes=["ab", "xiaoman", "qiyu"],
        collects=["xiaoman", "qiyu"],
        content=r"""上传接口是 Web 漏洞高发区，这里有三道独立防线：

1. **扩展名白名单**（`jpg/jpeg/png/webp`）。不用黑名单 —— 黑名单永远列不全，
   `.jspx`、`.phtml` 这类变体很容易漏。
   注意取的是**最后一段**扩展名：`a.jpg.jsp` 会被识别成 `jsp` 从而被拒。
2. **魔数校验**：不信任 `Content-Type` 与扩展名（客户端可伪造），
   读文件头字节判断真实类型（JPEG `FF D8 FF` / PNG `89 50 4E 47` / WebP `RIFF....WEBP`）。
   把 `shell.jsp` 改名成 `avatar.png` 骗不过这一关。
3. **文件名完全由服务端生成**：`avatars/{userId}/{UUID}.{ext}`，
   原始文件名只用来取扩展名，取完立刻丢弃 —— 从根上杜绝路径穿越，
   比"过滤 `../`"彻底得多。

再加一道兜底：拼接后校验 `target.startsWith(uploadRoot)`，
万一前面逻辑被绕过也跑不出根目录。

> 为什么不把上传目录和代码目录混在一起？因为静态资源目录一旦能写脚本，
> 就只剩"服务器配不配置执行权限"这一层防护了。存储根目录与代码目录分离是底线。""",
        comments=[
            dict(by="qiyu", text="补一条：图片最好再按内容重编码一次，能顺手干掉藏在 EXIF 里的载荷。个人项目可以不做，但要能说出来。"),
        ],
    ),
    dict(
        by="qiyu", board="database", type=1,
        title="BOOLEAN MODE 的语义漂移：用户搜「-线程」为什么什么都搜不到？",
        tags=["全文检索", "MySQL"],
        likes=["ab", "mumu"],
        collects=["mumu"],
        accept="ab",
        content=r"""实测发现一个反直觉行为：搜「线程」有结果，搜「-线程」结果 0 条。

原因是 BOOLEAN MODE 里 `-` 表示「**排除**含该词的文档」：

| 输入 | 解析结果 | 命中 |
|---|---|---|
| `虚拟线程` | 普通检索 | 命中 |
| `-线程` | 排除含"线程"的文档 | **0 条** |
| `+线程` | 必须包含 | 比默认更严格 |
| 纯 `+ - * " ( ) ~ @` | 无效 token，被忽略 | 0 条，**不报错** |

关键区分：

- **参数化绑定（`#{}`）挡住的是注入** —— `' OR 1=1 --` 这类确实进不来
- **BOOLEAN 操作符造成的是语义漂移** —— 参数化对它毫无作用，必须自己清洗输入

清洗的细节：把操作符**替换成空格**而不是删除。删掉会把「线程-并发」
粘成「线程并发」（语料里不存在的词），反而更搜不到。

还有一个已知取舍：剥操作符会让 `C++` 变成 `C`。两害相权取其轻 ——
选"结果符合直觉"的那个，并且在注释里把取舍写清楚，而不是假装没这回事。""",
        comments=[
            dict(by="ab", text="这个我必须承认：一开始我以为参数化就把搜索安全做完了。后来才发现“注入”和“语义”是两件事，只有后者需要清洗。"),
        ],
    ),
    dict(
        by="tangzhu", board="career", type=0,
        title="把项目写进简历时，我删掉了所有形容词",
        tags=["求职", "简历", "面试"],
        likes=["ab", "xiaoman", "qiyu", "lanke", "mumu"],
        collects=["ab", "xiaoman", "qiyu"],
        content=r"""简历上的项目描述，删掉形容词之后剩下来的才是真实信息量。

对比一下：

- ❌「使用 Redis 大幅提升了系统性能，具备高并发处理能力」
- ✅「列表页 P95 187ms → 90ms：`EXPLAIN ANALYZE` 定位到 filesort，
  索引补上 `is_top DESC` 后读行数 10025 → 20；

前者面试官只能反问"具体多少"，后者直接给了一个可以追问 10 分钟的技术点。

我的三条自查：

1. **每个数字都要能被追问到来源**：压测怎么压的、样本多少、有没有预热、
   是不是单机自压（客户端和后端同机争 CPU 时绝对数值没有意义）
2. **写"取舍"而不是"优点"**：为什么不上 ES / 不上 MQ / 不上微服务 ——
   说清"量级不匹配"比堆技术栈更像工程师
3. **失败与修正比成功更有说服力**：例如"加了索引还是慢"的排查过程（逐项 bisect），
   比"我优化了性能"更能证明会做事

> 一句提醒：压测数据一定要标注环境（单机自压只能同环境横向比较），
> 被问到时能主动说明局限，反而加分。""",
        comments=[
            dict(by="ab", text="第 3 条深有同感。我把“降序索引方向不匹配”的排查过程写进了项目文档，面试时反而成了最好讲的一段。"),
            dict(by="mumu", reply_to="ab", text="这种“翻车现场”确实是真做过才有的细节。"),
        ],
    ),
    dict(
        by="mumu", board="backend", type=1,
        title="两级评论为什么不用递归？聊聊 parentId 归一化到根评论",
        tags=["设计", "评论系统"],
        likes=["ab", "xiaoman", "lanke"],
        collects=["ab"],
        accept="ab",
        content=r"""做评论树时我选了**固定两级**，而不是无限嵌套。理由有三条：

1. **查询成本**：无限嵌套要取"第 5 层的第 3 条回复"就会退化成多次查询；
   固定两级可以一次 `SELECT` 取回该帖全部评论，应用层按 `parentId` 分组，
   一次查询 + O(n)，没有递归、没有 N+1。
2. **前端渲染**：不需要递归组件，列表结构天然两层。
3. **阅读体验**：真实对话是线性的，深于两级的对话反而更难读。

关键是**归一化**：

```text
A（一级）
├─ B 回复 A
└─ C 回复 B      ← C 的 parent_id 存 A（根），reply_user_id 存 B 的作者
```

这样树永远是两层，但"回复谁"的信息没丢 —— `reply_user_id` 单独存。

另外两个边界处理：

- **跨帖回复必须拦掉**：`parentId` 指向的评论不属于当前帖子时直接报错，
  否则能把别人的评论"嫁接"到自己的帖子里
- **根评论被删但回复还在**：把回复提升为一级（`parentId` 置 0），
  不静默吞内容 —— 评论作者要为自己的内容负责""",
        comments=[
            dict(by="ab", text="归一化到根这一手很关键：既保住了“两级”的简单，又没丢“回复谁”的语义。我们项目也是这么做的。"),
            dict(by="lanke", text="删根评论时“提升回复为一级”这个决定挺有品味，比静默级联删除体验好得多。"),
        ],
    ),
    dict(
        by="tangzhu", board="general", type=0,
        title="一个人做项目，我砍掉了哪些“看起来很专业”的东西",
        tags=["独立开发", "架构", "取舍"],
        likes=["ab", "lanke", "mumu", "qiyu"],
        collects=["lanke"],
        content=r"""单人项目的最大风险不是技术难，而是**范围蔓延**。这是我砍掉的清单和理由：

| 砍掉 | 理由 |
|---|---|
| 微服务 / Spring Cloud | 单机部署 + 零运维预算，拆开只有成本没有收益 |
| MQ（RocketMQ / Kafka） | 站内通知用 Spring Event 就够，量级差着三个数量级 |
| Elasticsearch（MVP 阶段） | 多一个 1GB 堆的中间件；ngram 已够用，留到 V0.2 做"优化前后对比" |
| 计数批量缓冲 | 没有并发压力，过早优化；先朴素实现，用真实压测数据再决定 |
| 富文本编辑器 | Markdown + 前端渲染更安全（消毒链路可控），且程序员社区本就习惯 Markdown |

**砍单原则**：凡是"现在不做，将来还能补，且不影响核心闭环"的，一律延后。
但要留好接口 —— 比如检索独立成 Service，将来换 ES 时 Controller 和前端一行都不用改；
这就是"延迟决策"而不是"技术债"。

反过来，**绝不砍**的是核心闭环：注册登录、发帖看帖、评论、点赞防重、
计数重建、全文检索、一键部署。""",
        comments=[
            dict(by="lanke", text="“留好接口”这句是关键区别。同样是先不做，留了替换点叫延迟决策，硬编码进去才叫债。"),
        ],
    ),
    dict(
        by="xiaoman", board="frontend", type=1,
        title="前端字体加载失败为什么会让整版排版白做？",
        tags=["前端工程", "字体", "性能"],
        likes=["ab", "tangzhu"],
        collects=["tangzhu"],
        accept="tangzhu",
        content=r"""一开始我在 `index.html` 里引了 Google Fonts，`preconnect` 也加了。
后来发现一个残酷的事实：**那个域名在国内不可达** —— 字体请求超时后，
浏览器会**静默回退**到系统字体，页面看起来"也还行"，但设计时按那套字体调的
行高、字重、字距全部落空。

更糟的是 `preconnect` 指向不可达域名，白白拖慢首屏。

改法是自托管（`@fontsource`，与站点同源，不受外部网络影响）。两个细节：

- **必须用 `latin-*` 入口**（`@fontsource/inter/latin-400.css`）。
  裸 `400.css` 会声明全部子集（cyrillic / greek / vietnamese…），
  实测 dist 因此多出 40 个字体文件、膨胀 3.1 MB —— 中文本来就不在 Inter 里，
  走系统字体栈即可（中文 webfont 动辄数 MB，自托管不划算）
- **必须在设计令牌之前引入**：`@font-face` 要先声明，否则会有字体闪烁（FOUT）

> 教训：**排在"设计"之前的是"资源能不能到达"**。字体、CDN、埋点，
> 只要一个不可达，前面所有设计工作都是自我感动。""",
        comments=[
            dict(by="tangzhu", text="这个坑太经典了：本地看着好好的（有缓存/代理），线上全是系统字体，还以为是浏览器差异。"),
        ],
    ),
]

# 关注关系：(关注者, 被关注者)
FOLLOWS = [
    ("xiaoman", "ab"), ("mumu", "ab"), ("lanke", "ab"), ("qiyu", "ab"),
    ("ab", "lanke"), ("ab", "qiyu"), ("ab", "tangzhu"),
    ("xiaoman", "qiyu"), ("mumu", "xiaoman"), ("tangzhu", "xiaoman"),
    ("lanke", "tangzhu"), ("qiyu", "mumu"),
]


class ApiError(Exception):
    pass


# 绕开系统代理：否则 localhost 请求会被代理劫持（本机实测过返回 502）
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(base, method, path, body=None, token=None, timeout=25):
    url = base + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with _opener.open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return json.loads(raw)
        except Exception:
            raise ApiError("%s %s -> HTTP %s: %s" % (method, path, e.code, raw[:200]))
    except Exception as e:                                   # 连接层错误
        raise ApiError("%s %s -> %s" % (method, path, e))


def api(base, method, path, body=None, token=None):
    """成功返回 data；code != 0 抛 ApiError（附带业务码，便于分支处理）"""
    b = call(base, method, path, body, token)
    if not isinstance(b, dict) or "code" not in b:
        raise ApiError("%s %s -> 非预期响应：%s" % (method, path, str(b)[:200]))
    if b["code"] != 0:
        raise ApiError("%s %s -> code=%s msg=%s" % (method, path, b["code"], b.get("message")))
    return b.get("data")


def main():
    ap = argparse.ArgumentParser(description="Codelkup 演示数据种子（幂等）")
    ap.add_argument("--base", default="http://127.0.0.1:8090/api", help="API 根地址（经 nginx 同源反代）")
    ap.add_argument("--password", default="Codelkup@Demo2026", help="演示账号统一密码")
    ap.add_argument("--email-domain", default="codelkup.dev", help="演示账号邮箱域名")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    print("目标：%s" % base)
    try:
        h = api(base, "GET", "/system/health")
        print("健康检查：mysql=%s redis=%s" % (h.get("mysql"), h.get("redis")))
    except ApiError as e:
        print("✗ 连不上或后端未就绪：%s" % e)
        return 1

    # ---------- 1. 账号 ----------
    #
    # ⚠️ **登录优先、注册兜底**（本地复验发现的原实现缺陷）：
    #    原实现对每个账号**先打 /auth/register**，只有拿到 40901（已存在）才 fallback 登录。
    #    而注册限流是**按 IP 计次**的（RATE_LIMIT_REGISTER_PER_HOUR），
    #    于是"复跑一次 seed"要花掉 6 次额度 —— 阈值 10 下**第二次跑必撞 42901**
    #    （实测 Redis 桶 codelkup:rate:register:<ip> 累积到 11）。
    #    这与脚本文档自称的"幂等、可反复执行"直接矛盾，也让
    #    LAUNCH-CHECKLIST 的"演示数据被污染时 seed 重灌"这条路径失效。
    #    改为：**先登录**（账号已存在 → 成功，零注册开销）；登录失败才注册。
    tokens, ids, created_users = {}, {}, 0
    for key, nickname, tech, bio in USERS:
        email = "%s@%s" % (key, args.email_domain)
        try:
            data = api(base, "POST", "/auth/login", {"email": email, "password": args.password})
            action = "已存在→登录"
        except ApiError as e:
            # 登录失败才尝试注册。40104 = 邮箱或密码错误（后端**有意不区分**
            # "账号不存在"与"密码错"，防账号枚举）；42902 = 登录失败次数过多。
            if "42902" in str(e):
                print("✗ 登录 %s 失败次数过多（42902）：清掉该账号/IP 的登录失败桶或等窗口过。" % email)
                return 1
            if "40104" not in str(e):
                print("✗ 登录 %s 失败：%s" % (email, e))
                return 1
            try:
                data = api(base, "POST", "/auth/register",
                           {"email": email, "password": args.password, "nickname": nickname})
                created_users += 1
                action = "新建"
            except ApiError as e2:
                # 两种"环境没准备好"的失败给动作化提示（真机复验发现：这两种都会在首次部署时撞上）
                if "40304" in str(e2):
                    print("✗ 注册 %s 被拒：注册开关处于关闭状态（40304）" % email)
                    print("  首次灌数据需要临时开放注册：.env 里 REGISTRATION_ENABLED=true → `docker compose up -d` → 重跑本脚本；")
                    print("  灌完再改回 false → `docker compose up -d backend`（必须 up -d，restart 不重新注入环境变量；已有账号的登录不受影响）。")
                    return 1
                if "42901" in str(e2):
                    print("✗ 注册 %s 被限流（42901）：注册限流按 IP 计，而本脚本要建 %d 个演示账号。" % (email, len(USERS)))
                    print("  处置：.env 里设 RATE_LIMIT_REGISTER_PER_HOUR>=%d → `docker compose up -d`（或清掉该 IP 的桶/等窗口过）→ 重跑本脚本。" % (len(USERS) + 2))
                    return 1
                if "40901" in str(e2):
                    print("✗ 账号 %s 已存在，但用 --password 登录失败：密码与库中不一致？" % email)
                    return 1
                print("✗ 注册 %s 失败：%s" % (email, e2))
                return 1
        tokens[key] = data["accessToken"]
        ids[key] = data["user"]["id"]
        # 完善资料（技术标签 / 简介）
        api(base, "PUT", "/users/me", {"techTags": tech, "bio": bio}, tokens[key])
        print("  %-8s %s（id=%s）" % (nickname, action, ids[key]))

    # ---------- 2. 关注关系 ----------
    follow_cnt = 0
    for follower, followee in FOLLOWS:
        try:
            api(base, "POST", "/users/%d/follow" % ids[followee], None, tokens[follower])
            follow_cnt += 1
        except ApiError:
            pass                                             # 重复关注等，忽略
    print("关注关系：%d 条" % follow_cnt)

    # ---------- 3. 帖子 + 互动 ----------
    boards = {b["slug"]: b["id"] for b in api(base, "GET", "/boards")}
    new_posts = skipped = 0
    stat = dict(comment=0, like=0, collect=0, accept=0)

    for p in POSTS:
        author, token = p["by"], tokens[p["by"]]
        # 幂等：该作者已有同名帖子就跳过（含互动，避免重复计数）
        existing = api(base, "GET", "/posts?authorId=%d&size=50" % ids[author])
        if any(r["title"] == p["title"] for r in existing["records"]):
            skipped += 1
            continue

        detail = api(base, "POST", "/posts", {
            "title": p["title"],
            "content": p["content"],
            "boardId": boards[p["board"]],
            "type": p.get("type", 0),
            "tags": p.get("tags", []),
        }, token)
        post_id = detail["id"]
        new_posts += 1

        # 评论 / 回复（记录 key -> commentId，供二级回复与采纳使用）
        cmt_ids = {}
        for c in p.get("comments", []):
            body = {"content": c["text"]}
            if c.get("reply_to"):
                body["parentId"] = cmt_ids.get(c["reply_to"])
            try:
                cm = api(base, "POST", "/posts/%d/comments" % post_id, body, tokens[c["by"]])
                cmt_ids.setdefault(c["by"], cm["id"])
                stat["comment"] += 1
            except ApiError as e:
                print("  ! 评论失败（post=%s）：%s" % (post_id, e))

        # 采纳
        if p.get("accept") and cmt_ids.get(p["accept"]):
            try:
                api(base, "POST", "/posts/%d/accept/%d" % (post_id, cmt_ids[p["accept"]]), None, token)
                stat["accept"] += 1
            except ApiError as e:
                print("  ! 采纳失败（post=%s）：%s" % (post_id, e))

        # 点赞 / 收藏（跳过作者自己，让计数有区分度）
        for liker in p.get("likes", []):
            if liker == author:
                continue
            try:
                api(base, "POST", "/posts/%d/like" % post_id, None, tokens[liker])
                stat["like"] += 1
            except ApiError:
                pass
        for collector in p.get("collects", []):
            if collector == author:
                continue
            try:
                api(base, "POST", "/posts/%d/collect" % post_id, None, tokens[collector])
                stat["collect"] += 1
            except ApiError:
                pass

    # ---------- 4. 汇总 ----------
    print("\n" + "=" * 62)
    print("演示数据就绪")
    print("  账号：新建 %d / 复用 %d" % (created_users, len(USERS) - created_users))
    print("  帖子：新建 %d / 跳过（已存在） %d" % (new_posts, skipped))
    print("  互动：评论 %d · 点赞 %d · 收藏 %d · 采纳 %d" % (
        stat["comment"], stat["like"], stat["collect"], stat["accept"]))
    print("  关注：%d 条（被关注方会收到通知，通知中心因此有内容）" % follow_cnt)
    print("-" * 62)
    print("演示账号（密码均为 %s）：" % args.password)
    for key, nickname, _, _ in USERS:
        print("  %-16s %s" % ("%s@%s" % (key, args.email_domain), nickname))
    print("=" * 62)
    return 0


if __name__ == "__main__":
    sys.exit(main())