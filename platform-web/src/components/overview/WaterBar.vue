<script setup lang="ts">
/**
 * 水位条：used / total。任一为 null → 「—」（total 为 null 显示「未配置」）；≥90% 红、≥80% 橙。
 */
import { computed } from 'vue'
import { formatMetricValue } from '@/utils/metrics'

const props = defineProps<{
  label: string
  used: number | null | undefined
  total: number | null | undefined
  unit: 'core' | 'byte'
  /** total 为 null 时的文案（配额场景=「未配置」；容量场景取不到=「—」） */
  missingText?: string
}>()

const pct = computed(() => {
  if (props.used == null || props.total == null || props.total <= 0) return null
  return (props.used / props.total) * 100
})
const tone = computed(() => (pct.value == null ? '' : pct.value >= 90 ? 'danger' : pct.value >= 80 ? 'warning' : 'ok'))
const fmt = (v: number) => formatMetricValue(v, props.unit === 'core' ? '核' : '字节', false)
const text = computed(() => {
  if (props.total == null) return props.missingText ?? '未配置'
  if (props.used == null) return '—'
  return `${fmt(props.used)} / ${fmt(props.total)}`
})
</script>

<template>
  <div class="ov-water">
    <div class="ov-water-head">
      <span class="ov-water-label">{{ label }}</span>
      <span class="ov-water-text">
        {{ text }}<b v-if="pct != null" :class="`tone-${tone}`"> · {{ pct.toFixed(1) }}%</b>
      </span>
    </div>
    <div class="ov-water-track">
      <div v-if="pct != null" class="ov-water-fill" :class="`tone-${tone}`" :style="{ width: `${Math.min(pct, 100)}%` }" />
    </div>
  </div>
</template>

<style scoped>
.ov-water { min-width: 0; }
.ov-water-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 6px;
}
.ov-water-label { color: var(--text-2); }
.ov-water-text { color: var(--text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ov-water-track {
  height: 8px;
  border-radius: 4px;
  background: var(--border);
  overflow: hidden;
}
.ov-water-fill { height: 100%; border-radius: 4px; }
.ov-water-fill.tone-ok { background: var(--success); }
.ov-water-fill.tone-warning { background: var(--warning); }
.ov-water-fill.tone-danger { background: var(--danger); }
b.tone-warning { color: var(--warning); }
b.tone-danger { color: var(--danger); }
b.tone-ok { color: var(--text-2); font-weight: 500; }
</style>
