<script setup lang="ts">
/**
 * 应用拓扑（设计 §8）：由变量引用生成的只读图。
 *
 * 节点 = 服务 + 依赖；边 = 服务的环境变量 / 配置文件里的 `${key.字段}` 引用（使用方 → 被使用的一方）。
 * 不存储、不可拖拽、不可连线：改关系就去改变量。点服务进服务详情，点依赖打开依赖详情。
 */
import { computed, nextTick, useId } from 'vue'
import { useRouter } from 'vue-router'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type { Edge, Node, NodeMouseEvent } from '@vue-flow/core'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import AppNodeCard from './AppNodeCard.vue'
import { layeredLayout } from './appCanvasLayout'
import { refsOf } from './dependencyUtil'
import type { AppDependencyRow, AppServiceRow, AppServiceState } from '@/types'

const props = defineProps<{
  appId: string
  services: AppServiceRow[]
  dependencies: AppDependencyRow[]
}>()

const emit = defineEmits<{ openDependency: [key: string] }>()

const router = useRouter()
const flowId = useId()
const { fitView } = useVueFlow(flowId)

const SERVICE_STATE: Record<AppServiceState, string> = {
  READY: 'ready', PENDING: 'running', FAILED: 'failed', MISSING: 'failed', UNKNOWN: 'pending', NOT_RELEASED: 'pending',
}
function depState(d: AppDependencyRow): string {
  if (d.status === 'READY') return 'ready'
  if (d.status === 'CREATING') return 'running'
  if (d.status === 'FAILED' || d.status === 'NO_INSTANCE') return 'failed'
  return 'pending'
}

const graph = computed(() => {
  const nodes: Array<{ key: string; kind: 'SERVICE' | 'WORKER' | 'MIDDLEWARE' | 'EXTERNAL'; params: Record<string, unknown>; state: string; removing?: boolean }> = []
  for (const d of props.dependencies) {
    nodes.push({
      key: d.key,
      kind: d.type === 'EXTERNAL' ? 'EXTERNAL' : 'MIDDLEWARE',
      params: d.type === 'EXTERNAL'
        ? { code: d.external?.protocol, host: d.external?.host, port: d.external?.port }
        : { code: d.code, ownership: d.ownership },
      state: depState(d),
    })
  }
  for (const s of props.services) {
    nodes.push({
      key: s.key,
      kind: s.kind,
      params: { image: s.image, port: s.desired?.port, replicas: s.replicas ?? s.desired?.replicas },
      state: SERVICE_STATE[s.state] ?? 'pending',
      // 待删除：下次发布就会下线，淡化显示
      removing: s.pending === 'DELETED',
    })
  }
  const present = new Set(nodes.map((n) => n.key))
  const edges: Array<{ from: string; to: string }> = []
  for (const s of props.services) {
    for (const to of refsOf(s.desired)) {
      // 引用了不存在的 key（发布时会被拦下）不画边：图只画真实存在的关系
      if (to !== s.key && present.has(to)) edges.push({ from: s.key, to })
    }
  }
  return { nodes, edges }
})

const flowNodes = computed<Node[]>(() => {
  const pos = layeredLayout(graph.value.nodes, graph.value.edges)
  return graph.value.nodes.map((n) => ({
    id: n.key,
    type: 'app',
    position: pos[n.key] ?? { x: 0, y: 0 },
    data: n,
    draggable: false,
    connectable: false,
    selectable: false,
  }))
})

const flowEdges = computed<Edge[]>(() => graph.value.edges.map((e) => ({
  id: `${e.from}->${e.to}`,
  source: e.from,
  target: e.to,
  sourceHandle: 'l',
  targetHandle: 'r',
  type: 'smoothstep',
  markerEnd: MarkerType.ArrowClosed,
  style: { strokeWidth: 1.6 },
  selectable: false,
})))

function onNodeClick({ node }: NodeMouseEvent): void {
  const kind = String((node.data as { kind?: string }).kind)
  if (kind === 'SERVICE' || kind === 'WORKER') {
    router.push(`/applications/${props.appId}/services/${encodeURIComponent(node.id)}`)
  } else {
    emit('openDependency', node.id)
  }
}

function onNodesInitialized(): void {
  void nextTick(() => fitView({ padding: 0.2 }))
}
</script>

<template>
  <div class="app-topo">
    <div v-if="!graph.nodes.length" class="app-topo__empty">还没有服务和依赖</div>
    <VueFlow
      v-else
      :id="flowId"
      :nodes="flowNodes"
      :edges="flowEdges"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :elements-selectable="false"
      :edges-updatable="false"
      :delete-key-code="null"
      :min-zoom="0.3"
      :max-zoom="1.4"
      @node-click="onNodeClick"
      @nodes-initialized="onNodesInitialized"
    >
      <template #node-app="nodeProps">
        <AppNodeCard :data="nodeProps.data" />
      </template>
      <Controls position="bottom-left" :show-interactive="false" />
    </VueFlow>
    <div class="app-topo__hint">箭头由使用方指向被使用的一方，来自环境变量和配置文件里的 ${名称.字段} 引用；点击节点查看详情。</div>
  </div>
</template>

<style scoped>
.app-topo {
  position: relative;
  height: 420px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
  background: var(--el-fill-color-lighter);
}
.app-topo__empty {
  display: grid;
  place-items: center;
  height: 100%;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.app-topo__hint {
  position: absolute;
  right: 10px;
  bottom: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  pointer-events: none;
}
.app-topo :deep(.vue-flow__controls) {
  overflow: hidden;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  box-shadow: none;
}
.app-topo :deep(.vue-flow__controls-button) {
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  fill: var(--el-text-color-regular);
}
.app-topo :deep(.vue-flow__controls-button:hover) {
  background: var(--el-fill-color-light);
}
.app-topo :deep(.vue-flow__controls-button svg) {
  fill: var(--el-text-color-regular);
}
</style>
