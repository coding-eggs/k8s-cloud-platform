# milvus-operator 1.3.12（平台内置）

| 项 | 值 |
|---|---|
| 来源 | `https://github.com/zilliztech/milvus-operator/releases/download/milvus-operator-1.3.12/milvus-operator-1.3.12.tgz` |
| appVersion | 1.3.12 |
| 镜像 | `milvusdb/milvus-operator:v1.3.12` |
| 平台安装 values | `enableWebhook: false`、`installCRDs: true`、显式 `image.repository/tag` |
| CRD（在 `templates/crds.yaml`） | `milvuses.milvus.io`、`milvusclusters.milvus.io`、`milvusupgrades.milvus.io` |
| 默认 Milvus 版本 | `v2.6.25`（`pkg/config/config.go` 的 `DefaultMilvusVersion`） |
| 内置依赖 chart（本版本） | etcd 8.12.0、silo 7.0.2（存储）、pulsar/kafka（平台不使用）、cert-manager（`enabled=false`） |

## 注意

- 本 chart 含 `charts/cert-manager` 子 chart（`condition: cert-manager.enabled`，默认 false、无 `crds/` 目录），
  原样保留，不裁剪；
- `enableWebhook: false` 时 controller 自己调 `SetDefault` 补默认值，不依赖 cert-manager；
- 官方 release 资产里的 chart 目录名就是 `milvus-operator`，内置时把整目录拷成 `1.3.12/chart`。
