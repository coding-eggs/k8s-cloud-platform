<script setup lang="ts">
/**
 * 状态饼图（环形）：节点 Ready/NotReady、命名空间 已分配/未分配/平台系统 等。
 * 颜色取 theme.css 的语义色变量；主题切换时重绘（canvas 不跟随 CSS 变量）。
 */
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Segment } from './labels'

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{
  title: string
  data: Segment[]
  note?: string
}>()

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null

const total = computed(() => props.data.reduce((a, d) => a + d.value, 0))

function cssVar(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

function render() {
  if (!chart) return
  const text = cssVar('--text-2', '#475569')
  const colors: Record<string, string> = {
    success: cssVar('--success', '#16a34a'),
    warning: cssVar('--warning', '#d97706'),
    danger: cssVar('--danger', '#dc2626'),
    info: cssVar('--info', '#2563eb'),
  }
  chart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}：{c}（{d}%）' },
    legend: { orient: 'vertical', right: 0, top: 'middle', textStyle: { color: text }, itemWidth: 10, itemHeight: 10 },
    series: [{
      type: 'pie',
      radius: ['55%', '80%'],
      center: ['35%', '50%'],
      avoidLabelOverlap: true,
      label: { show: true, position: 'center', formatter: () => String(total.value), fontSize: 20, fontWeight: 600, color: text },
      labelLine: { show: false },
      data: props.data.map((d) => ({ name: `${d.label} ${d.value}`, value: d.value, itemStyle: { color: colors[d.tone] } })),
    }],
  }, true)
}

onMounted(() => {
  if (el.value) {
    chart = echarts.init(el.value)
    render()
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(el.value)
  }
  themeObserver = new MutationObserver((muts) => {
    if (muts.some((m) => m.type === 'attributes' && m.attributeName === 'class')) render()
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  themeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
watch(() => props.data, render, { deep: true })
</script>

<template>
  <div class="ov-pie">
    <div class="ov-pie-title">{{ title }}</div>
    <div ref="el" class="ov-pie-chart" />
    <div v-if="note" class="ov-pie-note">{{ note }}</div>
  </div>
</template>

<style scoped>
.ov-pie {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px 16px;
  min-width: 0;
}
.ov-pie-title { font-size: 13px; color: var(--text-2); }
.ov-pie-chart { height: 160px; width: 100%; }
.ov-pie-note { font-size: 12px; color: var(--text-3); margin-top: 4px; }
</style>
