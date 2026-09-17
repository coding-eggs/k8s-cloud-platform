# B7 · 网络 Calico（IPPool / 保留 IP / IPAM 派生视图 / BGP* 只读 / 单副本静态 IP 绑定）

- **日期**：2026-09-15
- **状态**：草稿，待评审
- **覆盖条目**：原始清单 12、13
- **范围**：本阶段只交付规格；实现另开
- **CRD/资源**：双 group——`projectcalico.org/v3`（IPPool、BGPConfiguration/BGPPeer/BGPFilter）+ `crd.projectcalico.org/v1`（IPAMBlock、IPAMHandle、IPReservation，内部存储组）。全走 fabric8 通用 CRD API（无代码生成依赖）。
- **外部参考**：Calico IPAM 数据模型以 projectcalico / tigera 文档为准（`ipam-datastore.md` 设计文档 + `reference/resources/ippool`）；实现时逐字段核对。

---

## 1. 背景与目标

平台目前**无任何 Calico / 网络 IPAM 代码**（仅 `K8sCluster.calicoVersion` 一个版本字段）。本批新增「网络」模块：

| 子项 | 目标 |
|---|---|
| 12a | **IPPool**（`projectcalico.org/v3`）：列表 + 独立编辑页 + **详情页**（概览 / IP 分配块视图 / 保留 IP） |
| 12b | **可用 IP 列表 + 可搜索 + ipamblock 每 IP 分配/节点**：从内部 `ipamblocks`/`ipamhandles` **派生**，block 级汇总、按需展开单块、服务端搜索——**不枚举整池每个 IP**（化解 IPv4/IPv6 规模问题） |
| 12c | **保留 IP（IPReservation）**：平台可管理（admin CRUD 保留/释放一段 IP），并在块/IP 视图里标出 reserved |
| 12d | **BGPConfiguration / BGPPeer / BGPFilter**（`projectcalico.org/v3`）：**仅查看**，spec **完整字段表单**（只读）+ YAML。**FelixConfiguration 本批不做** |
| 13 | **单副本工作负载静态 IP 绑定**：保留/指定 IP 通过 pod template 注解 `cni.projectcalico.org/ipAddrs` 绑到 Pod，仅单副本（deployment/statefulset 且 replicas==1）可配 |

## 2. 范围

**In scope（本批交付）**
- 后端：IPPool / IPReservation DTO + CRUD operations（fabric8 通用 CRD + SSA）；BGP* 3 个只读 DTO + operations（仅 list/get/yaml）；**`CalicoIpamOperations` 派生视图 operation**（读 ipamblocks/ipamhandles/ipreservations/pods → poolSummary / block 列表 / 单块 per-IP）；`ResourceType` 加枚举 + factory 注册。
- k8s-server：`/admin/calico/**` 边界 controller——IPPool/IPReservation 走 `AbstractClusterResourceController`（6 端点）；BGP* 只读薄 controller（3 端点）；IPAM 派生查询 admin 端点（summary/blocks/block-ips）。
- platform-api：`K8sAdminClient` 新增方法 + `CalicoService` 编排（降级/缓存）+ item 13 的 `WorkloadValidator` 门控 + `PodTemplateDTO.staticIps`。
- 前端：导航「网络」（平台管理组，cluster 级）；IPPool 列表/详情/编辑、保留 IP 页、BGP* 只读页；**item 13 在工作负载编辑器加「固定 IP（Calico）」字段**；router / api client / types。

**Out of scope（v1 不做）**
- **FelixConfiguration**（用户明确先不做）——后续按需补只读表单。
- **BlockAffinity** 独立对象管理——它是内部 CAS 状态机的一部分，本批只经 `ipamblock.spec.affinity` 间接读节点归属，不单独建模/管理。
- **IPAMHandle 直接管理**——只读作二级索引（handle→IPs），不提供 UI 管理。
- Calico 网络策略（NetworkPolicy/Tier）——本批只做 IPAM + BGP 配置查看。
- 写 `ipamblocks`/`ipamhandles`——**永不写**（会破坏 Calico CAS 状态机）；保留 IP 走 `ipreservations`，IP 分配由 Calico 自身完成。

## 3. 现状盘点（探查结论）

| 项 | 现状 | 对本批的意义 |
|---|---|---|
| **CRD 范式（ServiceMonitor/B6）** | fabric8 `GenericKubernetesResource` + `CustomResourceDefinitionContext(group/version/kind/plural/scope)`；list/get/create/update(SSA)/delete/yaml。cluster-scoped 走 `ClusterOperations<T>`（scope=Cluster、无 namespace），同 B6 GatewayClass / Node | IPPool/IPReservation/BGP* **完全套用**此范式，仅 group/kind/scope 不同 |
| **两种 operations 接口** | `ClusterOperations<T>`（cluster-scoped）/ `NamespacedOperations<T>`。Calico 全 cluster-scoped → 全走 `ClusterOperations` | 直接复用；BGP* 只读 = 实现全接口但 controller 只暴露 list/get/yaml |
| **factory** | `KubernetesOperationsFactory.build()` switch on `ResourceType`；`getClusterOperation`(admin client) | 加 IPPool/IPReservation/BGP*/IPAM case，全走 `getClusterOperation`（平台管理级=全 admin client） |
| **跨源派生先例（B4）** | `CoreV1NodeOperations.listPodStats()`：`.inAnyNamespace().list()` + 内存聚合 → typed DTO；「所有 K8s 语义收在 k8s-core」 | **`CalicoIpamOperations` 的范式**：读多源（ipamblocks+reservations+pods）→ 内存派生 block/per-IP，K8s/Calico 语义归 k8s-core |
| **capability 机制** | `k8s_cluster.capability` JSON（group→versions[]），B2 读端点、B4 `capabilitySummary.hasCalico` 已预留 | `hasCalico` = capability 含 `projectcalico.org`；IPAM 视图另需 `crd.projectcalico.org`。纯 discovery，无活探测（不同于 B6 ambient） |
| **访问模型** | Calico 全 cluster-scoped、**无命名空间维度** → 不适用租户分配表边界；走 admin client + `/admin/**`（PLATFORM:admin、无租户上下文），同 B6 GatewayClass / Node | **平台管理级**：全 admin CRUD/只读，无租户域。这是唯一合理解（网络基础设施非租户资源） |
| **工作负载注解（item 13）** | `WorkloadDTO.podTemplate.annotations`（`Map<String,Object>`）**已完整建模**，`WorkloadConverter` 双向读写 pod template annotations（revert :111 / buildTemplate :774）。`WorkloadValidator` kind 感知三段式（A 互斥/条件、B 取值、C 引用） | item 13 = 往 pod template 写 `cni.projectcalico.org/ipAddrs`，**复用现有编辑路径**；门控落 `WorkloadValidator.validateA`（kind∈{deployment,statefulset} && replicas==1） |
| platform-api client | `K8sAdminClient`（/admin/** 生命周期，一方法一端点） | B7 各资源 CRUD/只读/IPAM 查询 → 新增 K8sAdminClient 方法（见 §5.3） |

## 4. 数据模型（platform-common，全 `extends BaseResources`）

> Calico v3 / crd-v1。字段为「建模核心集」；精确字段名以 tigera 文档为准，实现时逐条核对。cluster-scoped 无 namespace。

### 4.1 `IpoolDTO`（`projectcalico.org/v3`，CRUD）
| 字段 | 类型 | 说明 |
|---|---|---|
| name / labels / creationTime | — | 标准（name=RFC1123，创建后不可改） |
| cidr | String | `spec.cidr`（必填），如 `10.48.0.0/16` |
| blockSize | Integer | `spec.blockSize`（可选，前缀长度；null=默认 IPv4 /26、IPv6 /122） |
| nodeSelector | List<String> | `spec.nodeSelector`（表达式如 `projectcalico.org/node==worker`） |
| natOutgoing | Boolean | `spec.natOutgoing` |
| disabled | Boolean | `spec.disabled` |
| ipv4hierarchicalPortAllocation | Boolean | `spec.ipv4hierarchicalPortAllocation` |
| blocks | List<String> | `spec.blocks`（可选，指定使用的块 CIDR） |
| conditions | List<ConditionDTO>（只读） | `status.conditions` |

### 4.2 `IpReservationDTO`（`crd.projectcalico.org/v1`，CRUD）★保留 IP
| 字段 | 类型 | 说明 |
|---|---|---|
| name / labels / creationTime | — | 标准 |
| startIp | String | `spec.startIp`（保留段起，含） |
| endIp | String | `spec.endIp`（保留段止，含；单 IP 则 start==end） |

> group/plural 实现时核对（v1 vs v3 公开度）。语义：分配时转成 ordinal 过滤，被保留的 IP 不会被自动分配。

### 4.3 BGP* 只读 DTO（`projectcalico.org/v3`，view-only，完整字段）
**`BgpConfigurationDTO`**：name/labels/creationTime + `logLevel:String`、`nodeToNodeMeshEnabled:Boolean`、`asNumber:Integer`、`listenPort:Integer`、`communityRegistries:List<String>`、`passwordConfigured:Boolean` + conditions。
**`BgpPeerDTO`**：name/labels/creationTime + `ip:String`（对端地址）、`nodeSelector:List<String>`、`asNumber:Integer`、`passwordSecret:{name,namespace}`、`keepOriginalNextHop:Boolean`、`sourceAddress:String` + conditions。
**`BgpFilterDTO`**：name/labels/creationTime + `nodeSelector:List<String>`、`acceptPolicies:List<BgpAcceptPolicyDTO>{match{interfaceRegex,peerIPs[],sourcePrefixes[]},action(Accept/Reject/Drop)}`、`setAsPathPrepend:Integer`、`community:{name,value}` + conditions。

### 4.4 IPAM 派生 DTO（非 CRD，k8s-core 计算）
```
PoolIpamSummaryDTO { poolName, cidr, blockSize:Integer, capacity, allocated, free, reserved:Long, blockCount:Integer }
IpamBlockStatDTO   { cidr, node:String(nullable=affinity), totalIps, allocated, free, reserved:Long }  // 每块一行（默认视图）
IpamIpDetailDTO    { ip, status:"free"|"reserved"|"allocated", podName, podNamespace, node:String }     // 单块 per-IP（按需展开）
```
> 计数用 `long`（IPv6 容量大）。per-IP 的 allocated→pod 映射以 Pod `status.podIP` 反查为主、IPAMHandle 为二级索引。

## 5. 后端各层改动

### 5.1 k8s-core
- **CRUD operations**：`IppoolOperations implements ClusterOperations<IpoolDTO>`（group `projectcalico.org`/v3/kind IPPool/plural ippools/scope Cluster）；`IpReservationOperations implements ClusterOperations<IpReservationDTO>`（group `crd.projectcalico.org`/v1/kind IPReservation/plural ipreservations/scope Cluster）。全 fabric8 通用 CRD + SSA。
- **BGP* operations**：`BgpConfigurationOperations` / `BgpPeerOperations` / `BgpFilterOperations implements ClusterOperations<T>`（fabric8 通用 CRD）；实现全接口但**只被调 list/get/yaml**（view-only 由 controller 层收敛，见 §5.2）。
- **`CalicoIpamOperations`**（派生视图的家，构造注入 admin `KubernetesClient` + 相关 converter）：
  - 读 `ipamblocks`（crd v1，cluster-scoped `.list()`）+ `ipreservations` + （按需）`.pods().inAnyNamespace()`。
  - `poolSummary(poolName)` → `PoolIpamSummaryDTO`（含 allocated，供 IPPool 删除守卫）；`listBlocks(poolName?, search?)` → `List<IpamBlockStatDTO>`（已物化块）；`isFree(cidrOrIp)` / `nextFreeBlocks(pool, offset, limit)` / `blockIps(blockCidr)` → 可保留空间三件套。派生算法 + 复杂度见 §6。
- **factory**：`build()` 加 IPPool/IPReservation/BGP*×3/CalicoIpam case，全走 admin client（平台管理级）。
- **ResourceType**：加 `IP_POOL / IP_RESERVATION / BGP_CONFIGURATION / BGP_PEER / BGP_FILTER`（+ IPAM 派生可挂一个内部枚举或复用 IPPool 上下文，实现时定）。

### 5.2 k8s-server（边界，零业务逻辑）
- **IPPool**：`IppoolController extends AbstractClusterResourceController<IpoolDTO>`，`@RequestMapping("/admin/calico/ippool")`、`resourceType()=IP_POOL`。6 端点免费获得（`assertClusterAccess` + admin client）。
- **IPReservation**：`IpReservationController extends AbstractClusterResourceController<IpReservationDTO>`，`/admin/calico/ipreservation`。6 端点。
- **BGP* 只读**：各薄 controller `@RequestMapping("/admin/calico/bgp{configuration,peer,filter}")`，**仅暴露 list/get/yaml 3 个 GET 端点**（不接 create/update/delete），调对应 operation。`assertClusterAccess` + admin client。
- **IPAM 派生查询**：`CalicoIpamAdminController` `@RequestMapping("/admin/calico/ipam")`：
  - `GET /summary?poolName=` → `PoolIpamSummaryDTO`
  - `GET /blocks?poolName=&search=` → `List<IpamBlockStatDTO>`（已物化块，服务端过滤）
  - `GET /is-free?cidrOrIp=` → boolean（点查某 IP/块是否空闲）
  - `GET /next-free-blocks?poolName=&offset=&limit=` → `List<String>`（下一批空闲块 CIDR，分页）
  - `GET /block-ips?cidr=` → `List<IpamIpDetailDTO>`（单块 per-IP，未创建合成全 free）
  全 admin client、只读。

### 5.3 platform-api
- **`K8sAdminClient`** 新增方法：IPPool×6、IPReservation×6、BGP*×3(读)×3、IPAM summary/blocks/is-free/next-free-blocks/block-ips（一方法一端点，同现有风格）。
- **`CalicoService`**：编排 + 降级——某源不可用 → 该段 null/空 + 前端「—」；对 ipamblock 全量读（建索引）加**短 TTL 缓存（30–60s）**，同 B4。
- **IPPool 删除守卫**：delete 前先取 `poolSummary(pool).allocated`——>0 → 拒绝（「该池仍有 N 个已分配 IP，无法删除；请先释放/迁移」）；=0 才放行。默认池因恒有占用而被天然保护。
- **capability 门禁**：`hasCalico=false` → 「网络」模块横幅「该集群未安装 Calico」+ create 禁用（IPPool/IPReservation）。判定读 capability 列（含 `projectcalico.org`；IPAM 视图另需 `crd.projectcalico.org`，二者随 Calico 一起装）。
- **item 13**：`PodTemplateDTO` 加 `staticIps:List<String>`（支持双栈 v4+v6，与注解的 JSON 数组 1:1）；`WorkloadValidator.validateA` 加门控（见 §7）。

## 6. IPAM 派生视图 + 可保留空间（核心，回答「可用/可保留 IP / 块 / 规模怎么搞」）★

**关键约束**：/16 池=65536 IP、按 /26 切=1024 块；/8 是 13w 块；IPv6 /64 池 + /122 块 → 天文数字。**任何操作都不得枚举「补集」（未创建块），否则 O(T) 直接爆炸。**

### 6.1 数据源原则（效率地基）
- **唯一真相源 = 已物化/已认领的块集合 M**（`.list()` ipamblocks，受真实用量约束）+ IPReservation + （按需）pods。
- **空闲靠「缺席」判定**：某块空不空 = 它不在 claimed 集里；从不去数未创建块有多少、在哪。**池越空越快**（M≈0 → 点查 O(1)），成本只随用量 M 走，与池大小 T 无关。
- **建一次索引**：claimed 块按地址排序/radix，缓存于 platform-api（短 TTL 30–60s）。此后点查 O(1)、前缀查询 O(log M + k)，每请求不重扫 M。

### 6.2 可保留空间定义
`可保留 = 未创建块（整块空闲） ∪ 已创建块的 unallocated[]`。两者统一在「块导航」下呈现，**不是**只列未创建块：
- 未创建的块 → **合成全 free**（纯 CIDR 数学，无需对象存在）；
- 已创建未满的块 → 显示真实 `unallocated` 空闲 IP；
- 已满块 → 无。
> 当没有「未创建的空闲块」时（池块空间基本物化 / 要具体某 IP），可保留来源自然落到已创建块的 unallocated——同一模型覆盖，无需切换、**无需手填**。

### 6.3 v1 操作面（点查 + 下一空闲块，**不做整池树**）
| 端点 | 语义 | 复杂度 |
|---|---|---|
| `isFree(cidrOrIp)` | 该块/该 IP 当前空不空（jump-to 定位） | O(1) |
| `nextFreeBlocks(pool, offset, limit)` | 从起点 walk、跳过 claimed、凑够一页停 → 「下一批空闲块」分页 | O(跳过的已认领) ≤ O(M) |
| `blockIps(cidr)` | 单块 per-IP：已创建读 unallocated / **未创建合成全 free**；叠 reservation 交集标 reserved | O(blockSize) ≤ 64 |
| `listBlocks(pool?, search?)` | **已物化**块表（每块 cidr/node/free 数），服务端过滤 | O(M) |
| `poolSummary(pool)` | capacity/allocated/free/reserved，纯算术 | O(1)（缓存后） |

- **v4 浏览形态 = 点查 + 下一空闲块**：jump-to-CIDR/IP 定位、`nextFreeBlocks` 分页挑整块、`blockIps` 下钻单块挑具体 IP。**全 O(1)/O(M) 有界，无需手填**。
- **前缀下钻树**（/24→/26→IP）留作后续增强，v1 不做（避免过度解析 + 前端更重）。
- **搜索**：`isFree`/`nextFreeBlocks`/`listBlocks` 均支持按 CIDR/IP 定位；只回命中项，payload 小。

### 6.4 派生算法
- **块→池归属**：块 CIDR ⊆ 某池 CIDR（Calico 禁池重叠 → 无歧义）。
- **单块计数**：`totalIps`=块可分配地址数（去 network/broadcast）；`free`=`spec.unallocated[]` 长度（过滤 `spec.deleted=true` 软删）；`reserved`=与 IPReservation 段交集；`allocated=totalIps−free−reserved`。
- **per-IP（blockIps）**：块范围逐 IP → free（在 unallocated）/ reserved（在保留段）/ allocated（其余）。allocated 用 `pod.podIP` map（`.inAnyNamespace()` 一次，**仅 blockIps 时读 pods**）反查 pod name/ns/node；节点兜底取 block `spec.affinity`（`host:<node>`）。
- **poolSummary.free 含未创建块**：`capacity` 覆盖整池（含未物化），`free=capacity−allocated−reserved`——「还能留多少」的数字已含空块地址空间，只是默认视图不逐条渲染。

### 6.5 语义 caveat
1. 只能保留**当前 free** 的 IP；已 allocated 的留不了。
2. reservation 只影响**新分配**，不会把在用 IP 抢回来。

## 7. item 13 · 单副本工作负载静态 IP 绑定

- **数据面**：`PodTemplateDTO.staticIps:List<String>`（支持双栈 v4+v6）⇄ pod template 注解 `cni.projectcalico.org/ipAddrs`（Calico 期望 JSON 数组，如 `["10.48.3.5"]`、双栈 `["10.48.3.5","fd00::5"]`）。映射收在 `WorkloadConverter`（revert 读注解→staticIps；buildTemplate staticIps→写注解），其余注解 key 不动。
- **门控（`WorkloadValidator.validateA`）**：`staticIps` 非空时——kind 必须 ∈ {deployment, statefulset}（daemonset 每节点一份、无单副本语义 → 拒绝）；且 `replicas==1`（多副本抢同一 IP → 拒绝）。否则抛中文错误。
- **IP 有效性**：格式校验在 validator；「是否落在某池 / 是否已保留」为软校验——`WorkloadService` 侧查 IPPool（+ 可选 IPReservation），IP 不在任何池 → 警告/拒绝；未保留但空闲 → 提示「建议先保留该 IP，避免被自动分配抢走」（Calico 仍会 honor 注解）。
- **前端**：工作负载编辑器加「固定 IP（Calico）」输入框，仅当 kind∈{deployment,statefulset} && replicas==1 && `hasCalico` 时启用；否则隐藏/禁用 + 说明。编辑既有工作负载时回显已有注解值。
- **交叉引用**：工作负载编辑器另有「服务网格（Ambient）」模块 + 列表快捷开关（`istio.io/dataplane-mode` / `use-waypoint`，见 B3 §11）——同落 pod template metadata（ambient=labels、固定 IP=annotations），互不冲突。命名空间级 Calico 绑定池（`cni.projectcalico.org/ipv{4,6}pools` ns annotation）见 B3 §11。

## 8. 前端改动汇总

### 8.1 导航
- 「**网络**」模块，**平台管理组**（cluster 级，选集群、无命名空间）：IP 池 / 保留 IP / BGP 配置 / BGP 对等体 / BGP 过滤器。`hasCalico=false` 时整组横幅「未安装 Calico」+ create 禁用。

### 8.2 IPPool
- **列表页**：列（name、cidr、blockSize、natOutgoing、disabled、nodeSelector 摘要、utilization%）+ 刷新 + 「创建」+ 行操作下拉（查看/编辑/删除）。**删除带守卫**：池内有已分配 IP 时禁用/拒绝（见 §5.3）。
- **详情页**（`/calico/ippool/detail?clusterId=&name=`，tab 范式同 NodeDetailView）：
  1. **概览**：pool 字段（cidr/blockSize/natOutgoing/disabled/nodeSelector/blocks）+ stat tile（capacity/allocated/free/reserved，来自 `poolSummary`）。
  2. **IP 分配 / 可保留**★（点查 + 下一空闲块，见 §6.3）：已物化块表（每块 cidr/node/free 数，可搜索、分页）+ **jump-to-CIDR/IP**（`isFree` 定位某 IP/块状态）+ **下一批空闲块**（`nextFreeBlocks` 分页挑整块）；点某块 → `blockIps` 下钻 per-IP（free/reserved/allocated + pod/ns/node，未创建合成全 free）。
  3. **保留 IP**：本池相关的 IPReservation 列表。
- **独立编辑页**（复用 Hpa/ServiceMonitor editor 范式 + FieldHelp 全覆盖）：cidr/blockSize/natOutgoing/disabled/nodeSelector/blocks；name 仅创建时可填。

### 8.3 保留 IP 页
- **列表**：name、startIp–endIp、所属池摘要 + 删除。
- **创建（picker-first，非手填为主）**：选池 → 从 `nextFreeBlocks` 挑空闲块/连续几块（或 jump-to-CIDR/IP 定位）→ 可选在块内圈子范围（startIp–endIp，取自 `blockIps` 的空闲 IP）；**手填 startIp/endIp 仅作已知 VIP 的快捷项**。校验：段合法、不越界池、当前 free。FieldHelp 说明「保留后不会被自动分配，可配合 item 13 绑定」。

### 8.4 BGP* 只读页（配置/对等体/过滤器）
- 列表 + **详情（只读完整字段表单）** + **YAML tab**（全保真）。无创建/编辑/删除。

### 8.5 item 13
- 工作负载编辑器加「固定 IP（Calico）」字段（门控见 §7）。

### 8.6 注册点
- **router**：`/calico/ippool`(list) + `/calico/ippool/detail` + `/calico/ippool/editor` + `/calico/ipreservations` + `/calico/bgp{configuration,peer,filter}`，meta group=平台管理（admin 上下文）。
- **api/index.ts**：`calicoApi.ippool/*`、`calicoApi.ipReservation/*`、`calicoApi.bgp*`、`calicoApi.ipam.summary/blocks/blockIps`。
- **types.ts**：`K8sIpool / K8sIpReservation / K8sBgpConfiguration|Peer|Filter / PoolIpamSummary / IpamBlockStat / IpamIpDetail`。

## 9. 边界与异常

| 场景 | 行为 |
|---|---|
| 集群未装 Calico（capability 无 `projectcalico.org`） | 「网络」模块横幅「未安装」，create 禁用；存量对象（若有）照常展示 |
| capability 有 `projectcalico.org` 但无 `crd.projectcalico.org`（异常态） | IPAM 派生视图降级「—」（读不到 ipamblocks），IPPool/BGP* 照常 |
| admin client RBAC 未覆盖 projectcalico/crd.projectcalico 组 | list/CRUD 报权限错 → 前端降级提示；**实现时须确认 platform-system SA ClusterRole 含这两组读写规则**（见 §10 风险） |
| 池很大 / IPv6（大量块） | 一切操作锚定 claimed 集 M + 索引、空闲靠缺席判定，**永不枚举补集 T**；默认点查/下一空闲块（O(1)/O(M)），全量读走 TTL 缓存——payload 有界、不 OOM（§6.1） |
| 删除有已分配 IP 的池 | 拒绝（「仍有 N 个已分配 IP」）；默认池因恒占用被天然保护 |
| ipamblock 软删（`spec.deleted=true`） | 派生时过滤，不计入 free/allocated |
| allocated IP 反查不到 pod（handle 存在但 pod 已删/漂移） | per-IP 显示 allocated + node（取 block affinity），pod 列「—」（用 IPAMHandle 兜底 handle 名） |
| 集群断开 | 列表/详情降级提示，横幅「集群未连接」 |
| item 13：staticIps 非单副本 / 无 Calico | validator 拒绝（硬）；IP 不在池 → 警告（软） |

## 10. 验收标准（怎么算做完）

1. IPPool 列表/创建/编辑/YAML 全通（cluster-scoped、admin client、无命名空间）；**删除守卫**：池内有已分配 IP → 拒绝，空池可删；`hasCalico=false` 时模块门禁生效。
2. **IPPool 详情 IP 分配/可保留**：已物化块表正确（cidr/node/free 数）、jump-to-CIDR/IP 点查、下一批空闲块分页、点块 per-IP（状态 + pod/ns/node，未创建合成全 free）；**大池/IPv6 不枚举整池（锚定 claimed 集 M）、payload 有界**。
3. **保留 IP**：CRUD 全通；被保留段在块/IP 视图标为 reserved、不计入 free。
4. BGPConfiguration/BGPPeer/BGPFilter 只读：列表 + 完整字段表单 + YAML，无写入口。
5. item 13：单副本 deployment/statefulset 可设固定 IP → pod template 写入 `cni.projectcalico.org/ipAddrs`；多副本/daemonset 被拒；编辑回显正确。
6. 集群断开 / capability 缺失时各段优雅降级为「—」。

## 11. 待确认 / 风险

- **admin client RBAC**：读 `crd.projectcalico.org`（ipamblocks/ipamhandles）+ 读写 `projectcalico.org`（ippools/ipreservations/bgp*）需 platform-system SA ClusterRole 覆盖；实现时确认，不足则补规则。
- **IPReservation group/字段**：v1 vs v3 公开度、startIp/endIp 精确字段名以 tigera 文档核对（本 spec 为建模核心集）。
- **大集群全量读成本**：`listBlocks` / `poolSummary` 需扫 claimed 集 M；M 很大（数万块，超大 IPv6 池高占用）时全量 `.list()` + 建索引有成本。缓解：短 TTL 缓存（30–60s，同 B4）+ 超阈值降级——默认只走点查/下一空闲块（O(1)/O(M) 局部），不主动拉全表；前端块表按需分页触发，而非一次全量。
- **模块体量**：IPPool CRUD + 派生块视图 + 保留 IP + 3 只读 CRD + item 13 较大，建议实现分批落地（先 IPPool+块视图核心，再保留 IP/BGP*/item13）；spec 覆盖全量。
- **BGP* 只读收敛 / platform-api client 粒度**：现定「实现全 `ClusterOperations` + controller 只暴露 list/get/yaml」与「K8sAdminClient 一方法一端点」（同现有风格）；若嫌端点多，可抽 DTO 驱动 admin 资源 client（同 B6 GatewayClass 思路）统一六操作——纯实现取舍，不影响 spec。
