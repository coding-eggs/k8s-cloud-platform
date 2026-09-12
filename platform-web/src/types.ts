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
  storage?: string | null
  /** 数据来源（可选，spec.dataSourceRef）：从已有对象填充新卷 */
  dataSourceRef?: { apiGroup?: string | null; kind?: string | null; name?: string | null; namespace?: string | null } | null
  phase?: string | null
  creationTime?: string | null
}

/** StorageClass（集群级，只读：供工作负载编辑器下拉选择 storageClassName） */
export interface K8sStorageClass {
  name: string
  provisioner?: string | null
  reclaimPolicy?: string | null
  allowVolumeExpansion?: boolean | null
  creationTime?: string | null
}

/** PersistentVolume（集群级，只读：供 PVC 详情查看绑定的 PV） */
export interface K8sPersistentVolume {
  name: string
  labels?: Record<string, string> | null
  capacity?: string | null
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
  /** 状态原因（未完全就绪时的说明；正常时为空） */
  statusReason?: string | null
  images?: string[] | null
  ports?: K8sWorkloadPort[] | null
  creationTime?: string | null
  /** 属主引用（由 Operator/控制器创建的才有；非空即「op 管理」→ 禁用编辑） */
  ownerReferences?: OwnerRef[] | null
  /** 对外暴露的 Service（仅 list/get 返回，api 侧 join 计算）：绑定到本工作负载的 NodePort/LB Service */
  exposedServices?: K8sExposedService[] | null
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
  tlsConfig?: { insecureSkipVerify?: boolean | null; serverName?: string | null } | null
  relabelings?: K8sSmRelabeling[] | null
  metricRelabelings?: K8sSmRelabeling[] | null
}

/** ServiceMonitor（monitoring.coreos.com/v1 CRD） */
export interface K8sServiceMonitor {
  name: string
  namespace: string
  labels?: Record<string, string> | null
  /** spec.selector.matchLabels（必填）：由后端据 serviceRef 解析填充；查询时原样返回 */
  matchLabels?: Record<string, string> | null
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

/** HPA 指标目标（对应后端 HpaMetricTargetDTO） */
export interface K8sHpaMetricTarget {
  type?: 'Utilization' | 'AverageValue' | 'Value' | null
  averageUtilization?: number | null
  value?: string | null
  averageValue?: string | null
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

/** 资源管理上下文级联（/resource/context） */
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
  cpuCapacity?: string | null
  memoryCapacity?: string | null
  cpuAllocatable?: string | null
  memoryAllocatable?: string | null
  podsLimit?: number | null
  conditions?: NodeCondition[] | null
  taints?: NodeTaint[] | null
  creationTime?: string | null
}

/** 节点 Pod 聚合统计（列表页：实际数 + requests 之和） */
export interface NodePodStat {
  nodeName: string
  podCount: number
  cpuRequestMillicores: number
  memRequestBytes: number
}

/** 节点 Drain 结果 */
export interface NodeDrainResult {
  evicted?: string[] | null
  skipped?: string[] | null
  errors?: string[] | null
}

/** 节点事件（involvedObject.kind=Node） */
export interface NodeEvent {
  reason?: string | null
  message?: string | null
  type?: string | null
  count?: number | null
  firstTimestamp?: string | null
  lastTimestamp?: string | null
}

/** 节点当前用量（列表页 CPU%/内存%） */
export interface NodeCurrentMetric {
  cpuPercent?: number | null
  memPercent?: number | null
}


