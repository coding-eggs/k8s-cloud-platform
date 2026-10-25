<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import type { Connection, Edge, Node, NodeChange } from '@vue-flow/core'
import { applicationApi } from '@/api'
import type {
  ApplicationOperation,
  ApplicationOptions,
  ApplicationPreview,
  ApplicationView,
  AppNode,
  AppNodeKind,
  AppSpec,
} from '@/types'
import AppNodeCard from '@/components/app-canvas/AppNodeCard.vue'
import AppNodeInspector from '@/components/app-canvas/AppNodeInspector.vue'
import AppDeployDrawer from '@/components/app-canvas/AppDeployDrawer.vue'
import YamlBlock from '@/components/YamlBlock.vue'

/** 画布上的节点（不直接使用 Vue Flow 的 Node 泛型，只在绑定处转型） */
interface FlowNodeView {
  id: string
  type: string
  position: { x: number; y: number }
  data: { key: string; kind: AppNodeKind; params: Record<string, unknown>; state?: string }
}
interface FlowEdgeView {
  id: string
  source: string
  target: string
}

const route = useRoute()
const router = useRouter()
const { fitView } = useVueFlow()

const app = ref<ApplicationView>()
const options = ref<ApplicationOptions>({ nodeKinds: [], namespaces: [] })
const nodes = ref<FlowNodeView[]>([])
const edges = ref<FlowEdgeView[]>([])
const selectedKey = ref('')
const saving = ref(false)

const preview = ref<ApplicationPreview | null>(null)
const previewVisible = ref(false)
const previewing = ref(false)

const drawerVisible = ref(false)
const operation = ref<ApplicationOperation | null>(null)
let timer: number | undefined

const flowNodes = computed(() => nodes.value as unknown as Node[])
const flowEdges = computed(() => edges.value as unknown as Edge[])
const selectedNode = computed(() => nodes.value.find((n) => n.id === selectedKey.value))
const hasBlockingProblem = computed(() =>
  (preview.value?.problems ?? []).some((p) => p.severity === 'ERROR'))

const appId = computed(() => String(route.params.id || ''))

function defaultParams(kind: AppNodeKind): Record<string, unknown> {
  return kind === 'SERVICE'
    ? { image: '', port: 8080, replicas: 1, expose: 'none', env: [] }
    : { image: '', replicas: 1, env: [] }
}

function nextKey(kind: AppNodeKind): string {
  const base = kind === 'SERVICE' ? 'service' : 'worker'
  const used = new Set(nodes.value.map((n) => n.id))
  let i = 1
  while (used.has(`${base}-${i}`)) i++
  return `${base}-${i}`
}

function layout(index: number) {
  return { x: 80 + (index % 4) * 220, y: 60 + Math.floor(index / 4) * 150 }
}

function addNode(kind: AppNodeKind) {
  const key = nextKey(kind)
  nodes.value.push({
    id: key,
    type: 'app',
    position: layout(nodes.value.length),
    data: { key, kind, params: defaultParams(kind) },
  })
  selectedKey.value = key
}

function onConnect(connection: Connection) {
  if (connection.source === connection.target) {
    ElMessage.warning('连线不能指向自己')
    return
  }
  const id = `${connection.source}->${connection.target}`
  if (edges.value.some((e) => e.id === id)) return
  edges.value.push({ id, source: connection.source!, target: connection.target! })
}

/**
 * 拖动位置只保存在本数组里（不落 spec，刷新按网格重排）。
 * 必须收这个事件：否则每次状态轮询把 props 打回去时，节点会被拽回初始位置。
 */
function onNodesChange(changes: NodeChange[]) {
  for (const c of changes) {
    if (c.type === 'position' && c.position) {
      const node = nodes.value.find((n) => n.id === c.id)
      if (node) node.position = { ...c.position }
    }
  }
}

function onNodeClick(payload: { node: Node }) {
  selectedKey.value = payload.node.id
}

function updateParams(key: string, params: Record<string, unknown>) {
  const node = nodes.value.find((n) => n.id === key)
  if (node) node.data = { ...node.data, params }
}

async function removeNode(key: string) {
  await ElMessageBox.confirm(`移除节点「${key}」？保存并部署后，它创建的资源会被清理（PVC 保留）。`,
    '移除节点', { type: 'warning' })
  nodes.value = nodes.value.filter((n) => n.id !== key)
  edges.value = edges.value.filter((e) => e.source !== key && e.target !== key)
  if (selectedKey.value === key) selectedKey.value = ''
}

function toSpec(): AppSpec {
  return {
    namespace: app.value!.namespace,
    settings: {},
    nodes: nodes.value.map<AppNode>((n) => ({ key: n.id, kind: n.data.kind, params: n.data.params })),
    edges: edges.value.map((e) => ({ from: e.source, to: e.target })),
  }
}

async function load() {
  app.value = await applicationApi.get(appId.value)
  options.value = await applicationApi.options(app.value.clusterId)
  nodes.value = app.value.spec.nodes.map((n, i) => ({
    id: n.key,
    type: 'app',
    position: layout(i),
    data: { key: n.key, kind: n.kind, params: n.params ?? {}, state: 'PENDING' },
  }))
  edges.value = (app.value.spec.edges ?? []).map((e) => ({
    id: `${e.from}->${e.to}`, source: e.from, target: e.to,
  }))
}

async function save(): Promise<boolean> {
  saving.value = true
  try {
    app.value = await applicationApi.update({ appId: appId.value, spec: toSpec() })
    ElMessage.success('已保存')
    return true
  } finally {
    saving.value = false
  }
}

async function openPreview() {
  previewing.value = true
  try {
    preview.value = await applicationApi.preview({ appId: appId.value, spec: toSpec() })
    previewVisible.value = true
  } finally {
    previewing.value = false
  }
}

async function deploy() {
  if (!(await save())) return
  previewVisible.value = false
  const operationId = await applicationApi.deploy({ appId: appId.value })
  drawerVisible.value = true
  poll(operationId)
}

function poll(operationId: number) {
  window.clearInterval(timer)
  timer = window.setInterval(async () => {
    try {
      const op = await applicationApi.operation(operationId)
      operation.value = op
      applyNodeStates(op)
      if (op.status === 'SUCCESS' || op.status === 'FAILED') {
        window.clearInterval(timer)
        if (op.status === 'SUCCESS') {
          ElMessage.success('部署完成')
          app.value = await applicationApi.get(appId.value)
        } else {
          ElMessage.error(`部署失败：${op.message ?? ''}`)
        }
      }
    } catch {
      window.clearInterval(timer)
    }
  }, 3000)
}

function applyNodeStates(op: ApplicationOperation) {
  const raw = op.nodeStatesJson
  if (!raw) return
  let states: Record<string, string> = {}
  try {
    states = JSON.parse(raw) as Record<string, string>
  } catch {
    return
  }
  nodes.value = nodes.value.map((n) => ({
    ...n,
    data: { ...n.data, state: states[n.id] ?? n.data.state ?? 'PENDING' },
  }))
}

async function retry() {
  if (!operation.value) return
  const operationId = await applicationApi.deploy({
    appId: appId.value, revision: operation.value.revision,
  })
  poll(operationId)
}

onMounted(load)
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div v-if="app" class="canvas-page">
    <div class="canvas-page__header">
      <div class="canvas-page__title">
        <el-button link @click="router.push('/applications')">← 应用编排</el-button>
        <h2>{{ app.displayName || app.name }}</h2>
        <el-tag effect="plain" size="small">{{ app.namespace }}</el-tag>
        <el-tag effect="plain" size="small" type="info">v{{ app.currentRevision }}</el-tag>
      </div>
      <div class="canvas-page__actions">
        <el-button :loading="saving" @click="save">保存</el-button>
        <el-button :loading="previewing" @click="openPreview">预览</el-button>
        <el-button type="primary" @click="deploy">部署</el-button>
      </div>
    </div>

    <div class="canvas-page__body">
      <aside class="palette">
        <div class="palette__title">节点</div>
        <el-button
          v-for="k in options.nodeKinds"
          :key="k.kind"
          class="palette__item"
          @click="addNode(k.kind)"
        >
          <div>
            <div class="palette__name">{{ k.name }}</div>
            <div class="palette__desc">{{ k.description }}</div>
          </div>
        </el-button>
      </aside>

      <div class="canvas">
        <VueFlow
          :nodes="flowNodes"
          :edges="flowEdges"
          :delete-key-code="null"
          fit-view-on-init
          @connect="onConnect"
          @node-click="onNodeClick"
          @nodes-change="onNodesChange"
        >
          <template #node-app="nodeProps">
            <AppNodeCard
              :data="nodeProps.data"
              :selected="nodeProps.id === selectedKey"
            />
          </template>
          <Background :gap="18" :size="1.4" />
          <Controls position="bottom-left" />
        </VueFlow>
        <div v-if="!nodes.length" class="canvas__empty">
          从左侧添加节点开始画：拖一条线表示「谁使用谁」。
        </div>
      </div>

      <aside v-if="selectedNode" class="inspector-pane">
        <AppNodeInspector
          :node="{ key: selectedNode.id, kind: selectedNode.data.kind, params: selectedNode.data.params }"
          @update="updateParams(selectedNode!.id, $event)"
          @remove="removeNode(selectedNode!.id)"
        />
      </aside>
    </div>

    <AppDeployDrawer v-model="drawerVisible" :operation="operation" @retry="retry" />

    <el-dialog v-model="previewVisible" title="部署预览" width="720px">
      <template v-if="preview">
        <el-alert
          v-if="hasBlockingProblem"
          type="error"
          :closable="false"
          show-icon
          title="图里有阻断问题，不能部署"
          class="preview__alert"
        />
        <div v-if="preview.problems.length" class="preview__problems">
          <div v-for="(p, i) in preview.problems" :key="i" :class="`problem problem--${p.severity.toLowerCase()}`">
            [{{ p.severity }}] {{ p.nodeKey ? `${p.nodeKey}：` : '' }}{{ p.message }}
          </div>
        </div>
        <el-descriptions :column="2" border size="small" class="preview__diff">
          <el-descriptions-item label="新增对象">
            <span v-if="!preview.added.length">—</span>
            <div v-for="a in preview.added" :key="a" class="ok">{{ a }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="将删除">
            <span v-if="!preview.removed.length">—</span>
            <div v-for="r in preview.removed" :key="r" class="danger">{{ r }}</div>
          </el-descriptions-item>
        </el-descriptions>
        <YamlBlock :yaml="preview.manifest || ''" max-height="40vh" />
      </template>
      <template #footer>
        <el-button @click="previewVisible = false">取消</el-button>
        <el-button type="primary" :disabled="hasBlockingProblem" @click="deploy">确认部署</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.canvas-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.canvas-page__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.canvas-page__title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.canvas-page__title h2 {
  margin: 0;
  font-size: 18px;
}
.canvas-page__actions {
  display: flex;
  gap: 8px;
}
.canvas-page__body {
  display: grid;
  grid-template-columns: 180px 1fr auto;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.palette {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-bg-color);
}
.palette__title {
  font-weight: 600;
  margin-bottom: 8px;
}
.palette__item {
  width: 100%;
  margin-bottom: 8px;
  justify-content: flex-start;
  height: auto;
  padding: 8px;
  white-space: normal;
  text-align: left;
}
.palette__name {
  font-size: 13px;
}
.palette__desc {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.canvas {
  position: relative;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
  background: var(--el-fill-color-lighter);
}
.canvas__empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  pointer-events: none;
}
.inspector-pane {
  width: 340px;
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-bg-color);
}
.preview__alert,
.preview__diff {
  margin-bottom: 12px;
}
.preview__problems {
  margin-bottom: 12px;
}
.problem {
  font-size: 12px;
}
.problem--error {
  color: var(--el-color-danger);
}
.problem--warn {
  color: var(--el-color-warning);
}
.ok {
  color: var(--el-color-success);
}
.danger {
  color: var(--el-color-danger);
}
</style>
