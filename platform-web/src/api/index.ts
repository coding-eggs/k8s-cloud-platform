import http from './http'
import type {
  BgpConfiguration,
  BgpFilter,
  BgpPeer,
  CalicoFormOption,
  K8sCluster,
  K8sClusterCapability,
  K8sClusterOption,
  K8sConfigMap,
  K8sGateway,
  K8sGatewayClass,
  K8sGrpcRoute,
  K8sHpa,
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
  NamespaceAllocation,
  NamespaceView,
  NodeDrainResult,
  NodeEvent,
  NodeTaint,
  PlatformPermission,
  PlatformRole,
  PlatformTenant,
  PlatformUser,
  RbacTemplate,
  ResourceContext,
  SecretRefOption,
  TenantMemberView,
  TokenUserInfo,
} from '@/types'
import type { WorkloadDetail } from '@/types/workload'

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
    ipStack?: K8sCluster['ipStack']; prometheusUrl?: string; grafanaUrl?: string
  }) => http.post<never, K8sCluster>('/cluster/create', payload),
  update: (payload: {
    clusterId: string; clusterName?: string; description?: string; kubeconfig?: string
    version?: string; istioVersion?: string; calicoVersion?: string; containerRuntime?: string
    ipStack?: K8sCluster['ipStack']; prometheusUrl?: string; grafanaUrl?: string
  }) => http.post<never, K8sCluster>('/cluster/update', payload),
  toggleEnabled: (clusterId: string, enabled: number) =>
    http.post<never, K8sCluster>('/cluster/toggleEnabled', { clusterId, enabled }),
  delete: (clusterId: string) => http.post<never, void>('/cluster/delete', { clusterId }),
  provision: (clusterId: string) => http.post<never, K8sCluster>('/cluster/provision', { clusterId }),
  refreshCapability: (clusterId: string) => http.post<never, void>('/cluster/capability/refresh', { clusterId }),
  getCapability: (clusterId: string) =>
    http.post<never, K8sClusterCapability>('/cluster/capability/get', { clusterId }),
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
}

/** 命名空间管理 /namespace（K8s 命名空间视图 + 创建/编辑/删除 + 配额/限制范围） */

/**
 * 命名空间创建/编辑载荷。两个 istio 字段各自 **三态**：不传 = 不动该标签、空串 = 移除、有值 = 覆写
 * ——「跟随集群默认」要发空串而不是省略字段，否则后端按"未传"处理、旧值会留在 ns 上。
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

/** PVC /pvcs（spec 不可变，update 仅同步标签） */
export const pvcApi = makeResourceApi<K8sPvc>('pvcs')

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
}

/** ServiceMonitor /servicemonitors（CRD，集群未装 Prometheus Operator 时透传错误） */
export const serviceMonitorApi = makeResourceApi<K8sServiceMonitor>('servicemonitors')

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
  /** 命名空间内的 waypoint Gateway 名（平台侧读）——供命名空间编辑器的 istio.io/use-waypoint 下拉。
   *  命名空间是平台侧资源、Gateway 是租户域资源，平台管理员没有租户上下文，故不能走 gatewayApi.list。 */
  namespaceWaypoints: (clusterId: string, namespace: string) =>
    http.post<never, string[]>('/mesh/gateways', null, { params: { clusterId, namespace } }),
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
