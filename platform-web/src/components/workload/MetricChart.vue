<script setup lang="ts">
/** 单图 ECharts：时间轴折线。CPU/内存可选「绝对值 / %」切换（percentable + limit）。 */
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { MetricSeries } from '@/types/metrics'
import { cpuDisplayUnit, formatCpuAxisDisplay, formatCpuDisplay, formatMetricAxis, formatMetricValue } from '@/utils/metrics'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = withDefaults(defineProps<{
  title: string
  unit: string
  series?: MetricSeries[]
  loading?: boolean
  /** 是否允许切百分比（仅 CPU/内存） */
  percentable?: boolean
  /** 配额上限（与 value 同单位：CPU=核、内存=字节）；>0 且 percentable 时显示切换 */
  limit?: number | null
  /** 可选：时间范围选项（分钟）；提供时图头显示独立的时间范围切换 */
  ranges?: number[]
  /** 当前时间范围（分钟），配合 ranges + update:range 使用 */
  range?: number
}>(), {
  series: () => [],
  loading: false,
  percentable: false,
  limit: null,
  ranges: undefined,
  range: undefined,
})

const emit = defineEmits<{ 'update:range': [value: number] }>()

/** 'abs' = 绝对值；'pct' = 百分比（value / limit * 100） */
const mode = ref<'abs' | 'pct'>('abs')
const percent = computed(() => mode.value === 'pct')
const showToggle = computed(() => props.percentable && (props.limit ?? 0) > 0)
const hasData = computed(() => props.series.some((s) => s.points?.length))

/** CPU（unit=核）且绝对值模式：按整图最大值量级选 core / 毫核(m)；其余为 null（走通用格式化）。 */
const cpuUnit = computed(() => {
  if (props.unit !== '核' || percent.value) return null
  let max = 0
  for (const s of props.series) for (const p of s.points ?? []) if (p.value > max) max = p.value
  return cpuDisplayUnit(max)
})

const chartEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let themeObserver: MutationObserver | null = null
let resizeObserver: ResizeObserver | null = null

/** canvas 读不到 CSS 变量：把主题 token 解析成当前生效的具体色值（.dark 切换后取值随之变化）。 */
function cssVar(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

function buildOption(): echarts.EChartsCoreOption {
  const pct = percent.value
  const limit = props.limit ?? 0
  const cpu = cpuUnit.value // CPU abs 的动态单位（core/毫核）；非 CPU 或百分比为 null
  const transform = (v: number) => {
    if (pct && limit > 0) return (v / limit) * 100
    if (cpu) return v * cpu.scale
    return v
  }
  const fmtValue = (val: number | string): string => {
    const num = Number(val)
    return cpu ? formatCpuDisplay(num, cpu.suffix) : formatMetricValue(num, props.unit, pct)
  }
  const fmtAxis = (v: number): string => {
    return cpu ? formatCpuAxisDisplay(v, cpu.suffix) : formatMetricAxis(v, props.unit, pct)
  }
  // 主题色：canvas 不能直接用 var()，这里解析成具体色值；.dark 切换后重绘即取到新值
  const borderColor = cssVar('--border', '#e6eaf2')
  const axisColor = cssVar('--text-3', '#94a3b8')
  const legendColor = cssVar('--text-2', '#475569')
  const panelColor = cssVar('--panel', '#ffffff')
  const mainText = cssVar('--text-1', '#1e293b')

  // x 轴时间刻度：按图宽请求尽量多的刻度（越宽越多），hideOverlap 自动隐藏会重叠的 → 不挤且尽量多
  const chartWidth = chart ? chart.getWidth() : 600
  const splitNumber = Math.max(4, Math.min(14, Math.round(chartWidth / 80)))
  // 时间跨度 ≤ 1h 时精确到秒，区分度更高（配合 hideOverlap 不会挤）
  let minTs = Infinity
  let maxTs = -Infinity
  for (const s of props.series) for (const p of s.points ?? []) { if (p.ts < minTs) minTs = p.ts; if (p.ts > maxTs) maxTs = p.ts }
  const showSeconds = Number.isFinite(minTs) && (maxTs - minTs) <= 3600

  return {
    grid: { left: 4, right: 12, top: props.series.length > 1 ? 28 : 16, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: panelColor,
      borderColor,
      borderWidth: 1,
      textStyle: { color: mainText },
      valueFormatter: fmtValue,
    },
    legend: props.series.length > 1
      ? { top: 0, right: 0, icon: 'roundRect', itemWidth: 12, itemHeight: 8, textStyle: { fontSize: 11, color: legendColor } }
      : undefined,
    xAxis: {
      type: 'time',
      axisLine: { lineStyle: { color: borderColor } },
      splitNumber,
      axisLabel: {
        color: axisColor,
        hideOverlap: true,
        formatter: (v: number) => {
          const d = new Date(v)
          const p = (n: number) => String(n).padStart(2, '0')
          return showSeconds ? `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}` : `${p(d.getHours())}:${p(d.getMinutes())}`
        },
      },
    },
    yAxis: {
      type: 'value',
      min: 0,
      splitLine: { lineStyle: { color: borderColor } },
      axisLabel: { color: axisColor, formatter: fmtAxis },
    },
    series: props.series.map((s) => ({
      name: s.legend,
      type: 'line' as const,
      showSymbol: false,
      smooth: true,
      lineStyle: { width: 2 },
      emphasis: { focus: 'series' },
      data: (s.points ?? []).map((p) => [p.ts * 1000, transform(p.value)]),
    })),
  }
}

function render(): void {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

onMounted(() => {
  if (chartEl.value) {
    chart = echarts.init(chartEl.value)
    render()
    // 监听容器自身尺寸变化（窗口缩放 / 栅格重排 / 断点切换都会触发），比只监听 window resize 更可靠
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(chartEl.value)
  }
  // 主题切换 = <html> 的 class 增删 .dark；canvas 不会自动跟随 CSS 变量，需监听并手动重绘
  themeObserver = new MutationObserver((muts) => {
    if (muts.some((m) => m.type === 'attributes' && m.attributeName === 'class')) render()
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  themeObserver?.disconnect()
  themeObserver = null
  chart?.dispose()
  chart = null
})

watch([() => props.series, mode], render)
</script>

<template>
  <section class="metric-card">
    <header class="mc-head">
      <span class="mc-title">{{ title }}</span>
      <div class="mc-controls">
        <el-radio-group v-if="ranges && ranges.length" :model-value="range" size="small" @change="(v: number) => emit('update:range', v)">
          <el-radio-button v-for="r in ranges" :key="r" :value="r">{{ r }}m</el-radio-button>
        </el-radio-group>
        <el-radio-group v-if="showToggle" v-model="mode" size="small">
          <el-radio-button value="abs">值</el-radio-button>
          <el-radio-button value="pct">%</el-radio-button>
        </el-radio-group>
      </div>
    </header>
    <div class="mc-body" v-loading="loading">
      <div ref="chartEl" class="mc-chart"></div>
      <div v-if="!hasData && !loading" class="mc-empty">无数据</div>
    </div>
  </section>
</template>

<style scoped>
.metric-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel, #fff);
  padding: 12px 14px;
}
.mc-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 6px; }
.mc-controls { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mc-title { font-size: 13px; font-weight: 700; letter-spacing: .03em; color: var(--text-2); }
.mc-body { position: relative; }
.mc-chart { width: 100%; height: 220px; }
.mc-empty {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  font-size: 13px; color: var(--text-3); pointer-events: none;
}
</style>
