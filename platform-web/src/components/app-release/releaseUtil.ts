/**
 * 发布 / 回滚 UI 的纯函数：错误文案、镜像 tag、快照差异、状态文案。
 * 差异在前端算（回滚确认用 release/get 取目标和线上两份快照比较），不加后端端点。
 */
import type { AppEnvRow, AppRelease, AppServiceDesired } from '@/types'

export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

/** 后端业务错误原文（拦截器对非 200 码抛 Error(msg)，HTTP 错误则在 response.data.msg）；不改写，包括「线上已变化，请刷新后重试」 */
export function errMsg(e: unknown, fallback = '操作失败'): string {
  const anyE = e as { response?: { data?: { msg?: string } }; message?: string } | null
  return anyE?.response?.data?.msg || anyE?.message || fallback
}

export function imageTag(image?: string | null): string {
  if (!image) return ''
  const i = image.lastIndexOf(':')
  return i > image.lastIndexOf('/') ? image.slice(i + 1) : image
}

/** PENDING / RUNNING 在推进；INTERRUPTED 在等人点继续，不算「正在跑」 */
export function isRunning(status?: string | null): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

export const EXEC_STAGE: Record<string, string> = {
  DONE: '完成', PRECHECK: '预检', RENDER: '渲染', CONFLICT: '冲突检查', APPLY: '下发', HEALTH: '健康检查', PRUNE: '清理旧对象',
}
export const STAGE_ORDER = ['PRECHECK', 'RENDER', 'CONFLICT', 'APPLY', 'HEALTH', 'PRUNE']

export const EXEC_STATUS: Record<string, { text: string; type: Tone }> = {
  PENDING: { text: '排队中', type: 'warning' },
  RUNNING: { text: '执行中', type: 'warning' },
  INTERRUPTED: { text: '已中断', type: 'danger' },
  FAILED: { text: '失败', type: 'danger' },
  /** 执行器写入的成功终态 */
  SUCCESS: { text: '完成', type: 'success' },
  DONE: { text: '完成', type: 'success' },
  SUCCEEDED: { text: '完成', type: 'success' },
}

/** 进度条位置：DONE（全部阶段走完）= 步骤总数，所有步骤都显示完成；未知阶段停在第一步 */
export function stageStep(stage?: string | null): number {
  if (stage === 'DONE') return STAGE_ORDER.length
  return Math.max(0, STAGE_ORDER.indexOf(stage ?? ''))
}

export const RELEASE_STATUS: Record<string, { text: string; type: Tone }> = {
  RENDERED: { text: '执行中', type: 'warning' },
  DEPLOYED: { text: '已上线', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
}

/**
 * 回滚到某条发布后端是否会接受（前端据此隐藏入口；其余拒绝原因由后端原文给出）：
 * 源必须是 DEPLOYED；线上就是最新发布时回滚到它没有意义（有更新的失败 / 中断发布时，回到线上版本是合法收尾）。
 */
export function canRollbackTo(r: AppRelease, liveNo: number, maxNo: number): boolean {
  if (r.status !== 'DEPLOYED') return false
  if (r.releaseNo === liveNo && r.releaseNo === maxNo) return false
  return true
}

// ---------------------------------------------------------------- 快照差异

const FIELD_LABEL: Record<string, string> = {
  imageRepo: '镜像仓库', imageTag: '镜像 tag', port: '端口', replicas: '副本数', env: '环境变量', configFiles: '配置文件',
  command: '启动命令', args: '命令参数', imagePullPolicy: '拉取策略',
  cpuRequest: 'CPU 请求', memoryRequest: '内存请求', cpuLimit: 'CPU 限制', memoryLimit: '内存限制',
  persistenceEnabled: '持久化', storageSize: '存储大小', storageClass: '存储类', mountPath: '挂载路径',
  autoscalingEnabled: '自动扩缩', minReplicas: '最小副本', maxReplicas: '最大副本', targetCpu: '目标 CPU',
  probeEnabled: '健康探针', probePath: '探针路径', metricsEnabled: '指标采集', metricsPath: '指标路径', expose: '对外暴露',
}
export const fieldLabel = (k: string): string => FIELD_LABEL[k] ?? k

function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(norm(a)) === JSON.stringify(norm(b))
}
/** 键排序 + 去掉 null / 空串 / 空数组，避免「缺省 vs 显式缺省值」被当成差异 */
function norm(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(norm)
  if (v && typeof v === 'object') {
    const out: Record<string, unknown> = {}
    for (const k of Object.keys(v as object).sort()) {
      const x = (v as Record<string, unknown>)[k]
      if (x === null || x === undefined || x === '' || (Array.isArray(x) && x.length === 0)) continue
      out[k] = norm(x)
    }
    return out
  }
  return v
}

export interface ServiceDiff {
  key: string
  /** ADDED=目标有、线上没有（回滚后会重建）；REMOVED=线上有、目标没有（回滚后会删除）；CHANGED=字段不同 */
  type: 'ADDED' | 'REMOVED' | 'CHANGED'
  fields: string[]
  /** 环境变量逐项变化，文案里直接写变量名，不带值 */
  envNotes: string[]
  fromImage: string
  toImage: string
}

function envNotes(from: AppEnvRow[] = [], to: AppEnvRow[] = []): string[] {
  const a = new Map(from.map((e) => [e.name, e]))
  const b = new Map(to.map((e) => [e.name, e]))
  const out: string[] = []
  for (const [n, e] of b) {
    if (!a.has(n)) out.push(`+ ${n}`)
    else if (!same(a.get(n), e)) out.push(`~ ${n}`)
  }
  for (const n of a.keys()) if (!b.has(n)) out.push(`- ${n}`)
  return out
}

const imageOf = (d?: AppServiceDesired): string => (d ? `${d.imageRepo ?? ''}:${d.imageTag ?? ''}` : '')

/** 目标快照相对线上快照的服务差异（回滚后线上会变成目标） */
export function diffServices(live: AppRelease['snapshot'], target: AppRelease['snapshot']): ServiceDiff[] {
  const l = live?.services ?? {}
  const t = target?.services ?? {}
  const keys = [...new Set([...Object.keys(l), ...Object.keys(t)])].sort()
  const out: ServiceDiff[] = []
  for (const key of keys) {
    const from = l[key]
    const to = t[key]
    if (!from && to) out.push({ key, type: 'ADDED', fields: [], envNotes: [], fromImage: '', toImage: imageOf(to) })
    else if (from && !to) out.push({ key, type: 'REMOVED', fields: [], envNotes: [], fromImage: imageOf(from), toImage: '' })
    else if (from && to && !same(from, to)) {
      const fields = [...new Set([...Object.keys(from), ...Object.keys(to)])].sort()
        .filter((f) => !same(from[f], to[f]))
      out.push({
        key, type: 'CHANGED', fields: fields.map(fieldLabel),
        envNotes: fields.includes('env') ? envNotes(from.env, to.env) : [],
        fromImage: imageOf(from), toImage: imageOf(to),
      })
    }
  }
  return out
}

export interface DependencyDiff { key: string; type: 'ADDED' | 'REMOVED' | 'CHANGED' }

export function diffDependencies(live: AppRelease['snapshot'], target: AppRelease['snapshot']): DependencyDiff[] {
  const l = new Map((live?.dependencies ?? []).map((d) => [d.key, d]))
  const t = new Map((target?.dependencies ?? []).map((d) => [d.key, d]))
  const out: DependencyDiff[] = []
  for (const k of [...new Set([...l.keys(), ...t.keys()])].sort()) {
    if (!l.has(k)) out.push({ key: k, type: 'ADDED' })
    else if (!t.has(k)) out.push({ key: k, type: 'REMOVED' })
    else if (!same(l.get(k), t.get(k))) out.push({ key: k, type: 'CHANGED' })
  }
  return out
}
