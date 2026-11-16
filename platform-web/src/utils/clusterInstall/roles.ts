/**
 * 角色清理：`etcd` 角色只在「专用 etcd」模式下有意义。
 * 切走该模式（或导入时模式不是专用）时：
 * - 兼做控制面 / worker 的节点：只去掉 etcd 角色；
 * - **只做 etcd** 的节点：整行移除 —— 它们是专门为 etcd 加的机器，切走后不属于这个集群，
 *   留成 worker 会静默改变集群形态（多出几台工作节点），留成「无角色」又只会变成 hosts 里的孤儿主机。
 */
import type { InstallNode, NodeRole } from './types'

export interface RoleCleanup {
  nodes: InstallNode[]
  /** 被去掉 etcd 角色、但保留在表里的节点数 */
  stripped: number
  /** 整行移除的「仅 etcd」节点数 */
  dropped: number
}

export function cleanupEtcdRoles(nodes: InstallNode[], keepEtcdRole: boolean): RoleCleanup {
  if (keepEtcdRole) return { nodes, stripped: 0, dropped: 0 }

  const kept: InstallNode[] = []
  let stripped = 0
  let dropped = 0
  for (const n of nodes) {
    if (!n.roles.includes('etcd')) {
      kept.push(n)
      continue
    }
    const roles: NodeRole[] = n.roles.filter((r) => r !== 'etcd')
    if (roles.length === 0) {
      dropped += 1
      continue
    }
    stripped += 1
    kept.push({ ...n, roles })
  }
  return { nodes: kept, stripped, dropped }
}
