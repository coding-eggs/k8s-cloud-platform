# tempo chart 3.2.0（app 3.1.0）

- 来源：`https://grafana-community.github.io/helm-charts`（chart `tempo` 3.2.0 / app 3.1.0）
- 拉取日期：2026-10-18；原样入库
  - `grafana/helm-charts` 自 2026-01 起迁移到 `grafana-community/helm-charts`；
    旧 `grafana/tempo` 1.24.4 已标 deprecated，故改用社区仓库的 3.2.0
- 镜像：`docker.io/grafana/tempo:3.1.0`
- 本版本注意：
  - chart 默认 `tempo.retention` 只有 **24h**；平台做成表单字段，默认 `168h`；
  - S3（MinIO）配置路径是 `tempo.storage.trace.s3.{bucket,endpoint,access_key,secret_key}`，
    凭证占位符要留到运行时由 `-config.expand-env=true` 展开（recipe 里 `preserveUnknownPlaceholders=true`）；
  - 平台固定 `persistence.storageClassName: nfs-client`（**已知限制**：集群没有该 SC 时 PVC Pending，后续应做成表单字段）。

组件级约定、坑与加版本流程见上一级 `../README.md`。
