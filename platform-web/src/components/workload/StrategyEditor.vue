<script setup lang="ts">
import { computed, watch } from 'vue'
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

/** 本地暂存：切到非 RollingUpdate 时 model 丢弃 rollingUpdate（A4，保证提交正确），但把已填的滚动参数
 *  缓存起来；切回 RollingUpdate 时恢复 —— 避免「切走再切回」丢失 maxSurge/maxUnavailable/partition。
 *  model 始终只保留当前 type 下合法的字段（A4 不变式，提交/转换器不变） */
let rollingStash: RollingUpdate | null = null
/** 防回环：外部加载（loadDetail/重置）时据此重建 stash，避免上一个工作负载的残留串数据 */
let lastEmitted: string | null = null

watch(
  () => model.value,
  (s) => {
    const snap = JSON.stringify(s ?? null)
    if (snap === lastEmitted) return   // 自己 write 的回声 → 跳过
    // 外部加载：RollingUpdate 且有 rollingUpdate 则记入 stash；否则清空（避免残留）
    const ru = s?.rollingUpdate
    if ((s?.type ?? 'RollingUpdate') === 'RollingUpdate' && ru) {
      rollingStash = ru
    } else {
      rollingStash = null
    }
  },
  { immediate: true },
)

function write(partial: Partial<Strategy>): void {
  const next: Strategy = { ...(model.value ?? {}), ...partial }
  lastEmitted = JSON.stringify(next)
  model.value = next
}

/** 切到非 RollingUpdate 时丢弃 rollingUpdate（A4）；切回 RollingUpdate 从暂存恢复已填值 */
const type = computed<string>({
  get: () => model.value?.type ?? 'RollingUpdate',
  set: (t) => {
    if (t === 'RollingUpdate') {
      const ru = rollingStash ?? model.value?.rollingUpdate ?? null
      write({ type: t, rollingUpdate: ru })
    } else {
      rollingStash = model.value?.rollingUpdate ?? null
      write({ type: t, rollingUpdate: null })
    }
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

    <!-- statefulset：最大不可用 + 更新分区 -->
    <template v-else-if="showRolling && kind === 'statefulset'">
      <el-form-item>
        <template #label>最大不可用 <FieldHelp tip="maxUnavailable：滚动更新期间允许不可用的副本数百分比（K8s 默认 25%）。" /></template>
        <span class="se-num">
          <el-input-number v-model="maxUnavailable" :min="0" :max="100" controls-position="right" placeholder="默认 25" style="width: 160px" />
          <span class="se-pct">%</span>
        </span>
      </el-form-item>
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
