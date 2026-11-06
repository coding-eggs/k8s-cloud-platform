<script setup lang="ts">
/**
 * 数量卡：大号总数 + 按状态的小段（值为 0 的段不显示）。total == null → 「—」（取不到，不是 0）。
 * `kind` 给出右上角图标（与侧栏菜单同一个图标，一眼认出是什么）；告警卡有触发中的告警时图标转红。
 */
import { computed } from 'vue'
import { COUNT_KIND_ICON, type CountKind, type Segment } from './labels'

const props = defineProps<{
  title: string
  total: number | null
  segments?: Segment[]
  /** 总数旁的副标题（如「Pod 数 / 上限」） */
  sub?: string
  /** 资源类型：决定右上角图标；不传则不显示图标 */
  kind?: CountKind
}>()

const icon = computed(() => (props.kind ? COUNT_KIND_ICON[props.kind] : null))
const alarming = computed(() => props.kind === 'alert' && (props.total ?? 0) > 0)

const shown = computed(() => (props.segments ?? []).filter((s) => s.value > 0))
</script>

<template>
  <div class="ov-count-card">
    <div class="ov-count-head">
      <span class="ov-count-title">{{ title }}</span>
      <span v-if="icon" class="ov-count-icon" :class="{ alarming }">
        <el-icon><component :is="icon" /></el-icon>
      </span>
    </div>
    <div class="ov-count-total">
      <span>{{ total == null ? '—' : total }}</span>
      <small v-if="sub" class="ov-count-sub">{{ sub }}</small>
    </div>
    <div class="ov-count-segs">
      <span v-for="s in shown" :key="s.label" class="ov-count-seg" :class="`tone-${s.tone}`">
        <i class="dot" />{{ s.label }} {{ s.value }}
      </span>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.ov-count-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px 16px;
  background: var(--el-bg-color, transparent);
  min-width: 0;
}
.ov-count-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.ov-count-title {
  font-size: 13px;
  color: var(--text-2);
  line-height: 28px;
}
.ov-count-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  font-size: 16px;
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 14%, transparent);
}
.ov-count-icon.alarming {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 14%, transparent);
}
.ov-count-total {
  font-size: 26px;
  font-weight: 600;
  margin: 2px 0 8px;
  line-height: 1.2;
}
.ov-count-sub {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-3);
  margin-left: 6px;
}
.ov-count-segs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 12px;
  color: var(--text-2);
  min-height: 18px;
}
.ov-count-seg .dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}
.tone-success .dot { background: var(--success); }
.tone-warning .dot { background: var(--warning); }
.tone-danger .dot { background: var(--danger); }
.tone-info .dot { background: var(--info); }
.tone-danger { color: var(--danger); }
</style>
