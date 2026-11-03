# S4.3 卷快照 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 租户能在页面上给 PVC 打快照、看到状态、恢复成新 PVC；平台管理员能只读查看快照类和快照内容。

**Architecture:** 三个 CRD 走现有通用 CRD 范式（ops + converter，逐资源各写一份）。k8s-server 只给集群事实，门控和校验放在 platform-api。租户读快照类走 UPSTREAM 窄投影。恢复复用 `POST /pvcs`。

**Tech Stack:** Java 21、Spring Boot、fabric8 `genericKubernetesResources`、MyBatis 手工迁移 SQL、Vue 3 + Element Plus。

**Spec:** `docs/superpowers/specs/2026-11-01-volume-snapshot-design.md`

## Global Constraints

- CRD 组版本固定 `snapshot.storage.k8s.io/v1`；不做 v1beta1 回退。
- 同类资源不抽公共基类，三个资源各写一份。k8s-server 不放业务判断。
- 窄投影只返回 `name`、`driver`、`deletionPolicy`、`isDefault`，**绝不返回 `parameters`**。
- 权限行 `resource` 不带 `/resource` 前缀；角色绑定写显式 id，不用 LIKE；新行不按 code 继承。
- RBAC 模板只加 `volumesnapshots`，不加快照类和快照内容。
- 门控活探测：不读 `k8s_cluster.capability`，不读组件状态。探测调用失败要报错，不能当作「不支持」。
- 恢复只建新 PVC，只允许同命名空间。
- 不做定时快照、导入已有快照、平台写快照类、平台删快照内容。
- 工作区里有无关的未提交改动：每次提交都按文件名 `git add`，**绝不要 `git add -A`**，尤其不要带上 `../../dbback/k8s_cloud_platform.sql`。
- 提交信息结尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。
- Maven 统一用：`mvn -o test -pl <模块> -am -Dtest=<类> -Dsurefire.failIfNoSpecifiedTests=false`。

## Review Focus

1. **PVC 没有 `storageClassName`，或存储类是静态供应**：创建被拒，提示「该 PVC 所在的存储类不支持快照」，不能 NPE。测试放 Task 5。
2. **探测失败和 CRD 缺失混淆**：k8s-server 返回 500 时 capability 必须抛错；只有 `crdInstalled=false` 才算 `NO_CRD`。测试放 Task 3、Task 5。
3. **恢复请求带别的命名空间，或快照不存在**：`dataSourceRef.namespace` 不等于 PVC 命名空间时明确拒绝，快照不存在时给出可读错误，都不能透传成 K8s 原话。测试放 Task 6。
4. **`restoreSize` 为 null 或 0**（NFS 驱动可能如此）：不能拦截恢复，也不能 NPE。测试放 Task 6。
5. **很长的 PVC 名**：默认快照名、恢复名都不能超过 63 个字符，且截断后结尾不能是 `-`。放 Task 8 的工具函数，并在 Task 9 的浏览器验证里覆盖。

---

## File Structure

| 路径 | 职责 |
|---|---|
| `platform-common/.../dto/VolumeSnapshotDTO.java`、`VolumeSnapshotClassDTO.java`、`VolumeSnapshotContentDTO.java` | 三个资源的 DTO |
| `platform-common/.../dto/VolumeSnapshotClassOptionDTO.java`、`VolumeSnapshotClassOptionsDTO.java` | 窄投影 |
| `k8s-core/.../operations/snapshot/*Operations.java`、`converter/impl/snapshot/*Converter.java` | 集群读写 |
| `k8s-server/.../controllers/namespace/VolumeSnapshotController.java`、`controllers/cluster/VolumeSnapshotClassController.java`、`VolumeSnapshotContentController.java`、`VolumeSnapshotClassOptionsController.java` | 适配器端点 |
| `platform-data/.../migration/V2026_11_01_3__volume_snapshot.sql` | 权限行、角色绑定、RBAC 模板 |
| `platform-api/.../k8s/VolumeSnapshotClassClient.java` | 调窄投影 |
| `platform-api/.../services/VolumeSnapshotService.java`、`controllers/VolumeSnapshotController.java`、`models/VolumeSnapshotCapability.java` | 租户接口、门控、创建校验 |
| `platform-api/.../services/VolumeSnapshotClusterService.java`、`controllers/VolumeSnapshotClusterController.java` | 平台只读接口 |
| `platform-web/src/utils/volumeSnapshot.ts` | 状态、默认名、容量默认值等纯函数 |
| `platform-web/src/views/resource/VolumeSnapshotView.vue`、`components/storage/VolumeSnapshotCreateDialog.vue`、`VolumeSnapshotRestoreDialog.vue` | 租户页面 |
| `platform-web/src/views/resource/VolumeSnapshotClusterView.vue` | 平台页面 |

路径前缀：`platform-common/src/main/java/com/coding/common/models/k8s`、`k8s-core/src/main/java/com/coding/k8score`、`k8s-server/src/main/java/com/coding/k8sserver`、`platform-api/src/main/java/com/coding/platformapi`、`platform-data/src/main/resources/db/migration`。

---

### Task 1: VolumeSnapshot 的 DTO、converter、ops

**Files:**
- Create: `VolumeSnapshotDTO.java`、`operations/snapshot/VolumeSnapshotOperations.java`、`converter/impl/snapshot/VolumeSnapshotConverter.java`
- Modify: `ResourceType.java`、`factory/KubernetesOperationsFactory.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/snapshot/VolumeSnapshotOperationsTest.java`、`converter/impl/snapshot/VolumeSnapshotConverterTest.java`

**Interfaces:**
- Produces: `ResourceType.VOLUME_SNAPSHOT`；`VolumeSnapshotDTO extends BaseResources`，`getApiPath()` 返回 `/volumesnapshots`，字段：`String sourcePvcName`、`String volumeSnapshotClassName`、`Boolean readyToUse`、`BigDecimal restoreSize`、`String errorMessage`、`String errorTime`、`String boundContentName`、`String snapshotTime`（`status.creationTime`）、`String creationTime`（`metadata.creationTimestamp`）、`String deletionTimestamp`。

- [ ] **Step 1: 写 converter 失败测试**

`revertReadsStatus`：输入 `status.readyToUse=true`、`status.restoreSize=1Gi`、`status.boundVolumeSnapshotContentName=c1`，断言 `readyToUse==true`、`restoreSize==1073741824`、`boundContentName=="c1"`。
`revertSurfacesError`：`status.error.message="boom"`，断言 `errorMessage=="boom"`，`readyToUse` 为 null。
`revertWithoutStatus`：无 status，所有 status 字段为 null，不抛异常。
`convertWritesOnlySpec`：`sourcePvcName="data"`、`volumeSnapshotClassName="cls"`，断言 `spec.source.persistentVolumeClaimName=="data"`、`spec.volumeSnapshotClassName=="cls"`，且输出没有 `status`。

- [ ] **Step 2: 写 ops 失败测试**

参照 `CoreV1StorageClassOperationsTest` 的写法。
`createDelegatesToApi`、`listReturnsItems`、`listAllSpansNamespaces`。
`updateIsRejected`：`update(...)` 抛 `CloudPlatformException`，`EnumResponseType.OPERATION_NOT_SUPPORTED`。

- [ ] **Step 3: 运行，确认失败**

Run: `mvn -o test -pl k8s-core -am -Dtest='VolumeSnapshot*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，类不存在。

- [ ] **Step 4: 实现**

结构照抄 `ServiceMonitorOperations` / `ServiceMonitorConverter`，`CustomResourceDefinitionContext` 取 group `snapshot.storage.k8s.io`、version `v1`、kind `VolumeSnapshot`、plural `volumesnapshots`、scope `Namespaced`。`restoreSize` 用 `QuantityUtil.toBase`。`ResourceType` 加枚举值，factory 的 switch 加分支。

- [ ] **Step 5: 运行，确认通过，然后提交**

```bash
git add <上述文件>
git commit -m "feat(snapshot): VolumeSnapshot 的 DTO 与操作层"
```

---

### Task 2: 快照类、快照内容的只读操作层

**Files:**
- Create: `VolumeSnapshotClassDTO.java`、`VolumeSnapshotContentDTO.java`、对应 `operations/snapshot/*Operations.java`、`converter/impl/snapshot/*Converter.java`
- Modify: `ResourceType.java`、`KubernetesOperationsFactory.java`
- Test: `k8s-core/src/test/.../snapshot/VolumeSnapshotClassOperationsTest.java`、`VolumeSnapshotContentOperationsTest.java`

**Interfaces:**
- Produces: `ResourceType.VOLUME_SNAPSHOT_CLASS`、`VOLUME_SNAPSHOT_CONTENT`，都走 `ClusterOperations`。
- `VolumeSnapshotClassDTO`（apiPath `/volumesnapshotclasses`）：`String driver`、`String deletionPolicy`、`Boolean isDefault`、`Map<String,String> parameters`。
- `VolumeSnapshotContentDTO`（apiPath `/volumesnapshotcontents`）：`String driver`、`String deletionPolicy`、`String volumeSnapshotClassName`、`String snapshotName`、`String snapshotNamespace`、`Boolean readyToUse`、`BigDecimal restoreSize`、`String snapshotHandle`、`Boolean orphan`（k8s 层不填，Task 7 填）。

- [ ] **Step 1: 写失败测试**

`classReadsDefaultAnnotation`：注解 `snapshot.storage.kubernetes.io/is-default-class: "true"` → `isDefault==true`；无注解 → `false`。`classCreateIsRejected`、`contentDeleteIsRejected`：写操作都抛 `OPERATION_NOT_SUPPORTED`。`contentReadsSnapshotRef`：`spec.volumeSnapshotRef.{name,namespace}`、`status.restoreSize`（**整数字节，不是 Quantity**）、`status.snapshotHandle`。

- [ ] **Step 2: 运行确认失败，实现，运行确认通过**

Run: `mvn -o test -pl k8s-core -am -Dtest='VolumeSnapshot*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 先编译失败，实现后全部 PASS。

- [ ] **Step 3: 提交**

```bash
git add <上述文件>
git commit -m "feat(snapshot): 快照类与快照内容的只读操作层"
```

---

### Task 3: k8s-server 端点

**Files:**
- Create: 四个 controller（见 File Structure）；`VolumeSnapshotClassOptionDTO.java`、`VolumeSnapshotClassOptionsDTO.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/controllers/cluster/VolumeSnapshotClassOptionsRouteTest.java`

**Interfaces:**
- Consumes: Task 1、2 的 `ResourceType`。
- Produces: `POST /volumesnapshotclasses/options`，入参 `VolumeSnapshotClassDTO`（只用 `clusterId`），返回 `VolumeSnapshotClassOptionsDTO { boolean crdInstalled; List<VolumeSnapshotClassOptionDTO> classes }`；`VolumeSnapshotClassOptionDTO { String name; String driver; String deletionPolicy; Boolean isDefault }`。

- [ ] **Step 1: 写失败测试（照 `StorageClassProvisionerRouteTest`）**

`optionsNeverExposeParameters`：ops 返回带 `parameters` 的类，响应 JSON 里 `$.data.classes[0].parameters` 不存在，`name`、`driver`、`deletionPolicy`、`isDefault` 都对。
`missingCrdIsNotAnError`：`ops.list` 抛 `KubernetesClientException`（code 404），响应 200，`crdInstalled==false`，`classes` 为空。
`otherFailuresPropagate`：code 500 不被吞，响应非 200。
`boundaryIsUpstream`：`accessBoundary()==AccessBoundary.UPSTREAM`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -o test -pl k8s-server -am -Dtest=VolumeSnapshotClassOptionsRouteTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`VolumeSnapshotController extends AbstractNamespacedResourceController`，`@RequestMapping("/volumesnapshots")`。`VolumeSnapshotClassController`、`VolumeSnapshotContentController extends AbstractClusterResourceController`。`VolumeSnapshotClassOptionsController implements AccessBoundaryAware`，形状与 `StorageClassProvisionerController` 相同，先 `accessResolver.assertClusterAccess`，只捕获 404 的 `KubernetesClientException`。类注释写明 UPSTREAM 残留已接受（spec §5.2）。

- [ ] **Step 4: 运行确认通过，并跑 `BoundaryAuthorizationManagerTest`，然后提交**

Run: `mvn -o test -pl k8s-server -am -Dtest='VolumeSnapshot*Test,BoundaryAuthorizationManagerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

```bash
git add <上述文件>
git commit -m "feat(snapshot): k8s-server 快照端点与快照类窄投影"
```

---

### Task 4: 迁移 SQL

**Files:**
- Create: `V2026_11_01_3__volume_snapshot.sql`（落地时若编号已被占用，顺延）

**Interfaces:**
- Produces 权限码：`tenant:volumesnapshot:read|create|delete`、`tenant:page:volume-snapshots`、`platform:page:volume-snapshot-cluster`；平台接口复用 `platform:cluster:manage`。

- [ ] **Step 1: 按下表写权限行（显式 id + ODKU，文件头沿用 `V2026_10_16_3` 的部署警告）**

| id | domain | resource | action | code |
|---|---|---|---|---|
| `perm_res_vs_capability` | API | `/volumesnapshots/capability` | POST | `tenant:volumesnapshot:read` |
| `perm_res_vs_list` | API | `/volumesnapshots/list` | POST | `tenant:volumesnapshot:read` |
| `perm_res_vs_get` | API | `/volumesnapshots/{name}` | GET | `tenant:volumesnapshot:read` |
| `perm_res_vs_yaml` | API | `/volumesnapshots/{name}/yaml` | GET | `tenant:volumesnapshot:read` |
| `perm_res_vs_create` | API | `/volumesnapshots` | POST | `tenant:volumesnapshot:create` |
| `perm_res_vs_delete` | API | `/volumesnapshots/{name}` | DELETE | `tenant:volumesnapshot:delete` |
| `perm_res_vsclass_list` | API | `/volumesnapshotclasses/list` | POST | `platform:cluster:manage` |
| `perm_res_vsclass_get` | API | `/volumesnapshotclasses/{name}` | GET | `platform:cluster:manage` |
| `perm_res_vscontent_list` | API | `/volumesnapshotcontents/list` | POST | `platform:cluster:manage` |
| `perm_res_vscontent_get` | API | `/volumesnapshotcontents/{name}` | GET | `platform:cluster:manage` |
| `perm_page_volume_snapshots` | Page | `/resources/volumesnapshots` | VIEW | `tenant:page:volume-snapshots` |
| `perm_page_volume_snapshot_cluster` | Page | `/volume-snapshot-cluster` | VIEW | `platform:page:volume-snapshot-cluster` |

- [ ] **Step 2: 写角色绑定（显式 id）**

- `builtin_role_tenant_admin`：`perm_res_vs_*`、`perm_page_volume_snapshots`。
- `builtin_role_tenant_member`：四条 read（capability、list、get、yaml）和 `perm_page_volume_snapshots`。
- `builtin_role_admin`：上表全部。

- [ ] **Step 3: 写 RBAC 模板更新**

`JSON_ARRAY_APPEND(rules, '$.rules', CAST('{"apiGroups":["snapshot.storage.k8s.io"],"resources":["volumesnapshots"],"verbs":["*"]}' AS JSON))`，条件 `built_in = 1 AND deleted_at IS NULL AND rules NOT LIKE '%snapshot.storage.k8s.io%'`。文件头写明：重放后必须「重下发全部模板」。

- [ ] **Step 4: 校验语法后提交**

人工确认每个 `--` 后面都有空白字符。迁移由 Task 11 在开发库重放，这一步不连库。

```bash
git add platform-data/src/main/resources/db/migration/V2026_11_01_3__volume_snapshot.sql
git commit -m "feat(snapshot): 快照权限行、角色绑定与租户 RBAC 规则"
```

---

### Task 5: 门控与创建校验（platform-api）

**Files:**
- Create: `k8s/VolumeSnapshotClassClient.java`、`services/VolumeSnapshotService.java`、`controllers/VolumeSnapshotController.java`、`models/VolumeSnapshotCapability.java`
- Modify: `audit/AuditAction.java`、`services/StorageClassService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/VolumeSnapshotServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的窄投影。
- Produces:
  - `VolumeSnapshotClassClient.options(String clusterId): VolumeSnapshotClassOptionsDTO`
  - `StorageClassService.provisioners(String clusterId): List<StorageClassProvisionerDTO>`
  - `VolumeSnapshotCapability` record：`boolean available`、`String reason`（`NO_CRD` / `NO_CLASS` / null）、`List<VolumeSnapshotClassOptionDTO> classes`、`List<StorageClassEntry> storageClasses`；`StorageClassEntry(String name, String provisioner, List<String> snapshotClasses)`。
  - `VolumeSnapshotService.capability(VolumeSnapshotDTO query): VolumeSnapshotCapability`
  - `VolumeSnapshotService.resolveClassFor(PersistentVolumeClaimDTO pvc, String requestedClass, String clusterId): String`（解析和校验都在这里；`create` 调用它）。
  - `AuditAction.VOLUME_SNAPSHOT_CREATE = "volumesnapshot:create"`、`VOLUME_SNAPSHOT_DELETE = "volumesnapshot:delete"`。

- [ ] **Step 1: 写失败测试**

| 测试 | 断言 |
|---|---|
| `capabilityNoCrd` | `crdInstalled=false` → `available=false`，`reason="NO_CRD"` |
| `capabilityNoClass` | CRD 在、类为空 → `reason="NO_CLASS"` |
| `capabilityMapsStorageClassesByDriver` | 存储类 `a`（`nfs.csi.k8s.io`）对应类 `c1`；`b`（`cluster.local/nfs-subdir`）的 `snapshotClasses` 为空 |
| `capabilityProbeFailurePropagates` | client 抛异常时，`capability` 也抛异常，不返回 `available=false` |
| `createRejectsMissingPvc` | PVC 不存在 → 错误信息含 PVC 名 |
| `createRejectsUnboundPvc` | phase=`Pending` → 错误信息含「未绑定」 |
| `createRejectsPvcWithoutStorageClass` | `storageClassName=null` → 「该 PVC 所在的存储类不支持快照」 |
| `createRejectsDriverMismatch` | 指定的类 driver ≠ provisioner → 同上文案 |
| `createPicksDefaultClass` | 未指定，两个匹配类、其一 `isDefault` → 写入默认类 |
| `createPicksOnlyMatch` | 未指定，只有一个匹配类 → 写入它 |
| `createRequiresChoiceWhenAmbiguous` | 未指定，两个匹配类都不是默认 → 错误信息含「请选择快照类」 |

- [ ] **Step 2: 运行确认失败**

Run: `mvn -o test -pl platform-api -am -Dtest=VolumeSnapshotServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现 service 和 controller**

端点见 spec §5.3，形状照 `PvcController`（`/volumesnapshots`，没有 PUT）。`POST /volumesnapshots` 和 `DELETE /volumesnapshots/{name}` 加 `@Auditable`。PVC 用 `K8sClient.get` 读（走租户身份），存储类和快照类用窄投影。

- [ ] **Step 4: 运行确认通过，并跑 `AuditActionTest`，然后提交**

Run: `mvn -o test -pl platform-api -am -Dtest='VolumeSnapshotServiceTest,AuditActionTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

```bash
git add <上述文件>
git commit -m "feat(snapshot): 快照门控、创建校验与租户接口"
```

---

### Task 6: 从快照恢复的校验

**Files:**
- Modify: `services/PvcService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PvcServiceTest.java`

**Interfaces:**
- Consumes: `VolumeSnapshotClassClient.options`、`StorageClassService.provisioners`。
- Produces: `PvcService` 构造函数增加 `VolumeSnapshotClassClient`。

- [ ] **Step 1: 写失败测试（`create` 带 `dataSourceRef.kind=VolumeSnapshot`）**

| 测试 | 断言 |
|---|---|
| `restoreRejectsOtherNamespace` | `dataSourceRef.namespace="ns2"`、PVC 在 `ns1` → 错误含「同一命名空间」，未调用 `k8s.create` |
| `restoreRejectsMissingSnapshot` | 快照不存在 → 错误含快照名 |
| `restoreRejectsNotReady` | `readyToUse=false` → 错误含「尚未可用」 |
| `restoreRejectsDeleting` | `deletionTimestamp` 非空 → 错误含「正在删除」 |
| `restoreRejectsTooSmall` | `storage` 小于 `restoreSize` → 错误含快照大小 |
| `restoreAllowsNullOrZeroRestoreSize` | `restoreSize=null`、`0` 各一次 → 正常创建 |
| `restoreRejectsDriverMismatch` | 目标存储类 provisioner ≠ 快照类 driver → 拒绝 |
| `restoreDoesNotBlockWhenDriverUnknown` | 目标存储类或快照类查不到 → 正常创建 |
| `nonSnapshotDataSourceIsUntouched` | `kind=PersistentVolumeClaim` → 不查快照，正常创建 |

- [ ] **Step 2: 运行确认失败，实现，运行确认通过**

Run: `mvn -o test -pl platform-api -am -Dtest=PvcServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 先有 FAIL，实现后 PASS。

实现：`create` 开头判断 `apiGroup=snapshot.storage.k8s.io` 且 `kind=VolumeSnapshot`，调私有方法 `validateRestore`；存量测试的构造函数同步更新。

- [ ] **Step 3: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/PvcService.java platform-api/src/test/java/com/coding/platformapi/services/PvcServiceTest.java
git commit -m "feat(snapshot): 从快照恢复 PVC 的校验"
```

---

### Task 7: 平台只读接口

**Files:**
- Create: `services/VolumeSnapshotClusterService.java`、`controllers/VolumeSnapshotClusterController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/VolumeSnapshotClusterServiceTest.java`

**Interfaces:**
- Produces: spec §5.3 的四个平台端点；`listContents(VolumeSnapshotContentDTO): List<VolumeSnapshotContentDTO>`，填 `orphan`。

- [ ] **Step 1: 写失败测试**

`contentIsOrphanWhenSnapshotGone`：内容指向的 `ns/name` 不在 `listAll` 结果里 → `orphan=true`；存在 → `false`。
`listAllFailureLeavesOrphanNull`：`listAll` 抛异常 → 内容照常返回，`orphan=null`，不误标孤儿。

- [ ] **Step 2: 运行确认失败，实现，运行确认通过，然后提交**

Run: `mvn -o test -pl platform-api -am -Dtest=VolumeSnapshotClusterServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 先编译失败，实现后 PASS。

```bash
git add <上述文件>
git commit -m "feat(snapshot): 平台只读查看快照类与快照内容"
```

---

### Task 8: 前端类型、API、纯函数

**Files:**
- Create: `platform-web/src/utils/volumeSnapshot.ts`
- Modify: `platform-web/src/types.ts`、`src/api/index.ts`、`src/pageCodes.ts`

**Interfaces:**
- Produces:
  - `snapshotState(s, now): 'deleting' | 'failed' | 'ready' | 'stalled' | 'pending'`，顺序和 5 分钟阈值见 spec §6.1。
  - `defaultSnapshotName(pvc: string, now: Date): string`、`defaultRestoreName(snapshot: string): string`：不超过 63 个字符，截断后去掉结尾的 `-`，时间戳/`-restore` 后缀保留。
  - `defaultRestoreStorage(sourceBytes?: number, restoreSize?: number): number | undefined`：取较大者。
  - `volumeSnapshotApi`（`makeResourceApi` + `capability(ctx)`）、`volumeSnapshotClusterApi`。
  - `pageCodes.volumeSnapshot = 'tenant:page:volume-snapshots'`、`pageCodes.volumeSnapshotCluster = 'platform:page:volume-snapshot-cluster'`。

本仓前端没有单元测试框架，本批不引入；纯函数集中放这个文件，由 Task 9、10 的浏览器验证覆盖。

- [ ] **Step 1: 实现后运行类型检查，然后提交**

Run: `cd platform-web && npm run type-check`
Expected: 无错误。

```bash
git add platform-web/src/utils/volumeSnapshot.ts platform-web/src/types.ts platform-web/src/api/index.ts platform-web/src/pageCodes.ts
git commit -m "feat(snapshot): 前端类型、接口与状态函数"
```

---

### Task 9: 租户页面（列表、创建、恢复、PVC 入口）

**Files:**
- Create: `views/resource/VolumeSnapshotView.vue`、`components/storage/VolumeSnapshotCreateDialog.vue`、`components/storage/VolumeSnapshotRestoreDialog.vue`
- Modify: `router/index.ts`（PVC 路由之后，`domain: 'storage'`，`order` 在 PVC 和持久卷之间）、`views/resource/PvcView.vue`

**Interfaces:**
- Consumes: Task 8 的全部产出。
- `VolumeSnapshotCreateDialog`：props `pvcName?: string`（有值时锁定来源），emit `created`。
- `VolumeSnapshotRestoreDialog`：props `snapshot: VolumeSnapshot`，emit `restored`。

- [ ] **Step 1: 实现快照页**

列、状态、5 秒轮询、门控文案、删除确认按 spec §6.1、§6.5。删除确认必须显示该快照类的 `deletionPolicy` 含义。门控不可用时显示说明和刷新按钮，菜单项保留。

- [ ] **Step 2: 实现两个对话框**

字段、默认值、过滤规则按 spec §6.2、§6.3。§6.5 的文案**逐字**使用。

- [ ] **Step 3: 改 PVC 页**

行操作加「创建快照」：capability 不可用时隐藏；存储类没有匹配的快照类时禁用，tooltip 为「该 PVC 所在的存储类不支持快照」。新建表单 `dsKind=VolumeSnapshot` 时来源名称改下拉，隐藏来源命名空间；其他 kind 不变。

- [ ] **Step 4: 构建**

Run: `cd platform-web && npm run build`
Expected: 成功。

- [ ] **Step 5: 浏览器验证**

按 `efficient-browser-verification` 技能，假 token + XHR stub，逐项确认：`NO_CRD`、`NO_CLASS`、可用三种门控；五种状态；60 字符 PVC 名的默认快照名和恢复名都不超过 63 个字符；不支持的 PVC 按钮禁用且 tooltip 正确；恢复对话框容量默认值和下限；删除确认的策略文案。

- [ ] **Step 6: 提交**

```bash
git add <上述文件>
git commit -m "feat(snapshot): 租户快照页、创建恢复对话框与 PVC 入口"
```

---

### Task 10: 平台只读页面

**Files:**
- Create: `views/resource/VolumeSnapshotClusterView.vue`
- Modify: `router/index.ts`

路由 `volume-snapshot-cluster`，`platformDomain: 'cluster'`，`requiresPerm: [pageCodes.volumeSnapshotCluster]`，自带集群选择器（参照对象存储页）。

- [ ] **Step 1: 实现两个 tab（列见 spec §6.6）**

`orphan=true` 标「孤儿」，`orphan=null` 显示「—」。页面上没有任何写操作。

- [ ] **Step 2: 构建和浏览器验证（stub），然后提交**

Run: `cd platform-web && npm run build`
Expected: 成功；浏览器里两个 tab 正常显示，孤儿标记正确。

```bash
git add platform-web/src/views/resource/VolumeSnapshotClusterView.vue platform-web/src/router/index.ts
git commit -m "feat(snapshot): 平台只读查看快照类与快照内容页面"
```

---

### Task 11: 收尾验证

**Files:**
- Modify: `docs/task-ledger-2026-10-12.md`（S4.3 勾选已完成项，状态改为「已实施待部署」）、`k8s-server/src/main/resources/charts/components/csi-driver-nfs/README.md`（平台功能那行去掉「页面在 S4.3 实现」）

- [ ] **Step 1: 全量测试**

Run: `mvn -o test -pl k8s-core,k8s-server,platform-api -am`
Expected: BUILD SUCCESS。

- [ ] **Step 2: 权限交叉校验**

在开发库重放迁移后启动 platform-api。
Expected: `PermissionCrossCheckRunner` 不拒启，日志里没有 `/volumesnapshot*` 的缺行告警。

- [ ] **Step 3: 提交文档**

```bash
git add docs/task-ledger-2026-10-12.md k8s-server/src/main/resources/charts/components/csi-driver-nfs/README.md
git commit -m "docs(snapshot): 更新台账与组件文档"
```

- [ ] **Step 4: 报告真集群验收清单**

把 spec §9 的 7 条验收和部署顺序（重放迁移 → 重下发全部模板 → 重建三个模块 → 重新登录）交给用户。没有真跑过就不能写「已验收」。
