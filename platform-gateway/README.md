# platform-gateway

平台 API 网关（Spring Cloud Gateway，WebFlux）。浏览器的 `/api/**` 全部经它：**接口级鉴权（权限表）+ 403 审计**在这里，
业务服务只校验登录、只写业务。设计见 [spec](../docs/superpowers/specs/2026-11-03-platform-gateway-design.md)，
实施计划见 [plan](../docs/superpowers/plans/2026-11-04-platform-gateway.md)。

```
浏览器 ─▶ nginx /api/**（去前缀）─▶ platform-gateway :8090
                                   ├─ /oauth-clients/**  ─▶ platform-auth  /auth/internal/clients/**
                                   ├─ /jwks/**           ─▶ platform-auth  /auth/internal/jwk/**
                                   └─ /**（含 /ws/**）   ─▶ platform-api   :8081
```

## 1. 它做什么

| 步骤 | 说明 |
|---|---|
| 路由 | `platform.gateway.routes`：外部前缀**最长优先**；转发路径 = `context-path` + 内部路径，query 原样保留 |
| 认证 | 校验 JWT（`OAUTH2_SERVER` 的 JWKS + aud 白名单）；**原 token 原样透传**给上游（上游各自再校验，纵深防御） |
| 授权 | `PermissionDecider`：命中权限行（同 `(service, 方法, 内部路径)` 多行 ANY-of）> 豁免 > 拒绝；匿名一律拒 |
| 权限闭包 | 按 token 的 `data.tenantInfo.tenantId` 现算，与 platform-api **共用同一份 Redis 缓存**（TTL 30s） |
| 403 审计 | `GatewayDeniedAuditor` 写 `platform_audit_log`（DENIED，action = 被判的码逗号拼接；requestPath 不含 query） |
| 公开路径 | `platform.gateway.public-paths`（照抄 api 的 ignore-urls）：只对 api 路由生效，**不解析 Bearer**（`/alert/webhook` 带的是共享密钥） |

- 权限表按 `service` 列区分服务（`platform-api` / `platform-auth`），`resource` 写**服务内路径**。
- 权限表每 `permission-reload-interval`（默认 60s）重载一次；**首次加载失败拒启**，之后重载失败沿用旧表。
- query 上的 `access_token` 只在 `/ws/**` 接受（WS 握手带不了头）。
- SUCCESS / FAILED 审计不在网关：由各服务的 `@Auditable`（`platform-common` 的 `ServletAuditConfiguration`）记。

## 2. 配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `OAUTH2_SERVER` | `http://127.0.0.1:9527/auth` | auth 地址，**必须带 `/auth`**（网关自己拼 `/oauth2/jwks`），否则全站 401 |
| `JWT_AUDIENCES` | `platform-web-client` | aud 白名单，**与 api / auth 同值** |
| `PLATFORM_API_URL` | `http://platform-api:8081` | api 上游 |
| `PLATFORM_AUTH_URL` | `http://platform-auth:9527` | auth 上游（**不带** `/auth`，路由的 context-path 会补） |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USER` / `MYSQL_PASSWORD` | 研发库 | 读权限表、算闭包、写 DENIED 审计；与 api 同一个库 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | 研发 Redis，database 1 | **必须与 api 同库同 database**（共用闭包缓存键）；Redis 挂了直查库 |

## 3. 部署顺序（缺一步就坏）

1. **重放迁移**：`V2026_11_04_1`（权限表加 `service` 列 + JWK 行）→ `V2026_11_04_2`（OAuth 客户端 8 行挪到 platform-auth）。
   本仓没有 Flyway，手工重放；新构建的 mapper 会读 `service` 列，缺列直接 SQL 报错。
2. **部署网关**：配好上表的变量。网关起不来最常见的原因是权限表读不到（首载失败拒启）。
3. **部署 auth 与 api**：auth 用本批构建（`/internal/**` 改校验 JWT，启动自检要求 `/internal/**` 都有 `service=platform-auth` 的行）；
   api 用 **Task 11 的构建（`427753eb`），不含 Task 12** —— 这一步 api 仍自己判权限，网关漏判也不至于全开。
4. **nginx 切 `PLATFORM_GATEWAY_URL`**（platform-web 镜像的运行时变量，默认 `http://platform-gateway:8090`）→ 重新登录 → 跑 §6 验收。
5. **配 NetworkPolicy**（§4）：api 入站只放网关；auth 入站放 nginx + 网关。
6. **确认 5 生效之后**，再部署含 Task 12 的 api 构建（`dc414717` 起：api 只校验登录）。
   ⚠️ 顺序反了 = api 对所有登录用户全开（任何人直连 api 端口都不判权限码）。

auth 的 `/internal/**` 不能只靠 NetworkPolicy 隔离：auth 的协议端点（登录、`/oauth2/**`）仍要 nginx 可达。
所以 `/internal/` 靠 **nginx 对 `/auth/internal/` 回 404**（nginx 不转发该前缀）+ 网络上只有网关能直连 auth。

## 4. NetworkPolicy 示例

按 Pod label 放行（label 按实际部署改；命名空间同为平台所在 ns）：

```yaml
# api：只收网关（Alertmanager 若直连 api 推告警，再放它的来源）
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: platform-api-ingress
spec:
  podSelector:
    matchLabels: { app: platform-api }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels: { app: platform-gateway }
      ports:
        - { protocol: TCP, port: 8081 }
    # - from:                                   # 可选：Alertmanager 直连 /alert/webhook
    #     - namespaceSelector:
    #         matchLabels: { kubernetes.io/metadata.name: monitoring }
    #   ports:
    #     - { protocol: TCP, port: 8081 }
---
# auth：nginx（协议端点）+ 网关（/internal/**）
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: platform-auth-ingress
spec:
  podSelector:
    matchLabels: { app: platform-auth }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels: { app: platform-web }
        - podSelector:
            matchLabels: { app: platform-gateway }
      ports:
        - { protocol: TCP, port: 9527 }
```

⚠️ api / k8s-server 也要拉 auth 的 JWKS（`/auth/oauth2/jwks`）：若它们与 auth 不同节点池或启用了更严的默认拒绝，
把 `app: platform-api`、`app: k8s-server` 也加进 auth 的 `from`。

## 5. 长连接

| 断点 | 现象 | 对策 |
|---|---|---|
| 连接池复用上游 Tomcat 已关的空闲连接 | 偶发 `Connection prematurely closed` → 502 | 池 `max-idle-time: 15s`（< Tomcat keepAliveTimeout 20s）、`max-life-time: 5m` |
| SCG WS 单帧默认 64KB | 终端粘贴大段文本 → 1009 断开 | `websocket.max-frame-payload-length: 2097152`（2MB） |
| 网关响应超时 | 慢接口、大文件下载被掐 | **不设** `response-timeout`（nginx 的 1800s 兜底）；`connect-timeout: 5000` |
| 网关服务端空闲超时 | 静默终端被掐 | 不设 `server.netty.idle-timeout`；静默连接靠终端心跳保活 |
| 网关缓冲响应体 | 下载等全部到齐才开始、内存暴涨 | 不加读 body 的 filter（SCG 默认流式），`GatewayLongConnectionIT` 钉住 |
| 网关发布重启 | 正在用的终端全断 | `server.shutdown: graceful` + 30s；WS 必断，由终端断线提示 + 重连兜底 |
| access log 泄露 token | WS 握手 URL 带 `access_token` | 不开 Reactor Netty access log |

**终端心跳**（`PodTerminal.vue` ⇄ api 的 `PodExecRelayHandler`）：前端每 25s 发 `{"type":"ping"}`，
api 中继直接回 `{"type":"pong"}`、**不转发给 k8s-server**；前端 60s 收不到任何消息（在心跳 tick 上检查，最迟约 85s 发现）
就主动关闭并显示「重新连接」按钮（重连是新会话）。

**网关发布会断开所有打开的终端**：用户看到断开提示，点「重新连接」即可（shell 状态不保留）。

测试：`mvn -pl platform-gateway -am test -DexcludeSlow=` 才会跑 `@Tag("slow")` 的两例（静默 70s、上游 idle 关连接后复用），
默认构建跳过它们。

## 6. 真跑验收

逐项打勾，结果写回 spec §12。

- [ ] 登录后菜单与改造前一致；租户视图切换正常
- [ ] 无权用户调有行接口 → 403，审计页出现一条网关写的 DENIED（requestPath 无 query）
- [ ] OAuth 客户端页增删改、重置密钥、停用全部可用，审计页出现 auth 写的 SUCCESS 记录
- [ ] 非管理员调 `/api/jwks/generate` → 403；直接访问 `/auth/jwk/generate` 与 `/auth/internal/jwk/generate` → 404
- [ ] 终端静默 40 分钟后仍可输入（跨过 nginx 1800s 也不断 —— 心跳在跑）
- [ ] 终端里粘贴 300KB 文本不断开
- [ ] 下载 100MB 对象：进度立刻开始走，网关内存无明显上涨
- [ ] 终端打开时滚动重启网关 → 终端提示断开，点「重新连接」恢复
- [ ] 权限点管理页新增一行 → 60s 内网关按新行判定
- [ ] Alertmanager 告警推送仍能进来（`/alert/webhook`）
