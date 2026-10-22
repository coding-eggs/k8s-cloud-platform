# ps-operator 1.2.0（内置 chart）

- 来源：`https://github.com/percona/percona-helm-charts/releases/download/ps-operator-1.2.0/ps-operator-1.2.0.tgz`
- 拉取日期：2026-10-22；上游：Percona Operator for MySQL (PS)，Apache-2.0
- 安装：release `platform-middleware-mysql`，命名空间 `platform-middleware-system`
- CRD：`crds/crd.yaml` → `perconaservermysqls.ps.percona.com`（Helm 只在首装写 `crds/`，升级不更新）
- 镜像：`percona/percona-server-mysql-operator`（quay.io / docker.io）
- 实例凭据：Operator 自建 `<cr>-secrets`（平台不使用自生成密码）
