/**
 * 平台总览 / 各维度概览计数卡的类型（与 platform-api 的 PlatformOverviewView / ScopeSummaryView 同名同形）。
 * null 语义：计数块 null = 无读权限（不渲染该卡）；capacity null = 无可用集群；quota null = 无 ns 配了 hard。
 */

/** 数量卡：总数 + 按状态拆分（键序固定，值为 0 的键也保留） */
export interface CountBreakdown {
  total: number
  byStatus: Record<string, number>
}

export interface ServiceHealth {
  up: number
  total: number
  down: string[]
}

export interface OverviewPods {
  total: number
  running: number
  pending: number
  succeeded: number
  abnormal: number
}

export interface OverviewWorkloads {
  total: number
  unhealthy: number
}

export interface OverviewNodes {
  ready: number
  notReady: number
}

export interface OverviewNamespaces {
  allocated: number
  unallocated: number
  system: number
}

/** 只算配了 hard 的 ns；CPU 单位核、内存单位字节（未配的那一维为 null） */
export interface OverviewQuota {
  cpuUsed: number | null
  cpuHard: number | null
  memUsed: number | null
  memHard: number | null
}

export interface PlatformOverview {
  services: ServiceHealth
  clusters: { connected: number; unavailable: number; unavailableNames: string[] }
  /** 以下 k8s / 计数块：没有任何可用集群（或中间件实时状态读不到）→ null，显示「—」 */
  alertsFiring: CountBreakdown | null
  applications: CountBreakdown | null
  middleware: CountBreakdown | null
  workloads: OverviewWorkloads | null
  pods: OverviewPods | null
  tenants: { total: number; enabled: number; disabled: number; members: number; allocatedNamespaces: number }
  capacity: { cpuRequest: number | null; cpuAllocatable: number | null; memRequest: number | null; memAllocatable: number | null } | null
  nodes: OverviewNodes | null
  namespaces: OverviewNamespaces | null
  quota: OverviewQuota | null
  /** 按（集群, 命名空间）拆开的 Pod 分桶与不健康工作负载数（只列有 Pod 或有不健康的 ns）；没有可用集群 → null */
  byNamespace: NamespaceCounts[] | null
}

/** 一个（集群, 命名空间）的计数：总览卡片点「异常 4」时据此决定跳到哪个命名空间 */
export interface NamespaceCounts {
  clusterId: string
  clusterName?: string | null
  namespace: string
  running: number
  pending: number
  succeeded: number
  abnormal: number
  unhealthyWorkloads: number
}

export interface ScopeSummary {
  applications: CountBreakdown | null
  middleware: CountBreakdown | null
  pipelines: CountBreakdown | null
  alerts: CountBreakdown | null
  /** 仅命名空间范围 */
  pods: OverviewPods | null
  workloads: OverviewWorkloads | null
  quota: OverviewQuota | null
  /** 仅集群范围 */
  nodes: OverviewNodes | null
  namespaces: OverviewNamespaces | null
  /** 仅租户范围 */
  namespaceCount: number | null
  memberCount: number | null
}

/** 单节点 Pod 统计（GET /nodes/{name}/podstat）：CPU 毫核、内存字节 */
export interface NodePodStat {
  nodeName: string
  podCount: number
  cpuRequestMillicores: number
  memRequestBytes: number
  cpuLimitMillicores: number
  memLimitBytes: number
  podRunning: number
  podPending: number
  podSucceeded: number
  podAbnormal: number
}
