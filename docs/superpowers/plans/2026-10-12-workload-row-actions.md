# 工作负载列表页三个行内动作（NodePort 直开 / 改镜像 / 回滚）— 实施记录

- **日期**：2026-10-12
- **来源**：用户直接提出（无独立 spec；设计在会话内评审通过）
- **当前状态**：**已完成、已验证，待部署**。全程未提交。
- **下一步**：部署（见文末「部署顺序」—— 迁移之后**必须**点一次「重下发全部模板」）。

## 三个功能

| 功能 | 入口 | K8s 语义 |
|---|---|---|
| NodePort 直开 | 名称列 `port:nodePort` 里 nodePort 变链接（外链图标 + tooltip） | 新窗口打开 `http://<节点IP>:<nodePort>`，host = 第一个 **Ready 且有 IP** 的节点（externalIp 优先） |
| 改镜像 | 镜像列每个容器一行 `容器名 镜像 ✎` | 只改该容器的 `image`：读回线上对象 → 改 → SSA 写回（未建模字段不丢）→ 滚动更新 |
| 回滚 | 操作列「回滚」→ 选历史版本 | 取该版本的 pod 模板覆盖到工作负载本身（与 `kubectl rollout undo` 同语义） |

## 落地记录

### 后端

- **platform-common**：`NodeAddressDTO`（窄投影：name + 内网/外网 IP + Ready）、`WorkloadRevisionDTO`（revision/name/createdAt/changeCause/images/current）。
- **k8s-core**
  - `CoreV1NodeOperations.addresses()` + `projectAddresses()`：窄投影，刻意不回容量/标签/污点/Pod 数。
  - `WorkloadOperations.updateImage()` / `revisions()` / `rollback()`：三者的写路径统一为**读回线上 typed 对象 → 改内存字段 → SSA 整体写回**（`applyAndRevert`，同一个 `platform-system` fieldManager）。
    - 为什么不像 `update()` 那样用 converter 重建：`PodSpecDTO` 只建模了 11 个字段，整份重建会把 `securityContext`/`terminationGracePeriodSeconds` 这类未建模字段抹掉 —— 用户点的是"改镜像 / 回到第 2 版"，不是"按平台口味重写一遍"。起点是线上对象，则 SSA 的"省略即删除"不成立。
    - 顺带清掉 `resourceVersion`/`generation`：带上 resourceVersion 会让这次 SSA 变成乐观锁（Deployment 控制器刷一次 status 就变版本号 → 平白失败）。
  - 版本历史的归一：Deployment → 名下 ReplicaSet（ownerReference controller=true + `deployment.kubernetes.io/revision` 注解，`current` 取 Deployment 自身同名注解）；STS/DaemonSet → ControllerRevision（`.revision`，`current` 取模板与线上相等的那条）。
  - **回滚剥掉 `pod-template-hash`**：那是控制器写给 ReplicaSet 的内部标签，带进 Deployment 自己的模板会污染用户看到的标签，并被算进下一次 rollout 的哈希。
  - `KubernetesOperationsFactory.getWorkloadOperation(clusterId, tenantId, adminMode)`：非六端点 CRUD 故返回具体类型（同 `getNodeOperation`），client 按访问模式选。
- **k8s-server**
  - `WorkloadController` 加 `POST /{name}/image`、`GET /{name}/revisions`、`POST /{name}/rollback`（沿用 TENANT 边界与 tenant/admin client；基类的 `stamp` 由 private 改 protected 供子类复用）。
  - 新增 `NodeAddressController`（`GET /nodes/addresses`，边界 **UPSTREAM**）。**必须独立成类**：`AccessBoundary` 是按 handler 所属类读的，`NodeController` 声明了 `PLATFORM`，往它上面加方法租户一样 403。
- **platform-api**
  - `K8sWorkloadClient`（新，一方法一端点）、`K8sNodeClient.addresses()`。
  - `WorkloadService.updateImage/revisions/rollback`（只做请求形态校验：空容器名/空镜像/缺版本号；容器是否真的存在由 k8s-server 判 —— 那边读的是线上对象，才是权威）、`NodeService.addresses()`、`RbacTemplateService.syncAll()`。
  - 控制器端点 5 个：`/workloads/{name}/image`、`/workloads/{name}/revisions`、`/workloads/{name}/rollback`、`/nodes/addresses`、`/rbacTemplate/sync-all`。
- **迁移** `V2026_10_12_1__workload_actions_permissions.sql`：5 条 API 行 + 显式角色绑定（admin 全给；tenant-admin 工作负载三行 + 节点地址；tenant-member 只读两行）+ **租户 RBAC 模板追加 `controllerrevisions`（apps, get/list）**。

### 前端

- `api/index.ts`：`workloadApi.updateImage/revisions/rollback`、`nodeApi.addresses`、`templateApi.syncAll`；`types/workload.ts` 加 `WorkloadRevision`、`types.ts` 加 `NodeAddress`（并把 `K8sWorkload.podTemplate` 补上 `spec.containers` 以支撑镜像列）。
- 新 `composables/useNodeAddresses.ts`：按集群缓存的节点地址目录（仿 `nodeCatalog`），权限位短路（`tenant:cluster:capability:view`），失败静默降级 → nodePort 退化为纯文本而不是"点了没反应的假链接"。
- `WorkloadView.vue`：nodePort 链接（tooltip 写明"在新窗口打开 http://…（节点 node-1）"）、镜像列按容器逐行 + 编辑图标、操作列「回滚」项（`canWrite && !isOpManaged`）、改镜像弹窗。
- 新 `components/workload/WorkloadRollbackDialog.vue`：版本列表（当前版本禁选、默认停在上一版）+ 明确写出"回滚会覆盖 pod 模板并产生新版本号"。
- `TemplateView.vue`：「重下发全部模板」按钮（带确认）+ 资源候选补 `controllerrevisions`。

## 与评审设计的两处偏差（都是就着既有约定改的）

1. **节点地址端点的路径**：设计写的是 `POST /cluster/node-addresses`，落地改为 **`GET /nodes/addresses`**。
   理由：本仓的既有约定是**两跳 URL 同名**（api 侧 `/nodes/podstats` ≡ k8s-server 侧 `/nodes/podstats`，
   见 backend-layering §7 第 3 条），数据也归 `NodeService`（节点域）。权限码按评审结论仍复用
   `tenant:cluster:capability:view`。
2. **租户模板补权限的落地方式**：迁移只改"已经列了 `daemonsets` 的模板"（= 已把 apps 工作负载交给租户的
   模板），并新增管理员动作 `POST /rbacTemplate/sync-all` 把 DB 的 rules 推平到各集群 —— 平台原先只在
   保存单个模板时同步，光改库各集群的 `tn-tpl-*` ClusterRole 不会变。

## 明确不做

- 不写 `kubernetes.io/change-cause`（"变更说明"列只展示集群里已有的）。
- 镜像列不含 initContainers（与今天的扁平 `images` 同口径）。
- op 管理（`ownerReferences` 非空）的工作负载：改镜像/回滚入口前端禁用，与「编辑」「ambient 开关」同口径，**后端不重复判**。

## 部署顺序

```bash
# 1) 先重放迁移（无行的新端点会被启动期交叉校验拒启）
#    mysql < platform-data/src/main/resources/db/migration/V2026_10_12_1__workload_actions_permissions.sql
# 2) 起新构建（platform-common → k8s-core → k8s-server → platform-api）
# 3) 管理员在「RBAC 模板」页点一次「重下发全部模板」（或 POST /rbacTemplate/sync-all）
#    —— 漏这步只影响 StatefulSet/DaemonSet 的回滚（租户 SA 缺 controllerrevisions 读权限 → 403）
#    —— Deployment 回滚不受影响（读的是 ReplicaSet，租户 SA 本来就有 replicasets 权限）
```

## 验收（已实测）

- **后端**：四模块测试全绿（新增 k8s-core 17 例、platform-api 8 例）；既有失败 `WorkloadConverterStaticIpTest`
  在 HEAD 就 NPE（测试自身第 75 行构造 `LinkedHashMap` 时空注解），与本批无关。
- **前端**：`npm run build`（= vue-tsc + vite build）通过。
- **浏览器实测**（假 token + XHR stub，工作负载页）：
  - `/nodes/addresses` 被调用；点 `30080` → `window.open('http://1.2.3.4:30080')`（取 Ready 节点的**外网** IP）；tooltip 文案正确；`nodePort=null` 的 LoadBalancer 端口保持纯文本。
  - 镜像列 4 个容器各一个编辑图标；点开弹窗容器名与镜像预填正确。
  - 操作列三行都有「回滚」，op 管理行（DaemonSet 带 ownerReferences）禁用。
  - 回滚弹窗：3 个版本全列出（镜像/变更说明/时间），当前版本禁选、默认选中上一版；提交发出
    `POST /api/workloads/web/rollback {tenantId, clusterId, namespace, revision: 2}` —— 是**所选版本**而不是当前版本。
