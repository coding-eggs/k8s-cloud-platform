# S4.3 卷快照（VolumeSnapshot）设计

> 日期：2026-11-01。来源：`docs/task-ledger-2026-10-12.md` S4.3、`docs/gap-analysis-2026-10-12.md` §6.1。
> 本文修正 §6.1 的三处：门控不读组件状态、没有更新操作、快照类要按驱动匹配。

## 1. 为什么做

租户要能在改动数据前留一个恢复点，出问题时恢复成新 PVC。平台已经能从 `dataSourceRef` 建 PVC，
但造不出快照，只能手填快照名，整条路径走不通。

成功的标准：租户在页面上给一个 PVC 打快照，看到它变为可用，再恢复成新 PVC，并读到原来的数据。
失败时能在页面上直接看到原因。

## 2. 范围

**做**

- 租户视图：快照的列表、详情、创建、删除，从快照恢复为新 PVC。
- PVC 页：行操作「创建快照」；新建表单的 VolumeSnapshot 来源改为下拉选择。
- 平台视图：只读查看快照类和快照内容。
- 门控、权限行、租户 RBAC 模板。

**不做**

- 定时快照、保留策略、异地备份。
- 跨命名空间恢复（依赖 feature gate 和 ReferenceGrant）。
- 导入已有快照（手工建 `VolumeSnapshotContent`）。
- 平台创建或修改快照类：它由存储驱动决定。
- 中间件实例级的快照恢复：Operator 管理的 PVC 不能靠换卷恢复。
- 修改快照：spec 不可变，所以平台 API 没有 PUT。

**绝不能出现的情况**

- 页面显示「已创建」，但这个 PVC 的驱动根本做不了快照，用户要等很久才发现。
- 快照一直「进行中」却不给任何线索。
- 让用户以为快照等于备份。

## 3. 集群前提

需要三样东西：快照 CRD + `snapshot-controller`、支持快照的 CSI 驱动、至少一个快照类。
本平台提供了 `csi-driver-nfs` 组件，但**设计不依赖它**：其他驱动，或手工部署的同样可用。

## 4. 对象模型

CRD 组 `snapshot.storage.k8s.io/v1`，走现有通用 CRD 范式（参照 `ServiceMonitorOperations`），
每个资源单独一份 ops + converter，不抽公共基类。

| 资源 | 作用域 | 平台写 | 谁能读 |
|---|---|---|---|
| `VolumeSnapshot` | 命名空间 | 创建、删除 | 租户（租户 SA）、管理员 |
| `VolumeSnapshotClass` | 集群 | 否 | 管理员看完整对象；租户只拿窄投影（§5.2） |
| `VolumeSnapshotContent` | 集群 | 否 | 仅管理员 |

`VolumeSnapshotDTO` 的字段：

| 字段 | 来源 |
|---|---|
| `name`、`namespace`、`labels` 等基础字段 | metadata（沿用基类） |
| `creationTime` | `metadata.creationTimestamp` |
| `sourcePvcName` | `spec.source.persistentVolumeClaimName` |
| `volumeSnapshotClassName` | `spec.volumeSnapshotClassName` |
| `readyToUse` | `status.readyToUse` |
| `restoreSize` | `status.restoreSize`，用 `QuantityUtil` 转成字节的 BigDecimal |
| `errorMessage`、`errorTime` | `status.error` |
| `boundContentName` | `status.boundVolumeSnapshotContentName` |
| `snapshotTime` | `status.creationTime` |
| `deletionTimestamp` | `metadata.deletionTimestamp` |

## 5. 后端

### 5.1 k8s-server（只给集群事实）

| 端点 | 边界 | 说明 |
|---|---|---|
| `/volumesnapshots/**` | TENANT | 继承 `AbstractNamespacedResourceController`；ops 的 `update` 抛不支持；实现 `listAll` |
| `POST /volumesnapshotclasses/options` | UPSTREAM | 窄投影，见 §5.2 |
| `/volumesnapshotclasses/**`（其余） | PLATFORM | 继承 `AbstractClusterResourceController`，只读 |
| `/volumesnapshotcontents/**` | PLATFORM | 同上，只读 |

`ResourceType` 增加三项，并进入 `KubernetesOperationsFactory` 的 switch。

### 5.2 快照类窄投影

租户 SA 读不到集群级的快照类，而创建表单需要它。与 `StorageClassProvisionerController` 同样处理：
**单开一个 controller 类**声明 UPSTREAM（边界按类读取，不能加在 PLATFORM 的类上），用 admin client，
用 POST 避开 `GET /{name}`。

返回 `name`、`driver`、`deletionPolicy`、`isDefault`（注解
`snapshot.storage.kubernetes.io/is-default-class`）。**不返回 `parameters`**，那里可能有 secret 名。

CRD 不存在时（apiserver 404）返回 `crdInstalled=false`，不算错误。

已知且接受的残留：持任意 token 直连 k8s-server 能读到这四个字段，与 PV、节点地址、存储类窄投影相同，
不随租户变化，也不是越权看到别人的对象。

### 5.3 platform-api（产品判断）

新增 `VolumeSnapshotController` + `VolumeSnapshotService`，遵循 `docs/development/backend-layering.md`。

| 端点 | 权限码 | 说明 |
|---|---|---|
| `POST /volumesnapshots/capability` | `tenant:volumesnapshot:read` | 门控 + 表单候选，见下 |
| `POST /volumesnapshots/list` | `tenant:volumesnapshot:read` | |
| `GET /volumesnapshots/{name}` | `tenant:volumesnapshot:read` | |
| `GET /volumesnapshots/{name}/yaml` | `tenant:volumesnapshot:read` | |
| `POST /volumesnapshots` | `tenant:volumesnapshot:create` | |
| `DELETE /volumesnapshots/{name}` | `tenant:volumesnapshot:delete` | |
| `POST /volumesnapshotclasses/list`、`GET .../{name}` | `platform:cluster:manage` | 平台视图 |
| `POST /volumesnapshotcontents/list`、`GET .../{name}` | `platform:cluster:manage` | 平台视图 |

创建和删除快照的 controller 方法加 `@Auditable`，在 `AuditAction` 新增 `VOLUME_SNAPSHOT_CREATE`、
`VOLUME_SNAPSHOT_DELETE`：删除可能连带销毁底层数据，要留痕。恢复走 `POST /pvcs`，记为 `pvc:create`
（PVC 四个写接口的审计已先行加上）。审计不存请求体，所以这条记录分不出是不是从快照恢复。

**capability** 返回：

```json
{
  "available": true,
  "reason": null,
  "classes": [{ "name": "...", "driver": "...", "deletionPolicy": "Delete", "isDefault": true }],
  "storageClasses": [{ "name": "nfs-csi", "provisioner": "nfs.csi.k8s.io", "snapshotClasses": ["..."] }]
}
```

- `available=true` 要求 CRD 存在**且**至少一个快照类。缺 CRD 时 `reason=NO_CRD`，没有快照类时 `reason=NO_CLASS`。
- **活探测，不读 `k8s_cluster.capability`**：那一列要手工刷新才更新。
- **不读组件状态**：见 §3。
- 探测调用本身失败时报错，不显示成「不支持」，否则集群抖动会让入口消失。
- `storageClasses` 用现有的存储类窄投影，按 `provisioner == driver` 算出每个存储类可用的快照类。

**创建校验**（都要在下发前给出能看懂的错误）：

1. 来源 PVC 存在，且 phase 为 `Bound`。
2. PVC 的存储类有 provisioner，且等于所选快照类的 `driver`。否则提示「该 PVC 所在的存储类不支持快照」。
3. 没传快照类时，取该驱动的默认类；有且只有一个匹配类时取它；否则要求用户选择。

平台把解析出的类名写进 spec，不依赖集群默认值，这样详情页和审计都能看到实际用的是哪个类。

**恢复走现有的 `POST /pvcs`**，权限是 `tenant:pvc:create`，不新增端点。`PvcService.create` 在
`dataSourceRef.kind=VolumeSnapshot` 时增加校验：

1. 快照存在，且 `readyToUse=true`，并且不在删除中。
2. `namespace` 为空或等于 PVC 的命名空间。
3. `restoreSize` 非空时，容量不能小于它。
4. 目标存储类的 provisioner 等于快照所用类的 `driver`。这一项查不到存储类或快照类时不拦，交给集群判断。

持 `tenant:pvc:create` 但没有快照读权限的角色，也会因此读到快照状态；只回校验结果，不回对象内容，可以接受。

## 6. 前端

### 6.1 租户视图：快照页

路由 `resources/volumesnapshots`，域 `storage`，排在 PVC 之后，页面文件 `views/resource/VolumeSnapshotView.vue`。
页面按 `capability` 门控：不可用时显示说明（§6.5）和「刷新」按钮，**不隐藏菜单项**。

`capability` 要调接口才知道结果，且不同集群不同，所以门控放在页面内。这样用户也能知道为什么用不了。
这是对台账「整块隐藏」的有意修改。PVC 页的「创建快照」在不可用时才隐藏。

列：名称、来源 PVC、状态、大小（`restoreSize`）、快照类、创建时间、操作（恢复、查看 YAML、删除）。

状态是前端的纯函数，由浏览器验证覆盖：

| 条件（按顺序判断） | 显示 | 处理 |
|---|---|---|
| `deletionTimestamp` 非空 | 删除中 | 操作禁用；提示可能还有 PVC 在从它恢复 |
| `errorMessage` 非空 | 失败 | **原样显示错误信息** |
| `readyToUse=true` | 可用 | 可恢复 |
| `boundContentName` 为空且已超过 5 分钟 | 进行中 + 警告 | 提示「可能没有运行 snapshot-controller，或快照类配置有误」 |
| 其他 | 进行中 | 有 `boundContentName` 时不警告：NFS 打包大目录本来就慢 |

有未完成的快照时，列表每 5 秒轮询一次；全部结束或离开页面时停止。

### 6.2 创建快照

入口有两个：快照页的「创建」和 PVC 行操作（预填且锁定来源 PVC）。

字段：

- 名称：默认 `<pvc>-<yyyyMMddHHmmss>`，截断到 63 个字符以内。
- 来源 PVC：下拉，只列可用的；不支持快照的 PVC 显示为禁用项并说明原因。
- 快照类：候选按驱动过滤；只有一个时直接选中；紧跟着显示 `deletionPolicy` 的含义。

PVC 行操作在存储类不匹配时也**显示但禁用**，tooltip 说明原因。现有 `nfs-subdir` 上的 PVC 就是这种情况，
用户需要知道要先迁移。

### 6.3 恢复为新 PVC

快照行的「恢复」打开对话框，提交到 `POST /pvcs`：

- 名称：默认 `<快照名>-restore`（注意 63 字符上限）。
- 存储类：只列 provisioner 与快照驱动一致的；默认用来源 PVC 的存储类。
- 容量：默认取来源 PVC 容量和 `restoreSize` 中较大的；来源 PVC 已删除时取 `restoreSize`。下限是 `restoreSize`。
- 访问模式：默认取来源 PVC 的；来源已删除时默认 `ReadWriteOnce`，可改。

恢复总是建**新 PVC**，不覆盖原卷。成功后跳到 PVC 页，用户要自己改工作负载去挂载。

### 6.4 PVC 新建表单

`dsKind=VolumeSnapshot` 时：来源名称改为下拉（当前命名空间内 `readyToUse=true` 的快照），隐藏「来源命名空间」。
其他来源类型的行为不变。

### 6.5 必须写进界面的文案

| 位置 | 内容 |
|---|---|
| 创建对话框 | 快照不是备份：快照和原卷通常在同一套存储上，存储故障时会一起丢 |
| 创建对话框 | 只保证崩溃一致性：数据库等负载恢复后依靠自身恢复机制，打快照前最好先停写或刷盘 |
| 快照类旁 / 删除确认 | `Delete`：删除快照时底层数据一起删除，不可恢复。`Retain`：只删记录，底层数据保留，需要手工清理 |
| 恢复对话框 | 恢复后的 PVC 容量不能小于快照大小；恢复不影响原卷 |
| 门控 `NO_CRD` | 集群没有卷快照 API，请安装快照 CRD 和 snapshot-controller（可用组件「NFS CSI 驱动」） |
| 门控 `NO_CLASS` | 集群没有快照类，请联系管理员配置 |

### 6.6 平台视图

只读页面，两个 tab：快照类（名称、驱动、`deletionPolicy`、是否默认）、快照内容（名称、驱动、`deletionPolicy`、
绑定的快照、就绪状态、大小）。页面码 `platform:page:volume-snapshot-cluster`（只绑 `builtin_role_admin`），
接口码 `platform:cluster:manage`。租户页的页面码是 `tenant:page:volume-snapshots`。

快照内容用来发现 `Retain` 留下的孤儿：绑定的快照已不存在时标「孤儿」。平台不提供删除，也不提供跨命名空间的快照列表。

## 7. 权限与迁移

新文件 `V2026_11_01_3__volume_snapshot.sql`（编号以落地时最新为准），手工重放（本仓没有 Flyway）：

- 上表每个端点一行 API 权限，加页面权限行。照库里现有行写：`resource` **不带 `/resource` 前缀**。
- 角色绑定写显式 id，不用 LIKE，新行不会按 code 继承：
  - `builtin_role_tenant_admin`：read、create、delete。
  - `builtin_role_tenant_member`：只有 read。
  - `builtin_role_admin`：read、create、delete，和平台视图的行。
- 内置 RBAC 模板用 `JSON_ARRAY_APPEND` 追加一条规则，带 `NOT LIKE` 幂等守卫：

```json
{"apiGroups":["snapshot.storage.k8s.io"],"resources":["volumesnapshots"],"verbs":["*"]}
```

只放命名空间级的 `volumesnapshots`，**不放**快照类和快照内容。

部署顺序：重放迁移 → **「重下发全部模板」** → 重建 k8s-server、platform-api、platform-web → 重新登录或等 30 秒。
无权限行的新端点会被 `PermissionCrossCheckRunner` 拒启，所以迁移必须先于构建。

## 8. 错误与边界情况

| 情况 | 行为 |
|---|---|
| 快照名重复 | 返回已存在的错误，不覆盖 |
| 来源 PVC 正在创建快照时被删除 | PVC 会被 Kubernetes 的 finalizer 挡住，直到快照完成；PVC 页已有的 Terminating 展示足够 |
| 来源 PVC 已删除 | 快照仍可用、可恢复；来源列显示名称并标「已删除」 |
| 删除正在被恢复使用的快照 | 由 finalizer 拖住，显示「删除中」 |
| 恢复出的 PVC 一直 Pending | 平台不重复判断，PVC 页已有事件展示 |
| 集群切换 | 重新请求 `capability`，不跨集群缓存 |

**csi-driver-nfs 的两个特点（未实测）**：快照是 tar 包，`restoreSize` 可能是压缩后的大小，比原数据小。
所以容量默认值要取来源 PVC 容量和 `restoreSize` 的较大者，来源已删除时用户可能需要调大。
NFS 也不强制容量，所以小容量恢复不一定会报错。验收时要核实这一点，必要时调整文案。

## 9. 测试

| 层 | 覆盖 |
|---|---|
| k8s-core | converter 往返，含 error、restoreSize、无 status；ops 用 mock server 覆盖 CRUD、404、update 被拒 |
| k8s-server | 三类 controller 的边界声明；窄投影不含 `parameters`；CRD 不存在时 `crdInstalled=false` |
| platform-api | capability 的 `NO_CRD`、`NO_CLASS`、探测失败三种结果；创建校验四条；恢复校验四条及「查不到时不拦」 |
| 权限 | `PermissionCrossCheckRunner` 通过；角色绑定与 §7 一致；模板规则不含集群级资源 |
| platform-web | 类型检查和构建（前端没有单测框架，本批不引入） |
| 浏览器 | 假 token + XHR stub 接口：门控三种状态、状态判断的每一行、默认名称截断、容量默认值与下限、创建、恢复、删除确认 |

### 真集群验收（代码完成后）

1. 在 `nfs-csi` 上建 PVC，写入文件。
2. 页面创建快照，等到「可用」。
3. 恢复为新 PVC，挂载后读到文件。
4. 在 `nfs-subdir` 的 PVC 上确认「创建快照」被禁用，且原因清楚。
5. 故意选错快照类或停掉 controller，确认失败信息或超时警告可见。
6. 分别用 `Delete`、`Retain` 的类删除快照，确认 NFS 上的 tar 包和平台视图的孤儿标记符合预期。
7. 用租户成员账号确认只读。

## 10. 留给实施者决定

- 快照页是否拆子组件；对话框写法沿用现有页面。
- `capability` 的缓存：v1 不缓存。
- 列表轮询的实现：沿用 CI 或扩容页已有的做法。
