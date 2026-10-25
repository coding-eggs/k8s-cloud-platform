# istiod chart 1.31.3（Istio 1.31.3）

- 来源：`https://blob.istio.io/istio-release/charts`（官方 `istiod`；旧地址 `istio-release.storage.googleapis.com` 的索引里没有 1.31 稳定版）
- 拉取日期：2026-10-26；原样入库，sha256 与 repo index 的 digest 一致
- 镜像：**`docker.io/istio/pilot`**（1.31 起官方默认 hub 从 `registry.istio.io/release` 换成 Docker Hub；
  组件探测按 `istio.io/release/pilot` / `istio/pilot` 两条候选，后者命中）
- 本版本注意：
  - ambient 仍必须由 istiod 显式开启（`PILOT_ENABLE_AMBIENT=true`），否则 ztunnel 连 15012 被拒；
  - `PILOT_ENABLE_ALPHA_GATEWAY_API` 在 1.31 已不是 TCP listener 的前提（转正），留着只用于回退 1.30；
  - TCPRoute 消费 `gateway.networking.k8s.io/v1`（1.30 只认 v1alpha2）—— 这是升级本 chart 的主要原因。

组件级约定见 [istio-base/README.md](../../istio-base/README.md)。
