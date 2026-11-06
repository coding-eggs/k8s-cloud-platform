import type { CountBreakdown, NamespaceCounts, OverviewPods } from '@/types/overview'
import type { K8sNamespaceStatEntry } from '@/types'

export type Tone = 'success' | 'warning' | 'danger' | 'info'

export interface Segment {
  label: string
  value: number
  tone: Tone
  /** 点这一段时带到列表页的查询参数（列表页按它预先筛好）；不给 = 这一段不可单独点 */
  query?: Record<string, string>
}

type Def = Record<string, { label: string; tone: Tone; query?: Record<string, string> }>

/** 状态键 → 中文 + 色调（键与后端 OverviewBuckets 一致） */
/** 应用的 query 用台账原值（应用列表按台账 status 筛）：RUNNING=READY、UNRELEASED=DRAFT */
const APP: Def = {
  RUNNING: { label: '运行', tone: 'success', query: { status: 'READY' } },
  DEPLOYING: { label: '发布中', tone: 'info', query: { status: 'DEPLOYING' } },
  UNRELEASED: { label: '未发布', tone: 'info', query: { status: 'DRAFT' } },
  FAILED: { label: '失败', tone: 'danger', query: { status: 'FAILED' } },
  OFFLINE: { label: '下线', tone: 'warning', query: { status: 'OFFLINE' } },
}
const MIDDLEWARE: Def = {
  NORMAL: { label: '正常', tone: 'success' },
  ABNORMAL: { label: '异常', tone: 'danger' },
  PROCESSING: { label: '处理中', tone: 'info' },
}
const PIPELINE: Def = {
  SUCCEEDED: { label: '成功', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' },
  RUNNING: { label: '运行中', tone: 'info' },
  CANCELLED: { label: '已取消', tone: 'warning' },
  NEVER: { label: '未运行', tone: 'info' },
}
/** 中间件实例 / 流水线列表暂无状态筛选（流水线列表不带最近一次运行状态），这两类的段不可单独点 */
const ALERT: Def = {
  CRITICAL: { label: '严重', tone: 'danger', query: { status: 'firing', severity: 'critical' } },
  WARNING: { label: '警告', tone: 'warning', query: { status: 'firing', severity: 'warning' } },
  OTHER: { label: '其它', tone: 'info' },
}

const DEFS = { app: APP, middleware: MIDDLEWARE, pipeline: PIPELINE, alert: ALERT }

/** CountBreakdown → CountCard 的段（按定义顺序；未知键忽略） */
export function segmentsOf(kind: keyof typeof DEFS, b: CountBreakdown | null | undefined): Segment[] {
  if (!b) return []
  const def = DEFS[kind]
  return Object.entries(def).map(([k, d]) => ({ label: d.label, tone: d.tone, query: d.query, value: b.byStatus?.[k] ?? 0 }))
}

export function podSegments(p: OverviewPods | null | undefined): Segment[] {
  if (!p) return []
  return [
    { label: 'Running', value: p.running, tone: 'success', query: { status: 'running' } },
    { label: 'Pending', value: p.pending, tone: 'warning', query: { status: 'pending' } },
    { label: '已完成', value: p.succeeded, tone: 'info', query: { status: 'succeeded' } },
    { label: '异常', value: p.abnormal, tone: 'danger', query: { status: 'abnormal' } },
  ]
}

/** 数量卡的资源类型 → 图标（与侧栏菜单同一个图标；Element Plus 图标已全局注册，按名字渲染） */
export type CountKind = 'app' | 'middleware' | 'workload' | 'pod' | 'pipeline' | 'alert' | 'tenant' | 'namespace'

export const COUNT_KIND_ICON: Record<CountKind, string> = {
  app: 'Connection',
  middleware: 'Grid',
  workload: 'Box',
  pod: 'Cpu',
  pipeline: 'Operation',
  alert: 'Bell',
  tenant: 'OfficeBuilding',
  namespace: 'FolderOpened',
}

/**
 * 数量卡点击去哪：有数时去该资源的列表页；为 0 时去「从哪开始」的页面并给一句引导。
 * 能不能点由 CountCard 按目标路由的 requiresPerm 判（没权限就是一张普通卡，不给死链）。
 * 列表页都按顶栏上下文（租户 / 集群 / 命名空间）取数，跳过去看到的是当前上下文的范围。
 */
export const COUNT_KIND_LINK: Record<CountKind, { list: string; empty?: { text: string; path?: string; good?: boolean } }> = {
  app: { list: '/applications', empty: { text: '还没有应用，点击去新建', path: '/applications' } },
  middleware: { list: '/apps/instances', empty: { text: '还没有中间件，去商店装一个', path: '/apps' } },
  workload: { list: '/resources/workloads', empty: { text: '暂无工作负载' } },
  pod: { list: '/resources/pods', empty: { text: '暂无 Pod' } },
  pipeline: { list: '/pipelines', empty: { text: '还没有流水线，点击去新建', path: '/pipelines' } },
  alert: { list: '/alerts?status=firing', empty: { text: '没有触发中的告警', good: true } },
  tenant: { list: '/tenants', empty: { text: '还没有租户，点击去新建', path: '/tenants' } },
  namespace: { list: '/namespaces' },
}

/** 跳转范围：整张卡（和它的状态段）都按这个（集群[, 命名空间]）切顶栏再跳 —— 命名空间 / 集群概览页用 */
export interface CardScope {
  clusterId: string
  namespace?: string
}

/** 「点异常 4 → 去哪个命名空间」的一个候选 */
export interface NsTarget {
  clusterId: string
  clusterName?: string | null
  namespace: string
  count: number
}

/** 租户 / 集群概览的按命名空间明细行 → 计数行（口径同后端：abnormal = podCount − running − pending − succeeded） */
export function nsCountsOf(rows: K8sNamespaceStatEntry[] | null | undefined, clusterName?: (id: string) => string | null | undefined): NamespaceCounts[] {
  return (rows ?? []).map((r) => {
    const st = r.snapshot.stat
    const running = st.podRunning ?? 0
    const pending = st.podPending ?? 0
    const succeeded = st.podSucceeded ?? 0
    return {
      clusterId: r.clusterId,
      clusterName: clusterName?.(r.clusterId) ?? null,
      namespace: st.namespace,
      running,
      pending,
      succeeded,
      abnormal: Math.max(0, (st.podCount ?? 0) - running - pending - succeeded),
      unhealthyWorkloads: r.snapshot.unhealthyWorkloadCount ?? 0,
    }
  })
}

/**
 * 某张卡的某个状态段分布在哪些命名空间（按数量降序；为 0 的不列）。
 * 只有 Pod（按 status 分桶）与工作负载（unhealthy）有按命名空间的数；其它返回 null（不做命名空间跳转）。
 */
export function nsTargetsOf(kind: CountKind | undefined, seg: Segment, rows: NamespaceCounts[]): NsTarget[] | null {
  const status = seg.query?.status
  let pick: ((r: NamespaceCounts) => number) | null = null
  if (kind === 'pod' && status && ['running', 'pending', 'succeeded', 'abnormal'].includes(status)) {
    pick = (r) => r[status as 'running' | 'pending' | 'succeeded' | 'abnormal']
  } else if (kind === 'workload' && status === 'unhealthy') {
    pick = (r) => r.unhealthyWorkloads
  }
  if (!pick) return null
  const f = pick
  return rows
    .map((r) => ({ clusterId: r.clusterId, clusterName: r.clusterName, namespace: r.namespace, count: f(r) }))
    .filter((t) => t.count > 0)
    .sort((a, b) => b.count - a.count)
}
