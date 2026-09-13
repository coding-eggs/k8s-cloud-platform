<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const model = defineModel<Record<string, string>>()
const props = defineProps<{ disabled?: boolean }>()
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
      <el-input v-model="row[0]" placeholder="键" style="width: 160px" :disabled="props.disabled" @input="emitUpdate" />
      <el-input v-model="row[1]" placeholder="值" style="flex: 1" :disabled="props.disabled" @input="emitUpdate" />
      <el-button link type="danger" :disabled="props.disabled" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain :disabled="props.disabled" @click="add">+ 添加标签</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center }
.add-row-btn { width: 100% }
</style>
