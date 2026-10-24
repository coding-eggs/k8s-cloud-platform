# strimzi-kafka-operator 1.3.0（内置 chart）

- 来源：`https://github.com/strimzi/strimzi-kafka-operator/releases/download/1.3.0/strimzi-kafka-operator-helm-3-chart-1.3.0.tgz`
- 拉取日期：2026-10-22；上游：Strimzi，Apache-2.0
- 安装：release `platform-middleware-kafka`，命名空间 `platform-middleware-system`
- CRD：`crds/` 下 `kafkas.kafka.strimzi.io`、`kafkanodepools.kafka.strimzi.io` 等
- 镜像：`quay.io/strimzi/operator`
- 注意：1.x 纯 KRaft，实例需要 `KafkaNodePool` + `Kafka`（注解 `strimzi.io/node-pools: enabled`）

组件级约定、坑与加版本流程见上一级 `../README.md`。
