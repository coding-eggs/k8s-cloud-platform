# 说明：**有意不写 `# syntax=docker/dockerfile:1.7`** —— 那会让构建器先去 Docker Hub 拉一个前端镜像，
# 在只走内网/代理的构建机上会直接失败（实测：daemon 拉不到 docker/dockerfile）。
# `RUN --mount=type=cache` 用 Docker 自带的 dockerfile 前端就支持，不需要它。
#
# platform-auth（授权服务器，Spring Authorization Server / OIDC）的运行镜像。
#
# 构建 —— ⚠️ **上下文必须是仓库根**（Maven 反应堆要带上 platform-common）：
#   docker build -f platform-auth/Dockerfile -t k8s-cloud-platform/platform-auth:latest .
#
# 运行（默认端口 9527）：
#   docker run --rm -p 9527:9527 \
#     -e MYSQL_URL='jdbc:mysql://mysql:3306/k8s_cloud_platform?useUnicode=true&characterEncoding=utf8&allowMultiQueries=true&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true' \
#     -e MYSQL_USERNAME=root -e MYSQL_PASSWORD=*** \
#     -e REDIS_HOST=redis -e REDIS_PASSWORD=*** \
#     -e OAUTH2_SERVER=http://platform-auth:9527/oauth2/jwks \
#     k8s-cloud-platform/platform-auth:latest
#
# ⚠️ 容器里的 127.0.0.1 是**容器自己**，不是宿主机 —— 凡是默认值写成 127.0.0.1 / 192.168.x 的
#    依赖地址（MySQL / Redis / OAUTH2_SERVER / K8S_SERVER_URL）在容器里都要显式覆盖。
# ⚠️ 前端（platform-web）与**本服务必须同源**才能登录：SPA 的 origin、issuer、redirect_uri
#    三者必须同 host 字符串（同源铁律），否则会话 Cookie 不随 fetch 发、续期全 302。
#    做法见 platform-web/nginx.conf（它把 /oauth2 与 /session 转发到这里）。

# ---------- 构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
# 依赖缓存挂到 BuildKit 的 cache mount：第二次构建（含另外两个 Java 服务）不再重新下载
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -pl platform-auth -am -DskipTests package

# ---------- 运行 ----------
FROM eclipse-temurin:21-jre AS runtime

# 非 root 跑
RUN useradd -m -u 1001 app
WORKDIR /app
COPY --from=build --chown=app:app /build/platform-auth/target/*.jar /app/app.jar
USER app

# 与宿主机/集群一致的时区（不设也能跑，但日志时间戳会漂）
ENV TZ=Asia/Shanghai
# 堆按容器内存限额算 —— 别写死 -Xmx（限额一调就 OOM 或浪费）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 9527

# 存活探针：TCP 层。本服务引了 actuator，想按 HTTP 探可以换成 /actuator/health
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/9527' || exit 1

# 末尾的 "$@" 让 `docker run <image> --server.port=8080` 这类附加参数能生效
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
