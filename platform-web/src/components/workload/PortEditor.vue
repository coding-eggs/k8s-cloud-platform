<script setup lang="ts">
import { watch } from 'vue'
import type { ContainerPort } from '@/types/workload'
import { ensureModelList } from './modelList'
const model = defineModel<ContainerPort[]>()
const PROTOCOLS = ['TCP', 'UDP', 'SCTP']

/** 端口必填：默认至少一行，不用点添加就有；编辑已有负载且原无端口时也补一行 */
function blankPort(): ContainerPort { return { containerPort: null, protocol: 'TCP', name: '' } }
watch(model, (v) => { if (!v || v.length === 0) model.value = [blankPort()] }, { immediate: true })

function add(): void { ensureModelList(model).push(blankPort()) }
function remove(i: number): void { model.value?.splice(i, 1) }
</script>

<template>
  <div class="kv-editor">
    <div v-for="(p, i) in model" :key="i" class="kv-row">
      <el-input-number v-model="p.containerPort" :min="1" :max="65535" controls-position="right" placeholder="端口" style="width: 140px" />
      <el-select v-model="p.protocol" style="width: 100px">
        <el-option v-for="x in PROTOCOLS" :key="x" :label="x" :value="x" />
      </el-select>
      <el-input v-model="p.name" placeholder="名称（可选）" style="width: 160px" />
      <el-button link type="danger" :disabled="!model || model.length <= 1" @click="remove(i)">删除</el-button>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加端口</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center }
.add-row-btn { width: auto }
</style>
