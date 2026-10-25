# gateway chart 1.31.3（Istio 1.31.3）

- 来源：`https://blob.istio.io/istio-release/charts`（官方 `gateway`）
- 拉取日期：2026-10-26；原样入库，sha256 与 repo index 的 digest 一致
- 镜像：Deployment 里仍是 `image: auto`（由 istiod 注入 `docker.io/istio/proxyv2:1.31.3`）
- 本版本注意：chart 不含 Gateway CR（平台负责下发）；`service.ports` 仍是 list，Helm 合并**整体替换**。
  新增的 `values.schema.json` 字段（`healthCheckNodePort` / `dnsPolicy` / `dnsConfig`）平台不写，无影响。

组件级约定见 [istio-base/README.md](../../istio-base/README.md)。
