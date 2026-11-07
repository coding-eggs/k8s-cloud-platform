# 应用对外暴露（Istio 入口端口池 + 可选域名）与灰度 设计

> 状态：方案整理稿（2026-11-08），**尚未实现**。
> **第一步先做 §0 的 ListenerSet 验证。** 结果决定端口池走方案 A（ListenerSet）还是方案 B（分片 Gateway），
> §1、§5、§6.2、§9、§10 中标了「待 §0」的地方会随结果改写。
> 前置：应用模块去画布重做（`docs/development/application-release.md`），现只有 NodePort 暴露（§1.5）。

## 0. 先验证 ListenerSet（决定端口池方案）

端口池有两条路，都能满足「没有域名、按端口暴露、容量能放大」：

| | 方案 A：ListenerSet 端口池 | 方案 B：分片 Gateway 端口池 |
|---|---|---|
| 做法 | 每个命名空间一个平台生成的 ListenerSet `platform-ports`，分到的每个端口是其中一个 listener，挂到主 Gateway | 预建多个池 Gateway（每个 64 个端口），平台在库里分配，给命名空间打放行标签 |
| 容量 | 每个 ListenerSet 64 个，ListenerSet 个数不限（以实测的实际上限为准） | 每个分片 64 个，要更多就再加分片 |
| 资源 | 只有一组网关 Pod | 每个分片多一组网关 Pod |
| 分配端口时改什么 | 生成或更新 ListenerSet，还要更新固定 nodePort 的 ConfigMap | 只打命名空间标签，不改 Gateway 和 ConfigMap |
| 隔离 | 天然：listener 在租户自己的命名空间里，`allowedRoutes.from: Same` | 靠命名空间标签加 Selector |
| 依赖 | 普通 `istio` 网关类支持 ListenerSet，并且新端口能进 Service、能固定 nodePort | 只用现有机制，不需要额外验证 |

**倾向方案 A**（资源省、隔离更自然，域名方式也是同一套对象），前提是下面几项全部通过。只要有一项不通过，就走方案 B。

验证项（测试命名空间，临时对象，用一个独立的测试 Gateway，不碰 `external-gateway`）：
- A1. `istio` 网关类（Istio 1.31.3）接受 ListenerSet：ListenerSet 和其中每个 listener 都是 `Accepted / Programmed`，
  Gateway 的 `status.attachedListenerSets` 能正确计数。
- A2. ListenerSet 里的端口 listener 会**自动加到网关 Service 上**（HTTP 和 TCP 两种都要测）。
- A3. 新增的端口能**固定 nodePort 等于端口**（通过 `parametersRef` ConfigMap 或其他方式）。
  同时记录改 ConfigMap 时网关 Pod 会不会滚动、现有连接会不会断。
- A4. 删除 ListenerSet 或其中某个 listener 后，Service 端口能收回，路由被摘掉。
- A5. 容量：一个 Gateway 挂多个 ListenerSet、合计超过 64 个端口（例如 150 个）时仍然全部 `Programmed`。
- A6. 隔离：别的命名空间的 HTTPRoute 挂不上 `from: Same` 的 listener；主 Gateway 用 `allowedListeners: Selector`
  可以拒绝没打平台标签的命名空间建的 ListenerSet。
- A7. Harbor 的 443 listener 留在主 Gateway 上，跟 ListenerSet 共存时不受影响。

验证完成后，在本节补上结果和最终选定的方案，再改写下面标了「待 §0」的章节。

## 1. 结论（端口池部分待 §0）

- 这是私有云平台，用户多半**没有域名**，而且集群**没有 LB**。所以默认的暴露方式是**网关端口**：
  访问地址为 `http://<节点IP>:<端口>`，流量经过 Istio 入口网关，可以用 HTTPRoute 的 L7 能力，包括灰度。
- 重建一个平台管理的主 Gateway `istio-system/platform-ingress`，沿用现有入口的机制：
  NodePort 类型的 Service，用 `customnodeport` 固定 nodePort，按端口建 listener，HTTPRoute 按 `sectionName` 挂载。
- 主 Gateway 上**预建一段端口池**，`customnodeport` 一次性固定好对应的 nodePort。
  集群的 nodePort 范围已经放开到 1-65535，所以**listener 端口 = nodePort**，展示地址就是 `节点IP:端口`。
  平台只在库里分配端口，再下发 HTTPRoute 或 TCPRoute。日常发布不修改 Gateway，也不修改 ConfigMap。
- 端口池不够时，再建一个平台 Gateway。它有独立的 Deployment 和 Service，带自己的端口池。
- **域名是第二期的可选能力**：管理员给集群配置入口域名后缀后才出现。在 443 上按域名分流，
  用每个命名空间一个 ListenerSet 来承载域名 listener。没配域名的集群用不到 ListenerSet。
- 灰度（第二期）在应用的 HTTPRoute 上按权重或 header 分流，端口方式和域名方式都能用。

## 2. 现状

- 唯一的对外入口是 `istio-system/external-gateway`，由用户手工创建：
  - `gatewayClassName: istio`，注解 `networking.istio.io/service-type: NodePort`，
    `infrastructure.parametersRef` 指向 ConfigMap `customnodeport`，用来固定 nodePort。
  - 按端口建了 15 个 listener，`allowedRoutes.from: All`。挂着 11 条不带 hostname 的遗留 HTTPRoute，都是手工 `kubectl apply` 的。
  - Harbor：443 listener 在网关上终止 TLS，证书 Secret 是手工创建的，HTTPRoute 通过 `sectionName` 挂到这个 listener。
- apiserver 的 `service-node-port-range` 为 1-65535。
- 没有 LB。
- 平台看不见这个入口：`istio-system` 不在任何租户的分配表里，建 HTTPRoute 时父资源只能手填。
- 应用目前只能用 NodePort 暴露，不经过网关，没有 L7 能力。
- 环境：Istio 1.31.3，Gateway API v1.6.x。一个 Gateway 最多 64 个 listener。

## 3. 目标 / 非目标

**目标**
- 没有域名也能用：应用的 HTTP 服务经网关端口对外，TCP 服务也能经网关端口对外。
- 暴露配置是应用期望配置的一部分，随发布、回滚、下线一起生效，访问地址在概览里展示。
- 灰度发布（第二期）。
- 有内网 DNS 的集群可以按域名暴露（第二期）。

**非目标**
- 不让租户编辑 Gateway、ListenerSet、`customnodeport`。
- 遗留的 15 个端口和 11 条路由原样迁移，不改造。
- 不引入 LB（MetalLB 等）。
- 第一期不做 mTLS 客户端证书、WAF、限流。

## 4. 三种暴露方式

| 方式 | 地址 | 经过网关 | L7 / 灰度 | 期 |
|---|---|---|---|---|
| **网关端口**（默认） | `http://<节点IP>:<端口>` 或 `tcp://<节点IP>:<端口>` | 是 | HTTP 有，TCP 只有 L4 | 一 |
| 域名 | `https://<域名>`（443） | 是 | 有 | 二 |
| NodePort（保留现状） | `<节点IP>:<NodePort>` | 否 | 无 | 已有 |

「节点 IP」就是集群节点的 IP。NodePort 在每个节点上都能访问，概览里直接列出集群节点的 InternalIP，不需要另外配置对外地址。

## 5. 网关端口池（第一期核心）

> **待 §0**：本节按方案 B（分片 Gateway）写。§0 通过的话改写为方案 A：
> 每个命名空间一个 `platform-ports` ListenerSet；分配端口时生成 listener 并固定 nodePort；不再需要命名空间放行标签和池分片。
> §5.3 的端口段、§5.5 的 HTTPS 限制两个方案都适用。

### 5.1 主 Gateway

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: platform-ingress
  namespace: istio-system
  annotations: { networking.istio.io/service-type: NodePort }
spec:
  gatewayClassName: istio
  infrastructure:
    parametersRef: { group: "", kind: ConfigMap, name: platform-ingress-nodeport }  # 固定 nodePort = 端口
  listeners:
    - name: harbor                                  # 原样迁移
      port: 443
      protocol: HTTPS
      hostname: harbor.ackcp.cloud
      tls: { certificateRefs: [{ name: harbor-tls }] }   # 手工 Secret 复制到 istio-system
      allowedRoutes: { namespaces: { from: All } }
    # legacy-*：15 个遗留端口原样迁移
    - name: p20000                                  # 端口池：HTTP 段
      port: 20000
      protocol: HTTP
      allowedRoutes:
        namespaces:
          from: Selector
          selector: { matchLabels: { ingress.platform.coding/p20000: allowed } }
    # … p20002 …
    - name: p24000                                  # 端口池：TCP 段
      port: 24000
      protocol: TCP
      allowedRoutes:
        kinds: [{ kind: TCPRoute }]
        namespaces:
          from: Selector
          selector: { matchLabels: { ingress.platform.coding/p24000: allowed } }
```

### 5.2 分配不改 Gateway

- listener 的协议在预建时就定了，所以池分成 HTTP 段和 TCP 段，各段大小由管理员在初始化时决定。
  要调整比例，只需要改 Gateway 上 listener 的 `protocol`（端口不变，ConfigMap 不用改），这是管理员操作。
- **隔离靠命名空间标签**：每个池 listener 只接受带 `ingress.platform.coding/p<端口>=allowed` 标签的命名空间。
  平台把端口分给某个命名空间时给它打上这个标签，回收时去掉。这样分配端口不用改 Gateway，
  其他命名空间即使手写 HTTPRoute 挂过来也挂不上（Gateway API 原生拒绝，状态里能看到 `NotAllowedByListeners`）。
  前提是租户改不了命名空间标签（§9 验证）。
- 分配记录：`platform_ingress_port`（`cluster_id, gateway, port, protocol, namespace, app_id, service_key, status, released_at`），
  `(cluster_id, port)` 唯一。下线时释放，冷却 10 分钟后再分配，避免客户端还连着旧服务。

### 5.3 端口段的选择

nodePort 放开到 1-65535 以后，kube-proxy 会在**每个节点**上接管被分配的端口。所以：
- 端口池要避开节点上已有的服务端口（22、6443、2379-2380、10250、9100 等），以及遗留的 15 个端口。
  **整段 20000-25999 保留给端口池**：HTTP 用 20000-23999，TCP 用 24000-25999，管理员可以改。
  这只是保留的地址空间，真正建出来多少个 listener，由 §5.4 的分片决定。
- 现有的 NodePort 暴露（应用可以填固定 nodePort）也在同一个端口空间里。平台要拒绝应用的 NodePort 落进端口池段，
  并且同样拒绝上面那些保留端口。这一条要补到现有的 NodePort 校验里。

### 5.4 容量与扩容

- 一个 Gateway 最多 64 个 listener，这是 Gateway API 的硬上限。所以端口池要做大，就是**按分片建多个池 Gateway**：
  - `platform-ingress` 只放 Harbor、遗留端口和少量池端口（48 个以内）；
  - `platform-ingress-pool-1`、`-pool-2`…每个 64 个池端口，各自生成独立的 Deployment 和 Service，各配自己的 ConfigMap。
  - 初始化时默认建 2 个池分片（约 128 个端口，加上主 Gateway 共约 170 个）。可用端口低于 20% 时提示管理员扩一个分片。
- 分配表的 `gateway` 列记录端口在哪个 Gateway 上。这条路就是现有入口的机制，不需要额外验证。
- 代价：每个分片都多一组网关 Pod（Envoy），按 2 副本算，每个分片大约多 200m CPU / 256Mi 内存。
- 端口池只在初始化或扩容时改 Gateway 和 ConfigMap，会让网关 Pod 滚动一次，这是管理员的计划内操作。

### 5.5 HTTPS

端口方式没有域名，平台证书对不上，所以**网关端口只提供 HTTP 和 TCP**。
需要 HTTPS 的服务有两种做法：自己终止 TLS，走 TCP 段（TLS 透传）；或者等第二期用域名方式。

## 6. 应用侧配置与发布

### 6.1 期望配置

服务期望配置里的 `expose` 扩展为：

```jsonc
"expose": "gateway-http"   // 分配一个 HTTP 段端口，HTTPRoute 转发到服务端口
"expose": "gateway-tcp"    // 分配一个 TCP 段端口，TCPRoute 转发到服务端口
"expose": "nodeport", "nodePort": 30080   // 现有，不变
// 第二期："expose": "domain", "routes": [{ "domain": "...", "path": "/", "port": 8080 }]
```

- 端口**由平台分配，租户不能指定**。第一次保存时分配并锁定，之后的发布和回滚都沿用同一个端口，地址不变。
  改成不暴露或下线时释放。
- 一个服务对外一个端口（服务本来就只有一个端口）。

### 6.2 发布 / 回滚 / 下线（待 §0：方案 A 下「打标签」换成「更新 ListenerSet」）

- 发布：HTTPRoute `platform-app-<应用>-<服务>`（`parentRefs: platform-ingress, sectionName: p<端口>`）
  和其他应用对象一样进快照、随勾选下发。下发前确保命名空间已经打上该端口的放行标签。
- 下线：删掉路由，释放端口，去掉命名空间标签。
- 回滚：路由跟着快照回到旧版本，端口不变（端口锁定在服务上，不在快照里）。
- 概览：展示访问地址 `http://<节点IP>:<端口>`（列出各节点 IP），以及路由的 `Accepted / ResolvedRefs`。
  `NotAllowedByListeners` 这类原因原样显示。

### 6.3 依赖门禁

新增组件旗标 `platform-ingress`：平台 Gateway 已经 `Programmed`，并且端口池已经初始化。
按现有「没装不让开、已开可关」的规则拦截（`ComponentFeatureGuard` / `AppFeatureGate`）。

## 7. 域名方式（第二期，可选）

- 集群设置「入口域名后缀」（如 `apps.corp.local`）和对应的通配证书。前提是企业 DNS 已经把 `*.apps.corp.local` 解析到集群节点。
  **不配置，前端就不出现域名方式。**
- 平台默认域名 `<服务>-<应用>-<租户>.<后缀>`，挂在 443 上按 SNI / Host 分流。
- 自定义域名：租户提交申请，平台管理员审批。私有云的 DNS 通常在企业 IT 手里，租户加不了 TXT 记录，
  所以不做 DNS 验证。唯一性由平台库保证（`platform_domain`，`domain` 唯一）。证书由租户上传，平台校验 SAN 和到期时间。
- 承载：每个命名空间一个平台生成的 ListenerSet `platform-domains`，本命名空间在用的每个域名是其中一个 listener
  （`allowedRoutes.from: Same`）。应用自己的 HTTPRoute 挂到这个 ListenerSet 上。
  - 不按应用建 ListenerSet：同一个域名可以被同命名空间的多个应用按路径共用，listener 挂在某个应用名下，
    这个应用下线时会把别人的入口带走。
  - 主 Gateway 用 `allowedListeners: Selector` 只放行带平台标签的命名空间；租户 RBAC 模板不给 `listenersets` 写权限。
  - 同端口同域名冲突时，按「主 Gateway 优先 → ListenerSet 创建得早的优先 → 按名称字母序」裁决，落选的被标 `Conflicted`。
    平台已经保证唯一，所以出现 `Conflicted` 就要告警。
  - 跨命名空间引用 `istio-system` 下的通配证书需要 ReferenceGrant（ReferenceGrant 不会从 Gateway 继承）。
- ListenerSet 在 Gateway API v1.5 进入 Standard。普通 `istio` 网关类是否支持，要在第二期开始前实测（§9）。

## 8. 灰度（第二期）

- 服务开启灰度后，平台多建一份 `<服务>-canary` Deployment 和 Service，用新镜像或新配置，副本数单独设。
  **应用设置（链路追踪、服务网格）与 stable 完全一样**，否则 v1 / v2 的指标没法公平对比。
- 应用的 HTTPRoute 在 `backendRefs` 里写 stable 和 canary 两个后端，按权重分（如 90 / 10）。
  也可以加一条 `matches.headers`（如 `x-canary: true`），只把带这个头的请求转给 canary。
  端口方式和域名方式都可以用，访问地址不变。
- 操作：调整权重 → 全量（把 canary 的版本写回 stable，再删掉 canary）→ 或者回退（删掉 canary）。每一步都是一次发布记录。
- TCP 段只能按权重分连接，不能按 header 分。
- 服务之间调用的灰度需要 L7 waypoint，复用应用设置里的网格 L7。入口灰度不依赖网格。

## 9. 真集群验证（ListenerSet 端口池部分见 §0）

**第一期开始前**（在测试命名空间用临时对象，不碰现有入口）：
1. 池 listener 用 `allowedRoutes.namespaces.from: Selector`：打了标签的命名空间能挂上，
   没打标签的被拒（`NotAllowedByListeners`）；去掉标签后路由会被摘掉。
2. 租户的 RBAC 模板改不了命名空间标签。
3. 新 Gateway 加 `parametersRef` ConfigMap 后，nodePort 等于 listener 端口；和旧入口并存时没有端口冲突。
4. TCP 段：TCPRoute 挂上后能连通。TCPRoute 长期在 Experimental channel，要确认集群装了它的 CRD 以及具体版本。

**第二期开始前**：
5. 普通 `istio` 网关类接受 ListenerSet（`Accepted / Programmed`），和 Harbor 一起共用 443 时按 SNI 选证书正确。
6. ListenerSet 跨命名空间通过 ReferenceGrant 引用通配证书。
7. 两个 ListenerSet 写同一个域名时，后建的那个被标为 `Conflicted`。

## 10. 入口重建步骤（待 §0：方案 A 不建池分片）

1. 新建 `platform-ingress`（含 ConfigMap、Harbor listener、遗留 15 个 listener、端口池）。
   遗留端口和 443 跟旧入口重复，所以**不能并存**：先把新 Gateway 的端口池部分建好验证，再在维护窗口做切换。
2. 切换：删除旧 `external-gateway` → 新 Gateway 补上 Harbor 和遗留 listener → 把 11 条遗留路由和 Harbor 路由的
   `parentRefs` 改指 `platform-ingress`（sectionName 保持原名，原样迁移时 listener 名不变）。
3. 验证 Harbor 和遗留端口都能访问。
4. 平台侧：Istio 组件安装把 `platform-ingress` 纳入管理；平台 Gateway 默认对租户只读可见，
   这样平台里建 HTTPRoute 时能选到父资源，补上现在父资源只能手填的盲区。

## 11. 分期

| 期 | 内容 |
|---|---|
| 一 | 入口重建；端口池（HTTP / TCP 段）；应用「网关端口」暴露；访问地址与状态展示；NodePort 保留端口校验；组件门禁 |
| 二 | 入口灰度（权重 / header）；域名方式（后缀 + 默认域名 + 自定义域名审批 + ListenerSet） |
| 三 | 服务之间调用的灰度（依赖 L7 waypoint）；遗留端口清理；端口池扩容到第二个 Gateway 的界面化 |

## 12. 未决项

- 确认节点上 20000-25999 没有被其他服务占用。
- 已定：对外地址就是集群节点；canary 的应用设置与 stable 一样；端口池按分片 Gateway 放大。

## 参考

- [Gateway API ListenerSet](https://gateway-api.sigs.k8s.io/reference/api-types/listenerset/)
- [GEP-1713: ListenerSets](https://gateway-api.sigs.k8s.io/geps/gep-1713/)
- [Gateway API v1.5 发布说明](https://kubernetes.io/blog/2026/04/21/gateway-api-v1-5/)
- [Istio 1.30.1 发布说明](https://istio.io/latest/news/releases/1.30.x/announcing-1.30.1/)
