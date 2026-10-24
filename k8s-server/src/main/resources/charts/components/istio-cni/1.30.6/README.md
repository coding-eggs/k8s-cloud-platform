# cni chart 1.30.6（Istio 1.30.6）

- 来源：`https://istio-release.storage.googleapis.com/charts`（官方 `cni`）
- 拉取日期：2026-10-17；原样入库
- 镜像：`registry.istio.io/release/install-cni`
- 本版本注意：ambient 模式必须 `ambient.enabled=true`，否则只有 ztunnel 没有 CNI 接管。

组件级约定见 [istio-base/README.md](../../istio-base/README.md)。
