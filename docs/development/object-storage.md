# 对象存储（SeaweedFS 组件 · 桶与凭据）

> 组件安装见 `k8s-server/src/main/resources/charts/components/seaweedfs/README.md`。
> 本文覆盖平台上层的「桶 / 凭据 / 对象浏览」：数据模型、接口、权限、页面与限制。

## 1. 模型

| 概念 | 说明 |
|---|---|
| 集群级一套服务 | 每个集群装一套 SeaweedFS（master/volume/filer/s3），**不是**每租户一个实例 |
| 桶 | 归属一个租户；桶名由平台生成 `t-<租户短码>-<名字>`，跨租户不会撞名 |
| 凭据 | **每桶一组** AK/SK，权限是 bucket-scoped（`Read/Write/List/Tagging:<桶>`），只能碰这一个桶 |
| 配额 | 每租户每集群一个桶数上限（默认 10，管理员可改） |

**谁能做什么**（v1）：

- 建桶 / 删桶 / 设配额 / 发凭据 / 吊销凭据 = **平台管理员**（`platform:object-storage:manage`）；
- 看自己名下的桶与对象、取自己桶的凭据 = **租户**（`tenant:object-storage:read`）；
- 上传 / 删除**单个对象**（自己名下的桶）= **租户**（`tenant:object-storage:write`，2026-10-25 定）——
  写码与读码分开，「能看」不等于「能改」；
- **不做**：按前缀批量删、建目录、桶策略、对象复制；上传/下载经控制台各限 100MB。

## 2. 数据模型

| 表 | 关键列 |
|---|---|
| `platform_object_bucket` | `cluster_id` / `bucket` / `tenant_id` / `status` / `deleted_at` / **`quota_bytes`**（桶容量上限，NULL = 不限）；生成列 `active_key` 做「软删后可重建同名桶」的唯一约束 |
| `platform_object_credential` | `bucket_id` / `identity_name` / `access_key` / `secret_name` / `status`；**secretKey 不落库** |
| `platform_object_quota` | `(cluster_id, tenant_id)` → `max_buckets`；无记录 = 默认值 |

密钥本体在 K8s Secret：`platform-storage/platform-seaweedfs-cred-*`（key `access_key` / `secret_key`）。

## 3. 凭据是怎么工作的

```
应用（租户 Pod）── AK/SK 签名 ──▶ seaweedfs-s3 网关 ──▶ volume servers
                                  身份表来自 platform-seaweedfs-s3-config
```

- **数据面不经过平台**：应用直连网关，平台不查库、不加延迟；
- 平台只在**控制面**用自己的一把 `platform-admin` 身份做建/删桶、列对象、对账；
- 发放/吊销凭据 = 改配置 Secret + 滚动 `seaweedfs-s3`（秒级）。**吊销不是实时的**，最多差一次滚动；
- 静态配置**覆盖** filer 里的动态身份：admin UI 的 Users 页手工建账号不生效（凭据只能由平台发）。

## 4. 接口（platform-api `/object-storage/**`）

| 端点 | 权限 | 说明 |
|---|---|---|
| `POST /buckets/list` | read | 租户按归属收窄；管理员不传 tenantId 看全部 |
| `POST /buckets/stats` | read | 批量查桶用量（对象数/字节数）。S3 没有桶大小 API → 列举求和，单桶 5000 对象上限，到顶 `truncated=true`（页面显示「≥」） |
| `POST /buckets/quota` | manage | 改桶的**容量**上限（`null` = 不限）；**先写 S3 成功再更新台账**，避免页面显示一个没生效的上限 |
| `POST /buckets/create` | manage | 桶名 = `t-<租户短码>-<name>`；先校验配额再建 S3 桶；可带 `quotaBytes`（容量上限）——建桶后立刻写进 SeaweedFS，写失败会回滚刚建的空桶 |
| `POST /buckets/delete` | manage | 非空桶由 S3 拒绝；成功后排队的凭据一并吊销并软删台账 |
| `POST /quota/get` · `/quota/set` | read · manage | 租户的**桶数**配额（不是容量）；容量上限是每桶一条 |
| `POST /credentials/list` | read | 只返回 accessKey |
| `POST /credentials/issue` | manage | 返回 AK/SK **一次**；台账只记 AK |
| `POST /credentials/get` | read | 按需读取 AK/SK（审计留痕） |
| `POST /credentials/revoke` | manage | 删 identity + 删 Secret + 滚动网关 |
| `POST /objects/list` | read | 前缀 + key 游标分页（S3 ListObjectsV2 语义）；传 `delimiter="/"` 时按目录列——当前层文件在 `items`、子目录在 `folders`（CommonPrefixes） |
| `POST /objects/upload` | write | 单个对象；**请求体是原始字节流**（不 multipart/不缓冲），`size` 必带——读流之前拒超限，下游 MinIO 也要它 |
| `POST /objects/delete` | write | 单个对象；不存在也算成功（S3 语义）。**不做按前缀批量删** |
| `POST /objects/stat` | read | 对象元数据（大小/etag/修改时间）；下载前的大小校验也走它 |
| `POST /objects/download` | read | 流式；**先同步 stat 判 ≤100MB**，再开始输出 |

对应 k8s-server：`/object-storage/**`（UPSTREAM 边界，桶归属校验在 platform-api）、
`/object-storage/config/ensure`（组件安装前确保配置 Secret）。

## 5. 页面

菜单「存储 → 对象存储」（页面码 `tenant:page:object-storage`）：

- 租户：只看自己名下的桶 → 点桶名进入**文件浏览器**（目录下钻 + 面包屑 + 上一级 + 分页 + 下载）；
  有写权限时可**上传到当前目录**、**删除单个文件**；看/取自己的凭据；
- 管理员：全部租户的桶、**当前大小 / 容量上限**（`50.00 MiB / 1.00 GiB`，未设上限只显示用量）、
  代建桶（可填容量上限）、行内「配额」改已有桶的上限、设每租户的**桶数**上限、发/吊销凭据、删除桶。

## 6. 限制与已知问题

1. **写操作只到单对象**：上传（≤100MB，流式直传不落临时文件）与删除单个对象；
   没有按前缀批量删（S3 的"目录"只是前缀，误点会删一片）、没有建目录、没有桶策略；
2. **容量上限按桶设置，没有租户级总量上限**：
   - 建桶时可填 `quotaBytes`，经 k8s-server 用 **SigV4** 调 SeaweedFS 的 `PUT /{bucket}?seaweedfs-quota` 写进 S3；
   - 强制执行在 S3 侧：网关每分钟把超限桶标成 readOnly → 写入/上传分片/拷贝返回 **403 AccessDenied**，
     **删除仍允许**，回落到限额下会自动恢复（4.48 已含自动 enforce，不需要外挂脚本）；
   - 所以是**软上限**：两次检查之间可以短暂超限；
   - 列表页的「当前大小」是列举求和（单桶 5000 对象上限，超了显示「≥」），是下限口径，不是精确用量；
3. **上传/下载各 ≤100MB**：都走平台代理（S3 网关只有 ClusterIP），更大的请用 SDK 直连；
4. **两个「S3 地址」别混**（2026-10-25 定）：
   - **平台自己连 S3** → **每集群一条连接配置**：`k8s_cluster.object_storage_url`
     （集群管理 → 编辑 →「对象存储地址」）。空 = 按组件安装位置推导集群内地址
     （`http://seaweedfs-s3.platform-storage:8333`）。平台在集群外或业务集群时填暴露后的地址；
   - **组件表单里的 `s3Endpoint`** → 这个 S3 **对外**给应用 / 其它集群用的入口，随安装声明进操作快照；
     平台不使用它。要让集群外访问，先把 `seaweedfs-s3` 暴露成 NodePort/LB 再填这里。
5. **台账 vs S3 漂移**：别人用 admin 身份直接建/删桶时，平台页面看不出来；
   `ObjectStorageService.reconcile` 提供「S3 有、台账没有」的检查（v1 只报告，不自动接管）；
6. **凭据轮换**：吊销会立刻让使用方 403，滚动完成后生效；没有过期时间与自动轮换。

## 6.1 配置项

**平台访问某个集群 S3 的地址**不在 application.yaml 里，而是每集群一条（`k8s_cluster.object_storage_url`，
集群管理 → 编辑 →「对象存储地址」）：空 = 按组件安装位置推导
`http://seaweedfs-s3.<recipe namespace>:8333`。理由：地址取决于「哪个集群 + 平台从哪一侧访问」，
配置在一处就只对单集群成立（与 `prometheus_url` 同形）。

application.yaml 里只剩平台策略（两端都有 `platform.object-storage`，可用环境变量覆盖）：

| 模块 | 配置 | 默认 | 用途 |
|---|---|---|---|
| platform-api | `default-max-buckets` | `10` | 未给租户单独设置时的桶数上限 |
| platform-api | `max-download-mb` | `100` | 控制台单次下载上限 |
| platform-api | `max-upload-mb` | `100` | 控制台单次上传上限（前端也按这个值先挡一次） |
| k8s-server | `namespace` / `config-secret` / `s3-deployment` | `platform-storage` / `platform-seaweedfs-s3-config` / `seaweedfs-s3` | 集群侧定位 SeaweedFS 组件 |

环境变量：`OBJECT_STORAGE_DEFAULT_MAX_BUCKETS` / `OBJECT_STORAGE_MAX_DOWNLOAD_MB` /
`OBJECT_STORAGE_MAX_UPLOAD_MB` / `OBJECT_STORAGE_NAMESPACE` / `OBJECT_STORAGE_CONFIG_SECRET` /
`OBJECT_STORAGE_S3_DEPLOYMENT`。

## 7. 部署

1. 重放 `V2026_10_25_1__cluster_object_storage_url.sql`（`k8s_cluster.object_storage_url`）、
   `V2026_10_25_2__object_storage_write.sql`（上传/删除端点的权限行）、
   `V2026_10_25_3__object_storage_bucket_stats_permission.sql`（桶用量端点）、
   `V2026_10_25_4__object_bucket_quota.sql`（桶容量上限列）、
   `V2026_10_25_5__object_storage_bucket_quota_set_permission.sql`（改配额的端点），
   再重放 `V2026_10_24_1__object_storage.sql`（表 + 页面码 + 权限行 + 角色绑定）与
   `V2026_10_24_2__object_storage_credentials_list_permission.sql`（补「凭据列表」端点）；
   ⚠️ **漏任何一条端点权限行，platform-api 启动期会 fail-closed**（`PermissionCrossCheckRunner`），
   这类漏配由 `ObjectStoragePermissionMigrationTest` 在单测里挡住；
2. 重建 `platform-common`、`platform-data`、`k8s-server`（S3 客户端 + 新端点）、`platform-api`、`platform-web`；
3. 已装过 SeaweedFS 的集群：到「集群组件」对对象存储执行一次「编辑/重放」——
   `s3.existingConfigSecret` 会把配置切到平台管理（这一步是发凭据的前提）。
   - 页面提示「对象存储组件尚未安装」= 该集群没有成功操作记录，装一次即可；
   - 平台连不上 S3（连接超时 / 解析失败 / 403）时查两处：集群管理里的「对象存储地址」是否指向
     平台这侧打得通的地址（留空推导的是 ClusterIP 名字，平台在集群外必定解析不了；集群外要先把
     `seaweedfs-s3` 暴露成 NodePort/LB 或起 port-forward，注意 NodePort 默认区间 30000–32767）；
     以及 S3 网关是否已挂平台配置（没挂时 k8s-server 会明确提示重放）。
