<script setup lang="ts">
/**
 * 从依赖导入：选一个依赖，勾选要用的字段，给每个字段起变量名，一次追加成多行引用。
 *
 * 变量名默认 `<依赖名大写>_<字段大写>`（order-db → ORDER_DB_HOST），改前缀会重新生成没手动改过的名字。
 * 值永远是引用 `${依赖.字段}`：用户名 / 密码是 Secret 引用，不会变成明文。
 * 与已有变量重名的行不能导入（不静默覆盖）。
 */
import { computed, ref, watch } from 'vue'
import type { AppDependencyRow, AppEnvRow } from '@/types'
import { DEP_FIELD_LABEL, ENV_NAME_PATTERN, depFields, envPrefix, refToken } from './dependencyUtil'

const props = defineProps<{
  modelValue: boolean
  dependencies: AppDependencyRow[]
  /** 编辑器里已有的变量名 */
  existingNames: string[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'import', rows: AppEnvRow[]): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

interface Line { field: string; checked: boolean; name: string; touched: boolean }

const depKey = ref('')
const prefix = ref('')
const lines = ref<Line[]>([])

const dep = computed(() => props.dependencies.find((d) => d.key === depKey.value))
const DEFAULT_CHECKED = ['host', 'port', 'username', 'password']

function nameFor(field: string): string {
  return prefix.value ? `${prefix.value}_${field.toUpperCase()}` : field.toUpperCase()
}

function rebuild(): void {
  const d = dep.value
  lines.value = d
    ? depFields(d).map((f) => ({ field: f, checked: DEFAULT_CHECKED.includes(f), name: nameFor(f), touched: false }))
    : []
}

watch(() => props.modelValue, (open) => {
  if (!open) return
  depKey.value = props.dependencies[0]?.key ?? ''
  prefix.value = depKey.value ? envPrefix(depKey.value) : ''
  rebuild()
})
watch(depKey, (k) => {
  prefix.value = k ? envPrefix(k) : ''
  rebuild()
})
watch(prefix, () => {
  for (const l of lines.value) if (!l.touched) l.name = nameFor(l.field)
})

function problem(l: Line, i: number): string {
  if (!l.checked) return ''
  const n = l.name
  if (!n) return '变量名不能为空'
  if (!ENV_NAME_PATTERN.test(n)) return '只能含字母、数字、下划线，且不能以数字开头'
  if (n.startsWith('_REF_')) return '_REF_ 是平台保留前缀'
  if (props.existingNames.includes(n)) return '已有同名变量'
  if (lines.value.some((o, idx) => idx !== i && o.checked && o.name === n)) return '变量名重复'
  return ''
}

const chosen = computed(() => lines.value.filter((l) => l.checked))
const canImport = computed(() => !!dep.value && chosen.value.length > 0 && lines.value.every((l, i) => !problem(l, i)))

function submit(): void {
  if (!canImport.value || !dep.value) return
  const key = dep.value.key
  emit('import', chosen.value.map((l) => ({ name: l.name, kind: 'REF' as const, value: refToken(key, l.field) })))
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" title="从依赖导入变量" width="640px" class="app-env-import" destroy-on-close>
    <el-form label-width="80px" @submit.prevent>
      <el-form-item label="依赖">
        <el-select v-model="depKey" placeholder="选择依赖" style="width: 100%">
          <el-option v-for="d in dependencies" :key="d.key" :label="d.key" :value="d.key" />
        </el-select>
      </el-form-item>
      <el-form-item label="前缀">
        <el-input v-model="prefix" placeholder="如 DB（留空则不加前缀）" />
        <div class="app-env-import__tip">改前缀会重新生成还没手动改过的变量名；每个名字都可以单独改。</div>
      </el-form-item>
    </el-form>

    <el-table :data="lines" size="small" empty-text="请选择依赖">
      <el-table-column width="46">
        <template #default="{ row }"><el-checkbox v-model="row.checked" /></template>
      </el-table-column>
      <el-table-column label="变量名" min-width="220">
        <template #default="{ row, $index }">
          <el-input v-model="row.name" size="small" :disabled="!row.checked" spellcheck="false" @input="row.touched = true" />
          <div v-if="problem(row, $index)" class="app-env-import__err">{{ problem(row, $index) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="值（引用）" min-width="200">
        <template #default="{ row }">
          <code>{{ depKey ? refToken(depKey, row.field) : '' }}</code>
          <div class="app-env-import__tip">{{ DEP_FIELD_LABEL[row.field] }}</div>
        </template>
      </el-table-column>
    </el-table>
    <div class="app-env-import__tip app-env-import__foot">用户名和密码以 Secret 引用写入，变量值里不会出现明文口令。导入后可以在列表里继续改。</div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!canImport" @click="submit">添加 {{ chosen.length }} 个变量</el-button>
    </template>
  </el-dialog>
</template>

<style>
/* el-dialog 内部节点没有 data-v-*，scoped 打不到；唯一前缀 app-env-import */
.app-env-import .app-env-import__err { color: var(--el-color-danger); font-size: 12px; }
.app-env-import .app-env-import__tip { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.app-env-import .app-env-import__foot { margin-top: 8px; }
</style>
