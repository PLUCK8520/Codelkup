# Codelkup 上线部署手册（公网演示环境）

> 目标：把项目变成一个**面试官点开就能用**的链接。
> 本手册配套 `deploy/` 下的三个脚本：`deploy.sh`（一键上线）、`seed-demo.py`（演示数据）、`verify-deploy.py`（上线自检）。

---

## 1. 先选上线形态（这一步决定了后面怎么做）

| 方案 | 适用 | 域名/HTTPS | 备案 | 成本 |
|---|---|---|---|---|
| **A. 非内地服务器（香港/海外）+ IP 直连** | 想零成本先跑起来 | ❌ 只有 `http://IP:端口` | **不需要** | 最低 |
| **B. 非内地服务器 + 域名 + HTTPS**（推荐长期） | 想给简历放 `https://` 链接 | ✅ Caddy 自动签发 | **不需要** | 域名 ¥10~60/年 + VPS |
| C. 内地服务器 | — | — | ⛔ **本项目走不通**（见下） | — |

### ⛔ 为什么内地服务器这条对本项目是走不通的（两条官方规则）

1. **论坛类网站无法办理 ICP 备案**。阿里云备案帮助中心原文：开办论坛网站所需的
   《BBS 前置审批文件》**现已停止办理**，因此**论坛网站已无法办理 ICP 备案**
   （依据国务院令取消互联网电子公告服务专项审批）。
   Codelkup 就是论坛 —— 除非改产品定位并承担合规风险，否则备案这一关过不去。
2. **不绑域名、直接用 IP 对外提供服务，同样需要备案**。阿里云原文：
   "托管于中国内地服务器上的网站，如果网站没有绑定域名，直接通过 IP 地址对外提供服务
   **也需要进行 ICP 备案**"，且其备案系统**只支持域名备案、不支持 IP 备案**，
   "未备案成功前，均不允许开通网站访问"。腾讯云亦注明"不论设置哪个端口都能被监测到"。

**结论**：内地服务器只适合"纯本地/内网使用"，公网演示请走非内地地域。

### ⚠️ 非内地地域的代价：跨境线路质量

腾讯云官方地域文档：中国港澳台及其他国家地域的轻量服务器，
**从中国内地跨境访问时受运营商线路影响，可能出现较大延迟和丢包**。
阿里云对其"国际型"规格族（BGP_NCO = 非中国优化）也明确说明
"仅适用于不需要对中国大陆用户提供服务的应用场景"。

所以买香港机器时优先挑**面向大陆优化的线路**，或接受"能打开、但不够快"的现实
（演示站的流量极小，几 MB 首屏 + 图片，通常可以接受）。

---

## 2. 服务器规格

| 规格 | 可行性 | 说明 |
|---|---|---|
| 2C4G | ✅ 推荐 | 四个容器（MySQL 8 + Redis + JVM + nginx）从容运行 |
| 2C2G | ⚠️ 最低可行 | **必须**把 `JVM_MAX_RAM_PERCENTAGE` 压到 35~40，否则 JVM 会和 MySQL 抢内存被 OOM Kill |
| 1C1G | ❌ | MySQL 8 单独就要 400MB 左右，会持续 OOM |

磁盘：20GB 起（镜像约 1.5GB，MySQL 数据 + 上传文件按需）。

---

## 3. 五步上线

```bash
# ① 服务器上装 Docker（Ubuntu/Debian）
curl -fsSL https://get.docker.com | sh && systemctl enable --now docker

# ② 拿到代码（任选：git clone 你的仓库 / 本机 scp 上传整个目录）
git clone <你的仓库地址> codelkup && cd codelkup

# ③ 配置密钥（两个密码 + JWT 用 openssl 生成强随机值）
cp deploy/.env.production.example .env
openssl rand -base64 24   # → 填 MYSQL_ROOT_PASSWORD / DB_PASSWORD
openssl rand -base64 48   # → 填 JWT_SECRET
vi .env                   # 同时按内存填 JVM_MAX_RAM_PERCENTAGE、按方案填 WEB_PORT/CORS

# ④ 一键上线 + 灌演示数据 + 自检
chmod +x deploy/deploy.sh
./deploy/deploy.sh --seed --verify
```

`deploy.sh` 会在动手前拦掉三类问题：Docker 没装/没启动、`.env` 还是模板占位值、`JWT_SECRET` 不足 32 字节。启动后会轮询健康检查，超时自动打印后端日志尾部。

```bash
# ⑤ 防火墙只放必要端口
ufw allow 22/tcp && ufw allow 80/tcp && ufw allow 8090/tcp && ufw enable
```

---

## 4. 方案 B：加 HTTPS（Caddy，比 certbot 简单一个数量级）

域名解析到服务器 IP 后，在服务器上装 Caddy：

```bash
apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
echo 'deb [signed-by=/usr/share/keyrings/caddy-stable-archive-keyring.gpg] https://dl.cloudsmith.io/public/caddy/stable/deb/debian any-version main' \
  > /etc/apt/sources.list.d/caddy-stable.list
apt update && apt install -y caddy
```

`/etc/caddy/Caddyfile`（自动申请并续期证书，无需额外操作）：

```caddyfile
demo.example.com {
    encode gzip
    reverse_proxy 127.0.0.1:8090
}
```

```bash
systemctl reload caddy
```

于是：公网 `https://demo.example.com` → Caddy(443) → 前端 nginx(8090) → 后端(容器内 8085)。
**别忘了**把 `.env` 里的 `CORS_ALLOWED_ORIGINS` 改成 `https://demo.example.com` 并重启后端。

---

## 5. 演示账号与"给面试官的入口"

`seed-demo.py` 会创建 6 个演示账号（统一密码由 `--password` 指定，默认 `Codelkup@Demo2026`）：

| 邮箱 | 昵称 | 技术标签 |
|---|---|---|
| `ab@codelkup.dev` | 阿布 | Java / MySQL / Redis |
| `xiaoman@codelkup.dev` | 小满 | TypeScript / Vue / 工程化 |
| `qiyu@codelkup.dev` | 齐玉 | 算法 / NLP / Python |
| `lanke@codelkup.dev` | 蓝可 | Docker / K8s / 监控 |
| `tangzhu@codelkup.dev` | 汤煮 | 全栈 / 独立开发 |
| `mumu@codelkup.dev` | 木木 | 计算机基础 / 操作系统 |

> 演示帖子的内容**就是本项目的技术决策复盘**（索引方向、计数一致性、ngram 检索、XFF 信任边界、XSS 防线……）。
> 面试官点进来读到的每一条，都是可以顺着往下问 10 分钟的素材 —— 这比放"测试帖子 1/2/3"有用得多。

**不建议**在演示站放管理员账号密码：公网谁都能删帖/置顶。需要演示管理功能时，自己临时提权：

```bash
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" codelkup \
  -e "UPDATE t_user SET role=2 WHERE email='ab@codelkup.dev';"
# ⚠️ role 写在用户表里，JWT 只带 uid —— 改完必须**重新登录**才生效
```

放上简历前建议做的两件事：
1. 用 `verify-deploy.py` 跑一遍（16 项，含"头像经 nginx 可读""中文检索命中""401 语义"）
2. 自己用手机 4G 网络打开一次 —— 确认不是"只有本机能打开"（防火墙/安全组最常见的坑）

---

## 6. 日常运维

```bash
docker compose ps                       # 四个容器都该是 healthy
docker compose logs -f backend          # 后端日志（prod profile 不打印 SQL）
docker compose restart backend          # 重启后端（改 .env 后需要）
docker compose up -d --build            # 拉新代码后重建
docker compose down                     # 停止（数据保留在 volume，不会丢）

# 备份数据库（建议加进 crontab，每天一次）
docker compose exec -T mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" codelkup | gzip > ~/backup-$(date +%F).sql.gz

# 计数对账（Redis 与明细表偏差）
# 管理员登录后调用重建接口（会返回 driftCount）：POST /api/admin/counters/rebuild
```

---

## 7. 公网暴露面清单（上线前逐项确认）

| 项 | 现状 | 说明 |
|---|---|---|
| 对外端口 | **只有前端一个**（默认 80/8090） | MySQL/Redis 不映射宿主机端口，只活在 compose 内网 |
| SSH | 22 | 用密钥登录、关掉密码登录（`PasswordAuthentication no`） |
| 密钥 | `.env` 中，不入库 | `deploy.sh` 会拒绝模板占位值 |
| JWT | 生产必须外部注入，代码无默认值 | dev 专用占位密钥只存在于本地未跟踪文件 |
| 登录防爆破 | 5 次失败锁 15 分钟（IP + 邮箱维度） | IP 取自可信代理链（Tomcat RemoteIpValve），伪造 `X-Forwarded-For` 无效 |
| 邮箱查重限流 | 30 次 / 10 分钟 / IP | 防止被用来批量枚举站内邮箱 |
| 上传 | 扩展名白名单 + 魔术字节校验 + 服务端 UUID 重命名 | 头像上限 2MB，落数据卷 |
| API 文档 | 有意公开（`/v3/api-docs`、`/swagger-ui/`） | 演示站加分项；如不需要，删掉 nginx.conf 里那两条 location 即可 |
| 数据 | **只有自造的演示数据** | 不要往里放任何真实用户数据 |

---

## 8. 已知限制（被问到就直说，反而是加分项）

- **单机单点**：没有多副本、没有负载均衡 —— 个人项目的量级不需要，也不该假装需要
- **无 CDN / 对象存储**：头像存本地数据卷；要扩容就换 OSS（上传接口只需替换 `FileStorageService`）
- **压测数据是单机自压**：客户端与后端同机争 CPU，绝对数值不代表生产，只做同环境横向对比
- **计数是最终一致**：Redis 增量 + MySQL 可重建基线，偏差由重建脚本量化修正（`driftCount`）
- **全量重建随数据量变慢**：1 万帖约 20~40 秒（逐帖反查明细表），属管理员运维操作，不在请求路径上