<script setup lang="ts">
import type { DownwardAPIVolumeFile } from '@/types/workload'
import { ensureModelList } from './modelList'

/** downwardAPI 文件列表编辑器（fieldRef / resourceFieldRef 二选一）；被 downwardAPI 卷与 projected 的 downwardAPI 来源共用 */
const model = defineModel<DownwardAPIVolumeFile[] | null>()

const RESOURCES = ['limits.cpu', 'limits.memory', 'requests.cpu', 'requests.memory']

function add(): void {
  ensureModelList(model).push({ path: '', mode: null, fieldRef: { apiVersion: null, fieldPath: '' }, resourceFieldRef: null })
}
function remove(i: number): void {
  model.value?.splice(i, 1)
}

/** 本地暂存：按文件行身份缓存两个来源已填数据，切走再切回可恢复。
 *  model 始终只保留当前一个来源（A2 不变式）；loadDetail 换成新对象时旧缓存自然失效、不串数据 */
type Stash = Partial<Pick<DownwardAPIVolumeFile, 'fieldRef' | 'resourceFieldRef'>>
const stash = new Map<DownwardAPIVolumeFile, Stash>()
function st(f: DownwardAPIVolumeFile): Stash {
  let m = stash.get(f)
  if (!m) { m = {}; stash.set(f, m) }
  return m
}

/** 切换某文件来源：先暂存当前来源，再初始化目标（从缓存恢复或默认），清空其它（互斥）；保留 path/mode */
function setSource(f: DownwardAPIVolumeFile, src: 'fieldRef' | 'resourceFieldRef'): void {
  const m = st(f)
  const cur = f.fieldRef ? 'fieldRef' : (f.resourceFieldRef ? 'resourceFieldRef' : '')
  if (cur !== src) {
    if (f.fieldRef) m.fieldRef = f.fieldRef
    if (f.resourceFieldRef) m.resourceFieldRef = f.resourceFieldRef
  }
  f.fieldRef = null; f.resourceFieldRef = null
  if (src === 'fieldRef') f.fieldRef = m.fieldRef ?? { apiVersion: null, fieldPath: '' }
  else f.resourceFieldRef = m.resourceFieldRef ?? { containerName: null, divisor: null, resource: 'limits.cpu' }
}
</script>

<template>
  <div class="dapi-list">
    <div v-for="(f, i) in model" :key="i" class="dapi-file">
      <div class="kv-row">
        <el-input v-model="f.path" placeholder="文件名（如 name）" style="width: 150px" />
        <el-input-number v-model="f.mode" :min="0" controls-position="right" placeholder="mode（可选）" style="width: 130px" />
        <el-radio-group :model-value="f.fieldRef ? 'fieldRef' : (f.resourceFieldRef ? 'resourceFieldRef' : '')" @update:model-value="(s: string) => setSource(f, s as 'fieldRef' | 'resourceFieldRef')">
          <el-radio value="fieldRef">fieldRef</el-radio>
          <el-radio value="resourceFieldRef">resourceFieldRef</el-radio>
        </el-radio-group>
        <el-button link type="danger" @click="remove(i)">删除</el-button>
      </div>
      <div v-if="f.fieldRef" class="kv-row sub">
        <el-input v-model="f.fieldRef.apiVersion" placeholder="apiVersion（默认 v1，可选）" style="width: 150px" />
        <el-input v-model="f.fieldRef.fieldPath" placeholder="fieldPath（如 metadata.name、status.podIP）" style="width: 220px" />
      </div>
      <div v-else-if="f.resourceFieldRef" class="kv-row sub">
        <el-input v-model="f.resourceFieldRef.containerName" placeholder="containerName（可选）" style="width: 150px" />
        <el-input v-model="f.resourceFieldRef.divisor" placeholder="divisor（如 1m，可选）" style="width: 130px" />
        <el-select v-model="f.resourceFieldRef.resource" style="width: 190px">
          <el-option v-for="r in RESOURCES" :key="r" :label="r" :value="r" />
        </el-select>
      </div>
    </div>
    <el-button link type="primary" @click="add">+ 添加文件</el-button>
  </div>
</template>

<style scoped>
.dapi-list { width: 100% }
.dapi-file { margin-bottom: 8px; padding: 6px; border: 1px dashed var(--el-border-color); border-radius: 4px }
.kv-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 6px }
.kv-row.sub {  }
</style>
