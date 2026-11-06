<script setup lang="ts">
/**
 * 数量卡：大号总数 + 状态比例条 + 按状态的小段（值为 0 的段不显示）。total == null → 「—」（取不到，不是 0）。
 *
 * - `kind` 给出右上角图标（与侧栏菜单同一个图标）；告警卡有触发中的告警时图标转红；
 * - 整张卡可点击：有数时去该资源的列表页，为 0 时去「从哪开始」的页面（见 COUNT_KIND_LINK）。
 *   目标页没有权限就不可点（不给死链）；`to` 可覆盖目标，`no-link` 关掉链接（如节点详情里的 Pod 卡）；
 * - 比例条：各段按 max(total, Σ段) 等比铺开，剩余部分是轨道底色 —— 工作负载卡只有「不健康」一段，
 *   红色占比就是不健康比例；
 * - total 为 0：数字变淡，并给一句引导（「还没有应用，点击去新建」/「没有触发中的告警」）。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { usePermission } from '@/stores/permission'
import { COUNT_KIND_ICON, COUNT_KIND_LINK, type CountKind, type Segment } from './labels'

const props = defineProps<{
  title: string
  total: number | null
  segments?: Segment[]
  /** 总数旁的副标题（如「Pod 数 / 上限」） */
  sub?: string
  /** 资源类型：决定右上角图标、默认跳转与空态引导；不传则都没有 */
  kind?: CountKind
  /** 覆盖跳转目标 */
  to?: string
  /** 不可点（注意别把它并进 `to` 写成 string | false：Vue 会把含 false 的 prop 当布尔，不传时默认 false） */
  noLink?: boolean
}>()

const router = useRouter()
const perm = usePermission()

const icon = computed(() => (props.kind ? COUNT_KIND_ICON[props.kind] : null))
const alarming = computed(() => props.kind === 'alert' && (props.total ?? 0) > 0)
const isEmpty = computed(() => props.total === 0)
const emptyHint = computed(() => (isEmpty.value && props.kind ? COUNT_KIND_LINK[props.kind].empty ?? null : null))

const target = computed<string | null>(() => {
  if (props.noLink) return null
  if (props.to) return props.to
  if (!props.kind) return null
  const link = COUNT_KIND_LINK[props.kind]
  return (isEmpty.value ? emptyHint.value?.path : undefined) ?? link.list
})
/** 目标页的页面码一个都没有 → 不可点（菜单里本来也看不到那一页） */
const href = computed(() => {
  if (!target.value) return null
  const need = router.resolve(target.value).meta.requiresPerm
  return !need?.length || perm.hasAny(need) ? target.value : null
})

const shown = computed(() => (props.segments ?? []).filter((s) => s.value > 0))
const bar = computed(() => {
  const sum = shown.value.reduce((a, s) => a + s.value, 0)
  const denom = Math.max(props.total ?? 0, sum)
  if (denom <= 0 || sum <= 0) return []
  return shown.value.map((s) => ({ tone: s.tone, label: s.label, width: (s.value / denom) * 100 }))
})
</script>

<template>
  <component
    :is="href ? 'router-link' : 'div'"
    :to="href ?? undefined"
    class="ov-count-card"
    :class="{ clickable: !!href, empty: isEmpty }"
  >
    <div class="ov-count-head">
      <span class="ov-count-title">{{ title }}</span>
      <span v-if="icon" class="ov-count-icon" :class="{ alarming }">
        <el-icon><component :is="icon" /></el-icon>
      </span>
    </div>
    <div class="ov-count-total">
      <span class="ov-count-num">{{ total == null ? '—' : total }}</span>
      <small v-if="sub" class="ov-count-sub">{{ sub }}</small>
    </div>
    <div v-if="bar.length" class="ov-count-bar">
      <i v-for="b in bar" :key="b.label" :class="`tone-${b.tone}`" :style="{ width: `${b.width}%` }" />
    </div>
    <div class="ov-count-segs">
      <span v-if="emptyHint" class="ov-count-hint" :class="{ good: emptyHint.good }">{{ emptyHint.text }}</span>
      <span v-for="s in shown" :key="s.label" class="ov-count-seg" :class="`tone-${s.tone}`">
        <i class="dot" />{{ s.label }} {{ s.value }}
      </span>
      <slot />
    </div>
  </component>
</template>

<style scoped>
.ov-count-card {
  display: block;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px 16px;
  background: var(--el-bg-color, transparent);
  min-width: 0;
  color: inherit;
  text-decoration: none;
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.ov-count-card.clickable {
  cursor: pointer;
}
.ov-count-card.clickable:hover {
  border-color: var(--accent);
  transform: translateY(-1px);
  box-shadow: 0 4px 14px color-mix(in srgb, var(--accent) 14%, transparent);
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
  color: var(--text-1);
}
.ov-count-card.empty .ov-count-num {
  color: var(--text-3);
}
.ov-count-sub {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-3);
  margin-left: 6px;
}
.ov-count-bar {
  display: flex;
  height: 4px;
  border-radius: 2px;
  overflow: hidden;
  background: var(--border);
  margin-bottom: 8px;
}
.ov-count-bar i {
  display: block;
  height: 100%;
}
.ov-count-bar i.tone-success { background: var(--success); }
.ov-count-bar i.tone-warning { background: var(--warning); }
.ov-count-bar i.tone-danger { background: var(--danger); }
.ov-count-bar i.tone-info { background: var(--info); }
.ov-count-segs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 12px;
  color: var(--text-2);
  min-height: 18px;
}
.ov-count-hint {
  color: var(--text-3);
}
.ov-count-hint.good {
  color: var(--success);
}
.ov-count-card.clickable:hover .ov-count-hint:not(.good) {
  color: var(--accent);
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
.ov-count-seg.tone-danger { color: var(--danger); }
</style>
