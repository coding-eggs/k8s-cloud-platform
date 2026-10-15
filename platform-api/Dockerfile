# 说明：**有意不写 `# syntax=docker/dockerfile:1.7`** —— 那会让构建器先去 Docker Hub 拉一个前端镜像，
# 在只走内网/代理的构建机上会直接失败（实测：daemon 拉不到 docker/dockerfile）。
# `RUN --mount=type=cache` 用 Docker 自带的 dockerfile 前端就支持，不需要它。
#
# platform-api（租户域业务层 + HTTP 入口）的运行镜像。
#
# 构建 —— ⚠️ **上下文必须是仓库根**（反应堆要带上 platform-common / platform-data）：
#   docker build -f platform-api/Dockerfile -t k8s-cloud-platform/platform-api:latest .
#
# 运行（默认端口 8081）：
#   docker run --rm -p 8081:8081 \
#     -e MYSQL_URL='jdbc:mysql://mysql:3306/k8s_cloud_platform?useUnicode=true&characterEncoding=utf8&allowMultiQueries=true&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true' \
#     -e MYSQL_USERNAME=root -e MYSQL_PASSWORD=*** \
#     -e REDIS_HOST=redis -e REDIS_PASSWORD=*** \
#     -e K8S_SERVER_URL=http://k8s-server:8080 \
#     -e OAUTH2_SERVER=http://platform-auth:9527/oauth2/jwks \
#     -e CREDENTIAL_AES_KEY=*** -e KUBECONFIG_AES_KEY=*** \
#     k8s-cloud-platform/platform-api:latest
#
# ⚠️ 依赖地址在容器里必须显式覆盖（容器里的 127.0.0.1 是容器自己）：
#    MYSQL_* · REDIS_* · K8S_SERVER_URL · OAUTH2_SERVER · THANOS_QUERY_URL
# ⚠️ 两个 AES 主密钥（CREDENTIAL_AES_KEY / KUBECONFIG_AES_KEY）**必须与库里已有密文一致** ——
#    换一个值，所有凭据与 kubeconfig 都会解不开（报"密文损坏或主密钥与写入时不一致"）。
# ⚠️ 出站代理（OUTBOUND_PROXY_SERVER / _PORT / _TARGETS）：只有白名单目标走代理
#    （Docker Hub / github 等），内网 Harbor 与集群一律直连。
# ⚠️ 平台是**有状态**的：迁移要手工重放（本仓没有 Flyway），所以启动前先按 db/migration 顺序重放。

# ---------- 构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -pl platform-api -am -DskipTests package

# ---------- 运行 ----------
FROM eclipse-temurin:21-jre AS runtime

RUN useradd -m -u 1001 app
WORKDIR /app
COPY --from=build --chown=app:app /build/platform-api/target/*.jar /app/app.jar
USER app

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8081

# 存活探针：TCP 层（本服务没引 actuator）
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
