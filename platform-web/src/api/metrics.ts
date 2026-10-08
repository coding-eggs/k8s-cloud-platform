import http from './http'
import type { MetricSeriesResponse } from '@/types/metrics'
import type { NodeCurrentMetric } from '@/types'

/** 指标查询上下文：tenantId + clusterId + namespace（name 走 path，start/end 为 unix 秒） */
export interface MetricsRangeReq {
  tenantId: string
  clusterId: string
  namespace: string
  /** 工作负载类型（deployment/statefulset/daemonset）；仅工作负载维度传，Pod 维度可空 */
  kind?: string
  start: number
  end: number
}

/** 单维度指标 API：cpu / memory / network / disk 各一 POST（复用 http.ts，自动解 ResponseData.data） */
function makeMetricsApi(dim: 'workloads' | 'pods') {
  const base = `/${dim}`
  return {
    cpu: (name: string, req: MetricsRangeReq) =>
      http.post<never, MetricSeriesResponse>(`${base}/${encodeURIComponent(name)}/metrics/cpu`, req),
    memory: (name: string, req: MetricsRangeReq) =>
      http.post<never, MetricSeriesResponse>(`${base}/${encodeURIComponent(name)}/metrics/memory`, req),
    network: (name: string, req: MetricsRangeReq) =>
      http.post<never, MetricSeriesResponse>(`${base}/${encodeURIComponent(name)}/metrics/network`, req),
    disk: (name: string, req: MetricsRangeReq) =>
      http.post<never, MetricSeriesResponse>(`${base}/${encodeURIComponent(name)}/metrics/disk`, req),
  }
}

/** 工作负载维度（4 图） */
export const workloadMetrics = makeMetricsApi('workloads')
/** Pod 维度（4 图） */
export const podMetrics = makeMetricsApi('pods')

/** 命名空间指标查询上下文：clusterId + namespace（集群级，无租户）+ start/end */
export interface NamespaceMetricsReq {
  clusterId: string
  namespace: string
  start: number
  end: number
}

/** 命名空间维度（4 图，跨该 ns 全部 pod 聚合） */
export const namespaceMetrics = {
  cpu: (req: NamespaceMetricsReq) =>
    http.post<never, MetricSeriesResponse>('/namespace/metrics/cpu', req),
  memory: (req: NamespaceMetricsReq) =>
    http.post<never, MetricSeriesResponse>('/namespace/metrics/memory', req),
  network: (req: NamespaceMetricsReq) =>
    http.post<never, MetricSeriesResponse>('/namespace/metrics/network', req),
  disk: (req: NamespaceMetricsReq) =>
    http.post<never, MetricSeriesResponse>('/namespace/metrics/disk', req),
}

/** 节点指标查询上下文：clusterId + instance（<internalIp>:9100，前端从 node.internalIp 拼）+ start/end */
export interface NodeMetricsReq {
  clusterId: string
  instance: string
  start: number
  end: number
}

/** 节点维度（4 图 + 列表页当前值批量） */
export const nodeMetrics = {
  cpu: (name: string, req: NodeMetricsReq) =>
    http.post<never, MetricSeriesResponse>(`/nodes/${encodeURIComponent(name)}/metrics/cpu`, req),
  memory: (name: string, req: NodeMetricsReq) =>
    http.post<never, MetricSeriesResponse>(`/nodes/${encodeURIComponent(name)}/metrics/memory`, req),
  network: (name: string, req: NodeMetricsReq) =>
    http.post<never, MetricSeriesResponse>(`/nodes/${encodeURIComponent(name)}/metrics/network`, req),
  disk: (name: string, req: NodeMetricsReq) =>
    http.post<never, MetricSeriesResponse>(`/nodes/${encodeURIComponent(name)}/metrics/disk`, req),
  /** 列表页批量当前值：返回 instance → {cpuPercent, memPercent} */
  current: (clusterId: string, instances: string[]) =>
    http.post<never, Record<string, NodeCurrentMetric>>('/nodes/metrics/current', { clusterId, instances }),
}
