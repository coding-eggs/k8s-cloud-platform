<script setup lang="ts">
import type { EnvFrom } from '@/types/workload'

type Source = 'configMapRef' | 'secretRef'

const model = defineModel<EnvFrom[]>()

function sourceOf(e: EnvFrom): Source {
  return e.configMapRef ? 'configMapRef' : 'secretRef'
}

function setSource(e: EnvFrom, s: Source): void {
  if (s === 'configMapRef') {
    e.configMapRef = e.configMapRef ?? { name: '' }
    e.secretRef = null
  } else {
    e.secretRef = e.secretRef ?? { name: '' }
    e.configMapRef = null
  }
}

function add(): void {
  model.value ??= []
  model.value.push({ prefix: '', configMapRef: { name: '' }, secretRef: null })
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
        <el-input v-model="e.configMapRef.name" placeholder="ConfigMap 名称" style="width: 160px" />
        <el-switch v-model="e.configMapRef.optional" active-text="可选" />
      </template>
      <template v-else-if="e.secretRef">
        <el-input v-model="e.secretRef.name" placeholder="Secret 名称" style="width: 160px" />
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
