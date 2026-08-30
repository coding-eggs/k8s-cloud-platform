<script setup lang="ts">
import type { VolumeMount } from '@/types/workload'

const props = defineProps<{ volumeNames: string[] }>()
const model = defineModel<VolumeMount[]>()

function add(): void {
  model.value ??= []
  model.value.push({ name: '', mountPath: '', readOnly: false, subPath: '' })
}

function remove(i: number): void {
  model.value?.splice(i, 1)
}

/** C3：挂载引用的 volume 不在给定的 volumeNames（父组件传 spec.volumes + volumeClaimTemplates 名称并集）中 → 标红；空名（新行未选）不提示 */
function invalidName(m: VolumeMount): boolean {
  return m.name !== '' && !props.volumeNames.includes(m.name)
}
</script>

<template>
  <div class="kv-editor">
    <div v-for="(m, i) in model" :key="i" class="kv-row" :class="{ 'is-invalid': invalidName(m) }">
      <el-select
        v-model="m.name"
        filterable
        allow-create
        default-first-option
        placeholder="Volume 名称"
        style="width: 160px"
      >
        <el-option v-for="n in volumeNames" :key="n" :label="n" :value="n" />
      </el-select>
      <el-input v-model="m.mountPath" placeholder="mountPath（如 /data）" style="width: 180px" />
      <el-input v-model="m.subPath" placeholder="subPath（可选）" style="width: 140px" />
      <el-switch v-model="m.readOnly" active-text="只读" />
      <span v-if="invalidName(m)" class="c3-hint">Volume 未定义</span>
      <el-button link type="danger" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加挂载</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.add-row-btn { width: 100% }
.c3-hint { font-size: 12px; color: var(--el-color-danger) }
.kv-row.is-invalid :deep(.el-select__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset }
</style>
