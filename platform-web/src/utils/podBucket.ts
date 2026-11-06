import type { K8sPod } from '@/types'

/**
 * Pod 状态分桶，与后端总览计数同一口径（k8s-core ClusterAggregationOperations.abnormalReason / countPhase）：
 *
 * 1. 先求「最严重的异常原因」：phase 为 Pending / Failed / Unknown 本身算一个原因；
 *    再看每个容器（含 init）**当前**状态的 reason —— waiting 的 CrashLoopBackOff / ImagePullBackOff / ErrImagePull、
 *    terminated 的 OOMKilled / Error（Completed 是正常退出，不算）；按严重度取最重的一个。
 * 2. 分桶：原因是 Pending → pending；没有原因且 phase=Running → running；没有原因且 Succeeded → succeeded；其余 → abnormal。
 *
 * 所以「Pending 但容器拉不下镜像」算异常（ImagePullBackOff 比 Pending 重），与总览卡片上的数一致。
 */
export type PodBucket = 'running' | 'pending' | 'succeeded' | 'abnormal'

const SEVERITY: Record<string, number> = {
  CrashLoopBackOff: 100,
  OOMKilled: 90,
  ImagePullBackOff: 80,
  ErrImagePull: 70,
  Error: 60,
  Failed: 50,
  Unknown: 40,
  Pending: 30,
}
const CONTAINER_REASONS = new Set(['CrashLoopBackOff', 'ImagePullBackOff', 'ErrImagePull', 'OOMKilled', 'Error'])

function abnormalReason(pod: K8sPod): string | null {
  let best: string | null = null
  const take = (r: string | null | undefined): void => {
    if (r && (SEVERITY[r] ?? 0) > (best ? SEVERITY[best] ?? 0 : 0)) best = r
  }
  const phase = pod.phase ?? null
  if (phase === 'Pending' || phase === 'Failed' || phase === 'Unknown') take(phase)
  for (const c of pod.containerDetails ?? []) {
    if (c.reason && CONTAINER_REASONS.has(c.reason)) take(c.reason)
  }
  return best
}

export function podBucket(pod: K8sPod): PodBucket {
  const reason = abnormalReason(pod)
  if (reason === 'Pending') return 'pending'
  if (reason == null && pod.phase === 'Running') return 'running'
  if (reason == null && pod.phase === 'Succeeded') return 'succeeded'
  return 'abnormal'
}

export const POD_BUCKET_OPTIONS: { value: PodBucket; label: string }[] = [
  { value: 'running', label: 'Running' },
  { value: 'pending', label: 'Pending' },
  { value: 'succeeded', label: '已完成' },
  { value: 'abnormal', label: '异常' },
]
