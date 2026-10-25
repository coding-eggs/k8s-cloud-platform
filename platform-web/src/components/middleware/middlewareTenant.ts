import { computed } from 'vue'
import { useResourceContext } from '@/stores/context'

/**
 * 中间件页面的租户作用域：**直接取顶栏（资源上下文）里的租户**。
 *
 * <p>管理员在平台视图没有租户帽，顶栏级联的 ensureDefaults 会落到第一个可用租户；
 * 这里只把它当作「代管轨」的 tenantId 发给后端（`AuthContext.effectiveTenantId`），**不改 token**。
 * 平台桶必须有主人 —— 创建 Milvus 的「平台对象存储」就依赖它；租户用户拿到的就是自己的租户（后端以 token 为准）。
 */
export function useMiddlewareTenant() {
  const { state, load, ensureDefaults } = useResourceContext()

  const tenantId = computed(() => state.tenantId ?? undefined)

  /** 顶栏上下文加载完再发第一轮请求：避免先用空租户查一次、再重查 */
  async function ensure(): Promise<void> {
    await load()
    ensureDefaults()
  }

  return { state, tenantId, ensure }
}
