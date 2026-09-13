<script setup lang="ts">
import type { EnvFrom } from '@/types/workload'
import { ensureModelList } from './modelList'
import { useResourceOptions } from '@/composables/useResourceOptions'

const { options } = useResourceOptions()

type Source = 'configMapRef' | 'secretRef'

const model = defineModel<EnvFrom[]>()

function sourceOf(e: EnvFrom): Source {
  return e.configMapRef ? 'configMapRef' : 'secretRef'
}

/** 本地暂存：按来源行身份缓存两种 ref 已填数据，切走再切回可恢复。
 *  model 始终只保留当前一个来源（A2 不变式）；loadDetail 换成新对象时旧缓存自然失效、不串数据 */
type Stash = { configMapRef?: EnvFrom['configMapRef']; secretRef?: EnvFrom['secretRef'] }
const stash = new Map<EnvFrom, Stash>()
function st(e: EnvFrom): Stash {
  let m = stash.get(e)
  if (!m) { m = {}; stash.set(e, m) }
  return m
}

function setSource(e: EnvFrom, s: Source): void {
  const m = st(e)
  // 只暂存当前真实存在的来源，避免用 null 覆盖掉之前缓存的另一种来源数据
  if (e.configMapRef) m.configMapRef = e.configMapRef
  if (e.secretRef) m.secretRef = e.secretRef
  if (s === 'configMapRef') {
    e.configMapRef = m.configMapRef ?? { name: '' }
    e.secretRef = null
  } else {
    e.secretRef = m.secretRef ?? { name: '' }
    e.configMapRef = null
  }
}

function add(): void {
  ensureModelList(model).push({ prefix: '', configMapRef: { name: '' }, secretRef: null })
}

function remove(i: number): void {
  model.value?.splice(i, 1)
}
</script>

<template>
  <div class="kv-editor">
    <div v-for="(e, i) in model" :key="i" class="kv-row">
      <el-input v-model="e.prefix" placeholder="前缀（可选）" style="width: 120px" />
      <el-radio-group :model-value="sourceOf(e)" @update:model-value="(s: Source) => setSource(e, s)">
        <el-radio value="configMapRef">配置项</el-radio>
        <el-radio value="secretRef">密钥</el-radio>
      </el-radio-group>
      <template v-if="e.configMapRef">
        <el-select v-model="e.configMapRef.name" filterable allow-create default-first-option placeholder="ConfigMap 名称" style="width: 180px">
          <el-option v-for="n in options.configMaps" :key="n" :label="n" :value="n" />
        </el-select>
        <el-switch v-model="e.configMapRef.optional" active-text="可选" />
      </template>
      <template v-else-if="e.secretRef">
        <el-select v-model="e.secretRef.name" filterable allow-create default-first-option placeholder="Secret 名称" style="width: 180px">
          <el-option v-for="n in options.secrets" :key="n" :label="n" :value="n" />
        </el-select>
        <el-switch v-model="e.secretRef.optional" active-text="可选" />
      </template>
      <el-button link type="danger" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加来源</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center }
.add-row-btn { width: 100% }
</style>
