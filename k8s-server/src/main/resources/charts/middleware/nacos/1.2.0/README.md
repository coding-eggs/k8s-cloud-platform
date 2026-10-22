# nacos 1.2.0（内置 chart，appVersion 3.2.4）

- 来源：`https://github.com/nacos-group/nacos-k8s` 的 `helm/` 目录（master，2026-10-22）
- 上游：Apache-2.0
- 用法：**不是 Operator**，实例 = 一次 `helm upgrade --install`（release 名 = 实例名）
- 关键 values：`mode: standalone|cluster`、`nacos.replicaCount`、`nacos.storage.type: embedded`、`service.type: ClusterIP`（chart 默认 NodePort/30000，必须覆盖）
- 镜像：`nacos/nacos-server:v3.2.4`
- 存储：Nacos 3.x 集群模式用内嵌 JRaft 存储，**不依赖外置 MySQL**
