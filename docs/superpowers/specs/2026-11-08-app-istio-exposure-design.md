# 应用对外暴露（Istio 入口 + ListenerSet）与灰度 设计

> 状态：方案定稿前的整理稿（2026-11-08），**尚未实现**。重建入口前必须先完成 §9 的真集群验证。
> 前置：应用模块去画布重做（`application-release.md`），现只有 NodePort 暴露（§1.5）。

## 1. 结论

- 重建一个平台管理的**主 Gateway** `istio-system/platform-ingress`，80/443 按 Host / SNI 多域名共用，
  另有 TCP 端口池和遗留端口 listener。
- 每个命名空间一个平台生成的 ListenerSet `platform-domains`（Gateway API v1 的独立对象，v1.5 起 Standard），
  本命名空间里应用用到的每个对外域名是其中一个 listener；各应用只生成自己的 HTTPRoute。主 Gateway 用 `allowedListeners` 只放行带平台标签的命名空间。
  Gateway 自身 64 个 listener 的上限因此不再是问题：每个 ListenerSet 也是最多 64 个，但 ListenerSet 的个数不限。
- **租户不能直接建 ListenerSet / 改主 Gateway**：域名先在平台登记、校验，平台在发布时生成对象。
  域名唯一由平台库保证；Gateway API 的冲突裁决（先建者胜）只是兜底。
- 平台默认域名形如 `<服务>-<应用>-<租户>.apps.ackcp.cloud`，用一张 `*.apps.ackcp.cloud` 通配证书。
  租户自定义域名要先证明归属，证书由租户上传，或由平台通过 ACME 签发。
- 灰度放在同一套模型里做第二期：应用的 HTTPRoute 按权重或 header 把流量分到 stable / canary 两个 Service。

## 2. 背景与现状

- 现有入口 `istio-system/external-gateway`：NodePort，按端口建了 15 个 listener，`allowedRoutes.from: All`，
  挂着 11 条不带 hostname 的遗留路由。`harbor.ackcp.cloud` 挂在 443 上。
- 平台看不见这个入口的管理方式（见记忆：本集群对外入口拓扑），应用目前只能用 NodePort 暴露。
- 一个 Gateway 最多 64 个 listener。按端口一个 listener 的做法很快会用完，而且端口号对外不友好。
- 环境：Istio 1.31.3，Gateway API v1.6.x。ListenerSet 已在 v1.5 进入 Standard channel。

## 3. 目标 / 非目标

**目标**
- 应用服务可以按「域名 + 路径」对外暴露 HTTP/HTTPS，也可以按「端口」暴露 TCP。
- 平台默认域名开箱即用；租户可以绑自己的域名。
- 暴露配置是应用期望配置的一部分，随发布、回滚、下线一起生效。
- 为灰度发布留好位置（第二期）。

**非目标**
- 不让租户直接编辑 Gateway / ListenerSet 的 YAML。
- 不迁移、不改动 Harbor 以外的遗留路由。它们原样保留在主 Gateway 上，以后单独清理。
- 第一期不做 mTLS 客户端证书、WAF、限流。

## 4. 对象模型

| 对象 | 谁维护 | 放哪 | 内容 |
|---|---|---|---|
| 主 Gateway `platform-ingress` | 平台，随 Istio 组件安装 | `istio-system` | Harbor listener、遗留端口 listener、TCP 端口池、`allowedListeners` |
| 通配证书 `apps-wildcard-tls` + ReferenceGrant | 平台 | `istio-system` | ReferenceGrant 允许 `ListenerSet` 引用这张 Secret（ReferenceGrant 不会从 Gateway 继承） |
| 命名空间 ListenerSet `platform-domains` | **平台按本命名空间在用的域名汇总生成** | 应用命名空间 | 每个对外域名一个 listener，`allowedRoutes.from: Same` |
| 应用 HTTPRoute `platform-app-<应用slug>-<服务>` | 平台在应用发布时生成 | 应用命名空间 | `parentRefs: kind: ListenerSet, sectionName: <listener名>`，按路径转发到服务端口；第二期在这里加权重 |
| 自定义域名证书 Secret | 租户上传（平台写入），或平台 ACME 签发 | 应用命名空间 | 被同命名空间的 ListenerSet 直接引用，不需要 ReferenceGrant |
| TCP 暴露 TCPRoute | 平台在应用发布时生成 | 应用命名空间 | 挂到主 Gateway 端口池里分配给它的 listener |

主 Gateway 示意：

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: platform-ingress, namespace: istio-system }
spec:
  gatewayClassName: istio
  allowedListeners:
    namespaces:
      from: Selector
      selector: { matchLabels: { platform.coding/ingress: "true" } }
  listeners:                      # minItems=1：Harbor 正好占这一位
    - { name: harbor, port: 443, protocol: HTTPS, hostname: harbor.ackcp.cloud,
        tls: { certificateRefs: [{ name: harbor-tls }] } }
    - { name: http-redirect, port: 80, protocol: HTTP }   # 统一 80→443 跳转
    # tcp-30001 … 端口池；legacy-* 遗留端口
```

命名空间 ListenerSet 示意：

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: ListenerSet
metadata:
  name: platform-domains
  namespace: shop-prod
spec:
  parentRef: { name: platform-ingress, namespace: istio-system }
  listeners:
    - name: d-1c9e                # 平台默认域名；listener 名一律取域名哈希
      port: 443
      protocol: HTTPS
      hostname: api-shop-t1.apps.ackcp.cloud
      tls: { certificateRefs: [{ name: apps-wildcard-tls, namespace: istio-system }] }
      allowedRoutes: { namespaces: { from: Same } }
    - name: d-7f3a                # 自定义域名
      port: 443
      protocol: HTTPS
      hostname: shop.customer.com
      tls: { certificateRefs: [{ name: platform-domain-7f3a }] }
      allowedRoutes: { namespaces: { from: Same } }
```

规则：
- **冲突裁决**：同端口同域名冲突时，主 Gateway 优先，然后是创建得早的 ListenerSet，最后按 `命名空间/名称` 字母序。
  落选的 listener 标 `Conflicted` 不生效。平台登记时已经保证唯一，所以出现 `Conflicted` 就说明有对象绕过了平台，要告警。
- **路由挂载**：HTTPRoute 挂主 Gateway 时如果不写 `sectionName`，只挂到主 Gateway 自己的 listener，
  所以应用路由一律挂 ListenerSet。
- **标签放行**：命名空间要带 `platform.coding/ingress=true` 才能挂上来。由平台在命名空间分配或启用时打标，租户改不了命名空间标签。
- **RBAC**：租户 RBAC 模板不给 `listenersets` 写权限，也不给 `istio-system` 下任何对象的写权限。
  HTTPRoute 的写权限现在已经开放，所以租户自建的路由仍然能挂到自己命名空间的 ListenerSet 上（`from: Same`）。
  这一点可以接受：域名是平台给的，路由只是在本命名空间里分流量。

## 5. 域名从哪来：平台如何知道租户用什么域名

ListenerSet 不让租户自己维护，所以**域名是租户在平台里登记的数据**，平台再根据这份数据生成 ListenerSet。

### 5.1 两类域名

| 类型 | 来源 | 校验 | 证书 |
|---|---|---|---|
| 平台默认域名 | 平台按规则生成 `<服务>-<应用>-<租户>.apps.ackcp.cloud`（超长时截断加哈希），不用登记 | 无，平台拥有 `*.apps.ackcp.cloud` | 平台通配证书 |
| 租户自定义域名 | 租户在「域名」页登记 `shop.customer.com` | 唯一性 + 归属验证（见下） | 租户上传，或平台 ACME 签发 |

### 5.2 自定义域名的登记流程

1. **登记**：租户填域名，选允许使用这个域名的命名空间。
   平台在库里检查全局唯一（含通配冲突，例如 `*.customer.com` 与 `a.customer.com`），并拒绝平台保留后缀
   `ackcp.cloud`。状态设为「待验证」。
2. **归属验证**：平台生成随机 token，租户在 DNS 加一条 TXT 记录 `_platform-verify.shop.customer.com = <token>`。
   平台按需或定时查询，查到后状态变为「已验证」。
   不做这一步的话，租户 A 可以登记租户 B 的域名，抢先占住 listener。
3. **指向入口**：租户把 `shop.customer.com` 用 CNAME 指到平台入口（如 `ingress.ackcp.cloud`），或用 A 记录指到入口 IP。
   平台只提示、不强制：DNS 没指过来时流量进不来，但不影响发布。
4. **证书**：二选一。
   - 租户上传 PEM。平台校验证书和私钥匹配、SAN 覆盖该域名、未过期，然后写成应用命名空间里的 Secret
     `platform-domain-<哈希>`，并记录到期时间，到期前告警。
   - ACME（第二期，需要 cert-manager 组件）：域名指向入口后，用 HTTP-01 签发和续期。
5. **使用**：在应用服务的「对外暴露」里只能选已验证、并且允许本命名空间使用的域名。

### 5.3 数据表（草案）

`platform_domain`：`id, tenant_id, domain, wildcard, verify_token, status(pending|verified|failed),
verified_at, cert_mode(upload|acme), cert_not_after, created_at, updated_at`，`domain` 唯一索引。

`platform_domain_namespace`：`domain_id, cluster_id, namespace`，表示哪些命名空间可以用这个域名。

不加外键，分页用 PageHelper。权限点为 `tenant:domain:*`，新权限行要显式绑定 admin 和 tenant_admin。

### 5.4 为什么不在 ListenerSet 上写了算

- 冲突裁决是先建者胜。谁能写 ListenerSet，谁就能抢别人的域名，在 K8s 里事后才发现。
- 归属验证和证书校验都是业务逻辑，k8s-server 不做业务，只能放在 api 层。
- 平台库里有了域名才能做：列表、到期告警、审计、反查「哪个应用在用这个域名」。

## 6. 应用侧配置与发布

### 6.1 期望配置

服务期望配置里的 `expose` 从现在的 `nodeport` 扩展为三种：

```jsonc
// HTTP（有端口的服务）
"expose": "http",
"routes": [
  { "domain": "default", "path": "/", "port": 8080 },              // 平台默认域名
  { "domain": "shop.customer.com", "path": "/api", "port": 8080 }  // 已登记的自定义域名
]
// TCP
"expose": "tcp"          // 平台从端口池分配一个对外端口，回填到状态里
// 现有
"expose": "nodeport", "nodePort": 30080
```

校验：
- 域名必须是已验证、并允许本命名空间使用的。
- 同一应用内 `域名 + 路径` 不重复；跨应用由平台库检查（同域名不同路径允许分给不同应用，各自一个 HTTPRoute）。
- 路径只支持前缀匹配。

为什么是每个命名空间一个 ListenerSet、而不是每个应用一个：同一个域名可以被同命名空间的多个应用按路径共用，
而一个域名只能有一个 listener。如果 listener 放在某个应用名下，这个应用下线时就会把别人的入口带走。
跨命名空间共用同一域名第一期不支持。

### 6.2 发布 / 回滚 / 下线

- 发布：应用的 HTTPRoute 和其他应用对象一样进快照、随勾选下发。命名空间的 `platform-domains` ListenerSet
  在发布前由平台重算并下发（只增不减，因为别的应用可能还在用）。
- 下线：删掉本应用的 HTTPRoute，然后重算 ListenerSet，去掉已经没有应用使用的域名。
- 回滚：HTTPRoute 跟着快照回到旧版本；如果旧版本用了已经删除或失效的域名，发布前校验会拦住并说明原因。
- 状态：概览展示每条路由的访问地址，以及 ListenerSet listener 和 HTTPRoute 的 `Accepted / Programmed / ResolvedRefs`。
  `Conflicted` 和 `RefNotPermitted` 原样显示原因。

### 6.3 依赖门禁

新增组件旗标 `platform-ingress`：主 Gateway 已安装并 `Programmed`，同时集群支持 ListenerSet CRD。
按现有「没装不让开、已开可关」规则拦截（`ComponentFeatureGuard` / `AppFeatureGate`）。

## 7. 灰度（第二期）

在同一个 HTTPRoute 上做，不需要额外的入口对象：

- 服务开启灰度后，平台多建一份 `<服务>-canary` Deployment 和 Service，用新镜像或新配置，副本数单独设。
- HTTPRoute 的 `backendRefs` 写 stable 和 canary 两个，按权重分（如 90 / 10）。
  也可以加一条 `matches.headers`（如 `x-canary: true`）的规则，只把带头的请求转给 canary。
- 操作：调整权重 → 全量（把 canary 的版本写回 stable，再删掉 canary）→ 或者回退（删掉 canary）。
  每一步都是一次发布记录。
- 东西向流量（服务之间调用）的灰度需要 L7 waypoint，复用应用设置里的网格 L7。入口灰度不依赖网格。

## 8. TCP 端口池

- 主 Gateway 上预建一段 TCP listener（如 `tcp-30001`…`tcp-30040`），每个 `allowedRoutes` 默认 `from: Same`，不放行任何命名空间。
  分配给某个命名空间时，平台把该 listener 改成按命名空间放行。
- 端口分配记在平台库里（`platform_ingress_port`：`cluster_id, port, namespace, app_id, service_key`）。
  应用下线时释放端口，回收后冷却一段时间再分配，避免客户端还连着旧服务。
- 为什么不放进 ListenerSet：ListenerSet 新增的端口能不能自动加到 Istio 网关 Service 上没有把握（§9 第 4 项验证）。
  先放在主 Gateway，端口池的容量受 64 个 listener 上限约束：64 减去 Harbor、80 和遗留端口，大约 40 个。
  不够时再评估迁到 ListenerSet，或者为 TCP 单独建第二个 Gateway。

## 9. 重建前必须真集群验证

在测试命名空间里用临时对象和测试域名验证，不动现有入口：

1. `istio` 网关类（Istio 1.31.3）接受 ListenerSet：`Accepted / Programmed` 为 True，并且 Gateway 的
   `status.attachedListenerSets` 能计数。1.30.1 的修复说明针对的是 agentgateway，普通 istio 类要实测。
2. 主 Gateway 的 Harbor listener 和多个 ListenerSet 共用 443：按 SNI 选对证书，同端口合并后路由正确。
3. ListenerSet 跨命名空间引用 `istio-system/apps-wildcard-tls`：有 ReferenceGrant 时可用，没有时报 `RefNotPermitted`。
4. ListenerSet 里新开的端口能否出现在网关 Service / Deployment 上（决定 §8 能否迁移）。
5. 用同一个域名建两个 ListenerSet，确认后建的那个被标为 `Conflicted`，平台能读到原因。

## 10. 入口重建步骤

1. 定下 §12 的未决项（主要是 LB 还是 NodePort、Harbor TLS 在哪里终止）。
2. 新建 `platform-ingress`（先用新的 NodePort 或 LB 地址，与旧入口并存）。
   把 Harbor listener 和 15 个遗留端口 listener 原样搬过去，11 条遗留路由的 `parentRefs` 指向新 Gateway。
3. 切 DNS：`harbor.ackcp.cloud`、`*.apps.ackcp.cloud` 指到新入口，观察一段时间。
4. 删除旧 `external-gateway`。
5. 平台侧：组件安装把 `platform-ingress` 纳入 Istio 组件管理；租户 RBAC 模板确认没有 `listenersets` 的写权限，
   改完后要「重下发全部模板」。

## 11. 分期

| 期 | 内容 |
|---|---|
| 一 | 主 Gateway 重建；平台默认域名 + HTTP 路由暴露；命名空间 ListenerSet 生成；状态展示；组件门禁 |
| 二 | 自定义域名登记（TXT 验证 + 上传证书）；TCP 端口池；入口灰度（权重 / header） |
| 三 | ACME 自动签发；东西向灰度（依赖 L7 waypoint）；遗留端口清理 |

## 12. 未决项

- Harbor 的 443 由谁终止 TLS、证书从哪来：现在挂在 external-gateway 上，搬迁时证书 Secret 要一起迁到 `istio-system`。
- 新入口用 LoadBalancer 还是 NodePort：NodePort 下 80/443 对外并不是 80/443，需要前面再有一层 LB 或端口转发。
- 自定义域名是否进第一期（当前放在第二期）。
- 平台默认域名的命名规则和长度截断规则（DNS 标签最长 63）。

## 参考

- [Gateway API ListenerSet](https://gateway-api.sigs.k8s.io/reference/api-types/listenerset/)
- [GEP-1713: ListenerSets](https://gateway-api.sigs.k8s.io/geps/gep-1713/)
- [Gateway API v1.5 发布说明](https://kubernetes.io/blog/2026/04/21/gateway-api-v1-5/)
- [Istio 1.30.1 发布说明](https://istio.io/latest/news/releases/1.30.x/announcing-1.30.1/)
