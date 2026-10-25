<script setup lang="ts">
import { nextTick, ref, useId } from 'vue'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, Edge, EdgeChange, Node, NodeChange } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import AppNodeCard from './AppNodeCard.vue'
import { APP_NODE_MIME } from './appCanvasDrag'
import { NODE_HEIGHT, NODE_WIDTH } from './appCanvasLayout'
import type { Point } from './appCanvasLayout'
import type { AppNodeKind } from '@/types'

/**
 * 画布本身（只负责呈现与交互，不管数据从哪来）。
 *
 * <p>`useVueFlow` 在 `<VueFlow>` 的**父**组件里调用会新建一个自己的 store，
 * 拿到的 fitView 跟真正渲染的那个画布不是一回事 —— 所以这里显式给画布一个 id，
 * 两边用同一个 id 取到同一个实例。
 */
const props = defineProps<{
  nodes: Node[]
  edges: Edge[]
  selectedKey: string
  empty?: boolean
}>()

const emit = defineEmits<{
  connect: [Connection]
  nodeClick: [Node]
  nodesChange: [NodeChange[]]
  edgesChange: [EdgeChange[]]
  dropNode: [AppNodeKind, Point]
}>()

const flowId = useId()
const { fitView, screenToFlowCoordinate } = useVueFlow(flowId)

/** 从左侧面板拖过来时高亮画布；用计数抵消 dragleave 在子元素上的抖动 */
const dropActive = ref(false)
let dragDepth = 0

function carriesNode(e: DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes(APP_NODE_MIME)
}

function onDragEnter(e: DragEvent) {
  if (!carriesNode(e)) return
  dragDepth++
  dropActive.value = true
}

function onDragOver(e: DragEvent) {
  if (!carriesNode(e)) return
  // 不 preventDefault 浏览器不会派发 drop
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDragLeave() {
  dragDepth = Math.max(0, dragDepth - 1)
  if (!dragDepth) dropActive.value = false
}

function onDrop(e: DragEvent) {
  dragDepth = 0
  dropActive.value = false
  const kind = e.dataTransfer?.getData(APP_NODE_MIME) as AppNodeKind | undefined
  if (!kind) return
  e.preventDefault()
  // 光标落在卡片中心：先换算成画布坐标，再减半个卡片
  const point = screenToFlowCoordinate({ x: e.clientX, y: e.clientY })
  emit('dropNode', kind, { x: point.x - NODE_WIDTH / 2, y: point.y - NODE_HEIGHT / 2 })
}

/** 只在节点第一次量好尺寸时取景：此时才拿得到真实宽高，之后加节点不抢用户调好的视角 */
let fitted = false
function onNodesInitialized() {
  if (fitted || !props.nodes.length) return
  fitted = true
  nextTick(() => fitView({ padding: 0.2 }))
}

/** 给外面「自动整理」用：重新排序后把整张图请回视野 */
function fit() {
  fitView({ padding: 0.2, duration: 300 })
}

defineExpose({ fit })

const defaultEdgeOptions = {
  type: 'smoothstep',
  markerEnd: MarkerType.ArrowClosed,
  style: { strokeWidth: 1.6 },
}
</script>

<template>
  <div
    class="app-canvas"
    :class="{ 'app-canvas--drop': dropActive }"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <VueFlow
      :id="flowId"
      :nodes="nodes"
      :edges="edges"
      :default-edge-options="defaultEdgeOptions"
      :delete-key-code="['Delete', 'Backspace']"
      :snap-to-grid="true"
      :snap-grid="[16, 16]"
      :min-zoom="0.3"
      :max-zoom="1.4"
      @connect="emit('connect', $event)"
      @node-click="emit('nodeClick', $event.node)"
      @nodes-change="emit('nodesChange', $event)"
      @edges-change="emit('edgesChange', $event)"
      @nodes-initialized="onNodesInitialized"
    >
      <template #node-app="nodeProps">
        <AppNodeCard :data="nodeProps.data" :selected="nodeProps.id === selectedKey" />
      </template>
      <Background :gap="20" :size="1.2" />
      <Controls position="bottom-left" />
    </VueFlow>

    <div v-if="empty" class="app-canvas__empty">
      <div class="app-canvas__empty-title">从左侧添加节点开始画</div>
      <div class="app-canvas__empty-hint">
        从服务卡片左边的圆点拖到它依赖的中间件：箭头指向被使用的一方。
      </div>
    </div>

    <div v-if="dropActive" class="app-canvas__drop">
      <span>放到这里</span>
    </div>
  </div>
</template>

<style scoped>
.app-canvas {
  position: relative;
  height: 100%;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
  background: var(--el-fill-color-lighter);
}
.app-canvas__empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.app-canvas__empty-title {
  font-size: 14px;
  color: var(--el-text-color-regular);
}
.app-canvas__empty-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.app-canvas--drop {
  border-color: var(--el-color-primary);
}
.app-canvas__drop {
  position: absolute;
  inset: 8px;
  display: grid;
  place-items: center;
  /* 不要填底色：实心色块会把画布上的节点和连线整片盖掉 */
  border: 2px dashed var(--el-color-primary-light-5);
  border-radius: 8px;
  color: var(--el-color-primary);
  font-size: 12px;
  pointer-events: none;
}
/* 缩略控件默认是白底方块，在深色主题里很跳，跟着主题走 */
.app-canvas :deep(.vue-flow__controls) {
  overflow: hidden;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  box-shadow: none;
}
.app-canvas :deep(.vue-flow__controls-button) {
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  fill: var(--el-text-color-regular);
}
.app-canvas :deep(.vue-flow__controls-button:hover) {
  background: var(--el-fill-color-light);
}
.app-canvas :deep(.vue-flow__controls-button svg) {
  fill: var(--el-text-color-regular);
}
</style>
