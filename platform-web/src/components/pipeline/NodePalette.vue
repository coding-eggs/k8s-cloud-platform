<script setup lang="ts">
/**
 * 左侧节点面板。**列出哪些节点、各自长什么样，全由后端说了算**（`/pipeline/node-types`）——
 * 这里不写死任何 type，加一个后端节点它会自己带着图标与说明出现在这里。
 *
 * 拖进画布用原生 HTML5 drag：`dragstart` 把 type 写进 dataTransfer，
 * 画布那边 `drop` 时再换算坐标。比引一个拖拽库轻。
 */
import { onMounted } from 'vue'
import { usePipelineNodeTypes } from '@/composables/usePipelineNodeTypes'

const { types, load, accentOf, iconOf } = usePipelineNodeTypes()

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
      class="pcard"
      :class="`accent-${accentOf(t.type)}`"
      draggable="true"
      :title="t.description"
      @dragstart="onDragStart($event, t.type)"
    >
      <div class="pcard-icon">
        <el-icon><component :is="iconOf(t.type)" /></el-icon>
      </div>
      <div class="pcard-body">
        <div class="pcard-label">{{ t.label }}</div>
        <div v-if="t.description" class="pcard-desc">{{ t.description }}</div>
      </div>
    </div>

    <div v-if="!types.length" class="palette-empty">拿不到节点清单（看后端日志）</div>
    <div v-else class="palette-hint">拖到右边画布上</div>
  </div>
</template>

<style scoped>
.palette {
  width: 200px;
  flex: 0 0 200px;
  padding: 10px;
  border-right: 1px solid var(--border);
  overflow: auto;
  background: var(--bg-elevated);
}
.palette-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
  margin-bottom: 8px;
}
.pcard {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 8px 9px;
  margin-bottom: 7px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel);
  cursor: grab;
  user-select: none;
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.15s;
}
.pcard:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-panel);
  transform: translateY(-1px);
}
.pcard:active {
  cursor: grabbing;
}
.pcard-icon {
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  font-size: 13px;
  color: var(--accent);
  background: var(--accent-soft);
}
/* 配色只用语义名 → 浅色与暗色各自取自己的色（写死 hex 会在另一半主题里翻车） */
.accent-success .pcard-icon {
  color: var(--success);
  background: color-mix(in srgb, var(--success) 12%, transparent);
}
.accent-warning .pcard-icon {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 12%, transparent);
}
.accent-danger .pcard-icon {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 12%, transparent);
}
.pcard-body {
  min-width: 0;
}
.pcard-label {
  font-size: 13px;
  color: var(--text-1);
}
.pcard-desc {
  font-size: 11px;
  color: var(--text-3);
  line-height: 1.4;
  margin-top: 2px;
}
.palette-empty,
.palette-hint {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 10px;
}
</style>
