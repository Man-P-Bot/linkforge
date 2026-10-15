# =============================================================================
# LinkForge 应用镜像（D6 起，见 docs/PERFORMANCE.md）
#
# 为什么要容器化应用（而不是继续跑在宿主机）：
#   第 5 周必然容器化。若第 1 周基线是「宿主 JVM + 容器 wrk」、第 5 周是「容器 JVM + 容器 wrk」，
#   两者的 CPU 配额、cgroup 限制、网络栈都不一致，优化前后的比例不成立。
#   所以 D6 就把应用放进容器，让第 1 周与第 5 周的基线口径一致。
#
# 构建方式：多阶段
#   builder —— maven:3.9.12-eclipse-temurin-17，用仓库自带的 ./mvnw 构建（与本机完全同一套工具链）
#   runtime —— eclipse-temurin:17-jre-jammy，只带 JRE，不带构建工具
#
# 为什么必须用 ./mvnw 而不是 `mvn`：
#   .mvn/maven.config 固定了 -s .mvn/settings.xml（阿里云镜像）与 -Dmaven.repo.local=.mvn-repo，
#   这是 DR-001/DR-016 的约定——构建不依赖任何人的 ~/.m2。用 ./mvnw 保证容器内外同源。
#
# 用法（在项目根目录）：
#   docker build -t linkforge-app:0.1.0 .
#   docker run --rm --network linkforge_default \
#     -e SPRING_DATASOURCE_URL=... -e MYSQL_USER=... -e MYSQL_PASSWORD=... \
#     -e REDIS_HOST=redis -e SHORTLINK_BASE_URL=... linkforge-app:0.1.0
# =============================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9.12-eclipse-temurin-17 AS builder

WORKDIR /build

# 先只拷构建描述，让依赖层能被缓存：
# 只有 pom.xml 变化时才重新解析依赖，源码变化只重跑编译。
COPY pom.xml ./
COPY .mvn ./.mvn
COPY mvnw ./
COPY mvnw.cmd ./
COPY linkforge-core/pom.xml ./linkforge-core/
COPY linkforge-redirect/pom.xml ./linkforge-redirect/
COPY linkforge-stats/pom.xml ./linkforge-stats/
COPY linkforge-admin/pom.xml ./linkforge-admin/
COPY linkforge-app/pom.xml ./linkforge-app/

RUN chmod +x ./mvnw && \
    ./mvnw -B -q dependency:go-offline -DskipTests || true

# 再拷源码并真正构建。
# -DskipTests：D6 的验证靠 compose 启动后的 curl 与压测脚本，不在镜像里跑测试；
#             测试在宿主用 `clean install` 跑，那里才有 MySQL 集成测试的环境。
COPY . .

RUN chmod +x ./mvnw && \
    ./mvnw -B -q clean package -DskipTests && \
    ls -l linkforge-app/target/*.jar

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy

# 容器时区：与 mysql 服务保持一致，方便 docker compose logs 对时读。
# ⚠️ 这**不影响**业务时区正确性——应用的时间语义由 JDBC 参数
#    connectionTimeZone=UTC & forceConnectionTimeZoneToSession=true 决定（DR-032），
#    持久层的 LocalDateTime 一律按 UTC 解释。
ENV TZ=Asia/Shanghai

WORKDIR /app

# 非 root 运行
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin linkforge
COPY --from=builder --chown=linkforge:linkforge /build/linkforge-app/target/linkforge-app-*.jar /app/app.jar
USER linkforge

EXPOSE 8080

# 固定 JVM 堆与 GC（D6 决策 Q2）：
#   基线必须可复现。不固定堆，第 2 周调优后「堆大小」会混进「代码优化」的效果里。
#   -Xms1g -Xmx1g  固定堆，避免堆伸缩带来的抖动，也避免堆大小成为隐藏变量
#   -XX:+UseG1GC   固定 GC，与第 2 周调优前的默认值保持一致
#   GC 日志一并打开（-XX:+PrintGCDetails），第 5 周调优时对比 GC 行为用。
#     注意：该参数在 JDK 17 已废弃（会打印 deprecation warning），JDK 9 起的等价物是
#     -Xlog:gc*。此处按 D6 决策 Q2 的原文保留 -XX:+PrintGCDetails 以确保口径一致，
#     如需无警告输出可改为 -Xlog:gc*:file=/dev/stdout:time,uptime,level,tags。
ENV JAVA_OPTS="-Xms1g -Xmx1g -XX:+UseG1GC -XX:+PrintGCDetails -Xlog:gc*:file=/dev/stdout:time,uptime,level,tags"

# exec 形式启动，使 java 成为 PID 1，能收到 compose down 的 SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]