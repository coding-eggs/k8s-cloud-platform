# gateway chart 1.30.6（Istio 1.30.6）

- 来源：`https://istio-release.storage.googleapis.com/charts`（官方 `gateway`）
- 拉取日期：2026-10-17；原样入库
- 镜像：`registry.istio.io/release/proxyv2`
- 本版本注意：chart 不含 Gateway CR（平台负责下发）；`service.ports` 是 list，Helm 合并**整体替换**。

组件级约定见 [istio-base/README.md](../../istio-base/README.md)。
