<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Connection, Edge, EdgeChange, Node, NodeChange } from '@vue-flow/core'
import { applicationApi, middlewareApi } from '@/api'
import { useApplicationContext } from '@/composables/useApplicationContext'
import type {
  ApplicationOperation,
  ApplicationOptions,
  ApplicationPreview,
  ApplicationView,
  AppNode,
  AppNodeKind,
  AppSpec,
  MiddlewareCatalogItem,
  MiddlewareInstance,
} from '@/types'
import AppCanvas from '@/components/app-canvas/AppCanvas.vue'
import AppNodePalette from '@/components/app-canvas/AppNodePalette.vue'
import AppNodeContextMenu from '@/components/app-canvas/AppNodeContextMenu.vue'
import { NODE_WIDTH, layeredLayout } from '@/components/app-canvas/appCanvasLayout'
import type { Point } from '@/components/app-canvas/appCanvasLayout'
import AppNodeInspector from '@/components/app-canvas/AppNodeInspector.vue'
import AppDeployDrawer from '@/components/app-canvas/AppDeployDrawer.vue'
import YamlBlock from '@/components/YamlBlock.vue'

/** 画布上的节点（不直接使用 Vue Flow 的 Node 泛型，只在绑定处转型） */
interface FlowNodeView {
  id: string
  type: string
  /** false = Delete 键不会删节点（移除节点走检查器的确认框） */
  deletable: boolean
  position: { x: number; y: number }
  data: { key: string; kind: AppNodeKind; params: Record<string, unknown>; state?: string }
}
interface FlowEdgeView {
  id: string
  source: string
  target: string
  type?: string
}

const route = useRoute()
const router = useRouter()
const { tenantId, ensure } = useApplicationContext()

const app = ref<ApplicationView>()
const options = ref<ApplicationOptions>({ nodeKinds: [], namespaces: [] })
const middlewareCatalog = ref<MiddlewareCatalogItem[]>([])
const middlewareInstances = ref<MiddlewareInstance[]>([])
const nodes = ref<FlowNodeView[]>([])
const edges = ref<FlowEdgeView[]>([])
/** 用户手拖过的节点坐标（钉住）。没钉的节点每次都按依赖自动分层，图一改就重新排齐。 */
const pins = ref<Record<string, Point>>({})
const canvasRef = ref<InstanceType<typeof AppCanvas>>()
const selectedKey = ref('')
const saving = ref(false)

/** 节点右键菜单（视口坐标） */
const menu = ref<{ open: boolean; x: number; y: number; key: string }>({ open: false, x: 0, y: 0, key: '' })
const menuEdgeCount = computed(() => {
  const key = menu.value.key
  return key ? edges.value.filter((e) => e.source === key || e.target === key).length : 0
})

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
  if (kind === 'MIDDLEWARE') {
    return { code: 'mysql', provisionMode: 'CREATE', shape: '', initPayload: '', metricsEnabled: false }
  }
  if (kind === 'EXTERNAL') {
    return { code: 'mysql', host: '', port: 3306, secretName: '', usernameKey: '', passwordKey: '' }
  }
  return kind === 'SERVICE'
    ? { image: '', port: 8080, replicas: 1, expose: 'none', env: [] }
    : { image: '', replicas: 1, env: [] }
}

function nextKey(kind: AppNodeKind): string {
  const base = kind === 'SERVICE' ? 'service'
    : kind === 'WORKER' ? 'worker'
      : kind === 'MIDDLEWARE' ? 'db' : 'ext'
  const used = new Set(nodes.value.map((n) => n.id))
  let i = 1
  while (used.has(`${base}-${i}`)) i++
  return `${base}-${i}`
}

/** 画布坐标 = 按依赖分层 + 手工钉住的位置（钉住的优先） */
function relayout() {
  const auto = layeredLayout(
    nodes.value.map((n) => ({ key: n.id, kind: n.data.kind })),
    edges.value.map((e) => ({ from: e.source, to: e.target })),
  )
  nodes.value = nodes.value.map((n) => {
    const pinned = pins.value[n.id]
    const position: Point = pinned ? { x: pinned.x, y: pinned.y } : (auto[n.id] ?? n.position)
    return { ...n, position }
  })
}

/**
 * 加节点。带 position = 从面板拖过来的，落在松手的位置并钉住；
 * 不带 position = 点面板加的，交给自动分层落位。
 */
function addNode(kind: AppNodeKind, position?: Point) {
  const key = nextKey(kind)
  if (position) {
    pins.value = { ...pins.value, [key]: { x: position.x, y: position.y } }
  }
  nodes.value = [...nodes.value, {
    id: key,
    type: 'app',
    deletable: false,
    position: position ?? { x: 0, y: 0 },
    data: { key, kind, params: defaultParams(kind) },
  }]
  selectedKey.value = key
  relayout()
}

/** 清掉所有手工位置，按当前依赖重新排一遍 */
function autoArrange() {
  pins.value = {}
  relayout()
  nextTick(() => canvasRef.value?.fit())
}

/** 从草稿里读回上次保存的坐标；形状不对就当作没有，别让一个脏值把画布画坏 */
function savedPins(spec: AppSpec | undefined): Record<string, Point> {
  const raw = spec?.settings?.layout
  if (!raw || typeof raw !== 'object') return {}
  const out: Record<string, Point> = {}
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    const p = value as { x?: unknown; y?: unknown } | null
    if (p && typeof p.x === 'number' && typeof p.y === 'number') out[key] = { x: p.x, y: p.y }
  }
  return out
}

function onConnect(connection: Connection) {
  if (connection.source === connection.target) {
    ElMessage.warning('连线不能指向自己')
    return
  }
  const source = nodes.value.find((n) => n.id === connection.source)
  if (source && source.data.kind !== 'SERVICE' && source.data.kind !== 'WORKER') {
    ElMessage.warning('只有服务和后台进程可以发起连线（中间件/外部服务只能被使用）')
    return
  }
  const id = `${connection.source}->${connection.target}`
  if (edges.value.some((e) => e.id === id)) return
  edges.value = [...edges.value, { id, source: connection.source!, target: connection.target!, type: 'smoothstep' }]
  // 依赖变了就重排：新连线两端的层级可能要挪，用户钉住的节点不动
  relayout()
}

/**
 * 拖动会把这个节点钉住（写进 spec.settings.layout），之后结构变化也不再挪它。
 * 只认带 dragging 标记的位置变化：relayout 自己写坐标时不该反过来把节点全钉死。
 */
function onNodesChange(changes: NodeChange[]) {
  for (const c of changes) {
    if (c.type !== 'position' || !c.position || c.dragging === undefined) continue
    const node = nodes.value.find((n) => n.id === c.id)
    if (!node) continue
    node.position = { ...c.position }
    pins.value = { ...pins.value, [c.id]: { ...c.position } }
  }
}

/** 选中边按 Delete 删除：:edges 是单向绑定，得自己把数组同步回来，否则那边又冒出来 */
function onEdgesChange(changes: EdgeChange[]) {
  const removed = changes.filter((c) => c.type === 'remove').map((c) => c.id)
  if (!removed.length) return
  edges.value = edges.value.filter((e) => !removed.includes(e.id))
  relayout()
}

function onNodeClick(node: Node) {
  selectedKey.value = node.id
  closeMenu()
}

function onNodeContextMenu(node: Node, event: MouseEvent | TouchEvent) {
  // Vue Flow 只转发事件、不阻止默认行为，不自己 preventDefault 会同时弹出浏览器菜单
  event.preventDefault()
  // 触屏上 contextmenu 是长按来的 TouchEvent，没有 clientX/Y
  const point = 'clientX' in event
    ? { x: event.clientX, y: event.clientY }
    : { x: event.touches[0]?.clientX ?? 0, y: event.touches[0]?.clientY ?? 0 }
  selectedKey.value = node.id
  menu.value = { open: true, x: point.x, y: point.y, key: node.id }
}

function closeMenu() {
  if (menu.value.open) menu.value = { ...menu.value, open: false }
}

/** 复制节点：新 key、参数深拷贝、落在原节点右边并钉住（复制出来的位置是用户看得见的地方） */
function duplicateNode(key: string) {
  const source = nodes.value.find((n) => n.id === key)
  closeMenu()
  if (!source) return
  const newKey = nextKey(source.data.kind)
  const position = { x: source.position.x + NODE_WIDTH + 40, y: source.position.y }
  pins.value = { ...pins.value, [newKey]: position }
  nodes.value = [...nodes.value, {
    id: newKey,
    type: 'app',
    deletable: false,
    position,
    data: {
      key: newKey,
      kind: source.data.kind,
      params: JSON.parse(JSON.stringify(source.data.params)) as Record<string, unknown>,
    },
  }]
  selectedKey.value = newKey
  relayout()
}

function disconnectNode(key: string) {
  const before = edges.value.length
  edges.value = edges.value.filter((e) => e.source !== key && e.target !== key)
  const removed = before - edges.value.length
  closeMenu()
  if (!removed) return
  relayout()
  ElMessage.success(`已断开 ${removed} 条连线`)
}

async function menuRemove(key: string) {
  closeMenu()
  await removeNode(key)
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
  const next = { ...pins.value }
  delete next[key]
  pins.value = next
  if (selectedKey.value === key) selectedKey.value = ''
  relayout()
}

function toSpec(): AppSpec {
  return {
    namespace: app.value!.namespace,
    // settings 里的其它键原样带回；layout 存的是用户钉住的坐标，不参与渲染和部署
    settings: { ...(app.value?.spec.settings ?? {}), layout: pins.value },
    nodes: nodes.value.map<AppNode>((n) => ({ key: n.id, kind: n.data.kind, params: n.data.params })),
    edges: edges.value.map((e) => ({ from: e.source, to: e.target })),
  }
}

async function load() {
  await ensure()
  app.value = await applicationApi.get(appId.value, tenantId.value)
  options.value = await applicationApi.options(app.value.clusterId)
  loadMiddlewareOptions()
  const spec = app.value.spec
  const specNodes = spec.nodes ?? []
  const specEdges = spec.edges ?? []
  pins.value = savedPins(spec)
  nodes.value = specNodes.map((n) => ({
    id: n.key,
    type: 'app',
    deletable: false,
    position: { x: 0, y: 0 },
    data: { key: n.key, kind: n.kind, params: n.params ?? {}, state: 'PENDING' },
  }))
  edges.value = specEdges.map((e) => ({
    id: `${e.from}->${e.to}`, source: e.from, target: e.to, type: 'smoothstep',
  }))
  // 没有钉住记录的节点按依赖自动落位
  relayout()
}

async function loadMiddlewareOptions() {
  try {
    const clusterId = app.value?.clusterId ?? ''
    middlewareCatalog.value = await middlewareApi.catalog(clusterId)
    middlewareInstances.value = await middlewareApi.instanceList(clusterId)
  } catch {
    // 没有中间件模块权限时画布仍然可用，只是选不了中间件节点
    middlewareCatalog.value = []
    middlewareInstances.value = []
  }
}

async function save(): Promise<boolean> {
  saving.value = true
  try {
    app.value = await applicationApi.update({
      appId: appId.value, spec: toSpec(), tenantId: tenantId.value,
    })
    ElMessage.success('已保存')
    return true
  } finally {
    saving.value = false
  }
}

async function openPreview() {
  previewing.value = true
  try {
    preview.value = await applicationApi.preview({
      appId: appId.value, spec: toSpec(), tenantId: tenantId.value,
    })
    previewVisible.value = true
  } finally {
    previewing.value = false
  }
}

async function deploy() {
  if (!(await save())) return
  previewVisible.value = false
  const operationId = await applicationApi.deploy({ appId: appId.value, tenantId: tenantId.value })
  drawerVisible.value = true
  poll(operationId)
}

function poll(operationId: number) {
  window.clearInterval(timer)
  timer = window.setInterval(async () => {
    try {
      const op = await applicationApi.operation(operationId, tenantId.value)
      operation.value = op
      applyNodeStates(op)
      if (op.status === 'SUCCESS' || op.status === 'FAILED') {
        window.clearInterval(timer)
        if (op.status === 'SUCCESS') {
          ElMessage.success('部署完成')
          app.value = await applicationApi.get(appId.value, tenantId.value)
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
    appId: appId.value, revision: operation.value.revision, tenantId: tenantId.value,
  })
  poll(operationId)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeMenu()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  load()
})
onUnmounted(() => {
  window.clearInterval(timer)
  window.removeEventListener('keydown', onKeydown)
})
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
        <el-button @click="autoArrange">自动整理</el-button>
        <el-button :loading="saving" @click="save">保存</el-button>
        <el-button :loading="previewing" @click="openPreview">预览</el-button>
        <el-button type="primary" @click="deploy">部署</el-button>
      </div>
    </div>

    <div class="canvas-page__body">
      <AppNodePalette :kinds="options.nodeKinds" @add="addNode" />

      <div class="canvas">
        <AppCanvas
          ref="canvasRef"
          :nodes="flowNodes"
          :edges="flowEdges"
          :selected-key="selectedKey"
          :empty="!nodes.length"
          @connect="onConnect"
          @node-click="onNodeClick"
          @node-context-menu="onNodeContextMenu"
          @nodes-change="onNodesChange"
          @edges-change="onEdgesChange"
          @drop-node="addNode"
        />
      </div>

      <aside v-if="selectedNode" class="inspector-pane">
        <AppNodeInspector
          :node="{ key: selectedNode.id, kind: selectedNode.data.kind, params: selectedNode.data.params }"
          :catalog="middlewareCatalog"
          :instances="middlewareInstances"
          :namespace="app.namespace"
          @update="updateParams(selectedNode!.id, $event)"
          @remove="removeNode(selectedNode!.id)"
        />
      </aside>
    </div>

    <AppNodeContextMenu
      :open="menu.open"
      :x="menu.x"
      :y="menu.y"
      :edge-count="menuEdgeCount"
      @close="closeMenu"
      @duplicate="duplicateNode(menu.key)"
      @disconnect="disconnectNode(menu.key)"
      @remove="menuRemove(menu.key)"
    />

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
  grid-template-columns: 240px 1fr auto;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.canvas {
  position: relative;
  min-width: 0;
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
