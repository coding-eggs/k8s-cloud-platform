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
import { useRoute, useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import YamlBlock from '@/components/YamlBlock.vue'
import { meshApi } from '@/api'
import { useResourceContext } from '@/stores/context'
import type { MeshTopology, MeshTopologyEdge, MeshTopologyNode } from '@/types'

echarts.use([GraphChart, TooltipComponent, CanvasRenderer])

const ctx = useResourceContext()
const route = useRoute()
const router = useRouter()

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

// ---- 聚焦（?focus=ns/workload，逗号分隔；从 Service 页「在拓扑里看」过来）----
const focusIds = computed(() =>
  String(route.query.focus ?? '').split(',').map((s) => s.trim()).filter(Boolean),
)
/** 画布上要保留的节点集合：命中的 focus 节点 + 它们的直接邻居。null = 不聚焦 */
const focusKeep = computed<Set<string> | null>(() => {
  const ids = focusIds.value
  const t = topo.value
  if (!ids.length || !t) return null
  const known = new Set(t.nodes.map((n) => n.id))
  const keep = new Set(ids.filter((id) => known.has(id)))
  if (!keep.size) return null
  for (const e of t.edges) {
    if (keep.has(e.source)) keep.add(e.target)
    if (keep.has(e.target)) keep.add(e.source)
  }
  return keep
})
/** off = 没带 focus；on = 已聚焦；missing = 带了但图里找不到（多半是没有 L7 流量） */
const focusState = computed<'off' | 'on' | 'missing'>(() => {
  if (!focusIds.value.length || !topo.value) return 'off'
  return focusKeep.value ? 'on' : 'missing'
})

/** 实际参与画布渲染的拓扑（聚焦后只留子图） */
const viewTopo = computed<MeshTopology | null>(() => {
  const t = topo.value
  const keep = focusKeep.value
  if (!t || !keep) return t
  return {
    ...t,
    nodes: t.nodes.filter((n) => keep.has(n.id)),
    edges: t.edges.filter((e) => keep.has(e.source) && keep.has(e.target)),
  }
})

function clearFocus(): void {
  const q = { ...route.query }
  delete q.focus
  void router.replace({ query: q })
}

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
  dragOffsets.clear()   // 换了范围 = 换了一张图，旧的手工位置不再适用
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

/** L4 的边宽按**字节/秒**（log10）。与 QPS 不同量纲，别指望两根线的宽度可直接比较。 */
function bytesWidth(bytes: number): number {
  return 1 + 4 * Math.min(1, Math.log10(Math.max(bytes, 0) + 1) / 7)
}

/** 字节/秒 → 人能读的单位。 */
function bytesText(v: number | null | undefined): string {
  if (v == null) return '—'
  if (v >= 1024 * 1024) return `${(v / 1024 / 1024).toFixed(2)} MB/s`
  if (v >= 1024) return `${(v / 1024).toFixed(1)} KB/s`
  return `${v.toFixed(0)} B/s`
}

/** 错误率分档：0 绿 / >0 黄 / >5% 红。 */
function edgeColor(errorRate: number): string {
  if (errorRate > 0.05) return cssVar('--danger', '#f56c6c')
  if (errorRate > 0) return cssVar('--warning', '#e6a23c')
  return cssVar('--success', '#67c23a')
}

/** ⚠️ null 显示「—」而不是 0 —— 0 会被读成"延迟为 0ms"，那是假信息。 */
/** 节点 tooltip 里的健康摘要。阈值与 healthOf 保持一致。 */
function healthText(h?: NodeHealth): string {
  if (!h || h.level === 'unknown') return '无入向流量（不判定健康）'
  return `入向 ${num(h.inboundRps, 1)} QPS · 错误率 ${(h.errorRate * 100).toFixed(1)}% · P95 ${num(h.p95Ms, 0)} ms`
}

function num(v: number | null | undefined, digits = 2): string {
  return v == null ? '—' : v.toFixed(digits)
}

function labelOf(id: string): string {
  return id.substring(id.indexOf('/') + 1)
}

/** 健康判定阈值（先写死，图例上明说；将来要可配再挪到后端）。 */
const HEALTH_ERROR_RED = 0.05      // 错误率 ≥ 5%
const HEALTH_P95_RED = 1000        // P95 ≥ 1s
const HEALTH_P95_WARN = 300        // P95 ≥ 300ms

type HealthLevel = 'ok' | 'warn' | 'bad' | 'unknown'

interface NodeHealth {
  level: HealthLevel
  /** 入向 QPS（所有入向边之和）；0 表示这条判断没有依据 */
  inboundRps: number
  /** 按入向 QPS 加权的错误率 */
  errorRate: number
  /** 入向边的最大 P95；没有样本为 null */
  p95Ms: number | null
}

/**
 * 节点健康**只看入向边**：一条边的错误率/延迟描述的是**目标端**的表现，
 * 拿它去染源节点会把别人的错误记在你头上。
 * ⚠️ 没有入向流量（外部网关那种）→ unknown，保留原来的着色，不假装健康。
 */
function healthOf(id: string, edges: MeshTopologyEdge[]): NodeHealth {
  let rps = 0
  let weightedErr = 0
  let p95: number | null = null
  for (const e of edges) {
    if (e.target !== id) continue
    if (e.p95Ms != null) p95 = p95 == null ? e.p95Ms : Math.max(p95, e.p95Ms)
    if (e.rps <= 0) continue          // 0 QPS 的边（窗口内没调用）不参与加权
    rps += e.rps
    weightedErr += e.errorRate * e.rps
  }
  if (rps <= 0) {
    // 只有延迟样本、没有 QPS 的极端情况也不判定，避免用半截信息下结论
    return { level: 'unknown', inboundRps: 0, errorRate: 0, p95Ms: p95 }
  }
  const errorRate = weightedErr / rps
  const level: HealthLevel =
    errorRate >= HEALTH_ERROR_RED || (p95 != null && p95 >= HEALTH_P95_RED) ? 'bad'
      : errorRate > 0 || (p95 != null && p95 >= HEALTH_P95_WARN) ? 'warn'
        : 'ok'
  return { level, inboundRps: rps, errorRate, p95Ms: p95 }
}

/**
 * ⚠️ **必须排序**：Prometheus 不保证 `sum by(...)` 返回序列的顺序稳定。
 * 顺序一变，力导向的初始位就变，用户会觉得"每次重进画布都不一样"。
 * 按 id 排 + 下面 `force.initLayout: 'circular'` = 同一份数据永远收敛到同一张图。
 */
const chartNodes = computed(() => {
  void themeTick.value // 主题变量是依赖
  const edges = viewTopo.value?.edges ?? []
  const degree = new Map<string, number>()
  for (const e of edges) {
    degree.set(e.source, (degree.get(e.source) ?? 0) + 1)
    degree.set(e.target, (degree.get(e.target) ?? 0) + 1)
  }
  const success = cssVar('--success', '#67c23a')
  const warning = cssVar('--warning', '#e6a23c')
  const danger = cssVar('--danger', '#f56c6c')
  return [...(viewTopo.value?.nodes ?? [])]
    .sort((a, b) => a.id.localeCompare(b.id))
    .map((n: MeshTopologyNode) => {
      const health = healthOf(n.id, edges)
      const healthColor =
        health.level === 'bad' ? danger
          : health.level === 'warn' ? warning
            : health.level === 'ok' ? success
              : null
      return {
        id: n.id,
        name: n.id,
        workload: n.workload,
        namespace: n.namespace,
        gateway: n.gateway,
        health,
        symbol: n.gateway ? 'diamond' : 'circle',
        symbolSize: 18 + Math.min((degree.get(n.id) ?? 0) * 2, 18),
        itemStyle: {
          // 有入向流量 → 用健康色；没有 → 保留原来的命名空间色（不假装健康）
          color: healthColor ?? (n.gateway ? cssVar('--accent', '#6f7cff') : nsColor(n.namespace)),
          // 描边始终是命名空间色：颜色说健康，边框说归属，两个信号都不丢
          borderColor: nsColor(n.namespace),
          borderWidth: healthColor ? 2 : 0,
        },
      }
    })
})

/** 抽屉按 id 取健康（chartNodes 里已经算过，不重复算一遍） */
const healthById = computed(() => new Map(chartNodes.value.map((n) => [n.id, n.health])))

const chartLinks = computed(() => {
  void themeTick.value // 主题变量是依赖
  const l4Color = cssVar('--text-3', '#94a3b8')
  return [...(viewTopo.value?.edges ?? [])]
    .sort((a, b) => a.source.localeCompare(b.source) || a.target.localeCompare(b.target))
    .map((e: MeshTopologyEdge) => {
      const l4 = e.level === 'L4'
      return {
        source: e.source,
        target: e.target,
        sourceName: labelOf(e.source),
        targetName: labelOf(e.target),
        level: e.level,
        rps: e.rps,
        errorRate: e.errorRate,
        p95Ms: e.p95Ms,
        bytesPerSec: e.bytesPerSec,
        lineStyle: l4
          // L4 没有响应码，不能用错误率上色；虚线 + 中性色表示"只看到连接与字节"
          ? { width: bytesWidth(e.bytesPerSec ?? 0), color: l4Color, type: 'dashed', opacity: 0.85, curveness: 0.12 }
          : { width: edgeWidth(e.rps), color: edgeColor(e.errorRate), curveness: 0.12 },
      }
    })
})

/**
 * 分层布局：把"谁调谁"画成**从左到右**的流，层内上下排。
 * 静态计算、没有模拟 —— 不会晃，也不会每次刷新重新收敛。
 * 步骤：断环 → 最长路径定层 → 层内重心排序（减交叉）→ 摆坐标。
 */
function computeLayout(
  nodes: { id: string }[],
  edges: { source: string; target: string }[],
  w: number,
  h: number,
): Map<string, [number, number]> {
  const ids = nodes.map((n) => n.id)
  const known = new Set(ids)
  const succ = new Map<string, string[]>(ids.map((id) => [id, []]))
  const pred = new Map<string, string[]>(ids.map((id) => [id, []]))
  for (const e of edges) {
    if (!known.has(e.source) || !known.has(e.target) || e.source === e.target) continue
    succ.get(e.source)!.push(e.target)
    pred.get(e.target)!.push(e.source)
  }

  // ① 断环：DFS 里指向"正在访问"的边是回边，分层时忽略（节点本身照画）
  const backEdges = new Set<string>()
  const state = new Map<string, 0 | 1 | 2>(ids.map((id) => [id, 0]))
  const isBack = (u: string, v: string): boolean => backEdges.has(`${u}\u0000${v}`)
  const visit = (u: string): void => {
    state.set(u, 1)
    for (const v of succ.get(u)!) {
      const s = state.get(v)!
      if (s === 1) backEdges.add(`${u}\u0000${v}`)
      else if (s === 0) visit(v)
    }
    state.set(u, 2)
  }
  for (const id of ids) if (state.get(id) === 0) visit(id)

  // ② 分层 = 无环图上的最长路径（保证每条边都从左指向右）
  const rank = new Map<string, number>(ids.map((id) => [id, 0]))
  const indeg = new Map<string, number>(ids.map((id) => [id, 0]))
  for (const [u, vs] of succ) for (const v of vs) if (!isBack(u, v)) indeg.set(v, indeg.get(v)! + 1)
  const queue = ids.filter((id) => indeg.get(id) === 0)
  while (queue.length) {
    const u = queue.shift()!
    for (const v of succ.get(u)!) {
      if (isBack(u, v)) continue
      rank.set(v, Math.max(rank.get(v)!, rank.get(u)! + 1))
      indeg.set(v, indeg.get(v)! - 1)
      if (indeg.get(v) === 0) queue.push(v)
    }
  }

  // ③ 层内排序：按前驱的平均位置（重心）排，减少交叉
  const byRank: string[][] = []
  for (const id of ids) (byRank[rank.get(id)!] ??= []).push(id)
  const layers = byRank.filter((l) => l && l.length)
  for (let i = 1; i < layers.length; i++) {
    const prevLayer = layers[i - 1]!
    const curLayer = layers[i]!
    const prev = new Map(prevLayer.map((id, idx) => [id, idx]))
    const bary = (id: string): number => {
      const ps = pred.get(id)!.filter((p) => prev.has(p))
      return ps.length ? ps.reduce((s, p) => s + prev.get(p)!, 0) / ps.length : Number.POSITIVE_INFINITY
    }
    curLayer.sort((a, b) => bary(a) - bary(b) || a.localeCompare(b))
  }

  // ④ 坐标：横向按层、纵向层内均匀分布；间距按画布大小收缩，保证一屏放得下
  const pos = new Map<string, [number, number]>()
  const cols = Math.max(layers.length, 1)
  const maxRows = Math.max(...layers.map((l) => l.length), 1)
  const xGap = cols > 1 ? Math.min(300, Math.max(150, (w - 160) / (cols - 1))) : 0
  const yGap = Math.min(90, Math.max(52, (h - 120) / Math.max(maxRows - 1, 1)))
  const x0 = Math.max(80, (w - (cols - 1) * xGap) / 2)
  layers.forEach((layer, col) => {
    const top = h / 2 - ((layer.length - 1) * yGap) / 2
    layer.forEach((id, row) => pos.set(id, [x0 + col * xGap, top + row * yGap]))
  })
  return pos
}

/** 用户拖过的位置（**只在内存里**，不落盘）。拖动是临时微调，不参与分层计算。 */
const dragOffsets = new Map<string, [number, number]>()

/** ECharts 不把节点坐标写回 option，只能读内部 data 的 layout（类型里是私有的）。 */
interface GraphInternals {
  getModel(): {
    getSeriesByIndex(index: number): {
      getData(): {
        count(): number
        getRawDataItem(index: number): { id?: string } | null
        getItemLayout(index: number): number[] | undefined
      }
    }
  }
}

/** 读**当前画布**的节点坐标（拖动结束后用）。 */
function readCurrentPositions(): void {
  if (!chart) return
  try {
    const data = (chart as unknown as GraphInternals).getModel().getSeriesByIndex(0).getData()
    for (let i = 0; i < data.count(); i++) {
      const item = data.getRawDataItem(i)
      const l = data.getItemLayout(i)
      const x = l?.[0] ?? NaN
      const y = l?.[1] ?? NaN
      if (item?.id && Number.isFinite(x) && Number.isFinite(y)) dragOffsets.set(item.id, [x, y])
    }
  } catch {
    /* 内部 API 变了就不记拖动位置，不影响看图 */
  }
}

/** 当前的缩放/平移。notMerge 重画会把 roam 打回默认，得手动接上（用户每 30 秒被拉回默认视野同样是"跳一下"）。 */
function currentRoam(): { center?: number[]; zoom?: number } {
  try {
    const s = (chart?.getOption() as { series?: Array<{ center?: unknown; zoom?: unknown }> } | undefined)?.series?.[0]
    return {
      center: Array.isArray(s?.center) ? (s.center as number[]) : undefined,
      zoom: typeof s?.zoom === 'number' ? s.zoom : undefined,
    }
  } catch {
    return {}
  }
}

function buildOption(): echarts.EChartsCoreOption {
  const panel = cssVar('--panel', '#ffffff')
  const border = cssVar('--border', '#e6eaf2')
  const text1 = cssVar('--text-1', '#1e293b')

  const nodes = chartNodes.value
  const w = chart?.getWidth() || 900
  const h = chart?.getHeight() || 620
  // 每帧都重算：纯函数、成本可忽略；结果是确定性的，数据不变图就不变
  const layout = computeLayout(nodes, chartLinks.value, w, h)
  const data = nodes.map((node) => {
    const p = dragOffsets.get(node.id) ?? layout.get(node.id) ?? [w / 2, h / 2]
    return { ...node, x: p[0], y: p[1] }
  })
  return {
    // 位置是算出来的，别让 ECharts 再做过渡动画（那正是"晃"的来源）
    animation: false,
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: panel,
      borderColor: border,
      borderWidth: 1,
      textStyle: { color: text1 },
      formatter: (p: any) => {
        const d = p.data ?? {}
        if (p.dataType === 'edge') {
          if (d.level === 'L4') {
            return `${d.sourceName} → ${d.targetName}<br/>`
              + `L4（只看得到连接与字节，没有响应码）<br/>`
              + `流量 ${bytesText(d.bytesPerSec)}`
          }
          return `${d.sourceName} → ${d.targetName}<br/>`
            + `L7 · QPS ${num(d.rps)}<br/>`
            + `错误率 ${(d.errorRate * 100).toFixed(1)}%<br/>`
            + `P95 ${num(d.p95Ms, 1)} ms`
            + (d.bytesPerSec != null ? `<br/>L4 流量 ${bytesText(d.bytesPerSec)}` : '')
        }
        return `${d.workload}<br/>命名空间 ${d.namespace}${d.gateway ? '<br/>（外部网关）' : ''}`
          + `<br/>${healthText(d.health)}`
      },
    },
    series: [{
      type: 'graph',
      layout: 'none',      // 坐标全由 computeLayout 算好，ECharts 不跑任何布局模拟
      roam: true,          // 查看态：可缩放拖拽，但不做"摆位"（那是编辑态的需求，见流水线画布）
      draggable: true,
      // 标签放在节点**下方**并截断：左→右流动时，右侧标签会顶到下一列
      label: {
        show: true,
        position: 'bottom',
        distance: 6,
        fontSize: 11,
        width: 150,
        overflow: 'truncate',
        formatter: (p: any) => p.data?.workload ?? '',
      },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 7,
      emphasis: { focus: 'adjacency' },
      ...currentRoam(),
      data,
      links: chartLinks.value,
    }],
  }
}

function render(): void {
  if (!chart) return
  // ⚠️ **notMerge**：merge 模式下 graph 的 links 不会真正更新（实测 rps 一直停在旧值），
  // 所以每次都整体重画。重画不会晃：坐标是算出来的、且关掉了动画。
  chart.setOption(buildOption(), true)
}

// ---- 详情抽屉 ----
const drawer = ref(false)
const selectedNode = ref<MeshTopologyNode | null>(null)
const selectedEdge = ref<MeshTopologyEdge | null>(null)

/** 这条边对应的 PromQL —— 用户可以拿去 Grafana 自己复核（spec §4）。L4/L7 用的是不同指标。 */
const edgePromql = computed(() => {
  const e = selectedEdge.value
  if (!e) return ''
  const sNs = e.source.substring(0, e.source.indexOf('/'))
  const dNs = e.target.substring(0, e.target.indexOf('/'))
  const sel = `cluster_name="${ctx.currentCluster.value?.clusterName ?? '<集群名>'}",`
    + `source_workload_namespace="${sNs}",destination_workload_namespace="${dNs}"`
  if (e.level === 'L4') {
    return `sum by (source_workload, destination_workload) (rate(istio_tcp_sent_bytes_total{${sel}}[${windowSeconds.value}s]))`
  }
  return `sum by (source_workload, destination_workload) (`
    + `rate(istio_requests_total{${sel}}[${windowSeconds.value}s]))`
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
  // 用户拖动后记住位置（只在内存里），否则下一次自动刷新会把节点弹回布局位置
  chart.on('dragend', readCurrentPositions)
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
      <div v-if="focusState !== 'off'" class="focus-banner">
        <span v-if="focusState === 'on'">
          已聚焦：<code>{{ focusIds.join('、') }}</code> 及其上下游
        </span>
        <span v-else>
          拓扑里没有找到 <code>{{ focusIds.join('、') }}</code> —— 它可能这段时间没有 L7 流量，或不是工作负载
        </span>
        <el-button link type="primary" @click="clearFocus">看全图</el-button>
      </div>

      <div v-if="scrapedButEmpty" class="empty-banner">
        在采集，但<strong>这段时间没有 L7 调用</strong> —— 可能是确实没有流量，也可能是网格里没有
        负载、或目标服务没挂 waypoint。把时间窗调大再看。
      </div>

      <div class="chart-wrap" v-loading="loading">
        <div ref="chartEl" class="chart"></div>
      </div>

      <!-- 健康色图例：**颜色说健康，描边说命名空间**，两个信号别混 -->
      <div v-if="showChart" class="health-legend">
        <span><i class="dot ok"></i>正常</span>
        <span><i class="dot warn"></i>有错误或偏慢（P95 ≥ 300ms）</span>
        <span><i class="dot bad"></i>错误率 ≥ 5% 或 P95 ≥ 1s</span>
        <span><i class="dot unknown"></i>无入向流量（按命名空间着色）</span>
        <span class="sep"></span>
        <span><i class="line l7"></i>L7：有请求数 / 响应码 / 延迟</span>
        <span><i class="line l4"></i>L4：只有连接与字节</span>
        <span class="legend-note">描边 = 命名空间；健康只看 L7 入向边</span>
      </div>
    </template>

    <!-- 覆盖范围：常驻，别删（用户会以为自己看到了全部调用） -->
    <p class="coverage">
      <strong>实线（L7）</strong>：有请求数 / 响应码 / 延迟 —— 来自外部网关（南北向）和挂了 waypoint 的工作负载（东西向）。<br/>
      <strong>虚线（L4）</strong>：只有连接与字节 —— ztunnel 看到的全部 TCP，所以它的目标<strong>可能是不在网格里的</strong>
      工作负载（mysql / redis 这类），但调用本身是从网格内发起的。<br/>
      两层都看不见的：完全不经过 ztunnel 的流量。这是 Istio 的机制决定的，不是平台少画了。
    </p>

    <el-drawer v-model="drawer" :title="selectedEdge ? '调用详情' : '节点详情'" size="420px">
      <template v-if="selectedEdge">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="调用">{{ selectedEdge.source }} → {{ selectedEdge.target }}</el-descriptions-item>
          <el-descriptions-item label="层级">
            {{ selectedEdge.level }}（{{ selectedEdge.level === 'L7' ? '请求数 / 响应码 / 延迟' : '只有连接与字节，没有响应码' }}）
          </el-descriptions-item>
          <el-descriptions-item label="QPS">{{ selectedEdge.level === 'L7' ? num(selectedEdge.rps) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="错误率">{{ selectedEdge.level === 'L7' ? (selectedEdge.errorRate * 100).toFixed(1) + '%' : '—' }}</el-descriptions-item>
          <el-descriptions-item label="P95">{{ selectedEdge.level === 'L7' ? num(selectedEdge.p95Ms, 1) + ' ms' : '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="selectedEdge.bytesPerSec != null" label="L4 流量">{{ bytesText(selectedEdge.bytesPerSec) }}</el-descriptions-item>
        </el-descriptions>
        <p class="drawer-hint">这条边对应的 PromQL（可拿去 Grafana 自己复核）：</p>
        <YamlBlock :yaml="edgePromql" max-height="30vh" />
      </template>
      <template v-else-if="selectedNode">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="工作负载">{{ selectedNode.workload }}</el-descriptions-item>
          <el-descriptions-item label="命名空间">{{ selectedNode.namespace }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ selectedNode.gateway ? '外部网关（南北向入口）' : '业务工作负载' }}</el-descriptions-item>
          <el-descriptions-item label="健康">{{ healthText(healthById.get(selectedNode.id)) }}</el-descriptions-item>
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
  min-height: 620px;
}
.chart { width: 100%; height: 620px; }
.health-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  font-size: 12.5px;
  color: var(--text-3);
}
.health-legend .dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-right: 5px;
  vertical-align: middle;
}
.health-legend .dot.ok { background: var(--success, #67c23a); }
.health-legend .dot.warn { background: var(--warning, #e6a23c); }
.health-legend .dot.bad { background: var(--danger, #f56c6c); }
.health-legend .dot.unknown { background: var(--text-3, #94a3b8); }
.health-legend .sep { width: 1px; height: 12px; background: var(--border, #e6eaf2); }
.health-legend .line {
  display: inline-block;
  width: 18px;
  height: 0;
  border-top: 2px solid var(--text-3, #94a3b8);
  margin-right: 5px;
  vertical-align: middle;
}
.health-legend .line.l4 { border-top-style: dashed; }
.health-legend .legend-note { margin-left: auto; opacity: 0.8; }
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
.focus-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border: 1px solid var(--accent-border, #c7d2fe);
  background: var(--accent-bg, #eef2ff);
  color: var(--text-2);
  border-radius: 8px;
  font-size: 13px;
}
.focus-banner code {
  font-family: Consolas, 'JetBrains Mono', monospace;
  color: var(--text-1);
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
