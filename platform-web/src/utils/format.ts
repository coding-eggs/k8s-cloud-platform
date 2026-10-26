/** 时间格式化：兼容后端 epoch millis（number）与 ISO 字符串 */
export function fmtDate(v?: number | string | null): string {
  if (v === null || v === undefined || v === '') return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 存活时长：now - v，压缩为「Xd Yh / Yh Zm / Zm / Ws」 */
export function fmtAge(v?: number | string | null): string {
  if (v === null || v === undefined || v === '') return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return '-'
  let ms = Date.now() - d.getTime()
  if (ms < 0) ms = 0
  const s = Math.floor(ms / 1000)
  const days = Math.floor(s / 86400)
  const hours = Math.floor((s % 86400) / 3600)
  const mins = Math.floor((s % 3600) / 60)
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${mins}m`
  if (mins > 0) return `${mins}m`
  return `${s}s`
}

export type BadgeType = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

/**
 * 工作负载状态：由副本就绪情况推导（列表 / 详情页共用）。
 *
 * <p>`statusReason` 非空 = 控制器自报了异常（后端把 {@code Progressing=False}、{@code Available=False}
 * 以及「True 才是坏事」的 {@code ReplicaFailure} 都收进这个字段）。此时**不能只看 ready**：
 * 发版被配额/准入拒绝时，旧版本的 Pod 还在撑着 `ready >= desired`，按副本数看是绿色 Running，
 * 用户只会觉得"镜像改了但没生效"（2026-10-26 实报：movie-server 超 limits.memory 配额）。
 */
export function workloadStatus(
  kind: string | null | undefined,
  replicas?: number | null,
  readyReplicas?: number | null,
  statusReason?: string | null,
): { label: string; type: BadgeType } {
  const base = baseWorkloadStatus(kind, replicas, readyReplicas)
  if (!statusReason) return base
  const desired = replicas ?? 0
  // 副本是满的、却报了异常 → 满的那份是旧版本，新版本没起来
  if (desired > 0 && (readyReplicas ?? 0) >= desired) {
    return { label: '更新受阻', type: 'warning' }
  }
  return { label: base.label, type: 'warning' }
}

/** 只看副本数的基线判定（不掺 statusReason，保持纯函数） */
function baseWorkloadStatus(
  kind: string | null | undefined,
  replicas?: number | null,
  readyReplicas?: number | null,
): { label: string; type: BadgeType } {
  const ready = readyReplicas ?? 0
  if (kind === 'daemonset') {
    return ready > 0 ? { label: 'Running', type: 'success' } : { label: 'Pending', type: 'neutral' }
  }
  const desired = replicas ?? 0
  if (desired <= 0) return { label: 'Suspended', type: 'info' }
  if (ready >= desired) return { label: 'Running', type: 'success' }
  if (ready > 0) return { label: 'Progressing', type: 'warning' }
  return { label: 'Pending', type: 'neutral' }
}

/** Pod phase → 徽章类型（PodView / 详情页共用） */
export function podPhaseType(phase?: string | null): BadgeType {
  switch (phase) {
    case 'Running': return 'success'
    case 'Pending': return 'warning'
    case 'Succeeded': return 'info'
    case 'Failed': return 'danger'
    default: return 'neutral'
  }
}

/** 容器运行状态 → 徽章类型 */
export function containerStateType(state?: string | null): BadgeType {
  switch (state) {
    case 'Running': return 'success'
    case 'Waiting': return 'warning'
    case 'Terminated': return 'danger'
    default: return 'neutral'
  }
}
