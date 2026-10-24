# Istio（集群组件，含 Gateway API）

> **这是组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> Istio 不是单个 chart：`gateway-api`、`istio-base`、`istio-istiod`、`istio-cni`、`istio-ztunnel`、
> `istio-gateway` 六个 release 组成一个安装单元，本文放在 `istio-base` 下作为**组件总文档**。
> 本次安装的 chart 版本与来源见版本目录：`<version>/README.md`（当前 `1.30.6/`；
> 每个 chart 目录另有自己的 `provenance.yaml`）。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `istio` |
| 显示名 | Istio |
| 画像 | BUSINESS / MANAGEMENT |
| release 顺序 | `platform-gateway-api`(kube-system) → `istio-base` → `istiod` → `istio-cni` → `ztunnel` → `external-gateway`（后五个在 `istio-system`） |
| 探测判据 | `istio-system` Deployment 镜像 `istio.io/release/pilot`（或 `istio/pilot`），name 快路径 `istiod` |
| 平台功能 | `service-mesh`、`ambient-mesh`、`routes` |
| 对外入口 | Gateway `istio-system/external-gateway`（名字固定，集群对外拓扑依赖它） |
| 默认数据面 | ambient（cni + ztunnel），不装 sidecar 注入 |

### 1.1 版本声明（跨版本规则）

组件页的版本只认**主 release**（组件本体 = `istiod`），其余是随附依赖；**每个 chart 当前钉的版本**见
版本目录 `1.30.6/README.md`。

- 旧实现取「第一个 release」当组件版本，合并 Gateway API 后 Istio 会显示成 **1.6.2**（用户 2026-10-20 指出）；
- 现在 `istiod` 显式标了 `asPrimary()`，`availableVersions` / 升级目标 / 操作记录都只认它；
- 编辑表单的「当前生效值」会遍历所有 release 的快照取值（istiod 的副本/资源、external-gateway 的
  Service/端口分散在不同 release 里，只读第一个会全部回退默认值）。

**探测只认 istiod**：不把 Gateway API 的 CRD 判据挂到组件上 —— 多判据是 ANY-命中，
istiod 被删、CRD 残留时会把整个组件判成「已安装」。Gateway API 是否可用由 `MeshService`
读 `k8s_cluster.capability` 的 group 版本回答（`hasGatewayApi = gatewayVersions 非空`）。

### 1.2 平台里用到它的地方

- **网格状态横幅**：istio / ambient / gateway-api 三个能力标签；
- **工作负载 / 命名空间的 ambient 开关**：`dataplane-mode`、`use-waypoint` 标签与流量接管；
- **网关 / 路由页面**：Gateway、HTTPRoute、GRPCRoute、TCPRoute、TLSRoute、UDPRoute 的增删改；
- **对外暴露**：入口就是 `external-gateway`（§5），路由通过 parentRefs 挂到它；
- **可观测**：安装收尾下发 `Telemetry platform-tracing-ingress`，span 经 `platform-otel`
  provider 进 Tempo（collector 不在只丢 span，不影响流量）。

## 2. 安装前置与安装顺序

**集群要求**

- Gateway API 的 CRD 由本组件第一个 release 安装，不需要手工预装；
- ambient 依赖节点上的 CNI 与内核能力（iptables/nft、`net.ipv4.ip_unprivileged_port_start`）；
- `external-gateway` 默认 `service.type=LoadBalancer`。集群没有 LB 实现时 `EXTERNAL-IP` 会一直 pending，
  实际入口是节点 IP + NodePort —— 安装表单里改成 `NodePort` 更直观。

**顺序为什么固定**

`platform-gateway-api` 必须最先（istiod/cni/ztunnel 的模板与 Gateway CR 都依赖这些 CRD）；
`external-gateway` 放最后（它要用 istiod 的网关控制器，且平台随后下发 Gateway CR 与 Telemetry）。
多个 release 顺序执行、失败即停（k8s-server 侧），失败后修好配置重试即可。

**存量集群**：之前单独装过 `gateway-api` 组件的集群，`platform-gateway-api` release 已存在；
这次升级 Istio 时 helm `upgrade --install` 会接管同一 release，不会重复安装。

## 3. 契约 values（🔒 不可改）

| release | 关键契约 | 为什么 |
|---|---|---|
| `platform-gateway-api` | 无（experimental CRD chart，版本见版本目录） | TCP/TLS/UDP Route 只在 experimental 通道里 |
| `istio-base` | 无（CRD 在 templates 里，走 `istio.labels`） | CRD 由 chart 自管理；Istio 官方推荐用模板升级 CRD |
| `istiod` | `global.meshID=platform-mesh`；`pilot.env.PILOT_ENABLE_AMBIENT=true`；`meshConfig.defaultConfig.proxyMetadata.ISTIO_META_ENABLE_HBONE=true`；`meshConfig.enableTracing=true` + `extensionProviders[platform-otel]` 指向 `platform-otel-gateway-collector.monitor.svc:4317`；不设 `revision` | meshID 全局一致（装完再改是破坏性操作）；ambient 必须由 istiod 显式开启，否则 ztunnel 连 15012 被拒（§6）；HBONE metadata 给 waypoint（Envoy）；ingress/waypoint 的 `Telemetry` 引用该 provider；不做按 revision 的灰度 |
| `istio-cni` | `ambient.enabled=true` | 平台默认 ambient |
| `ztunnel` | 无 | 由 cni/istiod 的 ambient 开关驱动 |
| `external-gateway` | `name=external-gateway` | 集群对外入口名固定（网关对象、文档、平台 Telemetry 都按它引用） |

## 4. 可编辑配置（安装 / 编辑表单）

### 4.1 istiod

| 字段 | 默认 | 说明 |
|---|---|---|
| `replicaCount` | 1 | 副本数 |
| `autoscaleEnabled` | true | 自动扩缩容（开启时副本数由 HPA 决定） |
| `resources` | requests 100m/256Mi，limits 1/1Gi | 四格表单（requests/limits × cpu/memory），空值回落到平台默认 |

`meshID`、`revision` 有意不进表单：改 meshID 是破坏性操作，revision 与平台"重放即升级"的模型冲突。

### 4.2 external-gateway 的 Service / Gateway

`service.type`：下拉，`LoadBalancer`（默认）/ `NodePort` / `ClusterIP`。

`service.ports`：表格，**一行同时描述 Service 端口和 Gateway listener**：

```
名称 | 端口 | targetPort | 服务协议 | nodePort | 监听协议 | TLS 模式 | 证书 Secret
```

| 列 | 说明 |
|---|---|
| 端口 / targetPort | Service 的 port/targetPort；**listener 的端口取 targetPort**（Envoy 实际监听端口），不是对外 port |
| 服务协议 | K8s Service 的 TCP / UDP / SCTP |
| nodePort | 只有 NodePort 场景填；留空由 K8s 分配，**平台不校验范围**（由集群的 `--service-node-port-range` 决定） |
| 监听协议 | `HTTP` / `HTTPS` / `TLS` / `TCP` / `UDP`；**空 = 仅 Service，不生成 listener**（如 15021 status-port） |
| TLS 模式 | `Terminate` / `Passthrough`；只有 `TLS` 协议可选。**`HTTPS` 只能是 Terminate**（Gateway API 定义：HTTPS = 网关解密后按 HTTP 路由）；`Terminate` 必须选证书，`Passthrough` 不挂证书 |
| 证书 Secret | 从 `istio-system` 的 Secret 下拉选（拿不到列表时可手输）。Secret 必须已存在，否则 listener 一直未编程 |

默认三行：`status-port 15021`（仅 Service）、`http2 80`（HTTP）、`https 443`（`TLS + Passthrough`，免证书）。
要终止 TLS 就把 443 改成 `HTTPS`（或 `TLS + Terminate`）并选证书；要透传就用 `TLS + Passthrough`。

**表单细节（都是踩过的坑）**

- `service.ports` 是 list，Helm 合并**整体替换** —— 所以整组端口由平台显式声明，表单提交的也是整组；
- `listener` / `tlsMode` / `tlsSecret` 只用于生成 Gateway CR，**不会**进 Helm 的 Service values
  （k8s-server 收到前会裁掉，见 `withoutGatewayListenerFields`）；
- **「仅 Service」必须显式提交 `listener: ""`**：空串 = 明确不监听；只有字段**缺失**（功能上线前的老快照）
  才按默认行补齐（2026-10-20 实报：选了仅 Service 仍被加进 CR，就是空串被前端丢掉、后端误当缺失）；
- 老快照重放时按默认行（同名/同端口）补 `listener`/`tlsMode`，再按端口兜底（80→HTTP、443→TLS Passthrough）；
- 只有 `service.type` 和端口列表可改；Service 名固定，改名字等于换入口。

## 5. Gateway CR 由组件负责（2026-10-20）

### 5.1 为什么

`istio-gateway` chart **只创建 Deployment + Service，不创建 Gateway CR**（上游 chart 的 templates 里没有
`kind: Gateway`）。自建网关没有 Istio 默认的 `<网关名>-istio` Service，如果 Gateway CR 又不写
`spec.addresses`，istiod 的 status 控制器就找不到承载它的 Service：

```
Accepted   = True
Programmed = False   reason=AddressNotUsable
message: hostname "external-gateway-istio.istio-system.svc.cluster.local" not found
```

所以由组件建 CR 并维护这层绑定。

### 5.2 下发时机与内容

Istio 安装/重放成功后（k8s-server 返回 SUCCESS），platform-api 调用
`POST /components/external-gateway`，k8s-server 幂等 SSA 下发 `istio-system/external-gateway`：

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: external-gateway
  namespace: istio-system
  labels:
    platform.coding/managed: "true"
    platform.coding/component: istio
spec:
  gatewayClassName: istio
  addresses:
    - type: Hostname
      value: external-gateway.istio-system.svc.cluster.local   # 绑定承载它的 Service
  listeners:
    - name: http2
      port: 80
      protocol: HTTP
      allowedRoutes: { namespaces: { from: All } }
    - name: https
      port: 443
      protocol: TLS
      tls: { mode: Passthrough }
      allowedRoutes: { namespaces: { from: All } }
```

- `spec.listeners` = 端口表里「监听协议」非空的行（端口取 targetPort）；
- `Terminate` 带 `certificateRefs`（Secret 同 `istio-system`），`Passthrough` 只写 `mode`；
- 已存在的 CR 会被这组 listeners **整体覆盖**（组件拥有它，以表单为准）。手工改 CR 只能在重放前生效；
- Gateway API CRD 不存在时跳过（返回 false，不影响安装结果）；走 SSA，fieldManager 与其它平台对象一致。

### 5.3 与路由、Telemetry 的关系

- 网关 / 路由页面创建的是 `HTTPRoute` / `GRPCRoute` / `TLSRoute` 等，通过 `parentRefs` 挂到这个 Gateway；
- 安装收尾随后下发 `Telemetry platform-tracing-ingress`（`targetRefs` 指向该 Gateway，provider `platform-otel`），
  必须在 Gateway CR 之后 —— 它要求目标 Gateway 存在；
- waypoint 是另一种 Gateway（`istio-waypoint` 类），由工作负载页创建，不经过这里的端口表。

### 5.4 状态怎么看

Gateway 列表的「状态」列就是 `Programmed` 条件：

- `True`：地址已解析、监听器已下发；
- `False / AddressNotUsable`：找不到承载它的 Service（检查 `spec.addresses` 与 Service 名）；
- `Accepted=False`：listener 非法（缺证书、端口冲突、协议不合法等），消息在 detail 里。

## 6. 实锤（来自历史对话）

- **2026-10-18**：`istio-ambient` 合并进 `istio`（cni/ztunnel 与 istiod 同一安装单元）——
  用户评审要求「istio 部署要是 ambient 模式，不用暴漏表单，部署默认的」。
- **2026-10-19**：用户要求把 `gateway-api` 也并入 `istio`（§2）。
- **2026-10-20**：用户发现组件版本显示成 **1.6.2**（Gateway API 的版本）—— 旧实现取第一个 release。
  改为显式主 release（`istiod`），并新增逐 release 的 chart/应用版本展示（§1.1）。
- **2026-10-20**：用户报「装好了再编辑不回显」—— 状态投影只读第一个 release 的 values 快照，
  而 istiod 与 external-gateway 的字段分散在后面。改为遍历所有 release 取当前值。
- **2026-10-20**（安装前）：集群残留 48 天前 `istio-ambient`（Operator 安装）留下的
  `istio-reader-service-account` 与 15 个 Istio CRD，已无 Helm release 记录，安装报
  `invalid ownership metadata`。处理：补 `meta.helm.sh/release-name=istio-base` /
  `release-namespace=istio-system` 认领（CRD 数据不动），再 `--dry-run=server` 验证。
  **平台不自动接管外部安装**，这种残留必须先认领或清理。
- **2026-10-20**：ztunnel 一直不 Ready，日志刷 `ztunnel requires PILOT_ENABLE_AMBIENT=true`。
  根因：recipe 只给 cni 开了 ambient，没给 istiod 开 —— 官方 ambient 是 istiod env + cni ambient +
  ztunnel 三件套。修法见 §3；存量集群 EDIT/重放一次即可（istiod 滚动重启）。
- **2026-10-20**：用户问「external gateway 暴露的端口怎么控制」—— 当时只有 chart 默认的
  80/443/15021、`service.type=LoadBalancer`，平台没有任何表单。先做 `service.type` + 端口表格，
  再按用户要求补了「监听协议 / TLS 模式 / 证书 Secret」三列（§4.2）。
- **2026-10-20**：用户看到 Gateway `external-gateway` 状态 `False`（`AddressNotUsable`）。
  Gateway CR 是用户按老 YAML 新建的（带悬空的 `infrastructure.parametersRef → customnodeport`），
  没有 `spec.addresses`；chart 又不建 CR。结论：**CR 归组件建、关系归组件维护**（§5）。
- **2026-10-20**：用户选「仅 Service」后 443 仍出现在 Gateway CR —— 前端把空串丢掉，后端把"缺失"
  当老快照补了默认值。改为空串显式提交（§4.2）。
- 真集群实测：pilot 镜像为 `registry.istio.io/release/pilot`，老安装版本标签 1.30.3；
  平台登记 1.24 会作为 tooltip 显示不一致（见组件清单的版本来源说明）。
- 本集群入口 `istio-system/external-gateway` 的 listener 级路由跨 ns 挂载、hostnames 全空，
  平台因分配表边界看不到它 —— 这是「对外暴露」设计的已知拓扑（见
  `docs/development/cluster-prerequisites.md` 与入口拓扑笔记）。

## 7. 验证

```bash
# release 与工作负载
helm -n kube-system list | grep platform-gateway-api
helm -n istio-system list | grep -E 'istio-base|istiod|istio-cni|ztunnel|external-gateway'
kubectl -n istio-system get deploy,ds,pods

# CRD 与组件判据
kubectl get crd gateways.gateway.networking.k8s.io

# 入口 Service（类型/端口/NodePort）
kubectl -n istio-system get svc external-gateway -o wide

# Gateway CR：地址绑定 + 状态
kubectl -n istio-system get gateway external-gateway \
  -o jsonpath='{.spec.addresses}{"\n"}{range .status.conditions[*]}{.type}={.status} {.reason}{"\n"}{end}'

# listener 与端口表是否一致
kubectl -n istio-system get gateway external-gateway -o jsonpath='{.spec.listeners[*].port}{"\n"}'

# ingress tracing 的 Telemetry
kubectl -n istio-system get telemetry platform-tracing-ingress
```

平台侧：组件页只有一行 `Istio` 且健康；网格状态横幅 istio / ambient / gateway-api 均为可用；
网关 / 路由页面可创建对象；编辑表单能看到当前生效的副本数、资源与端口表。

## 8. 已知坑

1. **只装 istiod 没装 Gateway API** —— 合并后由 recipe 保证（gateway-api 排第一）；手工装 Istio 时别漏。
2. **Gateway API 版本判断** —— 不要用组件状态推断，读 capability 的 group 版本；
   Helm 卸载不删 CRD，CRD 残留 ≠ 可用。
3. **ambient 显示 sidecar** —— 平台装的永远是 ambient；手工 sidecar 模式安装会显示为 ambient（有意的简化）。
4. **ambient 三件套** —— istiod `PILOT_ENABLE_AMBIENT` + cni `ambient.enabled` + ztunnel，缺一个 ztunnel 就刷
   `requires PILOT_ENABLE_AMBIENT=true`。
5. **改 `meshID` / `revision` 是破坏性操作** —— 不要放进可编辑表单。
6. **升级后 istiod 重启** —— `--rollback-on-failure` 失败会回滚；旧 revision 资源可能残留。
7. **入口名不是 `external-gateway`** —— 集群对外拓扑与平台路由依赖这个固定名，不能改。
8. **Gateway 状态 False** —— 先看 `Programmed` 的 reason：`AddressNotUsable` = 找不到承载 Service
   （检查 `spec.addresses`）；`Accepted=False` = listener 非法（证书/端口/协议）。
9. **HTTPS 不能用 Passthrough** —— Gateway API 定义；要透传用 `TLS + Passthrough`，要 HTTP 路由用
   `HTTPS + Terminate`。
10. **证书 Secret 不存在** —— Terminate 的 listener 会一直未编程；Secret 必须在 `istio-system`。
11. **手工改 Service** —— `kubectl edit svc` 能立即生效，但改已有端口/类型会在下次平台重放时被表单值覆盖；
    新增端口能保留，但不属于平台声明，编辑表单也看不到。
12. **nodePort 范围** —— 平台不校验 30000-32767；范围由集群 `--service-node-port-range` 决定，
    填错由 K8s/helm 报错（操作记录的 `message/log_tail` 可见）。
13. **`service.ports` 整体替换** —— 每次保存提交的是整组端口；漏掉的行等于删除该端口。
14. **老快照兼容** —— 老集群的端口快照没有 `listener` 字段，重放时按默认行补齐；旧 CR 不必手工删。

## 9. 新增版本

六个 chart 的版本**不完全同步**（Gateway API 是独立项目）：

1. 分别拉取 `gateway-api` 与五个 Istio chart 的新版本，入库到各自 `<version>/chart/` + `provenance.yaml`，
   并在**版本目录**写 `<version>/README.md`（六个 chart 的版本表 + 来源 + 本版本变化）；
2. `ComponentRecipeCatalog.ISTIO_RELEASES` 按 release 顺序更新版本；
   Gateway API 仍排第一；给新 release 补 `asPrimary()`（istiod）与 `withAppVersion(...)`；
3. 检查 `extensionProviders` 的 schema（Istio 新版本可能改 API）；
4. 检查 ambient 相关 values key（`pilot.env.PILOT_ENABLE_AMBIENT`、`cni.ambient.enabled`、
   `ISTIO_META_ENABLE_HBONE` 是否仍存在，是否改默认）；
5. 检查 `istio-gateway` chart 的 `name` / `service.ports` 结构（端口表直接映射到 Gateway listener）；
6. 非生产集群验证：ambient 流量接管、入口 Gateway 的 `Programmed=True`、`Telemetry` tracing、
   Gateway/HTTPRoute 页面、端口表增删后 Service 与 listener 一致；
7. 重建 **k8s-server**（charts 入镜像）+ **platform-api**（recipe）+ **platform-web**（表单）。

> Istio 升级涉及 CNI/ztunnel DaemonSet 滚动与 CRD 变更，风险高；先在非生产集群走一遍。

## 10. 卸载

平台不提供卸载。手工按反向顺序 `helm uninstall external-gateway → ztunnel → istio-cni →
istiod → istio-base`；`platform-gateway-api` 是否卸载视集群其他组件是否需要 Gateway API 决定。
Istio 与 Gateway API 的 CRD 不会被 Helm 删除。

`external-gateway` Gateway CR 不随 Helm 卸载删除（它由平台 SSA 创建，不属于任何 release）；
不需要时手工 `kubectl -n istio-system delete gateway external-gateway`。
