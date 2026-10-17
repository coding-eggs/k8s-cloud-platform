<script setup lang="ts">
/**
 * 服务网格拓扑（S2.4）：把集群里**真实发生**的调用关系画成一张有向图。
 *
 * <p><b>数据从哪来</b>：Thanos 里的 `istio_requests_total`（L7）。因此图里只有
 * **走外部网关的南北向**和**进了网格且挂了 waypoint 的东西向** —— 这是 Istio 的机制决定的，
 * 不是平台少画了。页面下方常驻一段说明，别删。
 *
 * <p><b>两种空态必须分开说</b>：`meshMetricsAvailable=false` 是「这个集群没在采集网格指标」，
 * 不是「没有流量」。两者主查询都是空的，后端靠与流量无关的哨兵 `count(istio_build)` 分开。
 */
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import YamlBlock from '@/components/YamlBlock.vue'
import { meshApi } from '@/api'
import { useResourceContext } from '@/stores/context'
import type { MeshTopology, MeshTopologyEdge, MeshTopologyNode } from '@/types'

echarts.use([GraphChart, TooltipComponent, CanvasRenderer])

const ctx = useResourceContext()

// ---- 查询参数 ----
// 集群 / 命名空间**来自顶栏级联**（本路由 context:'full'），页内不再放一套下拉。
// 拓扑本身是集群级视图：默认看全集群（否则网关那些边会因为源在 istio-system 而消失），
// 「仅当前命名空间」是给想收窄的人开关。
const clusterId = computed(() => ctx.state.clusterId ?? '')
const scopeToNamespace = ref(false)
/** 顶栏当前命名空间（用于「仅当前命名空间」的文案与禁用态） */
const currentNamespace = computed(() => ctx.state.namespace ?? '')
const namespace = computed(() => (scopeToNamespace.value ? currentNamespace.value : ''))
const windowSeconds = ref(300)
const WINDOWS = [
  { label: '5 分钟', value: 300 },
  { label: '1 小时', value: 3600 },
  { label: '24 小时', value: 86400 },
]

// ---- 数据 ----
const loading = ref(false)
const topo = ref<MeshTopology | null>(null)
/** 「仍要试试」：用户在确认没采集之后仍想看（此时空图是唯一结果，但让用户自己决定） */
const forced = ref(false)

const showChart = computed(() => !!topo.value && (topo.value.meshMetricsAvailable || forced.value))
const notScraped = computed(() => !!topo.value && !topo.value.meshMetricsAvailable && !forced.value)
const scrapedButEmpty = computed(
  () => !!topo.value && topo.value.meshMetricsAvailable && topo.value.edges.length === 0,
)
let timer: number | undefined

async function load(): Promise<void> {
  if (!clusterId.value) return
  loading.value = true
  try {
    topo.value = await meshApi.topology(clusterId.value, namespace.value || undefined, windowSeconds.value)
  } catch {
    topo.value = null // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 30 秒自动刷新；页面不可见时跳过一次（回来时由 watch/手动刷新补齐） */
function startTimer(): void {
  stopTimer()
  timer = window.setInterval(() => {
    if (document.visibilityState !== 'visible') return
    void load()
  }, 30_000)
}
function stopTimer(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

watch([clusterId, namespace, windowSeconds], () => {
  forced.value = false
  void load()
})

// ---- ECharts ----
const chartEl = ref<HTMLDivElement>()
/** 主题切换时自增：让依赖 cssVar 的 computed 重新求值（canvas 不跟随 CSS 变量） */
const themeTick = ref(0)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null

/** canvas 读不到 CSS 变量：解析成当前生效的具体色值（.dark 切换后重绘即取到新值）。 */
function cssVar(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** 按命名空间分色：同一 ns 一个色，眼睛能顺着颜色分组。 */
const NS_PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#9c6ade', '#14b8a6', '#f56c6c', '#8b93a8']
function nsColor(ns: string): string {
  let h = 0
  for (let i = 0; i < ns.length; i++) h = (h * 31 + ns.charCodeAt(i)) >>> 0
  return NS_PALETTE[h % NS_PALETTE.length] ?? '#409eff'
}

/** 边宽 ∝ log10(rps)。**必须取对数**：实测有一条 26 万次的边，线性的结果是它一条撑满、其余全被压成一根线。 */
function edgeWidth(rps: number): number {
  return 1 + 5 * Math.min(1, Math.log10(Math.max(rps, 0) + 1) / 6)
}

/** 错误率分档：0 绿 / >0 黄 / >5% 红。 */
function edgeColor(errorRate: number): string {
  if (errorRate > 0.05) return cssVar('--danger', '#f56c6c')
  if (errorRate > 0) return cssVar('--warning', '#e6a23c')
  return cssVar('--success', '#67c23a')
}

/** ⚠️ null 显示「—」而不是 0 —— 0 会被读成"延迟为 0ms"，那是假信息。 */
function num(v: number | null | undefined, digits = 2): string {
  return v == null ? '—' : v.toFixed(digits)
}

function labelOf(id: string): string {
  return id.substring(id.indexOf('/') + 1)
}

const chartNodes = computed(() => {
  void themeTick.value // 主题变量是依赖
  const degree = new Map<string, number>()
  for (const e of topo.value?.edges ?? []) {
    degree.set(e.source, (degree.get(e.source) ?? 0) + 1)
    degree.set(e.target, (degree.get(e.target) ?? 0) + 1)
  }
  return (topo.value?.nodes ?? []).map((n: MeshTopologyNode) => ({
    id: n.id,
    name: n.id,
    workload: n.workload,
    namespace: n.namespace,
    gateway: n.gateway,
    symbol: n.gateway ? 'diamond' : 'circle',
    symbolSize: 18 + Math.min((degree.get(n.id) ?? 0) * 2, 18),
    itemStyle: { color: n.gateway ? cssVar('--accent', '#6f7cff') : nsColor(n.namespace) },
  }))
})

const chartLinks = computed(() => {
  void themeTick.value // 主题变量是依赖
  return (topo.value?.edges ?? []).map((e: MeshTopologyEdge) => ({
    source: e.source,
    target: e.target,
    sourceName: labelOf(e.source),
    targetName: labelOf(e.target),
    rps: e.rps,
    errorRate: e.errorRate,
    p95Ms: e.p95Ms,
    lineStyle: { width: edgeWidth(e.rps), color: edgeColor(e.errorRate), curveness: 0.12 },
  }))
})

function buildOption(): echarts.EChartsCoreOption {
  const panel = cssVar('--panel', '#ffffff')
  const border = cssVar('--border', '#e6eaf2')
  const text1 = cssVar('--text-1', '#1e293b')
  return {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: panel,
      borderColor: border,
      borderWidth: 1,
      textStyle: { color: text1 },
      formatter: (p: any) => {
        const d = p.data ?? {}
        if (p.dataType === 'edge') {
          return `${d.sourceName} → ${d.targetName}<br/>`
            + `QPS ${num(d.rps)}<br/>`
            + `错误率 ${(d.errorRate * 100).toFixed(1)}%<br/>`
            + `P95 ${num(d.p95Ms, 1)} ms`
        }
        return `${d.workload}<br/>命名空间 ${d.namespace}${d.gateway ? '<br/>（外部网关）' : ''}`
      },
    },
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,          // 查看态：可缩放拖拽，但不做"摆位"（那是编辑态的需求，见流水线画布）
      draggable: true,
      force: { repulsion: 320, edgeLength: [90, 220], gravity: 0.08 },
      label: { show: true, position: 'right', formatter: (p: any) => p.data?.workload ?? '' },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 7,
      emphasis: { focus: 'adjacency' },
      data: chartNodes.value,
      links: chartLinks.value,
    }],
  }
}

function render(): void {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

// ---- 详情抽屉 ----
const drawer = ref(false)
const selectedNode = ref<MeshTopologyNode | null>(null)
const selectedEdge = ref<MeshTopologyEdge | null>(null)

/** 这条边对应的 PromQL —— 用户可以拿去 Grafana 自己复核（spec §4）。 */
const edgePromql = computed(() => {
  const e = selectedEdge.value
  if (!e) return ''
  const sNs = e.source.substring(0, e.source.indexOf('/'))
  const dNs = e.target.substring(0, e.target.indexOf('/'))
  return `sum by (source_workload, destination_workload, source_workload_namespace, destination_workload_namespace) (`
    + `rate(istio_requests_total{cluster_name="${ctx.currentCluster.value?.clusterName ?? '<集群名>'}",`
    + `source_workload_namespace="${sNs}",destination_workload_namespace="${dNs}"}[${windowSeconds.value}s]))`
})

function onChartClick(params: any): void {
  const d = params.data
  if (!d) return
  if (params.dataType === 'edge') {
    selectedEdge.value = d as MeshTopologyEdge
    selectedNode.value = null
  } else {
    selectedNode.value = (topo.value?.nodes ?? []).find((n) => n.id === d.id) ?? null
    selectedEdge.value = null
  }
  drawer.value = true
}

// ---- 生命周期 ----
function attachChart(): void {
  if (!chartEl.value || chart) return
  chart = echarts.init(chartEl.value)
  chart.on('click', onChartClick)
  render()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(chartEl.value)
}

function detachChart(): void {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
}

onMounted(async () => {
  // 集群来自顶栏级联。ctx.load() 会按持久化选择校验并补齐缺级：
  // 补出集群时 watch(clusterId) 自动首查；本来就选好时 watch 不会触发，这里补一次。
  const alreadySelected = !!clusterId.value
  await ctx.load()
  if (alreadySelected && !topo.value) await load()
  startTimer()
})

onBeforeUnmount(() => {
  stopTimer()
  themeObserver?.disconnect()
  themeObserver = null
  detachChart()
})

// 有图才挂画布；空态/未采集时不实例化（否则容器 0 高，ECharts 会告警）
// ⚠️ 必须用 nextTick：watch 是 pre-flush，跑在本次渲染**之前**，
//    只 await 一个 microtask 时 `.chart` 还没进 DOM → attachChart 拿到 null → 空白画布。
watch(showChart, async (show) => {
  if (show) {
    await nextTick()
    attachChart()
    render()
  } else {
    detachChart()
  }
})
watch([chartNodes, chartLinks], render)

onMounted(() => {
  themeObserver = new MutationObserver((muts) => {
    if (muts.some((m) => m.type === 'attributes' && m.attributeName === 'class')) {
      themeTick.value++
      render()
    }
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})
</script>

<template>
  <div class="mesh-topology">
    <PageHeader title="网格拓扑" description="谁在调谁：节点 = 工作负载，边 = 调用（粗细 = QPS，颜色 = 错误率）">
      <el-checkbox v-model="scopeToNamespace" :disabled="!currentNamespace">
        仅当前命名空间<template v-if="currentNamespace">（{{ currentNamespace }}）</template>
      </el-checkbox>
      <el-radio-group v-model="windowSeconds" size="small">
        <el-radio-button v-for="w in WINDOWS" :key="w.value" :value="w.value">{{ w.label }}</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" circle :loading="loading" @click="load" />
    </PageHeader>

    <!-- ① 没选集群 -->
    <el-empty v-if="!clusterId" description="请在顶栏选择集群" />

    <!-- ② 没接采集：这不是空图能表达的事 -->
    <el-empty v-else-if="notScraped">
      <template #description>
        <div class="empty-title">这个集群<strong>没有在采集</strong>网格指标</div>
        <div class="empty-hint">
          不是"没有流量"，而是代理的 /metrics 没人抓。怎么接见
          <code>docs/development/cluster-prerequisites.md</code> §1.7。
        </div>
      </template>
      <el-button @click="forced = true">仍要试试</el-button>
    </el-empty>

    <template v-else>
      <div v-if="scrapedButEmpty" class="empty-banner">
        在采集，但<strong>这段时间没有 L7 调用</strong> —— 可能是确实没有流量，也可能是网格里没有
        负载、或目标服务没挂 waypoint。把时间窗调大再看。
      </div>

      <div class="chart-wrap" v-loading="loading">
        <div ref="chartEl" class="chart"></div>
      </div>
    </template>

    <!-- 覆盖范围：常驻，别删（用户会以为自己看到了全部调用） -->
    <p class="coverage">
      图里只有：走<strong>外部网关</strong>的南北向，和<strong>进了网格且挂了 waypoint</strong> 的东西向。
      没被纳入网格的负载互相调用、以及网关之外的集群外来源，<strong>一条都不在这里</strong> ——
      这是 Istio 的机制决定的，不是平台少画了。
    </p>

    <el-drawer v-model="drawer" :title="selectedEdge ? '调用详情' : '节点详情'" size="420px">
      <template v-if="selectedEdge">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="调用">{{ selectedEdge.source }} → {{ selectedEdge.target }}</el-descriptions-item>
          <el-descriptions-item label="QPS">{{ num(selectedEdge.rps) }}</el-descriptions-item>
          <el-descriptions-item label="错误率">{{ (selectedEdge.errorRate * 100).toFixed(1) }}%</el-descriptions-item>
          <el-descriptions-item label="P95">{{ num(selectedEdge.p95Ms, 1) }} ms</el-descriptions-item>
        </el-descriptions>
        <p class="drawer-hint">这条边对应的 PromQL（可拿去 Grafana 自己复核）：</p>
        <YamlBlock :yaml="edgePromql" max-height="30vh" />
      </template>
      <template v-else-if="selectedNode">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="工作负载">{{ selectedNode.workload }}</el-descriptions-item>
          <el-descriptions-item label="命名空间">{{ selectedNode.namespace }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ selectedNode.gateway ? '外部网关（南北向入口）' : '业务工作负载' }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.mesh-topology { display: flex; flex-direction: column; gap: 12px; }
.chart-wrap {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel, #fff);
  min-height: 520px;
}
.chart { width: 100%; height: 520px; }
.empty-title { font-size: 15px; font-weight: 700; color: var(--text-1); }
.empty-hint { margin-top: 6px; font-size: 12.5px; color: var(--text-3); line-height: 1.7; }
.empty-hint code { background: var(--fill-2, #f1f5f9); padding: 1px 5px; border-radius: 4px; }
.empty-banner {
  padding: 10px 14px;
  border: 1px solid var(--warning-border, #f5dab1);
  background: var(--warning-bg, #fdf6ec);
  color: var(--text-2);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.7;
}
.coverage {
  margin: 0;
  padding: 10px 14px;
  border: 1px dashed var(--border);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.75;
}
.drawer-hint { margin: 14px 0 6px; font-size: 12.5px; color: var(--text-3); }
</style>
