<script setup lang="ts">
/**
 * 容量子弹图：一条横条把「已占 / limits / 实际用量 / 上限」放在同一把尺上，一眼看出快满了还是超卖了。
 *
 * - 实心段 = `filled`（配额已用，或 requests）——颜色按占上限的比例：≥80% 橙、≥95% 红；
 * - 浅色段 = `limit`（limits 合计），超过上限就越过刻度线，并标「limits 超卖 N×」；
 * - 竖线（实）= `used`（监控当前用量），竖线（虚）= 上限 `scale`（配额 hard / 节点可分配）；
 * - 尺的量程 = 四者最大值；上限缺失（未配配额）时按其余最大值画，并明说「未配置」。
 * 任何一项为 null 就不画那一项（「不知道」≠ 0）。
 */
import { computed } from 'vue'
import { formatMetricValue } from '@/utils/metrics'

const props = withDefaults(defineProps<{
  label: string
  unit: 'core' | 'byte'
  /** 上限：配额 hard / 节点可分配；null = 没有（未配置 / 取不到） */
  scale?: number | null
  scaleName?: string
  /** 实心段 */
  filled?: number | null
  filledName?: string
  /** 浅色段：limits 合计（不适用时不传） */
  limit?: number | null
  /** 当前实际用量（取不到不传） */
  used?: number | null
  /** 上限缺失时的文案 */
  missingText?: string
}>(), {
  scale: null,
  scaleName: '配额',
  filled: null,
  filledName: '已用',
  limit: null,
  used: null,
  missingText: '未配置',
})

const fmt = (v: number) => formatMetricValue(v, props.unit === 'core' ? '核' : '字节', false)
const has = (v: number | null | undefined): v is number => v != null && Number.isFinite(v)

const domain = computed(() => Math.max(
  has(props.scale) ? props.scale : 0,
  has(props.filled) ? props.filled : 0,
  has(props.limit) ? props.limit : 0,
  has(props.used) ? props.used : 0,
))
const pos = (v: number) => (domain.value > 0 ? Math.min(100, (v / domain.value) * 100) : 0)

/** 实心段占上限的比例（决定颜色与标题百分比）；没有上限 → null */
const ratio = computed(() =>
  has(props.scale) && props.scale > 0 && has(props.filled) ? (props.filled / props.scale) * 100 : null)
const tone = computed(() => (ratio.value == null ? 'ok' : ratio.value >= 95 ? 'danger' : ratio.value >= 80 ? 'warning' : 'ok'))
/** limits 超过上限的倍数（>1 才算超卖） */
const overcommit = computed(() =>
  has(props.scale) && props.scale > 0 && has(props.limit) && props.limit > props.scale ? props.limit / props.scale : null)
const empty = computed(() => domain.value <= 0)
</script>

<template>
  <div class="cb">
    <div class="cb-head">
      <span class="cb-label">{{ label }}</span>
      <span class="cb-main">
        <template v-if="has(filled)">
          <b class="mono">{{ fmt(filled) }}</b>
          <span class="muted"> / </span>
        </template>
        <span class="mono">{{ has(scale) ? fmt(scale) : missingText }}</span>
        <b v-if="ratio != null" class="cb-pct" :class="`tone-${tone}`">{{ ratio.toFixed(0) }}%</b>
      </span>
    </div>

    <el-tooltip placement="top" :disabled="empty" :show-after="150">
      <template #content>
        <div class="cb-tip">
          <div v-if="has(filled)">{{ filledName }}：{{ fmt(filled) }}</div>
          <div v-if="has(limit)">limits：{{ fmt(limit) }}</div>
          <div v-if="has(used)">实际用量：{{ fmt(used) }}</div>
          <div>{{ scaleName }}：{{ has(scale) ? fmt(scale) : missingText }}</div>
        </div>
      </template>
      <div class="cb-track" :class="{ empty }" role="img" :aria-label="`${label} ${filledName} / ${scaleName}`">
        <div v-if="has(limit)" class="cb-limit" :style="{ width: pos(limit) + '%' }" />
        <div v-if="has(filled)" class="cb-fill" :class="`tone-${tone}`" :style="{ width: pos(filled) + '%' }" />
        <div v-if="has(scale) && scale < domain" class="cb-scale" :style="{ left: pos(scale) + '%' }" />
        <div v-if="has(used)" class="cb-used" :style="{ left: pos(used) + '%' }" />
      </div>
    </el-tooltip>

    <div class="cb-legend">
      <span v-if="has(filled)"><i class="sw sw-fill" :class="`tone-${tone}`" />{{ filledName }}</span>
      <span v-if="has(limit)"><i class="sw sw-limit" />limits {{ fmt(limit) }}</span>
      <span v-if="has(used)"><i class="sw sw-used" />实际 {{ fmt(used) }}</span>
      <span v-if="overcommit" class="cb-over">limits 超出{{ scaleName }} {{ overcommit.toFixed(1) }}×</span>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.cb { min-width: 0; }
.cb-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.cb-label { font-size: 13px; color: var(--text-2); }
.cb-main {
  font-size: 13px;
  color: var(--text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cb-main b { color: var(--text-1); }
.cb-pct { margin-left: 8px; font-weight: 600; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; }
.muted { color: var(--text-3); }

.cb-track {
  position: relative;
  height: 12px;
  border-radius: 4px;
  background: var(--border);
  cursor: default;
}
.cb-track.empty { opacity: .5; }
.cb-limit,
.cb-fill {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  border-radius: 4px;
}
.cb-limit { background: color-mix(in srgb, var(--accent) 28%, transparent); }
/* 实心段与 limits 段之间留 2px 表面缝 */
.cb-fill { box-shadow: 2px 0 0 var(--panel); }
.tone-ok { background: var(--accent); }
.tone-warning { background: var(--warning); }
.tone-danger { background: var(--danger); }
b.tone-ok, b.tone-warning, b.tone-danger { background: none; }
b.tone-ok { color: var(--text-2); }
b.tone-warning { color: var(--warning); }
b.tone-danger { color: var(--danger); }
.cb-scale,
.cb-used {
  position: absolute;
  top: -3px;
  bottom: -3px;
  width: 0;
  transform: translateX(-1px);
}
.cb-scale { border-left: 2px dashed var(--text-2); }
.cb-used { border-left: 2px solid var(--text-1); }

.cb-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
}
.cb-legend > span { display: inline-flex; align-items: center; gap: 5px; }
.sw { display: inline-block; width: 10px; height: 10px; border-radius: 2px; }
.sw-limit { background: color-mix(in srgb, var(--accent) 28%, transparent); }
.sw-used { width: 0; height: 12px; border-left: 2px solid var(--text-1); border-radius: 0; }
.cb-over { color: var(--warning); font-weight: 500; }
.cb-tip { line-height: 1.7; }
</style>
