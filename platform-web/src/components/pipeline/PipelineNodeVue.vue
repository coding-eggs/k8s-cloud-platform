<script setup lang="ts">
/**
 * 画布上的一个节点。
 *
 * ⚠️ Vue Flow 的节点类型统一是 `pipeline`（**不是**后端那个 `git`/`shell`/`image`）——
 * 后端的 type 放在 `data.nodeType` 里。这样只需要一个自定义节点组件，
 * 而不是每加一个后端节点就往前端加一个组件（那就把「加节点不用改前端」打穿了）。
 */
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{
  id: string
  data: {
    nodeType: string
    label: string
    /** 运行视图里用：这个节点的状态（画布编辑器里没有） */
    status?: string
  }
}>()

const STATUS_CLASS: Record<string, string> = {
  SUCCEEDED: 'ok',
  FAILED: 'bad',
  CANCELLED: 'warn',
  TIMEOUT: 'warn',
  RUNNING: 'running',
}

const statusClass = computed(() => (props.data.status ? STATUS_CLASS[props.data.status] ?? '' : ''))
</script>

<template>
  <div class="pipeline-node" :class="statusClass">
    <Handle type="target" :position="Position.Left" />
    <div class="pipeline-node-id">{{ data.label }}</div>
    <div class="pipeline-node-type">{{ data.nodeType }}</div>
    <div v-if="data.status" class="pipeline-node-status">{{ data.status }}</div>
    <Handle type="source" :position="Position.Right" />
  </div>
</template>

<style scoped>
.pipeline-node {
  min-width: 130px;
  padding: 8px 10px;
  border: 1px solid var(--border, #dcdfe6);
  border-radius: 6px;
  background: var(--bg-card, #fff);
  font-size: 12px;
  text-align: center;
}
.pipeline-node-id {
  font-weight: 600;
  word-break: break-all;
}
.pipeline-node-type {
  color: var(--text-dim, #909399);
  margin-top: 2px;
}
.pipeline-node-status {
  margin-top: 4px;
  font-family: monospace;
}
/* 运行视图的着色：与运行历史里的状态口径一致 */
.pipeline-node.ok {
  border-color: var(--success, #67c23a);
}
.pipeline-node.bad {
  border-color: var(--danger, #f56c6c);
}
.pipeline-node.warn {
  border-color: var(--warning, #e6a23c);
}
.pipeline-node.running {
  border-color: var(--info, #409eff);
}
</style>
