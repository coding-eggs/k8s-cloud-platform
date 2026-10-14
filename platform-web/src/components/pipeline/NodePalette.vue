<script setup lang="ts">
/**
 * 左侧节点面板。**列出哪些节点由后端说了算**（`/pipeline/node-types`）——
 * 这里不写死任何 type，加一个后端节点它会自己出现在这里。
 *
 * 拖进画布用原生 HTML5 drag：`dragstart` 把 type 写进 dataTransfer，
 * 画布那边 `drop` 时再换算坐标。比引一个拖拽库轻。
 */
import { onMounted } from 'vue'
import { usePipelineNodeTypes } from '@/composables/usePipelineNodeTypes'

const { types, load, labelOf } = usePipelineNodeTypes()

onMounted(load)

function onDragStart(e: DragEvent, type: string) {
  e.dataTransfer?.setData('application/x-pipeline-node', type)
  // 有些浏览器只在设置了 text/plain 时才允许拖
  e.dataTransfer?.setData('text/plain', type)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}
</script>

<template>
  <div class="palette">
    <div class="palette-title">节点</div>
    <div
      v-for="t in types"
      :key="t.type"
      class="palette-item"
      draggable="true"
      @dragstart="onDragStart($event, t.type)"
    >
      {{ labelOf(t.type) }}
    </div>
    <div v-if="!types.length" class="palette-empty">拿不到节点清单（看后端日志）</div>
  </div>
</template>

<style scoped>
.palette {
  width: 150px;
  flex: 0 0 150px;
  padding: 8px;
  border-right: 1px solid var(--border, #ebeef5);
  overflow: auto;
}
.palette-title {
  font-size: 12px;
  color: var(--text-dim, #909399);
  margin-bottom: 8px;
}
.palette-item {
  padding: 8px 10px;
  margin-bottom: 6px;
  border: 1px dashed var(--border, #dcdfe6);
  border-radius: 4px;
  font-size: 13px;
  cursor: grab;
  user-select: none;
}
.palette-item:active {
  cursor: grabbing;
}
.palette-empty {
  font-size: 12px;
  color: var(--text-dim, #909399);
}
</style>
