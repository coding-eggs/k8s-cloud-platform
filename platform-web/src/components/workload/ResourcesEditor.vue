<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { Resources } from '@/types/workload'
import FieldHelp from './FieldHelp.vue'

/** 行内值为「基础单位」数字：cpu=核数、memory=字节、其它资源=整数计数；null=未设置 */
interface ResRow { name: string; request: number | null; limit: number | null }

const model = defineModel<Resources>()
const rows = ref<ResRow[]>([])

/** cpu/memory 用「数字 + 固定单位」编辑：cpu→毫核 m，memory→Mi；其它资源为整数计数（无单位） */
function unitOf(name: string): 'm' | 'Mi' | null {
  if (name === 'cpu') return 'm'
  if (name === 'memory') return 'Mi'
  return null
}

/** 基础单位（核/字节/计数）→ 输入框显示值（毫核/Mi/原值）；null → null */
function toDisplayNum(value: number | null, name: string): number | null {
  if (value == null) return null
  const u = unitOf(name)
  if (!u) return value
  return u === 'm' ? value * 1000 : value / 2 ** 20
}

/** cpu/memory：输入框值（毫核/Mi，只取数字）→ 基础单位写回；空 → null（未设置） */
function setUnitNum(row: ResRow, side: 'request' | 'limit', n: number | string | null | undefined): void {
  const u = unitOf(row.name)
  if (!u) return
  const s = String(n ?? '').replace(/\D/g, '')
  row[side] = s === '' ? null : (u === 'm' ? Number(s) / 1000 : Number(s) * 2 ** 20)
  emitUpdate()
}

/** 其它资源：整数计数输入 → 基础单位写回；空/非法 → null */
function setCount(row: ResRow, side: 'request' | 'limit', n: number | string | null | undefined): void {
  const s = String(n ?? '').replace(/[^\d.-]/g, '')
  row[side] = (s === '' || Number.isNaN(Number(s))) ? null : Number(s)
  emitUpdate()
}

/** 非法格（负数/NaN）；null=未设置，合法 */
function cellInvalid(value: number | null): boolean {
  return value != null && (Number.isNaN(value) || value < 0)
}

/** B4：requests 与 limits 均存在且 requests > limits */
function rowB4(row: ResRow): boolean {
  const r = row.request, l = row.limit
  if (r == null || l == null) return false
  return r > l
}

/** 聚合所有无效格（负数/NaN）与 B4 违例行 */
const bad = computed(() => rows.value.some(row => cellInvalid(row.request) || cellInvalid(row.limit) || rowB4(row)))

function buildRows(obj?: Resources): ResRow[] {
  const keys: string[] = ['cpu', 'memory']
  for (const k of Object.keys(obj?.requests ?? {})) if (!keys.includes(k)) keys.push(k)
  for (const k of Object.keys(obj?.limits ?? {})) if (!keys.includes(k)) keys.push(k)
  return keys.map(name => ({ name, request: obj?.requests?.[name] ?? null, limit: obj?.limits?.[name] ?? null }))
}

let selfUpdate = false
watch(model, (obj) => {
  if (selfUpdate) return
  rows.value = buildRows(obj)
}, { immediate: true })

function emitUpdate(): void {
  const requests: Record<string, number> = {}
  const limits: Record<string, number> = {}
  for (const row of rows.value) {
    const name = row.name.trim()
    if (!name) continue
    if (row.request != null) requests[name] = row.request
    if (row.limit != null) limits[name] = row.limit
  }
  selfUpdate = true
  model.value = { requests, limits }
  nextTick(() => { selfUpdate = false })
}

function remove(i: number): void { rows.value.splice(i, 1); emitUpdate() }

function ph(name: string): string {
  if (name === 'cpu') return '500'          // 毫核：500 = 0.5 核
  if (name === 'memory') return '128'       // Mi：128Mi
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
            <el-input :model-value="toDisplayNum(row.request, row.name)"
                      @update:model-value="(v: number | string | null | undefined) => setUnitNum(row, 'request', v)"
                      :placeholder="ph(row.name)" style="width: 150px">
              <template #append>{{ unitOf(row.name) }}</template>
            </el-input>
          </span>
          <el-input v-else :model-value="row.request ?? ''" @input="(v: string) => setCount(row, 'request', v)" :placeholder="ph(row.name)" />
          <div v-if="cellInvalid(row.request)" class="error-hint">数量需为非负数</div>
        </div>

        <span v-if="row.name === 'cpu'" class="res-name">cpu.limits <FieldHelp tip="CPU（核）。单位 m = 毫核，十进制换算：1 核 = 1000m，故填 500 = 0.5 核。requests 为申请量，limits 为上限。" /></span>
        <span v-else-if="row.name === 'memory'" class="res-name">memory.limits <FieldHelp tip="内存。单位 Mi = Mebibyte，二进制换算：1 Mi = 1024 Ki = 1048576 字节，故填 512 = 512 MiB。requests 为申请量，limits 为上限。" /></span>

        <div class="cell" :class="{ 'is-error': cellInvalid(row.limit) }">
          <span v-if="unitOf(row.name)" class="unit-num">
            <el-input :model-value="toDisplayNum(row.limit, row.name)"
                             @update:model-value="(v: number | string | null | undefined) => setUnitNum(row, 'limit', v)"
                             :placeholder="ph(row.name)" style="width: 150px">
              <template #append>{{ unitOf(row.name) }}</template>
            </el-input>
          </span>
          <el-input v-else :model-value="row.limit ?? ''" @input="(v: string) => setCount(row, 'limit', v)" :placeholder="ph(row.name)" />
          <div v-if="cellInvalid(row.limit)" class="error-hint">数量需为非负数</div>
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
