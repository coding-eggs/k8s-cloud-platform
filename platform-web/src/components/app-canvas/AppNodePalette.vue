<script setup lang="ts">
import type { Component } from 'vue'
import { Coin, Connection, Cpu, InfoFilled, Monitor } from '@element-plus/icons-vue'
import type { AppNodeKind } from '@/types'
import { APP_NODE_MIME } from './appCanvasDrag'

interface NodeKindOption {
  kind: AppNodeKind
  name: string
  description: string
  /** 这个节点到底会生成什么（后端节点目录下发） */
  detail?: string
}

/**
 * 画布左侧的节点面板。
 *
 * <p>**拖到画布才是添加**。点一下只是展开说明（生成什么、来源有哪些），
 * 免得看一眼就平白在画布上多出一个节点。
 *
 * <p>四张卡片的颜色与画布上的节点卡片一一对应（服务蓝/后台进程紫/中间件绿/外部服务橙）。
 */
defineProps<{ kinds: NodeKindOption[] }>()

const KIND_ICONS: Record<AppNodeKind, Component> = {
  SERVICE: Monitor,
  WORKER: Cpu,
  MIDDLEWARE: Coin,
  EXTERNAL: Connection,
}

/** 每张卡片的说明气泡；开始拖拽时收起来（未受控，只能显式 hide） */
const tooltips = new Map<AppNodeKind, { hide: () => void }>()

function setTooltip(kind: AppNodeKind, instance: unknown) {
  if (instance) tooltips.set(kind, instance as { hide: () => void })
  else tooltips.delete(kind)
}

function onDragStart(event: DragEvent, kind: AppNodeKind) {
  tooltips.forEach((tooltip) => tooltip.hide())
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
      <span class="palette__hint">拖到画布添加</span>
    </div>

    <el-tooltip
      v-for="k in kinds"
      :key="k.kind"
      :ref="(el: unknown) => setTooltip(k.kind, el)"
      placement="right-start"
      effect="light"
      trigger="click"
      :show-after="0"
      :hide-after="0"
      popper-class="node-pick__popover"
    >
      <template #content>
        <div class="pick-detail">
          <div class="pick-detail__title">{{ k.name }}</div>
          <p class="pick-detail__text">{{ k.detail || k.description }}</p>
          <p class="pick-detail__hint">按住这张卡片拖到画布上即可添加。</p>
        </div>
      </template>

      <button
        type="button"
        class="node-pick"
        :class="`node-pick--${k.kind.toLowerCase()}`"
        draggable="true"
        @dragstart="onDragStart($event, k.kind)"
      >
        <span class="node-pick__badge">
          <el-icon :size="16"><component :is="KIND_ICONS[k.kind]" /></el-icon>
        </span>
        <span class="node-pick__body">
          <span class="node-pick__name">{{ k.name }}</span>
          <span class="node-pick__desc">{{ k.description }}</span>
        </span>
        <el-icon class="node-pick__info" :size="13"><InfoFilled /></el-icon>
      </button>
    </el-tooltip>

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
.node-pick__info {
  flex: none;
  margin-left: auto;
  margin-top: 3px;
  color: var(--el-text-color-placeholder);
  opacity: 0.7;
  transition: color 0.15s, opacity 0.15s;
}
.node-pick:hover .node-pick__info {
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
.pick-detail {
  width: 240px;
}
.pick-detail__title {
  margin-bottom: 4px;
  font-size: 13px;
  font-weight: 600;
}
.pick-detail__text {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.6;
}
.pick-detail__hint {
  margin: 8px 0 0;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}
</style>
