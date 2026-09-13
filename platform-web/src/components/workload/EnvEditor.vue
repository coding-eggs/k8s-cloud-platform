<script setup lang="ts">
import type { EnvVar, ValueFrom } from '@/types/workload'
import { ensureModelList } from './modelList'
import { useResourceOptions } from '@/composables/useResourceOptions'
import FieldHelp from './FieldHelp.vue'

const { options } = useResourceOptions()

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

/** 本地暂存：按变量行身份缓存 valueFrom 各来源已填数据，切走再切回可恢复。
 *  model 始终只保留当前一个来源（A2 不变式）；loadDetail 换成新对象时旧缓存自然失效、不串数据 */
type RefStash = { configMapKeyRef?: ValueFrom['configMapKeyRef']; secretKeyRef?: ValueFrom['secretKeyRef']; fieldRef?: ValueFrom['fieldRef'] }
const stash = new Map<EnvVar, RefStash>()
function st(v: EnvVar): RefStash {
  let m = stash.get(v)
  if (!m) { m = {}; stash.set(v, m) }
  return m
}

function setSource(v: EnvVar, s: SourceKey): void {
  // 切走前保存当前 valueFrom 各来源
  const vf = v.valueFrom
  const m = st(v)
  if (vf) {
    // 只暂存当前真实存在的来源，避免用 null 覆盖掉之前缓存的其它来源数据
    if (vf.configMapKeyRef) m.configMapKeyRef = vf.configMapKeyRef
    if (vf.secretKeyRef) m.secretKeyRef = vf.secretKeyRef
    if (vf.fieldRef) m.fieldRef = vf.fieldRef
  }
  if (s === 'value') { v.value = v.value ?? ''; v.valueFrom = null; return }
  if (s === 'configMapKeyRef') v.valueFrom = { configMapKeyRef: m.configMapKeyRef ?? { name: '', key: '' }, secretKeyRef: null, fieldRef: null }
  else if (s === 'secretKeyRef') v.valueFrom = { configMapKeyRef: null, secretKeyRef: m.secretKeyRef ?? { name: '', key: '' }, fieldRef: null }
  else v.valueFrom = { configMapKeyRef: null, secretKeyRef: null, fieldRef: m.fieldRef ?? { apiVersion: 'v1', fieldPath: '' } }
}

function add(): void {
  ensureModelList(model).push({ name: '', value: '', valueFrom: null })
}

function remove(i: number): void {
  model.value?.splice(i, 1)
}
</script>

<template>
  <div class="kv-editor">
    <div v-for="(v, i) in model" :key="i" class="kv-row">
      <el-input v-model="v.name" placeholder="变量名" style="width: 340px" />
      <el-radio-group :model-value="sourceOf(v)" @update:model-value="(s: SourceKey) => setSource(v, s)">
        <el-radio value="value">字面量</el-radio>
        <el-radio value="configMapKeyRef">配置项</el-radio>
        <el-radio value="secretKeyRef">密钥</el-radio>
        <el-radio value="fieldRef">字段</el-radio>
      </el-radio-group>
      <el-input v-if="sourceOf(v) === 'value'" v-model="v.value" placeholder="值" style="width: 180px" />
      <template v-else-if="v.valueFrom?.configMapKeyRef">
        <el-select v-model="v.valueFrom.configMapKeyRef.name" filterable allow-create default-first-option placeholder="ConfigMap 名称" style="width: 160px">
          <el-option v-for="n in options.configMaps" :key="n" :label="n" :value="n" />
        </el-select>
        <el-input v-model="v.valueFrom.configMapKeyRef.key" placeholder="键" style="width: 120px" />
        <span><FieldHelp tip="默认不可选即：不存在该键值 POD 无法启动。可选即：无论键值是否存在都不影响 POD 启动" /></span><el-switch v-model="v.valueFrom.configMapKeyRef.optional" active-text="可选" > </el-switch>
      </template>
      <template v-else-if="v.valueFrom?.secretKeyRef">
        <el-select v-model="v.valueFrom.secretKeyRef.name" filterable allow-create default-first-option placeholder="Secret 名称" style="width: 160px">
          <el-option v-for="n in options.secrets" :key="n" :label="n" :value="n" />
        </el-select>
        <el-input v-model="v.valueFrom.secretKeyRef.key" placeholder="键" style="width: 120px" />
        <span><FieldHelp tip="默认不可选即：不存在该资源 POD 无法启动。可选即：无论该资源是否存在都不影响 POD 启动" /></span><el-switch v-model="v.valueFrom.secretKeyRef.optional" active-text="可选" />
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
