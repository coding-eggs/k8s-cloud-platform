<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useNodeCatalog } from '@/stores/nodeCatalog'

/** 节点选择器（nodeSelector）：key/value 均为下拉，候选来自集群节点的真实标签；仍可自由输入。 */
const model = defineModel<Record<string, string>>()
const cat = useNodeCatalog()
const rows = ref<[string, string][]>([])

let selfUpdate = false
watch(model, (obj) => {
  if (selfUpdate) return
  rows.value = Object.entries(obj ?? {})
}, { immediate: true })

function emitUpdate(): void {
  const obj: Record<string, string> = {}
  for (const [k, v] of rows.value) {
    if (k.trim() !== '') obj[k] = v
  }
  selfUpdate = true
  model.value = obj
  nextTick(() => { selfUpdate = false })
}

function add(): void {
  rows.value.push(['', ''])
}

function remove(i: number): void {
  rows.value.splice(i, 1)
  emitUpdate()
}
</script>

<template>
  <div class="kv-editor">
    <div v-for="(row, i) in rows" :key="i" class="kv-row">
      <el-select
        v-model="row[0]" filterable allow-create default-first-option clearable
        placeholder="标签键（可搜索 / 输入）" style="width: 220px" @change="emitUpdate"
      >
        <el-option v-for="k in cat.labelKeyOptions" :key="k" :label="k" :value="k" />
      </el-select>
      <el-select
        v-model="row[1]" filterable allow-create default-first-option clearable
        placeholder="标签值（可搜索 / 输入）" style="flex: 1" @change="emitUpdate"
      >
        <el-option v-for="v in cat.labelValueOptions(row[0])" :key="v" :label="v" :value="v" />
      </el-select>
      <el-button link type="danger" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加标签</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center }
.add-row-btn { width: 100% }
</style>
