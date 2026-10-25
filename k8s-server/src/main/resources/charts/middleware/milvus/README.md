# Milvus（中间件 code: `milvus`）

> **组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的事实（来源/CRD 清单/镜像）在 `1.3.12/README.md`。
> 设计文档：`docs/superpowers/specs/2026-10-25-milvus-middleware-design.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | Operator（`milvus-operator`，`milvus.io/v1beta1`） |
| Operator chart | `1.3.12`，release `platform-middleware-milvus` → `platform-middleware-system` |
| 实例形态 | `STANDALONE`（1 个 Milvus Pod）/ `CLUSTER`（proxy/mixcoord/datanode/querynode/streamingnode） |
| 数据存储 | **外部对象存储**（平台 SeaweedFS 桶 / 用户自带 S3）；集群里不拉起 minio/silo |
| etcd | Operator 在租户命名空间拉起，1–3 节点（表单选择，创建后不可改） |
| 凭据 | 无（Milvus 默认不开鉴权）；对象存储 AK/SK 写在 `<实例名>-milvus-storage` |
| 端口 | gRPC 19530；指标/健康 9091 |
| 业务指标 | 各组件自带 Prometheus（9091 `/metrics`），平台只建 Service + ServiceMonitor |

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-milvus-<8 位随机>`，即 `Milvus` CR 名；Service 名是 `<实例名>-milvus`；
- **受管标签**：CR 带 `platform.coding/managed-by=middleware` + `platform.coding/middleware-instance=<id>`；
- **PVC 标签**：单节点本地盘（`<实例名>-data`）与 etcd PVC 都带同一个 `platform.coding/middleware-instance`，
  删实例且 `keepPvc=false` 时平台按它回收；
- **资源**：Milvus 组件与 etcd **必须**写 requests + limits（租户命名空间有 quota）；
- **租户 RBAC**：模板需要 `milvus.io`（`V2026_10_25_8`）+ **重下发全部模板**；
- **状态**：Pod 选择器 `app.kubernetes.io/instance=<实例名>,app.kubernetes.io/name=milvus`。

## 3. 形态与 CR 对应

| 形态 | CR 要点 |
|---|---|
| `STANDALONE` | `mode: standalone`；`persistence` 开 PVC（本地 WAL/临时盘）；WAL 用 rocksmq |
| `CLUSTER` | `mode: cluster` + `dependencies.msgStreamType: woodpecker`；`components.queryNode.replicas` 由表单控制，其余组件固定 1；不配 persistence（woodpecker 写对象存储） |

## 4. 坑（实施与真跑时核对）

1. **对象存储一律 external**：`storage.external=true`、`type=S3`、`endpoint` 只写 `host:port`（带 scheme 会被解析错端口）；
2. **TLS 写两处**：`dependencies.storage.ssl.enabled` + `config.minio.useSSL`；
3. **`spec.config` 是 `milvus.yaml` 的顶层**：bucket / TLS / `useVirtualHost` 都写在 `config.minio.*`，
   **不要**包一层 `config.milvus.*`（operator 直接把 `spec.config` 合并进 milvus.yaml）；
4. **SeaweedFS 用路径风格**：`config.minio.useVirtualHost=false`；
5. **etcd chart 默认不写 resources**：平台必须补，否则租户 quota 直接拒；
6. **单节点本地盘 PVC 没有 OwnerReference**：删 CR 不会带走它，必须靠标签回收；
7. **`installCRDs: true` 且 CRD 在 `templates/`**：手工卸载 release 会连 CRD 与全部实例一起删，平台不提供卸载入口。

## 5. 业务指标

- 9091 `/metrics`，所有组件都暴露；Service/Pod 选择器与状态判定一致；
- 指标定义在 platform-api `MiddlewareCatalog.MILVUS_METRICS`；
- 开启指标（编辑里）不重启 Pod。

## 6. 加一个新版本怎么做

**升级 Operator chart**：

1. `helm pull`/下载新 chart 到 `charts/middleware/milvus/<新版本>/chart/`，新建 `<新版本>/README.md`；
2. 改 `MiddlewareOperatorCatalog` 的 `chartVersion` 与 `helmValues.image.tag`；
3. 核对新 Operator 的默认 Milvus 版本（上游 `pkg/config/config.go`），必要时同步 platform-api 的版本清单与 `MilvusMiddlewareAdapter.DEFAULT_VERSION`；
4. 跑测试 → 真集群装一次 → 建单节点/集群各一个 → 看 9091 出数。

**升级 Milvus 版本**：改平台版本清单 + 适配器默认值；大版本（2.x → 3.x）先按官方兼容表在测试集群验证 CRD 字段。
