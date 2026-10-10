<script setup lang="ts">
/** 集群资源用量面板：CPU / 内存各一组三口径「allocatable / requests / used」+ 一张实时曲线。
 *
 * **刻意不给「使用率」单独占一格**（2026-10-10 用户指出冗余）：它 = used ÷ allocatable，这两个数就在相邻两格里；
 * 随时间的使用率由曲线图头的「%」切换承担（`percentable + limit=allocatable`）。
 *
 * 两个比值（**名字都是中性的，只在越界时才作为判定**）：
 *  - **调度水位** = requests / allocatable（100% = 排满；>100% 属异常 —— 正常调度下恒不超）
 *  - **limits 占比** = limits / allocatable（≤100% **健康**；**>100% 才叫超卖**，此时才显示「已超卖」标记）
 *
 * 三口径分工（spec §5.4 双平面，两边互不依赖）：
 *  - allocatable / requests ← 聚合快照（K8s 结构口径，父级从 /cluster/overview 传下来）
 *  - used ← Thanos 实时口径（本组件自己拉曲线，顺便取最后一个采样点作为「当前值」）
 * 任一侧缺数据就显示「—」，绝不把「读不到」渲染成 0。
 *
 * 命名：用 K8s 原生字段名 `allocatable`（node.status）与 `requests`（pod spec）——
 * 规格里那套「allocated 口径」的说法不再出现在 UI 上（allocated 就是 requests，而 allocated/allocatable
 * 两个词只差两个字母，摆在一起看必错）。
 *
 * 曲线复用 MetricChart。刷新由父级「全局刷新」驱动（defineExpose.refresh）。
 */
import { computed, onMounted, reactive, watch } from 'vue'
import { clusterMetrics, type ClusterMetricsReq } from '@/api/metrics'
import type { MetricSeriesResponse } from '@/types/metrics'
import type { K8sClusterResourceTotal, K8sResourceCapacity } from '@/types'
import MetricChart from '../workload/MetricChart.vue'
import { formatMetricValue } from '@/utils/metrics'

const props = defineProps<{
  clusterId: string
  /** Σ node.status.allocatable（allocatable 口径） */
  capacity?: K8sResourceCapacity | null
  /** Σ pod requests/limits（requests 口径 = 调度器已申领的量） */
  total?: K8sClusterResourceTotal | null
}>()

type Key = 'cpu' | 'memory' | 'network' | 'disk'
const KEYS: Key[] = ['cpu', 'memory', 'network', 'disk']
/** 时间范围预设（分钟），与节点/命名空间面板一致 */
const RANGES = [15, 30, 60]

interface Cell { data: MetricSeriesResponse | null; loading: boolean }
const state = reactive<Record<Key, Cell>>({
  cpu: { data: null, loading: false },
  memory: { data: null, loading: false },
  network: { data: null, loading: false },
  disk: { data: null, loading: false },
})
const ranges = reactive<Record<Key, number>>({ cpu: 30, memory: 30, network: 30, disk: 30 })

/**
 * 无「容量口径」可比的两张 IO 图（网络 / 磁盘）：集群级聚合，没有 requests/allocatable 的说法，
 * 所以只有曲线、没有 tile（对齐命名空间概览的 4 图布局：CPU / 内存 / 网络 IO / 磁盘 IO）。
 */
const IO_CHARTS: { key: Key; title: string; unit: string }[] = [
  { key: 'network', title: '网络 IO', unit: '字节/秒' },
  { key: 'disk', title: '磁盘 IO', unit: '字节/秒' },
]

async function fetchOne(key: Key): Promise<void> {
  if (!props.clusterId) return
  state[key].loading = true
  try {
    const end = Math.floor(Date.now() / 1000)
    const req: ClusterMetricsReq = { clusterId: props.clusterId, start: end - ranges[key] * 60, end }
    state[key].data = await clusterMetrics[key](req)
  } catch {
    state[key].data = null // 失败：该段显示「—」/「暂无数据」（拦截器已弹提示）
  } finally {
    state[key].loading = false
  }
}

async function fetchAll(): Promise<void> {
  if (!props.clusterId) return
  await Promise.all(KEYS.map((k) => fetchOne(k)))
}

// 每图时间范围变化 → 只重取该图；上下文变化 → 全部重取
KEYS.forEach((k) => watch(() => ranges[k], () => void fetchOne(k)))
watch(() => props.clusterId, fetchAll)
onMounted(fetchAll)

defineExpose({ refresh: fetchAll })

/** 当前 used = 曲线最后一个采样点（Thanos 当前值即最近一个 point）；无数据 null */
function usedOf(key: Key): number | null {
  const points = state[key].data?.series?.[0]?.points
  const last = points?.[points.length - 1]
  return last ? last.value : null
}

/** 三口径文本：null/undefined → 「—」 */
function fmt(v: number | null | undefined, unit: string): string {
  return v == null ? '—' : formatMetricValue(v, unit, false)
}


/**
 * **调度水位** = requests / allocatable：节点可分配量被"申领"掉多少。100% = 排满，新 pod 会 Pending。
 * ⚠️ 它**不是**超卖 —— 正常调度下 Σrequests 恒 ≤ allocatable（调度器只在新 pod 的 requests 放得下时才绑定），
 * 所以 >100% 反而是异常（有 pod 绕过调度器、如直接指定 nodeName，或节点减少）。
 */
function waterline(requests: number | null | undefined, allocatable: number | null | undefined): number | null {
  return ratio(requests, allocatable)
}

/**
 * **limits 占比** = limits / allocatable。**这是中性名** —— 它只表达"承诺的总量是可分配量的多少倍"。
 * 只有 **> 100%（= 1）才叫超卖**；≤100% 是**健康**的（承诺量还没用满可分配量，甚至最坏情况都放得下）。
 * 故 UI 上不要把它无条件写成「超卖」（那会让 62% 这种健康值被误读成告警）。
 */
function limitRatio(limit: number | null | undefined, allocatable: number | null | undefined): number | null {
  return ratio(limit, allocatable)
}

function ratio(numerator: number | null | undefined, denominator: number | null | undefined): number | null {
  if (numerator == null || denominator == null || denominator <= 0) return null
  return numerator / denominator
}

/** 比值 → 百分比文本；<10% 时留一位小数（免得 0.4% 变成 0%），否则取整。 */
function ratioText(r: number | null): string {
  if (r == null) return '—'
  return `${r < 0.1 ? (r * 100).toFixed(1) : (r * 100).toFixed(0)}%`
}

const blocks = computed(() => {
  /**
   * 单位**刻意写死**为平台约定（cpu=核、内存=字节），不取 Thanos 响应里的 unit：
   * 同一行里 allocatable/requests/limit 来自 K8s 快照、used 来自 Thanos，是**两个独立来源**；
   * 若跟随响应单位，一旦 Thanos 那边换了单位，使用率（used ÷ allocatable）就会按错误比例算。
   */
  const build = (key: Key, title: string, unit: string,
                 allocatable: number | null, requests: number | null, limit: number | null) => ({
    key,
    title,
    unit,
    allocatable,
    requests,
    limit,
    used: usedOf(key),
    /** 调度水位 = requests / allocatable */
    waterline: waterline(requests, allocatable),
    /** limits 占比 = limits / allocatable（**中性名**；> 1 才是超卖，见模板里的「已超卖」标记） */
    limitRatio: limitRatio(limit, allocatable),
  })
  return [
    build('cpu', 'CPU', '核', props.capacity?.cpuAllocatable ?? null,
        props.total?.cpuRequest ?? null, props.total?.cpuLimit ?? null),
    build('memory', '内存', '字节', props.capacity?.memoryAllocatable ?? null,
        props.total?.memRequest ?? null, props.total?.memLimit ?? null),
  ]
})
</script>

<template>
  <section class="metrics-panel">
    <div v-for="b in blocks" :key="b.key" class="res-block">
      <div class="rb-head">
        <h3 class="rb-title">{{ b.title }}</h3>
        <span class="muted rb-sub">
          调度水位 {{ ratioText(b.waterline) }} · limits 占比 {{ ratioText(b.limitRatio) }}
        </span>
        <!-- 「超卖」只在 >100% 时才作为判定出现：62% 那种是健康水平，写成「超卖 62%」是误导 -->
        <el-tag v-if="b.limitRatio != null && b.limitRatio > 1" size="small" type="warning" class="rb-flag">已超卖</el-tag>
      </div>

      <div class="stat-grid">
        <div class="stat-tile">
          <div class="stat-value">{{ fmt(b.allocatable, b.unit) }}</div>
          <div class="stat-label">节点可分配量合计</div>
        </div>
        <div class="stat-tile">
          <div class="stat-value">{{ fmt(b.requests, b.unit) }}</div>
          <div class="stat-label">pod requests</div>
        </div>
        <div class="stat-tile">
          <div class="stat-value">{{ fmt(b.used, b.unit) }}</div>
          <div class="stat-label">used（实时用量）</div>
        </div>
      </div>

      <div class="limit-line muted">
        调度水位：requests {{ fmt(b.requests, b.unit) }} / allocatable {{ fmt(b.allocatable, b.unit) }}（100% = 排满，新 pod 会 Pending；
        &gt;100% 属异常 —— 正常调度下 requests 之和恒不超可分配量）
      </div>
      <div class="limit-line muted">
        limits 占比：limits {{ fmt(b.limit, b.unit) }} / allocatable {{ fmt(b.allocatable, b.unit) }}（≤100% 是健康水平：最坏情况都放得下；
        <b>&gt;100% 才是超卖</b>，靠"容器很少同时打满 limits"挤进去）
      </div>

      <MetricChart
        :title="`${b.title} 实时用量`"
        :unit="b.unit"
        :series="state[b.key].data?.series ?? []"
        :loading="state[b.key].loading"
        percentable
        :limit="b.allocatable"
        :ranges="RANGES"
        :range="ranges[b.key]"
        @update:range="(v) => (ranges[b.key] = v)"
      />
    </div>

    <!-- 网络 / 磁盘 IO：无 requests/allocatable 口径可比，所以只有曲线（对齐命名空间概览的 4 图布局） -->
    <div v-for="c in IO_CHARTS" :key="c.key" class="io-block">
      <MetricChart
        :title="c.title"
        :unit="c.unit"
        :series="state[c.key].data?.series ?? []"
        :loading="state[c.key].loading"
        :ranges="RANGES"
        :range="ranges[c.key]"
        @update:range="(v) => (ranges[c.key] = v)"
      />
    </div>
  </section>
</template>

<style scoped>
.metrics-panel { display: flex; flex-direction: column; gap: 20px; }
.res-block { display: flex; flex-direction: column; gap: 10px; }
/* 网络 / 磁盘两图：与资源块同间距，各自一张卡（MetricChart 自带标题） */
.io-block { display: flex; flex-direction: column; gap: 10px; }
.rb-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.rb-title { margin: 0; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.rb-sub { font-size: 12px; }
/* 「已超卖」标记：只在 limits 占比 > 100% 时出现，别让它跟着数值一起抖动 */
.rb-flag { margin-left: -4px; }
.limit-line { font-size: 12px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; }
.stat-tile { padding: 14px 16px; border: 1px solid var(--border); border-radius: 8px; background: var(--panel-hover); }
.stat-value { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 20px; font-weight: 600; color: var(--text-1); word-break: break-all; }
.stat-label { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.muted { color: var(--text-3); }
</style>
