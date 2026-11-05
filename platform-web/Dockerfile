# platform-web（Vue 3 SPA）的**运行镜像**：nginx 托管构建产物 + 反向代理。
#
# ⚠️ 2026-10-16 改定：**npm 构建不在这里做** —— 交给流水线的「Node 构建」节点。
#   理由同另外三个：写在 Dockerfile 里的话，构建节点刚 build 一遍、`buildah bud` 又 build 一遍，
#   而第二遍拿不到依赖缓存（/cache 挂在构建节点上，不在 builder 里）。
#
# 用法（**两步，与流水线的两个节点一一对应**）：
#   1) 先在 platform-web/ 里构建（产出 dist/）。⚠️ VITE_* 是**构建期**被 Vite 内联进 JS 的，
#      所以它们由**构建命令**给（不再是 --build-arg）：
#        VITE_OAUTH_ISSUER=/auth \
#        VITE_OAUTH_CLIENT_ID=platform-web-client \
#        npm ci && npm run build-only
#      回调地址不用给：前端默认取「页面 origin + /callback」，同一镜像换域名/端口不用重新构建。
#      部署期要指定别的回调地址，用**运行时**变量 PLATFORM_OAUTH_REDIRECT_URI（见文件末尾「运行」），
#      不用重新构建；构建期 VITE_OAUTH_REDIRECT_URI 仍可显式给，但优先级低于运行时值（仅为兼容保留）。
#      ⚠️ VITE_OAUTH_ISSUER **只写上下文路径 /auth**（platform-auth 的 server.servlet.context-path），
#        代码拼出来的是 /auth/oauth2/... 这类相对路径，跟着本 nginx 的 origin 走，auth 由 nginx
#        整块转发（见 nginx.conf 的 /auth/，auth 部署在远端也照此）。写死具体 host 会退化成跨站，
#        续期/登出用的会话 Cookie（SameSite=Lax）就不随 fetch 发了。
#      （也可以把这三行写进 platform-web/.env.production —— Vite 会读它，优先级高于仓库里的 .env）
#   2) 再打包镜像，**上下文是模块目录**：
#        docker build -f platform-web/Dockerfile -t k8s-cloud-platform/web:latest platform-web
#
# 流水线里对应：「Node 构建」节点（工作目录 platform-web、命令见上面第 1 步）
#   → 「构建并推送镜像」节点（Dockerfile 路径 platform-web/Dockerfile、构建上下文 platform-web）
#
# ⚠️ VITE_* 不是运行时读环境变量 —— 改了要**重新构建**（不是重启容器）。
#    换域名/端口时**不用重新构建**：要么让前端默认取页面 origin + /callback，
#    要么设运行时变量 PLATFORM_OAUTH_REDIRECT_URI；无论哪种，platform-auth 里 platform-web-client
#    登记的 redirect_uri 都必须与前端实际用的值**一字不差**（授权端点是精确匹配）。
#
# 运行 —— 三个参数由**运行时环境变量**给（改了重启容器即可，不用重新打镜像）：
#   PLATFORM_API_URL   默认 http://platform-api:8081
#   PLATFORM_AUTH_URL  默认 http://platform-auth:9527
#   PLATFORM_OAUTH_REDIRECT_URI  默认空 = 前端取「页面 origin + /callback」；
#                     非空时原样作为 OAuth redirect_uri（例：http://192.168.85.1:30080/callback）
#   写法 scheme://host:port，结尾不要带 /。
#   ⚠️ nginx 启动时就要解析这两个地址里的主机名（解析不到直接起不来：host not found in upstream）。
#   docker compose / k8s 里服务名对得上就不用设；单机调试指到能解析的地址：
#   docker run --rm -p 80:80 \
#     -e PLATFORM_API_URL=http://10.0.0.12:8081 \
#     -e PLATFORM_AUTH_URL=http://10.0.0.13:9527 \
#     k8s-cloud-platform/web:latest
#
# 代理规则写在同一目录的 nginx.conf（是模板，启动时由 nginx 官方镜像的 envsubst 渲染）：
# /api/** → platform-api（去 /api 前缀），/auth/** → platform-auth，
# /runtime-config.js → 按上面的环境变量现生成 SPA 的运行时配置（回调地址等）。
# TLS 由本镜像的 nginx 终止时，把同目录的 nginx-https.conf 挂到
# /etc/nginx/templates/default.conf.template（用法见它的文件头；挂到 conf.d 会被覆盖）。
# 容器根文件系统只读时启动渲染写不进去：给 /etc/nginx/conf.d 挂一个可写的 emptyDir。

# 基础镜像走平台 Harbor（不访问 Docker Hub）：<harbor>/<上游主机>/<仓库>。
# 镜像要先由 scripts/harbor-replication.py 复制进 Harbor 的 docker.io 项目，否则这里拉不到。
FROM harbor.ackcp.cloud/docker.io/library/nginx:1.27-alpine

ENV PLATFORM_API_URL=http://platform-api:8081 \
    PLATFORM_AUTH_URL=http://platform-auth:9527 \
    PLATFORM_OAUTH_REDIRECT_URI="" \
    NGINX_ENVSUBST_FILTER=^PLATFORM_

# 放到模板目录：官方镜像的 /docker-entrypoint.d/20-envsubst-on-templates.sh 启动时渲染到 conf.d
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
