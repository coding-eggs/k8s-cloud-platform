<script setup lang="ts">
import type { EnvVar } from '@/types/workload'

type SourceKey = 'value' | 'configMapKeyRef' | 'secretKeyRef' | 'fieldRef'

const model = defineModel<EnvVar[]>()

const FIELD_PATHS = ['metadata.name', 'metadata.namespace', 'metadata.uid', 'metadata.labels', 'status.podIP']

function sourceOf(v: EnvVar): SourceKey {
  const vf = v.valueFrom
  if (vf?.configMapKeyRef) return 'configMapKeyRef'
  if (vf?.secretKeyRef) return 'secretKeyRef'
  if (vf?.fieldRef) return 'fieldRef'
  return 'value'
}

function setSource(v: EnvVar, s: SourceKey): void {
  if (s === 'value') {
    v.value = v.value ?? ''
    v.valueFrom = null
    return
  }
  const vf = v.valueFrom ?? (v.valueFrom = {})
  if (s === 'configMapKeyRef') {
    vf.configMapKeyRef = vf.configMapKeyRef ?? { name: '', key: '' }
    vf.secretKeyRef = null
    vf.fieldRef = null
  } else if (s === 'secretKeyRef') {
    vf.secretKeyRef = vf.secretKeyRef ?? { name: '', key: '' }
    vf.configMapKeyRef = null
    vf.fieldRef = null
  } else {
    vf.fieldRef = vf.fieldRef ?? { apiVersion: 'v1', fieldPath: '' }
    vf.configMapKeyRef = null
    vf.secretKeyRef = null
  }
}

function add(): void {
  model.value ??= []
  model.value.push({ name: '', value: '', valueFrom: null })
}

function remove(i: number): void {
  model.value?.splice(i, 1)
}
</script>

<template>
  <div class="kv-editor">
    <div v-for="(v, i) in model" :key="i" class="kv-row">
      <el-input v-model="v.name" placeholder="变量名" style="width: 140px" />
      <el-radio-group :model-value="sourceOf(v)" @update:model-value="(s: SourceKey) => setSource(v, s)">
        <el-radio value="value">字面量</el-radio>
        <el-radio value="configMapKeyRef">配置项</el-radio>
        <el-radio value="secretKeyRef">密钥</el-radio>
        <el-radio value="fieldRef">字段</el-radio>
      </el-radio-group>
      <el-input v-if="sourceOf(v) === 'value'" v-model="v.value" placeholder="值" style="width: 180px" />
      <template v-else-if="v.valueFrom?.configMapKeyRef">
        <el-input v-model="v.valueFrom.configMapKeyRef.name" placeholder="ConfigMap 名称" style="width: 140px" />
        <el-input v-model="v.valueFrom.configMapKeyRef.key" placeholder="键" style="width: 120px" />
        <el-switch v-model="v.valueFrom.configMapKeyRef.optional" active-text="可选" />
      </template>
      <template v-else-if="v.valueFrom?.secretKeyRef">
        <el-input v-model="v.valueFrom.secretKeyRef.name" placeholder="Secret 名称" style="width: 140px" />
        <el-input v-model="v.valueFrom.secretKeyRef.key" placeholder="键" style="width: 120px" />
        <el-switch v-model="v.valueFrom.secretKeyRef.optional" active-text="可选" />
      </template>
      <template v-else-if="v.valueFrom?.fieldRef">
        <el-select v-model="v.valueFrom.fieldRef.fieldPath" filterable allow-create default-first-option placeholder="字段路径" style="width: 180px">
          <el-option v-for="x in FIELD_PATHS" :key="x" :label="x" :value="x" />
        </el-select>
        <el-input v-model="v.valueFrom.fieldRef.apiVersion" placeholder="apiVersion（默认 v1）" style="width: 130px" />
      </template>
      <el-button link type="danger" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加变量</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.add-row-btn { width: 100% }
</style>
