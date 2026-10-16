/**
 * 把工作负载的 `spec.selector.matchLabels` 拼成 K8s labelSelector（等值集合 → `k=v,k2=v2`）。
 *
 * 列表页和详情页都要"按工作负载反查它的 Pod"，两边必须是同一条口径 ——
 * 尤其是 selector 缺失时退回的平台约定 `app=<工作负载名>`：各写一份的话，
 * 同一个工作负载会出现"列表页点开一个 Pod 都没有、进详情页却有一堆"的怪现象。
 */
export function podLabelSelector(
  sel: Record<string, string> | null | undefined,
  fallbackName: string,
): string {
  const entries = Object.entries(sel ?? {})
  if (entries.length) return entries.map(([k, v]) => `${k}=${v}`).join(',')
  return `app=${fallbackName}`
}
