import { computed, ref, watch, type Ref } from 'vue'
import { clusterApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'
import type { K8sComponentFlag, K8sComponentStatus } from '@/types'

/**
 * 集群组件（S1.2）：功能旗标是 CI / 服务网格 / 网络 / HPA 四处门控的**单一出处**。
 *
 * <h2>两个投影，两个用途</h2>
 * <ul>
 *   <li>{@code flags}（{@code /cluster/component-flags}）—— 功能 code → 能不能用 + 原因。
 *       租户可读（{@code tenant:cluster:capability:view}），<b>不含版本与命名空间</b>。
 *       本 composable 挂载即加载它。</li>
 *   <li>{@code statuses}（{@code /cluster/components}）—— 全量（状态 / 版本 / 健康 / 命名空间）。
 *       仅平台管理员。**按需加载**（{@link loadStatuses}）：只有集群详情页的组件 tab 与概览摘要需要，
 *       别的门控页不该为它多打一次集群。</li>
 * </ul>
 *
 * <h2>权限位短路</h2>
 * 两个码都没有时不发注定被拒的请求，旗标保持空 = 「未探测」，各门禁按既有降级路径走
 * （同 {@code useClusterCapability}）。`/cluster/components` 还要额外要求 {@code platform:cluster:manage}。
 *
 * <p>返回普通对象（非 reactive）：嵌套 ref 不会被模板自动解包，消费方须显式写 {@code .value}
 * —— 漏掉会令门禁静默恒真/恒假（同 {@code useClusterCapability} 的坑）。
 */
export function useClusterComponents(clusterId: Ref<string | null | undefined>) {
  const perm = usePermission()

  const flags = ref<Record<string, K8sComponentFlag>>({})
  const statuses = ref<K8sComponentStatus[]>([])
  const loading = ref(false)
  const statusesLoading = ref(false)

  /** 读旗标所需权限（ANY-of，与后端权限表该端点的两行一一对应） */
  const canRead = () => perm.hasAny([apiCodes.clusterManage, apiCodes.clusterCapabilityView])
  /** 全量投影与「重新探测」都独占 platform:cluster:manage */
  const canManage = () => perm.has(apiCodes.clusterManage)

  /** 旗标是否已拿到（false = 未探测 —— 与"全部不可用"是两件事） */
  const probed = computed(() => Object.keys(flags.value).length > 0)

  async function load(): Promise<void> {
    const id = clusterId.value
    if (!id) { flags.value = {}; return }
    if (!canRead()) { flags.value = {}; return } // 无权限 → 按「未探测」降级，不发注定被拒的请求
    loading.value = true
    try {
      const next = await clusterApi.componentFlags(id)
      if (clusterId.value !== id) return // 加载期间切了集群 → 丢弃过期结果
      flags.value = next ?? {}
    } catch {
      if (clusterId.value !== id) return
      flags.value = {} // 拦截器已提示；按未探测降级，门禁走各自的既有降级路径
    } finally {
      if (clusterId.value === id) loading.value = false
    }
  }

  /** 全量状态（组件 tab / 概览摘要用）。不做自动加载 —— 见类注释。 */
  async function loadStatuses(): Promise<void> {
    const id = clusterId.value
    if (!id || !canManage()) { statuses.value = []; return }
    statusesLoading.value = true
    try {
      const next = await clusterApi.components(id)
      if (clusterId.value !== id) return
      statuses.value = next ?? []
    } catch {
      if (clusterId.value !== id) return
      statuses.value = [] // 拦截器已提示；面板据此显示「探测不到」
    } finally {
      if (clusterId.value === id) statusesLoading.value = false
    }
  }

  /** 重新探测：后端探完覆写缓存，然后回读两个投影 */
  async function refresh(): Promise<void> {
    const id = clusterId.value
    if (!id || !canManage()) return
    await clusterApi.refreshComponents(id)
    await Promise.all([load(), statuses.value.length ? loadStatuses() : Promise.resolve()])
  }

  function available(code: string): boolean { return flags.value[code]?.available ?? false }
  function reasonOf(code: string): string { return flags.value[code]?.reason ?? '' }
  function missingOf(code: string): string[] { return flags.value[code]?.missing ?? [] }
  function statusOf(code: string): K8sComponentStatus | undefined {
    return statuses.value.find((s) => s.code === code)
  }

  watch(clusterId, () => {
    statuses.value = [] // 换集群 → 全量投影作废（等下一次 loadStatuses）
    void load()
  }, { immediate: true })

  return {
    flags, statuses, loading, statusesLoading, probed,
    available, reasonOf, missingOf, statusOf,
    /** 是否有权读旗标（ANY-of） */
    canRead: computed(canRead),
    /** 全量投影与「重新探测」需要 platform:cluster:manage */
    canManage: computed(canManage),
    load, loadStatuses, refresh,
  }
}
