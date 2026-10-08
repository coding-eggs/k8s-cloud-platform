import { computed, ref, watch, type Ref } from 'vue'
import { clusterApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'
import type { K8sClusterCapability } from '@/types'

/**
 * 集群 API 能力（metrics-server / prometheus-adapter / autoscaling 版本）派生门禁。
 * 入参 clusterId ref；clusterId 变化自动重读。空快照 = 未探测：不硬阻断，behavior 默认启用
 * （对齐后端 KubernetesOperationsFactory.buildHpa 无 capability 时默认 V2）。
 *
 * <p>behavior 默认 V2 仅指「未探测」态（cap 空）。若已探测但快照里没有 autoscaling 键
 * （buildHpa 判 V2 是三选一：列表空 / 无 autoscaling 键 / versions 空），本 composable 的
 * {@link hpaSupportsBehavior} 返回 false —— 真集群 discovery 必带 autoscaling group，
 * 故此边界不会在真实集群出现，此处从保守（探测过即严格按 versions 判定）。
 *
 * <p><b>权限位短路</b>：/cluster/capability/get 的码是 ANY-of（platform:cluster:manage 或
 * tenant:cluster:capability:view，见 V2026_10_07_2）。两个都没有时<b>不发请求</b>，cap 保持空
 * ——「未探测」态本身就是各门禁的既有降级路径，所以行为自然退化而非报错，
 * 也不会再让租户用户一进 HPA 页就吃 403 弹窗。
 *
 * <p>返回普通对象（非 reactive），嵌套 computed 不会被模板自动解包：消费方（含 template）
 * 必须显式写 {@code cap.probed.value} 等。漏掉 .value 会令门禁静默恒真/恒假。
 */
export function useClusterCapability(clusterId: Ref<string | null | undefined>) {
  const perm = usePermission()
  const cap = ref<K8sClusterCapability>({})
  const loading = ref(false)

  /** 读能力快照所需权限（ANY-of，与后端权限表的该端点多行一一对应） */
  const canRead = () => perm.hasAny([apiCodes.clusterManage, apiCodes.clusterCapabilityView])

  async function load(): Promise<void> {
    const id = clusterId.value
    if (!id) { cap.value = {}; return }
    if (!canRead()) { cap.value = {}; return } // 无权重 → 按"未探测"降级，不发注定被拒的请求
    loading.value = true
    try {
      const next = await clusterApi.getCapability(id)
      // 集群在加载期间已切换/清空 → 丢弃过期结果，等下一次 watch 触发（对齐 useResourceOptions 先例）
      if (clusterId.value !== id) return
      cap.value = next
    } catch {
      if (clusterId.value !== id) return
      cap.value = {} // 拦截器已提示；按未探测降级
    } finally {
      loading.value = false
    }
  }

  /** 手动触发后端重新 discovery 并回读（HPA 页「刷新能力」按钮用）。
   *  refresh 是写操作（真跑一次 discovery 并持久化），权限独占 platform:cluster:manage。 */
  async function refresh(): Promise<void> {
    if (!perm.has(apiCodes.clusterManage)) return
    const id = clusterId.value
    if (!id) return
    await clusterApi.refreshCapability(id)
    await load()
  }

  const probed = computed(() => Object.keys(cap.value).length > 0)
  const hasMetricsServer = computed(() => 'metrics.k8s.io' in cap.value)
  const hasCustomMetrics = computed(() => 'custom.metrics.k8s.io' in cap.value)
  const hasExternalMetrics = computed(() => 'external.metrics.k8s.io' in cap.value)
  /** 探测到 autoscaling 且无 v2 → false；未探测 → true（默认 V2） */
  const hpaSupportsBehavior = computed(() =>
    !probed.value ? true : (cap.value['autoscaling'] ?? []).includes('v2'))
  /** 页面级门禁：探测过且三类 metrics 全无 → 禁创建 */
  const metricsAvailable = computed(() =>
    !probed.value || hasMetricsServer.value || hasCustomMetrics.value || hasExternalMetrics.value)

  // ---- Calico（网络）能力门禁：纯 discovery，无活探测。未探测（cap 空）→ false（保守，可点「刷新能力」补探）----
  /** IPPool/BGP* 等 projectcalico.org 资源可用 → 允许创建；缺失则禁创建 + 横幅 */
  const hasCalico = computed(() => 'projectcalico.org' in cap.value)
  /** IPAM 派生视图另需 crd.projectcalico.org（ipamblocks）；缺失则派生段降级「—」 */
  const hasCalicoCrd = computed(() => 'crd.projectcalico.org' in cap.value)

  watch(clusterId, () => { void load() }, { immediate: true })

  return {
    cap, loading, probed, hasMetricsServer, hasCustomMetrics, hasExternalMetrics,
    hpaSupportsBehavior, metricsAvailable, hasCalico, hasCalicoCrd,
    /** 当前身份是否有权读能力快照（消费方可在无权限时隐藏/改写门禁提示，而不是让它静默恒真） */
    canRead: computed(() => canRead()),
    /** 当前身份是否有权刷新能力（写操作，仅平台管理员） */
    canRefresh: computed(() => perm.has(apiCodes.clusterManage)),
    load, refresh,
  }
}
