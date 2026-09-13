<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

/** 通用 Record<string,string> 键值对编辑器（镜像 ResourcesEditor 的本地行 + emit 回写模式） */
interface Row { key: string; value: string }

const model = defineModel<Record<string, string> | null>()
const rows = ref<Row[]>([])

function buildRows(obj?: Record<string, string> | null): Row[] {
  return Object.entries(obj ?? {}).map(([key, value]) => ({ key, value }))
}

let selfUpdate = false
watch(model, (obj) => {
  if (selfUpdate) return
  rows.value = buildRows(obj)
}, { immediate: true })

function emitUpdate(): void {
  const out: Record<string, string> = {}
  for (const row of rows.value) {
    const k = row.key.trim()
    if (!k) continue // 空 key 的行不落地（避免脏数据）
    out[k] = row.value
  }
  selfUpdate = true
  model.value = out
  nextTick(() => { selfUpdate = false })
}

function add(): void { rows.value.push({ key: '', value: '' }); emitUpdate() }
function remove(i: number): void { rows.value.splice(i, 1); emitUpdate() }
</script>

<template>
  <div class="kv-editor">
    <div v-for="(row, i) in rows" :key="i" class="kv-row ktp-row">
      <el-input v-model="row.key" placeholder="key" style="width: 150px" @input="emitUpdate" />
      <el-input v-model="row.value" placeholder="value" style="width: 220px" @input="emitUpdate" />
      <el-button link type="danger" @click="remove(i)">删除</el-button>
    </div>
    <el-button link type="primary" @click="add">+ 添加键值对</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 4px; align-items: center; flex-wrap: wrap }
.ktp-row { margin-bottom: 4px }
</style>
