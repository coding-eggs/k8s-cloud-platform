<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

const model = defineModel<Record<string, string>>()
const props = defineProps<{
  disabled?: boolean
  /**
   * 不在此编辑的保留键（如 istio.io/dataplane-mode / istio.io/use-waypoint）。
   * 两件事都要做，缺一不可：
   * 1. **不显示** —— 它们由专用区块（命名空间/工作负载的「服务网格」）独占管理，两个输入源会互相覆盖；
   * 2. **不抹掉** —— 提交时原样保留在 model 里。只做第 1 点是错的：emitUpdate 用 rows 重建整个 map，
   *    保留键会从 model 里消失，于是"改了个普通标签"顺带把 ambient 配置删了。
   */
  excludeKeys?: readonly string[]
}>()

const excluded = computed(() => new Set(props.excludeKeys ?? []))
const rows = ref<[string, string][]>([])

let selfUpdate = false
watch(model, (obj) => {
  if (selfUpdate) return
  const ex = excluded.value
  rows.value = Object.entries(obj ?? {}).filter(([k]) => !ex.has(k))
}, { immediate: true })

function emitUpdate(): void {
  const ex = excluded.value
  const obj: Record<string, string> = {}
  for (const [k, v] of Object.entries(model.value ?? {})) {
    if (ex.has(k)) obj[k] = v            // 保留键：原样带回（见 props.excludeKeys 说明）
  }
  for (const [k, v] of rows.value) {
    if (k.trim() !== '' && !ex.has(k)) obj[k] = v
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
