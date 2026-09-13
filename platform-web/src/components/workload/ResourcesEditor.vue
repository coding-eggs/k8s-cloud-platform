<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { Resources } from '@/types/workload'
import { parseQuantity } from '@/utils/quantity'
import FieldHelp from './FieldHelp.vue'

interface ResRow { name: string; request: string; limit: string }

const model = defineModel<Resources>()
const rows = ref<ResRow[]>([])

/** cpu/memory 用「数字 + 固定单位」编辑：cpu→毫核 m，memory→Mi；其它资源仍自由文本 */
function unitOf(name: string): 'm' | 'Mi' | null {
  if (name === 'cpu') return 'm'
  if (name === 'memory') return 'Mi'
  return null
}

/** 现有 quantity → 目标单位数值（parseQuantity 返回基础单位：核 / 字节）；空或无法解析 → null */
function toUnitNum(raw: string, name: string): number | null {
  const u = unitOf(name)
  if (!u) return null
  const s = raw.trim()
  if (s === '') return null
  const base = parseQuantity(s)
  if (Number.isNaN(base)) return null
  return u === 'm' ? base * 1000 : base / 2 ** 20
}

/** 数字（或清空）→ 回写为 `${n}m` / `${n}Mi`；null/undefined/NaN → 空串（未设置） */
function setUnitNum(row: ResRow, side: 'request' | 'limit', n: number | null | undefined): void {
  const u = unitOf(row.name)
  if (!u) return
  row[side] = (n == null || Number.isNaN(n)) ? '' : `${n}${u}`
  emitUpdate()
}

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

    <div v-for="(row, i) in rows" :key="i" class="res-item">
      <div class="res-row" :class="{ 'is-error': rowB4(row) }">
        <span v-if="row.name === 'cpu'" class="res-name">cpu.requests <FieldHelp tip="CPU（核）。单位 m = 毫核，十进制换算：1 核 = 1000m，故填 500 = 0.5 核。requests 为申请量，limits 为上限。" /></span>
        <span v-else-if="row.name === 'memory'" class="res-name">memory.requests <FieldHelp tip="内存。单位 Mi = Mebibyte，二进制换算：1 Mi = 1024 Ki = 1048576 字节，故填 512 = 512 MiB。requests 为申请量，limits 为上限。" /></span>

        <div class="cell" :class="{ 'is-error': cellInvalid(row.request) }">
          <span v-if="unitOf(row.name)" class="unit-num">

            <el-input :model-value="toUnitNum(row.request, row.name)"
                      @update:model-value="(v: number | null | undefined) => setUnitNum(row, 'request', v)"
                      :min="0" controls-position="right" placeholder="可选" style="width: 150px"
                      :formatter="(v: string) => v.replace(/\D/g, '')"
                      :parser="(v: string) => v.replace(/\D/g, '')">

              <template #append>{{ unitOf(row.name) }}</template>
            </el-input>
          </span>
          <el-input v-else v-model="row.request" :placeholder="ph(row.name, 'request')" @input="emitUpdate" />
          <div v-if="cellInvalid(row.request)" class="error-hint">无法解析该数量</div>
        </div>

        <span v-if="row.name === 'cpu'" class="res-name">cpu.limits <FieldHelp tip="CPU（核）。单位 m = 毫核，十进制换算：1 核 = 1000m，故填 500 = 0.5 核。requests 为申请量，limits 为上限。" /></span>
        <span v-else-if="row.name === 'memory'" class="res-name">memory.limits <FieldHelp tip="内存。单位 Mi = Mebibyte，二进制换算：1 Mi = 1024 Ki = 1048576 字节，故填 512 = 512 MiB。requests 为申请量，limits 为上限。" /></span>

        <div class="cell" :class="{ 'is-error': cellInvalid(row.limit) }">
          <span v-if="unitOf(row.name)" class="unit-num">
            <el-input :model-value="toUnitNum(row.limit, row.name)"
                             @update:model-value="(v: number | null | undefined) => setUnitNum(row, 'limit', v)"
                             :min="0" controls-position="right" placeholder="可选" style="width: 150px"
                             :formatter="(v: string) => v.replace(/\D/g, '')"
                             :parser="(v: string) => v.replace(/\D/g, '')">
              <template #append>{{ unitOf(row.name) }}</template>
            </el-input>
          </span>
          <el-input v-else v-model="row.limit" :placeholder="ph(row.name, 'limit')" @input="emitUpdate" />
          <div v-if="cellInvalid(row.limit)" class="error-hint">无法解析该数量</div>
        </div>
      </div>
      <div v-if="rowB4(row)" class="b4-hint">requests 不能大于 limits</div>
    </div>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.res-header { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; font-size: 12px; color: var(--el-text-color-secondary) }
.col-name, .res-name { width: 160px; flex-shrink: 0 }
.res-name { display: inline-flex; align-items: center; gap: 4px }
.unit-num { display: inline-flex; align-items: center; gap: 4px }
/* 单位 tag：高度对齐 input（--el-component-size），配色用主题 fill/border/text 变量 → 深浅色都贴合 */
.unit-suffix {
  height: var(--el-component-size, 32px);
  padding: 0 10px;
  font-size: 13px;
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
  border-radius: 4px;
  background-color: var(--el-fill-color-light);
  border-color: var(--el-border-color-lighter);
  color: var(--el-text-color-secondary);
}
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
