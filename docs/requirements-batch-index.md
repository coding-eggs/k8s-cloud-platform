# 需求批次总索引（2026-09）

> 本文件是本轮「需求细致化」工作的持久化进度台账，跨对话/上下文压缩不丢。
> 每批走独立一轮：细化需求 → 规格文档（`docs/superpowers/specs/`）→ （实现另开阶段）。
> **本阶段只交付规格文档，不写代码。**

## 工作方式

- 逐批推进，按清单顺序（B1 → B7）。
- 每批：探查现有代码 → 关键设计决策与用户确认 → 写 spec → 自审 → 用户评审。
- 代码探查按批做，不一次性全扫；外部 CRD/接口文档用 Apifox MCP（`apifox-endpoint-lookup`），Istio 外部 spec 走 7890 代理。
- 「你让我设计的部分」由我先给方案再确认，不留空。

## 批次状态

| 批 | 模块 | 覆盖条目 | 状态 | Spec | 关键决策 |
|---|---|---|---|---|---|
| **B1** | PodMonitor（参照 ServiceMonitor） | 1 | ✅ spec 已写，待评审 | [2026-09-14-podmonitor-design.md](superpowers/specs/2026-09-14-podmonitor-design.md) | 选择器=镜像 SM（选 Pod→反查 labels）；全栈镜像 SM |
| **B2** | HPA 重构 + 工作负载联动 + metrics-server 门禁 | 2–7 | ✅ spec 已写，待评审 | [2026-09-14-hpa-refactor-design.md](superpowers/specs/2026-09-14-hpa-refactor-design.md) | 独立编辑页+FieldHelp 全覆盖；工作负载下拉(禁已绑定)+新增 HpaService 后端校验双保险；capability 读端点做 metrics 门禁(按指标类型精确映射 group)+v2 behavior 动态启用(修误导告警) |
| **B3** | 命名空间管理增强 | 8 | ✅ spec 已写，待评审 | [2026-09-14-namespace-enhancement-design.md](superpowers/specs/2026-09-14-namespace-enhancement-design.md) | 编辑器=唯一编辑面(名称/描述/标签+配额+限制范围,用户重构);概览页只读(NodeDetailView 范式);BigDecimal 基础单位;quota/limitrange 从零建模+fetch-overlay 保未建模 key;**评审后追加(§11)命名空间级能力开关**:Calico 绑定池(ns annotation `cni.projectcalico.org/ipv{4,6}pools`)+ Istio ambient(ns/pod label `istio.io/dataplane-mode`/`use-waypoint`,pod 优先)+ **工作负载列表两个显式快捷开关**(`/workload/mesh-toggle`,L7 需选 gateway);waypoint per-ns 唯一校验归 B6 |
| **B4** | 集群概览 | 9 | ✅ spec 已写，待评审 | [2026-09-14-cluster-overview-design.md](superpowers/specs/2026-09-14-cluster-overview-design.md) | 操作收敛下拉+名称可点;**跨命名空间聚合归 k8s-core operation**(`.inAnyNamespace()` 一次 pass→per-ns 行+总量行,同 `listPodStats` 先例),k8s-server admin 薄透传、platform-api 编排;双平面:静态口径(allocated/计数/allocatable/配额)走 K8s op、实时 used 走 Thanos(by cluster_name + by-ns);概览页 4 tab;**全档=集群总量+per-ns 明细表**,配额列结构预留待 B3 |
| **B5** | 租户操作栏收敛 | 10 | ⬜ 未开始 | — | 可并入 UI 一致性收尾 |
| **B6** | 服务网格 Istio / Gateway API | 11 | ✅ spec 已写，待评审 | [2026-09-15-service-mesh-gateway-api-design.md](superpowers/specs/2026-09-15-service-mesh-gateway-api-design.md) | **访问分层**:GatewayClass=集群级平台管理(admin CRUD+租户只读引用,复用 `AbstractClusterResourceController`);**Gateway+5 Route=租户域**(复用现有 `AbstractNamespacedResourceController`+`K8sResourceClient`,分配表边界,同 ServiceMonitor,无新基类);7 类 Gateway API v1 全套 CRD 范式(fabric8 通用 CRD+SSA/fetch-overlay);**ambient 检测=活探测 ztunnel DaemonSet**(专用 mesh-status 端点,不污染 capability 列);hasGatewayApi 走 capability 门禁 |
| **B7** | 网络 Calico | 12–13 | ✅ spec 已写，待评审 | [2026-09-15-calico-network-design.md](superpowers/specs/2026-09-15-calico-network-design.md) | **双 group**:CRUD/只读对象在 `projectcalico.org/v3`(IPPool+BGP*),内部 IPAM 存储在 `crd.projectcalico.org/v1`(ipamblocks/ipamhandles/ipreservations,**只读**);全 cluster-scoped→平台管理级(admin,同 B6 GatewayClass);**效率模型**:一切锚定已物化 claimed 集 M + 索引、空闲靠缺席判定、**永不枚举补集 T**(O(1)/O(M)、TTL 缓存),化解大 CIDR/IPv6 规模;**IPPool 完整 CRUD + 删除守卫**(allocated>0 拒绝,默认池天然受保护);**v4 浏览=点查 isFree + 下一空闲块 nextFreeBlocks 分页**(不做前缀下钻树),可保留空间=未创建块(合成全 free)∪已创建块 unallocated;保留 IP=IPReservation admin CRUD(picker-first,非手填);BGP* 完整字段只读表单(FelixConfiguration 先不做);item13 静态 IP=pod template 注解 `cni.projectcalico.org/ipAddrs`(staticIps:List 支持双栈),门控单副本(deployment/sts && replicas==1) |

## 原始需求条目（13 条，供回溯）

1. PodMonitor 整体功能实现，参考 ServiceMonitor。
2. HPA 页面重构：工作负载支持下拉；每个 label 加 FieldHelper 描述作用与用法；新增/编辑用独立页面。
3. 限制同一工作负载只能绑定一个 HPA；下拉处对已有 HPA 的工作负载禁止选择。
4. 工作负载列表对已有 HPA 的加标识。
5. 工作负载列表加操作「添加 HPA」，自动跳转新增 HPA 页面。
6. 集群有 metrics-server / prometheus-adapter 才能展示/创建：external 需 prometheus-adapter，其他需 metrics-server。
7. HPA 新增不让填 behavior（当前是 v2 集群）。
8. 命名空间管理：加 ResourceQuota、LimitRange（具体需求我设计）；创建/编辑加描述字段（其他编辑功能同上）；命名空间概览（命名空间级别，展示项我设计）。
9. 集群管理：加概览功能，点击名称和查看进入；其他操作收敛在一起（像其他页面）。概览展示我设计。
10. 租户操作栏收敛在一起。
11. 服务网格模块 Istio（检测 ambient istio + kubernetes-sigs/gateway-api）：HTTPRoute/TCPRoute/TLSRoute/GRPCRoute/UDPRoute/GatewayClass/Gateway，其余我设计；文档 gateway-api.sigs.k8s.io（走 7890 代理）。
12. 网络模块 Calico (projectcalico.org/v3)：IPPool（详情、可用 IP 列表、可搜索、考虑 IPv4/IPv6 解析出 IP 太多的问题、ipreservation 保留 IP、查看每个 ipamblock 每 IP 分配情况/节点）；BGPConfiguration/BGPFilter/BGPPeer/FelixConfiguration(仅查看)。需求我设计。
13. 第 12 条保留的 IP 可通过注解绑定给 Pod，仅单副本工作负载可配置。
