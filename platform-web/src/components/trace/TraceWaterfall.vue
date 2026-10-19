<script setup lang="ts">
/**
 * 调用链瀑布图（平台自研 trace 查看）。
 *
 * 输入是后端拍平并按开始时间排序的 span 列表；这里重建父子树（DFS 展示），
 * 时间轴以整条 trace 的最早开始/最晚结束为基准。
 */
import { computed } from 'vue'
import type { TraceSpan } from '@/types'

const props = defineProps<{
  spans: TraceSpan[]
  selectedSpanId?: string | null
}>()
const emit = defineEmits<{
  (e: 'select', span: TraceSpan): void
}>()

interface Row { span: TraceSpan; depth: number }

const rows = computed<Row[]>(() => {
  const spans = props.spans ?? []
  if (!spans.length) return []
  const byId = new Map(spans.map((s) => [s.spanId, s]))
  const children = new Map<string, TraceSpan[]>()
  const roots: TraceSpan[] = []
  const num = (v?: string | null) => Number(v ?? 0)
  const byStart = (a: TraceSpan, b: TraceSpan) => num(a.startTimeUnixNano) - num(b.startTimeUnixNano)

  for (const s of spans) {
    const parent = s.parentSpanId
    if (parent && byId.has(parent)) {
      const list = children.get(parent) ?? []
      list.push(s)
      children.set(parent, list)
    } else {
      roots.push(s)
    }
  }
  roots.sort(byStart)
  for (const list of children.values()) list.sort(byStart)

  const out: Row[] = []
  const walk = (span: TraceSpan, depth: number): void => {
    out.push({ span, depth })
    for (const child of children.get(span.spanId) ?? []) walk(child, depth + 1)
  }
  for (const root of roots) walk(root, 0)

  // 兜底：循环引用等异常数据不能让 span 静默消失
  if (out.length < spans.length) {
    const seen = new Set(out.map((r) => r.span.spanId))
    for (const span of [...spans].sort(byStart)) {
      if (!seen.has(span.spanId)) out.push({ span, depth: 0 })
    }
  }
  return out
})

const bounds = computed(() => {
  const spans = props.spans ?? []
  if (!spans.length) return { min: 0, total: 1 }
  const min = Math.min(...spans.map((s) => Number(s.startTimeUnixNano ?? 0)))
  const max = Math.max(...spans.map((s) => Number(s.endTimeUnixNano ?? s.startTimeUnixNano ?? 0)))
  return { min, total: Math.max(1, max - min) }
})

function barStyle(span: TraceSpan): Record<string, string> {
  const start = Number(span.startTimeUnixNano ?? 0)
  const end = Number(span.endTimeUnixNano ?? span.startTimeUnixNano ?? 0)
  const left = Math.max(0, ((start - bounds.value.min) / bounds.value.total) * 100)
  const width = Math.max(0.5, ((end - start) / bounds.value.total) * 100)
  return { left: `${left}%`, width: `${Math.min(width, 100 - left)}%` }
}

/** 服务配色：同一条 trace 里不同服务一眼可分（错误 span 固定用 danger） */
const COLORS = ['#22d3ee', '#a78bfa', '#34d399', '#fbbf24', '#f472b6',
  '#60a5fa', '#fb923c', '#4ade80', '#2dd4bf', '#c084fc']

function serviceColor(name?: string | null): string {
  const text = name ?? ''
  let hash = 0
  for (let i = 0; i < text.length; i++) hash = (hash * 31 + text.charCodeAt(i)) >>> 0
  return COLORS[hash % COLORS.length] as string
}

function isError(span: TraceSpan): boolean {
  return (span.statusCode ?? '').toUpperCase().includes('ERROR')
}

function barColor(span: TraceSpan): string {
  return isError(span) ? 'var(--danger)' : serviceColor(span.serviceName)
}

const serviceCount = computed(() => new Set((props.spans ?? []).map((s) => s.serviceName || '—')).size)

function fmtDuration(ms?: number | null): string {
  if (ms == null) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms}ms`
}
</script>

<template>
  <div class="tw">
    <div class="tw-head">
      <span>调用链</span>
      <span class="tw-count">{{ spans.length }} spans · {{ serviceCount }} services</span>
    </div>
    <div v-if="!spans.length" class="tw-empty">没有 span（Tempo 可能还没写入或已被保留期清理）</div>
    <div
      v-for="row in rows"
      :key="row.span.spanId"
      class="tw-row"
      :class="{ 'tw-selected': row.span.spanId === selectedSpanId, 'tw-row-error': isError(row.span) }"
      @click="emit('select', row.span)"
    >
      <div class="tw-name" :style="{ paddingLeft: `${row.depth * 14}px` }">
        <span class="tw-dot" :style="{ background: serviceColor(row.span.serviceName) }"></span>
        <span class="tw-svc" :style="{ color: serviceColor(row.span.serviceName) }">{{ row.span.serviceName || '—' }}</span>
        <span class="tw-op">{{ row.span.name }}</span>
      </div>
      <div class="tw-track">
        <div class="tw-bar" :style="{ ...barStyle(row.span), background: barColor(row.span) }"></div>
      </div>
      <div class="tw-dur" :class="{ 'tw-dur-error': isError(row.span) }">{{ fmtDuration(row.span.durationMs) }}</div>
    </div>
  </div>
</template>

<style scoped>
.tw { border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.tw-head {
  display: flex; justify-content: space-between; padding: 8px 12px;
  font-size: 12px; color: var(--text-2); background: var(--bg);
}
.tw-count { color: var(--text-3); }
.tw-empty { padding: 24px; text-align: center; color: var(--text-3); font-size: 13px; }
.tw-row {
  display: flex; align-items: center; gap: 12px;
  padding: 6px 12px; cursor: pointer; border-top: 1px solid var(--border);
}
.tw-row:hover { background: var(--bg); }
.tw-selected { background: color-mix(in srgb, var(--accent) 10%, transparent); }
.tw-row-error .tw-op { color: var(--danger); }
.tw-name {
  flex: 0 0 42%; max-width: 42%; min-width: 0; display: flex; align-items: center; gap: 8px;
  font-size: 12px; overflow: hidden; white-space: nowrap;
}
.tw-dot { flex: 0 0 8px; width: 8px; height: 8px; border-radius: 50%; opacity: .9; }
.tw-svc { flex: 0 0 auto; max-width: 45%; overflow: hidden; text-overflow: ellipsis; }
.tw-op { color: var(--text-1); overflow: hidden; text-overflow: ellipsis; }
.tw-track { flex: 1 1 auto; position: relative; height: 18px; }
.tw-track::before {
  content: ''; position: absolute; left: 0; right: 0; top: 50%;
  border-top: 1px dashed var(--border);
}
.tw-bar {
  position: absolute; top: 5px; height: 8px; border-radius: 4px;
  opacity: .85; min-width: 2px;
}
.tw-dur {
  flex: 0 0 70px; text-align: right; font-size: 11px; color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.tw-dur-error { color: var(--danger); }
</style>
