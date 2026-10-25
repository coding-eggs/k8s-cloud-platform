import { computed } from 'vue'
import { useResourceContext } from '@/stores/context'

/**
 * 应用编排页面的顶栏上下文（与中间件页同一套：租户 / 集群都跟随顶栏级联）。
 *
 * <p>为什么租户要从顶栏取：管理员在平台视图（token 没有租户帽）创建应用时，
 * 后端 `effectiveTenantId` 拿不到租户就会拒绝；顶栏级联的 `ensureDefaults` 会把平台视图
 * 落到第一个可用租户，这里把它当作「代管轨」的 tenantId 发给后端，**不改 token**。
 */
export function useApplicationContext() {
  const { state, load, ensureDefaults, setCluster } = useResourceContext()

  const tenantId = computed(() => state.tenantId ?? undefined)
  const clusterId = computed(() => state.clusterId ?? undefined)

  /** 顶栏上下文加载完再发第一轮请求，避免先用空租户查一次、再重查 */
  async function ensure(): Promise<void> {
    await load()
    ensureDefaults()
  }

  return { state, tenantId, clusterId, setCluster, ensure }
}
