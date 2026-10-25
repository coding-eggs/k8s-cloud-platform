# cni chart 1.31.3（Istio 1.31.3）

- 来源：`https://blob.istio.io/istio-release/charts`（官方 `cni`）
- 拉取日期：2026-10-26；原样入库，sha256 与 repo index 的 digest 一致
- 镜像：**`docker.io/istio/install-cni`**（1.31 起默认 hub 改为 Docker Hub）
- 本版本注意：ambient 模式必须 `ambient.enabled=true`，否则只有 ztunnel 没有 CNI 接管。

组件级约定见 [istio-base/README.md](../../istio-base/README.md)。
