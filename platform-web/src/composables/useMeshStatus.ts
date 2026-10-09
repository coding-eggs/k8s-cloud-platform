import { computed, ref, watch, type Ref } from 'vue'
import { meshApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'
import { useClusterCapability } from '@/composables/useClusterCapability'
import type { MeshStatus } from '@/types'

/**
 * 服务网格状态（B6）：模块横幅 + create 门禁的数据源。两层来源，按权限降级。
 *
 * <h2>为什么有两层</h2>
 * `/mesh/status` 是平台侧端点（k8s-server 声明 `AccessBoundary.PLATFORM`，api 侧码 `platform:cluster:manage`）——
 * 只有平台管理员调得到。但租户的 Gateway 页也要知道「本集群装没装 Gateway API」（否则无法决定 create 是否可用），
 * 而那份信息租户本来就有：`/cluster/capability/get` 对租户开放（`tenant:cluster:capability:view`，V2026_10_07_2）。
 *
 * - **有权读 /mesh/status** → 全量：Gateway API + Istio + **ambient（ztunnel 活探测，只有这条通路有）**
 * - **只有 capability 读权** → 从能力快照派生 Gateway API / Istio；**ambient 标记为未知**（不猜、也不显示"未安装"）
 * - **两者都无权** → 「未探测」态，hasGatewayApi=false（保守，与 useClusterCapability 一致）
 *
 * <h2>hasGatewayApi=false 的三种成因不可区分</h2>
 * 未安装 / 未探测 / 集群断开。故本 composable 只暴露 hasGatewayApi 与 probed，由横幅负责把措辞写成
 * 中性的「未探测到 / 未安装」并给「刷新能力」按钮，而不是武断地说"没装"。
 *
 * <p>返回普通对象（非 reactive）：嵌套 ref 不会被模板自动解包，消费方须显式写 `.value`
 * （同 useClusterCapability 的约定，见其类注释）。
 */
export function useMeshStatus(clusterId: Ref<string | null | undefined>) {
  const perm = usePermission()

  /** 平台侧全量状态的读取权（/mesh/status） */
  const canReadFull = computed(() => perm.has(apiCodes.clusterManage))
  /** 刷新能力（写操作，独占 platform:cluster:manage） */
  const canRefresh = computed(() => perm.has(apiCodes.clusterManage))

  // 能力快照：租户侧唯一可用的来源，也是"刷新能力"后的即时回读
  const cap = useClusterCapability(clusterId)

  const full = ref<MeshStatus | null>(null)
  const loading = ref(false)

  async function loadFull(): Promise<void> {
    const id = clusterId.value
    if (!id || !canReadFull.value) {
      full.value = null
      return
    }
    loading.value = true
    try {
      const next = await meshApi.status(id)
      if (clusterId.value !== id) return // 加载期间切了集群 → 丢弃
      full.value = next
    } catch {
      if (clusterId.value !== id) return
      full.value = null // 拦截器已提示；退化为 capability 派生（或未探测）
    } finally {
      loading.value = false
    }
  }

  watch([clusterId, canReadFull], () => { void loadFull() }, { immediate: true })

  // ---- 派生：优先用全量结果，否则退回能力快照 ----
  const gatewayApiVersions = computed<string[]>(() => {
    if (full.value) return full.value.gatewayApiVersions ?? []
    return cap.cap.value['gateway.networking.k8s.io'] ?? []
  })
  const hasGatewayApi = computed(() => (full.value ? full.value.hasGatewayApi : gatewayApiVersions.value.length > 0))
  const hasIstio = computed(() => {
    if (full.value) return full.value.hasIstio
    const c = cap.cap.value
    return 'istio.io' in c || 'networking.istio.io' in c
  })
  /** ambient 是否探测过：只有全量通路能给答案，能力快照里没有这个信息 */
  const ambientKnown = computed(() => full.value !== null)
  const istioAmbient = computed(() => full.value?.istioAmbient === true)
  /** 有没有任何来源的答案（决定横幅写"未探测"还是具体状态） */
  const probed = computed(() => full.value !== null || cap.probed.value)

  /**
   * 刷新能力：真跑一次 discovery 并持久化，然后回读。
   * `/mesh/status` 有意不加 TTL 缓存（见后端 MeshService 注释），故刷新后横幅立即反映新快照。
   */
  async function refresh(): Promise<void> {
    if (!canRefresh.value) return
    const id = clusterId.value
    if (!id) return
    await cap.refresh()
    await loadFull()
  }

  return {
    loading,
    canReadFull,
    canRefresh,
    gatewayApiVersions,
    hasGatewayApi,
    hasIstio,
    istioAmbient,
    ambientKnown,
    probed,
    load: loadFull,
    refresh,
  }
}
