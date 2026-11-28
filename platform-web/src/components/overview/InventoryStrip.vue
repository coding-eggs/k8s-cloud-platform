<script setup lang="ts">
/**
 * 清单条：只有一个数、没有状态的对象（Service、PVC、命名空间、成员……）排成一行小字，
 * 不占顶部状态卡的位置。带 `to` 且有目标页权限的项可点（带 `scope` 时先切顶栏再跳）。null = 取不到，显示「—」。
 */
import { useRouter } from 'vue-router'
import { usePermission } from '@/stores/permission'
import { jumpWithContext, type JumpTarget } from '@/composables/useContextJump'

export interface InventoryItem {
  label: string
  value: number | string | null | undefined
  to?: string
  scope?: JumpTarget
  scopes?: (JumpTarget & { label: string; count: number })[]
}

defineProps<{ items: InventoryItem[] }>()

const router = useRouter()
const perm = usePermission()
function permitted(path?: string): boolean {
  if (!path) return false
  const need = router.resolve(path).meta.requiresPerm
  return !need?.length || perm.hasAny(need)
}
function open(it: InventoryItem, scope = it.scope ?? it.scopes?.[0]): void {
  if (!it.to) return
  if (scope) void jumpWithContext(router, scope, it.to)
  else void router.push(it.to)
}
</script>

<template>
  <div class="inv">
    <template v-for="(it, i) in items" :key="it.label">
      <span v-if="i > 0" class="inv-dot">·</span>
      <el-popover v-if="permitted(it.to) && (it.scopes?.length ?? 0) > 1" trigger="click" placement="bottom-start" :width="270">
        <template #reference>
          <button type="button" class="inv-item link">{{ it.label }} <b>{{ it.value ?? '—' }}</b></button>
        </template>
        <div class="scope-list" :aria-label="`选择 ${it.label} 所在命名空间`">
          <el-button v-for="scope in it.scopes" :key="`${scope.clusterId}/${scope.namespace}`" link type="primary" @click="open(it, scope)">
            {{ scope.label }} · {{ scope.count }}
          </el-button>
        </div>
      </el-popover>
      <a
        v-else-if="permitted(it.to) && (!it.scopes || it.scopes.length === 1)"
        class="inv-item link"
        :href="router.resolve(it.to!).href"
        @click.prevent="open(it)"
      >{{ it.label }} <b>{{ it.value ?? '—' }}</b></a>
      <span v-else class="inv-item">{{ it.label }} <b>{{ it.value ?? '—' }}</b></span>
    </template>
  </div>
</template>

<style scoped>
.inv {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px 8px;
  font-size: 13px;
  color: var(--text-3);
}
.inv-item b { color: var(--text-1); font-weight: 600; margin-left: 2px; }
.inv-item.link { color: var(--text-3); text-decoration: none; }
.inv-item.link:hover, .inv-item.link:hover b { color: var(--accent); }
button.inv-item { border: 0; padding: 0; background: transparent; font: inherit; cursor: pointer; }
.scope-list { display: grid; justify-items: start; gap: 4px; max-height: 240px; overflow: auto; }
.scope-list :deep(.el-button) { margin: 0; height: auto; white-space: normal; text-align: left; overflow-wrap: anywhere; }
.inv-dot { color: var(--text-3); opacity: .6; }
</style>
