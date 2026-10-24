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
- **对象级写操作（上传/删除）v1 不做**。

## 2. 数据模型

| 表 | 关键列 |
|---|---|
| `platform_object_bucket` | `cluster_id` / `bucket` / `tenant_id` / `status` / `deleted_at`；生成列 `active_key` 做「软删后可重建同名桶」的唯一约束 |
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
| `POST /buckets/create` | manage | 桶名 = `t-<租户短码>-<name>`；先校验配额再建 S3 桶 |
| `POST /buckets/delete` | manage | 非空桶由 S3 拒绝；成功后排队的凭据一并吊销并软删台账 |
| `POST /quota/get` · `/quota/set` | read · manage | 用量 + 上限 |
| `POST /credentials/list` | read | 只返回 accessKey |
| `POST /credentials/issue` | manage | 返回 AK/SK **一次**；台账只记 AK |
| `POST /credentials/get` | read | 按需读取 AK/SK（审计留痕） |
| `POST /credentials/revoke` | manage | 删 identity + 删 Secret + 滚动网关 |
| `POST /objects/list` · `/stat` | read | 前缀 + key 游标分页（S3 ListObjectsV2 语义） |
| `POST /objects/download` | read | 流式；**先同步 stat 判 ≤100MB**，再开始输出 |

对应 k8s-server：`/object-storage/**`（UPSTREAM 边界，桶归属校验在 platform-api）、
`/object-storage/config/ensure`（组件安装前确保配置 Secret）。

## 5. 页面

菜单「存储 → 对象存储」（页面码 `tenant:page:object-storage`）：

- 租户：只看自己名下的桶 → 浏览对象（前缀过滤 + 分页 + 下载）、看/取自己的凭据；
- 管理员：全部租户的桶、代建桶、设每租户上限、发/吊销凭据、删除桶。

## 6. 限制与已知问题

1. **只读对象**：上传/删除对象、建目录、改桶策略都没有做；
2. **下载 ≤100MB**：走平台代理（S3 网关只有 ClusterIP），大文件请用 SDK 直连；
3. **S3 地址是组件安装参数**：`s3Endpoint` 默认集群内地址；业务集群或平台跑在集群外时，
   要把 S3 暴露成 NodePort/LB（或本地 port-forward）并在组件表单里改；
4. **台账 vs S3 漂移**：别人用 admin 身份直接建/删桶时，平台页面看不出来；
   `ObjectStorageService.reconcile` 提供「S3 有、台账没有」的检查（v1 只报告，不自动接管）；
5. **凭据轮换**：吊销会立刻让使用方 403，滚动完成后生效；没有过期时间与自动轮换。

## 6.1 配置项（application.yaml）

两端都有 `platform.object-storage`，都可用环境变量覆盖：

| 模块 | 配置 | 默认 | 用途 |
|---|---|---|---|
| platform-api | `default-endpoint` | `http://seaweedfs-s3.platform-storage:8333` | 组件表单 `s3Endpoint` 的默认值 |
| platform-api | `default-max-buckets` | `10` | 未给租户单独设置时的桶数上限 |
| platform-api | `max-download-mb` | `100` | 控制台单次下载上限 |
| k8s-server | `namespace` / `config-secret` / `s3-deployment` | `platform-storage` / `platform-seaweedfs-s3-config` / `seaweedfs-s3` | 集群侧定位 SeaweedFS 组件 |
| k8s-server | `default-endpoint` | 同上 | 上游没带 endpoint 时的兜底 |

环境变量：`OBJECT_STORAGE_S3_ENDPOINT` / `OBJECT_STORAGE_DEFAULT_MAX_BUCKETS` /
`OBJECT_STORAGE_MAX_DOWNLOAD_MB` / `OBJECT_STORAGE_NAMESPACE` / `OBJECT_STORAGE_CONFIG_SECRET` /
`OBJECT_STORAGE_S3_DEPLOYMENT`。

## 7. 部署

1. 重放 `V2026_10_24_1__object_storage.sql`（表 + 页面码 + 权限行 + 角色绑定）与
   `V2026_10_24_2__object_storage_credentials_list_permission.sql`（补「凭据列表」端点）；
   ⚠️ **漏任何一条端点权限行，platform-api 启动期会 fail-closed**（`PermissionCrossCheckRunner`），
   这类漏配由 `ObjectStoragePermissionMigrationTest` 在单测里挡住；
2. 重建 `platform-common`、`k8s-server`（S3 客户端 + 新端点）、`platform-api`、`platform-web`；
3. 已装过 SeaweedFS 的集群：到「集群组件」对对象存储执行一次「编辑/重放」——
   `s3.existingConfigSecret` 会把配置切到平台管理（这一步是发凭据的前提）。
