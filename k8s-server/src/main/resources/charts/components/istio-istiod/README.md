# istiod（Istio 组件的**主 release**）

> **这是 chart 级 README（跨版本）**。本 chart 是 Istio 组件的控制面，也是**主 release** ——
> 组件版本、升级目标、操作记录只认它（`asPrimary()`）。组件总文档见
> [istio-base/README.md](../istio-base/README.md)；本次安装的版本见版本目录 `<version>/README.md`。

## 契约与可编辑项（摘要）

- 契约：`global.meshID=platform-mesh`、`pilot.env.PILOT_ENABLE_AMBIENT=true`、
  `pilot.env.PILOT_ENABLE_ALPHA_GATEWAY_API=true`（1.30 的 TCP listener 门控；1.31 起转正、已是多余项，
  留着只为回退）、
  `meshConfig.enableTracing=true` + `extensionProviders[platform-otel]`、不设 `revision`；
- 可编辑：`replicaCount` / `autoscaleEnabled` / `resources`；
- `meshID` 与 `revision` **有意不进表单**（前者破坏性、后者与平台"重放即升级"冲突）。
