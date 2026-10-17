# k8s-server（适配器：唯一持有 fabric8 / kubeconfig 的一层）的**运行镜像**。
#
# ⚠️ 2026-10-16 改定：**编译不在这里做** —— 交给流水线的「Java 构建」节点（理由同 platform-api）。
#
# 用法（**两步，与流水线的两个节点一一对应**）：
#   1) 先在**仓库根**编译（产出 k8s-server/target/*.jar）：
#        mvn -B -pl k8s-server -am -DskipTests package
#   2) 再打包镜像，**上下文是模块目录**：
#        docker build -f k8s-server/Dockerfile -t k8s-cloud-platform/k8s-server:latest k8s-server
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

FROM eclipse-temurin:21-jre

# S3.6：组件安装靠 PATH 上的 helm 二进制（HelmRunner 直接调 `helm`）。
# ⚠️ 版本必须与平台验证过的一致：Helm 4 用 --rollback-on-failure，没有 --atomic。
# ⚠️ 构建机需要能访问 get.helm.sh（内网可用 --build-arg HELM_BASE_URL=<镜像站>）；
#    需要出网代理时按惯例传 --build-arg HTTP_PROXY/HTTPS_PROXY。
ARG HELM_VERSION=v4.2.0
ARG HELM_BASE_URL=https://get.helm.sh
ARG TARGETARCH
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL "${HELM_BASE_URL}/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" -o /tmp/helm.tgz \
 && tar -xzf /tmp/helm.tgz -C /tmp \
 && mv "/tmp/linux-${TARGETARCH}/helm" /usr/local/bin/helm \
 && chmod +x /usr/local/bin/helm \
 && helm version --short \
 && rm -rf /tmp/helm.tgz "/tmp/linux-${TARGETARCH}" \
 && apt-get purge -y --auto-remove curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1001 app
WORKDIR /app
# S3.6：组件安装用的内置 chart（文本目录，不是 tgz）。
# 源位置在 src/main/resources/charts（IDE 走 classpath 解析）；镜像里复制成真实目录 /app/charts，
# 配合 WORKDIR=/app，ChartBundle 的默认配置值 `charts` 正好命中。
COPY --chown=app:app src/main/resources/charts /app/charts
COPY --chown=app:app target/*.jar /app/app.jar
USER app

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# 存活探针：TCP 层（本服务没引 actuator）
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
