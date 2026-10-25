# Istio 1.31.3 · Gateway API 1.6.2（组件当前钉的版本）

本文件只记**这一版**的事实：六个 chart 的版本与来源。跨版本的约定、坑与加版本流程见上一级
`../README.md`。

## 1. 版本表

| release | chart 目录 | chart 版本 | app 版本 | 角色 |
|---|---|---|---|---|
| `platform-gateway-api` | `gateway-api` | 1.6.2 | 1.6.2 | 随附（CRD 依赖，排第一） |
| `istio-base` | `istio-base` | 1.31.3 | 1.31.3 | 随附 |
| `istiod` | `istio-istiod` | **1.31.3** | 1.31.3 | **主（组件版本 = 1.31.3）** |
| `istio-cni` | `istio-cni` | 1.31.3 | 1.31.3 | 随附（ambient 数据面） |
| `ztunnel` | `istio-ztunnel` | 1.31.3 | 1.31.3 | 随附（ambient 数据面） |
| `external-gateway` | `istio-gateway` | 1.31.3 | 1.31.3 | 随附（对外入口，名字固定） |

Gateway API **不随 Istio 升**：Istio 1.31 的 `go.mod` 钉 `sigs.k8s.io/gateway-api v1.6.0`，
平台入库的 CRD chart 是 1.6.2（同 minor、更新），继续用。

## 2. 来源

| chart | 来源 | 拉取日期 |
|---|---|---|
| `istio-base` / `istio-istiod` / `istio-cni` / `istio-ztunnel` / `istio-gateway` | `https://blob.istio.io/istio-release/charts`（官方的**新** helm repo 地址） | 2026-10-26 |

全部**原样入库**，每个 chart 目录另有 `provenance.yaml` 记录 sha256/拉取命令。
sha256 已与 repo `index.yaml` 的 `digest` 逐一对上。

⚠️ **旧地址取不到 1.31.x 稳定版**：`https://istio-release.storage.googleapis.com/charts/index.yaml`
只到 `1.30.7` 与 `1.31.0-rc.0`（1.31 正式版没进这个索引）。加版本时用新地址
`https://blob.istio.io/istio-release/charts`（会 308 到 `istio-release.r2.istio.io`）。

## 3. 本版本注意

- **升 1.31 的动因：TCPRoute v1**。1.30.x 只监听 `v1alpha2` 的 TCPRoute（也受
  `PILOT_ENABLE_ALPHA_GATEWAY_API` 门控），而平台页面创建的是 `gateway.networking.k8s.io/v1`，
  所以 `attachedRoutes` 恒为 0、Envoy 不监听该端口（2026-10-26 实锤：Milvus 19530 连接被拒）。
  1.31 起 TCP listener 转正、controller 直接消费 `gatewayv1.TCPRoute`。详见组件级 §5.2。
- **镜像 hub 变了**：`global.hub` 从 `registry.istio.io/release` 改为 **`docker.io/istio`**
  （上游 1.31 的默认值）。集群若不能直连 Docker Hub，需要提前配镜像加速/代理，
  否则 istiod / ztunnel / install-cni 全部 ImagePullBackOff。组件探测两条候选都认
  （`istio.io/release/pilot` 与 `istio/pilot`），`docker.io/istio/pilot` 命中后者。
- **平台契约值全部兼容**：`global.meshID`、`pilot.env.PILOT_ENABLE_AMBIENT`、
  `pilot.env.PILOT_ENABLE_ALPHA_GATEWAY_API`、`meshConfig.enableTracing` +
  `extensionProviders[platform-otel]`、`meshConfig.defaultConfig.proxyMetadata` 在 1.31.3 的
  values 里都还在（`helm template` 逐条渲染确认）。其中 ALPHA 开关在 1.31 已是多余项，留着只为回退 1.30。
- **UDPRoute 仍然不支持**（1.31 的 `supported_features.go` 显式 `Delete(UDPRouteFeature)`）；
  **TLSRoute 一直是 v1**，不受影响。
- `istio-gateway` chart 新增 `values.schema.json` 字段（`service.healthCheckNodePort`、
  `dnsPolicy`、`dnsConfig`），平台不写这些键，无影响；`name` / `service.type` / `service.ports`
  的结构没变，端口表仍直接映射。
- 渲染验证（2026-10-26）：五个 chart 用 recipe 的契约值 + 可编辑默认值 `helm template` 全部成功，
  istiod env/`meshID`/`extensionProviders`、gateway Service 三端口、cni/ztunnel DaemonSet 均如期输出。
