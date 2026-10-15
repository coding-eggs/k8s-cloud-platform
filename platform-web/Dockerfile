# 说明：**有意不写 `# syntax=docker/dockerfile:1.7`** —— 那会让构建器先去 Docker Hub 拉一个前端镜像，
# 在只走内网/代理的构建机上会直接失败（实测：daemon 拉不到 docker/dockerfile）。
# `RUN --mount=type=cache` 用 Docker 自带的 dockerfile 前端就支持，不需要它。
#
# platform-web（Vue 3 SPA）的运行镜像：node 构建 → nginx 托管 + 反向代理。
#
# 构建 —— ⚠️ **上下文是仓库根**（与本仓另外三个 Dockerfile 一致，省得记两套）：
#   docker build -f platform-web/Dockerfile \
#     --build-arg VITE_OAUTH_ISSUER=http://your-host \
#     --build-arg VITE_OAUTH_REDIRECT_URI=http://your-host/callback \
#     -t k8s-cloud-platform/web:latest .
#
# 运行 —— ⚠️ 本镜像**不能单独跑**：nginx 启动时就要解析 platform-api / platform-auth 这两个名字
# （解析不到会直接起不来：host not found in upstream）。所以要跟它们一起部署：
#   docker compose / k8s：服务名对得上就行，什么都不用加
#   单机调试：用 --add-host 把这两个名字指到宿主机（或你自己挂一份 nginx.conf）
#   docker run --rm -p 80:80 \
#     --add-host platform-api:host-gateway --add-host platform-auth:host-gateway \
#     k8s-cloud-platform/web:latest
#
# ⚠️ 那三个 VITE_* 是**构建期**被 Vite 内联进 JS 的（不是运行时读环境变量）——
#    改域名/端口要**重新 build 镜像**，`docker run -e` 给它们没有用。三者必须与
#    platform-auth 里 platform-web-client 注册的 redirect_uri 一字不差（同源铁律）。
#
# 代理规则写在 platform-web/nginx.conf：/api/** → platform-api（去 /api 前缀），
# /oauth2 与 /session → platform-auth。服务名不同就改那个文件（或挂载覆盖）。

# ---------- 构建 ----------
FROM node:20-alpine AS build
WORKDIR /build

# 先只装依赖：改业务代码时这一层能复用
COPY platform-web/package.json platform-web/package-lock.json ./
RUN npm ci

COPY platform-web/ ./

ARG VITE_OAUTH_ISSUER=http://localhost
ARG VITE_OAUTH_CLIENT_ID=platform-web-client
ARG VITE_OAUTH_REDIRECT_URI=http://localhost/callback
# .env.production 的优先级高于仓库里那份开发用的 .env，所以这三个值稳定生效
RUN printf 'VITE_OAUTH_ISSUER=%s\nVITE_OAUTH_CLIENT_ID=%s\nVITE_OAUTH_REDIRECT_URI=%s\n' \
        "$VITE_OAUTH_ISSUER" "$VITE_OAUTH_CLIENT_ID" "$VITE_OAUTH_REDIRECT_URI" > .env.production \
 && npm run build-only

# ---------- 运行 ----------
FROM nginx:1.27-alpine AS runtime

COPY platform-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
