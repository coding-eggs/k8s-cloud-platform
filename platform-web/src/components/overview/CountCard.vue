<script setup lang="ts">
/**
 * 数量卡：大号总数 + 按状态的小段（值为 0 的段不显示）。total == null → 「—」（取不到，不是 0）。
 */
import { computed } from 'vue'
import type { Segment } from './labels'

const props = defineProps<{
  title: string
  total: number | null
  segments?: Segment[]
  /** 总数旁的副标题（如「Pod 数 / 上限」） */
  sub?: string
}>()

const shown = computed(() => (props.segments ?? []).filter((s) => s.value > 0))
</script>

<template>
  <div class="ov-count-card">
    <div class="ov-count-title">{{ title }}</div>
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
.ov-count-title {
  font-size: 13px;
  color: var(--text-2);
}
.ov-count-total {
  font-size: 26px;
  font-weight: 600;
  margin: 6px 0 8px;
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
