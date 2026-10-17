<script setup lang="ts">
/** 集群概览（B4 Phase 2）：一页四 tab —— 概览（首屏重点是健康/异常）/ 资源用量 / 资源明细 / 工作负载。
 *
 * 数据面两条腿（spec §5.4 双平面）：
 *  - 结构口径（allocatable / requests / 计数 / 存储 / 配额）← /cluster/overview（后端一次聚合快照）
 *  - 实时口径（used / 曲线）← Thanos（/cluster/metrics/**）
 *
 * 降级铁律：聚合段为 null 表示「读不到」（集群未连接/超时），**不是 0** —— 一律显示「—」。
 * 基本信息走 /cluster/get（只读 DB），集群断开也照常可见。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { calicoApi, clusterApi, namespaceApi } from '@/api'
import { clusterMetrics, type ClusterMetricsReq } from '@/api/metrics'
import type {
  K8sAbnormalPod, K8sCluster, K8sClusterOverview, K8sNamespaceStatEntry, K8sNodeHealth,
  K8sQuotaState, SecretRefOption,
} from '@/types'
import type { MetricSeriesResponse } from '@/types/metrics'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import ClusterMetricsPanel from '@/components/cluster/ClusterMetricsPanel.vue'
import ClusterComponentsPanel from '@/components/cluster/ClusterComponentsPanel.vue'
import { useClusterComponents } from '@/composables/useClusterComponents'
import { useComponentOperations } from '@/composables/useComponentOperations'
import { fmtAge, fmtDate, podPhaseType } from '@/utils/format'
import { formatMetricValue, humanizeBytes } from '@/utils/metrics'

const route = useRoute()
const router = useRouter()

// ---- 上下文：clusterId 来自 query（从集群列表点名称/「查看」进来）----
const clusterId = ref((route.query.clusterId as string) || '')
const cluster = ref<K8sCluster | null>(null)
const overview = ref<K8sClusterOverview | null>(null)
const loading = ref(false)
const loadError = ref(false)
const overviewError = ref(false)
const metricsRef = ref<InstanceType<typeof ClusterMetricsPanel> | null>(null)

// ---- 集群组件（S1.2）：组件 tab 与概览摘要共用同一份全量投影 ----
// 页面本身由 platform:page:cluster 门禁（管理员），组件 tab 不另要页面码。
const components = useClusterComponents(computed(() => clusterId.value || null))
// 组件安装/升级（S3.6）：操作记录 + 轮询。能不能装由后端下发的 availableVersions 决定，前端不硬编码清单
const operations = useComponentOperations(computed(() => clusterId.value || null))
/** 正在执行的组件（按钮 loading / 禁用） */
const applying = ref<string | null>(null)

/** Thanos 安装表单的命名空间候选 */
async function loadComponentNamespaces(): Promise<string[]> {
  const id = clusterId.value
  if (!id) return []
  const list = await namespaceApi.list(id)
  return (list ?? []).map((n) => n.name)
}

/**
 * Thanos 表单的 Secret 候选。
 * 复用 calico 的 form-options/secrets —— 同一个权限码（platform:cluster:manage），
 * 只返回 name + data keys，不下发任何凭证内容。
 */
async function loadComponentSecrets(namespace: string): Promise<SecretRefOption[]> {
  const id = clusterId.value
  if (!id || !namespace) return []
  return await calicoApi.secretOptions(id, namespace)
}

/** 安装/升级：确认 → 发起 → 轮询 → 成功后刷新探测 */
async function onApply(p: {
  code: string
  action: 'INSTALL' | 'UPGRADE'
  targetVersion?: string
  editableValues?: Record<string, unknown>
  alertWebhookUrl?: string
  thanosReceiveUrl?: string
  thanosInstall?: import('@/types').K8sThanosInstall
  calicoInstall?: import('@/types').K8sCalicoInstall
}): Promise<void> {
  const target = components.statusOf(p.code)
  try {
    await ElMessageBox.confirm(
      `将${p.action === 'INSTALL' ? '安装' : '升级'} ${target?.name ?? p.code}。
`
      + `release 与命名空间由平台固定，chart 版本 ${p.targetVersion ?? '平台默认版本'}。
`
      // 网络组件是最高风险档：CIDR/封装写错会直接断网
      + (p.code === 'calico'
        ? '⚠️ 这是网络组件：CIDR / 封装方式与集群现状不一致会立即中断集群网络。\n'
        : '')
      + '过程会向集群写入对象，请确认。',
      '确认操作',
      { type: p.code === 'calico' ? 'error' : 'warning' },
    )
  } catch {
    return
  }
  applying.value = p.code
  try {
    const id = await operations.apply(p.code, p.action, p.targetVersion, p.editableValues,
      p.alertWebhookUrl, p.thanosReceiveUrl, p.thanosInstall, p.calicoInstall)
    const op = await operations.wait(id)
    if (op.status === 'SUCCESS') {
      ElMessage.success('操作完成，正在刷新探测…')
      await components.refresh()
    } else {
      ElMessage.error(op.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    applying.value = null
  }
}

/** 概览里的 Istio/Calico 显示**实测版本**；人工登记值降为 tooltip（spec §6.2 ②） */
function probedVersionOf(code: string): string | null {
  return components.statusOf(code)?.version ?? null
}
function registeredVersionTip(code: string, registered?: string | null): string {
  const s = components.statusOf(code)
  // 已确认没装：此时显示的是登记值，措辞不能是"尚未探测到"
  if (s?.state === 'NOT_INSTALLED') return '探测确认未安装 —— 这里显示的是登记值'
  const probed = s?.version ?? null
  if (!probed) return '尚未探测到实测版本，暂显示登记值'
  if (registered && normVersion(registered) !== normVersion(probed)) {
    return `登记值 ${registered} 与实测 ${probed} 不一致（升级过集群？）`
  }
  return registered ? `登记值 ${registered}` : '登记时未填写'
}
/** 比较版本时忽略前导 v（实测 tag 是 v3.30.3，登记值常写 3.30.3）—— 否则 tooltip 会稳定误报不一致 */
function normVersion(v: string): string {
  return v.trim().replace(/^v/i, '')
}
/** 概览一行摘要（spec §6.2 ①）：与组件 tab 同一个数据源，不再各说一套 */
const overviewComponentSummary = computed(() => {
  const all = components.statuses.value
  if (!all.length) return components.statusesLoading.value ? '正在探测…' : '尚未探测'
  const missing = all.filter((s) => s.state === 'NOT_INSTALLED').length
  const unknown = all.filter((s) => s.state === 'UNKNOWN').length
  return unknown
    ? `${all.length} 个组件，${missing} 个缺失，${unknown} 个未探测`
    : `${all.length} 个组件，${missing} 个缺失`
})

/**
 * 两段分开取、分开降级（spec §7 的落点）：
 *  - 基本信息（DB）是页面锚点 —— 它失败才是真的「加载失败」；
 *  - 概览快照（聚合，慢且可能超时/失败）单独降级 —— 它挂掉不该把基本信息一起藏掉。
 * 原先用 Promise.all 一锅端：概览一失败整页就变「加载失败」，把「集群断开时基本信息可见」这条承诺也一起丢了。
 */
async function refresh(): Promise<void> {
  if (!clusterId.value) return
  const target = clusterId.value
  loading.value = true
  loadError.value = false
  overviewError.value = false
  overview.value = null // 刷新期间不留旧快照：宁可显示「—」，也不显示上一轮的数
  try {
    cluster.value = await clusterApi.get(target)
  } catch {
    if (target === clusterId.value) loadError.value = true
    loading.value = false
    return
  }
  try {
    const o = await clusterApi.overview(target)
    if (target !== clusterId.value) return // 期间切了集群：丢弃这次结果
    overview.value = o
    breakdownLoaded.value = false // 聚合快照换了 → 懒加载的明细作废，切回该 tab 时重拉
  } catch {
    if (target === clusterId.value) overviewError.value = true
  } finally {
    loading.value = false
  }
  // 组件全量投影（概览摘要 + 组件 tab 共用）：失败时面板自己显示「探测不到」，不影响其它段
  void components.loadStatuses()
}

async function refreshAll(): Promise<void> {
  await refresh()
  metricsRef.value?.refresh()
}

function goBack(): void {
  router.push('/clusters')
}

// ---- 降级 / 离线判定 ----
/**
 * 聚合段不可用。三种成因合一：概览接口失败（overview == null）/ 集群断开 / 聚合超时（resourceTotal == null）。
 * ⚠️ **必须把 `overview == null` 算进来**：漏了它时首屏会把「读不到」渲染成「无异常 Pod」「无副本不足」
 * —— 与本节存在的意义正好相反（读不到 ≠ 一切正常）。
 */
const aggregateUnavailable = computed(() => overview.value == null || overview.value.resourceTotal == null)
/** 集群未连接/禁用 → 顶部提示（基本信息仍可见） */
const offlineReason = computed(() => {
  const c = cluster.value
  if (!c) return ''
  if (c.enabled !== 1) return '集群已禁用'
  if (c.status === 'ERROR') return '集群连接错误'
  if (c.status === 'DISCONNECTED') return '集群未连接'
  return ''
})

// ---- Tab 1：健康与异常（首屏重点）----
const nodeSummary = computed(() => overview.value?.nodeSummary ?? null)
const unhealthyNodes = computed(() => overview.value?.unhealthyNodes ?? [])
const pendingPvc = computed(() => overview.value?.storage?.pvcPending ?? null)
const abnormalTotal = computed(() => overview.value?.abnormalPodTotal ?? null)
/** reason 分组计数：由后端给（**全量**口径，不受明细 200 条封顶影响），顺序已按严重度排好 */
const reasonGroups = computed(() =>
  Object.entries(overview.value?.abnormalPodReasonCounts ?? {}).map(([reason, count]) => ({ reason, count })),
)
/** 明细被截断（总数 > 清单长度）→ 弹窗与清单都要提示「仅显示前 N 条」 */
const abnormalCapped = computed(() => (overview.value?.abnormalPodTotal ?? 0) > (overview.value?.abnormalPods?.length ?? 0))
const unhealthyWorkloads = computed(() => overview.value?.unhealthyWorkloads ?? [])
const unhealthyWorkloadTotal = computed(() => overview.value?.unhealthyWorkloadTotal ?? null)

function nodeTip(n: K8sNodeHealth): string {
  const parts: string[] = [n.ready ? 'Ready' : 'NotReady']
  if (n.pressures?.length) parts.push(n.pressures.join(' / '))
  if (n.version) parts.push(n.version)
  return parts.join(' · ')
}

/** 异常 Pod 弹窗：按 reason 过滤（分组计数是全量的，弹窗里只有封顶后的那 200 条） */
const podDialogVisible = ref(false)
const podDialogReason = ref('')
const podDialogRows = computed(() =>
  (overview.value?.abnormalPods ?? []).filter((p) => (p.reason ?? 'Unknown') === podDialogReason.value),
)
function openReason(reason: string): void {
  podDialogReason.value = reason
  podDialogVisible.value = true
}
function goPod(row: K8sAbnormalPod): void {
  const ns = row.namespace ? `&namespace=${encodeURIComponent(row.namespace)}` : ''
  router.push(`/resources/pods/detail?name=${encodeURIComponent(row.name ?? '')}${ns}`)
}
function ageText(seconds?: number | null): string {
  return seconds == null ? '—' : fmtAge(Date.now() - seconds * 1000)
}

// ---- Tab 3：资源明细（懒加载）----
const activeTab = ref('overview')
const breakdown = ref<K8sNamespaceStatEntry[] | null>(null)
const breakdownLoading = ref(false)
const breakdownLoaded = ref(false)
/**
 * 各命名空间的内存用量（Thanos by-namespace 的当前值）。
 * **只取内存、不取 CPU**（2026-10-10 用户定）：CPU 是尖峰型指标，单点值没有代表性 —— 拿它排 Top-10 会让
 * 排名随采样时刻乱跳；内存是 working set（gauge），单点稳定、可比。CPU 的实时形状留给曲线看板（后续做）。
 */
const usedByNsMem = ref<Record<string, number>>({})
const nsKeyword = ref('')

/** 一条指标响应 → { 命名空间: 当前值 }（by(namespace) 的 legend 就是命名空间名） */
function lastValueOf(resp: MetricSeriesResponse | null): Record<string, number> {
  const out: Record<string, number> = {}
  for (const s of resp?.series ?? []) {
    const points = s.points ?? []
    const last = points[points.length - 1]
    if (s.legend && last) out[s.legend] = last.value
  }
  return out
}

async function loadBreakdown(): Promise<void> {
  if (!clusterId.value) return
  breakdownLoading.value = true
  const end = Math.floor(Date.now() / 1000)
  const req: ClusterMetricsReq = { clusterId: clusterId.value, start: end - 15 * 60, end }
  try {
    const [rows, mem] = await Promise.all([
      clusterApi.resourceBreakdown(clusterId.value),
      // 用量只取当前值：Thanos 不可用不该让整表失败（该列显示「—」）
      clusterMetrics.memoryByNamespace(req).catch(() => null),
    ])
    breakdown.value = rows
    usedByNsMem.value = lastValueOf(mem)
  } catch {
    breakdown.value = null
  } finally {
    breakdownLoading.value = false
    breakdownLoaded.value = true
  }
}

/** 前端搜索（命名空间名） */
const filteredBreakdown = computed(() => {
  const rows = breakdown.value ?? []
  const kw = nsKeyword.value.trim().toLowerCase()
  return kw ? rows.filter((r) => r.snapshot.stat.namespace.toLowerCase().includes(kw)) : rows
})

/**
 * Top-10：按**内存**用量排，只认实测值。
 * <p>口径注：原定按 CPU（§2），2026-10-10 用户改为内存 —— CPU 尖峰型，单点排名会随采样时刻乱跳；
 * 内存是 working set，单点稳定可比。**2026-10-11 用户要求去掉「无指标回退 requests」**：用 requests 顶替会把
 * 「申领大、实际小」的命名空间排到前面，那正是这个榜要避免的误导 —— 没有实测值的照常列出（不是藏起来）、
 * 排在末位、值显示「—」。
 */
const topNamespaces = computed(() => {
  const rows = (breakdown.value ?? []).map((r) => ({
    namespace: r.snapshot.stat.namespace,
    value: usedByNsMem.value[r.snapshot.stat.namespace] ?? null,
  }))
  const sorted = [...rows]
    .sort((a, b) => {
      if (a.value == null && b.value == null) return a.namespace.localeCompare(b.namespace)
      if (a.value == null) return 1 // 无实测值的沉最后
      if (b.value == null) return -1
      return b.value - a.value
    })
    .slice(0, 10)
  const max = sorted.reduce((m, r) => Math.max(m, r.value ?? 0), 0)
  return sorted.map((r) => ({ ...r, pct: r.value != null && max > 0 ? (r.value / max) * 100 : 0 }))
})

function goNamespace(ns: string): void {
  router.push(`/namespaces/detail?clusterId=${encodeURIComponent(clusterId.value)}&name=${encodeURIComponent(ns)}`)
}

// 懒加载：进「资源明细」tab 才拉（大集群上这是最重的一段）
watch(activeTab, (tab) => {
  if (tab === 'breakdown' && !breakdownLoaded.value) void loadBreakdown()
})
watch(clusterId, () => {
  breakdownLoaded.value = false
  breakdown.value = null
  if (clusterId.value) void refresh()
})

onMounted(() => {
  if (clusterId.value) void refresh()
})

// ---- 展示小工具（null → 「—」，绝不显示 0）----
function fmtCpu(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '核', false)
}
function fmtMem(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '字节', false)
}
function fmtBytes(v?: number | null): string {
  return v == null ? '—' : humanizeBytes(v)
}
function fmtCount(v?: number | null): string {
  return v == null ? '—' : String(v)
}

/**
 * 用量列的排序键：**只按这一列显示的值**排（无指标记 -1 → 降序时沉到最后）。
 * 刻意不回退 requests：那一列已经不显示了，用一个看不见的字段排序会让人看不懂顺序。
 * （无实测值的行记 -1 → 降序沉底；Top-10 同口径，见 `topNamespaces`。）
 */
function usageSortKey(row: K8sNamespaceStatEntry, usedMap: Record<string, number>): number {
  return usedMap[row.snapshot.stat.namespace] ?? -1
}

/**
 * 配额三档徽标的展示映射（**与租户概览面板 TenantOverviewPanel.quotaBadge 同一套措辞/标签类型**）：
 * OK 不显示（信息由占用条承载）；MISSING / PARTIAL 两档标异常。
 * 判定**读后端 quotaState**（判定集中在 platform-api 的 QuotaClassifier），前端不自行按 hard 是否为空判定。
 */
function quotaBadge(state: K8sQuotaState): { text: string; type: 'danger' | 'warning' } | null {
  if (state === 'MISSING') return { text: '⚠ 无配额', type: 'danger' }
  if (state === 'PARTIAL') return { text: '⚠ 配额未覆盖 requests', type: 'warning' }
  return null
}
</script>

<template>
  <div v-loading="loading" class="cluster-page">
    <PageHeader :title="cluster ? `集群概览 · ${cluster.clusterName}` : '集群概览'">
      <span class="refresh-bar">
        <el-tooltip content="立即刷新" placement="bottom" :show-after="100">
          <el-button :icon="Refresh" circle size="small" :disabled="!clusterId" @click="refreshAll" />
        </el-tooltip>
      </span>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="!clusterId" title="未指定集群" description="请从集群列表点集群名称或「查看」进入。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <EmptyState v-else-if="loadError" title="加载失败" description="集群可能已被删除，或所选集群不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else-if="cluster" class="cluster-detail panel">
      <div v-if="offlineReason" class="offline-notice">
        {{ offlineReason }}，实时数据不可用；基本信息与能力摘要来自平台库，照常可见。
      </div>
      <div v-if="overviewError" class="offline-notice">
        概览数据加载失败（聚合超时或集群未响应）—— 基本信息与能力摘要不受影响，可点右上角刷新重试。
      </div>

      <el-tabs v-model="activeTab">
        <!-- ===== Tab 1：概览（首屏重点是健康/异常）===== -->
        <el-tab-pane label="概览" name="overview">
          <section class="health-card">
            <h3 class="section-title">健康与异常</h3>

            <div v-if="aggregateUnavailable" class="muted notice">
              集群未连接或聚合不可用，本段数据暂不可用（显示「—」）。
            </div>
            <template v-else>
              <div class="stat-grid">
                <div class="stat-tile">
                  <div class="stat-value">{{ nodeSummary ? `${nodeSummary.ready} / ${nodeSummary.total}` : '—' }}</div>
                  <div class="stat-label">Ready / 总节点</div>
                </div>
                <div class="stat-tile" :class="{ 'tile-bad': (nodeSummary?.notReady ?? 0) > 0 }">
                  <div class="stat-value">{{ fmtCount(nodeSummary?.notReady) }}</div>
                  <div class="stat-label">NotReady 节点</div>
                </div>
                <div class="stat-tile" :class="{ 'tile-bad': (abnormalTotal ?? 0) > 0 }">
                  <div class="stat-value">{{ fmtCount(abnormalTotal) }}</div>
                  <div class="stat-label">异常 Pod</div>
                </div>
                <div class="stat-tile" :class="{ 'tile-bad': (unhealthyWorkloadTotal ?? 0) > 0 }">
                  <div class="stat-value">{{ fmtCount(unhealthyWorkloadTotal) }}</div>
                  <div class="stat-label">副本不足的工作负载</div>
                </div>
                <div class="stat-tile" :class="{ 'tile-warn': (pendingPvc ?? 0) > 0 }">
                  <div class="stat-value">{{ fmtCount(pendingPvc) }}</div>
                  <div class="stat-label">Pending PVC</div>
                </div>
              </div>

              <div class="hv-row">
                <span class="hv-k">需要关注的节点</span>
                <span class="hv-v">
                  <template v-if="unhealthyNodes.length">
                    <el-tooltip v-for="n in unhealthyNodes" :key="n.name ?? ''" :content="nodeTip(n)" placement="top">
                      <el-tag size="small" :type="n.ready ? 'warning' : 'danger'" class="hv-tag">{{ n.name }}</el-tag>
                    </el-tooltip>
                  </template>
                  <span v-else class="muted">无（全部 Ready 且无 pressure）</span>
                </span>
              </div>

              <div class="hv-row">
                <span class="hv-k">异常 Pod</span>
                <span class="hv-v">
                  <template v-if="reasonGroups.length">
                    <el-tag
                      v-for="g in reasonGroups"
                      :key="g.reason"
                      size="small"
                      type="danger"
                      class="hv-tag clickable"
                      @click="openReason(g.reason)"
                    >
                      {{ g.reason }} {{ g.count }}
                    </el-tag>
                    <span v-if="abnormalCapped" class="muted">
                      （明细仅显示前 {{ overview?.abnormalPods?.length ?? 0 }} 条）
                    </span>
                  </template>
                  <span v-else class="muted">无</span>
                </span>
              </div>

              <div class="hv-row">
                <span class="hv-k">副本不足</span>
                <span class="hv-v">
                  <template v-if="unhealthyWorkloads.length">
                    <el-tag v-for="w in unhealthyWorkloads.slice(0, 12)" :key="`${w.kind}/${w.namespace}/${w.name}`" size="small" type="warning" class="hv-tag">
                      {{ w.namespace }}/{{ w.name }} {{ w.readyReplicas }}/{{ w.replicas }}
                    </el-tag>
                    <span v-if="unhealthyWorkloads.length > 12" class="muted">…共 {{ unhealthyWorkloadTotal }} 个（见「工作负载」tab）</span>
                  </template>
                  <span v-else class="muted">无（全部工作负载副本齐）</span>
                </span>
              </div>
            </template>
          </section>

          <div class="overview-grid">
            <aside class="ov-side">
              <section class="info-card">
                <h3 class="section-title">基本信息</h3>
                <div class="info-rows">
                  <div class="info-row"><span class="k">集群名称</span><span class="v">{{ cluster.clusterName }}</span></div>
                  <div class="info-row">
                    <span class="k">状态</span>
                    <StatusBadgeTip
                      :label="cluster.status === 'CONNECTED' ? '已连接' : (cluster.status ?? '未知')"
                      :type="cluster.status === 'CONNECTED' ? 'success' : (cluster.status === 'ERROR' ? 'danger' : 'info')"
                    />
                  </div>
                  <div class="info-row"><span class="k">启用</span><el-tag size="small" :type="cluster.enabled === 1 ? 'success' : 'info'">{{ cluster.enabled === 1 ? '已启用' : '已禁用' }}</el-tag></div>
                  <div class="info-row"><span class="k">集群版本</span><code class="mono v">{{ cluster.version ?? '—' }}</code></div>
                  <div class="info-row"><span class="k">容器运行时</span><code class="mono v">{{ cluster.containerRuntime ?? '—' }}</code></div>
                  <div class="info-row"><span class="k">IP 栈</span><span class="v">{{ cluster.ipStack ?? '—' }}</span></div>
                  <div class="info-row"><span class="k">描述</span><span class="v">{{ cluster.description ?? '—' }}</span></div>
                  <div class="info-row"><span class="k">Prometheus</span><span class="v">{{ cluster.prometheusUrl ?? '—' }}</span></div>
                  <div class="info-row"><span class="k">Grafana</span><span class="v">{{ cluster.grafanaUrl ?? '—' }}</span></div>
                  <div class="info-row"><span class="k">最后心跳</span><span class="v">{{ fmtDate(cluster.lastHeartbeatTime) }}</span></div>
                  <div class="info-row"><span class="k">创建时间</span><span class="v">{{ fmtDate(cluster.createdAt) }}</span></div>
                </div>
              </section>
            </aside>

            <main class="ov-main">
              <section class="info-card">
                <h3 class="section-title">集群组件</h3>
                <div class="info-rows">
                  <!-- 版本显示**实测值**；人工登记值降为 tooltip（spec §6.2 ②）。
                       同一个 Istio 出现两个版本号比不做这个 tab 更糟。 -->
                  <div class="info-row">
                    <span class="k">Istio</span>
                    <el-tooltip :content="registeredVersionTip('istio', cluster.istioVersion)" placement="top">
                      <code class="mono v">{{ probedVersionOf('istio') ?? cluster.istioVersion ?? '—' }}</code>
                    </el-tooltip>
                  </div>
                  <div class="info-row">
                    <span class="k">Calico</span>
                    <el-tooltip :content="registeredVersionTip('calico', cluster.calicoVersion)" placement="top">
                      <code class="mono v">{{ probedVersionOf('calico') ?? cluster.calicoVersion ?? '—' }}</code>
                    </el-tooltip>
                  </div>
                  <!-- 7 个能力 tag 收敛成一行摘要 + 跳转（spec §6.2 ①）：同一个事实不再两个渲染点 -->
                  <div class="info-row col-row">
                    <span class="k">组件</span>
                    <span class="v">
                      {{ overviewComponentSummary }}
                      <el-button link type="primary" @click="activeTab = 'components'">查看详情</el-button>
                    </span>
                  </div>
                </div>
              </section>
            </main>
          </div>
        </el-tab-pane>

        <!-- ===== Tab 2：资源用量（三口径 + 曲线 + 存储）===== -->
        <!-- lazy：首屏（概览 tab）不需要曲线，别一进页面就打两次 Thanos 区间查询；
             首次切入才挂载，之后常驻（来回切 tab 不会重复请求） -->
        <el-tab-pane label="资源用量" name="usage" lazy>
          <div v-if="aggregateUnavailable" class="muted notice">
            聚合不可用：allocatable / requests 显示「—」（无数据 ≠ 0）；used 与曲线来自 Thanos，不受影响。
          </div>
          <ClusterMetricsPanel
            ref="metricsRef"
            :cluster-id="clusterId"
            :capacity="overview?.resourceCapacity ?? null"
            :total="overview?.resourceTotal ?? null"
          />

          <section class="info-card mt-16">
            <h3 class="section-title">存储</h3>
            <div v-if="aggregateUnavailable" class="muted notice">聚合不可用，本段数据暂不可用（显示「—」）。</div>
            <div v-else class="stat-grid">
              <div class="stat-tile">
                <div class="stat-value">{{ fmtCount(overview?.storage?.pvCount) }}</div>
                <div class="stat-label">PV 数量</div>
              </div>
              <div class="stat-tile">
                <div class="stat-value">{{ fmtBytes(overview?.storage?.pvCapacityBytes) }}</div>
                <div class="stat-label">PV 容量合计</div>
              </div>
              <div class="stat-tile">
                <div class="stat-value">{{ fmtBytes(overview?.storage?.pvBoundBytes) }}</div>
                <div class="stat-label">已绑定 PV 容量</div>
              </div>
              <div class="stat-tile">
                <div class="stat-value">{{ fmtCount(overview?.storage?.pvcBound) }}</div>
                <div class="stat-label">PVC Bound</div>
              </div>
              <div class="stat-tile" :class="{ 'tile-bad': (overview?.storage?.pvcPending ?? 0) > 0 }">
                <div class="stat-value">{{ fmtCount(overview?.storage?.pvcPending) }}</div>
                <div class="stat-label">PVC Pending</div>
              </div>
              <div class="stat-tile" :class="{ 'tile-bad': (overview?.storage?.pvcLost ?? 0) > 0 }">
                <div class="stat-value">{{ fmtCount(overview?.storage?.pvcLost) }}</div>
                <div class="stat-label">PVC Lost</div>
              </div>
            </div>
            <div v-if="!aggregateUnavailable && (overview?.storage?.pvcPending ?? 0) > 0" class="pending-hint">
              Pending PVC 意味着工作负载会卡在 ContainerCreating，建议在「存储」页查看具体 PVC。
            </div>
          </section>
        </el-tab-pane>

        <!-- ===== Tab 3：资源明细（懒加载）===== -->
        <el-tab-pane label="资源明细" name="breakdown">
          <!-- 三态要用 breakdownLoaded 区分：「还没拉」不能显示成「不可用」——那是两个完全不同的结论 -->
          <div v-if="!breakdownLoaded || breakdownLoading" class="muted notice">加载中…</div>
          <div v-else-if="!breakdown" class="muted notice">
            资源明细不可用（集群未连接或聚合超时）——本段显示「—」。
          </div>
          <template v-else>
            <section class="info-card">
              <h3 class="section-title">Top-10 命名空间（按内存用量）</h3>
              <div v-if="!topNamespaces.length" class="muted">暂无数据</div>
              <div v-else class="top-list">
                <div v-for="t in topNamespaces" :key="t.namespace" class="top-row">
                  <code class="res-name name-link top-ns" @click="goNamespace(t.namespace)">{{ t.namespace }}</code>
                  <span class="top-bar"><span class="top-bar-fill" :style="{ width: `${t.pct}%` }" /></span>
                  <span class="top-val mono">{{ fmtMem(t.value) }}</span>
                </div>
              </div>
            </section>

            <section class="info-card mt-16">
              <div class="list-head">
                <h3 class="section-title">全部命名空间（{{ filteredBreakdown.length }}）</h3>
                <el-input v-model="nsKeyword" placeholder="按命名空间搜索…" clearable :prefix-icon="Search" class="search-input" size="small" />
              </div>
              <el-table :data="filteredBreakdown" stripe max-height="460">
                <el-table-column label="命名空间" width="350" sortable :sort-method="(a: K8sNamespaceStatEntry, b: K8sNamespaceStatEntry) => a.snapshot.stat.namespace.localeCompare(b.snapshot.stat.namespace)">
                  <template #default="{ row }">
                    <code class="res-name name-link" @click="goNamespace(row.snapshot.stat.namespace)">{{ row.snapshot.stat.namespace }}</code>
                  </template>
                </el-table-column>
                <el-table-column prop="snapshot.stat.podCount" label="Pod" min-width="100" sortable />
                <el-table-column prop="snapshot.stat.deployCount" label="Deploy" min-width="100" sortable />
                <el-table-column prop="snapshot.stat.stsCount" label="Sts" min-width="100" sortable />
                <el-table-column prop="snapshot.stat.dsCount" label="Ds" min-width="100" sortable />
                <el-table-column prop="snapshot.stat.svcCount" label="Svc" min-width="100" sortable />
                <el-table-column
                  label="内存用量"
                  min-width="170"
                  sortable
                  :sort-method="(a: K8sNamespaceStatEntry, b: K8sNamespaceStatEntry) => usageSortKey(a, usedByNsMem) - usageSortKey(b, usedByNsMem)"
                >
                  <template #default="{ row }">
                    <span class="mono">{{ fmtMem(usedByNsMem[row.snapshot.stat.namespace]) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="配额 CPU（used / hard）" min-width="200">
                  <template #default="{ row }">
                    <span v-if="row.snapshot.stat.quotaCpuHard == null && row.snapshot.stat.quotaCpuUsed == null" class="muted">—</span>
                    <span v-else class="mono">{{ fmtCpu(row.snapshot.stat.quotaCpuUsed) }} / {{ fmtCpu(row.snapshot.stat.quotaCpuHard) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="配额内存（used / hard）" min-width="200">
                  <template #default="{ row }">
                    <span v-if="row.snapshot.stat.quotaMemHard == null && row.snapshot.stat.quotaMemUsed == null" class="muted">—</span>
                    <span v-else class="mono">{{ fmtMem(row.snapshot.stat.quotaMemUsed) }} / {{ fmtMem(row.snapshot.stat.quotaMemHard) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="配额状态" min-width="180">
                  <template #default="{ row }">
                    <!-- OK 不显示（只标异常两档）；判定读后端 row.quotaState，前端不自行按 hard 是否为空判定 -->
                    <el-tag v-if="quotaBadge(row.quotaState)" size="small" :type="quotaBadge(row.quotaState)!.type">
                      {{ quotaBadge(row.quotaState)!.text }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              <div class="muted table-note">
                内存用量列来自 Thanos（cAdvisor <code>container_memory_working_set_bytes</code>，<code>sum by (namespace)</code> 的最近采样点）= <b>实际运行用量</b>，无指标时显示「—」。
                <b>CPU 有意不给点值</b>：尖峰型指标，单点没有代表性（实时形状看「资源用量」tab 的曲线）。
                配额列来自该命名空间的 ResourceQuota：占用 = 按 <b>requests</b> 记账的配额消耗，上限 = <code>spec.hard</code>；没有配额（或配额只限 <code>limits.*</code> / <code>pods</code>）时显示「—」。
                「配额状态」列读后端三档判定（OK 不显示 / ⚠ 无配额 / ⚠ 配额未覆盖 requests），与租户概览面板同一套措辞。
              </div>
            </section>
          </template>
        </el-tab-pane>

        <!-- ===== Tab 4：工作负载 ===== -->
        <el-tab-pane label="工作负载" name="workloads">
          <section class="info-card">
            <h3 class="section-title">对象计数</h3>
            <div v-if="aggregateUnavailable" class="muted notice">聚合不可用，本段数据暂不可用（显示「—」）。</div>
            <div v-else class="stat-grid">
              <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview?.resourceTotal?.deploymentCount) }}</div><div class="stat-label">Deployment</div></div>
              <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview?.resourceTotal?.statefulsetCount) }}</div><div class="stat-label">StatefulSet</div></div>
              <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview?.resourceTotal?.daemonsetCount) }}</div><div class="stat-label">DaemonSet</div></div>
              <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview?.resourceTotal?.podCount) }}</div><div class="stat-label">Pod</div></div>
              <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview?.resourceTotal?.serviceCount) }}</div><div class="stat-label">Service</div></div>
              <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview?.resourceTotal?.namespaceCount) }}</div><div class="stat-label">命名空间</div></div>
            </div>
          </section>

          <section class="info-card mt-16">
            <h3 class="section-title">
              副本不足的工作负载（readyReplicas &lt; replicas）
              <span v-if="unhealthyWorkloadTotal" class="muted">共 {{ unhealthyWorkloadTotal }}</span>
            </h3>
            <div v-if="aggregateUnavailable" class="muted notice">聚合不可用，本段数据暂不可用。</div>
            <el-table v-else :data="unhealthyWorkloads" stripe max-height="520">
              <el-table-column prop="kind" label="类型" width="120" />
              <el-table-column label="命名空间" min-width="160">
                <template #default="{ row }"><span class="muted">{{ row.namespace ?? '—' }}</span></template>
              </el-table-column>
              <el-table-column label="名称" min-width="220">
                <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
              </el-table-column>
              <el-table-column label="就绪 / 期望" width="130">
                <template #default="{ row }">
                  <span class="mono" :class="{ 'danger-text': row.readyReplicas < row.replicas }">{{ row.readyReplicas }} / {{ row.replicas }}</span>
                </template>
              </el-table-column>
              <el-table-column label="缺口" width="90">
                <template #default="{ row }">{{ row.replicas - row.readyReplicas }}</template>
              </el-table-column>
            </el-table>
            <div v-if="!aggregateUnavailable && !unhealthyWorkloads.length" class="muted">无（全部工作负载副本齐）。</div>
            <div v-if="unhealthyWorkloadTotal && unhealthyWorkloadTotal > unhealthyWorkloads.length" class="muted table-note">
              仅显示缺口最大的前 {{ unhealthyWorkloads.length }} 个。
            </div>
          </section>
        </el-tab-pane>

        <!-- ===== Tab 5：集群组件（S1.2）=====
             lazy：与其它非首屏 tab 一致 —— 但注意组件全量投影在 refresh() 里就拉了
             （概览摘要要用），lazy 这里只保证面板自身不额外触发请求。 -->
        <el-tab-pane label="集群组件" name="components" lazy>
          <ClusterComponentsPanel
            :statuses="components.statuses.value"
            :loading="components.statusesLoading.value"
            :can-refresh="components.canManage.value"
            :operations="operations.latest.value"
            :applying="applying"
            :load-namespaces="loadComponentNamespaces"
            :load-secret-options="loadComponentSecrets"
            @refresh="components.refresh()"
            @apply="onApply"
          />
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 异常 Pod 明细弹窗 -->
    <el-dialog v-model="podDialogVisible" :title="`异常 Pod · ${podDialogReason}`" width="900px">
      <el-table :data="podDialogRows" stripe max-height="420">
        <el-table-column label="名称" min-width="260">
          <template #default="{ row }"><code class="res-name name-link" @click="goPod(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="命名空间" min-width="150">
          <template #default="{ row }"><span class="muted">{{ row.namespace ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><StatusBadgeTip :label="row.phase ?? 'Unknown'" :type="podPhaseType(row.phase)" :reason="row.reason" /></template>
        </el-table-column>
        <el-table-column label="原因" width="170">
          <template #default="{ row }"><span class="mono">{{ row.reason ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="restarts" label="重启" width="80" />
        <el-table-column label="节点" min-width="140">
          <template #default="{ row }"><span class="muted">{{ row.node ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="存活时长" width="110">
          <template #default="{ row }">{{ ageText(row.ageSeconds) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="abnormalCapped" class="muted table-note">
        仅显示前 {{ overview?.abnormalPods?.length ?? 0 }} 条（共 {{ abnormalTotal }} 条）；上面的分组计数是全量口径。
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.cluster-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.cluster-detail {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 16px 16px;
}
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.refresh-bar { display: inline-flex; align-items: center; gap: 8px; margin-right: 12px; }

.offline-notice {
  margin: 4px 0 8px;
  padding: 8px 12px;
  border: 1px solid var(--el-color-warning-light-5, #f3d19e);
  border-radius: 8px;
  background: var(--el-color-warning-light-9, #fdf6ec);
  font-size: 12.5px;
  color: var(--text-2);
}

/* 首屏健康卡：最上，全宽 */
.health-card { padding: 4px 0 8px; }
.section-title { margin: 0 0 12px; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.muted { color: var(--text-3); }
.notice { font-size: 12.5px; margin-bottom: 8px; }

.stat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; }
.stat-tile { padding: 14px 16px; border: 1px solid var(--border); border-radius: 8px; background: var(--panel-hover); }
.stat-value { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 20px; font-weight: 600; color: var(--text-1); word-break: break-all; }
.stat-label { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.tile-bad { border-color: var(--el-color-danger-light-5, #fab6b6); }
.tile-bad .stat-value { color: var(--el-color-danger, #f56c6c); }
.tile-warn { border-color: var(--el-color-warning-light-5, #f3d19e); }
.tile-warn .stat-value { color: var(--el-color-warning, #e6a23c); }

.hv-row { display: flex; align-items: flex-start; gap: 12px; margin-top: 14px; font-size: 13px; }
.hv-k { color: var(--text-3); flex-shrink: 0; min-width: 110px; }
.hv-v { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; min-width: 0; }
.hv-tag { margin: 0; }
.clickable { cursor: pointer; }

.overview-grid { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 16px; align-items: start; margin-top: 16px; }
.ov-side, .ov-main { min-width: 0; }
@media (max-width: 960px) { .overview-grid { grid-template-columns: 1fr; } }
.info-card { padding: 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--panel); }
.info-rows { display: flex; flex-direction: column; gap: 12px; }
.info-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.info-row.col-row { flex-direction: column; align-items: flex-start; gap: 6px; }
.info-row .k { color: var(--text-3); flex-shrink: 0; }
.info-row .v { color: var(--text-1); text-align: right; word-break: break-all; }
.col-row .v { text-align: left; width: 100%; }
.tag-wrap { display: flex; flex-wrap: wrap; gap: 6px; }
.cap-hint { font-size: 12px; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.res-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 13px; }
.name-link { cursor: pointer; color: var(--accent); }
.name-link:hover { text-decoration: underline; }
.mt-16 { margin-top: 16px; }
.pending-hint { margin-top: 10px; font-size: 12.5px; color: var(--el-color-warning, #e6a23c); }
.danger-text { color: var(--el-color-danger, #f56c6c); }

/* Top-10 迷你占比条 */
.top-list { display: flex; flex-direction: column; gap: 8px; }
.top-row { display: grid; grid-template-columns: 220px minmax(0, 1fr) 160px; align-items: center; gap: 12px; }
.top-ns { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.top-bar { display: block; height: 8px; border-radius: 4px; background: var(--panel-hover); overflow: hidden; }
.top-bar-fill { display: block; height: 100%; background: var(--accent); border-radius: 4px; }
.top-val { text-align: right; }
@media (max-width: 720px) { .top-row { grid-template-columns: 140px minmax(0, 1fr) 120px; } }

.list-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.list-head .section-title { margin: 0; }
.search-input { width: 240px; }
.table-note { margin-top: 8px; font-size: 12px; }
</style>
