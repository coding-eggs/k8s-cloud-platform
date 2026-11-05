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
#     -e OAUTH2_SERVER=http://platform-auth:9527/auth/oauth2/jwks \
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
#
# ============================ 构建期**完全离线** ============================
# 2026-11-04 改定：helm 与 kubectl **不再在构建时下载**。
#   原来这里 RUN curl get.helm.sh / dl.k8s.io，内网构建机没有出网就整条构建链失败，
#   而且每次构建拿到的字节取决于当时的外网（不可复现）。
#   现在二进制预先提交在 k8s-server/docker/tools/linux-amd64/ 下，构建只做 COPY + 解压：
#     docker/tools/linux-amd64/helm-v4.2.0-linux-amd64.tar.gz   （官方的 linux-amd64 tar 包）
#     docker/tools/linux-amd64/kubectl-v1.37.2-linux-amd64.gz   （裸二进制 gzip 过，约 18MB）
#   换版本：跑 `bash scripts/fetch-k8s-tools.sh`（会跟官方 sha256 比对），
#           再把下面两处文件名与这里列出的版本一起改掉。**别只改一处**。
# ⚠️ 仓库里只预置了 **linux-amd64**。真要出 arm64 镜像，先 `bash scripts/fetch-k8s-tools.sh arm64`
#    并把下面的路径换掉，否则会在 tools 阶段看到一句明确的报错。
# ⚠️ 因为不再装 apt 包，镜像里**没有** curl（构建期用不到，运行期也不需要）。
#    ca-certificates 由 eclipse-temurin 基础镜像自带 —— 连 K8s API / auth 的 HTTPS 全靠它。
# ==========================================================================

# ---------- 阶段一：把压缩包解开成真实二进制 ----------
# 单独一个阶段是为了让最终镜像里只有二进制，不残留那两个压缩包（省约 36MB）。
# 基础镜像走平台 Harbor（不访问 Docker Hub）：<harbor>/<上游主机>/<仓库>。
# 镜像要先由 scripts/harbor-replication.py 复制进 Harbor 的 docker.io 项目，否则这里拉不到。
FROM harbor.ackcp.cloud/docker.io/library/eclipse-temurin:21-jre AS tools

ARG TARGETARCH=amd64
# ⚠️ Dockerfile 的 RUN 只认一行，多行必须每行末尾带 `\`（否则只把第一行交给 sh → "expecting }"）。
RUN [ "$TARGETARCH" = "amd64" ] || { \
      echo "只预置了 linux-amd64 的 helm/kubectl；要出 $TARGETARCH 镜像请先跑 scripts/fetch-k8s-tools.sh $TARGETARCH" >&2; \
      exit 1; \
    }

# S3.6：组件安装靠 PATH 上的 helm 二进制（HelmRunner 直接调 `helm`）。
# ⚠️ 版本必须与平台验证过的一致：Helm 4 用 --rollback-on-failure，没有 --atomic。
COPY docker/tools/linux-amd64/helm-v4.2.0-linux-amd64.tar.gz /tmp/helm.tgz
# S3.6：超大 chart（Calico 的 CRD chart）不走 Helm release —— Helm 把整个 chart 存进 release
# Secret，超过 1MiB 就装不了。那条路径用 `kubectl apply --server-side` 落盘，所以镜像里要有 kubectl。
COPY docker/tools/linux-amd64/kubectl-v1.37.2-linux-amd64.gz /tmp/kubectl.gz

# 解压后立刻各跑一次 --version：文件被截断 / 架构不对会在这里就失败，而不是等到装组件时。
RUN tar -xzf /tmp/helm.tgz -C /tmp \
 && mv /tmp/linux-amd64/helm /usr/local/bin/helm \
 && gunzip -c /tmp/kubectl.gz > /usr/local/bin/kubectl \
 && chmod +x /usr/local/bin/helm /usr/local/bin/kubectl \
 && helm version --short \
 && kubectl version --client

# ---------- 阶段二：运行镜像 ----------
FROM harbor.ackcp.cloud/docker.io/library/eclipse-temurin:21-jre

COPY --from=tools /usr/local/bin/helm    /usr/local/bin/helm
COPY --from=tools /usr/local/bin/kubectl /usr/local/bin/kubectl

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
