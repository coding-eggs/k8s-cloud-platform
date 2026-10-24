# rabbitmq-cluster-operator 4.4.34（内置 chart）

- 来源：`https://charts.bitnami.com/bitnami`，chart `rabbitmq-cluster-operator` 4.4.34（appVersion 2.16.1）
- 拉取日期：2026-10-22；上游：RabbitMQ Cluster Operator（Bitnami 打包），MPL-2.0
- 安装：release `platform-middleware-rabbitmq`，命名空间 `platform-middleware-system`
- CRD：`rabbitmqclusters.rabbitmq.com`
- 镜像（**平台安装时全部显式覆盖**）：
  - `docker.io/bitnamilegacy/rabbitmq-cluster-operator:2.16.1-debian-12-r0`
  - `docker.io/bitnamilegacy/rmq-default-credential-updater:1.0.8-debian-12-r0`
  - `docker.io/library/rabbitmq:4.1.3`（Broker）
- 版本相关注意：
  - chart 默认镜像仍是 `bitnami/*`，2025 起 Bitnami 把历史 tag 迁到 `bitnamilegacy/*`，默认值已 manifest unknown；
    覆盖镜像时还必须 `global.security.allowInsecureImages: true`，否则 chart 拒绝非官方仓库；
  - 只启用 cluster operator（`msgTopologyOperator.enabled: false`）—— 拓扑 Operator 会多拉两个镜像，
    平台不提供拓扑类对象；
  - Pod / StatefulSet / PVC 的标签是 `app.kubernetes.io/name=<集群名>`（不是 `<集群名>-rabbitmq`）。

组件级约定、坑与加版本流程见上一级 `../README.md`。
