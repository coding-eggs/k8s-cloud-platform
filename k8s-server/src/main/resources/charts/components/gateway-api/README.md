# Gateway API CRD（Istio 组件的第一个 release）

> **这是 chart 级 README（跨版本）**。本 chart 不是独立组件，而是 **Istio 组件**的第一个 release
> （`platform-gateway-api`），提供 Gateway / HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute 的 CRD。
> 组件总文档（安装顺序、契约、坑）见 [istio-base/README.md](../istio-base/README.md)；
> 本次安装的版本与来源见版本目录：`<version>/README.md`（当前 `1.6.2/`）。

## 要点

- 上游 Gateway API 只发布 standard / experimental 的 YAML，**没有官方 Helm chart**；
  平台用社区 chart `wiremind/helm-charts` 的 `gateway-api-crds`（**experimental 通道**，
  2026-10-17 用户裁定）—— 只有 experimental 才有 TCP/TLS/UDP Route。
- 必须**最先安装**：istiod / cni / ztunnel 的模板与平台的 Gateway CR 都依赖这些 CRD。
- CRD 是集群级的：卸载 Helm 不会删 CRD；**CRD 残留 ≠ 功能可用**（组件可用性另由 capability 的 group 版本判断）。
- 同一集群只装一份；已有别人装的同名 CRD 时，安装会被归属预检拒掉（先认领或清理）。
