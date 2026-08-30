<script setup lang="ts">
import { computed } from 'vue'
import type { RollingUpdate, Strategy, WorkloadKind } from '@/types/workload'
import { parseQuantity } from '@/utils/quantity'

const props = defineProps<{ kind: WorkloadKind }>()
const model = defineModel<Strategy>()

/** type 选项按 kind：deployment=[RollingUpdate, Recreate]；statefulset/daemonset=[RollingUpdate, OnDelete] */
const TYPE_OPTIONS: Record<WorkloadKind, string[]> = {
  deployment: ['RollingUpdate', 'Recreate'],
  statefulset: ['RollingUpdate', 'OnDelete'],
  daemonset: ['RollingUpdate', 'OnDelete'],
}

/** A4：type≠RollingUpdate → 隐藏 rollingUpdate 整段 */
const showRolling = computed(() => (model.value?.type ?? 'RollingUpdate') === 'RollingUpdate')

function write(partial: Partial<Strategy>): void {
  model.value = { ...(model.value ?? {}), ...partial }
}

/** 切到非 RollingUpdate 时丢弃 rollingUpdate（A4）；切回 RollingUpdate 保留已有值 */
const type = computed<string>({
  get: () => model.value?.type ?? 'RollingUpdate',
  set: (t) => {
    if (t === 'RollingUpdate') write({ type: t })
    else write({ type: t, rollingUpdate: null })
  },
})

function writeRolling(partial: Partial<RollingUpdate>): void {
  const cur = model.value?.rollingUpdate ?? {}
  write({ rollingUpdate: { ...cur, ...partial } })
}

/** quantity 字段为 string 型（K8s quantity："1"/"25%"/"1Gi"）→ el-input；空输入 → null（未设置） */
const maxSurge = computed<string>({
  get: () => model.value?.rollingUpdate?.maxSurge ?? '',
  set: (v) => {
    const s = v.trim()
    writeRolling({ maxSurge: s === '' ? null : s })
  },
})

const maxUnavailable = computed<string>({
  get: () => model.value?.rollingUpdate?.maxUnavailable ?? '',
  set: (v) => {
    const s = v.trim()
    writeRolling({ maxUnavailable: s === '' ? null : s })
  },
})

/** partition 为 number 型 → el-input-number */
const partition = computed<number | null>({
  get: () => model.value?.rollingUpdate?.partition ?? null,
  set: (v) => writeRolling({ partition: v ?? null }),
})

/** B5（仅 deployment）：maxSurge/maxUnavailable 之一为 0 时，提示另一个须 > 0（K8s 要求至少一个允许滚动推进；后端严格校验，前端仅提示） */
const b5Hint = computed(() => {
  if (props.kind !== 'deployment') return ''
  const ru = model.value?.rollingUpdate
  if (!ru) return ''
  const s = parseQuantity(ru.maxSurge)
  const u = parseQuantity(ru.maxUnavailable)
  if (s === 0 && u === 0) return 'maxSurge 与 maxUnavailable 不能同时为 0，至少一个须 > 0'
  if (s === 0) return 'maxSurge 为 0：maxUnavailable 须 > 0'
  if (u === 0) return 'maxUnavailable 为 0：maxSurge 须 > 0'
  return ''
})
</script>

<template>
  <div class="strategy-editor">
    <div class="se-row">
      <span class="se-label">更新策略</span>
      <el-select v-model="type" style="width: 160px">
        <el-option v-for="t in TYPE_OPTIONS[kind]" :key="t" :label="t" :value="t" />
      </el-select>
    </div>
    <div v-if="showRolling" class="se-row se-rolling">
      <!-- deployment：maxSurge + maxUnavailable -->
      <template v-if="kind === 'deployment'">
        <el-input v-model="maxSurge" placeholder="maxSurge（如 1 或 25%）" style="width: 170px" />
        <el-input v-model="maxUnavailable" placeholder="maxUnavailable（如 1 或 25%）" style="width: 190px" />
      </template>
      <!-- statefulset：仅 partition -->
      <el-input-number
        v-else-if="kind === 'statefulset'"
        v-model="partition"
        :min="0"
        controls-position="right"
        placeholder="partition"
        style="width: 140px"
      />
      <!-- A5：daemonset 无 maxSurge，仅 maxUnavailable -->
      <el-input
        v-else-if="kind === 'daemonset'"
        v-model="maxUnavailable"
        placeholder="maxUnavailable（如 1 或 25%）"
        style="width: 190px"
      />
      <span v-if="b5Hint" class="b5-hint">{{ b5Hint }}</span>
    </div>
  </div>
</template>

<style scoped>
.strategy-editor { width: 100% }
.se-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.se-label { width: 90px; flex-shrink: 0; font-size: 12px; color: var(--el-text-color-secondary) }
.se-rolling { padding-left: 98px }
.b5-hint { font-size: 12px; color: var(--el-text-color-secondary) }
</style>
