import { computed } from 'vue'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'

/**
 * 中间件页面的顶栏上下文（资源上下文 store 的租户 / 集群 / 命名空间）。
 *
 * <p>租户：**token 里有租户帽时以 token 为准**（`AuthContext.effectiveTenantId` 就是这么判的），
 * 这里会把顶栏级联的租户对齐到帽上，避免"级联选了 B、实际查的是 A"的错觉；
 * 平台视图（base token）下用级联里 ensureDefaults 落到的第一个可用租户，作为代管轨的 tenantId 发给后端。
 * 平台桶必须有主人 —— 创建 Milvus 的「平台对象存储」就依赖它。
 *
 * <p>集群/命名空间：页面不再自己维护一套「当前上下文」——统一跟随顶栏级联，
 * 页面上的集群下拉只是同一个值的另一个入口（写回 `setCluster`）。顶栏切换时列表才会跟着重查。
 */
export function useMiddlewareTenant() {
  const perm = usePermission()
  const { state, load, ensureDefaults, setCluster, setTenant, namespacesOfCluster } = useResourceContext()

  const tenantId = computed(() => state.tenantId ?? undefined)
  const clusterId = computed(() => state.clusterId ?? undefined)
  const namespace = computed(() => state.namespace ?? undefined)
  /** 顶栏级联里当前集群下可选的命名空间（列表页的命名空间筛选下拉用它） */
  const namespaces = computed(() => namespacesOfCluster.value)

  /** 顶栏上下文加载完再发第一轮请求：避免先用空租户查一次、再重查 */
  async function ensure(): Promise<void> {
    await load()
    // 租户帽优先（后端 effectiveTenantId 同规则）：级联跟着帽走，别让用户以为选别的租户生效
    const hat = perm.currentTenant?.tenantId
    if (hat && state.tenantId !== hat) {
      setTenant(hat)
    }
    ensureDefaults()
  }

  return { state, tenantId, clusterId, namespace, namespaces, setCluster, ensure }
}
