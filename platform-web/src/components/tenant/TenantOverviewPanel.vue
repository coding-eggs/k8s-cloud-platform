<script setup lang="ts">
/**
 * 租户概览面板（Task 11）：一块自包含的租户视角首屏。
 *
 * 复用两处入口（同一 UI）：TenantView 的 admin 代管抽屉（传被查看租户）、/tenants/detail 自管页（传当前租户）。
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
import { tenantApi } from '@/api'
import type { K8sAbnormalPod, K8sNamespaceStatEntry, K8sQuotaState, K8sTenantOverview } from '@/types'
import type { MetricSeries } from '@/types/metrics'
import MetricChart from '@/components/workload/MetricChart.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useResourceContext } from '@/stores/context'
import { fmtAge, fmtDate } from '@/utils/format'
import { formatMetricValue } from '@/utils/metrics'

const props = defineProps<{ tenantId: string }>()

const router = useRouter()
const ctx = useResourceContext()

// ---- 展示小工具（null → 「—」，绝不显示 0；单位沿用本仓既有格式化：CPU=核、内存=字节）----
function fmtCpu(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '核', false)
}
function fmtMem(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '字节', false)
}
function fmtCount(v?: number | null): string {
  return v == null ? '—' : String(v)
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

async function loadOverview(): Promise<void> {
  const target = props.tenantId
  if (!target) return
  loading.value = true
  loadError.value = false
  try {
    const data = await tenantApi.overview({ tenantId: target })
    if (target !== props.tenantId) return // 期间切了租户：丢弃这次结果
    overview.value = data
  } catch {
    if (target === props.tenantId) {
      overview.value = null
      loadError.value = true
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
</script>

<template>
  <div v-loading="loading" class="tov">
    <!-- 快照整块失败：其余段不显示（没有范围事实可渲染）；可重试 -->
    <EmptyState v-if="loadError" title="租户概览加载失败" description="可能是后端或缓存暂时不可用，请稍后重试。">
      <el-button type="primary" :icon="Refresh" @click="reloadAll">重试</el-button>
    </EmptyState>

    <div v-else-if="!overview" class="muted notice">加载中…</div>

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

      <!-- ===== KPI 区 ===== -->
      <section class="card">
        <div class="card-head">
          <h3 class="section-title">资源与配额</h3>
          <span class="sampled muted">数据截至 {{ sampledAtText }}</span>
        </div>

        <div class="res-lines">
          <div class="res-line">
            <span class="res-kind">CPU</span>
            <span class="cell"><span class="lab">requests</span><b class="mono">{{ fmtCpu(overview.total.cpuRequest) }}</b></span>
            <span class="cell"><span class="lab">limits</span><b class="mono">{{ fmtCpu(overview.total.cpuLimit) }}</b></span>
            <span class="cell"><span class="lab">配额 hard</span><b class="mono">{{ fmtCpu(overview.quotaCpuHard) }}</b></span>
            <span class="cell occ">
              <span class="lab">占用</span>
              <template v-if="occPct(overview.quotaCpuUsed, overview.quotaCpuHard) != null">
                <span class="bar"><span class="bar-fill" :style="{ width: occPct(overview.quotaCpuUsed, overview.quotaCpuHard) + '%' }" /></span>
                <span class="mono">{{ fmtCpu(overview.quotaCpuUsed) }} / {{ fmtCpu(overview.quotaCpuHard) }}</span>
              </template>
              <span v-else class="muted">—</span>
            </span>
          </div>
          <div class="res-line">
            <span class="res-kind">内存</span>
            <span class="cell"><span class="lab">requests</span><b class="mono">{{ fmtMem(overview.total.memRequest) }}</b></span>
            <span class="cell"><span class="lab">limits</span><b class="mono">{{ fmtMem(overview.total.memLimit) }}</b></span>
            <span class="cell"><span class="lab">配额 hard</span><b class="mono">{{ fmtMem(overview.quotaMemHard) }}</b></span>
            <span class="cell occ">
              <span class="lab">占用</span>
              <template v-if="occPct(overview.quotaMemUsed, overview.quotaMemHard) != null">
                <span class="bar"><span class="bar-fill" :style="{ width: occPct(overview.quotaMemUsed, overview.quotaMemHard) + '%' }" /></span>
                <span class="mono">{{ fmtMem(overview.quotaMemUsed) }} / {{ fmtMem(overview.quotaMemHard) }}</span>
              </template>
              <span v-else class="muted">—</span>
            </span>
          </div>
        </div>

        <div class="stat-grid">
          <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview.total.podCount) }}</div><div class="stat-label">Pod</div></div>
          <div class="stat-tile"><div class="stat-value">{{ fmtCount(workloadCount) }}</div><div class="stat-label">工作负载</div></div>
          <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview.total.serviceCount) }}</div><div class="stat-label">Service</div></div>
          <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview.total.pvcCount) }}</div><div class="stat-label">PVC</div></div>
          <div class="stat-tile"><div class="stat-value">{{ fmtCount(overview.total.namespaceCount) }}</div><div class="stat-label">命名空间</div></div>
        </div>
      </section>

      <!-- ===== 需要关注 ===== -->
      <section class="card">
        <h3 class="section-title">需要关注</h3>

        <div
          v-if="
            overview.namespacesWithoutQuota.length ||
            overview.namespacesPartialQuota.length ||
            overview.abnormalPodTotal > 0 ||
            overview.unhealthyWorkloadTotal > 0
          "
          class="attention"
        >
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

        <div v-else class="muted positive">没有需要关注的项目</div>
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
        <el-table v-else :data="breakdown" stripe max-height="480">
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
          <el-table-column label="requests (CPU / 内存)" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ fmtCpu(row.snapshot.stat.cpuRequest) }} / {{ fmtMem(row.snapshot.stat.memRequest) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="limits (CPU / 内存)" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ fmtCpu(row.snapshot.stat.cpuLimit) }} / {{ fmtMem(row.snapshot.stat.memLimit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="配额 hard (CPU / 内存)" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ fmtCpu(row.snapshot.stat.quotaCpuHard) }} / {{ fmtMem(row.snapshot.stat.quotaMemHard) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="占用条" min-width="220">
            <template #default="{ row }">
              <div v-if="occPct(row.snapshot.stat.quotaCpuUsed, row.snapshot.stat.quotaCpuHard) != null" class="occ-row">
                <span class="occ-tag">CPU</span>
                <span class="bar"><span class="bar-fill" :style="{ width: occPct(row.snapshot.stat.quotaCpuUsed, row.snapshot.stat.quotaCpuHard) + '%' }" /></span>
              </div>
              <div v-if="occPct(row.snapshot.stat.quotaMemUsed, row.snapshot.stat.quotaMemHard) != null" class="occ-row">
                <span class="occ-tag">内存</span>
                <span class="bar"><span class="bar-fill" :style="{ width: occPct(row.snapshot.stat.quotaMemUsed, row.snapshot.stat.quotaMemHard) + '%' }" /></span>
              </div>
              <span
                v-if="
                  occPct(row.snapshot.stat.quotaCpuUsed, row.snapshot.stat.quotaCpuHard) == null &&
                  occPct(row.snapshot.stat.quotaMemUsed, row.snapshot.stat.quotaMemHard) == null
                "
                class="muted"
                >—</span
              >
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
.positive {
  font-size: 13px;
  padding: 4px 0;
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

.res-lines {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 16px;
}
.res-line {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  font-size: 13px;
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

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 12px;
}
.stat-tile {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-hover);
}
.stat-value {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-1);
  word-break: break-all;
}
.stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
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

.occ-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 2px 0;
}
.occ-tag {
  width: 34px;
  font-size: 11px;
  color: var(--text-3);
}
</style>
