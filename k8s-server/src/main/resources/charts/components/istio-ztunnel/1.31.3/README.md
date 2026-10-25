# ztunnel chart 1.31.3（Istio 1.31.3）

- 来源：`https://blob.istio.io/istio-release/charts`（官方 `ztunnel`）
- 拉取日期：2026-10-26；原样入库，sha256 与 repo index 的 digest 一致
- 镜像：**`docker.io/istio/ztunnel`**（1.31 起默认 hub 改为 Docker Hub）
- 本版本注意：无自定义 values；Ready 依赖 istiod 的 `PILOT_ENABLE_AMBIENT=true` 与 cni 的 ambient 开关。
  1.31 的 values 注释提到 `requests.cpu` 超过 2 核时会同时决定 ztunnel 的最小 worker 线程数（平台不写该值）。

组件级约定见 [istio-base/README.md](../../istio-base/README.md)。
