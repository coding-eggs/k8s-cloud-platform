# 应用对外暴露（Istio 入口 + ListenerSet 端口池 + 可选域名）与灰度 设计

> 状态：方案稿（2026-11-08），**尚未实现**。§0 的真集群验证已完成，结论是采用方案 A（ListenerSet 端口池）。
> 前置：应用模块去画布重做（`docs/development/application-release.md`），现只有 NodePort 暴露（§1.5）。

## 0. ListenerSet 验证结果（2026-11-08，coding-cluster）

用临时命名空间 `lsprobe-*` 建了两个测试网关，各验证一种模式：一个是 Istio 自动部署的网关，一个是和生产一样的手动部署网关（Service 自建、`spec.addresses` 绑定）。
验证完已全部删除，生产的 `external-gateway` 在验证前后都是 `Programmed=True`。

| # | 验证项 | 结果 |
|---|---|---|
| A1 | `istio` 网关类（Istio 1.31.3，Gateway API bundle v1.6.2）接受 ListenerSet | ✅ ListenerSet 和其中的 listener 都是 `Accepted / Programmed`，Gateway `attachedListenerSets` 计数正确 |
| A2 | ListenerSet 的端口能用 | ✅ HTTP 和 TCP 都打通。自动部署模式下 Istio 会把端口加到网关 Service 上；手动部署模式下不会改 Service，见 A3 |
| A3 | 固定 nodePort 等于端口 | ✅ 手动部署模式：Service 预先声明 `nodePort: 20011`，之后再给 ListenerSet 加 `p20011`，本机直接访问 `节点IP:20011` 返回 200 |
| A3' | 端口没在 Service 上预先声明 | ⚠️ listener 自己显示 `Programmed=True`，但**整个 Gateway 变成 `Programmed=False（AddressNotUsable）`**。别的端口照常通，状态却全挂红。**所以平台只能分配 Service 上已经声明的端口** |
| A4 | 删除 listener 或 ListenerSet | ✅ 自动部署模式下 Service 端口会收回；路由变成 `NoMatchingParent`，端口不再通 |
| A5 | 容量 | ✅ 一个 Gateway 挂了 2 个 ListenerSet，各 60 个 listener，加上自身，合计 122 个，全部 `Programmed`，Envoy 实际监听了 120 个端口 |
| A6 | 隔离 | ✅ 没打放行标签的命名空间建 ListenerSet，被拒为 `NotAllowed`；别的命名空间的 HTTPRoute 挂到 `from: Same` 的 listener，被拒为 `NotAllowedByListeners` |
| A7 | Gateway 自身的 HTTPS 和 ListenerSet 的 HTTPS 共用一个端口 | ✅ 同一个 20443 端口，按 SNI 分别返回各自的证书，各自路由到各自的后端（模拟 Harbor 443 的场景） |
| A8 | 冲突 | ✅ 后建的 ListenerSet 用了相同端口：同协议时报 `HostnameConflict`，不同协议时报 `ProtocolConflict`，都被标成 `Conflicted`。先建的那个继续正常工作 |
| A9 | 加减 listener 会不会让网关 Pod 重启 | ✅ 整个验证过程中两个网关 Pod 都没有重启 |

另外核实到的几件事：

- **生产入口实际是手动部署模式**：`istio-system/external-gateway` 这个 Gateway 带着 `platform.coding/component: istio` 和
  `platform.coding/managed: "true"` 标签，并用 `spec.addresses` 绑定到 helm chart `gateway-1.31.3`（release `external-gateway`）建的 Service。
  Service 的端口和 nodePort 都写在 helm values 里，nodePort 等于端口，比如 `80→80`、`443→443`。
  旧记忆里说的 `customnodeport` ConfigMap 已经不存在了。
- TCPRoute 有 `v1`（bundle v1.6.2 experimental channel），用 `v1alpha2` 会提示已弃用。所以 TCPRoute **用 v1**。
- **nodePort 范围是 1-65535，Service 不写 nodePort 时会随机分配**。验证中一个没写 nodePort 的端口就被分到了 1340。
  现有的 `monitor/platform-tempo-nodeport`（21746）和 `platform-system/platform-redis-nodeport`（25358）也是随机分到的。
  这意味着任何 NodePort Service 都可能抢走端口池里的端口，甚至抢到节点上的服务端口。见 §5.3。

## 1. 结论

- 这是私有云，用户多半**没有域名**，集群也**没有 LB**。所以默认的暴露方式是**网关端口**：
  访问地址是 `http://<节点IP>:<端口>`，流量经过 Istio 入口网关，能用 HTTPRoute 的 L7 能力，包括灰度。
- **不重建入口**：直接在现有的、已经由平台管理的 `external-gateway` 上加。Gateway 上补 `allowedListeners`；
  helm values 里给网关 Service 预先声明一段端口池（nodePort 等于端口）。原有的 listener 和路由都不动，也不需要停机切换。
- **每个命名空间一个平台生成的 ListenerSet `platform-ingress`**，分到的每个端口是其中一个 listener，listener 的协议在分配时决定（HTTP 或 TCP）。
  应用只生成自己的 HTTPRoute 或 TCPRoute，挂到这个 ListenerSet 上。
- 64 个 listener 的上限只限制单个 Gateway 或单个 ListenerSet，ListenerSet 的数量不限。
  实际容量由 Service 预先声明了多少个端口决定，扩容就是管理员改一次 helm values。
- **域名是第二期的可选能力**：管理员给集群配置入口域名后缀后才出现。域名 listener 也放进同一个命名空间 ListenerSet，在 443 上按 SNI 分流（A7 已验证）。
- 灰度（第二期）在应用的 HTTPRoute 上按权重或 header 分流，端口方式和域名方式都能用；canary 的应用设置和 stable 一样。

## 2. 现状

- `istio-system/external-gateway`：`gatewayClassName: istio`，手动部署模式，Service 来自平台 Istio 组件安装的 helm release，类型是 NodePort。
- Gateway 上按端口建了 listener（`http2:80`、`harbor-https:443`、`platform-auth:9527`、`kibana:5601`、`elasticsearch:9200`、
  `thanos:19090` 等），都是 `allowedRoutes.from: All`。
  Harbor 的 TLS 在这个网关上终止（`harbor-tls` 是手工建的 Secret），HTTPRoute 按 `sectionName` 挂上来。
- 节点：master `192.168.85.180`、node1 `.161`、node2 `.162`。
- 应用目前只能用 NodePort 暴露，不经过网关，没有 L7 能力。
- 平台看不见这个入口：`istio-system` 不在任何租户的分配表里，建 HTTPRoute 时父资源只能手填。

## 3. 目标 / 非目标

**目标**
- 没有域名也能用：应用的 HTTP 和 TCP 服务都能经网关端口对外。
- 暴露配置是应用期望配置的一部分，随发布、回滚、下线一起生效，访问地址在概览里展示。
- 灰度发布（第二期）。
- 有内网 DNS 的集群可以按域名暴露（第二期）。

**非目标**
- 不让租户编辑 Gateway、ListenerSet、网关 Service。
- 现有的 listener 和路由不迁移、不改造。
- 不引入 LB（MetalLB 等）。
- 第一期不做 mTLS 客户端证书、WAF、限流。

## 4. 三种暴露方式

| 方式 | 地址 | 经过网关 | L7 / 灰度 | 期 |
|---|---|---|---|---|
| **网关端口**（默认） | `http://<节点IP>:<端口>` 或 `tcp://<节点IP>:<端口>` | 是 | HTTP 有，TCP 只有 L4 | 一 |
| 域名 | `https://<域名>`（443） | 是 | 有 | 二 |
| NodePort（保留现状） | `<节点IP>:<NodePort>` | 否 | 无 | 已有 |

「节点 IP」就是集群节点的 IP。NodePort 在每个节点上都能访问，所以概览直接列出集群节点的 InternalIP。

## 5. 网关端口池（第一期核心）

### 5.1 网关侧（管理员一次性配置，平台 Istio 组件管理）

Gateway 增加 `allowedListeners`，其余不变：

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: external-gateway, namespace: istio-system }
spec:
  gatewayClassName: istio
  addresses: [{ type: Hostname, value: external-gateway.istio-system.svc.cluster.local }]
  allowedListeners:
    namespaces:
      from: Selector
      selector: { matchLabels: { ingress.platform.coding/ports: "true" } }
  listeners: [ … 现有 listener 原样保留 … ]
```

helm values 里，网关 Service 预先声明端口池，nodePort 等于端口：

```yaml
service:
  ports:
    # … 现有端口 …
    - { name: pool-20000, port: 20000, targetPort: 20000, nodePort: 20000 }
    # … 一直到 pool-20999
```

- **池端口不区分协议**：Service 端口都是 TCP，listener 写 HTTP 还是 TCP 在分配时决定。
- 这些端口在 Envoy 上平时不监听：没有对应的 listener 时，连接会被拒绝。
- 扩容（加端口）只改 Service，不重启网关 Pod。
- 平台在分配时以**网关 Service 实际声明的端口**为准。表单里配置的范围和 Service 实际声明的端口对不上时报警。

**Istio 组件表单的改动**（「编辑」对话框，external-gateway release）：
- 现有的「端口列表」不动。它的每一行同时生成 Service 端口和 Gateway listener，用来放 Harbor、80 和遗留端口。
- 新增一个「应用端口池」，**只需要一个范围**（起始端口、结束端口两个输入框），默认 `20000-20999`（1000 个），留空表示不启用。
  不按 HTTP / TCP 分成两个范围，因为协议是在分配时写到 ListenerSet 的 listener 上的，同一个池端口哪种协议都能用。
- 渲染 helm values 时，平台把这个范围展开成 Service 端口（`name: pool-<端口>`、`nodePort` 等于端口、不生成 listener），
  接在端口列表后面写进 `service.ports`。范围本身单独存成一个可编辑值，例如 `platform.ingressPortPool: {from, to}`，
  这样表单里显示的是范围，而不是展开后的 300 行。
- 保存时的校验：
  - 范围不能和端口列表里的 port / nodePort 重叠；
  - 不能和集群里其他 Service 已经占用的 nodePort 重叠（保存前读一次）；
  - 范围大小不超过 5000。
    Envoy 只为已经分配的端口建 listener，所以范围大小不影响它；但 kube-proxy 会为每一个声明的端口在每个节点上生成转发规则。
    范围超过 1000 时，表单要提示「上线前确认 kube-proxy 的模式（iptables / ipvs / nftables）和规则同步耗时」。
  - **缩小范围或停用时，如果有已分配的端口落在被删掉的那部分里，就拒绝保存**，并列出占用这些端口的应用。
- 改范围只改 Service，不重启网关 Pod。

### 5.2 命名空间侧（平台在分配端口时生成）

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: ListenerSet
metadata:
  name: platform-ingress
  namespace: shop-prod            # 命名空间上要有 ingress.platform.coding/ports=true（平台打）
  labels: { platform.coding/managed: "true" }
spec:
  parentRef: { name: external-gateway, namespace: istio-system }
  listeners:
    - { name: p20001, port: 20001, protocol: HTTP, allowedRoutes: { namespaces: { from: Same } } }
    - { name: p20002, port: 20002, protocol: TCP,  allowedRoutes: { namespaces: { from: Same } } }
```

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: platform-app-shop-api, namespace: shop-prod }
spec:
  parentRefs: [{ group: gateway.networking.k8s.io, kind: ListenerSet, name: platform-ingress, sectionName: p20001 }]
  rules: [{ backendRefs: [{ name: api, port: 8080 }] }]
---
apiVersion: gateway.networking.k8s.io/v1      # TCPRoute 用 v1
kind: TCPRoute
metadata: { name: platform-app-shop-db, namespace: shop-prod }
spec:
  parentRefs: [{ group: gateway.networking.k8s.io, kind: ListenerSet, name: platform-ingress, sectionName: p20002 }]
  rules: [{ backendRefs: [{ name: db, port: 5432 }] }]
```

- **为什么每个命名空间一个 ListenerSet，而不是每个应用一个**：第二期的域名 listener 要被同一命名空间里的多个应用按路径共用。如果把它挂在某个应用名下，那个应用下线就会把别人的入口带走。端口 listener 也放在一起，这样一个命名空间只有一个平台对象。
- 一个 ListenerSet 最多 64 个 listener。一个命名空间超过 64 个时，平台再生成 `platform-ingress-2`（实际很少用到）。
- **端口分配记在平台库**，端口全局唯一：`platform_ingress_port`（`cluster_id, port, namespace, app_id, service_key, protocol, status, released_at`），
  `(cluster_id, port)` 唯一。下线时释放，冷却 10 分钟后才能再分配，避免客户端还连着旧服务。
- **只分配 Service 上已经声明的端口**（A3'）：分配前先读网关 Service 核对。否则整个 Gateway 会变成 `AddressNotUsable`。
- 隔离有三层：
  1. 平台库保证一个端口只分给一个命名空间；
  2. 主 Gateway 用 `allowedListeners` 只接受打了平台标签的命名空间；
  3. listener 用 `from: Same`，别的命名空间的路由挂不上来。

  如果有人绕过平台建了同端口的 listener，Gateway API 让先建的那个生效，后建的标 `Conflicted`（A8），已经在跑的应用不受影响。平台发现 `Conflicted` 就告警。
- RBAC：租户 RBAC 模板不给 `listenersets` 写权限，也不给命名空间 `patch` 权限（实现时核对现有模板）。

### 5.3 端口段与随机 nodePort 风险

nodePort 范围是 1-65535，所以：
- **池端口必须在 Service 里预先声明**。预先声明就等于提前占住这些 nodePort，别的 Service 就随机不到它们了。
- 池范围要避开节点上的服务端口（22、6443、2379-2380、10250 等），以及集群里已经占用的 nodePort。
  默认的 `20000-20999` 已经核对过，当前没有被占用（这一带已占用的是 21746、23646、25358，都在默认范围之外）。
- **任何不写 nodePort 的 NodePort Service，都可能随机落到低端口**。验证里就随机到过 1340，理论上也可能撞上节点上的服务端口。
  平台的「NodePort 暴露」要做两件事：
  - 填了固定 nodePort 的：拒绝落进池段的，也拒绝落进节点保留端口的。
  - 没填的：由平台在一个安全区间里替它选一个写进去，不交给 K8s 随机分配。

  这一条与端口池无关，属于现有功能的缺陷，单独修。

### 5.4 HTTPS

端口方式没有域名，平台证书对不上，所以**网关端口只提供 HTTP 和 TCP**。
需要 HTTPS 的服务有两种做法：自己终止 TLS，走 TCP 透传；或者用第二期的域名方式。

## 6. 应用侧配置与发布

### 6.1 期望配置

服务期望配置里的 `expose` 扩展为：

```jsonc
"expose": "gateway-http"   // 分配一个池端口，listener 协议 HTTP，生成 HTTPRoute
"expose": "gateway-tcp"    // 分配一个池端口，listener 协议 TCP，生成 TCPRoute
"expose": "nodeport", "nodePort": 30080   // 现有，不变
// 第二期："expose": "domain", "routes": [{ "domain": "...", "path": "/", "port": 8080 }]
```

- 端口**由平台分配，租户不能指定**。第一次保存时分配并锁定，之后的发布和回滚都沿用同一个端口，地址不变。
  改成不暴露或下线时释放。
- 一个服务对外一个端口（服务本来就只有一个端口）。

### 6.2 发布 / 回滚 / 下线

- 发布：
  1. 先确保命名空间带 `ingress.platform.coding/ports=true` 标签。
  2. 重算本命名空间 ListenerSet 的 listener 列表（来源是分配表里本命名空间的有效端口）并下发。
  3. 应用的 HTTPRoute 或 TCPRoute 和其他应用对象一样进快照、随勾选下发。
- 下线：删掉路由 → 释放端口 → 重算 ListenerSet（删掉这个 listener）。命名空间里没有端口了，就删掉 ListenerSet、去掉标签。
- 回滚：路由跟着快照回到旧版本。端口锁定在服务上、不在快照里，所以回滚不换端口。
- ListenerSet 是命名空间级的共享对象，不进任何应用的快照，由平台按分配表**重算后整体下发**（SSA，平台独占这个对象）。
- 概览展示：
  - 访问地址 `http://<节点IP>:<端口>`，列出各节点 IP；
  - listener 的 `Programmed / Conflicted`；
  - 路由的 `Accepted / ResolvedRefs`。

  `NotAllowedByListeners`、`HostnameConflict`、`AddressNotUsable` 这类原因原样显示。

### 6.3 依赖门禁

新增组件旗标 `platform-ingress`：入口 Gateway 已经 `Programmed`、带 `allowedListeners`，并且 Service 上声明了端口池。
按现有「没装不让开、已开可关」的规则拦截（`ComponentFeatureGuard` / `AppFeatureGate`）。

## 7. 域名方式（第二期，可选）

- 集群设置「入口域名后缀」（如 `apps.corp.local`）和对应的通配证书，前提是企业 DNS 已经把 `*.apps.corp.local` 解析到集群节点。
  **不配置，前端就不出现域名方式。**
- 平台默认域名 `<服务>-<应用>-<租户>.<后缀>`，挂在 443 上按 SNI / Host 分流。Harbor 继续留在 Gateway 自己的 443 listener 上（A7 已验证可以共存）。
- 自定义域名：租户提交申请，平台管理员审批。私有云的 DNS 通常在企业 IT 手里，租户加不了 TXT 记录，所以不做 DNS 验证。
  唯一性由平台库保证（`platform_domain`，`domain` 唯一）。证书由租户上传，平台校验 SAN 和到期时间。
- 承载：放进命名空间的同一个 ListenerSet `platform-ingress`，每个在用的域名一个 HTTPS listener（`port: 443`，带 hostname）。
  默认域名引用 `istio-system` 的通配证书，需要 ReferenceGrant，而且 ReferenceGrant 不会从 Gateway 继承；自定义域名的证书放在本命名空间，不需要 ReferenceGrant。
- 第二期开始前还要补验两项：跨命名空间引用证书走 ReferenceGrant；Harbor 的 443 listener 和 ListenerSet 的 443 共存时（A7 是在 20443 上模拟的），Harbor 不受影响。

## 8. 灰度（第二期）

- 服务开启灰度后，平台多建一份 `<服务>-canary` Deployment 和 Service，用新镜像或新配置，副本数单独设。
  **应用设置（链路追踪、服务网格）与 stable 完全一样**，否则 v1 / v2 的指标没法公平对比。
- 应用的 HTTPRoute 在 `backendRefs` 里写 stable 和 canary 两个后端，按权重分（如 90 / 10）。
  也可以加一条 `matches.headers`（如 `x-canary: true`），只把带这个头的请求转给 canary。端口方式和域名方式都能用，访问地址不变。
- 操作：调整权重 → 全量（把 canary 的版本写回 stable，再删掉 canary）→ 或者回退（删掉 canary）。每一步都是一次发布记录。
- TCP 只能按权重分连接，不能按 header 分。
- 服务之间调用的灰度需要 L7 waypoint，复用应用设置里的网格 L7。入口灰度不依赖网格。

## 9. 上线步骤（不停机）

1. 修复 NodePort 随机分配的风险（§5.3 末尾），并检查现有的 NodePort Service 有没有落在池段里。
2. 改平台 Istio 组件：
   - helm values 给网关 Service 加端口池；
   - Gateway 加 `allowedListeners`；
   - 租户 RBAC 模板确认没有 `listenersets` 写权限和命名空间 `patch` 权限，改完后要「重下发全部模板」。
3. 平台入口 Gateway 对租户只读可见，这样平台里手工建 HTTPRoute 时能选到父资源，补上父资源只能手填的盲区。
4. 现有 listener 和路由不动，所以没有切换窗口。

## 10. 分期

| 期 | 内容 |
|---|---|
| 一 | 端口池（Service 预声明 + 命名空间 ListenerSet + 分配表）；应用「网关 HTTP / TCP」暴露；访问地址与状态展示；NodePort 端口校验修复；组件门禁 |
| 二 | 入口灰度（权重 / header）；域名方式（后缀 + 默认域名 + 自定义域名审批） |
| 三 | 服务之间调用的灰度（依赖 L7 waypoint）；遗留 listener 清理 |

## 11. 未决项

> **第一期已实现（未部署）**，2026-11-08，main 上 `f08adf1..`（计划 `docs/superpowers/plans/2026-11-08-app-gateway-port-pool.md`）。
> 与本文的出入：ListenerSet / Service 端口端点放在 k8s-server `/application/**`（UPSTREAM）而不是 `/components/**`
> （租户 token 会 403）；已上线服务改离网关方式时端口在发布时释放而不是保存时；§9 第 1 步 NodePort 随机分配的修复未做。

- 已定：端口池默认 `20000-20999`（1000 个），上限 5000。
- 已核实（2026-11-08）：kube-proxy 是 ipvs 模式，几千个 Service 端口没有问题。
- 实现计划：`docs/superpowers/plans/2026-11-08-app-gateway-port-pool.md`。
- 已定：对外地址就是集群节点；canary 的应用设置与 stable 一样；端口池走 ListenerSet（§0 已验证）。

## 参考

- [Gateway API ListenerSet](https://gateway-api.sigs.k8s.io/reference/api-types/listenerset/)
- [GEP-1713: ListenerSets](https://gateway-api.sigs.k8s.io/geps/gep-1713/)
- [Gateway API v1.5 发布说明](https://kubernetes.io/blog/2026/04/21/gateway-api-v1-5/)
- [Istio 1.30.1 发布说明](https://istio.io/latest/news/releases/1.30.x/announcing-1.30.1/)
