<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { Resources } from '@/types/workload'
import { parseQuantity } from '@/utils/quantity'

interface ResRow { name: string; request: string; limit: string }

const FIXED = new Set(['cpu', 'memory'])
const model = defineModel<Resources>()
const rows = ref<ResRow[]>([])

/** 非空但解析失败 → 该格无效（空值合法，表示未设置） */
function cellInvalid(raw: string): boolean {
  const s = raw.trim()
  return s !== '' && Number.isNaN(parseQuantity(s))
}

/** B4：requests 与 limits 均存在且均可解析为有限数值时，requests > limits */
function rowB4(row: ResRow): boolean {
  const r = row.request.trim()
  const l = row.limit.trim()
  if (r === '' || l === '') return false
  const rn = parseQuantity(r)
  const ln = parseQuantity(l)
  if (Number.isNaN(rn) || Number.isNaN(ln)) return false
  return rn > ln
}

/** 聚合所有无效格（NaN）与 B4 违例行 */
const bad = computed(() => rows.value.some(row => cellInvalid(row.request) || cellInvalid(row.limit) || rowB4(row)))

function buildRows(obj?: Resources): ResRow[] {
  const keys: string[] = ['cpu', 'memory']
  for (const k of Object.keys(obj?.requests ?? {})) if (!keys.includes(k)) keys.push(k)
  for (const k of Object.keys(obj?.limits ?? {})) if (!keys.includes(k)) keys.push(k)
  return keys.map(name => ({ name, request: obj?.requests?.[name] ?? '', limit: obj?.limits?.[name] ?? '' }))
}

let selfUpdate = false
watch(model, (obj) => {
  if (selfUpdate) return
  rows.value = buildRows(obj)
}, { immediate: true })

function emitUpdate(): void {
  const requests: Record<string, string> = {}
  const limits: Record<string, string> = {}
  for (const row of rows.value) {
    const name = row.name.trim()
    if (!name) continue
    const r = row.request.trim()
    const l = row.limit.trim()
    if (r !== '') requests[name] = r
    if (l !== '') limits[name] = l
  }
  selfUpdate = true
  model.value = { requests, limits }
  nextTick(() => { selfUpdate = false })
}

function add(): void { rows.value.push({ name: '', request: '', limit: '' }) }
function remove(i: number): void { rows.value.splice(i, 1); emitUpdate() }

function ph(name: string, side: 'request' | 'limit'): string {
  if (name === 'cpu') return side === 'request' ? '100m' : '0.5'
  if (name === 'memory') return side === 'request' ? '128Mi' : '1Gi'
  return '数量（可选）'
}

defineExpose({ isValid: () => !bad.value })
</script>

<template>
  <div class="kv-editor">
    <div class="res-header">
      <span class="col-name">资源</span>
      <span class="col-cell">requests</span>
      <span class="col-cell">limits</span>
      <span class="col-op"></span>
    </div>
    <div v-for="(row, i) in rows" :key="i" class="res-item">
      <div class="res-row" :class="{ 'is-error': rowB4(row) }">
        <span v-if="FIXED.has(row.name)" class="res-name">{{ row.name }}</span>
        <el-input v-else v-model="row.name" placeholder="资源名（如 ephemeral-storage）" style="width: 160px" @input="emitUpdate" />
        <div class="cell" :class="{ 'is-error': cellInvalid(row.request) }">
          <el-input v-model="row.request" :placeholder="ph(row.name, 'request')" @input="emitUpdate" />
          <div v-if="cellInvalid(row.request)" class="error-hint">无法解析该数量</div>
        </div>
        <div class="cell" :class="{ 'is-error': cellInvalid(row.limit) }">
          <el-input v-model="row.limit" :placeholder="ph(row.name, 'limit')" @input="emitUpdate" />
          <div v-if="cellInvalid(row.limit)" class="error-hint">无法解析该数量</div>
        </div>
        <el-button v-if="!FIXED.has(row.name)" link type="danger" style="width: 48px" @click="remove(i)">删除</el-button>
        <span v-else class="col-op"></span>
      </div>
      <div v-if="rowB4(row)" class="b4-hint">requests 不能大于 limits</div>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加资源</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.res-header { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; font-size: 12px; color: var(--el-text-color-secondary) }
.col-name, .res-name { width: 160px; flex-shrink: 0 }
.res-name { line-height: 32px }
.col-cell { flex: 1; min-width: 0 }
.col-op { width: 48px; flex-shrink: 0 }
.res-item { margin-bottom: 8px }
.res-row { display: flex; gap: 8px; align-items: center }
.cell { flex: 1; min-width: 0 }
.error-hint, .b4-hint { color: var(--el-color-danger); font-size: 12px; line-height: 16px; margin-top: 2px }
.cell.is-error :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset }
.res-row.is-error :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset }
.add-row-btn { width: 100% }
</style>
