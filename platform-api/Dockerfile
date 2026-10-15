# platform-api（租户域业务层 + HTTP 入口）的**运行镜像**。
#
# ⚠️ 2026-10-16 改定：**编译不在这里做** —— 交给流水线的「Java 构建」节点。
#   理由：编译写在 Dockerfile 里的话，「Java 构建」节点刚编译一遍、`buildah bud` 又编译一遍，
#   而且第二遍**拿不到依赖缓存**（/cache 挂在构建节点上，不在 builder 里）——
#   构建节点存在的意义就没了（用户 2026-10-16 的决定：打包阶段独立成节点）。
#
# 用法（**两步，与流水线的两个节点一一对应**）：
#   1) 先在**仓库根**编译（产出 platform-api/target/*.jar）：
#        mvn -B -pl platform-api -am -DskipTests package
#   2) 再打包镜像，**上下文是模块目录**（不是仓库根 —— buildah/docker 会把整个上下文传给
#      builder，模块目录小得多）：
#        docker build -f platform-api/Dockerfile -t k8s-cloud-platform/platform-api:latest platform-api
#
# 流水线里对应：
#   「Java 构建」节点：工作目录留空（仓库根）、命令 mvn -B -pl platform-api -am -DskipTests package
#   「构建并推送镜像」节点：Dockerfile 路径 platform-api/Dockerfile、构建上下文 platform-api
#
# ⚠️ 本文件假定 `target/*.jar` **已经编译好了**（上面第 1 步）。单独 `docker build` 会失败在
#    COPY 那一步 —— 那是设计如此，不是配置错误。
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

FROM eclipse-temurin:21-jre

RUN useradd -m -u 1001 app
WORKDIR /app
# 相对**上下文**（= 模块目录）—— 那一个 jar 就是 spring-boot repackage 出来的可执行包
COPY --chown=app:app target/*.jar /app/app.jar
USER app

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8081

# 存活探针：TCP 层（本服务没引 actuator）
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
