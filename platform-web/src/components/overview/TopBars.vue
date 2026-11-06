<script setup lang="ts">
/**
 * 「Top N」横向条形图：每行 = 名称 + 比例条 + 数值文本（如「配额占用 Top 5 命名空间」）。
 * 比例由调用方算好（0~100+，超过 100 按 100 画并标红）；颜色同容量子弹图：≥80% 橙、≥95% 红。
 * 行带 `to` 且有目标页权限时可点；带 `scope` 时先把顶栏切到该集群 / 命名空间再跳（同计数卡）。
 */
import { useRouter } from 'vue-router'
import { usePermission } from '@/stores/permission'
import { jumpWithContext, type JumpTarget } from '@/composables/useContextJump'

export interface TopBarItem {
  key: string
  label: string
  /** 名称前的小字（如集群名） */
  sub?: string | null
  pct: number
  text: string
  to?: string
  scope?: JumpTarget
}

defineProps<{ items: TopBarItem[]; emptyText?: string }>()

const router = useRouter()
const perm = usePermission()
function permitted(path?: string): boolean {
  if (!path) return false
  const need = router.resolve(path).meta.requiresPerm
  return !need?.length || perm.hasAny(need)
}
function open(it: TopBarItem): void {
  if (!it.to || !permitted(it.to)) return
  if (it.scope) void jumpWithContext(router, it.scope, it.to)
  else void router.push(it.to)
}
const tone = (p: number) => (p >= 95 ? 'danger' : p >= 80 ? 'warning' : 'ok')
</script>

<template>
  <div class="tb">
    <div v-if="!items.length" class="tb-empty">{{ emptyText ?? '暂无数据' }}</div>
    <component
      :is="permitted(it.to) ? 'button' : 'div'"
      v-for="it in items"
      :key="it.key"
      type="button"
      class="tb-row"
      :class="{ link: permitted(it.to) }"
      @click="open(it)"
    >
      <span class="tb-name" :title="it.sub ? `${it.sub} / ${it.label}` : it.label">
        <small v-if="it.sub" class="tb-sub">{{ it.sub }} /</small>{{ it.label }}
      </span>
      <span class="tb-track"><span class="tb-fill" :class="`tone-${tone(it.pct)}`" :style="{ width: Math.min(100, Math.max(0, it.pct)) + '%' }" /></span>
      <span class="tb-text" :class="`t-${tone(it.pct)}`">{{ it.text }}</span>
    </component>
  </div>
</template>

<style scoped>
.tb { display: flex; flex-direction: column; gap: 4px; }
.tb-empty { font-size: 13px; color: var(--text-3); padding: 8px 0; }
.tb-row {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1.4fr) auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 5px 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-1);
  font: inherit;
  font-size: 13px;
  text-align: left;
}
.tb-row.link { cursor: pointer; }
.tb-row.link:hover { background: var(--panel-hover); }
.tb-row.link:hover .tb-name { color: var(--accent); }
.tb-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tb-sub { color: var(--text-3); margin-right: 3px; }
.tb-track { height: 8px; border-radius: 4px; background: var(--border); overflow: hidden; }
.tb-fill { display: block; height: 100%; border-radius: 4px; }
.tone-ok { background: var(--accent); }
.tone-warning { background: var(--warning); }
.tone-danger { background: var(--danger); }
.tb-text { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12px; color: var(--text-2); white-space: nowrap; }
.t-warning { color: var(--warning); }
.t-danger { color: var(--danger); }
</style>
