import { clusterApi, resourceContextApi } from '@/api'
import type { K8sCluster } from '@/types'

/**
 * 中间件页面的集群下拉数据源。
 *
 * <p>管理员走 `/cluster/list`（带 enabled 标记，可优先落在启用集群）；
 * 租户没有 `platform:cluster:manage`，`/cluster/list` 会 403 —— 改从 `/context` 的分配表里取，
 * 只保留能看见的集群（没有分配命名空间的集群不会出现）。
 */
export async function loadMiddlewareClusters(isAdmin: boolean): Promise<K8sCluster[]> {
  if (isAdmin) return (await clusterApi.list()) ?? []
  const ctx = await resourceContextApi.get()
  const seen = new Map<string, K8sCluster>()
  for (const t of ctx?.tenants ?? []) {
    for (const c of t.clusters ?? []) {
      if (!seen.has(c.clusterId)) {
        // /context 的集群节点只有 id + 名称，与 /cluster/list 的完整对象形状不同
        seen.set(c.clusterId, { clusterId: c.clusterId, clusterName: c.clusterName } as unknown as K8sCluster)
      }
    }
  }
  return [...seen.values()]
}
