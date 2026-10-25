<script setup lang="ts">
import { computed } from 'vue'
import type { ApplicationOperation } from '@/types'

const props = defineProps<{ modelValue: boolean; operation: ApplicationOperation | null }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; retry: [] }>()

const steps = [
  { key: 'PRECHECK', label: '预检' },
  { key: 'RENDER', label: '渲染' },
  { key: 'CONFLICT', label: '冲突检查' },
  { key: 'APPLY', label: '写入集群' },
  { key: 'PRUNE', label: '清理旧对象' },
  { key: 'HEALTH', label: '等待就绪' },
]

const activeIndex = computed(() => {
  const step = props.operation?.currentStep
  const i = steps.findIndex((s) => s.key === step)
  return i < 0 ? steps.length : i
})

const nodeStates = computed<Array<[string, string]>>(() => {
  const raw = props.operation?.nodeStatesJson
  if (!raw) return []
  try {
    return Object.entries(JSON.parse(raw) as Record<string, string>)
  } catch {
    return []
  }
})

const finished = computed(() => props.operation?.status === 'SUCCESS' || props.operation?.status === 'FAILED')
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="部署进度"
    size="420px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-steps direction="vertical" :active="activeIndex" finish-status="success" class="steps">
      <el-step v-for="s in steps" :key="s.key" :title="s.label" />
    </el-steps>

    <el-alert
      v-if="operation?.status === 'FAILED'"
      type="error"
      :closable="false"
      show-icon
      class="alert"
      :title="operation.message || '部署失败'"
    />

    <el-table v-if="nodeStates.length" :data="nodeStates.map(([k, v]) => ({ key: k, state: v }))" size="small">
      <el-table-column prop="key" label="节点" />
      <el-table-column prop="state" label="状态" width="110" />
    </el-table>

    <template #footer>
      <el-button v-if="operation?.status === 'FAILED'" type="primary" @click="emit('retry')">重试</el-button>
      <el-button :disabled="!finished" @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.steps {
  margin-bottom: 16px;
}
.alert {
  margin-bottom: 12px;
}
</style>
