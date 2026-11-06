<script setup lang="ts">
/**
 * 租户概览面板（Task 11）：一块自包含的租户视角首屏。
 *
 * 复用两处入口（同一 UI）：TenantView 的 admin 代管抽屉（传被查看租户）、租户视图总览（传当前租户）。
 * 范围（有哪些命名空间）取自后端分配表；事实取自各命名空间切片 —— 首屏所有数字与明细逐行相加一致（spec §6.1）。
 *
 * 三段分开加载、分开降级（spec §10，B4 的 Promise.all 一锅端踩过）：
 *   1. 快照（/tenant/overview）      —— 失败整块显示失败态；
 *   2. 明细（/tenant/overview/breakdown，进面板即拉）—— 失败只影响明细表；
 *   3. 两条曲线（/tenant/overview/metrics/{cpu,memory}）—— 失败只影响各自的图表区。
 * 某集群取不到数不整页失败：后端把它计入 unavailableClusters（首屏显式提示 + 可重试），其余照常出数。
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { middlewareApi, tenantApi } from '@/api'
import type { K8sAbnormalPod, K8sNamespaceStatEntry, K8sQuotaState, K8sTenantOverview } from '@/types'
import type { MetricSeries } from '@/types/metrics'
import MetricChart from '@/components/workload/MetricChart.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useResourceContext } from '@/stores/context'
import { fmtAge, fmtDate } from '@/utils/format'
import { formatMetricValue } from '@/utils/metrics'
import CountCard from '@/components/overview/CountCard.vue'
import StatusCards from '@/components/overview/StatusCards.vue'
import CapacityBullet from '@/components/overview/CapacityBullet.vue'
import InventoryStrip, { type InventoryItem } from '@/components/overview/InventoryStrip.vue'
import { middlewareNsCountsOf, nsCountsOf, podSegments, segmentsOf, workloadSegments, type Segment } from '@/components/overview/labels'
import type { ScopeSummary } from '@/types/overview'

const props = defineProps<{ tenantId: string }>()
/** loaded：快照取数成功（带时刻）；failed：刷新失败但保留了上一次的数（总览页头据此显示「更新于 / 刷新失败」） */
const emit = defineEmits<{ loaded: [at: number]; failed: [] }>()

const router = useRouter()
const ctx = useResourceContext()

// ---- 展示小工具（null → 「—」，绝不显示 0；单位沿用本仓既有格式化：CPU=核、内存=字节）----
function fmtCpu(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '核', false)
}
function fmtMem(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '字节', false)
}
/** 占用率（%）。hard 为 null/0（或 used 缺数）→ null（不画条、显示「—」）。 */
function occPct(used?: number | null, hard?: number | null): number | null {
  if (used == null || hard == null || hard <= 0) return null
  return Math.min(100, Math.max(0, (used / hard) * 100))
}
/** 异常 Pod 存活时长：ageSeconds → 「Xd Yh」。 */
function ageText(seconds?: number | null): string {
  return seconds == null ? '—' : fmtAge(Date.now() - seconds * 1000)
}

// ==================== 1. 快照 ====================
const overview = ref<K8sTenantOverview | null>(null)
const loading = ref(false)
const loadError = ref(false)

/** 该租户分配到的命名空间总数（范围，来自分配表）—— 为 0 才是「无命名空间」空态。 */
const allocatedNamespaceCount = computed(() =>
  (overview.value?.clusters ?? []).reduce((sum, c) => sum + (c.namespaceCount ?? 0), 0),
)
const workloadCount = computed(() => {
  const t = overview.value?.total
  return t ? t.deploymentCount + t.statefulsetCount + t.daemonsetCount : null
})
/** 「数据截至」：R11 —— sampledAt <= 0 是「未知」哨兵，显示「—」，绝不格式化成 1970。 */
const sampledAtText = computed(() => {
  const s = overview.value?.sampledAt ?? 0
  return s > 0 ? fmtDate(s) : '—'
})
const clusterNameOf = (clusterId: string): string =>
  overview.value?.clusters.find((c) => c.clusterId === clusterId)?.clusterName ?? clusterId

const summary = ref<ScopeSummary | null>(null)

/** Pod / 工作负载卡：数取快照合计；点状态时按命名空间明细（breakdown，进面板即拉）定位到具体命名空间 */
const tenantPodSegments = computed<Segment[]>(() => {
  const t = overview.value?.total
  if (!t) return []
  const running = t.podRunning ?? 0
  const pending = t.podPending ?? 0
  const succeeded = t.podSucceeded ?? 0
  return podSegments({ total: t.podCount, running, pending, succeeded, abnormal: Math.max(0, t.podCount - running - pending - succeeded) })
})
const tenantWorkloadSegments = computed<Segment[]>(() =>
  workloadSegments(workloadCount.value, overview.value?.unhealthyWorkloadTotal),
)
/** 中间件卡点状态时现拉：本租户各集群的实例 → 按命名空间计数 */
async function loadTenantMwRows() {
  const target = props.tenantId
  const ids = (overview.value?.clusters ?? []).map((c) => c.clusterId)
  const lists = await Promise.all(ids.map((id) => middlewareApi.instanceList(id, undefined, target).catch(() => [])))
  return middlewareNsCountsOf(lists.flat(), clusterNameOf)
}
/** 清单条：只有数、没有状态的对象（不占状态卡位） */
const inventory = computed<InventoryItem[]>(() => [
  { label: 'Service', value: overview.value?.total.serviceCount, to: '/resources/services' },
  { label: 'PVC', value: overview.value?.total.pvcCount, to: '/resources/pvcs' },
  { label: '命名空间', value: summary.value?.namespaceCount ?? allocatedNamespaceCount.value },
  { label: '成员', value: summary.value?.memberCount },
])

/**
 * 容量子弹图的口径：配额 hard 只覆盖「配了配额的命名空间」。
 * - 全部命名空间都有配额（或一个都没有）→ requests / limits / 实际用量和量程同口径，全画；
 * - 部分有配额 → 实心段只画「配额已用」（与 hard 同口径），limits / 实际用量不画（口径不一会误导），并注明未计入的数量。
 */
const quotaCoverage = computed<'all' | 'none' | 'partial'>(() => {
  const o = overview.value
  if (!o || (o.quotaCpuHard == null && o.quotaMemHard == null)) return 'none'
  return o.namespacesWithoutQuota.length ? 'partial' : 'all'
})
/** 监控当前值（曲线「用量」序列最后一个点）；取不到 → null */
function currentUsed(key: CurveKey): number | null {
  const ser = curve.value[key].series
  const s = ser.find((x) => x.legend === '用量') ?? ser[0]
  const last = s?.points[s.points.length - 1]
  return last ? last.value : null
}
/**
 * 四条子弹图（CPU / 内存 × requests / limits）：requests 对 requests 配额、limits 对 limits 配额，各用各的尺。
 * 有配额时实心段取配额已用（与 hard 同口径），没有时取合计并标「未配置」；实际用量竖线画在 requests 那条上。
 */
const bullets = computed(() => {
  const o = overview.value
  if (!o) return []
  const cov = quotaCoverage.value
  const t = o.total
  const mk = (key: string, label: string, unit: 'core' | 'byte', hard: number | null | undefined, qUsed: number | null | undefined, sum: number | null | undefined, sumName: string, used: number | null) => ({
    key, label, unit,
    scale: hard ?? null,
    filled: hard != null ? (qUsed ?? null) : (sum ?? null),
    filledName: hard != null ? '配额已用' : sumName,
    used,
    missingText: `未配置${sumName}配额`,
  })
  // 网格两列：左列 CPU（requests 在上、limits 在下），右列内存
  return [
    mk('cpu-req', 'CPU requests', 'core', o.quotaCpuHard, o.quotaCpuUsed, t.cpuRequest, 'requests', cov === 'partial' ? null : currentUsed('cpu')),
    mk('mem-req', '内存 requests', 'byte', o.quotaMemHard, o.quotaMemUsed, t.memRequest, 'requests', cov === 'partial' ? null : currentUsed('memory')),
    mk('cpu-lim', 'CPU limits', 'core', o.quotaLimitCpuHard, o.quotaLimitCpuUsed, t.cpuLimit, 'limits', null),
    mk('mem-lim', '内存 limits', 'byte', o.quotaLimitMemHard, o.quotaLimitMemUsed, t.memLimit, 'limits', null),
  ]
})

/** 各命名空间配额占用（requests / limits 配额各两维，只算配了 hard 的维）；top = 最高的那一维 */
const quotaOcc = computed(() =>
  (breakdown.value ?? []).map((r) => {
    const st = r.snapshot.stat
    const pct = (u?: number | null, h?: number | null) => (u != null && h != null && h > 0 ? (u / h) * 100 : null)
    const dims = [
      { name: 'CPU requests', p: pct(st.quotaCpuUsed, st.quotaCpuHard) },
      { name: '内存 requests', p: pct(st.quotaMemUsed, st.quotaMemHard) },
      { name: 'CPU limits', p: pct(st.quotaLimitCpuUsed, st.quotaLimitCpuHard) },
      { name: '内存 limits', p: pct(st.quotaLimitMemUsed, st.quotaLimitMemHard) },
    ].filter((d) => d.p != null) as { name: string; p: number }[]
    const top = dims.reduce<{ name: string; p: number } | null>((m, d) => (m == null || d.p > m.p ? d : m), null)
    return { row: r, top }
  }))
const multiCluster = computed(() => (overview.value?.clusters.length ?? 0) > 1)
/** 需要关注：配额占用 ≥ 80% 的命名空间（任一维） */
const nearLimit = computed(() =>
  quotaOcc.value
    .filter((x) => x.top != null && x.top.p >= 80)
    .map((x) => ({ key: `${x.row.clusterId}/${nsOf(x.row)}`, ns: nsOf(x.row), row: x.row, pct: x.top!.p, dim: x.top!.name }))
    .sort((a, b) => b.pct - a.pct))
/** 明细表行序：配额占用（requests / limits、CPU / 内存四维取最高）降序；没有配额的排最后（按集群、名称） */
const sortedBreakdown = computed(() =>
  [...quotaOcc.value]
    .sort((a, b) => {
      const pa = a.top?.p ?? -1
      const pb = b.top?.p ?? -1
      if (pa !== pb) return pb - pa
      return (a.row.clusterId + '/' + nsOf(a.row)).localeCompare(b.row.clusterId + '/' + nsOf(b.row))
    }))
/** 明细表的资源列（一列一种单位，避免「x 核 / y GiB」混排） */
const RES_DIMS = [
  { key: 'cpu' as const, label: 'CPU（已用 / 配额）↓' },
  { key: 'mem' as const, label: '内存（已用 / 配额）↓' },
]
function resCell(row: K8sNamespaceStatEntry, dim: 'cpu' | 'mem') {
  const st = row.snapshot.stat
  const f = dim === 'cpu' ? fmtCpu : fmtMem
  const line = (k: string, hard: number | null | undefined, qUsed: number | null | undefined, sum: number | null | undefined) => {
    if (hard == null) return { k, w: null as number | null, cls: '', text: `${f(sum)} · 未配额` }
    const used = qUsed ?? null
    const p = used != null && hard > 0 ? (used / hard) * 100 : null
    return { k, w: p == null ? null : Math.min(100, p), cls: `tone-${p == null ? 'ok' : occTone(p)}`, text: `${f(used)} / ${f(hard)}` }
  }
  return {
    lines: dim === 'cpu'
      ? [line('requests', st.quotaCpuHard, st.quotaCpuUsed, st.cpuRequest), line('limits', st.quotaLimitCpuHard, st.quotaLimitCpuUsed, st.cpuLimit)]
      : [line('requests', st.quotaMemHard, st.quotaMemUsed, st.memRequest), line('limits', st.quotaLimitMemHard, st.quotaLimitMemUsed, st.memLimit)],
  }
}
const occTone = (p: number) => (p >= 95 ? 'danger' : p >= 80 ? 'warning' : 'ok')

const hasAttention = computed(() => {
  const o = overview.value
  return !!o && (o.namespacesWithoutQuota.length > 0 || o.namespacesPartialQuota.length > 0
    || o.abnormalPodTotal > 0 || o.unhealthyWorkloadTotal > 0 || nearLimit.value.length > 0)
})

const tenantNsRows = computed(() => (breakdown.value ? nsCountsOf(breakdown.value, clusterNameOf) : null))

async function loadOverview(): Promise<void> {
  const target = props.tenantId
  if (!target) return
  loading.value = true
  loadError.value = false
  // 计数卡独立降级：失败只是不显示这一行（刷新失败保留上一次的数；换租户时由下面的 watch 清空）
  tenantApi.summary(target)
    .then((s) => { if (target === props.tenantId) summary.value = s })
    .catch(() => { /* 不影响其它段 */ })
  try {
    const data = await tenantApi.overview({ tenantId: target })
    if (target !== props.tenantId) return // 期间切了租户：丢弃这次结果
    overview.value = data
    emit('loaded', Date.now())
  } catch {
    if (target === props.tenantId) {
      // 已有数据时（自动刷新 / 手动刷新）保留旧数，只通知页头；首次就失败才整块显示失败态
      if (overview.value) emit('failed')
      else loadError.value = true
    }
  } finally {
    if (target === props.tenantId) loading.value = false
  }
}

// ==================== 2. 明细表（进面板即拉 = 「首次渲染时拉」）====================
const breakdown = ref<K8sNamespaceStatEntry[] | null>(null)
const breakdownLoading = ref(false)
const breakdownLoaded = ref(false)

async function loadBreakdown(): Promise<void> {
  const target = props.tenantId
  if (!target) return
  breakdownLoading.value = true
  try {
    const rows = await tenantApi.overviewBreakdown({ tenantId: target })
    if (target !== props.tenantId) return
    breakdown.value = rows
  } catch {
    if (target === props.tenantId) breakdown.value = null
  } finally {
    if (target === props.tenantId) {
      breakdownLoading.value = false
      breakdownLoaded.value = true
    }
  }
}

const nsOf = (row: K8sNamespaceStatEntry): string => row.snapshot.stat.namespace
/** R14：只有当该行的 (集群, 命名空间) 与顶栏上下文一致时才是链接 —— 否则点下去会作用到另一个上下文里的同名对象。 */
const canOpen = (row: K8sNamespaceStatEntry): boolean =>
  ctx.state.clusterId === row.clusterId && ctx.state.namespace === nsOf(row)
function openNamespace(row: K8sNamespaceStatEntry): void {
  router.push(
    `/namespaces/detail?clusterId=${encodeURIComponent(row.clusterId)}&name=${encodeURIComponent(nsOf(row))}`,
  )
}
/** quotaState 直接读后端三态（前端只做展示映射，不自行判定）：OK 不显示 / MISSING / PARTIAL。 */
function quotaBadge(state: K8sQuotaState): { text: string; type: 'danger' | 'warning' } | null {
  if (state === 'MISSING') return { text: '⚠ 无配额', type: 'danger' }
  if (state === 'PARTIAL') return { text: '⚠ 配额未覆盖 requests', type: 'warning' }
  return null
}

// ==================== 3. 两条曲线（各自独立降级）====================
const RANGES = [15, 60, 360, 1440]
type CurveKey = 'cpu' | 'memory'
interface CurveState {
  series: MetricSeries[]
  unit: string
  loading: boolean
  error: boolean
  range: number
}
const curve = ref<Record<CurveKey, CurveState>>({
  cpu: { series: [], unit: '核', loading: false, error: false, range: 60 },
  memory: { series: [], unit: '字节', loading: false, error: false, range: 60 },
})

async function loadCurve(key: CurveKey): Promise<void> {
  const target = props.tenantId
  if (!target) return
  const end = Math.floor(Date.now() / 1000)
  const start = end - curve.value[key].range * 60
  curve.value[key].loading = true
  curve.value[key].error = false
  try {
    const resp =
      key === 'cpu'
        ? await tenantApi.tenantMetricsCpu({ tenantId: target, start, end })
        : await tenantApi.tenantMetricsMemory({ tenantId: target, start, end })
    if (target !== props.tenantId) return
    curve.value[key].series = resp.series ?? []
    curve.value[key].unit = resp.unit
  } catch {
    if (target === props.tenantId) {
      curve.value[key].series = []
      curve.value[key].error = true
    }
  } finally {
    if (target === props.tenantId) curve.value[key].loading = false
  }
}
function onRangeChange(key: CurveKey, v: number): void {
  curve.value[key].range = v
  void loadCurve(key)
}

// ==================== 加载编排 ====================
function reloadAll(): void {
  void loadOverview()
  void loadBreakdown()
  void loadCurve('cpu')
  void loadCurve('memory')
}

// tenantId 变化（列表换行 / 自管页切上下文）→ 全部重拉，旧数据先清空
watch(
  () => props.tenantId,
  () => {
    overview.value = null
    summary.value = null
    loadError.value = false
    breakdown.value = null
    breakdownLoaded.value = false
    curve.value.cpu.series = []
    curve.value.cpu.error = false
    curve.value.memory.series = []
    curve.value.memory.error = false
    reloadAll()
  },
  { immediate: true },
)

defineExpose({ reloadAll })
</script>

<template>
  <div class="tov">
    <!-- 快照整块失败：其余段不显示（没有范围事实可渲染）；可重试 -->
    <EmptyState v-if="loadError" title="租户概览加载失败" description="可能是后端或缓存暂时不可用，请稍后重试。">
      <el-button type="primary" :icon="Refresh" @click="reloadAll">重试</el-button>
    </EmptyState>

    <!-- 首次加载：骨架屏占位（之后的刷新保留旧数据，不遮罩） -->
    <el-skeleton v-else-if="!overview" animated class="tov-skeleton">
      <template #template>
        <div class="tov-sk-cards">
          <el-skeleton-item v-for="i in 5" :key="i" variant="rect" class="sk-card" />
        </div>
        <el-skeleton-item variant="rect" class="sk-block" />
        <el-skeleton-item variant="rect" class="sk-block" />
      </template>
    </el-skeleton>

    <!-- 该租户没有任何命名空间（范围=0） -->
    <EmptyState
      v-else-if="allocatedNamespaceCount === 0"
      title="尚未分配命名空间"
      description="请联系平台管理员分配命名空间。"
    />

    <template v-else>
      <!-- 降级：某集群数据不可用（其余照常出数） -->
      <el-alert
        v-if="overview.unavailableClusters.length"
        class="unavail"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>
          集群 {{ overview.unavailableClusters.map(clusterNameOf).join('、') }} 数据不可用（其余集群照常）。
          <el-button link type="primary" :icon="Refresh" @click="reloadAll">重试</el-button>
        </template>
      </el-alert>

      <!-- ===== 状态卡（四个概览统一：工作负载 · Pod · 应用 · 中间件 · CI · 告警；无读权限的块不渲染）===== -->
      <StatusCards>
        <CountCard title="工作负载" kind="workload" :total="workloadCount" :segments="tenantWorkloadSegments" :ns-rows="tenantNsRows" />
        <CountCard title="Pod" kind="pod" :total="overview.total.podCount" :segments="tenantPodSegments" :ns-rows="tenantNsRows" />
        <CountCard v-if="summary?.applications" title="应用" kind="app" :total="summary.applications.total" :segments="segmentsOf('app', summary.applications)" />
        <CountCard v-if="summary?.middleware" title="中间件" kind="middleware" :load-ns-rows="loadTenantMwRows" :total="summary.middleware.total" :segments="segmentsOf('middleware', summary.middleware)" />
        <CountCard v-if="summary?.pipelines" title="CI（最近一次运行）" kind="pipeline" :total="summary.pipelines.total" :segments="segmentsOf('pipeline', summary.pipelines)" />
        <CountCard v-if="summary?.alerts" title="告警触发中" kind="alert" :total="summary.alerts.total" :segments="segmentsOf('alert', summary.alerts)" />
      </StatusCards>

      <!-- ===== 资源与配额：清单条 + 子弹图（CPU / 内存）+ 配额占用 Top 5 命名空间 ===== -->
      <section class="card">
        <div class="card-head">
          <h3 class="section-title">资源与配额</h3>
          <InventoryStrip :items="inventory" class="cap-inv" />
          <span class="sampled muted">数据截至 {{ sampledAtText }}</span>
        </div>

        <div class="cap-bullets">
            <CapacityBullet
              v-for="b in bullets"
              :key="b.key"
              :label="b.label"
              :unit="b.unit"
              :scale="b.scale"
              scale-name="配额"
              :filled="b.filled"
              :filled-name="b.filledName"
              :used="b.used"
              :missing-text="b.missingText"
            />
            <div v-if="quotaCoverage === 'partial'" class="cap-note muted">
              {{ overview.namespacesWithoutQuota.length }} 个命名空间没有配额，未计入 requests 量程；实际用量按全部命名空间统计，此处不画以免口径混淆。
            </div>
        </div>
      </section>

      <!-- ===== 需要关注 ===== -->
      <div v-if="!hasAttention" class="card all-good">✓ 没有需要关注的项目</div>
      <section v-else class="card">
        <h3 class="section-title">需要关注</h3>

        <div class="attention">
          <!-- 配额接近上限（≥ 80%） -->
          <div v-if="nearLimit.length" class="att-block">
            <div class="att-k">配额接近上限 <span class="muted">≥ 80%</span></div>
            <div class="att-tags">
              <el-tag
                v-for="n in nearLimit"
                :key="n.key"
                :type="n.pct >= 95 ? 'danger' : 'warning'"
                size="small"
              >
                {{ multiCluster ? clusterNameOf(n.row.clusterId) + ' / ' : '' }}{{ n.ns }} {{ n.dim }} {{ n.pct.toFixed(0) }}%
              </el-tag>
            </div>
          </div>

          <!-- 配额 -->
          <div v-if="overview.namespacesWithoutQuota.length || overview.namespacesPartialQuota.length" class="att-block">
            <div class="att-k">配额</div>
            <div class="att-tags">
              <el-tag v-if="overview.namespacesWithoutQuota.length" type="danger" size="small">
                ⚠ {{ overview.namespacesWithoutQuota.length }} 个命名空间无配额
              </el-tag>
              <el-tag v-if="overview.namespacesPartialQuota.length" type="warning" size="small">
                ⚠ {{ overview.namespacesPartialQuota.length }} 个命名空间配额未覆盖 requests
              </el-tag>
            </div>
            <div v-if="overview.namespacesWithoutQuota.length" class="chips">
              <span class="muted">无配额：</span>
              <span v-for="ns in overview.namespacesWithoutQuota" :key="'w-' + ns" class="chip">{{ ns }}</span>
            </div>
            <div v-if="overview.namespacesPartialQuota.length" class="chips">
              <span class="muted">配额未覆盖 requests：</span>
              <span v-for="ns in overview.namespacesPartialQuota" :key="'p-' + ns" class="chip">{{ ns }}</span>
            </div>
          </div>

          <!-- 异常 Pod：按 reason 分组计数（全量口径），名单可展开 -->
          <div v-if="overview.abnormalPodTotal > 0" class="att-block">
            <div class="att-k">异常 Pod <span class="muted">共 {{ overview.abnormalPodTotal }}</span></div>
            <div class="att-tags">
              <el-tag v-for="(cnt, reason) in overview.abnormalPodReasonCounts" :key="reason" type="danger" size="small">
                {{ reason }} {{ cnt }}
              </el-tag>
            </div>
            <el-collapse v-if="overview.abnormalPods.length" class="att-collapse">
              <el-collapse-item :title="`展开名单（${overview.abnormalPods.length} 条${overview.abnormalPodTotal > overview.abnormalPods.length ? '，已截断' : ''}）`" name="pods">
                <el-table :data="overview.abnormalPods" size="small" stripe max-height="300">
                  <el-table-column label="命名空间" min-width="130">
                    <template #default="{ row }"><span class="muted">{{ row.namespace ?? '—' }}</span></template>
                  </el-table-column>
                  <el-table-column label="名称" min-width="200">
                    <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
                  </el-table-column>
                  <el-table-column label="原因" min-width="150">
                    <template #default="{ row }"><span class="mono">{{ row.reason ?? '—' }}</span></template>
                  </el-table-column>
                  <el-table-column prop="restarts" label="重启" width="80" />
                  <el-table-column label="存活时长" width="110">
                    <template #default="{ row }">{{ ageText((row as K8sAbnormalPod).ageSeconds) }}</template>
                  </el-table-column>
                </el-table>
              </el-collapse-item>
            </el-collapse>
          </div>

          <!-- 不健康工作负载 -->
          <div v-if="overview.unhealthyWorkloadTotal > 0" class="att-block">
            <div class="att-k">不健康工作负载 <span class="muted">共 {{ overview.unhealthyWorkloadTotal }}</span></div>
            <div class="att-tags">
              <el-tag
                v-for="w in overview.unhealthyWorkloads"
                :key="`${w.kind}/${w.namespace}/${w.name}`"
                type="warning"
                size="small"
              >
                {{ w.namespace }}/{{ w.name }} {{ w.readyReplicas }}/{{ w.replicas }}
              </el-tag>
              <span v-if="overview.unhealthyWorkloadTotal > overview.unhealthyWorkloads.length" class="muted">
                …共 {{ overview.unhealthyWorkloadTotal }} 个
              </span>
            </div>
          </div>
        </div>

      </section>

      <!-- ===== 两条曲线（各自独立降级）===== -->
      <section class="charts">
        <div v-if="curve.cpu.error" class="chart-fail">
          租户 CPU 曲线加载失败（Thanos 不可用或查询失败）——不影响上方快照。
          <el-button link type="primary" :icon="Refresh" @click="loadCurve('cpu')">重试</el-button>
        </div>
        <MetricChart
          v-else
          title="租户 CPU 用量"
          :unit="curve.cpu.unit"
          :series="curve.cpu.series"
          :loading="curve.cpu.loading"
          percentable
          :limit="overview.quotaCpuHard ?? null"
          :ranges="RANGES"
          :range="curve.cpu.range"
          @update:range="(v: number) => onRangeChange('cpu', v)"
        />

        <div v-if="curve.memory.error" class="chart-fail">
          租户内存曲线加载失败（Thanos 不可用或查询失败）——不影响上方快照。
          <el-button link type="primary" :icon="Refresh" @click="loadCurve('memory')">重试</el-button>
        </div>
        <MetricChart
          v-else
          title="租户内存用量"
          :unit="curve.memory.unit"
          :series="curve.memory.series"
          :loading="curve.memory.loading"
          percentable
          :limit="overview.quotaMemHard ?? null"
          :ranges="RANGES"
          :range="curve.memory.range"
          @update:range="(v: number) => onRangeChange('memory', v)"
        />
      </section>

      <!-- ===== 明细表 ===== -->
      <section class="card">
        <h3 class="section-title">命名空间资源明细（{{ breakdown?.length ?? 0 }}）</h3>
        <!-- 「还没拉」与「不可用」是两个结论，用 breakdownLoaded 区分 -->
        <div v-if="!breakdownLoaded || breakdownLoading" class="muted notice">加载中…</div>
        <div v-else-if="!breakdown" class="muted notice">明细不可用（取数失败）——本段显示「—」。</div>
        <el-table v-else :data="sortedBreakdown.map((x) => x.row)" stripe max-height="480">
          <el-table-column label="命名空间" width="200" fixed>
            <template #default="{ row }">
              <span v-if="canOpen(row)" class="name-link" @click="openNamespace(row)">{{ nsOf(row) }}</span>
              <el-tooltip v-else content="先切换上下文（顶栏选择该集群 / 命名空间）" placement="top">
                <span class="muted">{{ nsOf(row) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="集群" min-width="140">
            <template #default="{ row }"><span class="muted">{{ clusterNameOf(row.clusterId) }}</span></template>
          </el-table-column>
          <!-- 按资源分列（一列一种单位）：两行 = requests 对 requests 配额、limits 对 limits 配额（ResourceQuota 的 requests.* / limits.* 是两条独立的线）；
               条 = 占用比例，≥80% 橙、≥95% 红；没设该配额只写合计 + 未配额 -->
          <el-table-column v-for="dim in RES_DIMS" :key="dim.key" :label="dim.label" min-width="260">
            <template #default="{ row }">
              <div class="rc">
                <div v-for="ln in resCell(row, dim.key).lines" :key="ln.k" class="rc-row">
                  <span class="rc-k">{{ ln.k }}</span>
                  <span class="rc-bar">
                    <span v-if="ln.w != null" class="rc-fill" :class="ln.cls" :style="{ width: ln.w + '%' }" />
                  </span>
                  <span class="rc-v">{{ ln.text }}</span>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="200">
            <template #default="{ row }">
              <!-- OK 不显示（只标异常两档）；判定全在后端 quotaState -->
              <el-tag v-if="quotaBadge(row.quotaState)" size="small" :type="quotaBadge(row.quotaState)!.type">
                {{ quotaBadge(row.quotaState)!.text }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>
  </div>
</template>

<style scoped>
.tov-skeleton { padding-top: 4px; }
.tov-sk-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}
.tov-skeleton .sk-card { width: 100%; height: 112px; border-radius: 10px; }
.tov-skeleton .sk-block { width: 100%; height: 160px; border-radius: 10px; margin-bottom: 14px; }
.tov {
  padding-bottom: 8px;
}
.notice {
  font-size: 12.5px;
  margin: 6px 0 8px;
}
.muted {
  color: var(--text-3);
}
.mono {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
.unavail {
  margin: 4px 0 12px;
}

.card {
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  margin-bottom: 14px;
}
.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.section-title {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--text-2);
}
.sampled {
  font-size: 12px;
}

.res-kind {
  width: 44px;
  font-weight: 700;
  color: var(--text-2);
  flex-shrink: 0;
}
.cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.cell .lab {
  color: var(--text-3);
}
.cell.occ {
  gap: 8px;
}
.bar {
  display: inline-block;
  width: 120px;
  height: 8px;
  border-radius: 4px;
  background: var(--panel-hover);
  overflow: hidden;
}
.bar-fill {
  display: block;
  height: 100%;
  background: var(--accent);
  border-radius: 4px;
}


.attention {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.att-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.att-k {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}
.att-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
}
.chip {
  padding: 1px 8px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-hover);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12px;
  color: var(--text-2);
}
.att-collapse {
  border-top: 1px solid var(--border);
}

.charts {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}
@media (min-width: 1100px) {
  .charts {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
}
.chart-fail {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  padding: 16px;
  font-size: 12.5px;
  color: var(--text-2);
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 120px;
}

.bar-fill.tone-warning { background: var(--warning); }
.bar-fill.tone-danger { background: var(--danger); }

.cap-inv { flex: 1; min-width: 0; margin-left: 12px; }
.cap-bullets { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px 32px; }
.cap-bullets .cap-note { grid-column: 1 / -1; }
@media (max-width: 900px) {
  .cap-bullets { grid-template-columns: minmax(0, 1fr); }
}
.cap-note { font-size: 12px; line-height: 1.6; }
.all-good {
  padding: 10px 16px;
  font-size: 13px;
  color: var(--success);
}
.rc { display: flex; flex-direction: column; gap: 4px; }
.rc-row { display: grid; grid-template-columns: 58px 84px auto; align-items: center; gap: 8px; white-space: nowrap; }
.rc-k { font-size: 12px; color: var(--text-3); }
.rc-bar { position: relative; height: 8px; border-radius: 4px; background: var(--panel-hover); }
.rc-fill { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 4px; }
.rc-fill.tone-ok { background: var(--accent); }
.rc-fill.tone-warning { background: var(--warning); }
.rc-fill.tone-danger { background: var(--danger); }
.rc-v { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12px; color: var(--text-1); }
</style>
