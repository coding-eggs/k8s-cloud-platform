import http from './http'
import type {
  K8sCluster,
  K8sConfigMap,
  K8sHpa,
  K8sNode,
  K8sPvc,
  K8sPersistentVolume,
  K8sPod,
  K8sSecret,
  K8sService,
  K8sServiceMonitor,
  K8sStorageClass,
  NamespaceAllocation,
  NamespaceView,
  NodeDrainResult,
  NodeEvent,
  NodePodStat,
  NodeTaint,
  PlatformTenant,
  RbacTemplate,
  ResourceContext,
} from '@/types'
import type { WorkloadDetail } from '@/types/workload'

/** 集群管理 /cluster */
export const clusterApi = {
  list: () => http.post<never, K8sCluster[]>('/cluster/list'),
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
}

/** 租户管理 /tenant（含命名空间分配：给租户分配命名空间） */
export const tenantApi = {
  list: () => http.post<never, PlatformTenant[]>('/tenant/list'),
  get: (id: string) => http.post<never, PlatformTenant>('/tenant/get', { id }),
  create: (payload: { name: string; serviceAccount: string; status?: number }) =>
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
}

/** 命名空间管理 /namespace（K8s 命名空间视图 + 删除未分配） */
export const namespaceApi = {
  list: (clusterId: string) => http.post<never, NamespaceView[]>('/namespace/list', { clusterId }),
  delete: (payload: { clusterId: string; namespace: string }) =>
    http.post<never, void>('/namespace/delete', payload),
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
  get: () => http.get<never, ResourceContext>('/resource/context'),
}

/** 资源管理 - ConfigMap /resource/configmaps（参考实现；list/create/update 上下文走 body，get/yaml/delete 走 query） */
export const configMapApi = {
  list: (ctx: { tenantId: string; clusterId: string; namespace: string; labelSelector?: string }) =>
    http.post<never, K8sConfigMap[]>('/resource/configmaps/list', ctx),
  get: (name: string, ctx: { tenantId: string; clusterId: string; namespace: string }) =>
    http.get<never, K8sConfigMap>(`/resource/configmaps/${encodeURIComponent(name)}`, { params: ctx }),
  getYaml: (name: string, ctx: { tenantId: string; clusterId: string; namespace: string }) =>
    http.get<never, string>(`/resource/configmaps/${encodeURIComponent(name)}/yaml`, { params: ctx }),
  create: (ctx: { tenantId: string; clusterId: string }, body: K8sConfigMap) =>
    http.post<never, K8sConfigMap>('/resource/configmaps', { ...body, ...ctx }),
  update: (name: string, ctx: { tenantId: string; clusterId: string }, body: K8sConfigMap) =>
    http.put<never, K8sConfigMap>(`/resource/configmaps/${encodeURIComponent(name)}`, { ...body, ...ctx }),
  delete: (name: string, ctx: { tenantId: string; clusterId: string; namespace: string }) =>
    http.delete<never, void>(`/resource/configmaps/${encodeURIComponent(name)}`, { params: ctx }),
}

// ==================== 其余资源：与 ConfigMap 同构（list/get/yaml/create/update/delete） ====================

type Ctx3 = { tenantId: string; clusterId: string; namespace: string }
type Ctx2 = { tenantId: string; clusterId: string }
/** list 专用上下文：labelSelector 可选（K8s 原生选择器语法，原样透传；不传 = 全量） */
type ListCtx = Ctx3 & { labelSelector?: string }

/**标准 CRUD + yaml 透传工厂：base = /resource/{base}（list/create/update 上下文走 body，get/yaml/delete 走 query） */
function makeResourceApi<T>(base: string) {
  return {
    list: (ctx: ListCtx) => http.post<never, T[]>(`/resource/${base}/list`, ctx),
    get: (name: string, ctx: Ctx3) => http.get<never, T>(`/resource/${base}/${encodeURIComponent(name)}`, { params: ctx }),
    getYaml: (name: string, ctx: Ctx3) => http.get<never, string>(`/resource/${base}/${encodeURIComponent(name)}/yaml`, { params: ctx }),
    create: (ctx: Ctx2, body: T) => http.post<never, T>(`/resource/${base}`, { ...body, ...ctx }),
    update: (name: string, ctx: Ctx2, body: T) => http.put<never, T>(`/resource/${base}/${encodeURIComponent(name)}`, { ...body, ...ctx }),
    delete: (name: string, ctx: Ctx3) => http.delete<never, void>(`/resource/${base}/${encodeURIComponent(name)}`, { params: ctx }),
  }
}

/** Secret /resource/secrets */
export const secretApi = makeResourceApi<K8sSecret>('secrets')

/** Service /resource/services */
export const serviceApi = makeResourceApi<K8sService>('services')

/** PVC /resource/pvcs（spec 不可变，update 仅同步标签） */
export const pvcApi = makeResourceApi<K8sPvc>('pvcs')

/** StorageClass /resource/storageclasses（集群级，只读：供下拉选择 storageClassName；无 namespace） */
export const storageClassApi = {
  list: (ctx: Ctx2) => http.post<never, K8sStorageClass[]>('/resource/storageclasses/list', ctx),
  get: (name: string, ctx: Ctx2) => http.get<never, K8sStorageClass>(`/resource/storageclasses/${encodeURIComponent(name)}`, { params: ctx }),
  getYaml: (name: string, ctx: Ctx2) => http.get<never, string>(`/resource/storageclasses/${encodeURIComponent(name)}/yaml`, { params: ctx }),
}

/** PersistentVolume /resource/persistentvolumes（集群级，只读：供 PVC 详情查看绑定的 PV；无 namespace） */
export const persistentVolumeApi = {
  list: (ctx: Ctx2) => http.post<never, K8sPersistentVolume[]>('/resource/persistentvolumes/list', ctx),
  get: (name: string, ctx: Ctx2) => http.get<never, K8sPersistentVolume>(`/resource/persistentvolumes/${encodeURIComponent(name)}`, { params: ctx }),
  getYaml: (name: string, ctx: Ctx2) => http.get<never, string>(`/resource/persistentvolumes/${encodeURIComponent(name)}/yaml`, { params: ctx }),
}

/** 工作负载 /resource/workloads（kind 在 body；get/delete/yaml 跨 kind 查找） */
export const workloadApi = makeResourceApi<WorkloadDetail>('workloads')

/** ServiceMonitor /resource/servicemonitors（CRD，集群未装 Prometheus Operator 时透传错误） */
export const serviceMonitorApi = makeResourceApi<K8sServiceMonitor>('servicemonitors')

/** ServiceMonitor Prometheus discovery 定位（对应后端 ServiceMonitorKeyRequest：clusterId + namespace + name） */
type SmDiscoveryCtx = { clusterId: string; namespace: string; name: string }

/** ServiceMonitor Relabeling/MetricRelabeling 的 sourceLabels 候选（编辑态；后端从集群 Prometheus discovery 拉取，不可达返回空）。
 * relabeling=服务发现原始标签(discoveredLabels)；metricRelabeling=最终目标标签(labels)，前端另补 __name__ */
export const serviceMonitorRelabelApi = {
  labels: (ctx: SmDiscoveryCtx) =>
    http.post<never, { relabeling: string[]; metricRelabeling: string[] }>('/resource/servicemonitors/relabel-labels', ctx),
  /** MetricRelabeling regex 的 __name__（指标名）候选：scoped 到本 SM 活跃 target；无 target/不可达返回空 */
  metricNames: (ctx: SmDiscoveryCtx) =>
    http.post<never, string[]>('/resource/servicemonitors/metric-names', ctx),
}

/** HPA /resource/hpas（发散资源：autoscaling v1/v2 由后端按集群 capability 分派） */
export const hpaApi = makeResourceApi<K8sHpa>('hpas')

/** Pod /resource/pods（只读 + 删除：由工作负载控制器管理，无 create/update） */
export const podApi = {
  list: (ctx: ListCtx) => http.post<never, K8sPod[]>('/resource/pods/list', ctx),
  get: (name: string, ctx: Ctx3) => http.get<never, K8sPod>(`/resource/pods/${encodeURIComponent(name)}`, { params: ctx }),
  getYaml: (name: string, ctx: Ctx3) => http.get<never, string>(`/resource/pods/${encodeURIComponent(name)}/yaml`, { params: ctx }),
  delete: (name: string, ctx: Ctx3) => http.delete<never, void>(`/resource/pods/${encodeURIComponent(name)}`, { params: ctx }),
}

/** Node /resource/nodes（集群级，admin；无 create/delete，节点专属动作走独立端点） */
export const nodeApi = {
  list: (ctx: { clusterId: string; labelSelector?: string }) =>
    http.post<never, K8sNode[]>('/resource/nodes/list', ctx),
  get: (name: string, clusterId: string) =>
    http.get<never, K8sNode>(`/resource/nodes/${encodeURIComponent(name)}`, { params: { clusterId } }),
  getYaml: (name: string, clusterId: string) =>
    http.get<never, string>(`/resource/nodes/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
  cordon: (clusterId: string, name: string) =>
    http.post<never, K8sNode>('/resource/nodes/cordon', { clusterId, name }),
  uncordon: (clusterId: string, name: string) =>
    http.post<never, K8sNode>('/resource/nodes/uncordon', { clusterId, name }),
  updateLabelsTaints: (name: string, clusterId: string, body: { labels: Record<string, string>; taints: NodeTaint[] }) =>
    http.put<never, K8sNode>(`/resource/nodes/${encodeURIComponent(name)}`, body, { params: { clusterId } }),
  drain: (payload: { clusterId: string; name: string; force?: boolean; deleteEmptyDir?: boolean }) =>
    http.post<never, NodeDrainResult>('/resource/nodes/drain', payload),
  podstats: (clusterId: string) =>
    http.get<never, NodePodStat[]>('/resource/nodes/podstats', { params: { clusterId } }),
  pods: (name: string, clusterId: string) =>
    http.get<never, K8sPod[]>(`/resource/nodes/${encodeURIComponent(name)}/pods`, { params: { clusterId } }),
  events: (name: string, clusterId: string) =>
    http.get<never, NodeEvent[]>(`/resource/nodes/${encodeURIComponent(name)}/events`, { params: { clusterId } }),
}
