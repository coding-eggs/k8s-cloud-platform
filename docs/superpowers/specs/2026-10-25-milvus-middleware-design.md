# 中间件商店新增 Milvus（operator 模式）· 设计（2026-10-25）

> 上位设计：[2026-10-22-middleware-store-design.md](2026-10-22-middleware-store-design.md)（固定清单 + Operator + 实例台账）。
> 本文件只定义第 8 款 `milvus`，形态、字段、生命周期与既有 7 款保持同一套约定。

---

## 0. 一句话

在中间件商店新增 **Milvus**：Operator 由平台安装（`milvus-operator` 1.3.12），租户在自己的命名空间建 `milvus.io/v1beta1 Milvus` 实例；支持 **单节点** 与 **集群（内嵌 woodpecker WAL）** 两种形态；**对象存储一律走外部**（平台 SeaweedFS 桶，或用户自带 S3），集群里不额外拉起对象存储 Pod。

---

## 1. 决策（已与用户确认）

| # | 决策 | 取值 |
|---|---|---|
| 1 | 形态范围 | `STANDALONE` + `CLUSTER`；集群形态用 `msgStreamType: woodpecker`（不引 Pulsar/Kafka/ZooKeeper） |
| 2 | 对象存储 | **不允许平台在集群里创建对象存储资源**（不部署 minio/silo） |
| 3 | 外部存储接法 | 两种都给：`PLATFORM`（平台 SeaweedFS 建桶 + 写凭据 Secret）与 `CUSTOM`（用户自带 S3） |
| 4 | etcd | 由 Operator 在租户命名空间拉起，节点数表单可选 **1–3** |

---

## 2. 研究基线（2026-10-25 快照，实施时按此钉住）

### 2.1 Operator chart

| 项 | 值 |
|---|---|
| chart | `milvus-operator` **1.3.12**（appVersion `1.3.12`），来源 GitHub release `zilliztech/milvus-operator` |
| 镜像 | `milvusdb/milvus-operator:v1.3.12` |
| CRD | `milvuses.milvus.io`（`v1beta1`）、`milvusclusters.milvus.io`、`milvusupgrades.milvus.io`；**在 `templates/crds.yaml`**，不是 `crds/` 目录 |
| webhook | 默认 `enableWebhook: false`；controller 内部自己调 `SetDefault`，**不需要 cert-manager** |
| 监听范围 | 不传 `--watch-namespace` 时全集群监听（租户 ns 的 CR 能被 reconcile） |
| 依赖子 chart | `charts/cert-manager`（`condition: cert-manager.enabled`，默认 false）；原样内置，不裁剪 |

### 2.2 Milvus 版本与组件

- Operator 1.3.12 默认 Milvus 版本 **`v2.6.25`**（`pkg/config/config.go`），镜像 `milvusdb/milvus:v2.6.25`。
- **2.6 起**：`indexNode` 不再单独部署；集群形态默认启用 **streamingNode**，组件为 `proxy` / `mixcoord` / `datanode` / `querynode` / `streamingnode`（默认各 1 副本）。
- **woodpecker 内嵌模式**：`msgStreamType: woodpecker` 且 `woodpecker.external` 未设时，WAL 跑在 Milvus 进程内、直接写对象存储 —— 不需要 PVC，也不额外拉起组件（源码 `pkg/controllers/configmaps.go` 明确写了 "embedded inside milvus over external etcd + object storage"）。
- **单节点形态**：`mode: standalone`，WAL 用内置 rocksmq；`spec.persistence` 关闭时数据目录是 emptyDir。

### 2.3 端口、标签、服务名

| 项 | 值 |
|---|---|
| gRPC | `19530`（proxy / standalone） |
| 指标 + 健康 | `9091`（`/metrics`、`/healthz`），所有组件都暴露 |
| Service | `<CR 名>-milvus`，选择器优先 `milvus.io/service=true`（proxy/standalone 才有该标签） |
| Pod 标签 | `app.kubernetes.io/instance=<CR 名>`、`app.kubernetes.io/name=milvus`、`app.kubernetes.io/component=<组件>` |
| CR status | `status.status` ∈ `Pending` / `Healthy` / `Unhealthy` / `Stopped`；`status.conditions[].type` ∈ `EtcdReady` / `StorageReady` / `MsgStreamReady` / `MilvusReady` / `MilvusUpdated` |
| 鉴权 | 默认关闭（无用户名/口令） |

### 2.4 外部对象存储在 CR 里的写法（源码核对）

```yaml
spec:
  dependencies:
    storage:
      external: true
      type: S3                    # MinIO | S3 | Azure
      endpoint: <host:port>       # 无 scheme；端口缺省按 useSSL 取 80/443
      secretRef: <Secret 名>       # 键固定为 accesskey / secretkey
      ssl:
        enabled: false            # 影响 operator 的 StorageReady 探测
  config:
    minio:                        # Milvus 配置是**顶层**键（operator 直接把 spec.config 并进 milvus.yaml）
      bucketName: <桶名>
      useVirtualHost: false       # SeaweedFS 必须路径风格
      useSSL: false               # ← 真正影响 Milvus 客户端的是这里，不是 storage.ssl
```

> ⚠️ **`useSSL` 要写两处**：`dependencies.storage.ssl.enabled` 给 operator 做就绪探测，`config.minio.useSSL` 才是 Milvus 客户端的开关。两处不一致会出现"探测说通、Milvus 连不上"。
>
> ⚠️ **`config` 下不要包 `milvus:` 层**：operator 把 `spec.config` 直接合并进 `milvus.yaml`（源码 `updateConfigMap` → `util.MergeValues(conf, mc.Spec.Conf.Data)`），而 `GetMinioSecure()` 读的也是顶层的 `minio.useSSL`。上游 `milvus_cluster_minimum.yaml` 里那种 `config.milvus.*` 写法在 1.3.12 下不生效。

### 2.5 平台侧既有能力（复用，不新建）

| 能力 | 位置 |
|---|---|
| 租户桶配额 / 桶命名 | `ObjectStorageService.bucketName(tenantId, name)` → `t-<tenantHash8>-<name>` |
| 建桶（含配额、回滚） | `ObjectStorageService.createBucket` |
| 发凭据（桶级 Read/Write/List/Tagging） | `ObjectStorageService.issueCredential` → `accesskey`/`secretkey` |
| 删桶（连带吊销凭据） | `ObjectStorageService.deleteBucket` |
| 集群内 S3 地址 | `ComponentRecipeCatalog.seaweedS3InClusterEndpoint()` → `http://<seaweedfs>-s3.platform-storage:8333` |
| 组件是否已装 | `ClusterComponentService.seaweedInstalled(clusterId)` |

---

## 3. 范围

### 3.1 产物

| 模块 | 内容 |
|---|---|
| 内置 chart | `k8s-server/src/main/resources/charts/middleware/milvus/1.3.12/chart/**` + `/README.md` + `1.3.12/README.md` |
| k8s-server | `MiddlewareOperatorCatalog` 条目；`MilvusMiddlewareAdapter`；`MiddlewareAdapter` 新增一个默认方法；`MiddlewareInstanceService` 调用该默认方法 |
| platform-api | `MiddlewareCatalog` 条目 + 指标定义；`MiddlewareService` 创建/删除与对象存储协同；`MiddlewareFormOptionsDTO` 新字段 |
| platform-web | `MIDDLEWARE_FIELDS.milvus`、商店页 Milvus 专属表单项、图标映射 |
| 迁移 | `V2026_10_25_8__milvus_tenant_rbac.sql` |
| 文档 | 组件级 README、`docs/development/middleware-store.md` 补一节 |

### 3.2 明确不做

- 形态切换（standalone ↔ cluster）、创建后更换对象存储 —— 需删实例重建；
- 创建后修改 etcd 节点数；
- Milvus 用户/角色管理（鉴权保持默认关闭）；
- 备份/恢复、MilvusUpgrade CR 的升级界面；
- Operator 卸载入口（`installCRDs: true` 卸载会连 CRD 与全部实例一起删）；
- 平台在集群里部署 minio/silo 等对象存储。

---

## 4. Operator 安装

- 目录项：`code=milvus`、`chartVersion=1.3.12`；
- `requiredCrds`：`milvuses.milvus.io`、`milvusclusters.milvus.io`、`milvusupgrades.milvus.io`；
- `crdsShipInCrdsDir=false`（CRD 在 `templates/`，Helm 会给它们打 release 注解）；
- `helmValues`：

```yaml
enableWebhook: false
installCRDs: true
image:
  repository: milvusdb/milvus-operator
  pullPolicy: IfNotPresent
  tag: v1.3.12
```

- release / namespace 沿用平台约定：`platform-middleware-milvus` → `platform-middleware-system`。
- `installCRDs: true` 是 chart 默认值，保持不动（CRD 在 `templates/` 里，Helm 会给它们打 release 注解）；平台没有卸载入口，所以"卸载即删 CRD + 全部实例"不会在 UI 上被误触。

---

## 5. 形态与 CR

### 5.1 目录声明

| mode | label | minReplicas | fixedReplicas | replicasLabel | help |
|---|---|---|---|---|---|
| `STANDALONE` | 单节点 | 1 | 1 | 节点数 | 开发/测试；1 个 Milvus Pod + 内置 etcd（固定 1 个） |
| `CLUSTER` | 集群 | 1 | null | 查询节点数 | proxy/mixcoord/datanode/querynode/streamingnode；WAL 用内嵌 woodpecker |

版本清单：`["2.6.25"]`（第一项为默认；表单显示 `2.6.25`，镜像拼成 `milvusdb/milvus:v2.6.25`）。

> 「节点数」在集群形态只控制 `components.queryNode.replicas`，其余组件固定 1。理由：2.6 的读写扩展点主要是查询节点；把 5 个组件的副本数一次性绑到同一个数字会让资源占用成倍跳。

### 5.2 单节点 CR（`mode: standalone`）

```yaml
apiVersion: milvus.io/v1beta1
kind: Milvus
metadata:
  name: mw-milvus-12ab34cd
  namespace: <租户 ns>
  labels:
    platform.coding/managed-by: middleware
    platform.coding/middleware-instance: <实例 id>
spec:
  mode: standalone
  components:
    image: milvusdb/milvus:v2.6.25
    resources:
      requests: {cpu: <cpuRequest>, memory: <memoryRequest>}
      limits:   {cpu: <cpuLimit>,   memory: <memoryLimit>}
  persistence:                     # 本地 WAL/临时盘
    enabled: true
    persistentVolumeClaim:
      spec:
        accessModes: [ReadWriteOnce]
        resources: {requests: {storage: <localStorageSize>}}
        storageClassName: <storageClass>     # 用户未选则省略
  dependencies:
    etcd:
      inCluster:
        values:
          replicaCount: 1
          persistence:
            size: 5Gi
            storageClass: <storageClass>     # 可选
          resources:
            requests: {cpu: 100m, memory: 256Mi}
            limits:   {cpu: 500m, memory: 512Mi}
    storage:
      external: true
      type: S3
      endpoint: <host:port>
      secretRef: <name>-milvus-storage
      ssl: {enabled: <bool>}
  config:
    minio:
      bucketName: <桶名>
      useVirtualHost: false
      useSSL: <bool>
```

### 5.3 集群 CR（`mode: cluster`）

在 5.2 基础上：

```yaml
spec:
  mode: cluster
  components:
    image: milvusdb/milvus:v2.6.25
    resources: { ... }             # 同 5.2
    queryNode:
      replicas: <表单节点数>        # 1..9
  # 不写 persistence（WAL 由内嵌 woodpecker 写对象存储）
  dependencies:
    msgStreamType: woodpecker
    etcd:
      inCluster:
        values:
          replicaCount: <1..3，默认 3>
          persistence: {size: 5Gi, storageClass: <可选>}
          resources: { ... 同 5.2 }
    storage: { ... 同 5.2 }
  config: { ... 同 5.2 }
```

### 5.4 资源与配额的硬约束

- **每个容器都必须有 requests + limits**：Milvus 各组件、etcd。租户命名空间的 ResourceQuota 会拒掉缺 limits 的 Pod（MySQL/Kibana 都踩过）。
- etcd 的 chart 默认**完全不写 resources**，平台必须在 `etcd.inCluster.values.resources` 里补；
- etcd 数据盘固定 `5Gi`（不暴露表单），StorageClass 用表单里选的那一个。

### 5.5 扩容（编辑）

| 变更 | 处理 |
|---|---|
| `replicas` | 集群形态 → 改 `components.queryNode.replicas`；单节点形态 → 拒绝（"固定 1 个节点"） |
| CPU / 内存 | 覆盖 `components.resources` |
| 采集业务指标 | 建 Service + ServiceMonitor（不改 CR，不重启 Pod） |
| 存储 / 形态 / etcd 节点数 | 不允许，前端只读并提示「需删除后重建」 |

---

## 6. 对象存储

表单字段 `objectStorageType` 二选一：`PLATFORM` / `CUSTOM`。**两种都不在集群里创建存储 Pod。**

### 6.1 PLATFORM（平台 SeaweedFS）

**前置**：`ClusterComponentService.seaweedInstalled(clusterId)` 为真；否则表单禁用该选项（`form-options` 返回 `objectStorageAvailable=false` 并在 UI 里提示"请先在集群组件里安装对象存储"）。

**创建**：

1. `createBucket(clusterId, tenantId, name="milvus-<实例名>", quotaBytes=<storageSize>)`（走租户桶配额与命名规则）；
2. `issueCredential(bucket)` 拿 `accesskey` / `secretkey`；
3. endpoint 取 `seaweedS3InClusterEndpoint()`，去掉 `http://` 前缀 → `platform-seaweedfs-s3.platform-storage:8333`，`useSSL=false`；
4. CR 的 `config.milvus.minio.bucketName` = 上一步的桶名。

**删除**：

- `keepPvc=false` → `deleteBucket`（连带吊销凭据）；失败只记 WARN，不阻塞实例删除（桶可在对象存储页手工清理）；
- `keepPvc=true` → 桶与凭据保留，台账软删，供用户接管数据。

**回滚**：实例创建失败 → 删桶 + 吊销凭据（`deleteBucket` 已包含），再软删台账（沿用 2026-10-22 设计的"失败不留幽灵记录"）。

### 6.2 CUSTOM（用户自带 S3）

表单：`endpoint`（`host:port` 或带 scheme 的 URL，后端统一规整成 host:port）、`bucket`、`accessKey`、`secretKey`、`useSsl`。

- 平台只写 Secret，不建桶、不设配额；
- 删除实例只删 Secret（`keepPvc` 时保留），**不碰用户的桶**；
- `storageSize` 在 CUSTOM 下不展示、不使用。

### 6.3 Secret 形态

`<实例名>-milvus-storage`，写在租户命名空间：

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mw-milvus-12ab34cd-milvus-storage
  labels:
    platform.coding/managed-by: middleware
    platform.coding/middleware-instance: <实例 id>
stringData:
  accesskey: <AK>
  secretkey: <SK>
```

- 键名固定 `accesskey` / `secretkey`（operator 的 `AccessKey` / `SecretKey` 常量）；
- AK/SK **不落库、不记日志、不回传前端**（与"初始口令"同级）；`spec_json` 只记 `objectStorageType` / `objectStorageBucketId` / `objectStorageBucket` / `objectStorageQuota`；
- `usesPlatformPassword=false`（Milvus 无口令，平台不写凭据口令 Secret）。

---

## 7. 契约变更

### 7.1 `MiddlewareCreateRequest`（platform-api）+ `MiddlewareCreateRequestDTO`（common）

新增字段（其余中间件忽略）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `etcdReplicas` | Integer | 1–3；null → 单节点 1、集群 3 |
| `objectStorageType` | String | `PLATFORM` / `CUSTOM`；null → 报错（Milvus 必填） |
| `objectStorageEndpoint` | String | CUSTOM 必填 |
| `objectStorageBucket` | String | CUSTOM 必填（PLATFORM 由后端填） |
| `objectStorageAccessKey` | String | CUSTOM 必填 |
| `objectStorageSecretKey` | String | CUSTOM 必填 |
| `objectStorageUseSsl` | Boolean | null/false |
| `localStorageSize` | String | 单节点本地盘；null → `10Gi` |

`storageSize` 沿用通用语义，Milvus 下 = **对象存储容量**（PLATFORM 时作为桶配额）。

### 7.2 `MiddlewareFormOptionsDTO`

新增 `boolean objectStorageAvailable`（本集群是否已装对象存储组件；读失败按 false）。

### 7.3 `MiddlewareAdapter`

新增默认方法：

```java
/** 本中间件自己的 CR 异常判定（不改共享的 Ready/Available 规则）。null = 没发现问题。 */
default String crProblem(GenericKubernetesResource live) {
    return null;
}
```

调用点：`MiddlewareInstanceService` 状态判定里 `crFailureMessage(live)` 为空时取 `adapter.crProblem(live)`；`MiddlewareStatusEvaluator` 不变。

### 7.4 其他

- `MiddlewareDeleteRequestDTO`：不用改（`keepPvc` 已存在）；
- `MiddlewareScaleRequestDTO`：不用改（`replicas` / 资源 / `metricsEnabled` 够用）；
- 新增权限行：**无**（端点复用现有 middleware 权限码）。

---

## 8. 创建 / 删除 / 扩容时序

### 8.1 创建（platform-api）

```
1. 校验 catalog / mode / version / operator ready（沿用现状）
2. objectStorageType 必填（`PLATFORM` / `CUSTOM`）；PLATFORM 时断言对象存储组件已装；CUSTOM 时校验 endpoint / bucket / accessKey / secretKey 均非空；`etcdReplicas` 超出 1–3 直接拒
3. PLATFORM：createBucket → issueCredential（拿到 AK/SK）
4. 插入实例台账（spec_json 记 6.3 里的字段）
5. 组装 DTO → k8s-server create
       └ k8s-server：写存储 Secret → 创建 Milvus CR（单 CR）
6. 失败：
   ├ k8s-server 回滚已建的 Secret / CR（沿用现有创建回滚）
   ├ platform-api 删桶（PLATFORM 时）
   └ 软删台账，操作记录 FAILED（沿用现状）
```

### 8.2 删除

```
1. 校验归属
2. k8s-server：删 Milvus CR（keepPvc=false 时连带 PVC）、删存储 Secret（keepPvc=true 保留）
3. platform-api：PLATFORM 且 keepPvc=false → deleteBucket（失败仅 WARN）
4. 台账软删 + 操作记录
```

### 8.3 扩容

沿用现有 `scale`：先把请求转成 `MiddlewareScaleRequestDTO`，k8s-server 读线上 CR → 改字段 → SSA 整体提交；`metricsEnabled` 只建采集资源（`MetricsResources`），不改 CR。

---

## 9. 状态判定

- Pod 选择器：`app.kubernetes.io/instance=<CR 名>,app.kubernetes.io/name=milvus`（覆盖全部组件，也覆盖 standalone）。
- `desiredPods`：从 CR 的 `components` 里把**有 `replicas` 的组件**求和（standalone 只算 `standalone.replicas`；集群算 proxy/mixcoord/datanode/querynode/streamingnode，值为 0 的跳过）。读不到返回 null（退回"现存 Pod 全就绪"判定）。
- `crProblem(live)`：
  1. `status.status` 为 `Unhealthy` / `Stopped` → `"Milvus 状态 " + value`；
  2. 按优先级检查 conditions：`StorageReady` → `EtcdReady` → `MsgStreamReady` → `MilvusReady`，遇到 `status=False` 且 message 非空即返回（前缀标注是哪一项）。
  - 目的：桶配错、etcd 起不来时直接显示根因，而不是"0 个 Pod / 等待创建"。

---

## 10. 连接方式

`MiddlewareService.connection` 新增 `case "milvus"`：

| 行 | 值 |
|---|---|
| gRPC | `<cr 名>-milvus.<ns>.svc.cluster.local:19530` |
| 指标 / 健康检查 | `<cr 名>-milvus.<ns>.svc.cluster.local:9091` |
| 凭据（PLATFORM） | `Secret <cr 名>-milvus-storage（accesskey / secretkey）；桶 <桶名>` |
| 凭据（CUSTOM） | `Secret <cr 名>-milvus-storage（用户提供的 AK/SK）` |
| note | `Milvus 默认未开启鉴权；对外暴露前请自行控制网络访问（命名空间/网格策略）。` |

---

## 11. 业务指标

- 采集资源沿用 `MetricsResources`：Service `<cr 名>-platform-metrics` + ServiceMonitor；
- 端口 `9091`、路径 `/metrics`、scheme `http`；
- Service/Pod selector 与状态判定一致（`app.kubernetes.io/instance=<cr 名>,app.kubernetes.io/name=milvus`）；
- 集群形态下所有组件都暴露 9091，PromQL 用 `sum` / `max` 聚合。

指标声明（10 项，实施时按真集群 `/metrics` 输出核对标签与名称）：

| key | 形态 | PromQL（模板 `%s` = cluster_name / namespace / pod 正则） |
|---|---|---|
| `req` | CHART 请求/秒 | `sum(rate(milvus_proxy_req_count{cluster_name="%s",namespace="%s",pod=~"%s"}[2m]))` |
| `latency` | STAT 平均延迟(s) | `sum(rate(milvus_proxy_req_latency_sum{...}[2m])) / clamp_min(sum(rate(milvus_proxy_req_latency_count{...}[2m])), 0.001)` |
| `insert_vectors` | CHART 写入向量/秒 | `sum(rate(milvus_proxy_insert_vectors_count{...}[2m]))` |
| `delete_vectors` | CHART 删除向量/秒 | `sum(rate(milvus_proxy_delete_vectors_count{...}[2m]))` |
| `collections` | STAT 集合数 | `max(milvus_rootcoord_collection_num{...})` |
| `entities` | STAT 实体数 | `max(milvus_rootcoord_entity_num{...})` |
| `qn_entities` | STAT 查询节点实体数 | `sum(milvus_querynode_entity_num{...})` |
| `sq_req` | CHART 查询请求/秒 | `sum(rate(milvus_querynode_sq_req_count{...}[2m]))` |
| `rows` | CHART 行写入/秒 | `sum(rate(milvus_datanode_msg_rows_count{...}[2m]))` |
| `flush_bytes` | CHART 刷盘字节/秒 | `sum(rate(milvus_datanode_flushed_data_size{...}[2m]))` |

> 名称来自 Milvus v2.6.25 源码 `pkg/metrics/{proxy,rootcoord,querynode,datanode}_metrics.go`；`_sum/_count` 后缀与标签集以真集群为准，实施时逐项核对，核对结果写进组件 README。

---

## 12. 前端

### 12.1 商店卡片

- 名称 `Milvus`，描述「向量数据库（milvus-operator）」；
- 图标：`MiddlewareIcon` 的 `KNOWN` 增加 `{ keys: ['milvus'], icon: DataLine, color: '#00a1ea' }`。

### 12.2 创建表单

通用字段：形态 / 版本 / 命名空间 / 显示名 / CPU 请求·上限 / 内存请求·上限 / 节点数 / StorageClass。

Milvus 专属（`v-if="createItem?.code === 'milvus'"`）：

| 控件 | 条件 | 说明 |
|---|---|---|
| etcd 节点数（1–3） | 始终 | 默认：单节点 1、集群 3 |
| 对象存储来源（平台 / 自带） | 始终 | 平台选项在 `objectStorageAvailable=false` 时禁用并给提示 |
| 对象存储容量（Gi） | `PLATFORM` | 作为桶配额 |
| endpoint / bucket / AK / SK / TLS | `CUSTOM` | AK/SK 为密码框 |
| 本地数据盘（Gi） | `STANDALONE` | 默认 10Gi |
| 采集业务指标开关 | 始终 | 默认关 |

- 切形态时把节点数重置为固定值 / 1；
- 切存储来源时清掉另一侧的字段值；
- 默认选中「平台」当 `objectStorageAvailable=true`，否则选中「自带」；
- 创建成功后弹连接方式（沿用）。

### 12.3 详情 / 编辑

- 编辑弹窗只放：CPU、内存、节点数（集群）、采集业务指标；
- 存储来源、容量、etcd 节点数显示为只读行并提示「需删除后重建」；
- CR conditions 已在详情页通用展示（`conditionsOf`），不需改。

---

## 13. 权限与迁移

新迁移 `V2026_10_25_8__milvus_tenant_rbac.sql`（照 `V2026_10_22_5` 的写法：每条 UPDATE 自带 `NOT LIKE` 守卫、`--` 后必须有空白）：

```sql
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["milvus.io"],"resources":["milvuses","milvusclusters","milvusupgrades"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%milvus.io%';
```

**重放后必须点一次「重下发全部模板」（`POST /rbacTemplate/sync-all`）**，否则已有租户 ClusterRole 仍是旧 rules，创建 CR 会 403。

---

## 14. 测试与验证

### 14.1 单测

- `MilvusMiddlewareAdapterTest`：
  - standalone / cluster 两种 CR 结构（mode、queryNode.replicas、msgStreamType）；
  - PLATFORM / CUSTOM 两种存储（endpoint 规整、`useSSL` 两处一致、bucketName、secretRef）；
  - etcd 副本数与 resources / persistence；
  - 缺省值（etcdReplicas、localStorageSize、版本）；
  - `statusSelector`、`desiredPods`（standalone=1；cluster=5+queryNode 增量）；
  - `crProblem`（Unhealthy、StorageReady=False 带消息、消息为空不误报）。
- `MiddlewareServiceTest`：建桶 → k8s 失败 → 删桶回滚；删除时 `keepPvc` 两种分支；`objectStorageAvailable` 读失败降级 false。

### 14.2 真集群验证（实施完成的判据）

1. 装 Operator，状态 `READY`，三个 CRD `Established`；
2. 建单节点（PLATFORM 桶）：Secret 写入、CR `Healthy`、`19530` 可握手、`9091/metrics` 出数、写一条向量并检索成功；
3. 建集群形态（CUSTOM S3 或平台桶）：5 个组件 Pod 全就绪、woodpecker WAL 不额外拉 Pod、无 Pending；
4. 删单节点（`keepPvc=false`）：桶与凭据被回收；`keepPvc=true` 时桶保留；
5. 编辑：改资源、集群改查询节点数、开启业务指标（Service/ServiceMonitor 生成、指标面板出数）。

> 本次不验证「存量数据迁移/升级」（MilvusUpgrade 不在范围）。

---

## 15. 风险与取舍

| 风险 | 影响 | 处理 |
|---|---|---|
| SeaweedFS 与 Milvus S3 客户端兼容性（路径风格、region、TLS） | 平台桶接法可能连不上 | 先真跑验证；不通时 CUSTOM 接法先交付，平台桶单独排障（必要时在桶侧补 region/签名配置） |
| 平台桶被租户配额卡住（每租户桶数上限） | 创建失败 | 建桶失败直接把后端错误透出（"桶数量已达上限"） |
| etcd / Milvus 组件资源缺失 | Pod 被配额拒绝 | 5.4 强制写全 |
| Operator CRD 27k 行、`installCRDs:true` | 手工卸载会连 CRD 一起删 | 平台不提供卸载入口；README 写明 |
| Milvus 默认无鉴权 | 任何能访问 19530 的 Pod 都能读写 | 连接卡片明确提示；不擅自开鉴权（会与平台凭据模型冲突） |

---

## 16. 部署步骤（本批）

1. 重放 `V2026_10_25_8__milvus_tenant_rbac.sql`；
2. 调 `POST /rbacTemplate/sync-all`（重下发全部模板）；
3. 重建并发布 `k8s-server` / `platform-api` / `platform-web`；
4. 管理员在中间件商店装 Milvus Operator；
5. 建单节点实例验证，再建集群实例验证。
