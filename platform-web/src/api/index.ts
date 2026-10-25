import http from './http'
import type {
  AuditLog,
  AuditQuery,
  BgpConfiguration,
  BgpFilter,
  BgpPeer,
  CalicoFormOption,
  CredentialCreateBody,
  CredentialTestResult,
  CredentialUpdateBody,
  CredentialView,
  K8sCluster,
  K8sClusterCapability,
  K8sClusterOption,
  K8sClusterOverview,
  K8sComponentFlag,
  K8sComponentOperation,
  K8sComponentStatus,
  K8sOtelInstall,
  K8sSeaweedInstall,
  K8sThanosInstall,
  TraceDetail,
  TraceSummary,
  K8sLogEntry,
  K8sLogQuery,
  K8sCalicoInstall,
  K8sNamespaceStatEntry,
  K8sTenantOverview,
  K8sConfigMap,
  K8sGateway,
  WaypointRef,
  K8sGatewayClass,
  K8sGrpcRoute,
  K8sHpa,
  Pipeline,
  PipelineCapability,
  PipelineConnection,
  PipelineNodeType,
  PipelineRun,
  PipelineRunLog,
  PipelineRetryPlan,
  PipelineRetryWorkspaces,
  PipelineStepRef,
  K8sHttpRoute,
  K8sNode,
  K8sPvc,
  K8sPersistentVolume,
  K8sPod,
  K8sPodMonitor,
  K8sLimitRange,
  K8sResourceQuota,
  K8sSecret,
  K8sService,
  K8sServiceMonitor,
  K8sPrometheusRule,
  PlatformAlertEvent,
  AlertQuery,
  AlertSelfTestResult,
  NotificationChannel,
  NotificationChannelOption,
  NotificationChannelRequest,
  K8sStorageClass,
  K8sTcpRoute,
  K8sTlsRoute,
  K8sUdpRoute,
  K8sIpool,
  K8sIpReservation,
  PoolIpamSummary,
  IpamBlockStat,
  IpamIpDetail,
  MeshStatus,
  MeshTopology,
  NamespaceAllocation,
  NamespaceView,
  NodeAddress,
  NodeDrainResult,
  NodeEvent,
  NodeTaint,
  PlatformPermission,
  PlatformRole,
  PlatformTenant,
  PlatformUser,
  RbacTemplate,
  RegistryBrowseResult,
  RegistryConnectionView,
  RegistrySuggestResponse,
  ResourceContext,
  SecretRefOption,
  TenantMemberView,
  TokenUserInfo,
  MiddlewareCatalogItem,
  ObjectStorageBucket,
  ObjectStorageBucketStat,
  ObjectStorageCredential,
  ObjectStorageCredentialSecret,
  ObjectStorageObjectPage,
  ObjectStorageObjectStat,
  ObjectStorageQuota,
  MiddlewareConnection,
  MiddlewareFormOptions,
  MiddlewareInstanceDetail,
  MiddlewareInstance,
  MiddlewareMetrics,
  MiddlewareOperation,
  MiddlewareOperatorStatus,
} from '@/types'
import type { MetricSeriesResponse } from '@/types/metrics'
import type { WorkloadDetail, WorkloadRevision } from '@/types/workload'

/** 集群管理 /cluster */
export const clusterApi = {
  list: () => http.post<never, K8sCluster[]>('/cluster/list'),
  /**
   * 集群下拉选项（窄投影，鉴权 platform:allocation:list）：只返回 clusterId/clusterName/enabled/ipStack。
   * 命名空间管理/编辑/概览等「只需要一个集群选择框」的页面用这个，不要调 list()
   * ——list() 属平台管理面（platform:cluster:manage），会让这些页面必然 403。
   */
  options: () => http.post<never, K8sClusterOption[]>('/cluster/options'),
  get: (clusterId: string) => http.post<never, K8sCluster>('/cluster/get', { clusterId }),
  create: (payload: {
    clusterName: string; kubeconfig: string; description?: string
    version?: string; istioVersion?: string; calicoVersion?: string; containerRuntime?: string
    ipStack?: K8sCluster['ipStack']; prometheusUrl?: string; grafanaUrl?: string; objectStorageUrl?: string
  }) => http.post<never, K8sCluster>('/cluster/create', payload),
  update: (payload: {
    clusterId: string; clusterName?: string; description?: string; kubeconfig?: string
    version?: string; istioVersion?: string; calicoVersion?: string; containerRuntime?: string
    ipStack?: K8sCluster['ipStack']; prometheusUrl?: string; grafanaUrl?: string; objectStorageUrl?: string
  }) => http.post<never, K8sCluster>('/cluster/update', payload),
  toggleEnabled: (clusterId: string, enabled: number) =>
    http.post<never, K8sCluster>('/cluster/toggleEnabled', { clusterId, enabled }),
  delete: (clusterId: string) => http.post<never, void>('/cluster/delete', { clusterId }),
  provision: (clusterId: string) => http.post<never, K8sCluster>('/cluster/provision', { clusterId }),
  refreshCapability: (clusterId: string) => http.post<never, void>('/cluster/capability/refresh', { clusterId }),
  getCapability: (clusterId: string) =>
    http.post<never, K8sClusterCapability>('/cluster/capability/get', { clusterId }),

  /** 集群组件清单（全量，平台侧；需 platform:cluster:manage） */
  components: (clusterId: string) =>
    http.post<never, K8sComponentStatus[]>('/cluster/components', { clusterId }),
  /**
   * 组件功能旗标（租户可读的窄投影：功能 code → 能不能用 + 原因）。
   * ⭐ 这是 CI / 服务网格 / 网络 / HPA 四处门控的**单一出处** —— 别再各自去读 capability 拼。
   */
  componentFlags: (clusterId: string) =>
    http.post<never, Record<string, K8sComponentFlag>>('/cluster/component-flags', { clusterId }),
  /** 重新探测（探完覆写缓存；写操作，需 platform:cluster:manage） */
  refreshComponents: (clusterId: string) =>
    http.post<never, void>('/cluster/components/refresh', { clusterId }),
  /** 安装/升级组件（异步，返回 operationId；需 platform:cluster:manage） */
  componentApply: (payload: {
    clusterId: string
    componentCode: string
    action: 'INSTALL' | 'UPGRADE'
    targetVersion?: string
    editableValues?: Record<string, unknown>
    /** 平台告警回调地址（仅 platformAlerting 的组件；clusterId 由后端拼） */
    alertWebhookUrl?: string
    /** Thanos Receive 地址（仅 thanosRemoteWrite 的组件；remoteWrite 目标） */
    thanosReceiveUrl?: string
    /** Thanos 安装参数（仅 thanosInstall 的组件；管理集群） */
    thanosInstall?: K8sThanosInstall
    /** Calico 网络参数（仅 calicoNetwork 的组件） */
    calicoInstall?: K8sCalicoInstall
    /** Tekton profile（仅 tektonProfile 的组件：lite / basic / all） */
    tektonProfile?: string
    /** OTel 采集栈参数（仅 otel-operator 组件） */
    otel?: K8sOtelInstall
    /** 对象存储参数（仅 seaweedStorage 的组件：StorageClass / 容量 / 副本数） */
    seaweed?: K8sSeaweedInstall
  }) => http.post<never, number>('/cluster/components/apply', payload),
  /** 查一条操作（轮询）：按 operationId，或按 (clusterId, componentCode) 取最近一次成功的 */
  componentOperation: (payload: {
    operationId?: number
    clusterId?: string
    componentCode?: string
  }) => http.post<never, K8sComponentOperation>('/cluster/components/operations/get', payload),
  /** 按集群列出各组件最近一次操作（含进行中；组件 tab 首屏一次拿全） */
  componentOperations: (clusterId: string) =>
    http.post<never, K8sComponentOperation[]>('/cluster/components/operations/list', { clusterId }),
  /** 中止进行中的操作：只释放平台侧并发锁（worker 已随重启消失 / helm 早已退出时用它解锁） */
  componentAbandon: (operationId: number) =>
    http.post<never, void>(`/cluster/components/operations/${operationId}/abandon`),

  /**
   * 集群概览快照（集群概览页首屏）：总量 + 节点健康/异常 Pod + 存储 + 能力摘要。
   * 聚合来源不可用时，聚合派生段为 null（前端显示「—」，不是 0）。
   */
  overview: (clusterId: string) =>
    http.post<never, K8sClusterOverview>('/cluster/overview', { clusterId }),

  /** 资源明细 per-namespace（「资源明细」tab 懒加载）；与 overview 同源同一份聚合快照。不可用返回 null。
   *  形状为命名空间切片（NamespaceStatEntry：clusterId + snapshot + quotaState），行数据在 snapshot.stat。 */
  resourceBreakdown: (clusterId: string) =>
    http.post<never, K8sNamespaceStatEntry[] | null>('/cluster/resource-breakdown', { clusterId }),
}

/**
 * 租户概览请求体（快照 / 明细 / 两条曲线共用，对应后端 TenantOverviewRequest）。
 * tenantId 为**生效租户** id：自管轨可不传（由 token 锁定，传别人的直接 TENANT_MISMATCH），代管轨（平台管理员）必须显式传。
 * clusterId 只对曲线有意义（可选，限定到单集群；缺省=租户全部集群 —— 快照/明细恒为租户全部集群）。
 * start/end 只对曲线有意义（unix 秒）。
 */
type TenantOverviewReq = {
  tenantId?: string
  clusterId?: string
  start?: number
  end?: number
}

/** 租户管理 /tenant（含命名空间分配：给租户分配命名空间） */
export const tenantApi = {
  list: () => http.post<never, PlatformTenant[]>('/tenant/list'),
  get: (id: string) => http.post<never, PlatformTenant>('/tenant/get', { id }),
  create: (payload: { name: string; serviceAccount: string; status?: number; ownerUserId?: string }) =>
    http.post<never, PlatformTenant>('/tenant/create', payload),
  update: (payload: { id: string; name?: string; status?: number }) =>
    http.post<never, PlatformTenant>('/tenant/update', payload),
  delete: (id: string) => http.post<never, void>('/tenant/delete', { id }),
  provision: (id: string) => http.post<never, PlatformTenant>('/tenant/provision', { id }),

  // ---- 命名空间分配 ----
  namespaceAllocate: (payload: {
    tenantId: string
    clusterId: string
    namespace: string
    roleTemplateId?: string
  }) => http.post<never, NamespaceAllocation>('/tenant/namespace/allocate', payload),
  namespaceList: (payload?: { tenantId?: string; clusterId?: string }) =>
    http.post<never, NamespaceAllocation[]>('/tenant/namespace/list', payload ?? {}),
  namespaceDeallocate: (payload: { tenantId: string; clusterId: string; namespace: string }) =>
    http.post<never, void>('/tenant/namespace/deallocate', payload),

  // ---- 租户成员/角色（自管/代管一致收口：自管须携带与 token 一致的 tenantId，代管必传；后端 TenantContextResolver 裁决） ----
  memberList: (tenantId: string) =>
    http.post<never, TenantMemberView[]>('/tenant/member/list', { tenantId }),
  memberAdd: (payload: { tenantId: string; userId: string }) =>
    http.post<never, void>('/tenant/member/add', payload),
  memberRemove: (payload: { tenantId: string; userId: string }) =>
    http.post<never, void>('/tenant/member/remove', payload),
  memberRoleGrant: (payload: { tenantId: string; userId: string; roleId: string }) =>
    http.post<never, void>('/tenant/member/role/grant', payload),
  memberRoleRevoke: (payload: { tenantId: string; userId: string; roleId: string }) =>
    http.post<never, void>('/tenant/member/role/revoke', payload),

  // ---- 租户概览（Phase 4；快照/明细与两条曲线同源=分配表范围）----
  /** 租户概览首屏快照：合计 + 逐集群小计 + 异常 Pod / 不健康工作负载名单 + 配额三档（逐集群降级） */
  overview: (body: TenantOverviewReq) =>
    http.post<never, K8sTenantOverview>('/tenant/overview', body),
  /** 资源明细（per-namespace，懒加载）：每行回带 clusterId + quotaState（判定在后端） */
  overviewBreakdown: (body: TenantOverviewReq) =>
    http.post<never, K8sNamespaceStatEntry[]>('/tenant/overview/breakdown', body),
  /** 租户 CPU 用量曲线（核）：按租户命名空间集合聚合的**一条**「租户合计」曲线；clusterId 传则限定单集群 */
  tenantMetricsCpu: (body: TenantOverviewReq) =>
    http.post<never, MetricSeriesResponse>('/tenant/overview/metrics/cpu', body),
  /** 租户内存用量曲线（字节）：同 cpu，单位为字节 */
  tenantMetricsMemory: (body: TenantOverviewReq) =>
    http.post<never, MetricSeriesResponse>('/tenant/overview/metrics/memory', body),
}

/** 命名空间管理 /namespace（K8s 命名空间视图 + 创建/编辑/删除 + 配额/限制范围） */

/**
 * 命名空间创建/编辑载荷。两个 istio 字段各自 **三态**：不传 = 不动该标签、空串 = 移除、有值 = 覆写
 * ——「不设标签」要发空串而不是省略字段，否则后端按"未传"处理、旧值会留在 ns 上。
 * 同理：capability 未探测到 Calico / Istio 时前端不传对应字段（防误清既有配置）。
 */
type NamespaceUpsertPayload = {
  clusterId: string
  name: string
  description?: string
  labels?: Record<string, string>
  ipv4Pools?: string[]
  ipv6Pools?: string[]
  /** istio.io/dataplane-mode：ambient / none / ''（移除） */
  dataplaneMode?: string
  /** istio.io/use-waypoint：waypoint 名 / none / ''（移除） */
  useWaypoint?: string
}

export const namespaceApi = {
  list: (clusterId: string) => http.post<never, NamespaceView[]>('/namespace/list', { clusterId }),
  delete: (payload: { clusterId: string; namespace: string }) =>
    http.post<never, void>('/namespace/delete', payload),
  /** 单个命名空间视图（含 description/labels/分配信息）；不存在返回 null */
  get: (clusterId: string, namespace: string) =>
    http.post<never, NamespaceView | null>('/namespace/get', { clusterId, namespace }),
  /** 命名空间快照（采样切片 + 配额三档状态 quotaState）：租户概览/命名空间详情页的读取面。
   *  刻意与 /namespace/get 分离（后者是分配页/编辑器的读取面）。授权 platform:allocation:list */
  statGet: (clusterId: string, namespace: string) =>
    http.post<never, K8sNamespaceStatEntry>('/namespace/stat/get', { clusterId, namespace }),
  /** 命名空间原始 YAML（只读展示） */
  yaml: (clusterId: string, namespace: string) =>
    http.post<never, string>('/namespace/yaml', { clusterId, namespace }),
  create: (payload: NamespaceUpsertPayload) =>
    http.post<never, void>('/namespace/create', payload),
  update: (payload: NamespaceUpsertPayload) =>
    http.post<never, void>('/namespace/update', payload),
  /** 配额：get 为 null = 未配置；upsert 幂等（对象名固定 default）；delete 缺失时 no-op */
  quotaGet: (clusterId: string, namespace: string) =>
    http.post<never, K8sResourceQuota | null>('/namespace/quota/get', { clusterId, namespace }),
  quotaUpsert: (clusterId: string, namespace: string, quota: K8sResourceQuota) =>
    http.post<never, void>('/namespace/quota/upsert', { clusterId, namespace, quota }),
  quotaDelete: (clusterId: string, namespace: string) =>
    http.post<never, void>('/namespace/quota/delete', { clusterId, namespace }),
  limitrangeGet: (clusterId: string, namespace: string) =>
    http.post<never, K8sLimitRange | null>('/namespace/limitrange/get', { clusterId, namespace }),
  limitrangeUpsert: (clusterId: string, namespace: string, limitRange: K8sLimitRange) =>
    http.post<never, void>('/namespace/limitrange/upsert', { clusterId, namespace, limitRange }),
  limitrangeDelete: (clusterId: string, namespace: string) =>
    http.post<never, void>('/namespace/limitrange/delete', { clusterId, namespace }),
}

/** RBAC 模板 /rbacTemplate */
export const templateApi = {
  list: () => http.post<never, RbacTemplate[]>('/rbacTemplate/list'),
  get: (id: string) => http.post<never, RbacTemplate>('/rbacTemplate/get', { id }),
  create: (payload: { name: string; description?: string; rules: RbacTemplate['rules'] }) =>
    http.post<never, RbacTemplate>('/rbacTemplate/create', payload),
  update: (payload: { id: string; description?: string; rules: RbacTemplate['rules'] }) =>
    http.post<never, RbacTemplate>('/rbacTemplate/update', payload),
  delete: (id: string) => http.post<never, void>('/rbacTemplate/delete', { id }),
  /**
   * 重下发全部模板到所有启用集群（DB 是唯一真相）：保存单个模板时也会同步，
   * 本动作用于「库里的 rules 被迁移/手工改过」后一次性推平 —— 例如迁移给租户 SA 补了
   * controllerrevisions 读权限，不重下发的话各集群已有的 tn-tpl-* ClusterRole 还是旧 rules。
   */
  syncAll: () => http.post<never, number>('/rbacTemplate/sync-all'),
}

/** 资源管理上下文（顶栏 chip：租户 → 集群 → 命名空间，DB 级联） */
export const resourceContextApi = {
  get: () => http.get<never, ResourceContext>('/context'),
}

/** 当前用户上下文 /user（/me 与 /my-tenants 为「登录即」端点：ExemptPaths 豁免权限点，仅需认证） */
export const userApi = {
  me: () => http.post<never, TokenUserInfo>('/user/me'),
  myTenants: () => http.post<never, PlatformTenant[]>('/user/my-tenants'),
}

/** 用户管理 /user（表驱动鉴权 platform:user:manage）。
 * ⚠️ 无「查某用户已有平台角色」端点（/user/me 只含自己）→ 授予/回收是盲操作，UI 不假装有当前态。 */
export const platformUserApi = {
  list: () => http.post<never, PlatformUser[]>('/user/list'),
  create: (payload: { username: string; password: string; displayName?: string; email?: string; status?: number }) =>
    http.post<never, PlatformUser>('/user/create', payload),
  get: (id: string) => http.post<never, PlatformUser>('/user/get', { id }),
  platformRoleGrant: (payload: { userId: string; roleId: string }) =>
    http.post<never, void>('/user/platformRole/grant', payload),
  platformRoleRevoke: (payload: { userId: string; roleId: string }) =>
    http.post<never, void>('/user/platformRole/revoke', payload),
  /** 用户已持平台角色回显（仅启用 + 未删除 + PLATFORM 族） */
  platformRoleList: (userId: string) =>
    http.post<never, PlatformRole[]>('/user/platformRole/list', { id: userId }),
}

/** 角色管理 /role（读 platform:role:read / 写 platform:role:manage；permission/save 全量重存，codes 非 ids） */
export const roleApi = {
  list: () => http.post<never, PlatformRole[]>('/role/list'),
  create: (payload: { name: string; code: string; description?: string; scope: string }) =>
    http.post<never, PlatformRole>('/role/create', payload),
  delete: (id: string) => http.post<never, void>('/role/delete', { id }),
  permissionSave: (payload: { roleId: string; permissionCodes: string[] }) =>
    http.post<never, void>('/role/permission/save', payload),
  permissionList: (roleId: string) => http.post<never, string[]>('/role/permission/list', { id: roleId }),
  /** 该角色**可分配**的权限点行。可分配范围是授权策略，判据在后端（RoleService.assignablePermissions）：
   *  TENANT 角色只有 tenant: 族，PLATFORM 角色两族都可 —— 前端不自行按 code 前缀过滤，否则会与后端漂移。 */
  permissionAssignable: (roleId: string) =>
    http.post<never, PlatformPermission[]>('/role/permission/assignable', { id: roleId }),
}

/** 权限点管理 /permission（list = platform:role:read；写操作 = platform:role:manage。
 * code 不唯一 —— 一行 = 一个 URL 规则，前端按 code 聚合；后端写前裸端点校验 + 提交后热加载即时生效） */
export const permissionApi = {
  list: () => http.post<never, PlatformPermission[]>('/permission/list'),
  create: (payload: { domain: string; resource: string; action: string; code: string; description?: string }) =>
    http.post<never, PlatformPermission>('/permission/create', payload),
  update: (payload: { id: string; domain: string; resource: string; action: string; code: string; description?: string }) =>
    http.post<never, void>('/permission/update', payload),
  delete: (id: string) => http.post<never, void>('/permission/delete', { id }),
  reload: () => http.post<never, void>('/permission/reload'),
}

/** 资源管理 - ConfigMap /configmaps（参考实现；list/create/update 上下文走 body，get/yaml/delete 走 query） */
export const configMapApi = {
  list: (ctx: { tenantId: string; clusterId: string; namespace: string; labelSelector?: string }) =>
    http.post<never, K8sConfigMap[]>('/configmaps/list', ctx),
  /** 跨全部命名空间（平台侧全局视图；需 platform:configmap:list-all） */
  listAll: (ctx: ListAllCtx) => http.post<never, K8sConfigMap[]>('/configmaps/list-all', ctx),
  get: (name: string, ctx: { tenantId: string; clusterId: string; namespace: string }) =>
    http.get<never, K8sConfigMap>(`/configmaps/${encodeURIComponent(name)}`, { params: ctx }),
  getYaml: (name: string, ctx: { tenantId: string; clusterId: string; namespace: string }) =>
    http.get<never, string>(`/configmaps/${encodeURIComponent(name)}/yaml`, { params: ctx }),
  create: (ctx: { tenantId: string; clusterId: string }, body: K8sConfigMap) =>
    http.post<never, K8sConfigMap>('/configmaps', { ...body, ...ctx }),
  update: (name: string, ctx: { tenantId: string; clusterId: string }, body: K8sConfigMap) =>
    http.put<never, K8sConfigMap>(`/configmaps/${encodeURIComponent(name)}`, { ...body, ...ctx }),
  delete: (name: string, ctx: { tenantId: string; clusterId: string; namespace: string }) =>
    http.delete<never, void>(`/configmaps/${encodeURIComponent(name)}`, { params: ctx }),
}

// ==================== 其余资源：与 ConfigMap 同构（list/get/yaml/create/update/delete） ====================

type Ctx3 = { tenantId: string; clusterId: string; namespace: string }
type Ctx2 = { tenantId: string; clusterId: string }
/** list 专用上下文：labelSelector 可选（K8s 原生选择器语法，原样透传；不传 = 全量） */
type ListCtx = Ctx3 & { labelSelector?: string }
/** 跨命名空间 list 上下文（平台侧 /list-all）：**不带 namespace** —— 返回项各自带自己的 namespace。
 *  授权码独立于 list（platform:xxx:list-all vs tenant:xxx:list），见 apiCodes.ts。 */
type ListAllCtx = Ctx2 & { labelSelector?: string }

/**标准 CRUD + yaml 透传工厂：base = /{base}（list/create/update 上下文走 body，get/yaml/delete 走 query） */
function makeResourceApi<T>(base: string) {
  return {
    list: (ctx: ListCtx) => http.post<never, T[]>(`/${base}/list`, ctx),
    get: (name: string, ctx: Ctx3) => http.get<never, T>(`/${base}/${encodeURIComponent(name)}`, { params: ctx }),
    getYaml: (name: string, ctx: Ctx3) => http.get<never, string>(`/${base}/${encodeURIComponent(name)}/yaml`, { params: ctx }),
    create: (ctx: Ctx2, body: T) => http.post<never, T>(`/${base}`, { ...body, ...ctx }),
    update: (name: string, ctx: Ctx2, body: T) => http.put<never, T>(`/${base}/${encodeURIComponent(name)}`, { ...body, ...ctx }),
    delete: (name: string, ctx: Ctx3) => http.delete<never, void>(`/${base}/${encodeURIComponent(name)}`, { params: ctx }),
  }
}

/** Secret /secrets */
export const secretApi = makeResourceApi<K8sSecret>('secrets')

/** Service /services */
export const serviceApi = makeResourceApi<K8sService>('services')

/**
 * PVC /pvcs。⚠️ `update` **只同步标签**；扩容是**独立端点**（与工作负载的改镜像 / 暂停同形）。
 */
export const pvcApi = {
  ...makeResourceApi<K8sPvc>('pvcs'),
  /**
   * 扩容。⚠️ 只能往大改、且存储类得开了 `allowVolumeExpansion` —— **两条判据都在后端**，
   * 而且它读的是**集群上那个对象**。前端拦一遍只是为体验，别把这当防线。
   *
   * 为什么和 `update` 分开：扩容动的是存储后端、异步、不可逆，跟"改个标签"不是一回事 ——
   * 合成一个入口，权限与审计都说不清。
   */
  resize: (name: string, ctx: Ctx2, storage: number) =>
    http.post<never, K8sPvc>(`/pvcs/${encodeURIComponent(name)}/resize`, { ...ctx, storage }),
}

/** StorageClass /storageclasses（集群级，只读：供下拉选择 storageClassName；无 namespace） */
export const storageClassApi = {
  list: (ctx: Ctx2) => http.post<never, K8sStorageClass[]>('/storageclasses/list', ctx),
  get: (name: string, ctx: Ctx2) => http.get<never, K8sStorageClass>(`/storageclasses/${encodeURIComponent(name)}`, { params: ctx }),
  getYaml: (name: string, ctx: Ctx2) => http.get<never, string>(`/storageclasses/${encodeURIComponent(name)}/yaml`, { params: ctx }),
}

/** PersistentVolume /persistentvolumes（集群级，只读：供 PVC 详情查看绑定的 PV；无 namespace） */
export const persistentVolumeApi = {
  list: (ctx: Ctx2) => http.post<never, K8sPersistentVolume[]>('/persistentvolumes/list', ctx),
  get: (name: string, ctx: Ctx2) => http.get<never, K8sPersistentVolume>(`/persistentvolumes/${encodeURIComponent(name)}`, { params: ctx }),
  getYaml: (name: string, ctx: Ctx2) => http.get<never, string>(`/persistentvolumes/${encodeURIComponent(name)}/yaml`, { params: ctx }),
}

/** 工作负载 /workloads（kind 在 body；get/delete/yaml 跨 kind 查找） */
export const workloadApi = {
  ...makeResourceApi<WorkloadDetail>('workloads'),
  /** 暂停/恢复 Deployment 更新（body.paused=true 暂停 / false 恢复） */
  pause: (name: string, ctx: Ctx3, paused: boolean) =>
    http.post<never, WorkloadDetail>(`/workloads/${encodeURIComponent(name)}/pause`, { ...ctx, name, paused }),
  /**
   * ambient 开关（B3 §11）：只改 pod template 的两个 istio 保留 label，其余字段取线上现值回写。
   * 两个字段各自独立三态：**不传 = 不动、空串 = 移除该 label、有值 = 覆写** ——
   * 列表页两个开关各发各的，不会互相踩。改 pod template → 触发滚动更新。
   */
  meshToggle: (name: string, ctx: Ctx3 & { kind?: string; dataplaneMode?: string; useWaypoint?: string }) =>
    http.post<never, WorkloadDetail>(`/workloads/${encodeURIComponent(name)}/mesh-toggle`, { ...ctx, name }),
  /**
   * OTel 可观测接入：只改 pod template 的注入注解与主容器 OTEL_SERVICE_NAME；
   * tracing/logs 都关 = 移除注入注解。改 pod template → 触发滚动更新。
   */
  observability: (name: string, ctx: Ctx3 & {
    kind?: string
    language: string
    tracing: boolean
    logs: boolean
    serviceName?: string
    containerName?: string
    logMode?: 'app' | 'file'
    /** 文件日志的具体文件（容器内绝对路径；支持 access.log* 这类 glob） */
    logFile?: string
  }) => http.post<never, WorkloadDetail>(
    `/workloads/${encodeURIComponent(name)}/observability`, { ...ctx, name }),
  /**
   * 改一个容器的镜像（列表页「镜像」列的行内编辑 = 直接发版）：只改该容器的 image，
   * 其余字段取线上现值回写（未建模字段不丢）→ 改的是 pod template，触发滚动更新。
   * 注意只收 name/containerName/image，不把 name 塞进 body（路径里已经有了）。
   */
  updateImage: (name: string, ctx: Ctx3 & { containerName: string; image: string }) =>
    http.post<never, WorkloadDetail>(`/workloads/${encodeURIComponent(name)}/image`, { ...ctx, containerName: ctx.containerName, image: ctx.image }),
  /** 历史版本列表（新→旧）：Deployment 取名下 ReplicaSet、STS/DS 取 ControllerRevision */
  revisions: (name: string, ctx: Ctx3) =>
    http.get<never, WorkloadRevision[]>(`/workloads/${encodeURIComponent(name)}/revisions`, { params: ctx }),
  /** 回滚到指定版本（取该版本的 pod template 覆盖到工作负载本身，与 kubectl rollout undo 同语义）→ 触发滚动更新 */
  rollback: (name: string, ctx: Ctx3 & { revision: number }) =>
    http.post<never, WorkloadDetail>(`/workloads/${encodeURIComponent(name)}/rollback`, { ...ctx, revision: ctx.revision }),
}

/** ServiceMonitor /servicemonitors（CRD，集群未装 Prometheus Operator 时透传错误） */
export const serviceMonitorApi = makeResourceApi<K8sServiceMonitor>('servicemonitors')

/** PrometheusRule /prometheusrules。运行状态（runtime）由后端在 list/get 时附着，前端不需要额外请求。 */
export const prometheusRuleApi = makeResourceApi<K8sPrometheusRule>('prometheusrules')

/**
 * 告警（平台库里的数据，不是 K8s 对象）。租户身份由后端按 token 自动收窄。
 * - `selfTest` 让平台给自己投一条测试告警，验证「HTTP → 放行 → 密钥 → 解析 → 落库」整条链路；
 * - `remove` 是清测试数据用的物理删（幂等）。
 */
export const alertApi = {
  list: (q: AlertQuery) => http.post<never, PlatformAlertEvent[]>('/alert/list', q),
  selfTest: (clusterId: string) =>
    http.post<never, AlertSelfTestResult>('/alert/self-test', null, { params: { clusterId } }),
  remove: (id: number) => http.delete<never, void>(`/alert/${encodeURIComponent(String(id))}`),
}

/**
 * 通知方式（管理员维护）。当前只支持邮件。
 * - `options` 是规则编辑页的候选（全局 + 本租户的启用渠道），用 `tenant:` 码，租户管理员也能调；
 * - `list` / 增删改是 `platform:` 码，只有管理员能调。
 */
export const notificationChannelApi = {
  list: () => http.post<never, NotificationChannel[]>('/notification-channels/list', {}),
  create: (body: NotificationChannelRequest) =>
    http.post<never, NotificationChannel>('/notification-channels', body),
  update: (code: string, body: NotificationChannelRequest) =>
    http.put<never, NotificationChannel>(`/notification-channels/${encodeURIComponent(code)}`, body),
  remove: (code: string) => http.delete<never, void>(`/notification-channels/${encodeURIComponent(code)}`),
  options: (tenantId?: string | null) =>
    http.post<never, NotificationChannelOption[]>('/notification-channels/options', { tenantId: tenantId ?? null }),
}

/** 本租户用户（规则编辑页的收件人候选）。email 可能为空 —— 界面上要提示「无邮箱」 */
export const notificationUserApi = {
  list: (tenantId?: string | null) =>
    http.post<never, { id: string; displayName: string; email: string | null }[]>(
      '/notification-users/list',
      { tenantId: tenantId ?? null },
    ),
}

/** ServiceMonitor Prometheus discovery 定位（对应后端 ServiceMonitorKeyRequest：clusterId + namespace + name） */
type SmDiscoveryCtx = { clusterId: string; namespace: string; name: string }

/** ServiceMonitor Relabeling/MetricRelabeling 的 sourceLabels 候选（编辑态；后端从集群 Prometheus discovery 拉取，不可达返回空）。
 * relabeling=服务发现原始标签(discoveredLabels)；metricRelabeling=最终目标标签(labels)，前端另补 __name__ */
export const serviceMonitorRelabelApi = {
  labels: (ctx: SmDiscoveryCtx) =>
    http.post<never, { relabeling: string[]; metricRelabeling: string[] }>('/servicemonitors/relabel-labels', ctx),
  /** MetricRelabeling regex 的 __name__（指标名）候选：scoped 到本 SM 活跃 target；无 target/不可达返回空 */
  metricNames: (ctx: SmDiscoveryCtx) =>
    http.post<never, string[]>('/servicemonitors/metric-names', ctx),
}

/** PodMonitor /podmonitors（CRD，集群未装 Prometheus Operator 时透传错误） */
export const podMonitorApi = makeResourceApi<K8sPodMonitor>('podmonitors')

/** PodMonitor Prometheus discovery 定位（对应后端 PodMonitorKeyRequest：clusterId + namespace + name） */
type PmDiscoveryCtx = { clusterId: string; namespace: string; name: string }

/** PodMonitor Relabeling/MetricRelabeling 的 sourceLabels 候选（编辑态；pool 前缀 podMonitor/{ns}/{name}/，不可达返回空） */
export const podMonitorRelabelApi = {
  labels: (ctx: PmDiscoveryCtx) =>
    http.post<never, { relabeling: string[]; metricRelabeling: string[] }>('/podmonitors/relabel-labels', ctx),
  /** MetricRelabeling regex 的 __name__ 候选：scoped 到本 PM 活跃 target；无 target/不可达返回空 */
  metricNames: (ctx: PmDiscoveryCtx) =>
    http.post<never, string[]>('/podmonitors/metric-names', ctx),
}

// ==================== 服务网格 Gateway API（B6）====================

/** 服务网格 /mesh（GatewayClass = 平台管理面；网关状态与引用候选单列） */
export const meshApi = {
  /** GatewayClass CRUD（集群级，平台管理面：platform:cluster:manage） */
  gatewayClass: {
    list: (ctx: { clusterId: string; labelSelector?: string }) =>
      http.post<never, K8sGatewayClass[]>('/mesh/gatewayclasses/list', ctx),
    get: (name: string, clusterId: string) =>
      http.get<never, K8sGatewayClass>(`/mesh/gatewayclasses/${encodeURIComponent(name)}`, { params: { clusterId } }),
    getYaml: (name: string, clusterId: string) =>
      http.get<never, string>(`/mesh/gatewayclasses/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
    create: (clusterId: string, body: K8sGatewayClass) =>
      http.post<never, K8sGatewayClass>('/mesh/gatewayclasses', body, { params: { clusterId } }),
    update: (name: string, clusterId: string, body: K8sGatewayClass) =>
      http.put<never, K8sGatewayClass>(`/mesh/gatewayclasses/${encodeURIComponent(name)}`, body, { params: { clusterId } }),
    delete: (name: string, clusterId: string) =>
      http.delete<never, void>(`/mesh/gatewayclasses/${encodeURIComponent(name)}`, { params: { clusterId } }),
  },
  /** GatewayClass 引用候选（窄投影 name/controllerName/description，任何登录用户可读）——供 Gateway 编辑器选 gatewayClassName */
  gatewayClassRefs: (clusterId: string) =>
    http.post<never, K8sGatewayClass[]>('/mesh/gatewayclass-refs', null, { params: { clusterId } }),
  /** 网格状态（hasIstio / istioAmbient / hasGatewayApi / versions）。失败上抛 → 调用方退化「未探测」 */
  status: (clusterId: string) =>
    http.post<never, MeshStatus>('/mesh/status', null, { params: { clusterId } }),
  /** 命名空间内的 waypoint 引用候选（名字 + 处理哪类流量；平台侧读）——供命名空间编辑器的
   *  istio.io/use-waypoint 下拉。命名空间是平台侧资源、Gateway 是租户域资源，平台管理员没有租户上下文，
   *  故不能走 gatewayApi.list。必须带 waypoint-for：命名空间级只能指向能处理服务流量的（service/all）。 */
  namespaceWaypoints: (clusterId: string, namespace: string) =>
    http.post<never, WaypointRef[]>('/mesh/gateways', null, { params: { clusterId, namespace } }),
  /** waypoint 链路追踪：读 */
  waypointTracing: (ctx: { tenantId: string; clusterId: string; namespace: string; gatewayName: string }) =>
    http.get<never, boolean>('/mesh/waypoints/tracing', { params: ctx }),
  /** waypoint 链路追踪：开/关（下发/删除指向该 Gateway 的 Telemetry） */
  setWaypointTracing: (ctx: { tenantId: string; clusterId: string; namespace: string; gatewayName: string; enabled: boolean }) =>
    http.post<never, boolean>('/mesh/waypoints/tracing', ctx),
  /** 服务网格拓扑（节点=工作负载，边=谁调谁）。
   *  ⚠️ meshMetricsAvailable=false 表示这个集群没在采集网格指标，不是"没有流量"。 */
  topology: (clusterId: string, namespace?: string, windowSeconds?: number) =>
    http.post<never, MeshTopology>('/mesh/topology', { clusterId, namespace, windowSeconds }),
}

/** 链路追踪 /traces（平台自研视图；数据源 = 管理集群的 Tempo，仅平台管理员） */
export const traceApi = {
  search: (body: {
    /** 平台管理员用它按顶栏/筛选器的租户收窄；租户用户由后端强制自己的租户 */
    tenantId?: string
    clusterName?: string
    namespace?: string
    serviceName?: string
    start?: number
    end?: number
    limit?: number
    /** 只看包含 error 状态 span 的 trace */
    errorsOnly?: boolean
    /** 只看耗时超过该毫秒数的 trace */
    minDurationMs?: number
  }) => http.post<never, TraceSummary[]>('/traces/search', body),
  /** 某集群下出现过的服务名（Tempo 标签值；页面「服务」下拉的候选） */
  services: (params: {
    tenantId?: string
    clusterName?: string
    namespace?: string
    start?: number
    end?: number
  }) => http.get<never, string[]>('/traces/services', { params }),
  get: (traceId: string) =>
    http.get<never, TraceDetail>(`/traces/${encodeURIComponent(traceId)}`),
}

/**
 * 日志查询中心 /logs（数据源 = collector 写日志的 ES）。
 * 租户用户由后端按分配表收窄命名空间，前端只负责把筛选条件传过去。
 */
export const logApi = {
  search: (body: K8sLogQuery) => http.post<never, K8sLogEntry[]>('/logs/search', body),
}

/** Gateway /gateways（CRD，集群未装 Gateway API 时透传 404；租户域） */
export const gatewayApi = makeResourceApi<K8sGateway>('gateways')

/** HTTPRoute /httproutes（CRD，集群未装 Gateway API 时透传 404；租户域） */
export const httpRouteApi = makeResourceApi<K8sHttpRoute>('httproutes')

/** GRPCRoute /grpcroutes（CRD；GRPCRoute 自 Gateway API v1.1 GA，后端单挂 v1，租户域） */
export const grpcRouteApi = makeResourceApi<K8sGrpcRoute>('grpcroutes')

/** TCPRoute /tcproutes（CRD；CRD 版本 v1/v1alpha2 由后端按集群 capability 分派，租户域） */
export const tcpRouteApi = makeResourceApi<K8sTcpRoute>('tcproutes')

/** TLSRoute /tlsroutes（CRD；比 TCP/UDP 多 hostnames = SNI 匹配；版本分派同 TCP，租户域） */
export const tlsRouteApi = makeResourceApi<K8sTlsRoute>('tlsroutes')

/** UDPRoute /udproutes（CRD；与 TCPRoute 同构；版本分派同 TCP，租户域） */
export const udpRouteApi = makeResourceApi<K8sUdpRoute>('udproutes')

/** HPA /hpas（发散资源：autoscaling v1/v2 由后端按集群 capability 分派） */
export const hpaApi = makeResourceApi<K8sHpa>('hpas')

/** Pod /pods（只读 + 删除：由工作负载控制器管理，无 create/update） */
export const podApi = {
  list: (ctx: ListCtx) => http.post<never, K8sPod[]>('/pods/list', ctx),
  /** 跨全部命名空间（平台侧全局视图；需 platform:pod:list-all） */
  listAll: (ctx: ListAllCtx) => http.post<never, K8sPod[]>('/pods/list-all', ctx),
  get: (name: string, ctx: Ctx3) => http.get<never, K8sPod>(`/pods/${encodeURIComponent(name)}`, { params: ctx }),
  getYaml: (name: string, ctx: Ctx3) => http.get<never, string>(`/pods/${encodeURIComponent(name)}/yaml`, { params: ctx }),
  delete: (name: string, ctx: Ctx3) => http.delete<never, void>(`/pods/${encodeURIComponent(name)}`, { params: ctx }),
}

/** Node /nodes（集群级，admin；无 create/delete，节点专属动作走独立端点） */
export const nodeApi = {
  list: (ctx: { clusterId: string; labelSelector?: string }) =>
    http.post<never, K8sNode[]>('/nodes/list', ctx),
  /**
   * 节点地址窄投影（NodePort 直连用）：只回 name + 内网/外网 IP + Ready，
   * 不含容量/标签/污点。授权码 tenant:cluster:capability:view（租户可读的集群只读快照），
   * 与 /cluster/capability/get 同码 —— 能在工作负载列表页点 nodePort 的人就能拿这份数据。
   */
  addresses: (clusterId: string) =>
    http.get<never, NodeAddress[]>('/nodes/addresses', { params: { clusterId } }),
  get: (name: string, clusterId: string) =>
    http.get<never, K8sNode>(`/nodes/${encodeURIComponent(name)}`, { params: { clusterId } }),
  getYaml: (name: string, clusterId: string) =>
    http.get<never, string>(`/nodes/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
  cordon: (clusterId: string, name: string) =>
    http.post<never, K8sNode>('/nodes/cordon', { clusterId, name }),
  uncordon: (clusterId: string, name: string) =>
    http.post<never, K8sNode>('/nodes/uncordon', { clusterId, name }),
  updateLabelsTaints: (name: string, clusterId: string, body: { labels: Record<string, string>; taints: NodeTaint[] }) =>
    http.put<never, K8sNode>(`/nodes/${encodeURIComponent(name)}`, body, { params: { clusterId } }),
  drain: (payload: { clusterId: string; name: string; force?: boolean; deleteEmptyDir?: boolean }) =>
    http.post<never, NodeDrainResult>('/nodes/drain', payload),
  pods: (name: string, clusterId: string) =>
    http.get<never, K8sPod[]>(`/nodes/${encodeURIComponent(name)}/pods`, { params: { clusterId } }),
  /** 节点上某 Pod 的 YAML（只读；集群域 admin，无需 tenantId） */
  podYaml: (nodeName: string, clusterId: string, namespace: string, podName: string) =>
    http.get<never, string>(
      `/nodes/${encodeURIComponent(nodeName)}/pods/${encodeURIComponent(namespace)}/${encodeURIComponent(podName)}/yaml`,
      { params: { clusterId } },
    ),
  events: (name: string, clusterId: string) =>
    http.get<never, NodeEvent[]>(`/nodes/${encodeURIComponent(name)}/events`, { params: { clusterId } }),
}

// ==================== 网络 Calico（集群级，admin；/calico/**）====================

/** Calico IPPool /calico/ippool（list 走 body；get/yaml/create/update/delete 的 clusterId 走 query）。
 * 集群级 CRD：无租户/命名空间维度，只有集群选择。 */
export const calicoApi = {
  ippool: {
    list: (ctx: { clusterId: string; labelSelector?: string }) =>
      http.post<never, K8sIpool[]>('/calico/ippool/list', ctx),
    get: (name: string, clusterId: string) =>
      http.get<never, K8sIpool>(`/calico/ippool/${encodeURIComponent(name)}`, { params: { clusterId } }),
    getYaml: (name: string, clusterId: string) =>
      http.get<never, string>(`/calico/ippool/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
    create: (clusterId: string, body: K8sIpool) =>
      http.post<never, K8sIpool>('/calico/ippool', body, { params: { clusterId } }),
    update: (name: string, clusterId: string, body: K8sIpool) =>
      http.put<never, K8sIpool>(`/calico/ippool/${encodeURIComponent(name)}`, body, { params: { clusterId } }),
    delete: (name: string, clusterId: string) =>
      http.delete<never, void>(`/calico/ippool/${encodeURIComponent(name)}`, { params: { clusterId } }),
  },
  ipreservation: {
    list: (ctx: { clusterId: string; labelSelector?: string }) =>
      http.post<never, K8sIpReservation[]>('/calico/ipreservation/list', ctx),
    get: (name: string, clusterId: string) =>
      http.get<never, K8sIpReservation>(`/calico/ipreservation/${encodeURIComponent(name)}`, { params: { clusterId } }),
    getYaml: (name: string, clusterId: string) =>
      http.get<never, string>(`/calico/ipreservation/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
    create: (clusterId: string, body: K8sIpReservation) =>
      http.post<never, K8sIpReservation>('/calico/ipreservation', body, { params: { clusterId } }),
    update: (name: string, clusterId: string, body: K8sIpReservation) =>
      http.put<never, K8sIpReservation>(`/calico/ipreservation/${encodeURIComponent(name)}`, body, { params: { clusterId } }),
    delete: (name: string, clusterId: string) =>
      http.delete<never, void>(`/calico/ipreservation/${encodeURIComponent(name)}`, { params: { clusterId } }),
  },
  // ---- BGP*（CRUD；Configuration 写仅平台管理员——后端 code platform:bgp:config:manage 收敛）----
  bgpConfiguration: {
    list: (ctx: { clusterId: string; labelSelector?: string }) =>
      http.post<never, BgpConfiguration[]>('/calico/bgpconfiguration/list', ctx),
    get: (name: string, clusterId: string) =>
      http.get<never, BgpConfiguration>(`/calico/bgpconfiguration/${encodeURIComponent(name)}`, { params: { clusterId } }),
    getYaml: (name: string, clusterId: string) =>
      http.get<never, string>(`/calico/bgpconfiguration/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
    create: (clusterId: string, body: BgpConfiguration) =>
      http.post<never, BgpConfiguration>('/calico/bgpconfiguration', body, { params: { clusterId } }),
    update: (name: string, clusterId: string, body: BgpConfiguration) =>
      http.put<never, BgpConfiguration>(`/calico/bgpconfiguration/${encodeURIComponent(name)}`, body, { params: { clusterId } }),
    delete: (name: string, clusterId: string) =>
      http.delete<never, void>(`/calico/bgpconfiguration/${encodeURIComponent(name)}`, { params: { clusterId } }),
  },
  bgpPeer: {
    list: (ctx: { clusterId: string; labelSelector?: string }) =>
      http.post<never, BgpPeer[]>('/calico/bgppeer/list', ctx),
    get: (name: string, clusterId: string) =>
      http.get<never, BgpPeer>(`/calico/bgppeer/${encodeURIComponent(name)}`, { params: { clusterId } }),
    getYaml: (name: string, clusterId: string) =>
      http.get<never, string>(`/calico/bgppeer/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
    create: (clusterId: string, body: BgpPeer) =>
      http.post<never, BgpPeer>('/calico/bgppeer', body, { params: { clusterId } }),
    update: (name: string, clusterId: string, body: BgpPeer) =>
      http.put<never, BgpPeer>(`/calico/bgppeer/${encodeURIComponent(name)}`, body, { params: { clusterId } }),
    delete: (name: string, clusterId: string) =>
      http.delete<never, void>(`/calico/bgppeer/${encodeURIComponent(name)}`, { params: { clusterId } }),
  },
  bgpFilter: {
    list: (ctx: { clusterId: string; labelSelector?: string }) =>
      http.post<never, BgpFilter[]>('/calico/bgpfilter/list', ctx),
    get: (name: string, clusterId: string) =>
      http.get<never, BgpFilter>(`/calico/bgpfilter/${encodeURIComponent(name)}`, { params: { clusterId } }),
    getYaml: (name: string, clusterId: string) =>
      http.get<never, string>(`/calico/bgpfilter/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
    create: (clusterId: string, body: BgpFilter) =>
      http.post<never, BgpFilter>('/calico/bgpfilter', body, { params: { clusterId } }),
    update: (name: string, clusterId: string, body: BgpFilter) =>
      http.put<never, BgpFilter>(`/calico/bgpfilter/${encodeURIComponent(name)}`, body, { params: { clusterId } }),
    delete: (name: string, clusterId: string) =>
      http.delete<never, void>(`/calico/bgpfilter/${encodeURIComponent(name)}`, { params: { clusterId } }),
  },
  /** BGP 编辑器下拉候选（命名空间 / 工作负载；只读，失败后端降级空候选） */
  formOptions: (clusterId: string) =>
    http.post<never, CalicoFormOption>('/calico/form-options', null, { params: { clusterId } }),
  /** 某命名空间下的 Secret 引用候选（级联第二级；name + data keys） */
  secretOptions: (clusterId: string, namespace: string) =>
    http.get<never, SecretRefOption[]>('/calico/form-options/secrets', { params: { clusterId, namespace } }),
  ipam: {
    /** 池 IPAM 汇总（含 allocated，供删除守卫） */
    summary: (clusterId: string, poolName: string) =>
      http.get<never, PoolIpamSummary>('/calico/ipam/summary', { params: { clusterId, poolName } }),
    /** 已物化块表（可选按池 / 关键字过滤） */
    blocks: (clusterId: string, poolName?: string, search?: string) =>
      http.get<never, IpamBlockStat[]>('/calico/ipam/blocks', { params: { clusterId, poolName, search } }),
    /** 点查某 IP/块当前是否空闲（jump-to 定位） */
    isFree: (clusterId: string, cidrOrIp: string) =>
      http.get<never, boolean>('/calico/ipam/is-free', { params: { clusterId, cidrOrIp } }),
    /** 下一批空闲块 CIDR（分页；offset/limit） */
    nextFreeBlocks: (clusterId: string, poolName: string, offset = 0, limit = 20) =>
      http.get<never, string[]>('/calico/ipam/next-free-blocks', { params: { clusterId, poolName, offset, limit } }),
    /** 单块 per-IP（未物化合成全 free） */
    blockIps: (clusterId: string, cidr: string) =>
      http.get<never, IpamIpDetail[]>('/calico/ipam/block-ips', { params: { clusterId, cidr } }),
  },
}

/**
 * 审计日志（S0.1，平台管理面）。
 * list 用游标分页：审计表持续追加，offset 翻页会漏读/重读。
 * purge 闭开区间 [from, to)，返回实际删除行数。
 */
export const auditApi = {
  list: (q: AuditQuery) => http.post<never, AuditLog[]>('/audit/list', q),
  purge: (from: string, to: string) => http.post<never, number>('/audit/purge', { from, to }),
  /** 动作码候选值 = AuditAction 常量（动作码）∪ API 域权限码；取全集而非审计表观测值 */
  actions: () => http.post<never, string[]>('/audit/actions'),
}

export const credentialApi = {
  list: () => http.post<never, CredentialView[]>('/credential/list'),
  /** GIT 类型必须给 probeUrl：凭据是按 host 配的，能不能用取决于具体仓库的权限 */
  test: (id: string, probeUrl?: string) =>
    http.post<never, CredentialTestResult>('/credential/test', { id, probeUrl }),
  create: (body: CredentialCreateBody) => http.post<never, CredentialView>('/credential/create', body),
  update: (body: CredentialUpdateBody) => http.post<never, CredentialView>('/credential/update', body),
  remove: (id: string) => http.post<never, void>('/credential/delete', { id }),
}

export const registryApi = {
  /** 窄投影，浏览页的连接下拉 */
  connections: () => http.post<never, RegistryConnectionView[]>('/registry/connections'),
  /** path 为空 = 第一层 */
  browse: (credentialId: string, path: string[]) =>
    http.post<never, RegistryBrowseResult>('/registry/browse', { credentialId, path }),
  /**
   * 入参是输入框里的整串；返回**要填进去的完整字符串**，前端整段替换。
   *
   * ⚠️ `silent403` 不能省：这是**每按键一次**的高频调用，而 axios 拦截器默认会把任何业务错弹成红条
   * （`http.ts` 的三条错误路径都受这个 flag 管）。不置位的话，后端一有问题就是「每打一个字一条红条」，
   * 正好把 spec §5.3 要的「补全失败静默降级」打穿。
   */
  suggest: (imagePrefix: string) =>
    http.post<never, RegistrySuggestResponse>('/registry/suggest', { imagePrefix }, { silent403: true }),
}


export const pipelineApi = {
  list: (clusterId: string, namespace: string) =>
    http.post<never, Pipeline[]>('/pipeline/list', { clusterId, namespace }),
  get: (id: string) => http.post<never, Pipeline>('/pipeline/get', { id }),
  create: (body: Pipeline) => http.post<never, Pipeline>('/pipeline/create', body),
  update: (body: Pipeline) => http.post<never, Pipeline>('/pipeline/update', body),
  remove: (id: string) => http.post<never, void>('/pipeline/delete', { id }),

  /** 内置节点的表单 schema 与语言预设 —— **属性面板完全由它渲染**（透传自 k8s-server） */
  nodeTypes: () => http.post<never, PipelineNodeType[]>('/pipeline/node-types'),
  /** 画布 → Tekton YAML。**保存时也走它做画布校验**：带环 / 未知节点类型当场被拒 */
  preview: (canvasJson: string) =>
    http.post<never, string>('/pipeline/preview', { canvasJson }),
  /** 可在流水线里引用的 git 连接（窄投影） */
  gitConnections: () => http.post<never, PipelineConnection[]>('/pipeline/git-connections'),
  /** 同上，但面向**镜像节点**（排除 git）—— 少了它，镜像节点只能手打连接 id */
  registryConnections: () => http.post<never, PipelineConnection[]>('/pipeline/registry-connections'),

  /**
   * ⚠️ 下面几个端点里有**一半要走到 k8s-server**（触发 / 取消 / 节点落点 / 日志 / YAML），
   * 而 k8s-server 对**平台管理员**要求显式指定租户（"管理员代操作需显式指定 tenantId"）——
   * 所以它们都要带**生效租户**。租户用户不用给（token 说了算，传别人的反而会被判 TENANT_MISMATCH）；
   * 平台管理员必须给：取顶栏级联里选的那个租户（`state.tenantId`）。
   */
  run: (pipelineId: string, tenantId?: string, maskedNodeIds?: string[]) =>
    http.post<never, string>('/pipeline/run', { id: pipelineId, tenantId, maskedNodeIds }),
  /** 重试计划（只读）：前端据此决定哪些节点有「重试」按钮 */
  retryPlan: (runName: string, tenantId?: string) =>
    http.post<never, PipelineRetryPlan>('/pipeline/run/retry/plan', { runName, tenantId }),
  /** 从断点继续；nodeId 必须是计划里的可重试节点 */
  retryRun: (runName: string, nodeId: string, tenantId?: string) =>
    http.post<never, string>('/pipeline/run/retry', { runName, nodeId, tenantId }),
  /** 待重试工作区（失败运行留下的卷） */
  retryWorkspaces: (clusterId: string, namespace: string, tenantId?: string) =>
    http.post<never, PipelineRetryWorkspaces>('/pipeline/retry-workspaces',
      { clusterId, namespace, tenantId }),
  /** 清理待重试工作区 —— **不可恢复**：之后那些运行不能再从断点继续 */
  clearRetryWorkspaces: (clusterId: string, namespace: string, tenantId?: string) =>
    http.post<never, void>('/pipeline/retry-workspaces/clear',
      { clusterId, namespace, tenantId }),
  cancelRun: (runName: string, tenantId?: string) =>
    http.post<never, void>('/pipeline/run/cancel', { name: runName, tenantId }),
  /**
   * 删掉一条运行记录。⚠️ **不可恢复** —— 归档时集群对象就删了，库里的行是日志与 YAML 的唯一留存。
   * 还在跑的那条会被后端拒（先取消）。确认框留在调用方。
   */
  runDelete: (runName: string) =>
    http.post<never, void>('/pipeline/run/delete', { name: runName }),
  runs: (pipelineId: string) => http.post<never, PipelineRun[]>('/pipeline/run/list', { id: pipelineId }),
  /** 详情（含**触发那一刻的画布**与**归档时抓的 YAML**）—— 列表行不带这两个大字段 */
  runGet: (runName: string) => http.post<never, PipelineRun>('/pipeline/run/get', { name: runName }),
  /** 每个节点**现在**的落点（运行详情的 DAG 着色）。⚠️ 只有还在跑才有：归档后返回空 */
  runSteps: (runName: string, tenantId?: string) =>
    http.post<never, PipelineStepRef[]>('/pipeline/run/steps', { name: runName, tenantId }),
  /** 一次运行渲染出来的 YAML（还在跑抓集群对象；归档后读归档时抓下来的那一份） */
  runYaml: (runName: string, tenantId?: string) =>
    http.post<never, string | null>('/pipeline/run/yaml', { name: runName, tenantId }),
  /**
   * 已归档读库、还在跑现抓；nodeName 为空则把所有节点拼起来。
   *
   * ⭐ 还在跑时带上上一批回的 `cursor` 就是**增量**：只回新行，游标与时间戳前缀都在服务端处理。
   */
  runLogs: (runName: string, nodeName?: string, tenantId?: string, sinceTime?: string) =>
    http.post<never, PipelineRunLog>('/pipeline/run/logs', { runName, nodeName, tenantId, sinceTime }),

  /** 这个集群能不能跑 CI —— **页面级门控的唯一判据**（不可用时带原因，前端据此提示并禁用「运行」） */
  capability: (clusterId: string, tenantId?: string, namespace?: string) =>
    http.post<never, PipelineCapability>('/pipeline/capability', { clusterId, tenantId, namespace }),

  /**
   * 清掉这个命名空间的**构建缓存**（删那块长期卷，下次运行自动重建）。
   *
   * ⚠️ 删的是卷而不是里面的文件：有运行正在用它时删不掉（后端会抛，不是假装成功）。
   */
  clearCache: (clusterId: string, namespace: string, tenantId?: string) =>
    http.post<never, void>('/pipeline/cache/clear', { clusterId, namespace, tenantId }),
}

/** 对象存储（SeaweedFS 组件：桶 / 凭据 / 对象读写） */
export const objectStorageApi = {
  listBuckets: (payload: { clusterId: string; tenantId?: string }) =>
    http.post<never, ObjectStorageBucket[]>('/object-storage/buckets/list', payload),
  createBucket: (payload: {
    clusterId: string; tenantId: string; name: string; displayName?: string
    /** 容量上限（字节）；不传 = 不限 */
    quotaBytes?: number
  }) =>
    http.post<never, ObjectStorageBucket>('/object-storage/buckets/create', payload),
  deleteBucket: (payload: { clusterId: string; tenantId?: string; bucketId: string }) =>
    http.post<never, void>('/object-storage/buckets/delete', payload),
  /** 修改桶的容量上限（null = 不限；仅管理员） */
  setBucketQuota: (payload: { bucketId: string; tenantId?: string; quotaBytes: number | null }) =>
    http.post<never, void>('/object-storage/buckets/quota', payload),
  /** 开/关桶的匿名读（仅管理员；只给读，不给列目录） */
  setAnonymousRead: (payload: { bucketId: string; tenantId?: string; anonymousRead: boolean }) =>
    http.post<never, void>('/object-storage/buckets/anonymous', payload),
  quota: (payload: { clusterId: string; tenantId?: string }) =>
    http.post<never, ObjectStorageQuota>('/object-storage/quota/get', payload),
  setQuota: (payload: { clusterId: string; tenantId: string; maxBuckets: number }) =>
    http.post<never, void>('/object-storage/quota/set', payload),
  credentials: (payload: { clusterId: string; tenantId?: string; bucketId: string }) =>
    http.post<never, ObjectStorageCredential[]>('/object-storage/credentials/list', payload),
  issueCredential: (payload: { clusterId: string; tenantId?: string; bucketId: string }) =>
    http.post<never, ObjectStorageCredentialSecret>('/object-storage/credentials/issue', payload),
  viewCredential: (payload: { clusterId: string; tenantId?: string; credentialId: string }) =>
    http.post<never, ObjectStorageCredentialSecret>('/object-storage/credentials/get', payload),
  revokeCredential: (payload: { clusterId: string; tenantId?: string; credentialId: string }) =>
    http.post<never, void>('/object-storage/credentials/revoke', payload),
  objects: (payload: {
    clusterId: string; tenantId?: string; bucketId: string
    prefix?: string; after?: string; maxKeys?: number
    /** "/" = 按目录下钻（返回 folders）；不传 = 扁平递归 */
    delimiter?: string
  }) => http.post<never, ObjectStorageObjectPage>('/object-storage/objects/list', payload),
  statObject: (payload: { clusterId: string; tenantId?: string; bucketId: string; key: string }) =>
    http.post<never, ObjectStorageObjectStat>('/object-storage/objects/stat', payload),
  /**
   * 上传单个对象（≤100MB）。请求体是文件的原始字节（不是 multipart），
   * size 必须给：平台在读流之前据此拒超限，下游也要用它做对象长度。
   */
  uploadObject: (
    params: { bucketId: string; key: string; size: number; contentType?: string; tenantId?: string },
    file: Blob,
    onProgress?: (percent: number) => void,
  ) => http.post<never, void>('/object-storage/objects/upload', file, {
    params,
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
    onUploadProgress: (e) => onProgress?.(e.total ? Math.round((e.loaded / e.total) * 100) : 0),
  }),
  /** 批量查桶的当前用量（后端列举求和，有上限） */
  bucketStats: (payload: { clusterId: string; tenantId?: string; buckets: string[] }) =>
    http.post<never, ObjectStorageBucketStat[]>('/object-storage/buckets/stats', payload),
  /** 删除单个对象（不做按前缀批量删） */
  deleteObject: (payload: { bucketId: string; key: string; tenantId?: string }) =>
    http.post<never, void>('/object-storage/objects/delete', payload),
  /** 下载对象（≤100MB）：返回二进制流，调用方自己 blob 化 */
  downloadObject: (payload: { clusterId: string; tenantId?: string; bucketId: string; key: string }) =>
    http.post<never, Blob>('/object-storage/objects/download', payload, { responseType: 'blob' }),
}

/** 中间件商店（固定清单 + Operator 实例） */
export const middlewareApi = {
  catalog: (clusterId: string) =>
    http.post<never, MiddlewareCatalogItem[]>('/middleware/catalog', { clusterId }),
  /** 实例的连接方式（集群内地址 + 凭据位置），创建成功后弹给用户 */
  connection: (instanceId: string, tenantId?: string) =>
    http.post<never, MiddlewareConnection>('/middleware/instance/connection', { instanceId, tenantId }),
  /** 创建表单的可选项（本租户命名空间 / 集群 StorageClass），由后端按租户收窄 */
  formOptions: (clusterId: string, tenantId?: string) =>
    http.post<never, MiddlewareFormOptions>('/middleware/form-options', { clusterId, tenantId }),
  instanceList: (clusterId: string, code?: string, tenantId?: string) =>
    http.post<never, MiddlewareInstance[]>('/middleware/instance/list', { clusterId, code, tenantId }),
  instanceGet: (instanceId: string, tenantId?: string) =>
    http.post<never, MiddlewareInstance>('/middleware/instance/get', { instanceId, tenantId }),
  /** 详情页聚合：台账 + 规格 + 连接方式 + 集群实时对象 */
  instanceDetail: (instanceId: string, tenantId?: string) =>
    http.post<never, MiddlewareInstanceDetail>('/middleware/instance/detail', { instanceId, tenantId }),
  /** 概览的监控曲线：CPU/内存/网络/磁盘一次返回（start/end 为 unix 秒） */
  instanceMetrics: (instanceId: string, start: number, end: number, tenantId?: string) =>
    http.post<never, MiddlewareMetrics>('/middleware/instance/metrics', { instanceId, start, end, tenantId }),
  instanceOperations: (instanceId: string, tenantId?: string) =>
    http.post<never, MiddlewareOperation[]>('/middleware/instance/operations', { instanceId, tenantId }),
  rename: (payload: { instanceId: string; displayName: string; tenantId?: string }) =>
    http.post<never, MiddlewareInstance>('/middleware/instance/rename', payload),
  create: (payload: {
    clusterId: string
    code: string
    mode: string
    namespace: string
    displayName?: string
    version?: string
    replicas?: number
    /** Redis 集群形态：每主分片从节点数 */
    replicasPerShard?: number
    cpuRequest?: string
    cpuLimit?: string
    memoryRequest?: string
    memoryLimit?: string
    storageSize?: string
    storageClass?: string
    kibana?: boolean
    /** Elasticsearch：开启 HTTPS */
    tls?: boolean
    /** Elasticsearch：安装 IK 分词器 */
    ik?: boolean
    /** 采集业务指标（默认关） */
    metricsEnabled?: boolean
    tenantId?: string
  }) => http.post<never, number>('/middleware/instance/create', payload),
  scale: (payload: {
    instanceId: string
    replicas?: number
    cpuRequest?: string
    cpuLimit?: string
    memoryRequest?: string
    memoryLimit?: string
    storageSize?: string
    /** Elasticsearch：安装 IK（单向，不在编辑里卸载） */
    ik?: boolean
    /** 业务指标：单向开启（装 exporter / 建采集资源） */
    metricsEnabled?: boolean
    tenantId?: string
  }) => http.post<never, number>('/middleware/instance/scale', payload),
  remove: (payload: { instanceId: string; keepPvc: boolean; tenantId?: string }) =>
    http.post<never, number>('/middleware/instance/delete', payload),
  operation: (operationId: number) =>
    http.post<never, MiddlewareOperation>('/middleware/operation/get', { operationId }),
  operatorInstall: (clusterId: string, code: string) =>
    http.post<never, MiddlewareOperatorStatus>('/middleware/operator/install', { clusterId, code }),
}
