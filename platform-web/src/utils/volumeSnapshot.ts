import type { K8sVolumeSnapshot } from '@/types'

/** 快照状态（按 spec §6.1 的顺序判断） */
export type SnapshotState = 'deleting' | 'failed' | 'ready' | 'stalled' | 'pending'

/** 没绑到快照内容超过这么久，就提示可能没有 snapshot-controller 或快照类配错了 */
export const STALL_MS = 5 * 60 * 1000

/** K8s 资源名上限 */
const NAME_MAX = 63

export const SNAPSHOT_STATE_LABEL: Record<SnapshotState, string> = {
  deleting: '删除中',
  failed: '失败',
  ready: '可用',
  stalled: '进行中',
  pending: '进行中',
}

/**
 * 状态判断，顺序有意义：
 * 删除中 → 失败（原样显示错误）→ 可用 → 久未绑定（进行中 + 警告）→ 进行中。
 * 已经绑定了快照内容就不警告：NFS 打包大目录本来就慢。
 */
export function snapshotState(s: K8sVolumeSnapshot, now: number = Date.now()): SnapshotState {
  if (s.deletionTimestamp) return 'deleting'
  if (s.errorMessage) return 'failed'
  if (s.readyToUse === true) return 'ready'
  const created = s.creationTime ? Date.parse(s.creationTime) : NaN
  if (!s.boundContentName && !Number.isNaN(created) && now - created > STALL_MS) return 'stalled'
  return 'pending'
}

/** 还没结束的快照（列表据此决定要不要 5 秒轮询） */
export function isUnfinished(s: K8sVolumeSnapshot): boolean {
  const st = snapshotState(s)
  return st === 'pending' || st === 'stalled' || st === 'deleting'
}

/** 把 base 截到 max 以内，再去掉结尾的 `-`（K8s 名字不能以 `-` 结尾） */
function fit(base: string, max: number): string {
  return base.slice(0, Math.max(0, max)).replace(/-+$/, '')
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 默认快照名 `<pvc>-<yyyyMMddHHmmss>`，总长不超过 63；时间戳后缀始终保留 */
export function defaultSnapshotName(pvc: string, now: Date = new Date()): string {
  const ts = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  const suffix = `-${ts}`
  return fit(pvc, NAME_MAX - suffix.length) + suffix
}

/** 默认恢复名 `<快照名>-restore`，总长不超过 63 */
export function defaultRestoreName(snapshot: string): string {
  const suffix = '-restore'
  return fit(snapshot, NAME_MAX - suffix.length) + suffix
}

/**
 * 恢复的默认容量（字节）：来源 PVC 容量与快照大小取较大者；来源 PVC 已删除时取快照大小。
 * 快照大小为 null / 0（NFS 驱动可能这样）时不参与比较。两者都没有返回 undefined。
 */
export function defaultRestoreStorage(sourceBytes?: number | null, restoreSize?: number | null): number | undefined {
  const candidates = [sourceBytes, restoreSize].filter((v): v is number => v != null && v > 0)
  return candidates.length ? Math.max(...candidates) : undefined
}

/** §6.5：快照类 deletionPolicy 的含义（快照类旁、删除确认里都用这一句） */
export function deletionPolicyText(policy?: string | null): string {
  if (policy === 'Delete') return 'Delete：删除快照时底层数据一起删除，不可恢复。'
  if (policy === 'Retain') return 'Retain：只删记录，底层数据保留，需要手工清理。'
  return '删除策略未知。'
}

/** §6.5：门控不可用时的说明 */
export const GATE_TEXT: Record<'NO_CRD' | 'NO_CLASS', string> = {
  NO_CRD: '集群没有卷快照 API，请安装快照 CRD 和 snapshot-controller（可用组件「NFS CSI 驱动」）',
  NO_CLASS: '集群没有快照类，请联系管理员配置',
}

/** §6.5：创建对话框里的两条提醒 */
export const CREATE_NOTES = [
  '快照不是备份：快照和原卷通常在同一套存储上，存储故障时会一起丢',
  '只保证崩溃一致性：数据库等负载恢复后依靠自身恢复机制，打快照前最好先停写或刷盘',
]

/** §6.5：恢复对话框里的提醒 */
export const RESTORE_NOTE = '恢复后的 PVC 容量不能小于快照大小；恢复不影响原卷'

/** PVC 行操作禁用时的 tooltip（与后端报错同一句） */
export const UNSUPPORTED_TEXT = '该 PVC 所在的存储类不支持快照'
