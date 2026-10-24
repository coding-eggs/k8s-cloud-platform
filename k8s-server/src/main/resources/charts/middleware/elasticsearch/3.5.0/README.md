# eck-operator 3.5.0（内置 chart）

- 来源：`https://helm.elastic.co/helm/eck-operator/eck-operator-3.5.0.tgz`
- 拉取日期：2026-10-22；上游：Elastic Cloud on Kubernetes，Elastic License 2.0 / SSPL
- 安装：release `platform-middleware-elasticsearch`，命名空间 `platform-middleware-system`
- CRD：子 chart `eck-operator-crds` 以模板方式安装（可随 upgrade 更新）→ `elasticsearches.elasticsearch.k8s.elastic.co`
- 镜像：`docker.elastic.co/eck/eck-operator`

组件级约定、坑与加版本流程见上一级 `../README.md`。
