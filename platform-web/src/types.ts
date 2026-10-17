/** RBAC 策略规则（与后端 PolicyRuleDTO 对应） */
export interface PolicyRule {
  apiGroups?: string[]
  resources?: string[]
  verbs?: string[]
  resourceNames?: string[]
}

// 复用工作负载编辑器的容器子结构（与后端 ContainerDTO 同构）
import type {
  ContainerPort,
  EnvFrom,
  EnvVar,
  K8sExposedService,
  Lifecycle,
  OwnerRef,
  Probe,
  Resources,
  VolumeMount,
} from './types/workload'

/** K8s 集群（kubeconfig 不下发，恒为 null） */
export interface K8sCluster {
  clusterId: string
  clusterName: string
  kubeconfig?: string | null
  version?: string | null
  istioVersion?: string | null
  calicoVersion?: string | null
  containerRuntime?: string | null
  prometheusUrl?: string | null
  grafanaUrl?: string | null
  description?: string | null
  ipStack?: 'IPV4' | 'IPV6' | 'IPV4_AND_IPV6' | null
  status?: 'CONNECTED' | 'DISCONNECTED' | 'CONNECTING' | 'ERROR' | null
  enabled: number
  lastHeartbeatTime?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  /** 集群 API 能力（JSON：group → versions[]），运行时 discovery 探测后持久化 */
  capability?: string | null
  /** 集群画像（S3.6）：MANAGEMENT=平台自身所在集群；BUSINESS=业务集群 */
  clusterRole?: 'MANAGEMENT' | 'BUSINESS' | null
}

/**
 * 集群下拉选项（窄投影，POST /cluster/options）：只含定位、启停与 IP 栈。
 *
 * 供「只需要一个集群选择框」的页面（命名空间管理/编辑/概览）使用，权限码 platform:allocation:list；
 * 与 /cluster/list（platform:cluster:manage，含版本/描述/内网地址等登记信息）区分粒度，
 * 避免这些页面为了一个下拉框去要平台管理权限。
 */
export interface K8sClusterOption {
  clusterId: string
  clusterName: string
  enabled: number
  ipStack?: 'IPV4' | 'IPV6' | 'IPV4_AND_IPV6' | null
}

/**
 * 集群概览（镜像后端 ClusterOverviewDTO）。
 *
 * ⚠️ **聚合段为 null 表示「读不到」而不是「为 0」**（集群未连接 / 聚合超时）：UI 必须显示「—」，
 * 不能把 null 当 0 渲染 —— 「一个异常 Pod 都没有」与「不知道有没有」是相反结论。
 */
export interface K8sClusterOverview {
  nodeSummary?: K8sNodeSummary | null
  /** 需要关注的节点：未 Ready **或** 有 pressure（不是字面上的 notReady 列表） */
  unhealthyNodes?: K8sNodeHealth[] | null
  resourceCapacity?: K8sResourceCapacity | null
  resourceTotal?: K8sClusterResourceTotal | null
  storage?: K8sStorageStat | null
  abnormalPods?: K8sAbnormalPod[] | null
  /** 异常 Pod 总数（未封顶）；null = 聚合不可用 */
  abnormalPodTotal?: number | null
  /** 异常 Pod 按 reason 分组计数（**全量**，不受明细 200 条封顶影响），按严重度降序；null = 聚合不可用 */
  abnormalPodReasonCounts?: Record<string, number> | null
  unhealthyWorkloads?: K8sUnhealthyWorkload[] | null
  /** 不健康工作负载总数（未封顶）；null = 聚合不可用 */
  unhealthyWorkloadTotal?: number | null
}

/** 节点健康合计（total / ready / notReady） */
export interface K8sNodeSummary {
  total: number
  ready: number
  notReady: number
}

/** 节点健康投影；ready 的节点也可能带 pressure（它是调度提示，不是 NotReady） */
export interface K8sNodeHealth {
  name?: string | null
  ready: boolean
  /** MemoryPressure / DiskPressure / PIDPressure 中命中的那些 */
  pressures?: string[] | null
  version?: string | null
}

/** Σ node.status.allocatable —— 「allocatable / allocated / used」三口径里的 allocatable（核 / 字节） */
export interface K8sResourceCapacity {
  cpuAllocatable?: number | null
  memoryAllocatable?: number | null
}

/** 集群资源总量：allocated 口径（= Σ pod requests）+ 对象计数 */
export interface K8sClusterResourceTotal {
  cpuRequest?: number | null
  memRequest?: number | null
  cpuLimit?: number | null
  memLimit?: number | null
  podCount: number
  deploymentCount: number
  statefulsetCount: number
  daemonsetCount: number
  serviceCount: number
  namespaceCount: number
  pvcCount: number
  pvCount: number
}

/** 单命名空间资源明细行（「资源明细」tab，懒加载 /cluster/resource-breakdown） */
export interface K8sNamespaceResourceStat {
  namespace: string
  podCount: number
  deployCount: number
  stsCount: number
  dsCount: number
  svcCount: number
  pvcCount: number
  cpuRequest?: number | null
  memRequest?: number | null
  cpuLimit?: number | null
  memLimit?: number | null
  /** 该 ns 的 ResourceQuota（requests.cpu/memory）；无配额时 null（≠ 0） */
  quotaCpuHard?: number | null
  quotaMemHard?: number | null
  quotaCpuUsed?: number | null
  quotaMemUsed?: number | null
}

/** 异常 Pod 明细行；reason 是归组用的最严重原因（CrashLoopBackOff > OOMKilled > … > Pending） */
export interface K8sAbnormalPod {
  namespace?: string | null
  name?: string | null
  phase?: string | null
  reason?: string | null
  restarts: number
  node?: string | null
  ageSeconds?: number | null
}

/** 不健康工作负载（readyReplicas < replicas 的 Deployment / StatefulSet） */
export interface K8sUnhealthyWorkload {
  namespace?: string | null
  name?: string | null
  /** Deployment / StatefulSet */
  kind?: string | null
  readyReplicas: number
  replicas: number
}

// ===== 租户概览（Phase 4；字段与后端 TenantOverviewDTO / NamespaceStatEntry 等 DTO 逐字一致）=====

/**
 * 命名空间配额三档（后端 QuotaState，判定集中在 platform-api 的 QuotaClassifier）。
 * OK=有 ResourceQuota 且含 requests.cpu + requests.memory；MISSING=无任何 ResourceQuota；
 * PARTIAL=有 quota 但缺 requests.cpu / requests.memory 任一 key。前端只消费该结果，不自行按 key 判。
 */
export type K8sQuotaState = 'OK' | 'MISSING' | 'PARTIAL'

/**
 * 一个命名空间的事实切片（后端 NamespaceSnapshotDTO）—— 租户概览明细 / 命名空间详情共用一行。
 * 作用域：凡带 namespace 的都归这里（异常 Pod、不健康工作负载都带 ns）；集群键里不放这些。
 */
export interface K8sNamespaceSnapshot {
  /** 资源明细行（复用 K8sNamespaceResourceStat，与后端 NamespaceStatDTO 逐字段一致） */
  stat: K8sNamespaceResourceStat
  /** 该 ns 异常 Pod 总数（**全量**计数，不是名单条数） */
  abnormalPodCount: number
  /** 该 ns 的 reason 分组计数（同样全量） */
  abnormalPodReasonCounts: Record<string, number>
  /** 异常 Pod 名单（severity 排序 + 每 ns 封顶） */
  abnormalPods: K8sAbnormalPod[]
  unhealthyWorkloadCount: number
  unhealthyWorkloads: K8sUnhealthyWorkload[]
  /** 采样时刻：epoch 毫秒 */
  sampledAt: number
}

/** 明细行（后端 NamespaceStatEntry）= 命名空间切片 + 所属集群 + 配额三档状态 */
export interface K8sNamespaceStatEntry {
  clusterId: string
  snapshot: K8sNamespaceSnapshot
  quotaState: K8sQuotaState
}

/**
 * 租户概览里「一个集群」的小计行（后端 TenantClusterSummaryDTO）。
 * namespaceCount 是**范围**（分配表里该集群下属于本租户的命名空间数），不因取不到数而变；
 * available=false 时 total 为 null（本页少算这一块，并已显式标记）。
 */
export interface K8sTenantClusterSummary {
  clusterId: string
  /** 集群名（取自 k8s_cluster）；集群已不存在时为 null */
  clusterName?: string | null
  /** 该集群下属于本租户的命名空间数（=范围，来自分配表） */
  namespaceCount: number
  /** 本次请求是否取到该集群的切片 */
  available: boolean
  /** 该集群的切片小计（复用 K8sClusterResourceTotal）；available=false 时为 null */
  total?: K8sClusterResourceTotal | null
}

/**
 * 租户概览首屏（后端 TenantOverviewDTO）。合计恒等于明细逐行相加（spec §6.1）。
 * 逐集群降级：某集群取不到数时它的行不进合计，但 id 出现在 unavailableClusters、
 * 该集群小计 available=false —— 首屏显式提示，不整页失败。
 */
export interface K8sTenantOverview {
  tenantId: string
  /** 各行（= 各命名空间切片）中**最旧**的采样时刻（epoch 毫秒）；无数据 → 0 */
  sampledAt: number
  /** Σ 各行（复用 K8sClusterResourceTotal，与后端 ClusterTotalDTO 逐字段一致） */
  total: K8sClusterResourceTotal
  /** Σ 有配额的行的 hard；无任何配额行 → null（前端据此不给占用百分比） */
  quotaCpuHard?: number | null
  quotaMemHard?: number | null
  /** Σ 有配额的行的 used */
  quotaCpuUsed?: number | null
  quotaMemUsed?: number | null
  /** 没有任何 ResourceQuota 的命名空间名（QuotaState.MISSING） */
  namespacesWithoutQuota: string[]
  /** 有配额但缺 requests.cpu / requests.memory 任一 key 的命名空间名（QuotaState.PARTIAL） */
  namespacesPartialQuota: string[]
  /** 异常 Pod 总数（全量，未封顶） */
  abnormalPodTotal: number
  /** reason 分组计数（全量求和，严重度降序） */
  abnormalPodReasonCounts: Record<string, number>
  /** 异常 Pod 名单（跨集群合并后按 severity 排序 + 封顶 50） */
  abnormalPods: K8sAbnormalPod[]
  /** 不健康工作负载总数（全量，未封顶） */
  unhealthyWorkloadTotal: number
  /** 不健康工作负载名单（跨集群合并后排序 + 封顶） */
  unhealthyWorkloads: K8sUnhealthyWorkload[]
  /** 逐集群小计（含不可用集群，其 available=false、total=null） */
  clusters: K8sTenantClusterSummary[]
  /** 取数时节不可用的集群 id 列表（其余集群照常出数） */
  unavailableClusters: string[]
}

/** 存储统计：PV 容量（total vs bound）+ PVC 状态计数 */
export interface K8sStorageStat {
  pvCount: number
  pvcBound: number
  pvcPending: number
  pvcLost: number
  /** Σ PV spec.capacity.storage（字节） */
  pvCapacityBytes?: number | null
  /** Σ 已 Bound PV 的容量（字节） */
  pvBoundBytes?: number | null
}

/** 租户 */
export interface PlatformTenant {
  id: string
  name: string
  serviceAccount: string
  status: number
  createdAt?: string | null
  updatedAt?: string | null
}

/** token 内租户上下文（与后端 UserTenantInfo 对应；base token 无此对象） */
export interface UserTenantInfo {
  userId?: string
  tenantId: string
  tenantName?: string | null
  serviceAccount?: string | null
}

/** token 的 data claim / /user/me 返回体（与后端 TokenUserInfo 对应） */
export interface TokenUserInfo {
  username?: string
  displayName?: string | null
  email?: string | null
  /** 1正常 0禁用 */
  status?: number
  tenantInfo?: UserTenantInfo | null
  /** 平台域角色 code（如 admin） */
  platformRoles?: string[] | null
  /** 当前上下文权限点闭包 */
  permissions?: string[] | null
}

/** RBAC 角色（platform_role；内置角色 builtIn=1 不可删/不可改 code+scope，权限集仍可重存） */
export interface PlatformRole {
  id: string
  name: string
  code: string
  description?: string | null
  /** 角色族：PLATFORM / TENANT */
  scope: 'PLATFORM' | 'TENANT' | string
  /** 内置角色：1 不可删、code/scope 不可改；0 可编辑（后端为 Byte → JSON number） */
  builtIn?: number | null
  /** 1 启用 0 禁用 */
  status?: number | null
  createdAt?: string | number | null
  updatedAt?: string | number | null
}

/** RBAC 权限点行（platform_permission；⚠️ code 不唯一 —— 一行 = 一个 URL 规则，ANY-of 种子按行落库） */
export interface PlatformPermission {
  id: string
  /** 权限域：API / K8S / Page */
  domain?: string | null
  /** 资源：API 域 = URL 模式 */
  resource?: string | null
  /** 动作：API 域 = HTTP 方法（本平台全 POST） */
  action?: string | null
  /** 权限点 code（同 code 可覆盖多个 URL → 前端按 code 聚合渲染） */
  code: string
  description?: string | null
  createdAt?: string | number | null
}

/** 平台用户（/user/list|get 响应 password 已置 null，不外泄 hash） */
export interface PlatformUser {
  id: string
  username: string
  displayName?: string | null
  email?: string | null
  /** 1正常 0禁用 */
  status?: number | null
  type?: string | null
  /** LOCAL/LDAP/OIDC */
  source?: string | null
  lastLoginAt?: string | number | null
  createdAt?: string | number | null
  updatedAt?: string | number | null
}

/** 租户成员行（/tenant/member/list；owner = 持有内置 tenant-admin） */
export interface TenantMemberView {
  userId: string
  username?: string | null
  displayName?: string | null
  /** 该成员在本租户内被授予的角色 id 列表 */
  roleIds?: string[] | null
  owner: boolean
}

/** 命名空间分配 */
export interface NamespaceAllocation {
  id: string
  tenantId: string
  clusterId: string
  namespace: string
  roleTemplateId?: string | null
  createTime?: string | null
}

/** waypoint 引用候选（= 后端 WaypointRefDTO 的窄投影：名字 + 处理哪类流量） */
export interface WaypointRef {
  name: string
  /** istio.io/waypoint-for：service（缺省）/ workload / all / none */
  waypointFor?: string | null
}

/** 命名空间视图（K8s 命名空间 + 分配信息合并，api 侧加工） */
export interface NamespaceView {
  name: string
  phase?: string | null
  creationTimestamp?: string | null
  managedBy: boolean
  /** 是否可编辑/删除/设配额（false = 命中受保护系统命名空间名单） */
  editable: boolean
  allocatedTenantName?: string | null
  /** 描述（metadata.annotations["description"]） */
  description?: string | null
  labels?: Record<string, string> | null
  /** Calico 绑定 IPv4 地址池（ns annotation；null/空=默认分配） */
  ipv4Pools?: string[] | null
  /** Calico 绑定 IPv6 地址池（ns annotation；null/空=默认分配） */
  ipv6Pools?: string[] | null
  /** Istio ambient 数据面模式（ns label istio.io/dataplane-mode；ambient / none；null/空=未设，即不纳入网格） */
  dataplaneMode?: string | null
  /** Istio L7 waypoint（ns label istio.io/use-waypoint；= waypoint Gateway 名 或 none；null/空=未设） */
  useWaypoint?: string | null
}

/** 资源量对（cpu 核 / memory 字节，基础单位）—— 与后端 ResourcePairDTO 对齐（D7 冻结契约） */
export interface ResourcePair {
  cpu?: number | null
  memory?: number | null
}

/** LimitRange 单条限制项；defaultValue ⇄ K8s spec.limits[].default */
export interface K8sLimitRangeItem {
  type: 'Container' | 'PersistentVolumeClaim'
  max?: ResourcePair | null
  min?: ResourcePair | null
  defaultValue?: ResourcePair | null
  defaultRequest?: ResourcePair | null
}

/** LimitRange（平台单份，对象名固定 default） */
export interface K8sLimitRange {
  name?: string | null
  namespace?: string | null
  limits?: K8sLimitRangeItem[] | null
  /** 该 ns 存在多份 LimitRange（只读告警旗标） */
  multiple?: boolean | null
  resourceVersion?: string | null
  creationTime?: string | null
}

/** 配额已用量（只读，来自 status.used），字段与约束项一一对应 */
export interface K8sResourceQuotaUsed {
  pods?: number | null
  limitsCpu?: number | null
  limitsMemory?: number | null
  requestsCpu?: number | null
  requestsMemory?: number | null
  persistentVolumeClaims?: number | null
}

/** ResourceQuota（平台单份，对象名固定 default）。值为基础单位：cpu=核、memory=字节 */
export interface K8sResourceQuota {
  name?: string | null
  namespace?: string | null
  pods?: number | null
  limitsCpu?: number | null
  limitsMemory?: number | null
  requestsCpu?: number | null
  requestsMemory?: number | null
  persistentVolumeClaims?: number | null
  used?: K8sResourceQuotaUsed | null
  multiple?: boolean | null
  resourceVersion?: string | null
  creationTime?: string | null
}

/** RBAC 模板视图（rules 已解析为结构化规则） */
export interface RbacTemplate {
  id: string
  name: string
  description?: string | null
  rules: PolicyRule[]
  builtIn: number
  createdAt?: string | null
  updatedAt?: string | null
}

/** ConfigMap（基础表单字段，与后端 ConfigMapDTO 对应） */
export interface K8sConfigMap {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  data?: Record<string, string> | null
  /** 二进制数据（值为 base64 字符串，对应 K8s binaryData） */
  binaryData?: Record<string, string> | null
  /** 不可变：true 后 data/binaryData 不可再修改 */
  immutable?: boolean | null
  creationTime?: string | null
}

/** Secret（data 为明文，后端已从 base64 解码） */
export interface K8sSecret {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  type: string
  /** key-value 明文（提交时后端映射到 stringData；读取时由 base64 data 解码） */
  data?: Record<string, string> | null
  /** 不可变：true 后 data 不可再修改 */
  immutable?: boolean | null
  creationTime?: string | null
}

/** Service 端口（targetPort 为字符串：数字或命名端口） */
export interface K8sServicePort {
  name?: string | null
  port?: number | null
  targetPort?: string | null
  nodePort?: number | null
  protocol?: string | null
  /** 应用层协议（如 http/grpc）：透传保留，UI 不编辑 */
  appProtocol?: string | null
}

/** Service */
export interface K8sService {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  type: string
  ports?: K8sServicePort[] | null
  /** Pod 选择器（label map）：绑定工作负载/Pod 时由后端解析填充；留空=端点由外部管理。仅非 ExternalName */
  selector?: Record<string, string> | null
  /** 集群 IP：''/null=自动分配，'None'=headless（无虚拟 IP），或具体 IP；编辑时不可改 */
  clusterIp?: string | null
  /** 双栈集群 IP 列表（最多 2，[0] 须等于 clusterIp） */
  clusterIps?: string[] | null
  /** 节点额外接受的 IP（K8s 不管理） */
  externalIps?: string[] | null
  /** IP 族（IPv4 / IPv6，双栈最多 2） */
  ipFamilies?: string[] | null
  /** SingleStack / PreferDualStack / RequireDualStack */
  ipFamilyPolicy?: string | null
  internalTrafficPolicy?: string | null
  externalTrafficPolicy?: string | null
  /** ClientIP 会话保持秒数（1–86400，默认 10800）；仅 sessionAffinity=ClientIP 生效 */
  sessionAffinityTimeoutSeconds?: number | null
  /** 忽略 ready/not-ready，未就绪 Pod 也纳入端点 */
  publishNotReadyAddresses?: boolean | null
  /** 会话保持：None / ClientIP */
  sessionAffinity?: string | null
  allocateLoadBalancerNodePorts?: boolean | null
  healthCheckNodePort?: number | null
  loadBalancerClass?: string | null
  loadBalancerSourceRanges?: string[] | null
  /** ExternalName 别名（CNAME，小写 RFC-1123） */
  externalName?: string | null
  /** selector 绑定来源（平台侧）：type=workload|pod + name。后端据此解析 selector；非 K8s 字段、不落库 */
  selectorRef?: { type: 'workload' | 'pod'; name: string } | null
  creationTime?: string | null
}

/** PVC（spec 创建后不可变，phase = Bound/Pending/Lost） */
export interface K8sPvc {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  storageClassName?: string | null
  accessModes?: string[] | null
  storage?: number | null
  /** 数据来源（可选，spec.dataSourceRef）：从已有对象填充新卷 */
  dataSourceRef?: { apiGroup?: string | null; kind?: string | null; name?: string | null; namespace?: string | null } | null
  phase?: string | null
  /**
   * 这块卷**真能不能**在线扩容（`unsupported` / `csi` / `unbacked`；读不到对应存储类时 `null`）。
   *
   * ⚠️ 由后端算好带下来，**前端别自己拿 `storageClassName` 去猜** —— 判据要 `CSIDriver` 名单，
   * 前端拿不到；而只看存储类上的 `allowVolumeExpansion` 是假绿灯（2026-10-16 就是这么撞上的：
   * 两个 SC 都写着 true、两个都做不到）。`null` 按「不许扩」处理，与后端同一口径。
   */
  expansion?: string | null
  /**
   * `status.conditions` 里 **status=True 的那些 type**（如 `Resizing` / `FileSystemResizePending`）。
   * ⚠️ 扩容的中间态**不在 `phase` 上** —— 扩容期间 `phase` 一直是 `Bound`，只有这里看得出来。
   */
  conditions?: string[] | null
  creationTime?: string | null
}

/** StorageClass（集群级，只读：供工作负载编辑器下拉选择 storageClassName） */
export interface K8sStorageClass {
  name: string
  provisioner?: string | null
  reclaimPolicy?: string | null
  allowVolumeExpansion?: boolean | null
  /**
   * **真能不能在线扩容**（unsupported / csi / unbacked）。
   * ⚠️ 与 allowVolumeExpansion 不是一回事：那个只表示「允许」，非 CSI 的 provisioner
   * （nfs-subdir、local-path）改了也只会把请求改大，status.capacity 一动不动。
   */
  expansion?: string | null
  creationTime?: string | null
}

/** PersistentVolume（集群级，只读：供 PVC 详情查看绑定的 PV） */
export interface K8sPersistentVolume {
  name: string
  labels?: Record<string, string> | null
  capacity?: number | null
  accessModes?: string[] | null
  storageClassName?: string | null
  reclaimPolicy?: string | null
  phase?: string | null
  claimNamespace?: string | null
  claimName?: string | null
  creationTime?: string | null
}

/** Pod 容器详情（spec + status 合并，与后端 PodContainerDTO 对应） */
export interface PodContainerDetail {
  name?: string | null
  image?: string | null
  command?: string[] | null
  args?: string[] | null
  workingDir?: string | null
  imagePullPolicy?: string | null
  envs?: EnvVar[] | null
  envFrom?: EnvFrom[] | null
  ports?: ContainerPort[] | null
  resources?: Resources | null
  lifecycle?: Lifecycle | null
  livenessProbe?: Probe | null
  readinessProbe?: Probe | null
  startupProbe?: Probe | null
  volumeMounts?: VolumeMount[] | null
  // ---- status ----
  init?: boolean | null
  ready?: boolean | null
  restartCount?: number | null
  state?: string | null
  /** 运行态原因（waiting/terminated 的 reason，如 ImagePullBackOff / CrashLoopBackOff / OOMKilled） */
  reason?: string | null
  /** 运行态详细信息（waiting/terminated 的 message） */
  message?: string | null
  startedAt?: string | null
}

/** Pod（只读：由工作负载控制器管理） */
export interface K8sPod {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  /** 对象自身注解（metadata.annotations；与标签同层） */
  annotations?: Record<string, string> | null
  phase?: string | null
  nodeName?: string | null
  podIp?: string | null
  serviceAccountName?: string | null
  restarts?: number | null
  containers?: string[] | null
  containerDetails?: PodContainerDetail[] | null
  /** 状态原因（非就绪/失败时的说明；健康时为空） */
  statusReason?: string | null
  creationTime?: string | null
}

/** 工作负载端口（基础表单：仅容器端口） */
export interface K8sWorkloadPort {
  containerPort?: number | null
}

/** 工作负载（kind = deployment/statefulset/daemonset，一个 DTO 三种 kind） */
export interface K8sWorkload {
  kind: string
  name: string
  namespace: string
  labels?: Record<string, string> | null
  replicas?: number | null
  readyReplicas?: number | null
  /** Deployment 是否暂停更新（仅 deployment 有意义） */
  paused?: boolean | null
  /** 状态原因（未完全就绪时的说明；正常时为空） */
  statusReason?: string | null
  images?: string[] | null
  ports?: K8sWorkloadPort[] | null
  creationTime?: string | null
  /** 属主引用（由 Operator/控制器创建的才有；非空即「op 管理」→ 禁用编辑） */
  ownerReferences?: OwnerRef[] | null
  /** 对外暴露的 Service（仅 list/get 返回，api 侧 join 计算）：绑定到本工作负载的 NodePort/LB Service */
  exposedServices?: K8sExposedService[] | null
  /** Pod 模板（列表也回带 —— 后端 DTO 全量）；此处只声明列表用到的部分：网格标签回显
   *  （istio.io/dataplane-mode / istio.io/use-waypoint）与「镜像」列的容器名+镜像。
   *  编辑面用 types/workload 的 WorkloadDetail。 */
  podTemplate?: {
    labels?: Record<string, string> | null
    spec?: { containers?: { name: string; image?: string | null }[] | null } | null
  } | null
}

/** ServiceMonitor 端点鉴权 Secret 引用（name + key） */
export interface K8sSmSecretRef {
  name?: string | null
  key?: string | null
}

/** ServiceMonitor Relabeling / MetricRelabeling 单条规则 */
export interface K8sSmRelabeling {
  sourceLabels?: string[] | null
  targetLabel?: string | null
  regex?: string | null
  replacement?: string | null
  separator?: string | null
  modulus?: number | null
  /** replace / keep / drop / hashmod / labelmap / labeldrop / labelkeep */
  action?: string | null
}

/** ServiceMonitor 抓取端点（spec.endpoints[] 项） */
export interface K8sSmEndpoint {
  port?: string | null
  path?: string | null
  interval?: string | null
  scrapeTimeout?: string | null
  /** http / https */
  scheme?: string | null
  /** 抓取 URL 附加参数（map[string][]string） */
  params?: Record<string, string[]> | null
  basicAuth?: { username?: K8sSmSecretRef | null; password?: K8sSmSecretRef | null } | null
  bearerTokenSecret?: K8sSmSecretRef | null
  /** Bearer Token 文件路径（容器内挂载）；平台 UI 不编辑，仅透传保留 */
  bearerTokenFile?: string | null
  tlsConfig?: { insecureSkipVerify?: boolean | null; serverName?: string | null } | null
  relabelings?: K8sSmRelabeling[] | null
  metricRelabelings?: K8sSmRelabeling[] | null
}

/** ServiceMonitor selector 表达式（spec.selector.matchExpressions[] 项） */
export interface K8sSmMatchExpression {
  key?: string | null
  /** In / NotIn / Exists / DoesNotExist */
  operator?: string | null
  values?: string[] | null
}

/** ServiceMonitor（monitoring.coreos.com/v1 CRD） */
export interface K8sServiceMonitor {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  /** spec.selector.matchLabels（必填）：由后端据 serviceRef 解析填充；查询时原样返回 */
  matchLabels?: Record<string, string> | null
  /** spec.selector.matchExpressions：与 matchLabels 并存，ANDed；支持按表达式选 Service */
  matchExpressions?: K8sSmMatchExpression[] | null
  /** 绑定的目标 Service（平台侧）：选择后据此反查 labels 生成 matchLabels；非 K8s 字段、不落库 */
  serviceRef?: { name: string; namespace: string } | null
  namespaceSelector?: { any?: boolean | null; matchNames?: string[] | null } | null
  jobLabel?: string | null
  podTargetLabels?: string[] | null
  sampleLimit?: number | null
  targetLimit?: number | null
  labelLimit?: number | null
  bodySizeLimit?: string | null
  attachMetadata?: { node?: boolean | null } | null
  /** spec.endpoints（必填） */
  endpoints?: K8sSmEndpoint[] | null
  creationTime?: string | null
}

/** PrometheusRule 里的一条规则（alert 与 record 二选一，expr 必填） */
export interface K8sPrometheusRuleRule {
  alert?: string | null
  record?: string | null
  expr?: string | null
  /** ⚠️ 后端 Java 字段是 for_（for 是关键字），JSON 键就是 for_ */
  for_?: string | null
  keepFiringFor?: string | null
  labels?: Record<string, string> | null
  annotations?: Record<string, string> | null
}

/** PrometheusRule 的一组规则（spec.groups[]） */
export interface K8sPrometheusRuleGroup {
  name?: string | null
  interval?: string | null
  queryOffset?: string | null
  limit?: number | null
  labels?: Record<string, string> | null
  partialResponseStrategy?: string | null
  rules?: K8sPrometheusRuleRule[] | null
}

/** 单条规则的运行状态（仅查询返回，由后端读 Prometheus /api/v1/rules 附着） */
export interface K8sRuleHealth {
  group?: string | null
  name?: string | null
  /** ok | err | unknown */
  health?: string | null
  lastError?: string | null
  /** inactive | pending | firing；recording 规则为 null */
  state?: string | null
  activeAlerts?: number | null
}

/** loaded = 在 Prometheus 里找到了；missing = 没找到且过了宽限期；unknown = 拉不到 */
export interface K8sRuleRuntime {
  loadState?: 'loaded' | 'missing' | 'unknown' | null
  rules?: K8sRuleHealth[] | null
}

/** PrometheusRule（monitoring.coreos.com/v1 CRD） */
export interface K8sPrometheusRule {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  /** spec.groups（必填，至少一组） */
  groups?: K8sPrometheusRuleGroup[] | null
  creationTime?: string | null
  /** 仅查询返回；拉不到 Prometheus 时为 null */
  runtime?: K8sRuleRuntime | null
}

/** 告警事件（平台库 platform_alert_event 里的一行；一次 firing 会话一行） */
export interface PlatformAlertEvent {
  id: number
  clusterId: string
  /** 来自 labels.namespace（规则作者自报）；null = 不属于任何租户 */
  namespace?: string | null
  alertname: string
  severity?: string | null
  /** firing | resolved */
  status: string
  fingerprint: string
  /** 原始 labels 的 JSON 串 */
  labels?: string | null
  /** 原始 annotations 的 JSON 串 */
  annotations?: string | null
  startsAt: string
  endsAt?: string | null
  receivedAt: string
}

/** 告警列表查询（筛选 + 游标）；租户身份由后端按 token 自动收窄 */
export interface AlertQuery {
  clusterId: string
  from?: string | null
  to?: string | null
  namespace?: string | null
  status?: string | null
  severity?: string | null
  alertname?: string | null
  /** 默认 false：心跳告警不进首屏 */
  showWatchdog?: boolean
  cursorStartsAt?: string | null
  cursorId?: number | null
  limit?: number
}

/** 自检结果：三个数字定位链路断在哪一层 */
export interface AlertSelfTestResult {
  status: number
  rowCount: number
  ids: number[]
  error?: string | null
}

/** PodMonitor endpoint Secret 引用（与后端 PodMonitorEndpointDTO.SecretRef 同构） */
export interface K8sPmSecretRef { name?: string | null; key?: string | null }

/** PodMonitor Relabeling/MetricRelabeling 单条规则（与后端 PodMonitorEndpointDTO.Relabeling 同构） */
export interface K8sPmRelabeling {
  sourceLabels?: string[] | null
  targetLabel?: string | null
  regex?: string | null
  replacement?: string | null
  separator?: string | null
  modulus?: number | null
  /** replace / keep / drop / hashmod / labelmap / labeldrop / labelkeep */
  action?: string | null
}

/** PodMonitor 抓取端点（spec.podMetricsEndpoints[] 项；port 数字/名字由后端拆 portNumber） */
export interface K8sPmEndpoint {
  /** 端口名或端口号（单一字段） */
  port?: string | null
  path?: string | null
  interval?: string | null
  scrapeTimeout?: string | null
  /** http / https */
  scheme?: string | null
  params?: Record<string, string[]> | null
  basicAuth?: { username?: K8sPmSecretRef | null; password?: K8sPmSecretRef | null } | null
  bearerTokenSecret?: K8sPmSecretRef | null
  tlsConfig?: { insecureSkipVerify?: boolean | null; serverName?: string | null } | null
  /** 标签冲突时以指标自带标签为准（PodMonitor 特有建模） */
  honorLabels?: boolean | null
  relabelings?: K8sPmRelabeling[] | null
  metricRelabelings?: K8sPmRelabeling[] | null
}

/** PodMonitor selector 表达式（spec.selector.matchExpressions[] 项） */
export interface K8sPmMatchExpression {
  key?: string | null
  /** In / NotIn / Exists / DoesNotExist */
  operator?: string | null
  values?: string[] | null
}

/** PodMonitor（monitoring.coreos.com/v1 CRD） */
export interface K8sPodMonitor {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  /** spec.selector.matchLabels（必填）：由后端据 podRef 解析填充；查询时原样返回 */
  matchLabels?: Record<string, string> | null
  matchExpressions?: K8sPmMatchExpression[] | null
  /** 绑定的目标 Pod（平台侧）：选择后据此反查 labels 生成 matchLabels；非 K8s 字段、不落库 */
  podRef?: { name: string; namespace: string } | null
  namespaceSelector?: { any?: boolean | null; matchNames?: string[] | null } | null
  jobLabel?: string | null
  podTargetLabels?: string[] | null
  sampleLimit?: number | null
  targetLimit?: number | null
  labelLimit?: number | null
  bodySizeLimit?: string | null
  attachMetadata?: { node?: boolean | null } | null
  /** spec.podMetricsEndpoints（必填；注意非 ServiceMonitor 的 endpoints） */
  podMetricsEndpoints?: K8sPmEndpoint[] | null
  creationTime?: string | null
}

/** 集群 API 能力快照（group → versions[]）；空对象 = 未探测。后端 POST /cluster/capability/get */
export type K8sClusterCapability = Record<string, string[]>

// ---- 集群组件（S1.2）----

/** 组件状态四态 —— ⚠️ UNKNOWN 不是 NOT_INSTALLED：探测失败时不能说"没装" */
export type K8sComponentState = 'INSTALLED_HEALTHY' | 'INSTALLED_UNHEALTHY' | 'NOT_INSTALLED' | 'UNKNOWN'

/** 一条组件的完整状态（平台侧投影，`POST /cluster/components`） */
export interface K8sComponentStatus {
  code: string
  name: string
  state: K8sComponentState
  version?: string | null
  namespace?: string | null
  ready?: number | null
  desired?: number | null
  detail?: string | null
  /** 它支撑的平台功能 code（依赖关系只在后端目录里写一遍） */
  features: string[]
  /** 平台可安装的 chart 版本（空 = 没有 recipe，不支持安装）；⚠️ chart 版本，与上面 version 不是一套编号 */
  availableVersions?: string[]
  /** 可编辑配置项（⚙️，来自 recipe）；契约值（🔒）不在其中 */
  editableFields?: K8sEditableField[]
  /** 安装/升级成功后是否下发平台告警联动（Secret + AlertmanagerConfig） */
  platformAlerting?: boolean
  /** 告警回调地址的平台默认值（表单预填；clusterId 由后端拼） */
  defaultAlertWebhookUrl?: string | null
  /** 安装时是否需要填 Thanos Receive 地址（Prometheus 的 remoteWrite 目标） */
  thanosRemoteWrite?: boolean
  /** Thanos Receive 地址的平台默认值（表单预填） */
  defaultThanosReceiveUrl?: string | null
}

/** 组件的一个可编辑配置项；key 是路径形式，apply 时原样回传 */
export interface K8sEditableField {
  key: string
  label: string
  type: 'string' | 'number' | 'boolean' | 'json'
  defaultValue: unknown
}

/** 组件安装/升级操作（S3.6）。chart 版本与应用版本是两套编号 —— from/to 指 chart 版本 */
export type K8sComponentOperationStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'UNKNOWN'

export interface K8sComponentOperation {
  id: number
  clusterId: string
  componentCode: string
  action: 'INSTALL' | 'UPGRADE'
  profile: string
  fromVersion?: string | null
  toVersion?: string | null
  status: K8sComponentOperationStatus
  message?: string | null
  logTail?: string | null
  operator?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

/** 一个功能的可用性旗标（租户侧窄投影，`POST /cluster/component-flags`）—— 有意不含版本/命名空间 */
export interface K8sComponentFlag {
  available: boolean
  reason?: string | null
  missing: string[]
}

/** HPA 指标目标（对应后端 HpaMetricTargetDTO） */
export interface K8sHpaMetricTarget {
  type?: 'Utilization' | 'AverageValue' | 'Value' | null
  averageUtilization?: number | null
  value?: number | null
  averageValue?: number | null
}

/** HPA 跨版本对象引用（scaleTargetRef / object.describedObject） */
export interface K8sHpaObjectRef {
  apiVersion?: string | null
  kind?: string | null
  name?: string | null
  namespace?: string | null
}

/** HPA 单条指标（type 决定哪个子字段有值，五类互斥） */
export interface K8sHpaMetric {
  type: 'Resource' | 'ContainerResource' | 'Pods' | 'Object' | 'External'
  resource?: { name?: string | null; target?: K8sHpaMetricTarget | null } | null
  containerResource?: { container?: string | null; name?: string | null; target?: K8sHpaMetricTarget | null } | null
  pods?: { metricName?: string | null; target?: K8sHpaMetricTarget | null } | null
  object?: { describedObject?: K8sHpaObjectRef | null; metricName?: string | null; selector?: Record<string, string> | null; target?: K8sHpaMetricTarget | null } | null
  external?: { metricName?: string | null; metricSelector?: Record<string, string> | null; target?: K8sHpaMetricTarget | null } | null
}

/** HPA 扩缩容速率策略 */
export interface K8sHpaScalingPolicy {
  type?: 'Pods' | 'Percent' | null
  value?: number | null
  periodSeconds?: number | null
}

/** HPA 扩缩容行为规则（scaleUp / scaleDown） */
export interface K8sHpaBehaviorRule {
  stabilizationWindowSeconds?: number | null
  selectPolicy?: 'Max' | 'Min' | 'Disabled' | null
  policies?: K8sHpaScalingPolicy[] | null
}

/** HPA 扩缩容行为（v2.1+） */
export interface K8sHpaBehavior {
  scaleUp?: K8sHpaBehaviorRule | null
  scaleDown?: K8sHpaBehaviorRule | null
}

/** HPA（HorizontalPodAutoscaler，autoscaling/v2 全保真；v1 集群仅 CPU） */
export interface K8sHpa {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  minReplicas?: number | null
  maxReplicas?: number | null
  currentReplicas?: number | null
  scaleTargetRef?: K8sHpaObjectRef | null
  metrics?: K8sHpaMetric[] | null
  behavior?: K8sHpaBehavior | null
  creationTime?: string | null
}

/** 资源管理上下文级联（/context） */
export interface ResourceContextCluster {
  clusterId: string
  clusterName: string
  /** 集群 IP 栈：决定 Service 编辑器展示单栈还是双栈表单 */
  ipStack?: 'IPV4' | 'IPV6' | 'IPV4_AND_IPV6' | null
  namespaces: string[]
}

export interface ResourceContextTenant {
  tenantId: string
  name: string
  status: number
  clusters: ResourceContextCluster[]
}

export interface ResourceContext {
  tenants: ResourceContextTenant[]
}

// ===== 节点（集群级，admin）=====

/** 节点 Taint */
export interface NodeTaint {
  key?: string | null
  value?: string | null
  effect?: string | null
}

/** 节点 Condition */
export interface NodeCondition {
  type?: string | null
  status?: string | null
  reason?: string | null
  message?: string | null
  lastHeartbeatTime?: string | null
  lastTransitionTime?: string | null
}

/** K8s 节点（集群级；capacity/allocatable 为原始字符串如 "8" / "16Gi"） */
/**
 * 节点地址窄投影（/nodes/addresses）：只回 name + 两个 IP + Ready。
 * 供工作负载列表页点击 nodePort 直连用 —— 只需要"哪台机器能连"，
 * 容量/标签/污点这些集群拓扑不在这份数据里。
 */
export interface NodeAddress {
  name?: string | null
  /** 内网 IP（status.addresses[type=InternalIP]） */
  internalIp?: string | null
  /** 外网 IP（未设置则为空） */
  externalIp?: string | null
  ready?: boolean | null
}

export interface K8sNode {
  name: string
  clusterId?: string | null
  labels?: Record<string, string> | null
  /** Ready condition 的 status：True/False/Unknown */
  status?: string | null
  unschedulable?: boolean | null
  roles?: string[] | null
  kubeletVersion?: string | null
  os?: string | null
  arch?: string | null
  kernelVersion?: string | null
  containerRuntimeVersion?: string | null
  osImage?: string | null
  podCidr?: string | null
  internalIp?: string | null
  externalIp?: string | null
  cpuCapacity?: number | null
  memoryCapacity?: number | null
  cpuAllocatable?: number | null
  memoryAllocatable?: number | null
  podsLimit?: number | null
  conditions?: NodeCondition[] | null
  taints?: NodeTaint[] | null
  creationTime?: string | null
  cpuRequestMillicores?: number | null
  memRequestBytes?: number | null
  podCount?: number | null
}

/** 节点 Drain 结果 */
export interface NodeDrainResult {
  evicted?: string[] | null
  skipped?: string[] | null
  errors?: string[] | null
}

/** 节点相关事件（节点自身 + 该节点 kubelet 上报，含 Pod 级） */
export interface NodeEvent {
  reason?: string | null
  message?: string | null
  type?: string | null
  count?: number | null
  firstTimestamp?: string | null
  lastTimestamp?: string | null
  /** 涉及对象类型（involvedObject.kind）：Node / Pod / … */
  kind?: string | null
  /** 涉及对象名（involvedObject.name） */
  objectName?: string | null
  /** 涉及对象命名空间 */
  namespace?: string | null
}

/** 节点当前用量（列表页 CPU%/内存%） */
export interface NodeCurrentMetric {
  cpuPercent?: number | null
  memPercent?: number | null
}

// ===== 网络 Calico（集群级，admin；/calico/**）=====

/** K8s condition（Calico CRD status.conditions 项） */
export interface K8sCondition {
  type?: string | null
  status?: string | null
  reason?: string | null
  message?: string | null
  lastTransitionTime?: string | null
}

/** IPPool（projectcalico.org/v3 CRD，集群级；无租户/命名空间维度） */
export interface K8sIpool {
  name: string
  clusterId?: string | null
  labels?: Record<string, string> | null
  /** CIDR（必填），如 10.48.0.0/16 */
  cidr?: string | null
  /** 块前缀长度（null=Calico 默认，v4 通常 26 / v6 122） */
  blockSize?: number | null
  /** 节点选择器（如 projectcalico.org/node==worker） */
  nodeSelector?: string[] | null
  natOutgoing?: boolean | null
  disabled?: boolean | null
  ipv4hierarchicalPortAllocation?: boolean | null
  blocks?: string[] | null
  /** 只读：status.conditions */
  conditions?: K8sCondition[] | null
  creationTime?: string | null
}

/** IPPool IPAM 派生汇总（非 CRD，后端算）。计数为 number（后端 long；IPv6 饱和值展示时按量级格式化） */
export interface PoolIpamSummary {
  poolName?: string | null
  cidr?: string | null
  blockSize?: number | null
  capacity?: number | null
  allocated?: number | null
  free?: number | null
  reserved?: number | null
  blockCount?: number | null
}

/** 单个已物化 IPAM 块统计（三态划分 free+reserved+allocated=totalIps） */
export interface IpamBlockStat {
  cidr: string
  node?: string | null
  totalIps?: number | null
  allocated?: number | null
  free?: number | null
  reserved?: number | null
}

/** 单块 per-IP 明细（status 三态；allocated 由 pod.status.podIP 反查） */
export interface IpamIpDetail {
  ip: string
  status: 'free' | 'reserved' | 'allocated'
  podName?: string | null
  podNamespace?: string | null
  node?: string | null
}

/** IPReservation（projectcalico.org/v3 CRD，集群级；spec.reservedCIDRs = CIDR 列表，单 IP = "ip/32"、范围 = "cidr"） */
export interface K8sIpReservation {
  name: string
  clusterId?: string | null
  labels?: Record<string, string> | null
  /** spec.reservedCIDRs：保留 CIDR 列表（单 IP = "ip/32"，范围 = "cidr"） */
  reservedCidrs?: string[] | null
  creationTime?: string | null
}

// ===== BGP* 只读（projectcalico.org/v3 CRD，集群级；view-only，无写入口）=====

/** Secret 引用（BGP 密码；只存引用不存密文） */
export interface BgpSecretKeyRef {
  name?: string | null
  namespace?: string | null
  key?: string | null
}

/** BGPConfiguration spec.service*IPs 单项 */
export interface BgpServiceBlock {
  cidr?: string | null
}

/** BGP community 名称↔值映射项 */
export interface BgpCommunity {
  name?: string | null
  value?: string | null
}

/** 按前缀的宣告配置项 */
export interface BgpPrefixAdvertisement {
  cidr?: string | null
  communities?: string[] | null
}

/** BGPConfiguration（只读；asNumber 为 numorstring → string） */
export interface BgpConfiguration {
  name: string
  clusterId?: string | null
  labels?: Record<string, string> | null
  logSeverityScreen?: string | null
  nodeToNodeMeshEnabled?: boolean | null
  /** numorstring：64512 或 "AS64512" */
  asNumber?: string | null
  listenPort?: number | null
  serviceClusterIPs?: BgpServiceBlock[] | null
  serviceExternalIPs?: BgpServiceBlock[] | null
  serviceLoadBalancerIPs?: BgpServiceBlock[] | null
  communities?: BgpCommunity[] | null
  prefixAdvertisements?: BgpPrefixAdvertisement[] | null
  /** spec.nodeMeshPassword.secretKeyRef */
  nodeMeshPassword?: BgpSecretKeyRef | null
  /** None / NodeIP */
  bindMode?: string | null
  ignoredInterfaces?: string[] | null
  // 新版字段（旧版 Calico 缺席 → null）
  serviceLoadBalancerAggregation?: string | null
  localWorkloadPeeringIPV4?: string | null
  localWorkloadPeeringIPV6?: string | null
  programClusterRoutes?: string | null
  ipv4NormalRoutePriority?: number | null
  ipv6NormalRoutePriority?: number | null
  conditions?: K8sCondition[] | null
  creationTime?: string | null
}

/** BGPPeer（只读；真实 schema：peerIP、nodeSelector 为字符串） */
export interface BgpPeer {
  name: string
  clusterId?: string | null
  labels?: Record<string, string> | null
  node?: string | null
  /** 节点选择器表达式（字符串，非列表） */
  nodeSelector?: string | null
  /** 对端地址，可带端口：10.0.0.1:179 */
  peerIp?: string | null
  asNumber?: string | null
  localAsNumber?: string | null
  peerSelector?: string | null
  keepOriginalNextHop?: boolean | null
  /** Auto / Self / Keep（新版） */
  nextHopMode?: string | null
  /** spec.password.secretKeyRef */
  password?: BgpSecretKeyRef | null
  /** UseNodeIP / None */
  sourceAddress?: string | null
  maxRestartTime?: string | null
  keepaliveTime?: string | null
  numAllowedLocalASNumbers?: number | null
  ttlSecurity?: number | null
  reachableBy?: string | null
  filters?: string[] | null
  localWorkloadSelector?: string | null
  /** Auto / Manual（新版） */
  reversePeering?: string | null
  conditions?: K8sCondition[] | null
  creationTime?: string | null
}

/** BGPFilter 规则单项（v4/v6 共用；展示向扁平化：prefixLength→min/max、communities.values、operations 判别联合） */
export interface BgpFilterRule {
  cidr?: string | null
  prefixLengthMin?: number | null
  prefixLengthMax?: number | null
  /** RemotePeers */
  source?: string | null
  /** 出接口匹配（真实 JSON key = "interface"，Java 保留字故后端字段名 iface） */
  iface?: string | null
  /** Equal / NotEqual / In / NotIn */
  matchOperator?: string | null
  /** eBGP / iBGP */
  peerType?: string | null
  communityValues?: string[] | null
  asPathPrefix?: string[] | null
  priority?: number | null
  /** Accept / Reject */
  action?: string | null
  operations?: BgpFilterOperation[] | null
}

/** BGPFilter 规则操作（判别联合 → 三个互斥字段） */
export interface BgpFilterOperation {
  addCommunity?: string | null
  prependAsPath?: string[] | null
  setPriority?: number | null
}

/** BGPFilter（spec = exportV4/importV4/exportV6/importV6 四条规则列表） */
export interface BgpFilter {
  name: string
  clusterId?: string | null
  labels?: Record<string, string> | null
  exportV4?: BgpFilterRule[] | null
  importV4?: BgpFilterRule[] | null
  exportV6?: BgpFilterRule[] | null
  importV6?: BgpFilterRule[] | null
  conditions?: K8sCondition[] | null
  creationTime?: string | null
}

/** BGP 编辑器下拉候选（/calico/form-options，只读）；Secret 引用按命名空间另走级联接口 */
export interface CalicoFormOption {
  namespaces: string[]
  workloads: WorkloadOption[]
}

/** Secret 引用候选：命名空间 + 名称 + data keys */
export interface SecretRefOption {
  namespace: string
  name: string
  keys: string[]
}

/** 工作负载候选：Deployment / StatefulSet */
export interface WorkloadOption {
  namespace: string
  kind: string
  name: string
}

// ==================== 服务网格 Gateway API（B6） ====================
// 字段名与后端 DTO 一一对应（Jackson 直接序列化），也与 Gateway API v1 的 CRD 字段同名。
// ⚠️ 与 spec（2026-09-15）不同、按 v1.6 CRD 实测修正过的几处：
//   · allowedRoutes 是 { namespaces: { from, selector }, kinds[] }，不是 namespacesFrom/namespaceSelector
//   · listener tls.mode 只有 Terminate / Passthrough
//   · route 的父引用叫 parentRefs，元素是 group/kind/namespace/name/sectionName/port
//   · backendRef 的权重字段是 weight（0–1000000，缺省 1），不是百分比

/** 标签选择器（allowedRoutes.namespaces.selector，结构同 metav1.LabelSelector） */
export interface K8sLabelSelector {
  matchLabels?: Record<string, string> | null
  matchExpressions?: { key?: string | null; operator?: string | null; values?: string[] | null }[] | null
}

/** spec.parametersRef：group/kind/name 必填；namespace 仅在目标为命名空间级资源时给 */
export interface K8sGatewayClassParametersRef {
  group?: string | null
  kind?: string | null
  name?: string | null
  namespace?: string | null
}

/** GatewayClass（gateway.networking.k8s.io/v1，集群级；平台管理面） */
export interface K8sGatewayClass {
  name: string
  controllerName?: string | null
  parametersRef?: K8sGatewayClassParametersRef | null
  description?: string | null
  conditions?: K8sCondition[] | null
  creationTime?: string | null
  labels?: Record<string, string> | null
}

/** listener.tls.certificateRefs[]：缺省 group=core、kind=Secret、namespace=Gateway 所在命名空间 */
export interface K8sGatewayCertRef {
  group?: string | null
  kind?: string | null
  name?: string | null
  namespace?: string | null
}

/** listener.tls：mode 只有 Terminate / Passthrough（HTTPS 固定 Terminate） */
export interface K8sGatewayTls {
  mode?: string | null
  certificateRefs?: K8sGatewayCertRef[] | null
}

/** allowedRoutes.kinds[] */
export interface K8sGatewayRouteGroupKind {
  group?: string | null
  kind?: string | null
}

/** allowedRoutes.namespaces：from ∈ All / Same / Selector / None；from=Selector 时 selector 生效 */
export interface K8sGatewayRouteNamespaces {
  from?: string | null
  selector?: K8sLabelSelector | null
}

/** listener.allowedRoutes：限制哪些 Route 能挂到本 listener */
export interface K8sGatewayAllowedRoutes {
  namespaces?: K8sGatewayRouteNamespaces | null
  kinds?: K8sGatewayRouteGroupKind[] | null
}

/** spec.listeners[] 项 */
export interface K8sGatewayListener {
  name?: string | null
  hostname?: string | null
  port?: number | null
  /** HTTP / HTTPS / TLS / TCP / UDP */
  protocol?: string | null
  tls?: K8sGatewayTls | null
  allowedRoutes?: K8sGatewayAllowedRoutes | null
}

/** spec.infrastructure（parametersRef 未建模，只在 YAML tab 可见） */
export interface K8sGatewayInfrastructure {
  labels?: Record<string, string> | null
  annotations?: Record<string, string> | null
}

/** spec.addresses[] / status.addresses[] */
export interface K8sGatewayAddress {
  type?: string | null
  value?: string | null
}

/** status.listeners[] */
export interface K8sGatewayListenerStatus {
  name?: string | null
  attachedRoutes?: number | null
  supportedKinds?: K8sGatewayRouteGroupKind[] | null
  conditions?: K8sCondition[] | null
}

/** Gateway（gateway.networking.k8s.io/v1，命名空间级、租户域） */
export interface K8sGateway {
  name: string
  namespace?: string | null
  gatewayClassName?: string | null
  listeners?: K8sGatewayListener[] | null
  infrastructure?: K8sGatewayInfrastructure | null
  addresses?: K8sGatewayAddress[] | null
  conditions?: K8sCondition[] | null
  listenerStatuses?: K8sGatewayListenerStatus[] | null
  creationTime?: string | null
  labels?: Record<string, string> | null
}

/** Route → 父资源引用（spec.parentRefs[]；HTTP/GRPC/TCP/TLS/UDP 共用） */
export interface K8sRouteParentRef {
  group?: string | null
  kind?: string | null
  namespace?: string | null
  name?: string | null
  /** 目标 Gateway 的 listener 名 */
  sectionName?: string | null
  port?: number | null
}

/** Route → 后端引用（rule.backendRefs[]；weight 0–1000000，缺省 1） */
export interface K8sRouteBackendRef {
  group?: string | null
  kind?: string | null
  name?: string | null
  namespace?: string | null
  port?: number | null
  weight?: number | null
}

/** rule.matches[].path：type ∈ Exact / PathPrefix / RegularExpression */
export interface K8sHrPathMatch {
  type?: string | null
  value?: string | null
}

/** rule.matches[].headers[] / queryParams[]：type ∈ Exact（缺省）/ RegularExpression */
export interface K8sHrValueMatch {
  type?: string | null
  name?: string | null
  value?: string | null
}

/** rule.matches[] 项（四维 AND） */
export interface K8sHrMatch {
  path?: K8sHrPathMatch | null
  method?: string | null
  headers?: K8sHrValueMatch[] | null
  queryParams?: K8sHrValueMatch[] | null
}

/** 头操作：set / add / remove */
export interface K8sHrHeaderFilter {
  set?: { name?: string | null; value?: string | null }[] | null
  add?: { name?: string | null; value?: string | null }[] | null
  remove?: string[] | null
}

/** 路径重写：type=ReplaceFullPath 配 replaceFullPath；ReplacePrefixMatch 配 replacePrefixMatch */
export interface K8sHrPathModifier {
  type?: string | null
  replaceFullPath?: string | null
  replacePrefixMatch?: string | null
}

/** RequestRedirect（statusCode 仅 301/302，缺省 302） */
export interface K8sHrRequestRedirect {
  scheme?: string | null
  hostname?: string | null
  path?: K8sHrPathModifier | null
  port?: number | null
  statusCode?: number | null
}

/** URLRewrite（hostname 与 path 至少给一个） */
export interface K8sHrUrlRewrite {
  hostname?: string | null
  path?: K8sHrPathModifier | null
}

/** RequestMirror（backendRef 无 weight —— 这里是 BackendObjectReference） */
export interface K8sHrRequestMirror {
  backendRef?: K8sRouteBackendRef | null
  percent?: number | null
}

/** filter.extensionRef（group/kind/name 三者都必填） */
export interface K8sHrExtensionRef {
  group?: string | null
  kind?: string | null
  name?: string | null
}

/**
 * rule.filters[] 项。已建模 6 种 type；`CORS` / `ExternalAuth` 未建模 —— 后端 fetch-overlay
 * 会按 type 保留整个元素，前端只在 YAML tab 里看得到它们。
 */
export interface K8sHrFilter {
  type?: string | null
  requestHeaderModifier?: K8sHrHeaderFilter | null
  responseHeaderModifier?: K8sHrHeaderFilter | null
  requestRedirect?: K8sHrRequestRedirect | null
  urlRewrite?: K8sHrUrlRewrite | null
  requestMirror?: K8sHrRequestMirror | null
  extensionRef?: K8sHrExtensionRef | null
}

/**
 * rule。name/matches/filters/backendRefs 已建模；
 * timeouts / retry / sessionPersistence 未建模 → 后端 fetch-overlay 原样保留（YAML tab 可见）。
 */
export interface K8sHrRule {
  name?: string | null
  matches?: K8sHrMatch[] | null
  filters?: K8sHrFilter[] | null
  backendRefs?: K8sRouteBackendRef[] | null
}

/** status.parents[] 项（5 类 Route 共用：HTTP/GRPC/TCP/TLS/UDP） */
export interface K8sRouteParentStatus {
  parentRef?: K8sRouteParentRef | null
  controllerName?: string | null
  conditions?: K8sCondition[] | null
}

/** HTTPRoute（gateway.networking.k8s.io/v1，命名空间级、租户域） */
export interface K8sHttpRoute {
  name: string
  namespace?: string | null
  parentRefs?: K8sRouteParentRef[] | null
  hostnames?: string[] | null
  rules?: K8sHrRule[] | null
  parentStatuses?: K8sRouteParentStatus[] | null
  creationTime?: string | null
  labels?: Record<string, string> | null
}

// ---------- GRPCRoute（骨架同 HTTPRoute，差异在 matches 与 filters）----------

/** gRPC 方法匹配：service/method 是服务名与方法名，不含斜杠前缀 */
export interface K8sGrpcMethodMatch {
  type?: string | null
  service?: string | null
  method?: string | null
}

/** gRPC 请求头（metadata）匹配 */
export interface K8sGrpcHeaderMatch {
  type?: string | null
  name?: string | null
  value?: string | null
}

/** rule.matches[] 项：只有 method 与 headers（无 path、无 queryParams） */
export interface K8sGrpcMatch {
  method?: K8sGrpcMethodMatch | null
  headers?: K8sGrpcHeaderMatch[] | null
}

/** rule.filters[] 项：只有 4 种 type（无 RequestRedirect / URLRewrite） */
export interface K8sGrpcFilter {
  type?: string | null
  requestHeaderModifier?: K8sHrHeaderFilter | null
  responseHeaderModifier?: K8sHrHeaderFilter | null
  requestMirror?: K8sHrRequestMirror | null
  extensionRef?: K8sHrExtensionRef | null
}

export interface K8sGrpcRule {
  name?: string | null
  matches?: K8sGrpcMatch[] | null
  filters?: K8sGrpcFilter[] | null
  backendRefs?: K8sRouteBackendRef[] | null
}

/** GRPCRoute（自 Gateway API v1.1 GA，单挂 v1） */
export interface K8sGrpcRoute {
  name: string
  namespace?: string | null
  parentRefs?: K8sRouteParentRef[] | null
  hostnames?: string[] | null
  rules?: K8sGrpcRule[] | null
  parentStatuses?: K8sRouteParentStatus[] | null
  creationTime?: string | null
  labels?: Record<string, string> | null
}

// ---------- L4 路由（TCP / TLS / UDP）----------

/**
 * L4 路由的一条规则：只有 name + backendRefs。
 * ⚠️ 没有 matches —— L4 不做七层匹配；TLSRoute 的 SNI 匹配在 spec.hostnames（见 K8sTlsRoute），
 * 不是旧实验 API 的 rules[].matches[].sniHostname（Gateway API 所有 v1.x 版本都没有它）。
 */
export interface K8sL4RouteRule {
  name?: string | null
  backendRefs?: K8sRouteBackendRef[] | null
}

/** TCPRoute（CRD 版本 v1 / v1alpha2 由后端按集群 capability 分派；rules 在 CRD 里 maxItems=1） */
export interface K8sTcpRoute {
  name: string
  namespace?: string | null
  parentRefs?: K8sRouteParentRef[] | null
  rules?: K8sL4RouteRule[] | null
  parentStatuses?: K8sRouteParentStatus[] | null
  creationTime?: string | null
  labels?: Record<string, string> | null
}

/** UDPRoute（与 TCPRoute 同构） */
export interface K8sUdpRoute {
  name: string
  namespace?: string | null
  parentRefs?: K8sRouteParentRef[] | null
  rules?: K8sL4RouteRule[] | null
  parentStatuses?: K8sRouteParentStatus[] | null
  creationTime?: string | null
  labels?: Record<string, string> | null
}

/** TLSRoute（比 TCP/UDP 多 hostnames，即 SNI 匹配） */
export interface K8sTlsRoute {
  name: string
  namespace?: string | null
  parentRefs?: K8sRouteParentRef[] | null
  /** SNI 名（空 = 匹配全部 SNI） */
  hostnames?: string[] | null
  rules?: K8sL4RouteRule[] | null
  parentStatuses?: K8sRouteParentStatus[] | null
  creationTime?: string | null
  labels?: Record<string, string> | null
}

/**
 * 服务网格探测结果（/mesh/status）。hasGatewayApi=false 是模块的硬门禁（create 禁用 + 横幅提示）；
 * hasIstio / istioAmbient 是信息性展示。
 */
export interface MeshStatus {
  hasIstio: boolean
  istioAmbient: boolean
  hasGatewayApi: boolean
  gatewayApiVersions: string[]
}

/** 拓扑里的一个节点。⚠️ id = `命名空间/工作负载` —— 同名 workload 跨命名空间很常见，
 *  只存 workload 名会把两条边粘成一个节点。 */
export interface MeshTopologyNode {
  id: string
  namespace: string
  workload: string
  /** 外部网关（istio-system 里那些）：它是"外部入口"，画得跟业务节点不一样 */
  gateway: boolean
}

/** 拓扑里的一条边。⚠️ p95Ms 为 null = 窗口内没有延迟样本，UI 要显示「—」而不是 0。 */
export interface MeshTopologyEdge {
  source: string
  target: string
  /** `L7` = 有请求数/响应码/延迟；`L4` = 只有连接与字节（没有错误率、没有请求延迟）。 */
  level: 'L7' | 'L4'
  rps: number
  /** 5xx / 总量，0..1。仅 L7 有意义。 */
  errorRate: number
  p95Ms?: number | null
  /** L4 的流量口径：字节/秒。仅 L4 边，或同一对端点也有 L4 流量的 L7 边。 */
  bytesPerSec?: number | null
}

export interface MeshTopology {
  nodes: MeshTopologyNode[]
  edges: MeshTopologyEdge[]
  windowSeconds: number
  /** ⚠️ false = 这个集群**没在采集**网格指标，不是"没有流量"。两种空态文案不同。 */
  meshMetricsAvailable: boolean
}

/**
 * 审计日志行（S0.1）。没有单条详情端点：列表即返回全部字段，详情为行展开。
 */
export interface AuditLog {
  id: number
  occurredAt: string
  actorUsername: string
  actorDisplayName?: string | null
  tenantId?: string | null
  clusterId?: string | null
  namespace?: string | null
  /** 写操作 = 动作码（<resource>:<verb>）；DENIED = 被拒的权限码 */
  action?: string | null
  targetName?: string | null
  result: 'SUCCESS' | 'FAILED' | 'DENIED'
  errorCode?: string | null
  errorMsg?: string | null
  /** 仅 DENIED 有值 */
  httpMethod?: string | null
  /** 仅 DENIED 有值 */
  requestPath?: string | null
  clientIp?: string | null
  userAgent?: string | null
  /** 仅写操作有值 */
  durationMs?: number | null
}

/**
 * 审计列表查询：筛选 + 游标分页（游标 = 上一页最后一条的 occurredAt + id）。
 */
export interface AuditQuery {
  from?: string | null
  to?: string | null
  actorUsername?: string | null
  clusterId?: string | null
  namespace?: string | null
  result?: string | null
  action?: string | null
  cursorOccurredAt?: string | null
  cursorId?: number | null
  limit?: number
}

/**
 * 凭据（S0.2）。**本类型不含凭据内容** —— 后端永不回显，只有 hasSecret（有没有配）
 * 与 fingerprint（哪一份）两种派生形态。加字段前先读后端 spec 的「永不回显」一节。
 */
export interface CredentialView {
  id: string
  name: string
  /**
   * GIT / HARBOR（镜像仓库）/ DOCKERHUB（**私有** Docker Hub：用户名 + PAT）。
   * 公共镜像的来源是平台的**内置档**，不走凭据表 —— 所以这里的 DOCKERHUB 专指私有仓库
   */
  type: 'GIT' | 'HARBOR' | 'DOCKERHUB'
  /** host 基址 */
  endpoint: string
  /** BASIC = 用户名+密码/令牌；NONE = 匿名（无凭据，出站不带 Authorization 头） */
  authType: 'BASIC' | 'NONE'
  hasSecret: boolean
  /** 明文认证字段 JSON 的 SHA-256 前 16 位 */
  fingerprint?: string | null
  description?: string | null
  enabled: boolean
  /** 1 = 允许在流水线里使用（**默认 0/关**）。它不是凭据内容，可以回显（就是个开关） */
  usableByPipelines?: number | null
  /** 上次测试通过的时刻；测失败不改它 */
  lastVerifiedAt?: string | null
  /** 上次测试的失败原因；成功则 null */
  lastVerifyError?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/**
 * 新建凭据。`authType=BASIC` 时 username / secret 必填；
 * **`NONE`（匿名）时两者都必须为空**（后端会拒掉「匿名 + 带凭据」）。
 */
export interface CredentialCreateBody {
  name: string
  type: 'GIT' | 'HARBOR' | 'DOCKERHUB'
  endpoint: string
  authType: 'BASIC' | 'NONE'
  username?: string
  secret?: string
  description?: string | null
  enabled?: boolean
  /** 允许在流水线里使用（默认关）。开了之后**任意租户**的流水线都能引用它 */
  usableByPipelines?: boolean
}

/** 更新凭据。username / secret **留空 = 保持不变**；authType 变更时必须重填。 */
export interface CredentialUpdateBody {
  id: string
  name: string
  type: 'GIT' | 'HARBOR' | 'DOCKERHUB'
  endpoint: string
  authType: 'BASIC' | 'NONE'
  username?: string | null
  secret?: string | null
  description?: string | null
  enabled?: boolean
  /** 留空 = 保持不变（后端把 undefined 当"不动"，避免只改描述的一次保存悄悄撤销授权）；要关掉就显式传 false */
  usableByPipelines?: boolean
}

/** 测试连接结果。suggestedUrl 非空 = 「对端要求改用这个地址」，要提供一键填入。 */
export interface CredentialTestResult {
  ok: boolean
  message: string
  suggestedUrl?: string | null
}

/**
 * 浏览页「连接下拉」的窄投影（S3.1）。**只有四个字段** —— 不是 `/credential/list` 的复用，
 * 那个要平台管理员的码，租户进页面就会 403。
 */
export interface RegistryConnectionView {
  id: string
  name: string
  type: string
  endpoint: string
}

/** 浏览结果的一节面包屑。path 是累积路径，点击即回到该层。 */
export interface RegistryCrumb {
  label: string
  path: string[]
}

/** 一个节点。details 的标签**就是表格的列头** —— 列由后端决定，前端不写死。 */
export interface RegistryNode {
  name: string
  hasChildren: boolean
  /** 非空 = 这一行可「复制镜像地址」 */
  copyText?: string | null
  details: { label: string; value: string }[]
}

export interface RegistryBrowseResult {
  breadcrumb: RegistryCrumb[]
  children: RegistryNode[]
}

/**
 * 一条镜像候选（S3.1 增补）。`value` = 要填进输入框的**完整串**，前端整段替换、不做拼接；
 * `connectionName` = 它来自哪条连接，**只用于展示**。
 *
 * <p>一次输入可能同时问多条连接（无 host 时），所以必须能分辨来源 ——
 * 而「是哪个连接」是唯一无法从候选串里推出来的信息。
 * 两条连接返回同一个 value 是可能的（同 endpoint 的两条连接），但下拉由 el-autocomplete
 * 自己渲染、按 index 建 key，所以不会重复 key。若将来改成自己写 `v-for` 渲染候选，
 * key 要用 `value + '|' + connectionName`。
 */
export interface RegistrySuggestItem {
  value: string
  connectionName: string
}

export interface RegistrySuggestResponse {
  items: RegistrySuggestItem[]
}





// ===================== CI（流水线，S3.5 第 ① 片）=====================

/** 一个节点的表单字段。**由后端 schema 驱动** —— 前端不写死任何节点的字段清单。 */
export interface PipelineNodeField {
  key: string
  label: string
  type: 'TEXT' | 'TEXTAREA' | 'SELECT' | 'IMAGE' | 'BOOLEAN' | 'NUMBER'
  required: boolean
  placeholder?: string | null
  help?: string | null
  defaultValue?: string | null
  options?: { value: string; label: string }[] | null
  /** 收在属性面板的「高级」里（常态不用填、但排障时必须能填的字段，例如代理） */
  advanced?: boolean
  /** 只对 `NUMBER` 有意义：**固定**跟在输入框后面的单位（如 `Mi`）。
   *  ⚠️ 单位由后端给，用户只填数字 —— 让用户写单位最后会出现 `2G`/`2GB`/`2048` 三种写法 */
  unit?: string | null
}

/** 语言预设。**只是默认值** —— 选它只换下面几个框里的内容，不锁死任何字段。 */
export interface BuildPreset {
  key: string
  label: string
  image: string
  command: string
}

/** `/pipeline/node-types` 的一项（透传自 k8s-server 的节点注册表）。 */
export interface PipelineNodeType {
  type: string
  /** 画布与面板上的显示名（后端节点自己声明的） */
  label: string
  /** 一句话说明「这个节点是干嘛的」，节点面板上显示 */
  description: string
  fields: PipelineNodeField[]
  presets: BuildPreset[]
  /** Element Plus 图标名 */
  icon: string
  /** 配色**语义名**（success / accent / warning / info / danger）—— 到 CSS 才落成具体色值，
   *  这样浅色与暗色两套主题各自取自己的色 */
  accent: string
  /** 卡片副标题取哪几个字段的值（按顺序用 ` · ` 连） */
  summaryFields: string[]
}

export interface PipelineCanvasNode {
  /** **身份**：进 Tekton 的 task 名，也是日志与运行状态的键（前端生成，形如 `git-l8x31`） */
  id: string
  type: string
  /** **显示名**（用户起的，可中文、可空）。空 = 各处退回显示 `id`。
   *  ⚠️ 它不参与任何键：改名字不动集群对象名，也不会让旧运行的日志对不上 */
  name?: string | null
  config: Record<string, string>
  /** 画布上的**显示坐标**（纯展示，渲染器不看它）。
   *  ⚠️ 必须存下来：不存就等于「每次打开都换一张图」，用户手摆的布局白摆。
   *  老画布没有它 → 退回自动排布 */
  position?: { x: number; y: number } | null
  /** **失败也继续**：`'continue'` = 这一节点失败不阻断它的下游（Tekton 的 `task.onError`）。
   *  不写 = 照常失败（它挂了，下游全部跳过）。
   *  ⚠️ 取值与后端 `PipelineRenderer.CONTINUE` 成对 —— 改要同时改 */
  onError?: string | null
  /** **收尾执行**：true = 挪进 Tekton 的 `finally[]`。
   *  ⚠️ 收尾节点**不参与顺序**（既不能连线，也不能写 runAfter）—— 它们同时开跑 */
  finallyTask?: boolean | null
  /** 收尾节点的**执行时机**：`'always'`（不写）/ `'failed'` / `'succeeded'`。
   *  只在 `finallyTask` 为 true 时有意义。
   *  ⚠️ 与后端 `PipelineRenderer.WHEN_*` 三个常量成对 —— 改要同时改 */
  finallyWhen?: string | null
}

/** 运行控制那一小包（属性面板 → 编辑器的 patch）。
 *  ⚠️ 走 patch 而不是让面板直接改 `data`：勾上「收尾执行」要**顺手摘掉它的连线**，
 *  那个副作用只有编辑器做得了（面板不持有 edges）。 */
export interface PipelineRunControlPatch {
  onError?: string
  finallyTask?: boolean
  finallyWhen?: string
}

export interface PipelineCanvasEdge {
  from: string
  to: string
}

export interface PipelineCanvas {
  nodes: PipelineCanvasNode[]
  edges: PipelineCanvasEdge[]
}

/** 流水线**定义**（平台自己的表；集群里不留 Pipeline 对象）。 */
export interface Pipeline {
  id?: string
  clusterId?: string
  namespace?: string
  name: string
  description?: string | null
  /** 画布 JSON：`{nodes, edges}` —— 流水线的唯一真相源 */
  canvasJson?: string | null
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 一次运行。**归档后集群对象就没了，库是运行历史的唯一来源**。 */
export interface PipelineRun {
  id: string
  pipelineId: string
  clusterId: string
  namespace: string
  /** 集群里的 PipelineRun 名，也是归档幂等键 */
  runName: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT' | string
  reason?: string | null
  triggeredBy?: string | null
  startTime?: string | null
  completionTime?: string | null
  /** 每个节点的状态与耗时快照（`节点=状态` 逗号分隔），归档时抓下来的 */
  stepsJson?: string | null
  /** 触发那一刻的画布 JSON —— 历史运行详情画**它**，不是画今天的流水线。
   *  ⚠️ 列表接口（run/list）**不带**这个字段（几十条记录不该每条都背着画布），详情走 run/get */
  canvasJson?: string | null
  /** 归档那一刻抓下来的 PipelineRun YAML（对象随后被删，这是「当年跑的是什么」的唯一存续形式）。
   *  同样只在 run/get 里带 */
  runYaml?: string | null
  createdAt?: string | null
}

/** 「这个集群能不能跑 CI」—— Tekton 与可动态供应的 StorageClass 缺一不可（spec §9）。 */
export interface PipelineCapability {
  available: boolean
  /** 不可用时**一定有**原因：不然用户只看到「功能没了」 */
  reason?: string | null
  /** 构建缓存的状态；null = 没问（没给命名空间）或没探到 —— 前端显示「—」，不当作"不可用" */
  cache?: PipelineCacheStatus | null
}

/** 构建缓存：每个命名空间一块长期卷，构建节点的依赖缓存挂在它上面（跑完不删）。 */
export interface PipelineCacheStatus {
  /** 现在挂得上吗 —— 决定下次运行会不会用上缓存 */
  ready: boolean
  /** 那块卷在不在。存在却没 ready = 建了却绑不上（存储不支持共享卷 RWX） */
  exists: boolean
  claimName: string
  capacity: string
  /** ready=false 时的一句人话 */
  reason?: string | null
  /** 集群前提那句话说清实际值，例如「Tekton coschedule = pipelineruns」 */
  prerequisite?: string | null
  /** 前提满足吗。不满足时 ready 一定为 false，但卷可能好好地在那儿 —— 两件事分开显示 */
  prerequisiteOk?: boolean
}

/** 一次运行里一个节点**现在**的落点（k8s-server 解析的 Tekton run → TaskRun → Pod）。 */
export interface PipelineStepRef {
  nodeName: string
  status: string
  podName?: string | null
  containers?: string[]
  /** Tekton 对这个节点失败的解释；没失败 / Tekton 没给就是 null */
  message?: string | null
}

/**
 * 一次运行的日志，**给"跟着看"用的形状**。
 *
 * ⚠️ `cursor` 是**服务端**的增量游标（k8s 的时间戳前缀与边界去重都在那边处理完了）——
 * 前端只管把 `text` 接上去、把 `cursor` 原样带回来，不要自己去解析时间戳。
 * `cursor === null`（或 `live === false`）表示"这一份是全量、没法增量"：整段替换并停止轮询。
 */
export interface PipelineRunLog {
  text: string
  cursor?: string | null
  live: boolean
}

/** 可在流水线里引用的连接（**窄投影**：只有身份与地址）。 */
export interface PipelineConnection {
  id: string
  name: string
  endpoint: string
}
