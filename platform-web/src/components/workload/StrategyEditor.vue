<script setup lang="ts">
import { computed } from 'vue'
import type { RollingUpdate, Strategy, WorkloadKind } from '@/types/workload'
import FieldHelp from './FieldHelp.vue'

const props = defineProps<{ kind: WorkloadKind }>()
const model = defineModel<Strategy>()

/** type 选项按 kind：deployment=[RollingUpdate, Recreate]；statefulset/daemonset=[RollingUpdate, OnDelete] */
const TYPE_OPTIONS: Record<WorkloadKind, string[]> = {
  deployment: ['RollingUpdate', 'Recreate'],
  statefulset: ['RollingUpdate', 'OnDelete'],
  daemonset: ['RollingUpdate', 'OnDelete'],
}

/** A4：type≠RollingUpdate → 隐藏滚动参数 */
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

/**
 * maxSurge / maxUnavailable：表单用「数字 + %」限制输入（el-input-number，仅非负整数），
 * 存为 K8s quantity 字符串 "N%"；空 → null（未设置，K8s 默认 25%）。
 * get 时把已有 "N%"（或历史遗留的裸数字）解析回数值。
 */
function toPctNum(raw?: string | null): number | null {
  if (raw == null) return null
  const n = Number.parseFloat(String(raw).replace(/%$/, ''))
  return Number.isNaN(n) ? null : n
}

const maxSurge = computed<number | null>({
  get: () => toPctNum(model.value?.rollingUpdate?.maxSurge),
  set: (n) => writeRolling({ maxSurge: n == null ? null : `${n}%` }),
})

const maxUnavailable = computed<number | null>({
  get: () => toPctNum(model.value?.rollingUpdate?.maxUnavailable),
  set: (n) => writeRolling({ maxUnavailable: n == null ? null : `${n}%` }),
})

/** partition 为 number 型 → el-input-number */
const partition = computed<number | null>({
  get: () => model.value?.rollingUpdate?.partition ?? null,
  set: (v) => writeRolling({ partition: v ?? null }),
})

/** B5（仅 deployment）：两者都显式填 0 才非法（留空 = K8s 默认 25%，不算 0）。后端严格校验，前端仅提示 */
const b5Hint = computed(() => {
  if (props.kind !== 'deployment') return ''
  if (maxSurge.value === 0 && maxUnavailable.value === 0) return '最大超出与最大不可用不能同时为 0，至少一个须 > 0'
  return ''
})
</script>

<template>
  <!-- 更新策略独立成屏：字段按顺序自上而下竖排（不再水平并排） -->
  <el-form label-width="140px" label-position="left">
    <el-form-item>
      <template #label>更新策略 <FieldHelp tip="工作负载的滚动更新方式：RollingUpdate（滚动更新，默认）/ Recreate（Deployment：先删后建）/ OnDelete（StatefulSet、DaemonSet：手动删除 Pod 才更新）。" /></template>
      <el-select v-model="type" style="width: 260px">
        <el-option v-for="t in TYPE_OPTIONS[kind]" :key="t" :label="t" :value="t" />
      </el-select>
    </el-form-item>

    <!-- deployment：最大超出 + 最大不可用 -->
    <template v-if="showRolling && kind === 'deployment'">
      <el-form-item>
        <template #label>最大超出 <FieldHelp tip="maxSurge：滚动更新期间允许超出期望副本数的百分比（K8s 默认 25%）。例：30% = 新旧 Pod 总数最多为期望的 130%。" /></template>
        <span class="se-num">
          <el-input-number v-model="maxSurge" :min="0" :max="100" controls-position="right" placeholder="默认 25" style="width: 160px" />
          <span class="se-pct">%</span>
        </span>
      </el-form-item>
      <el-form-item>
        <template #label>最大不可用 <FieldHelp tip="maxUnavailable：滚动更新期间允许不可用的副本数百分比（K8s 默认 25%）。例：30% = 旧 Pod 可缩到期望的 70%。" /></template>
        <span class="se-num">
          <el-input-number v-model="maxUnavailable" :min="0" :max="100" controls-position="right" placeholder="默认 25" style="width: 160px" />
          <span class="se-pct">%</span>
        </span>
      </el-form-item>
    </template>

    <!-- statefulset：仅更新分区 -->
    <template v-else-if="showRolling && kind === 'statefulset'">
      <el-form-item>
        <template #label>更新分区 <FieldHelp tip="partition：StatefulSet 分区更新，序号 ≤ partition 的 Pod 才会被更新（默认 0 = 全部）。" /></template>
        <el-input-number v-model="partition" :min="0" controls-position="right" placeholder="默认 0" style="width: 160px" />
      </el-form-item>
    </template>

    <!-- A5：daemonset 无 maxSurge，仅最大不可用 -->
    <template v-else-if="showRolling && kind === 'daemonset'">
      <el-form-item>
        <template #label>最大不可用 <FieldHelp tip="maxUnavailable：滚动更新期间允许不可用的副本数百分比（K8s 默认 25%）。" /></template>
        <span class="se-num">
          <el-input-number v-model="maxUnavailable" :min="0" :max="100" controls-position="right" placeholder="默认 25" style="width: 160px" />
          <span class="se-pct">%</span>
        </span>
      </el-form-item>
    </template>

    <!-- B5 提示：缩进到内容列（x=140） -->
    <div v-if="b5Hint" class="b5-hint">{{ b5Hint }}</div>
  </el-form>
</template>

<style scoped>
/* 数字输入 + % 后缀贴在一起 */
.se-num { display: inline-flex; align-items: center; gap: 4px }
.se-pct { font-size: 13px; color: var(--text-2) }
.b5-hint { padding-left: 140px; font-size: 12px; color: var(--el-color-warning); line-height: 1.6 }
</style>
