<script setup lang="ts">
/**
 * 资源量输入：只填数字，单位固定（CPU = 核、内存 = MiB、存储 = GiB），存回 K8s quantity 字符串。
 *
 * - CPU 可填小数（最多 3 位），整数存 `2`，小数存毫核 `500m`；内存 / 存储只能是整数，存 `512Mi` / `10Gi`。
 * - 回显老数据（`1Gi`、`0.5`、`1G` 这类）按固定单位换算；换算后不是整数的（如 `1G` = 953.67 MiB）
 *   取整显示并提示原值，**用户没改就不回写**，原值原样保留。
 * - 清空 = 不设置（存空串，提交时丢掉）。
 */
import { computed } from 'vue'
import { parseQuantity } from '@/utils/quantity'

const props = defineProps<{
  kind: 'cpu' | 'memory' | 'storage'
  placeholder?: string
  disabled?: boolean
}>()
const model = defineModel<string | null | undefined>()

const SPEC = {
  cpu: { unit: '核', factor: 1, precision: 3, step: 0.1, min: 0.001 },
  memory: { unit: 'MiB', factor: 2 ** 20, precision: 0, step: 64, min: 1 },
  storage: { unit: 'GiB', factor: 2 ** 30, precision: 0, step: 1, min: 1 },
} as const
const spec = computed(() => SPEC[props.kind])

function toQuantity(v: number): string {
  if (props.kind === 'memory') return `${v}Mi`
  if (props.kind === 'storage') return `${v}Gi`
  return Number.isInteger(v) ? String(v) : `${Math.round(v * 1000)}m`
}

/** 原值换算到固定单位后的精确值；空 / 解析不了为 null */
const exact = computed<number | null>(() => {
  const raw = (model.value ?? '').trim()
  if (!raw) return null
  const base = parseQuantity(raw)
  return Number.isFinite(base) && base > 0 ? base / spec.value.factor : null
})
const shown = computed<number | undefined>(() => {
  if (exact.value == null) return undefined
  const p = 10 ** spec.value.precision
  return Math.max(spec.value.min, Math.round(exact.value * p) / p)
})
/** 原值与显示值不等价（取整了 / 解析不了）时提示原值 */
const note = computed(() => {
  const raw = (model.value ?? '').trim()
  if (!raw) return ''
  if (exact.value == null) return `原值「${raw}」无法识别，重新填写后按 ${spec.value.unit} 保存`
  return shown.value != null && toQuantity(shown.value) !== raw && Math.abs(shown.value - exact.value) > 1e-9
    ? `原值「${raw}」，修改后按 ${spec.value.unit} 保存` : ''
})

function onInput(v: number | null | undefined): void {
  if (v == null || Number.isNaN(v)) { model.value = ''; return }
  // CPU 不设 precision（否则 0.5 显示成 0.500），在这里截到 3 位小数 = 1 毫核
  const p = 10 ** spec.value.precision
  const n = Math.max(spec.value.min, Math.round(v * p) / p)
  if (n === shown.value) return   // 没改：不把老写法（如 1G）改写掉
  model.value = toQuantity(n)
}
</script>

<template>
  <div class="app-qty">
    <div class="app-qty__row">
      <el-input-number
        :model-value="shown"
        :disabled="disabled"
        :min="spec.min"
        :step="spec.step"
        :precision="kind === 'cpu' ? undefined : spec.precision"
        :controls="false"
        :placeholder="placeholder"
        class="app-qty__num"
        @update:model-value="onInput"
      />
      <span class="app-qty__unit">{{ spec.unit }}</span>
    </div>
    <div v-if="note" class="app-qty__note">{{ note }}</div>
  </div>
</template>

<style scoped>
.app-qty__row { display: flex; align-items: center; gap: 8px; }
.app-qty__num { width: 180px; }
.app-qty__unit { color: var(--el-text-color-regular); font-size: 13px; }
.app-qty__note { margin-top: 2px; font-size: 12px; line-height: 1.5; color: var(--el-color-warning); }
</style>
