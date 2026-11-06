import { ElMessage } from 'element-plus'
import type { RouteLocationRaw, Router } from 'vue-router'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import { pageCodes } from '@/pageCodes'

/**
 * 先把顶栏上下文（租户 → 集群 → 命名空间）切到目标，再跳到列表页。
 * 总览卡片「点异常 4 → 跳到异常所在的命名空间」用：列表页都按顶栏上下文取数，不切就会看到别处的数据。
 *
 * - 租户：优先当前租户（它也有这个集群 / 命名空间时不换）；否则取上下文树里第一个有它的租户。
 *   这里只改资源页的**查询上下文**，不改 token 的租户帽（同 stores/context.ts ensureDefaults 的约定）；
 * - 只给集群（集群概览页）：命名空间沿用当前的（若属于该集群），否则落到该集群下第一个；
 * - 命名空间没有分配给任何租户（kube-system 这类平台系统 ns）→ 顶栏选不到它：
 *   有命名空间概览权限就打开它的命名空间概览，否则提示一句，原地不动。
 */
export interface JumpTarget {
  clusterId: string
  namespace?: string
}

export async function jumpWithContext(router: Router, target: JumpTarget, to: RouteLocationRaw): Promise<void> {
  const ctx = useResourceContext()
  await ctx.load()
  const fits = (t: (typeof ctx.state.tenants)[number]): boolean =>
    t.clusters.some((c) => c.clusterId === target.clusterId && (!target.namespace || c.namespaces.includes(target.namespace)))
  const tenant = (ctx.currentTenant.value && fits(ctx.currentTenant.value) ? ctx.currentTenant.value : null)
    ?? ctx.state.tenants.find(fits)
    ?? null

  if (!tenant) {
    const perm = usePermission()
    if (target.namespace && perm.has(pageCodes.namespace.detail)) {
      ElMessage.info(`命名空间 ${target.namespace} 未分配给任何租户，打开它的命名空间概览`)
      await router.push({ path: '/namespaces/detail', query: { clusterId: target.clusterId, name: target.namespace } })
    } else {
      ElMessage.warning(target.namespace
        ? `命名空间 ${target.namespace} 未分配给任何租户，顶栏无法切换过去`
        : '该集群没有分配给你可访问的租户，顶栏无法切换过去')
    }
    return
  }

  if (ctx.state.tenantId !== tenant.tenantId) ctx.setTenant(tenant.tenantId)
  if (ctx.state.clusterId !== target.clusterId) ctx.setCluster(target.clusterId)
  const namespaces = ctx.namespacesOfCluster.value
  const ns = target.namespace
    ?? (ctx.state.namespace && namespaces.includes(ctx.state.namespace) ? ctx.state.namespace : namespaces[0])
    ?? null
  if (ctx.state.namespace !== ns) ctx.setNamespace(ns)
  await router.push(to)
}
