# 说明：**有意不写 `# syntax=docker/dockerfile:1.7`** —— 那会让构建器先去 Docker Hub 拉一个前端镜像，
# 在只走内网/代理的构建机上会直接失败（实测：daemon 拉不到 docker/dockerfile）。
# `RUN --mount=type=cache` 用 Docker 自带的 dockerfile 前端就支持，不需要它。
#
# k8s-server（适配器：唯一持有 fabric8 / kubeconfig 的一层）的运行镜像。
#
# 构建 —— ⚠️ **上下文必须是仓库根**（反应堆要带上 k8s-core / platform-common / platform-data）：
#   docker build -f k8s-server/Dockerfile -t k8s-cloud-platform/k8s-server:latest .
#
# 运行（默认端口 8080）：
#   docker run --rm -p 8080:8080 \
#     -e MYSQL_URL='jdbc:mysql://mysql:3306/k8s_cloud_platform?useUnicode=true&characterEncoding=utf8&allowMultiQueries=true&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true' \
#     -e MYSQL_USERNAME=root -e MYSQL_PASSWORD=*** \
#     -e OAUTH2_SERVER=http://platform-auth:9527/oauth2/jwks \
#     k8s-cloud-platform/k8s-server:latest
#
# ⚠️ 依赖地址在容器里必须显式覆盖（容器里的 127.0.0.1 是容器自己）：
#    MYSQL_URL / MYSQL_USERNAME / MYSQL_PASSWORD · OAUTH2_SERVER
#    流水线那批键（都有默认值）：PIPELINE_ARCHIVE_ENABLED · PIPELINE_RUN_TIMEOUT ·
#    PIPELINE_TIMEOUT_CHECK · PIPELINE_LOG_MAX_BYTES（**必须与 platform-api 那份一致**）
# ⚠️ 本服务要连集群：kubeconfig 存在平台库里（密文），出站是直连 K8s API ——
#    它**不走** platform-api 那条出站代理白名单，部署时按集群的地址放行。
# ⚠️ 流水线的归档/超时检查是**后台定时任务**，不要跑多副本（否则同一条运行会被归档两次；
#    幂等挡住了重复写，但没必要）。

# ---------- 构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -pl k8s-server -am -DskipTests package

# ---------- 运行 ----------
FROM eclipse-temurin:21-jre AS runtime

RUN useradd -m -u 1001 app
WORKDIR /app
COPY --from=build --chown=app:app /build/k8s-server/target/*.jar /app/app.jar
USER app

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# 存活探针：TCP 层（本服务没引 actuator）
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
