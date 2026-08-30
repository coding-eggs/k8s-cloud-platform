<script setup lang="ts">
import type { PvcTemplate } from '@/types/workload'

const model = defineModel<PvcTemplate[]>()

const ACCESS_MODES = ['ReadWriteOnce', 'ReadOnlyMany', 'ReadWriteMany', 'ReadWriteOncePod'] as const
const VOLUME_MODES = ['Filesystem', 'Block'] as const

function add(): void {
  model.value ??= []
  model.value.push({ name: '', accessModes: ['ReadWriteOnce'], storage: '', storageClassName: null, volumeMode: 'Filesystem' })
}

function remove(i: number): void {
  model.value?.splice(i, 1)
}
</script>

<template>
  <div class="pvc-editor">
    <div v-for="(p, i) in model" :key="i" class="kv-row">
      <el-input v-model="p.name" placeholder="name（与 volumeMount 同名）" style="width: 160px" />
      <el-select v-model="p.accessModes" multiple collapse-tags placeholder="accessModes" style="width: 220px">
        <el-option v-for="m in ACCESS_MODES" :key="m" :label="m" :value="m" />
      </el-select>
      <el-input v-model="p.storage" placeholder="storage（如 1Gi）" style="width: 130px" />
      <el-input v-model="p.storageClassName" placeholder="storageClassName（可选）" style="width: 170px" />
      <el-select v-model="p.volumeMode" placeholder="volumeMode" style="width: 130px">
        <el-option v-for="m in VOLUME_MODES" :key="m" :label="m" :value="m" />
      </el-select>
      <el-button link type="danger" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加 volumeClaimTemplate</el-button>
    <!-- C4：前端提示——后端严格校验每个模板名须被某容器以同名 volumeMount 引用 -->
    <div v-if="model?.length" class="c4-hint">请确保有容器以同名 volumeMount 引用</div>
  </div>
</template>

<style scoped>
.pvc-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.add-row-btn { width: 100% }
.c4-hint { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px }
</style>
