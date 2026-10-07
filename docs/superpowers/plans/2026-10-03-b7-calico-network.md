# B7 · 网络 Calico — 实施计划（新 session 直接可执行）

- **日期**：2026-10-03
- **规格**：[2026-09-15-calico-network-design.md](../specs/2026-09-15-calico-network-design.md)（完整设计/字段/派生算法/边界都在那里；本文件是「怎么落地」的执行清单）
- **当前状态**（2026-10-06 更新）：**Phase 1 / 2 / 3 已完成并全绿**（后端 mvn test + 前端 npm build 均 EXIT=0，未 commit）。下一步：**Phase 3.5 · BGP* 全 CRUD**（用户 2026-10-06 确认扩围，见该节），再进 Phase 4。
- **注意**：spec §8.4 / item 12d「BGP* 仅查看」已被 Phase 3.5 推翻（全 CRUD + Configuration 管理员专属 + 编辑页说明块）。

> ⚠️ 本计划对 spec 有两处**落地修正**（spec 写于设计期，代码现状优先）：
> 1. **导航不新建「网络」组**——仓库已有「集群运维」组 + `/ops/ippools` 占位入口（admin-gated）。B7 全部落在这里，替换占位页。见 §0.2。
> 2. **后端走 `/admin/calico/**` + `K8sAdminClient`**（spec 本意，且 MainLayout 注释「地址池走 k8s-server /admin/**（PLATFORM:admin 纵深防御）」印证）。**不是** StorageClass 那套 `/resources/**` + `K8sResourceClient`——Calico 是集群基础设施、无租户维度，用 admin 路径做 PLATFORM:admin 纵深防御。见 §0.3。

---

## 0. 落地前必读（约定 & 模板文件）

新 session 先读这几处，全程照抄范式：

| 要写什么 | 照抄这个模板 | 关键差异 |
|---|---|---|
| CRD operations（CRUD/只读） | `k8s-core/.../operations/monitoring/ServiceMonitorOperations.java` | **scope=Cluster**：去掉所有 `.inNamespace(ns)`，用 `.list()` / `.withName(name).get()`；CRD context 换 group/kind/plural/scope（见 §0.1） |
| CRD converter | `k8s-core/.../converter/impl/monitoring/ServiceMonitorConverter.java` | spec↔DTO 双向；`convertForUpdate(dto, live)` 走 fetch-overlay 保未建模字段 |
| k8s-server 集群级 controller（6 端点免费） | `k8s-server/.../controllers/cluster/StorageClassController.java` + 基类 `AbstractClusterResourceController.java` | **@RequestMapping 改成 `/admin/calico/<plural>`**（不是 /resources）；`resourceType()` 返回新枚举 |
| k8s-server 非标准端点 controller | 参考 `K8sAdminClient` 打的那些 `/admin/**` 端点的对应 server 侧 controller | IPAM 派生查询是自定义 query-param 端点，不套 6 端点基类 |
| platform-api admin client | `platform-api/.../k8s/K8sAdminClient.java` | 一方法一端点，`gateway.exchange(HttpMethod.X, "/admin/calico/...", ...)`；纯传输零业务 |
| platform-api 编排 service | `platform-api/.../services/ServiceMonitorService.java`（或任意 *Service） | 降级 + TTL 缓存 + 删除守卫等业务逻辑收这里 |
| 前端列表页 | `platform-web/src/views/resource/ServiceMonitorView.vue` | 集群级：无租户/命名空间选择器，只有集群选择 |
| 前端独立编辑页 | `platform-web/src/views/resource/ServiceMonitorEditorView.vue`（或 Hpa） | FieldHelp 全覆盖；name 仅创建可填 |
| 前端详情页（tab + stat tile） | `platform-web/src/views/NodeDetailView.vue` | IPPool 详情 = 概览 tab + IP 分配/可保留 tab + 保留 IP tab |
| 导航菜单 | `platform-web/src/layouts/MainLayout.vue`「集群运维」组（约 line 231-237） | 已有 `/ops/ippools` 占位项；在此扩成多个入口 |
| 路由 | `platform-web/src/router/index.ts`（`/ops/ippools` 在 line ~72） | 加 detail/editor + ipreservation/bgp* 路由，meta.group='集群运维' |
| api client | `platform-web/src/api/index.ts` | 新增 `calicoApi.*` |
| types | `platform-web/src/types.ts` | 新增 K8sIpool / K8sIpReservation / K8sBgp* / PoolIpamSummary / IpamBlockStat / IpamIpDetail |

### 0.1 CRD 上下文（group/version/kind/plural/scope，实现时逐字段对 tigera 文档核对）

| 资源 | group | version | kind | plural | scope | 读写 |
|---|---|---|---|---|---|---|
| IPPool | `projectcalico.org` | v3 | IPPool | ippools | Cluster | CRUD |
| IPReservation（保留 IP） | `crd.projectcalico.org` | v1 | IPReservation | ipreservations | Cluster | CRUD |
| BGPConfiguration | `projectcalico.org` | v3 | BGPConfiguration | bgpconfigurations | Cluster | 只读 |
| BGPPeer | `projectcalico.org` | v3 | BGPPeer | bgppeers | Cluster | 只读 |
| BGPFilter | `projectcalico.org` | v3 | BGPFilter | bgpfilters | Cluster | 只读 |
| IPAMBlock（内部，派生用） | `crd.projectcalico.org` | v1 | IPAMBlock | ipamblocks | Cluster | **只读**（永不写） |
| IPAMHandle（内部，二级索引） | `crd.projectcalico.org` | v1 | IPAMHandle | ipamhandles | Cluster | **只读** |

> fabric8 通用 CRD：`new CustomResourceDefinitionContext.Builder().withGroup(...).withVersion(...).withKind(...).withPlural(...).withScope("Cluster").build()`，操作走 `client.genericKubernetesResources(CRD)`。SSA 更新用 `.fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply()`（同 ServiceMonitorOperations.update）。

### 0.2 导航 & 路由现状（不要新建「网络」组）
- `MainLayout.vue` 已有 `<div v-if="perm.isAdmin" class="menu-group">集群运维</div>` + `<el-menu-item v-if="perm.isAdmin" index="/ops/ippools">地址池</el-menu-item>`。
- `router/index.ts` line ~72：`{ path: 'ops/ippools', name: 'ippools', component: () => import('@/views/ops/IppoolView.vue'), meta: { title: '地址池', group: '集群运维' } }`。
- **做法**：把「集群运维」组扩成 Calico 全部入口（IP 池 / 保留 IP / BGP 配置 / BGP 对等体 / BGP 过滤器），全 `v-if="perm.isAdmin"`；替换 `/ops/ippools` 占位页为真列表页，新增 detail/editor/ipreservation/bgp* 路由。

### 0.3 后端路径 & client（定死）
- **k8s-server**：全部 Calico 端点挂 `/admin/calico/**`（PLATFORM:admin 纵深防御 + `assertClusterAccess`）。
  - IPPool / IPReservation → 复用 `AbstractClusterResourceController<T>`，`@RequestMapping("/admin/calico/ippool")` / `"/admin/calico/ipreservation"`（6 端点免费：POST /list、GET /{name}、GET /{name}/yaml、POST、PUT /{name}、DELETE /{name}）。
  - BGP*×3 → 薄 controller，**只暴露 list/get/yaml**（不接 create/update/delete），`@RequestMapping("/admin/calico/bgp{configuration,peer,filter}")`。
  - IPAM 派生 → `CalicoIpamAdminController` `@RequestMapping("/admin/calico/ipam")`：`GET /summary?poolName=`、`GET /blocks?poolName=&search=`、`GET /is-free?cidrOrIp=`、`GET /next-free-blocks?poolName=&offset=&limit=`、`GET /block-ips?cidr=`（全只读）。
- **platform-api**：
  - `K8sAdminClient` 新增方法（一方法一端点）：IPPool×6、IPReservation×6、BGP*×3(读)、IPAM×5。
  - 编排 service：`CalicoService`（降级 + ipamblock 全量读 TTL 缓存 30–60s + IPPool 删除守卫）。
  - **面向前端的 platform-api controller**：`/calico/**` 顶层前缀（如 `/calico/ippool/*`、`/calico/ipam/*`），各委托 `K8sAdminClient`。
- **RBAC（必做，否则启动 brick）**：platform-api 新增的 `/calico/**` controller 端点会被 `PermissionCrossCheckRunner` 枚举——每个都要「有权限行 or 豁免」。Calico 是 admin-only 集群运维 → **加 Flyway migration 补权限行**（code 建议 `platform:cluster:manage`，admin 已持有；参照 [V2026_09_26_1__b3_namespace_permissions.sql](../../platform-data/src/main/resources/db/migration/V2026_09_26_1__b3_namespace_permissions.sql) 的写法 + `ON DUPLICATE KEY UPDATE`）。**实现时确认 admin 确实持有该 code，且 cross-check 通过。**（备选：若产品上想让「集群运维」整组走 admin 门不逐端点建行，可与用户确认是否豁免 `/calico/**`——但默认按补权限行做。）

---

## Phase 1 · IPPool CRUD + IPAM 派生块视图（核心，先做这个）

**目标**：IPPool 列表/创建/编辑/YAML 全通；详情页「概览 + IP 分配/可保留」tab（已物化块表、jump-to-CIDR/IP 点查、下一批空闲块分页、点块 per-IP 下钻）；删除守卫；capability 门禁。**大池/IPv6 不枚举整池**（锚定 claimed 集 M）。

### Step 1 — platform-common DTO
新建（全 `extends BaseResources`，见 spec §4）：
- `IpoolDTO`：name/labels/creationTime + `cidr:String`(必填)、`blockSize:Integer`、`nodeSelector:List<String>`、`natOutgoing:Boolean`、`disabled:Boolean`、`ipv4hierarchicalPortAllocation:Boolean`、`blocks:List<String>`、`conditions:List<ConditionDTO>`(只读)。
- IPAM 派生 DTO（非 CRD，k8s-core 算）：
  - `PoolIpamSummaryDTO { poolName, cidr, blockSize:Integer, capacity, allocated, free, reserved:Long, blockCount:Integer }`
  - `IpamBlockStatDTO { cidr, node:String(nullable), totalIps, allocated, free, reserved:Long }`
  - `IpamIpDetailDTO { ip, status:"free"|"reserved"|"allocated", podName, podNamespace, node:String }`
- 计数一律 `long`（IPv6 容量大）。

### Step 2 — k8s-core operations + converter
- `IppoolConverter`（spec↔DTO；`convertForUpdate` fetch-overlay）。
- `IppoolOperations implements ClusterOperations<IpoolDTO>`：CRD context = IPPool/Cluster（§0.1）；list/get/create/update(SSA)/delete/yaml，全去掉 namespace。create 前 `checkExist` → 抛 `RESOURCE_EXIST`。
- **`CalicoIpamOperations`**（派生视图的家，构造注入 admin `KubernetesClient` + 需要的 converter）：
  - 读 `ipamblocks`（`.list()`，cluster-scoped）+ `ipreservations` + （仅 blockIps 时）`.pods().inAnyNamespace()`。
  - 方法：`poolSummary(poolName)`、`listBlocks(poolName?, search?)`、`isFree(cidrOrIp)`、`nextFreeBlocks(pool, offset, limit)`、`blockIps(blockCidr)`。
  - **算法铁律（spec §6，务必遵守）**：唯一真相源 = 已物化/已认领块集 M + IPReservation；**空闲靠「缺席」判定，永不枚举补集 T**；未创建块在 `blockIps` 里合成全 free；单块计数 `free=unallocated[]长度(过滤 spec.deleted=true)`、`reserved=与保留段交集`、`allocated=totalIps−free−reserved`；per-IP allocated→pod 用 `pod.status.podIP` map 反查（node 兜底取 block `spec.affinity`）。
- **factory**：`KubernetesOperationsFactory.build()` 加 `case IP_POOL -> new IppoolOperations(client, new IppoolConverter())`；`CalicoIpamOperations` 单独暴露 getter（同 `getNodeOperation` 风格，admin client）。
- **ResourceType**：加 `IP_POOL(IpoolDTO.class)`、`IP_RESERVATION(...)`、`BGP_CONFIGURATION/PEER/FILTER(...)`（Phase 2/3 用到；本 Phase 至少加 IP_POOL）。

### Step 3 — k8s-server controllers
- `IppoolController extends AbstractClusterResourceController<IpoolDTO>`，`@RequestMapping("/admin/calico/ippool")`，`resourceType()=IP_POOL`。
- `CalicoIpamAdminController` `@RequestMapping("/admin/calico/ipam")`：5 个 GET 端点（§0.3），调 `CalicoIpamOperations`，`assertClusterAccess(clusterId)` + admin client。

### Step 4 — platform-api
- `K8sAdminClient`：加 IPPool×6 + IPAM×5 方法（照现有方法风格）。
- `CalicoService`：编排 + 降级（某源不可用→该段 null/空，前端「—」）+ **ipamblock 全量读 TTL 缓存**（30–60s）+ **删除守卫**：`delete(pool)` 前先 `poolSummary(pool).allocated`，>0 → 抛中文错「该池仍有 N 个已分配 IP，无法删除；请先释放/迁移」，=0 才放行。
- controller `/calico/ippool/*`（list/get/yaml/create/update/delete）+ `/calico/ipam/*`（summary/blocks/is-free/next-free-blocks/block-ips），委托 service/client。
- **capability 门禁**：`hasCalico=false`（capability 无 `projectcalico.org`）→ create 禁用 + 前端横幅；IPAM 视图另需 `crd.projectcalico.org`，缺失则派生段降级「—」。
- **RBAC**：加 Flyway migration 给 `/calico/**` 端点补权限行（§0.3）。

### Step 5 — 前端
- 替换 `views/ops/IppoolView.vue` 占位 → 真列表页：列（name/cidr/blockSize/natOutgoing/disabled/nodeSelector 摘要/utilization%）+ 刷新 + 创建 + 行操作下拉（查看/编辑/删除，删除带守卫提示）。
- 新建 `views/ops/IppoolDetailView.vue`（tab 范式同 NodeDetailView）：① 概览（pool 字段 + stat tile capacity/allocated/free/reserved）② IP 分配/可保留★（已物化块表可搜索分页 + jump-to-CIDR/IP 点查 `isFree` + 下一批空闲块 `nextFreeBlocks` 分页 + 点块下钻 `blockIps` per-IP：free/reserved/allocated + pod/ns/node，未创建合成全 free）。
- 新建 `views/ops/IppoolEditorView.vue`（独立编辑页 + FieldHelp；name 仅创建可填）。
- `router/index.ts`：加 `/ops/ippools/detail`、`/ops/ippools/editor`。
- `api/index.ts`：`calicoApi.ippool.{list,get,yaml,create,update,delete}` + `calicoApi.ipam.{summary,blocks,isFree,nextFreeBlocks,blockIps}`。
- `types.ts`：K8sIpool / PoolIpamSummary / IpamBlockStat / IpamIpDetail。
- `MainLayout.vue`「集群运维」组：保留/更新「地址池」入口（指向真列表页）。

### Step 6 — 验证 Phase 1
```bash
mvn -pl platform-common,k8s-core,k8s-server,platform-api -am test
npm --prefix platform-web run build   # vue-tsc strict，注意 noUnusedLocals
```
- 单测：`IppoolConverterTest`（spec↔DTO 双向 + fetch-overlay）、`CalicoIpamOperationsTest`（用 mock client 喂 ipamblocks/reservations/pods，断言 poolSummary/listBlocks/isFree/nextFreeBlocks/blockIps；**必须覆盖「大池不枚举补集」——构造一个大 CIDR + 少量 claimed 块，断言 free 数正确且不 OOM/不超时**）、删除守卫用例。
- 前端 build 全绿。

**Phase 1 做完 → 停下来给用户看（列表+详情+块视图跑通），确认方向再进 Phase 2。**

---

## Phase 2 · 保留 IP（IPReservation）
- platform-common：`IpReservationDTO`（name/labels/creationTime + `startIp:String`、`endIp:String`；单 IP 则 start==end）。**group/字段名以 tigera 文档核对**（spec §11 风险）。
- k8s-core：`IpReservationConverter` + `IpReservationOperations implements ClusterOperations<IpReservationDTO>`（CRD=IPReservation/Cluster）；factory + ResourceType。
- k8s-server：`IpReservationController extends AbstractClusterResourceController`，`/admin/calico/ipreservation`（6 端点）。
- platform-api：K8sAdminClient×6 + service + `/calico/ipreservation/*` controller + RBAC 行。
- **派生视图联动**：`CalicoIpamOperations` 的 reserved 交集已含 IPReservation（Phase 1 就应读它）；本 Phase 确保块/IP 视图里被保留段标 `reserved`、不计入 free。
- 前端：`views/ops/IpReservationView.vue`（列表 name/startIp–endIp/所属池摘要 + 删除）+ **创建 picker-first**（选池 → `nextFreeBlocks` 挑空闲块/连续几块，或 jump-to-CIDR/IP 定位 → 可选在块内圈子范围 startIp–endIp；手填仅作已知 VIP 快捷项）。校验：段合法、不越界池、当前 free。
- router/api/types/MainLayout「集群运维」加「保留 IP」入口。

## Phase 3 · BGP* 只读（Configuration / Peer / Filter）
- platform-common：`BgpConfigurationDTO` / `BgpPeerDTO` / `BgpFilterDTO`（完整字段，见 spec §4.3；含嵌套 `BgpAcceptPolicyDTO` 等）。
- k8s-core：3 个 converter + 3 个 `*Operations implements ClusterOperations<T>`（**实现全接口但只被调 list/get/yaml**）；factory + ResourceType。
- k8s-server：3 个薄 controller，`/admin/calico/bgp{configuration,peer,filter}`，**仅 list/get/yaml**。
- platform-api：K8sAdminClient（读×3）+ service + `/calico/bgp*/*` controller（只暴露 list/get/yaml）+ RBAC 行。
- 前端：3 个列表页 + 详情（**只读完整字段表单** + YAML tab），无创建/编辑/删除入口。router/api/types/MainLayout「集群运维」加 3 个入口。

## Phase 3.5 · BGP* 全 CRUD（用户 2026-10-06 确认扩围，插入在 Phase 4 之前）

**背景**：原需求清单 item 12 写「(仅查看)」，Phase 3 按只读落地。用户确认改为三个对象（BGPConfiguration / BGPPeer / BGPFilter）**全 CRUD**，并追加两条约束：
1. **BGPConfiguration 的创建/编辑/删除只能集群（平台）管理员操作**；BGPPeer / BGPFilter 写操作对持有 `platform:cluster:manage` 的用户开放（能看即能管）。
2. **三个对象的创建/编辑页，表单上方必须有「说明块」**，清晰描述：这个对象是做什么的 / 如何使用 / 注意事项。

**权限分层（权威在后端 code 判权，前端只做显隐）**

| 端点 | code | 说明 |
|---|---|---|
| BGP* 读 ×9（Phase 3 已有行） | `platform:cluster:manage` | 不动 |
| BGPPeer / BGPFilter create/update/delete ×6（新） | `platform:cluster:manage` | 与读同族；自定义角色授该 code 即成为可操作者 |
| BGPConfiguration create/update/delete ×3（新） | **`platform:bgp:config:manage`（新 code）** | 迁移里**显式只绑 builtin_role_admin**（⚠️ V2026_09_24_2 的「admin→全部 platform:%」是一次性快照 SELECT，新 code 不会自动进 admin；权限闭包在 token 签发时按 role→role_permission→code 动态算，绑了即生效）。管理员仍可在角色编辑器里显式下放该 code——属主动授权，可接受 |

**k8s-core**
- 三个 `Bgp*Operations` 去掉 create/update/delete 的 `OPERATION_NOT_SUPPORTED` throw，照 `IppoolOperations` 实现（fabric8 通用 CRD；update 走共享 SSA 路径，见 memory fabric8-ssa-generic-crd）。converter 不动。
- 已核查：现有测试**没有**断言 throw 行为，删 throw 不会红。

**k8s-server**
- 三个薄 controller（`controllers/calico/Bgp*Controller`）改为继承 `AbstractClusterResourceController<T>`（同 `IppoolController` 形态）：6 标准端点免费获得（POST /list、GET /{name}、GET /{name}/yaml、POST、PUT /{name}、DELETE /{name}），`resourceType()` = BGP_CONFIGURATION / BGP_PEER / BGP_FILTER。零业务逻辑不变。

**platform-api**
- `K8sAdminClient` +9 方法：`POST /admin/calico/bgp{configuration,peer,filter}`（create）、`PUT .../{name}`（update）、`DELETE .../{name}`（delete），照 IPPool 三件套写法。
- `CalicoService` +9 透传；`CalicoController` +9 端点（`/calico/bgpconfiguration`、`/calico/bgpconfiguration/{name}` PUT/DELETE 等，HTTP 方法与 k8s-server 对齐）。

**RBAC 迁移**：`platform-data/db/migration/V2026_10_06_1__calico_bgp_write_permissions.sql`
- 9 行权限（显式 id `perm_calico_bgp_{cfg,peer,filter}_{create,update,delete}` + ODKU）：cfg×3 → `platform:bgp:config:manage`；peer×3、filter×3 → `platform:cluster:manage`。resource/action 与 CalicoController 新端点逐一对齐（POST `/calico/bgpX`、PUT/DELETE `/calico/bgpX/{name}`）。
- admin 绑定：`INSERT INTO platform_role_permission SELECT CONCAT('rp_admin_bgp_cfg_', p.id), 'builtin_role_admin', p.id FROM platform_permission p WHERE p.code='platform:bgp:config:manage'`（ODKU 幂等）。
- ⚠️ 部署顺序注释：先重放本文件再起新构建（PermissionCrossCheckRunner fail-closed，无行端点拒启）。

**前端**
- `api/index.ts`：`calicoApi.bgp*` 各加 create/update/delete；types 不动（DTO 字段已全）。
- **3 个编辑器页（新）**：`BgpConfigurationEditorView.vue` / `BgpPeerEditorView.vue` / `BgpFilterEditorView.vue`，路由 `ops/bgp{configurations,peers,filters}/editor`（?name= 编辑态；name 仅创建可填）。
- **说明块（硬要求）**：每页表单上方放说明面板（el-alert type=info 或自定义 panel），三段式「是什么 / 怎么用 / 注意」，文案要点（实现时润色成完整中文段落）：
  - **BGPConfiguration**：Calico 节点 BGP 的**集群级全局默认配置**——AS 号、节点间全 mesh（nodeToNodeMeshEnabled）、监听端口、日志级别、宣告哪些 Service IP 段（ClusterIP/ExternalIP/LB IP）、communities 注册表等。创建后对集群内启用 BGP 的节点整体生效，通常一个对象即可（如 name=default），可配合 BGPPeer 做按 peer 细化。⚠️ 改 AS 号 / 关 nodeMesh / 改监听端口可能**立即中断节点间与对外 BGP 连通性**；删除唯一配置对象会回落到 Calico 内置默认值。**仅平台管理员可操作**。
  - **BGPPeer**：声明「指定节点（`node` 或 `nodeSelector`）↔ 外部对端 IP」的一条 BGP peering，典型场景是接物理路由器/负载均衡器。填对端 IP + 对端 AS 号；可选引用已有 BGPFilter（`filters` 多选）做路由过滤；`password` 引用集群内 Secret（namespace/name/key）。⚠️ peerIp/AS 填错则 peering 建不起来或影响既有路由；`keepOriginalNextHop` 已废弃勿用；删除只影响对应节点到该对端的 peering，**不影响节点间 mesh**。
  - **BGPFilter**：BGP 路由过滤规则集，四条列表 exportV4/importV4/exportV6/importV6（方向=相对应用它的节点：export=发给对端前、import=收到后）。规则按顺序评估、**第一条命中生效**；Accept 可附 operations（addCommunity / prependASPath / setPriority）。⚠️ **过滤器本身不产生任何效果，必须被 BGPPeer 的 `filters` 引用才生效**；Reject 用错会造成路由黑洞（如 import 全拒 → 对端网络不可达）；被引用后改动即时生效。
- 表单结构：Configuration=平铺字段 + 嵌套列表动态行（serviceClusterIPs/ExternalIPs/LBIPs `[{cidr}]`、communities `[name=value]`、prefixAdvertisements `[cidr+communities]`、ignoredInterfaces tags）；Peer=平铺字段 + filters 多选（数据源=该集群现有 BGPFilter 列表）+ password secretKeyRef 三项；Filter=四个 section × 动态规则列表（每条 ~10 字段 + operations 子列表），三者中最复杂。
- 列表页：加「创建」按钮 + 行操作「编辑/删除」（el-popconfirm 确认）；**BGPConfiguration 页的创建/编辑/删除仅 `perm.isAdmin` 可见**，其 editor 路由 meta requiresPerm 含 `platform:bgp:config:manage`（Peer/Filter 用 `platform:cluster:manage`）。
- MainLayout 菜单不动（3 个入口已有）。

**验证**：无新增纯逻辑单测（改动=去 throw + 端点透传 + 前端表单），跑回归 `mvn -pl platform-common,k8s-core,k8s-server,platform-api -am test` + `npm --prefix platform-web run build`；手工冒烟三对象 CRUD + 非 admin 调 Configuration 写端点 403。

**验收追加（对照 spec §10 第 7 条）**
7. BGP* 全 CRUD：三类创建/编辑/删除全通、编辑回显正确；三个编辑页表单上方均有「是什么/怎么用/注意」说明块；非 admin 看不到也调不通 Configuration 写入口（UI 隐藏 + 后端 403）；持 `platform:cluster:manage` 可管 Peer/Filter。

---

## Phase 4 · item 13 单副本工作负载静态 IP
- platform-common：`PodTemplateDTO` 加 `staticIps:List<String>`（双栈 v4+v6，与注解 JSON 数组 1:1）。
- k8s-core：`WorkloadConverter` 双向——revert 读 pod template 注解 `cni.projectcalico.org/ipAddrs` → staticIps；buildTemplate staticIps → 写注解（其余注解 key 不动）。
- **门控**：`WorkloadValidator.validateA`——staticIps 非空时 kind 必须 ∈ {deployment, statefulset} 且 `replicas==1`，否则抛中文错。
- 软校验：IP 不在任何 IPPool → 警告/拒绝；未保留但空闲 → 提示「建议先保留该 IP」（Calico 仍 honor 注解）。
- 前端：工作负载编辑器加「固定 IP（Calico）」输入框，仅 kind∈{deploy,sts} && replicas==1 && hasCalico 时启用，否则隐藏/禁用 + 说明；编辑既有回显。
- 单测：WorkloadConverter 双向注解映射、validator 门控（多副本/daemonset 拒绝）。

---

## 横切 caveat（实现期必须处理）
1. **admin client RBAC（部署前用户确认，代码侧无法验证）**：platform-system SA 的 ClusterRole 需覆盖 `projectcalico.org`（读写 ippools/ipreservations/bgp*）+ `crd.projectcalico.org`（读 ipamblocks/ipamhandles）。不足则补规则。在 plan/代码注释标注。
2. **IPReservation group(v1/v3)/字段名**：以 tigera 文档为准，实现时核对（可走 Apifox MCP / 7890 代理查 CRD schema）。
3. **capability 门禁**：`hasCalico` = capability 含 `projectcalico.org`；IPAM 视图另需 `crd.projectcalico.org`。纯 discovery，无活探测（不同于 B6 ambient）。
4. **RBAC cross-check**：每个新增 platform-api `/calico/**` 端点都要权限行或豁免，否则 `PermissionCrossCheckRunner` brick 启动。见 §0.3。
5. **大池/IPv6 效率**：一切锚定 claimed 集 M + TTL 缓存，永不枚举补集 T；块表按需分页触发，不一次全量拉。
6. **降级**：集群断开 / capability 缺失 / admin RBAC 未覆盖 → 各段优雅降级「—」+ 横幅提示，不整页崩。

## 验证命令（每 Phase 末）
```bash
mvn -pl platform-common,k8s-core,k8s-server,platform-api -am test
npm --prefix platform-web run build
```

## 验收（对照 spec §10）
1. IPPool 列表/创建/编辑/YAML 全通（cluster-scoped、admin client、无 namespace）；删除守卫（有已分配 IP→拒，空池可删）；hasCalico=false 门禁生效。
2. IPPool 详情 IP 分配/可保留：块表正确、jump-to 点查、下一批空闲块分页、点块 per-IP（状态+pod/ns/node，未创建合成全 free）；大池/IPv6 不枚举整池、payload 有界。
3. 保留 IP CRUD 全通；被保留段在块/IP 视图标 reserved、不计入 free。
4. BGP* 只读：列表 + 完整字段表单 + YAML（Phase 3 基线）；**全 CRUD 见 Phase 3.5 验收第 7 条**（原「无写入口」已作废）。
5. item 13：单副本 deploy/sts 可设固定 IP → pod template 写 `cni.projectcalico.org/ipAddrs`；多副本/daemonset 拒；编辑回显正确。
6. 集群断开 / capability 缺失时各段降级「—」。

## 建议推进节奏
Phase 1（IPPool+块视图核心）做完先停，给用户看 → 确认后进 Phase 2（保留 IP）→ Phase 3（BGP* 只读）→ **Phase 3.5（BGP* 全 CRUD，用户 2026-10-06 插入）** → Phase 4（item 13）。每 Phase 末跑上面验证命令。
