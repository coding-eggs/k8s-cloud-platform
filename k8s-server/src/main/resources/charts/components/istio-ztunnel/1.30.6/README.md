# ztunnel chart 1.30.6（Istio 1.30.6）

- 来源：`https://istio-release.storage.googleapis.com/charts`（官方 `ztunnel`）
- 拉取日期：2026-10-17；原样入库
- 镜像：`registry.istio.io/release/ztunnel`
- 本版本注意：无自定义 values；Ready 依赖 istiod 的 `PILOT_ENABLE_AMBIENT=true` 与 cni 的 ambient 开关。

组件级约定见 [istio-base/README.md](../../istio-base/README.md)。
