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
import { Refresh, Search } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { clusterApi } from '@/api'
import { clusterMetrics, type ClusterMetricsReq } from '@/api/metrics'
import type { K8sAbnormalPod, K8sCluster, K8sClusterOverview, K8sNamespaceResourceStat, K8sNodeHealth } from '@/types'
import type { MetricSeriesResponse } from '@/types/metrics'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import ClusterMetricsPanel from '@/components/cluster/ClusterMetricsPanel.vue'
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
const breakdown = ref<K8sNamespaceResourceStat[] | null>(null)
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
  return kw ? rows.filter((r) => r.namespace.toLowerCase().includes(kw)) : rows
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
    namespace: r.namespace,
    value: usedByNsMem.value[r.namespace] ?? null,
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
function usageSortKey(row: K8sNamespaceResourceStat, usedMap: Record<string, number>): number {
  return usedMap[row.namespace] ?? -1
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
                <h3 class="section-title">组件版本与 API 能力</h3>
                <div class="info-rows">
                  <div class="info-row"><span class="k">Istio</span><code class="mono v">{{ cluster.istioVersion ?? '—' }}</code></div>
                  <div class="info-row"><span class="k">Calico</span><code class="mono v">{{ cluster.calicoVersion ?? '—' }}</code></div>
                  <div class="info-row col-row">
                    <span class="k">集群能力</span>
                    <span class="v tag-wrap">
                      <el-tag size="small" :type="overview?.capabilitySummary.hasMetricsServer ? 'success' : 'info'">metrics-server</el-tag>
                      <el-tag size="small" :type="overview?.capabilitySummary.hasCustomMetrics ? 'success' : 'info'">custom-metrics</el-tag>
                      <el-tag size="small" :type="overview?.capabilitySummary.hasExternalMetrics ? 'success' : 'info'">external-metrics</el-tag>
                      <el-tag size="small" :type="overview?.capabilitySummary.hasMonitoringOperator ? 'success' : 'info'">prometheus-operator</el-tag>
                      <el-tag size="small" :type="overview?.capabilitySummary.hasGatewayApi ? 'success' : 'info'">Gateway API</el-tag>
                      <el-tag size="small" :type="overview?.capabilitySummary.hasIstio ? 'success' : 'info'">Istio</el-tag>
                      <el-tag size="small" :type="overview?.capabilitySummary.hasCalico ? 'success' : 'info'">Calico CRD</el-tag>
                    </span>
                    <span
                      v-if="overview && !overview.capabilitySummary.hasCalico && !overview.capabilitySummary.hasGatewayApi && !overview.capabilitySummary.hasIstio"
                      class="muted cap-hint"
                    >
                      能力未探测（全灰）。可在集群列表点「刷新能力」后回来重看。
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
                <el-table-column label="命名空间" width="350" sortable :sort-method="(a: K8sNamespaceResourceStat, b: K8sNamespaceResourceStat) => a.namespace.localeCompare(b.namespace)">
                  <template #default="{ row }">
                    <code class="res-name name-link" @click="goNamespace(row.namespace)">{{ row.namespace }}</code>
                  </template>
                </el-table-column>
                <el-table-column prop="podCount" label="Pod" min-width="100" sortable />
                <el-table-column prop="deployCount" label="Deploy" min-width="100" sortable />
                <el-table-column prop="stsCount" label="Sts" min-width="100" sortable />
                <el-table-column prop="dsCount" label="Ds" min-width="100" sortable />
                <el-table-column prop="svcCount" label="Svc" min-width="100" sortable />
                <el-table-column
                  label="内存用量"
                  min-width="170"
                  sortable
                  :sort-method="(a: K8sNamespaceResourceStat, b: K8sNamespaceResourceStat) => usageSortKey(a, usedByNsMem) - usageSortKey(b, usedByNsMem)"
                >
                  <template #default="{ row }">
                    <span class="mono">{{ fmtMem(usedByNsMem[row.namespace]) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="配额 CPU（used / hard）" min-width="200">
                  <template #default="{ row }">
                    <span v-if="row.quotaCpuHard == null && row.quotaCpuUsed == null" class="muted">—</span>
                    <span v-else class="mono">{{ fmtCpu(row.quotaCpuUsed) }} / {{ fmtCpu(row.quotaCpuHard) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="配额内存（used / hard）" min-width="200">
                  <template #default="{ row }">
                    <span v-if="row.quotaMemHard == null && row.quotaMemUsed == null" class="muted">—</span>
                    <span v-else class="mono">{{ fmtMem(row.quotaMemUsed) }} / {{ fmtMem(row.quotaMemHard) }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <div class="muted table-note">
                内存用量列来自 Thanos（cAdvisor <code>container_memory_working_set_bytes</code>，<code>sum by (namespace)</code> 的最近采样点）= <b>实际运行用量</b>，无指标时显示「—」。
                <b>CPU 有意不给点值</b>：尖峰型指标，单点没有代表性（实时形状看「资源用量」tab 的曲线）。
                配额列来自该命名空间的 ResourceQuota：占用 = 按 <b>requests</b> 记账的配额消耗，上限 = <code>spec.hard</code>；没有配额（或配额只限 <code>limits.*</code> / <code>pods</code>）时显示「—」。
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
