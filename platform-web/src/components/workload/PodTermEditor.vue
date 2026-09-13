<script setup lang="ts">
import type { PodAffinityTerm } from '@/types/workload'
import LabelEditor from './LabelEditor.vue'
import FieldHelp from './FieldHelp.vue'
import { useResourceOptions } from '@/composables/useResourceOptions'

const { options } = useResourceOptions()

const model = defineModel<PodAffinityTerm>()

/** 各字段作用说明（FieldHelp） */
const T_NAMESPACES = '限定参与匹配的 Pod 所在命名空间；留空 = 仅当前命名空间。'
const T_TOPOLOGY = '拓扑域键，决定「同一组」的粒度：kubernetes.io/hostname=按节点、topology.kubernetes.io/zone=按可用区。'
const T_MATCHLABELS = '筛选目标 Pod 的标签选择器：只有带这些标签的 Pod 才会被本条规则考虑。'
</script>

<template>
  <div v-if="model" class="pt-editor">
    <div class="pt-row">
      <span class="pt-fl">namespaces<FieldHelp :tip="T_NAMESPACES" /></span>
      <el-select
        v-model="model.namespaces"
        multiple filterable allow-create default-first-option :reserve-keyword="false"
        placeholder="留空 = 当前命名空间" style="width: 240px"
      >
        <el-option v-for="n in options.namespaces" :key="n" :label="n" :value="n" />
      </el-select>
    </div>
    <div class="pt-row">
      <span class="pt-fl">topologyKey<FieldHelp :tip="T_TOPOLOGY" /></span>
      <el-input v-model="model.topologyKey" placeholder="如 kubernetes.io/hostname" style="width: 260px" />
    </div>
    <div class="pt-row">
      <span class="pt-fl">matchLabels<FieldHelp :tip="T_MATCHLABELS" /></span>
      <LabelEditor v-if="model.matchLabels" v-model="model.matchLabels" />
      <el-button v-else link type="primary" @click="model.matchLabels = {}">+ 添加 matchLabels</el-button>
    </div>
  </div>
</template>

<style scoped>
.pt-editor { width: 100% }
.pt-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 6px }
.pt-fl { font-size: 12px; color: var(--el-text-color-secondary); white-space: nowrap }
</style>
