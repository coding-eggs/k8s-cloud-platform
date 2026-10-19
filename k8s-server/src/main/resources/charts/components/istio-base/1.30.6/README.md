# istio-base（chart 1.30.6）· Istio 组件（含 Gateway API）

> Istio 组件不是单个 chart：`charts/components/` 下的 `gateway-api`、`istio-base`、`istio-istiod`、
> `istio-cni`、`istio-ztunnel`、`istio-gateway` 六个 release 组成一个安装单元。
> 本文放在 `istio-base` 下作为组件总文档；各 chart 的版本与来源见各自 `provenance.yaml`。

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

## 2. 合并 Gateway API（2026-10-19）

用户要求：**Gateway API 组件与 Istio 合并为一个组件**。

理由：只装 Istio 不装 Gateway API，网关 / 路由页面不可用；拆成两个组件只会让用户漏装一个。
现在 `gateway-api` 的 chart 作为 Istio recipe 的**第一个 release**（`platform-gateway-api`，
`kube-system`）安装，组件列表里只保留 `istio` 一行。

**探测为什么不合并 CRD 判据**：多判据是 ANY-命中（`prefer`：任一 found 即 found）。
如果把 `gateways.gateway.networking.k8s.io` 的 CRD 判据也挂到 `istio` 上，
istiod 被删、CRD 残留时整个组件会被判成「已安装」。所以组件健康仍只看 istiod；
Gateway API 是否可用由 `MeshService` 读 `k8s_cluster.capability` 的 group 版本回答
（`hasGatewayApi = gatewayVersions 非空`）。

**存量集群**：之前单独装过 `gateway-api` 组件的集群，`platform-gateway-api` release 已存在；
这次升级 Istio 时 helm `upgrade --install` 会接管同一 release，不会重复安装。

## 3. 契约 values（🔒 不可改）

| release | 关键契约 | 为什么 |
|---|---|---|
| `platform-gateway-api` | 无（experimental CRD chart 1.6.2） | TCP/TLS/UDP Route 只在 experimental 通道里 |
| `istio-base` | 无 | |
| `istiod` | `global.meshID=platform-mesh`；`meshConfig.enableTracing=true` + `extensionProviders[platform-otel]` 指向 `platform-otel-gateway-collector.monitor.svc:4317`；不设 `revision` | meshID 全局一致（装完再改是破坏性操作）；ingress/waypoint 的 `Telemetry` 引用该 provider；不做按 revision 的灰度 |
| `istio-cni` | `ambient.enabled=true` | 平台默认 ambient |
| `ztunnel` | 无 | |
| `external-gateway` | `name=external-gateway` | 集群对外入口名固定 |

可编辑值（istiod）：`replicaCount`（默认 1）、`autoscaleEnabled`（默认 true）、`resources`（JSON）。

## 4. 实锤（来自历史对话）

- **2026-10-18**：`istio-ambient` 合并进 `istio`（cni/ztunnel 与 istiod 同一安装单元）——
  用户评审要求「istio 部署要是 ambient 模式，不用暴漏表单，部署默认的」。
- **2026-10-19**：用户要求把 `gateway-api` 也并入 `istio`（本文 §2）。
- 真集群实测：pilot 镜像为 `registry.istio.io/release/pilot`，istio 版本标签 1.30.3；
  平台登记 1.24 会作为 tooltip 显示不一致（见组件清单的版本来源说明）。
- 本集群入口 `istio-system/external-gateway` 的 listener 级路由跨 ns 挂载、hostnames 全空，
  平台因分配表边界看不到它 —— 这是「对外暴露」设计的已知拓扑（见
  `docs/development/cluster-prerequisites.md` 与入口拓扑笔记）。

## 5. 验证

```bash
helm -n kube-system list | grep platform-gateway-api
helm -n istio-system list | grep -E 'istio-base|istiod|istio-cni|ztunnel|external-gateway'
kubectl get crd gateways.gateway.networking.k8s.io
kubectl -n istio-system get deploy,ds
```

平台侧：组件页只有一行 `Istio` 且健康；网格状态横幅 istio / ambient / gateway-api 均为可用；
网关 / 路由页面可创建对象。

## 6. 已知坑

1. **只装 istiod 没装 Gateway API** —— 合并后由 recipe 保证（gateway-api 排第一）；手工装 Istio 时别漏。
2. **Gateway API 版本判断** —— 不要用组件状态推断，读 capability 的 group 版本；
   Helm 卸载不删 CRD，CRD 残留 ≠ 可用。
3. **ambient 显示 sidecar** —— 平台装的永远是 ambient；手工 sidecar 模式安装会显示为 ambient（有意的简化）。
4. **改 `meshID` / `revision` 是破坏性操作** —— 不要放进可编辑表单。
5. **升级后 istiod 重启** —— `--rollback-on-failure` 失败会回滚；旧 revision 资源可能残留。
6. **入口名不是 `external-gateway`** —— 集群对外拓扑与平台路由依赖这个固定名，不能改。

## 7. 新增版本

六个 chart 的版本**不完全同步**（Gateway API 是独立项目）：

1. 分别拉取 `gateway-api` 与五个 Istio chart 的新版本，入库到对应 `<version>/chart/` + `provenance.yaml`；
2. `ComponentRecipeCatalog.ISTIO_RELEASES` 按 release 顺序更新版本；
   Gateway API 仍排第一；
3. 检查 `extensionProviders` 的 schema（Istio 新版本可能改 API）；
4. 检查 `installCRDs` / ambient 相关 values key；
5. 非生产集群验证：ambient 流量接管、入口 Gateway、`Telemetry` tracing、Gateway/HTTPRoute 页面；
6. 重建 k8s-server + platform-api。

> Istio 升级涉及 CNI/ztunnel DaemonSet 滚动与 CRD 变更，风险高；先在非生产集群走一遍。

## 8. 卸载

平台不提供卸载。手工按反向顺序 `helm uninstall external-gateway → ztunnel → istio-cni →
istiod → istio-base`；`platform-gateway-api` 是否卸载视集群其他组件是否需要 Gateway API 决定。
Istio 与 Gateway API 的 CRD 不会被 Helm 删除。
