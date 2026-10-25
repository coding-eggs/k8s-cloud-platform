import { computed } from 'vue'
import { useResourceContext } from '@/stores/context'

/**
 * 中间件页面的顶栏上下文（资源上下文 store 的租户 / 集群）。
 *
 * <p>租户：管理员在平台视图没有租户帽，顶栏级联的 ensureDefaults 会落到第一个可用租户；
 * 这里只把它当作「代管轨」的 tenantId 发给后端（`AuthContext.effectiveTenantId`），**不改 token**。
 * 平台桶必须有主人 —— 创建 Milvus 的「平台对象存储」就依赖它。
 *
 * <p>集群：中间件按集群隔离，页面不再自己维护一套「当前集群」——统一跟随顶栏级联，
 * 页面上的集群下拉只是同一个值的另一个入口（写回 `setCluster`）。这样顶栏切集群/租户时列表才会跟着重查。
 */
export function useMiddlewareTenant() {
  const { state, load, ensureDefaults, setCluster } = useResourceContext()

  const tenantId = computed(() => state.tenantId ?? undefined)
  const clusterId = computed(() => state.clusterId ?? undefined)
  const namespace = computed(() => state.namespace ?? undefined)

  /** 顶栏上下文加载完再发第一轮请求：避免先用空租户查一次、再重查 */
  async function ensure(): Promise<void> {
    await load()
    ensureDefaults()
  }

  return { state, tenantId, clusterId, namespace, setCluster, ensure }
}
