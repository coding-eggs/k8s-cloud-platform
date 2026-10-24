# istiod chart 1.30.6（Istio 1.30.6）

- 来源：`https://istio-release.storage.googleapis.com/charts`（官方 `istiod`）
- 拉取日期：2026-10-17；原样入库
- 镜像：`registry.istio.io/release/pilot`（组件探测按 `istio.io/release/pilot`，兜底 `istio/pilot`）
- 本版本注意：ambient 必须由 istiod 显式开启（`PILOT_ENABLE_AMBIENT=true`），否则 ztunnel 连 15012 被拒。

组件级约定见 [istio-base/README.md](../../istio-base/README.md)。
