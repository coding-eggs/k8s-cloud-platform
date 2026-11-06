import type { CountBreakdown, OverviewPods } from '@/types/overview'

export type Tone = 'success' | 'warning' | 'danger' | 'info'

export interface Segment {
  label: string
  value: number
  tone: Tone
}

type Def = Record<string, { label: string; tone: Tone }>

/** 状态键 → 中文 + 色调（键与后端 OverviewBuckets 一致） */
const APP: Def = {
  RUNNING: { label: '运行', tone: 'success' },
  DEPLOYING: { label: '发布中', tone: 'info' },
  UNRELEASED: { label: '未发布', tone: 'info' },
  FAILED: { label: '失败', tone: 'danger' },
  OFFLINE: { label: '下线', tone: 'warning' },
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
const ALERT: Def = {
  CRITICAL: { label: '严重', tone: 'danger' },
  WARNING: { label: '警告', tone: 'warning' },
  OTHER: { label: '其它', tone: 'info' },
}

const DEFS = { app: APP, middleware: MIDDLEWARE, pipeline: PIPELINE, alert: ALERT }

/** CountBreakdown → CountCard 的段（按定义顺序；未知键忽略） */
export function segmentsOf(kind: keyof typeof DEFS, b: CountBreakdown | null | undefined): Segment[] {
  if (!b) return []
  const def = DEFS[kind]
  return Object.entries(def).map(([k, d]) => ({ label: d.label, tone: d.tone, value: b.byStatus?.[k] ?? 0 }))
}

export function podSegments(p: OverviewPods | null | undefined): Segment[] {
  if (!p) return []
  return [
    { label: 'Running', value: p.running, tone: 'success' },
    { label: 'Pending', value: p.pending, tone: 'warning' },
    { label: '已完成', value: p.succeeded, tone: 'info' },
    { label: '异常', value: p.abnormal, tone: 'danger' },
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
  alert: { list: '/alerts', empty: { text: '没有触发中的告警', good: true } },
  tenant: { list: '/tenants', empty: { text: '还没有租户，点击去新建', path: '/tenants' } },
  namespace: { list: '/namespaces' },
}
