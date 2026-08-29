/** RBAC 策略规则（与后端 PolicyRuleDTO 对应） */
export interface PolicyRule {
  apiGroups?: string[]
  resources?: string[]
  verbs?: string[]
  resourceNames?: string[]
}

/** K8s 集群（kubeconfig 不下发，恒为 null） */
export interface K8sCluster {
  clusterId: string
  clusterName: string
  kubeconfig?: string | null
  version?: string | null
  istioVersion?: string | null
  calicoVersion?: string | null
  containerRuntime?: string | null
  description?: string | null
  ipStack?: 'IPV4' | 'IPV6' | 'IPV4_AND_IPV6' | null
  status?: 'CONNECTED' | 'DISCONNECTED' | 'CONNECTING' | 'ERROR' | null
  enabled: number
  lastHeartbeatTime?: string | null
  createdAt?: string | null
  updatedAt?: string | null
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

/** 命名空间分配 */
export interface NamespaceAllocation {
  id: string
  tenantId: string
  clusterId: string
  namespace: string
  roleTemplateId?: string | null
  createTime?: string | null
}

/** 命名空间视图（K8s 命名空间 + 分配信息合并，api 侧加工） */
export interface NamespaceView {
  name: string
  phase?: string | null
  creationTimestamp?: string | null
  managedBy: boolean
  allocatedTenantName?: string | null
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
  creationTime?: string | null
}

/** Secret（data 为明文，后端已从 base64 解码） */
export interface K8sSecret {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  type: string
  data?: Record<string, string> | null
  creationTime?: string | null
}

/** Service 端口（targetPort 为字符串：数字或命名端口） */
export interface K8sServicePort {
  name?: string | null
  port?: number | null
  targetPort?: string | null
  nodePort?: number | null
  protocol?: string | null
}

/** Service */
export interface K8sService {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  type: string
  ports?: K8sServicePort[] | null
  clusterIp?: string | null
  creationTime?: string | null
}

/** PVC（spec 创建后不可变，phase = Bound/Pending/Lost） */
export interface K8sPvc {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  storageClassName?: string | null
  accessModes?: string[] | null
  storage?: string | null
  phase?: string | null
  creationTime?: string | null
}

/** Pod（只读：由工作负载控制器管理） */
export interface K8sPod {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  phase?: string | null
  nodeName?: string | null
  podIp?: string | null
  restarts?: number | null
  containers?: string[] | null
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
  images?: string[] | null
  ports?: K8sWorkloadPort[] | null
  creationTime?: string | null
}

/** ServiceMonitor 抓取端点 */
export interface K8sSmEndpoint {
  port?: string | null
  path?: string | null
  interval?: string | null
}

/** ServiceMonitor（monitoring.coreos.com/v1 CRD） */
export interface K8sServiceMonitor {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  matchLabels?: Record<string, string> | null
  endpoints?: K8sSmEndpoint[] | null
  creationTime?: string | null
}

/** 资源管理上下文级联（/resource/context） */
export interface ResourceContextCluster {
  clusterId: string
  clusterName: string
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

