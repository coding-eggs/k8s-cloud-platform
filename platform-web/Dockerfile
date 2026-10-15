# platform-web（Vue 3 SPA）的**运行镜像**：nginx 托管构建产物 + 反向代理。
#
# ⚠️ 2026-10-16 改定：**npm 构建不在这里做** —— 交给流水线的「Node 构建」节点。
#   理由同另外三个：写在 Dockerfile 里的话，构建节点刚 build 一遍、`buildah bud` 又 build 一遍，
#   而第二遍拿不到依赖缓存（/cache 挂在构建节点上，不在 builder 里）。
#
# 用法（**两步，与流水线的两个节点一一对应**）：
#   1) 先在 platform-web/ 里构建（产出 dist/）。⚠️ 那三个 VITE_* 是**构建期**被 Vite 内联进 JS 的，
#      所以它们现在由**构建命令**给（不再是 --build-arg）：
#        VITE_OAUTH_ISSUER=http://your-host \
#        VITE_OAUTH_CLIENT_ID=platform-web-client \
#        VITE_OAUTH_REDIRECT_URI=http://your-host/callback \
#        npm ci && npm run build-only
#      （也可以把这三行写进 platform-web/.env.production —— Vite 会读它，优先级高于仓库里的 .env）
#   2) 再打包镜像，**上下文是模块目录**：
#        docker build -f platform-web/Dockerfile -t k8s-cloud-platform/web:latest platform-web
#
# 流水线里对应：「Node 构建」节点（工作目录 platform-web、命令见上面第 1 步）
#   → 「构建并推送镜像」节点（Dockerfile 路径 platform-web/Dockerfile、构建上下文 platform-web）
#
# ⚠️ 那三个 VITE_* 不是运行时读环境变量 —— 改域名/端口要**重新构建**（不是重启容器）。
#    三者必须与 platform-auth 里 platform-web-client 注册的 redirect_uri 一字不差（同源铁律）。
#
# 运行 —— ⚠️ 本镜像**不能单独跑**：nginx 启动时就要解析 platform-api / platform-auth 这两个名字
# （解析不到会直接起不来：host not found in upstream）。所以要跟它们一起部署：
#   docker compose / k8s：服务名对得上就行，什么都不用加
#   单机调试：用 --add-host 把这两个名字指到宿主机（或你自己挂一份 nginx.conf）
#   docker run --rm -p 80:80 \
#     --add-host platform-api:host-gateway --add-host platform-auth:host-gateway \
#     k8s-cloud-platform/web:latest
#
# 代理规则写在同一目录的 nginx.conf：/api/** → platform-api（去 /api 前缀），
# /oauth2 与 /session → platform-auth。服务名不同就改那个文件（或挂载覆盖）。

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
