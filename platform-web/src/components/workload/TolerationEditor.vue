<script setup lang="ts">
import type { Toleration } from '@/types/workload'

const model = defineModel<Toleration[]>()

const OPERATORS = ['Equal', 'Exists'] as const
const EFFECTS = ['NoSchedule', 'PreferNoSchedule', 'NoExecute'] as const

function add(): void {
  model.value ??= []
  model.value.push({ key: '', operator: 'Exists', value: '', effect: 'NoSchedule', tolerationSeconds: null })
}

function remove(i: number): void {
  model.value?.splice(i, 1)
}

/** B6：key 为空时强制 operator=Exists（空 key 只有 Exists 有意义，即容忍全部污点），同时清掉 value（K8s 要求 Exists 时 value 为空；el-select 对程序化改值不触发 change，须在此处一并清理） */
function onKeyInput(t: Toleration): void {
  if (!t.key) {
    t.operator = 'Exists'
    t.value = ''
  }
}

/** B6：切到 Exists 时清掉 value（K8s 要求 Exists 时 value 必须为空），避免提交非法对象 */
function onOperatorChange(t: Toleration): void {
  if (t.operator === 'Exists') t.value = ''
}

/** B6：value 仅在 key 非空且 operator=Equal 时可编辑 */
function valueEnabled(t: Toleration): boolean {
  return !!t.key && t.operator === 'Equal'
}

/** B7：tolerationSeconds 仅 effect=NoExecute 时生效，其余情况禁用并提示将被忽略 */
function secondsIgnored(t: Toleration): boolean {
  return t.effect !== 'NoExecute'
}
</script>

<template>
  <div class="kv-editor">
    <div v-for="(t, i) in model" :key="i" class="kv-row">
      <el-input v-model="t.key" placeholder="key（空=容忍全部污点）" style="width: 170px" @input="onKeyInput(t)" />
      <el-select v-model="t.operator" style="width: 100px" @change="onOperatorChange(t)">
        <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
      </el-select>
      <el-input v-model="t.value" placeholder="value" style="width: 150px" :disabled="!valueEnabled(t)" />
      <el-select v-model="t.effect" style="width: 150px">
        <el-option v-for="e in EFFECTS" :key="e" :label="e" :value="e" />
      </el-select>
      <el-input-number
        v-model="t.tolerationSeconds"
        :min="1"
        controls-position="right"
        placeholder="秒"
        style="width: 120px"
        :disabled="secondsIgnored(t)"
      />
      <span v-if="secondsIgnored(t)" class="b7-hint">将被忽略</span>
      <el-button link type="danger" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加容忍</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.add-row-btn { width: 100% }
.b7-hint { font-size: 12px; color: var(--el-text-color-secondary) }
</style>
