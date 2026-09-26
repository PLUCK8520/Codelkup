# ============================================================
# Codelkup 后端镜像（多阶段构建）
#
# 「换台干净机器 docker compose up 就能跑」的前提是**镜像里自带构建能力**，
# 所以这里用多阶段：阶段 1 在容器内 mvn package，阶段 2 只带 JRE + jar。
# 运行镜像不含 Maven 与源码，最终体积约 200MB（而不是 800MB+）。
# ============================================================

# ---------------- 阶段 1：构建 ----------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# ⚠️ 这一步是构建提速的关键，别合并成一句 COPY . .
#    先把 pom.xml 单独拷进来让 Maven 把依赖拉全，形成独立的 Docker 层。
#    之后只要 pom.xml 没改，改 Java 代码就不会触发重新下载依赖 ——
#    否则每次改一行代码都要重拉几百 MB。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 依赖层已就绪，这时才拷源码
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------------- 阶段 2：运行 ----------------
FROM eclipse-temurin:21-jre-alpine

# 时区 —— 必须处理，否则时间整体差 8 小时：
#   1) JDBC 连接串里写了 serverTimezone=Asia/Shanghai
#   2) Jackson 也配了 time-zone: Asia/Shanghai
#   alpine 基础镜像默认只有 UTC。这里装上 tzdata → 设置时区 → 立刻删掉 tzdata，
#   既拿到正确时区又不把时区数据库留在镜像里。
# curl 只给 HEALTHCHECK 用。
RUN apk add --no-cache tzdata curl \
 && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone \
 && apk del tzdata

# 非 root 运行：万一容器被攻破，降低影响面
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

# 上传目录先建好并归属 app 用户 ——
# compose 会把数据卷挂到这里，若目录属主不对，非 root 进程会写不进去
RUN mkdir -p /app/uploads && chown -R app:app /app

USER app

COPY --from=build --chown=app:app /build/target/*.jar app.jar

EXPOSE 8085

# 健康检查打项目自定义的 /api/system/health（它内部会探测 MySQL 与 Redis，
# 比单纯判进程存活更有意义 —— 依赖挂了也能反映出来）。
# start-period 给到 75s：Spring Boot 启动 + Flyway 首次迁移在这个窗口内完成。
HEALTHCHECK --interval=15s --timeout=5s --start-period=75s --retries=6 \
  CMD curl -fsS http://127.0.0.1:8085/api/system/health || exit 1

# MaxRAMPercentage 让 JVM 按 cgroup 限制计算堆大小，
# 而不是按宿主机物理内存 —— 容器里默认行为会算出过大的堆，容易被 OOM Kill。
#
# ⚠️ 这里用 `sh -c` 包一层是为了让堆上限**可配**：2C2G 的小内存 VPS 上
#    JVM 会和 MySQL 抢内存，必须把百分比压到 35 左右（compose 里通过
#    JVM_MAX_RAM_PERCENTAGE 注入）。exec 保证 PID 1 仍然是 java，信号能正常收到。
ENV JVM_MAX_RAM_PERCENTAGE=75.0
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=${JVM_MAX_RAM_PERCENTAGE} -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
