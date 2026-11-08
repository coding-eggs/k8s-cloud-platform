# 中间件对外暴露（复用应用端口池 / 平台分配 NodePort）设计

> 前置：应用对外暴露（`2026-11-08-app-istio-exposure-design.md`）已实现：Istio 网关端口池、命名空间 ListenerSet
> `platform-ingress`、端口分配表 `platform_ingress_port`、平台分配 NodePort（`AppNodePortAllocator`）。
> 用户 2026-11-08 拍板：第一批范围按下表；网关与 NodePort 两种方式都给；开发环境，不考虑存量兼容。

## 1. 范围

| 中间件 | 可暴露端点（key / 目标 Service:端口 / 网关协议） |
|---|---|
| MySQL | `rw` haproxy:3306 TCP；`ro` haproxy:3307 TCP |
| PostgreSQL | `rw` `<名>-rw`:5432 TCP；`ro` `<名>-ro`:5432 TCP |
| Redis（仅单机） | `main` `<名>`:6379 TCP |
| Elasticsearch | `http` `<名>-es-http`:9200；`kibana` `<名>-kb-http`:5601（Kibana 开着时）。未开 TLS 走网关 HTTP，开了 TLS 走 TCP 透传 |
| RabbitMQ | `amqp` `<名>`:5672 TCP；`management` `<名>`:15672 HTTP |
| Milvus | `grpc` `<名>-milvus`:19530 TCP |

不支持（页面说明原因）：Kafka（客户端改连 broker 通告地址，需逐 broker 端口 + 改 Strimzi listener）、
Nacos（2.x 客户端连「HTTP 端口 + 1000」的 gRPC，端口池给不出这对端口）、Redis 哨兵 / 集群（返回 / 重定向到 Pod IP）。

端点表与连接信息（`MiddlewareService.connection`）同处维护，Service 名 / 端口只在一处拼。

## 2. 方式与语义

每个端点三选一：不暴露 / 网关 / NodePort。网关协议由端点决定，用户不选。

- **没有「发布」**：中间件改暴露方式立即生效（应用是保存期望配置、发布时生效）。
- **网关**：从应用端口池分配端口（`platform_ingress_port`，`owner_kind=MIDDLEWARE`，`app_id`=实例 id、
  `service_key`=端点 key），重算命名空间 ListenerSet，再下发 `TCPRoute` / `HTTPRoute`（parentRef = ListenerSet
  `platform-ingress` 的 `p<端口>`，backendRef = 目标 Service:端口）。分配、冷却、拿回原端口、每命名空间 64 个上限、
  Istio `routes` 功能门禁全部沿用应用的规则。
- **NodePort**：平台在 30000-32767 挑空闲端口（与应用共用避让集合：集群全部 nodePort + 应用服务已保存的 +
  中间件已分配的），存表后不变；k8s-server 建一个 NodePort Service，selector / targetPort 抄目标 Service。
  不提供手填端口（YAGNI）。
- **切换顺序**：先下发新对象（删旧路由 / Service），离开网关的端口在 ListenerSet 重算成功后才释放（同应用 I4）。
- **删实例**：k8s-server 按标签删掉该实例的暴露对象，释放端口，删台账行；失败只记日志，不挡删除。
- **安全提示**：网关 TCP 透传与 NodePort 都不加认证，全靠中间件自己的账号；Milvus 未开鉴权时开启暴露要二次确认。

## 3. 数据

- `platform_ingress_port` 加列 `owner_kind`（APP / MIDDLEWARE，默认 APP），只用于展示归属（ListenerSet 页）。
  唯一性不变：应用 id 前缀 `app_`、实例 id 前缀 `mwi_`，不会撞。
- 新表 `platform_middleware_expose`：`(instance_id, endpoint_key)` 唯一，`mode`（GATEWAY / NODEPORT）、
  `node_port`（NODEPORT 时）。不暴露 = 无行。不加外键。

## 4. 接口

- platform-api：`POST /middleware/instance/expose/list`（`tenant:middleware:read`）、
  `POST /middleware/instance/expose/update`（`tenant:middleware:write`，审计）。
- k8s-server（零业务，TENANT 边界）：`POST /middleware/expose/apply`（按给定参数建 / 删路由与 NodePort Service）、
  `POST /middleware/expose/delete-all`（按实例标签清理）。对象名 `<实例名>-expose-<key>`，
  标签 `platform.coding/middleware-expose=<实例名>` + `platform.coding/managed-by=middleware-expose`：后者让
  `ManagedResourceGuard` 判成 MIDDLEWARE —— 路由策略 / 服务页显示归属并置灰编辑删除，直接调 API 也被拒，
  只能在中间件详情「对外访问」里取消。

## 5. 页面

中间件详情「概览」的连接方式下方加「对外访问」卡片：每个端点一行（名称 / 集群内目标 / 方式下拉 / 对外端口 /
状态）。不支持的类型 / 模式显示原因。ListenerSet 页的归属列认出中间件（显示实例名，租户视角可跳详情）。
