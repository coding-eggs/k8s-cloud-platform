<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  data: { key: string; kind: string; params: Record<string, unknown>; state?: string }
  selected?: boolean
}>()

const state = computed(() => props.data.state ?? 'PENDING')
</script>

<template>
  <div class="app-node" :class="{ 'app-node--selected': selected, 'app-node--failed': state === 'FAILED' }">
    <div class="app-node__title">
      <span class="dot" :class="`dot--${state.toLowerCase()}`" />
      {{ data.key }}
    </div>
    <div class="app-node__kind">{{ data.kind === 'SERVICE' ? '服务' : '后台进程' }}</div>
    <div class="app-node__image">{{ data.params.image || '未填写镜像' }}</div>
  </div>
</template>

<style scoped>
.app-node {
  min-width: 160px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  box-shadow: 0 1px 2px rgb(0 0 0 / 6%);
}
.app-node--selected {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-7);
}
.app-node--failed {
  border-color: var(--el-color-danger);
}
.app-node__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}
.app-node__kind {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.app-node__image {
  margin-top: 6px;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-info);
}
.dot--ready { background: var(--el-color-success); }
.dot--failed { background: var(--el-color-danger); }
.dot--running { background: var(--el-color-primary); }
.dot--missing { background: var(--el-color-warning); }
</style>
