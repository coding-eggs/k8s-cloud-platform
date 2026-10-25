<script setup lang="ts">
import type { Component } from 'vue'
import { Coin, Connection, Cpu, Monitor } from '@element-plus/icons-vue'
import type { AppNodeKind } from '@/types'
import { APP_NODE_MIME } from './appCanvasDrag'

/**
 * 画布左侧的节点面板。
 *
 * <p>四张卡片的颜色与画布上的节点卡片一一对应（服务蓝/后台进程紫/中间件绿/外部服务橙），
 * 让人在点之前就知道加出来的是什么。描述来自后端的节点目录，最长两行，超出用 tooltip。
 *
 * <p>两种加入方式：点一下（自动分层落位），或拖到画布上的指定位置（位置会被钉住）。
 */
defineProps<{
  kinds: Array<{ kind: AppNodeKind; name: string; description: string }>
}>()

const emit = defineEmits<{ add: [AppNodeKind] }>()

const KIND_ICONS: Record<AppNodeKind, Component> = {
  SERVICE: Monitor,
  WORKER: Cpu,
  MIDDLEWARE: Coin,
  EXTERNAL: Connection,
}

function onDragStart(event: DragEvent, kind: AppNodeKind) {
  event.dataTransfer?.setData(APP_NODE_MIME, kind)
  // 有些浏览器只在设了 text/plain 时才肯发起拖拽
  event.dataTransfer?.setData('text/plain', kind)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}
</script>

<template>
  <aside class="palette">
    <div class="palette__head">
      <span class="palette__title">添加节点</span>
      <span class="palette__hint">点击或拖到画布</span>
    </div>

    <button
      v-for="k in kinds"
      :key="k.kind"
      type="button"
      class="node-pick"
      :class="`node-pick--${k.kind.toLowerCase()}`"
      :title="k.description"
      draggable="true"
      @dragstart="onDragStart($event, k.kind)"
      @click="emit('add', k.kind)"
    >
      <span class="node-pick__badge">
        <el-icon :size="16"><component :is="KIND_ICONS[k.kind]" /></el-icon>
      </span>
      <span class="node-pick__body">
        <span class="node-pick__name">{{ k.name }}</span>
        <span class="node-pick__desc">{{ k.description }}</span>
      </span>
      <span class="node-pick__plus">+</span>
    </button>

    <p class="palette__note">
      箭头从使用者指向被使用的资源；虚线框表示资源在集群外。
    </p>
  </aside>
</template>

<style scoped>
.palette {
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
}
.palette__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 2px;
}
.palette__title {
  font-size: 13px;
  font-weight: 600;
}
.palette__hint {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}
.node-pick {
  --pick: #3b7dd8;

  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: grab;
  user-select: none;
  transition: border-color 0.15s, background 0.15s, transform 0.1s;
}
.node-pick:hover {
  border-color: var(--pick);
  background: var(--el-fill-color);
}
.node-pick:active {
  cursor: grabbing;
  transform: scale(0.985);
}
.node-pick--service {
  --pick: #3b7dd8;
}
.node-pick--worker {
  --pick: #7a5af5;
}
.node-pick--middleware {
  --pick: #23925f;
}
.node-pick--external {
  --pick: #d9822b;
}
.node-pick__badge {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 7px;
  color: #fff;
  background: var(--pick);
}
.node-pick__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.node-pick__name {
  font-size: 13px;
  font-weight: 500;
}
.node-pick__desc {
  display: -webkit-box;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.node-pick__plus {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
  font-size: 15px;
  line-height: 20px;
  opacity: 0;
  transition: opacity 0.15s;
}
.node-pick:hover .node-pick__plus {
  color: var(--pick);
  opacity: 1;
}
.palette__note {
  margin: 4px 0 0;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  line-height: 1.6;
}
</style>
