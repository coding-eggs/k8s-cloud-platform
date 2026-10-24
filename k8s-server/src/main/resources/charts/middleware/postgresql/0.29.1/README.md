# cloudnative-pg 0.29.1（内置 chart）

- 来源：`https://cloudnative-pg.github.io/charts`，chart `cloudnative-pg` 0.29.1（appVersion 1.30.1）
- 拉取日期：2026-10-22；上游：CloudNativePG，Apache-2.0
- 安装：release `platform-middleware-postgresql`，命名空间 `platform-middleware-system`
- CRD：`clusters.postgresql.cnpg.io`（在 chart 的 `templates/` 里，安装即建，可随 `helm upgrade` 更新）
- 镜像：`ghcr.io/cloudnative-pg/cloudnative-pg`；实例镜像用 Operator 自带默认（平台不写 `imageName`）
- 版本相关注意：
  - `spec.monitoring.enablePodMonitor` 在本版本**已 deprecated**（"请自己管理 PodMonitor"）→ 平台改用
    Service + ServiceMonitor（9187），不要写回去；
  - chart 带一个可选的 `monitoring` 依赖（Grafana dashboard），平台不用；
  - 状态判定要带 `cnpg.io/podRole=instance`，否则 initdb/join 的 Job Pod 会被算进去。

组件级约定、坑与加版本流程见上一级 `../README.md`。
