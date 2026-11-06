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
