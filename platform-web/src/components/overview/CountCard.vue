<script setup lang="ts">
/**
 * 数量卡：大号总数 + 状态比例条 + 按状态的小段（值为 0 的段不显示）。total == null → 「—」（取不到，不是 0）。
 *
 * - `kind` 给出右上角图标（与侧栏菜单同一个图标）；告警卡有触发中的告警时图标转红；
 * - 整张卡可点击：有数时去该资源的列表页，为 0 时去「从哪开始」的页面（见 COUNT_KIND_LINK）。
 *   目标页没有权限就不可点（不给死链）；`to` 可覆盖目标，`no-link` 关掉链接（如节点详情里的 Pod 卡）；
 * - 带 `query` 的状态段（如 Pod「异常 4」）可单独点：去同一个列表页并带上筛选参数（列表页据此预先筛好）；
 * - 跳之前先切顶栏上下文（列表页都按顶栏取数）：
 *   · `scope`（命名空间 / 集群概览页）：整张卡和状态段都切到这个集群[/命名空间]；
 *   · `ns-rows` / `load-ns-rows`（平台 / 集群 / 租户概览的 Pod、工作负载卡）：按状态段算出分布在哪些命名空间，
 *     只有一个 → 直接切过去；多个 → 卡片下方弹一个小列表，点哪个切哪个（见 labels.nsTargetsOf）；
 * - 比例条：各段按 max(total, Σ段) 等比铺开，剩余部分是轨道底色 —— 工作负载卡只有「不健康」一段，
 *   红色占比就是不健康比例；
 * - total 为 0：数字变淡，并给一句引导（「还没有应用，点击去新建」/「没有触发中的告警」）。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { usePermission } from '@/stores/permission'
import { jumpWithContext } from '@/composables/useContextJump'
import type { NamespaceCounts } from '@/types/overview'
import {
  COUNT_KIND_ICON, COUNT_KIND_LINK, nsTargetsOf,
  type CardScope, type CountKind, type NsTarget, type Segment,
} from './labels'

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
  /** 跳转前把顶栏切到这个集群[/命名空间] */
  scope?: CardScope
  /** 按命名空间的计数（已在手上的）：状态段据此跳到对应命名空间 */
  nsRows?: NamespaceCounts[] | null
  /** 按命名空间的计数（点的时候才拉，如集群概览的资源明细） */
  loadNsRows?: () => Promise<NamespaceCounts[] | null>
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
function permitted(path: string): boolean {
  const need = router.resolve(path).meta.requiresPerm
  return !need?.length || perm.hasAny(need)
}
const href = computed(() => (target.value && permitted(target.value) ? target.value : null))

/** 状态段跳转的基准：列表页（不是空态引导页）；可能自带查询参数（告警页默认 status=firing） */
const listPath = computed<string | null>(() => {
  if (props.noLink) return null
  const p = props.to ?? (props.kind ? COUNT_KIND_LINK[props.kind].list : null)
  return p && permitted(p) ? p : null
})
/** 普通点击才接管（Ctrl / ⌘ / Shift / 中键 = 新标签打开，交给浏览器） */
function plainClick(e: MouseEvent): boolean {
  return e.button === 0 && !e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey
}
async function go(to: RouteLocationRaw, where?: CardScope): Promise<void> {
  if (where) await jumpWithContext(router, where, to)
  else await router.push(to)
}
function onCardClick(e: MouseEvent): void {
  if (!href.value || !plainClick(e)) return
  e.preventDefault()
  void go(href.value, props.scope)
}

/** 多命名空间候选列表（卡片下方的小弹层） */
const picker = ref<{ seg: Segment; targets: NsTarget[] } | null>(null)
const pickerLoading = ref(false)
const multiCluster = computed(() => new Set((picker.value?.targets ?? []).map((t) => t.clusterId)).size > 1)

function segmentLocation(s: Segment): RouteLocationRaw | null {
  if (!listPath.value || !s.query) return null
  const base = router.resolve(listPath.value)
  return { path: base.path, query: { ...base.query, ...s.query } }
}
async function openSegment(e: MouseEvent, s: Segment): Promise<void> {
  const to = segmentLocation(s)
  if (!to) return
  e.preventDefault()
  e.stopPropagation()
  if (!props.nsRows && !props.loadNsRows) return go(to, props.scope)
  let rows = props.nsRows ?? null
  if (!rows && props.loadNsRows) {
    pickerLoading.value = true
    try {
      rows = await props.loadNsRows()
    } catch {
      rows = null
    } finally {
      pickerLoading.value = false
    }
  }
  const targets = rows ? nsTargetsOf(props.kind, s, rows) : null
  if (!targets || targets.length === 0) return go(to, props.scope) // 定位不到命名空间：按 scope / 当前顶栏跳
  if (targets.length === 1) {
    const t = targets[0]!
    return go(to, { clusterId: t.clusterId, namespace: t.namespace })
  }
  picker.value = { seg: s, targets }
}
function pickTarget(t: NsTarget): void {
  const s = picker.value?.seg
  picker.value = null
  const to = s ? segmentLocation(s) : null
  if (to) void go(to, { clusterId: t.clusterId, namespace: t.namespace })
}

// 点弹层外面 / 按 Esc 关掉
const wrapRef = ref<HTMLElement | null>(null)
function onDocDown(e: MouseEvent): void {
  if (wrapRef.value && !wrapRef.value.contains(e.target as Node)) picker.value = null
}
function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') picker.value = null
}
watch(picker, (v) => {
  document.removeEventListener('mousedown', onDocDown)
  document.removeEventListener('keydown', onKey)
  if (v) {
    document.addEventListener('mousedown', onDocDown)
    document.addEventListener('keydown', onKey)
  }
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocDown)
  document.removeEventListener('keydown', onKey)
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
  <div ref="wrapRef" class="ov-count-wrap">
  <component
    :is="href ? 'a' : 'div'"
    :href="href ? router.resolve(href).href : undefined"
    class="ov-count-card"
    :class="{ clickable: !!href, empty: isEmpty }"
    @click="onCardClick"
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
      <span
        v-for="s in shown"
        :key="s.label"
        class="ov-count-seg"
        :class="[`tone-${s.tone}`, { 'seg-link': !!(listPath && s.query) }]"
        :title="listPath && s.query ? `只看「${s.label}」` : undefined"
        @click="listPath && s.query ? openSegment($event, s) : undefined"
      >
        <i class="dot" />{{ s.label }} {{ s.value }}
      </span>
      <span v-if="pickerLoading" class="ov-count-hint">定位中…</span>
      <slot />
    </div>
  </component>

  <!-- 多命名空间：点哪个就把顶栏切到哪个再打开列表 -->
  <div v-if="picker" class="ov-ns-picker" role="menu">
    <div class="ov-ns-picker-head">「{{ picker.seg.label }}」分布在 {{ picker.targets.length }} 个命名空间</div>
    <button
      v-for="t in picker.targets"
      :key="`${t.clusterId}/${t.namespace}`"
      type="button"
      class="ov-ns-item"
      role="menuitem"
      @click="pickTarget(t)"
    >
      <span class="ov-ns-name">
        <small v-if="multiCluster" class="ov-ns-cluster">{{ t.clusterName || t.clusterId }} /</small>
        {{ t.namespace }}
      </span>
      <b>{{ t.count }}</b>
    </button>
  </div>
  </div>
</template>

<style scoped>
.ov-count-wrap {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.ov-count-card {
  flex: 1;
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
.ov-count-seg.seg-link {
  cursor: pointer;
  border-radius: 4px;
  padding: 0 4px;
  margin: 0 -4px;
}
.ov-count-seg.seg-link:hover {
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  text-decoration: underline;
}
.ov-ns-picker {
  position: absolute;
  z-index: 20;
  top: calc(100% + 6px);
  left: 0;
  min-width: 100%;
  max-width: 340px;
  max-height: 280px;
  overflow-y: auto;
  padding: 6px;
  border: 1px solid var(--border-strong, var(--border));
  border-radius: 10px;
  background: var(--panel);
  box-shadow: 0 10px 28px rgba(0, 0, 0, .28);
}
.ov-ns-picker-head {
  padding: 4px 8px 6px;
  font-size: 12px;
  color: var(--text-3);
}
.ov-ns-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-1);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.ov-ns-item:hover {
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent);
}
.ov-ns-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ov-ns-cluster {
  color: var(--text-3);
  margin-right: 2px;
}
</style>
