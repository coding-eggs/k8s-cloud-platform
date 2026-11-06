<script setup lang="ts">
/**
 * 拓扑边：按 dagre 给的拐点画平滑曲线（B 样条，同 d3 的 curveBasis）。
 *
 * 不用 Vue Flow 自带的 smoothstep：它只会从左锚点连到右锚点，跨列的边会从中间卡片背后穿过；
 * dagre 已经给跨列的边留了通道，照着拐点画才能绕开卡片。没有拐点时退回两锚点直连。
 */
import { computed } from 'vue'
import { BaseEdge } from '@vue-flow/core'
import type { EdgeProps } from '@vue-flow/core'
import type { Point } from './appCanvasLayout'

const props = defineProps<EdgeProps<{ points?: Point[]; dimmed?: boolean; active?: boolean }>>()

function basisPath(pts: Point[]): string {
  const [a, b] = pts
  if (!a || !b) return ''
  if (pts.length === 2) return `M${a.x},${a.y}L${b.x},${b.y}`
  let d = `M${a.x},${a.y}L${(5 * a.x + b.x) / 6},${(5 * a.y + b.y) / 6}`
  let p0 = a
  let p1 = b
  const curve = (next: Point) => {
    d += `C${(2 * p0.x + p1.x) / 3},${(2 * p0.y + p1.y) / 3},${(p0.x + 2 * p1.x) / 3},${(p0.y + 2 * p1.y) / 3},`
      + `${(p0.x + 4 * p1.x + next.x) / 6},${(p0.y + 4 * p1.y + next.y) / 6}`
    p0 = p1
    p1 = next
  }
  for (const p of pts.slice(2)) curve(p)
  curve(p1)
  return `${d}L${p1.x},${p1.y}`
}

const path = computed(() => {
  const pts = props.data?.points
  if (pts && pts.length >= 2) return basisPath(pts)
  return `M${props.sourceX},${props.sourceY}L${props.targetX},${props.targetY}`
})

const edgeStyle = computed(() => ({
  ...(typeof props.style === 'object' ? props.style : {}),
  strokeWidth: props.data?.active ? 2.2 : 1.6,
  stroke: props.data?.active ? 'var(--el-color-primary)' : undefined,
  opacity: props.data?.dimmed ? 0.15 : 1,
  transition: 'opacity .15s, stroke .15s',
}))
</script>

<template>
  <BaseEdge :id="id" :path="path" :marker-end="markerEnd" :style="edgeStyle" />
</template>
