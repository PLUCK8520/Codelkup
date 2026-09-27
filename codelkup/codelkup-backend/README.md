# Codelkup — 本地开发环境说明

> 最后核实：2026-09-24
> 详细设计见 `../../docs/DEV-DOC-codelkup.md`

## 一、依赖服务

| 服务 | 地址 | 凭据 | 启动方式 |
|---|---|---|---|
| MySQL | `localhost:3306` | 库 `codelkup` / 账号 `codelkup` | 本机已安装（8.0.37） |
| Redis | `localhost:6379` | 无密码 | `docker start codelkup-redis` |

### Redis 首次创建

```bash
docker run -d --name codelkup-redis -p 6379:6379 \
  --restart unless-stopped redis:7-alpine
```

### 验证依赖

```bash
docker exec codelkup-redis redis-cli ping        # 期望 PONG

"D:/MySQL/MySQL Server 8.0/bin/mysql.exe" -u codelkup -p -e "SELECT 1" codelkup
```

## 二、启动应用

```bash
# 方式 1：脚本
start-backend.bat

# 方式 2：手动
mvn clean package -DskipTests
java -jar target/codelkup-backend-0.0.1-SNAPSHOT.jar --server.port=8085
```

### ⚠️ 端口说明（重要）

本机端口占用情况（实测）：

| 端口 | 占用者 | 说明 |
|---|---|---|
| 8080~8083 | `vmnat.exe` (VMware NAT) | 系统服务，**整段占用** |
| 65336 | WorkBuddy | 且会注入环境变量 `SERVER__PORT=65336` |
| **8085** | **本项目使用** | 空闲 |

**注意**：WorkBuddy 注入的 `SERVER__PORT=65336` 优先级高于 `application.yml`，
会覆盖配置导致端口冲突。启动时**必须**剔除该变量：

```bash
# Git Bash
env -u SERVER__PORT mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8085
```

## 三、验证

```bash
# 骨架自检（同时探测 MySQL + Redis）
curl --noproxy '*' http://127.0.0.1:8085/api/system/health

# 期望输出
# {"code":0,"message":"success","data":{"application":"codelkup","mysql":"UP","redis":"UP"}}
```

其他端点：

| 端点 | 用途 |
|---|---|
| `/swagger-ui/index.html` | 接口文档（Swagger UI） |
| `/v3/api-docs` | OpenAPI JSON |
| `/actuator/health` | Actuator 健康检查 |
| `/actuator/prometheus` | 指标（M3-10）。⚠️ **只对回环 + RFC1918 来源开放**（`SecurityConfig`），公网经 nginx 一律 404；自定义指标与打点口径见 DEV-DOC §10.7 |

> 💡 指标验收脚本：`python verify-v03-metrics.py`（附着型，需后端已在跑）——
> 除了断言端点与四组自定义指标，还会用 `X-Forwarded-For` 冒充公网/容器两种来源验闸门。
> 想跑得快就用 `python serve-backend.py --codelkup.score.initial-delay-ms=2000 --codelkup.sse.heartbeat-ms=2000` 启动后端。

> 💡 **Windows 提示**：若系统配置了代理，curl 访问 localhost 会被代理劫持返回 502。
> 加 `--noproxy '*'` 绕过。

## 四、数据库迁移

使用 **Flyway**，脚本位于 `src/main/resources/db/migration/`。

- 命名规范：`V<版本>__<描述>.sql`（如 `V1__init_user.sql`）
- 应用启动时自动执行未应用的迁移
- **已应用的脚本不可修改**（Flyway 会校验 checksum，改了会启动失败）

## 五、密钥管理

| 文件 | 是否提交 | 说明 |
|---|---|---|
| `application.yml` | ✅ 提交 | 不含明文密钥，均为占位符 |
| `application-dev.yml` | ❌ **不提交** | 含本地密码，已在 `.gitignore` |
| `application-dev.yml.example` | ✅ 提交 | 模板 |
| `application-prod.yml` | ✅ 提交 | 全部读环境变量 |

## 六、已知待办

- [ ] `SecurityConfig` 当前为「放行所有接口」骨架状态，Sprint 1 需接入 JWT 过滤器并收紧权限
- [ ] 日志中出现 `Using generated security password` —— `UserDetailsServiceAutoConfiguration` 自动配置生效中，Sprint 1 提供自定义 `UserDetailsService` 后消失
