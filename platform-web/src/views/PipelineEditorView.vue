<script setup lang="ts">
/**
 * 流水线画布。左：节点面板（拖入）· 中：画布（自由连线、**环检测**、并发语义提示）· 右：属性面板。
 *
 * 三条刻意的地方：
 * 1. **环在连线那一刻就拒**（前端实时提示），保存时后端**也会**再拒一次
 *    （跑一次 `/pipeline/preview`）—— 前端拦不住的（绕过 UI 直接打接口）必须由后端兜住。
 * 2. **没连线的两个节点是并发**，不是"没连上" —— 顶栏显式提示这件事，否则用户会以为画错了。
 * 3. **画布 JSON 是唯一真相源**：集群里不留 Pipeline 对象，每次运行才渲染成 PipelineSpec。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
// 这三个是官方拆出去的兄弟包（core 里没有）
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import type { Connection, Edge, EdgeChange, MouseTouchEvent, Node, NodeChange } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { pipelineApi } from '@/api'
import type { PipelineCanvas, PipelineCanvasNode, PipelineConnection, PipelineNodeType } from '@/types'
import { usePipelineNodeTypes } from '@/composables/usePipelineNodeTypes'
import { usePipelineCapability } from '@/composables/usePipelineCapability'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import YamlBlock from '@/components/YamlBlock.vue'
import PipelineNodeVue from '@/components/pipeline/PipelineNodeVue.vue'
import NodePalette from '@/components/pipeline/NodePalette.vue'
import NodePropsPanel from '@/components/pipeline/NodePropsPanel.vue'

const route = useRoute()
const router = useRouter()
const perm = usePermission()
const { types: nodeTypeList, load: loadNodeTypes, labelOf } = usePipelineNodeTypes()
const { screenToFlowCoordinate } = useVueFlow()

const pipelineId = computed(() => (route.query.id as string) || '')
const isNew = computed(() => !pipelineId.value)

const form = ref<{ id?: string; clusterId: string; namespace: string; name: string; description: string }>({
  clusterId: (route.query.clusterId as string) || '',
  namespace: (route.query.namespace as string) || '',
  name: '',
  description: '',
})

/** 画布节点的 data 形状（后端 type 在 nodeType 里 —— Vue Flow 的类型统一叫 `pipeline`） */
interface PipelineNodeData {
  nodeType: string
  /** 用户起的显示名（可空）；空则卡片上退回显示节点 id */
  name: string
  config: Record<string, string>
  status?: string
}

/**
 * ⚠️ 画布状态用**我们自己的结构化类型**，不用 Vue Flow 的 `Node` / `Edge`。
 * 那两个是很深的泛型联合（`Node<Data, Events, Type>` 再套 Vue 的 VNode 联合），
 * 在这个组件的形状下对它们做 `map` / `find` / 赋值会一路触发 TS2589「类型实例化过深」。
 *
 * 于是只在**绑定给 `<VueFlow>` 的那一处**（`flowNodes` / `flowEdges` 两个 computed）做一次转型，
 * 其余全部按结构读写。
 */
type FlowNodeView = {
  id: string
  type: string
  position: { x: number; y: number }
  data: PipelineNodeData
}
type FlowEdgeView = { id: string; source: string; target: string }

const nodes = ref<FlowNodeView[]>([])
const edges = ref<FlowEdgeView[]>([])

/** 只在绑定处转型 —— 这是整个组件里唯一碰 Vue Flow 泛型的地方 */
const flowNodes = computed(() => nodes.value as unknown as Node[])
const flowEdgesBound = computed(() => edges.value as unknown as Edge[])
const selected = ref<string>('')
/**
 * 选中的**连线**。与节点选中互斥（节点选中归属性面板用）。
 *
 * <p>留它的理由**不是**给某个按钮用（删节点/删连线已经收进右键菜单了），而是：选中本身是可见反馈，
 * 且 Vue Flow 内置的「选中 + Delete」那条键盘路需要它 —— 界面上靠下面那句提示说出来。
 */
const selectedEdge = ref('')
const gitConnections = ref<PipelineConnection[]>([])
const registryConnections = ref<PipelineConnection[]>([])
const yamlPreview = ref('')
const showPreview = ref(false)
const saving = ref(false)

/** 一个自定义节点组件服务所有后端 type（后端 type 在 data.nodeType 里）——「加节点不用改前端」 */
const canManage = computed(() => perm.has(apiCodes.pipelineManage))
const canRun = computed(() => perm.has(apiCodes.pipelineRun))

/** 生效租户：同样来自进来时带的上下文（平台管理员代管轨触发运行必须显式给） */
const effectiveTenantId = computed(() => (route.query.tenantId as string) || undefined)

/** 集群来自创建流水线时带过来的上下文（编辑器不进顶栏级联） */
const capClusterId = computed(() => form.value.clusterId || '')
/** 页面级门控（三态：`false` 才禁用 —— `null` = 不知道，不拦；真正的拦截在后端） */
const { available: capAvailable, reason: capReason } = usePipelineCapability(capClusterId)

/** 画布上的节点（后端形状），用于保存与预览 */
function toCanvasNodes(): PipelineCanvasNode[] {
  return nodes.value.map((n) => {
    const name = (n.data.name ?? '').trim()
    return {
      id: n.id,
      type: n.data.nodeType,
      // 没起名就**不写这个键**：存下来的画布干净，后端那边「没有 name」与「空 name」等价
      ...(name ? { name } : {}),
      config: { ...(n.data.config ?? {}) },
      // ⚠️ 坐标必须存：不存的话重开时按序号重排，用户手拖的布局全丢
      //    （2026-10-15 用户报「保存后再编辑，节点叠在一起了」）。
      //    取整只是让 JSON 干净 —— Vue Flow 拖动会给出小数
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    }
  })
}

function canvasJson(): string {
  const canvas: PipelineCanvas = {
    nodes: toCanvasNodes(),
    edges: edges.value.map((e) => ({ from: e.source, to: e.target })),
  }
  return JSON.stringify(canvas)
}

/** 选中的节点（属性面板的入参） */
const selectedNode = computed(() => {
  const n = nodes.value.find((x) => x.id === selected.value)
  if (!n) return null
  return {
    id: n.id,
    type: n.data.nodeType,
    name: n.data.name ?? '',
    config: n.data.config ?? {},
  }
})

// ---- 载入 ----

onMounted(async () => {
  await loadNodeTypes()
  gitConnections.value = await pipelineApi.gitConnections().catch(() => [])
  registryConnections.value = await pipelineApi.registryConnections().catch(() => [])
  if (!isNew.value) {
    const p = await pipelineApi.get(pipelineId.value)
    form.value = {
      id: p.id,
      clusterId: p.clusterId ?? '',
      namespace: p.namespace ?? '',
      name: p.name,
      description: p.description ?? '',
    }
    applyCanvas(p.canvasJson)
  }
})

function applyCanvas(json?: string | null) {
  if (!json) return
  try {
    const c = JSON.parse(json) as PipelineCanvas
    nodes.value = (c.nodes ?? []).map((n, i) => flowNode(n.id, n.type, n.name ?? '', n.config, i, n.position))
    edges.value = (c.edges ?? []).map((e) => flowEdge(e.from, e.to))
  } catch {
    ElMessage.error('画布内容无法解析')
  }
}

let seq = 0
function nextId(type: string): string {
  // 节点 id 会进 Tekton 的 task 名（K8s 对象名）：小写字母/数字/连字符
  seq += 1
  return `${type}-${Date.now().toString(36).slice(-4)}${seq}`
}


/** 没有存过坐标时的退回排布：3 列网格（老画布、以及刚拖进来的新节点） */
function autoPosition(index: number): { x: number; y: number } {
  return { x: 80 + (index % 3) * 200, y: 60 + Math.floor(index / 3) * 120 }
}

function flowNode(
  id: string,
  type: string,
  name: string,
  config: Record<string, string>,
  index: number,
  position?: { x: number; y: number } | null,
): FlowNodeView {
  return {
    id,
    type: 'pipeline',
    // 存过坐标就用存的（用户手拖出来的布局）；没有才退回网格
    position: position ? { x: position.x, y: position.y } : autoPosition(index),
    data: { nodeType: type, name: name ?? '', config: config ?? {} },
  }
}

function flowEdge(from: string, to: string): FlowEdgeView {
  return { id: `e-${from}-${to}`, source: from, target: to }
}

// ---- 画布交互 ----

/**
 * ⚠️ 手写 change 应用而不用 `applyNodeChanges`：后者的形参类型是内部 `GraphNode`，
 * 与 `<VueFlow :nodes>` 收的 `Node` 不是同一个 —— 用它会引出一串类型不兼容。
 * 我们只需要三种变化（拖动位置 / 选中 / 删除），手写反而更清楚。
 */
function onNodesChange(changes: NodeChange[]) {
  let removed = false
  for (const c of changes) {
    if (c.type === 'position' && c.position) {
      const n = nodes.value.find((x) => x.id === c.id)
      if (n) n.position = { ...c.position }
    } else if (c.type === 'remove') {
      nodes.value = nodes.value.filter((x) => x.id !== c.id)
      removed = true
    }
  }
  if (removed) {
    const alive = new Set(nodes.value.map((n) => n.id))
    edges.value = edges.value.filter((e) => alive.has(e.source) && alive.has(e.target))
    selectedEdge.value = ''            // 被删的节点可能正连着它
    if (!alive.has(selected.value)) selected.value = ''
  }
}

function onEdgesChange(changes: EdgeChange[]) {
  for (const c of changes) {
    if (c.type === 'remove') {
      edges.value = edges.value.filter((e) => e.id !== c.id)
    }
  }
}

/** 从左侧拖进来的节点 */
function onDrop(e: DragEvent) {
  const type = e.dataTransfer?.getData('application/x-pipeline-node')
  if (!type) return
  const pos = screenToFlowCoordinate(eventPoint(e))
  const id = nextId(type)
  nodes.value = [...nodes.value, { ...flowNode(id, type, '', {}, 0), position: pos }]
  selected.value = id
}

/** 加一条边之前先看会不会成环 —— **绝不让一个环渲染出去**（Review Focus #1 的前端一半） */
function wouldCycle(from: string, to: string): boolean {
  if (from === to) return true
  const adj = new Map<string, string[]>()
  for (const e of edges.value) {
    if (!adj.has(e.source)) adj.set(e.source, [])
    adj.get(e.source)!.push(e.target)
  }
  if (!adj.has(from)) adj.set(from, [])
  adj.get(from)!.push(to)
  const seen = new Set<string>()
  const stack = [to]
  while (stack.length) {
    const cur = stack.pop()!
    if (cur === from) return true
    if (seen.has(cur)) continue
    seen.add(cur)
    for (const nxt of adj.get(cur) ?? []) stack.push(nxt)
  }
  return false
}

function onConnect(params: Connection) {
  if (wouldCycle(params.source, params.target)) {
    ElMessage.warning('这样连会形成环 —— 节点之间的连线必须是一条有向无环图')
    return
  }
  edges.value = [...edges.value, flowEdge(params.source, params.target)]
}

function onNodeClick(e: { node: { id: string } }) {
  selected.value = e.node.id
  selectedEdge.value = ''
}

function onEdgeClick(e: { edge: { id: string } }) {
  selectedEdge.value = e.edge.id
  selected.value = ''
}

/** 从一个节点的 "+" 接一个下游节点：新节点落在它右边，并自动连一条边。 */
function insertAfter(fromId: string, type: string) {
  const from = nodes.value.find((n) => n.id === fromId)
  const x = (from?.position.x ?? 0) + 250
  const y = (from?.position.y ?? 0) + 20
  const id = nextId(type)
  nodes.value = [...nodes.value, { ...flowNode(id, type, '', {}, 0), position: { x, y } }]
  if (!wouldCycle(fromId, id)) {
    edges.value = [...edges.value, flowEdge(fromId, id)]
  }
  selected.value = id
}

async function removeSelected() {
  // 先看连线：它不需要确认（删一条连线是画布上的家常动作，误删也只是重连一次）
  if (selectedEdge.value) {
    edges.value = edges.value.filter((e) => e.id !== selectedEdge.value)
    selectedEdge.value = ''
    return
  }
  if (!selected.value) return
  nodes.value = nodes.value.filter((n) => n.id !== selected.value)
  edges.value = edges.value.filter((e) => e.source !== selected.value && e.target !== selected.value)
  selected.value = ''
}

function onPropsChange() {
  // 属性面板直接改的是 data.config，这里只要触发一次重渲染让画布上的标题跟上
  nodes.value = [...nodes.value]
}

/**
 * 改名。**名字不是节点的"字段"**（它不在 `fields` 里 —— 那是节点类型自己的东西），
 * 而是画布级的东西，所以走一条自己的事件，而不是混进 config。
 */
function onRename(name: string) {
  const n = nodes.value.find((x) => x.id === selected.value)
  if (!n) return
  n.data.name = name
  nodes.value = [...nodes.value]
}

/**
 * 画布上的**右键菜单**。
 *
 * <p>为什么要有它：删节点 / 删连线 / 加节点原先是「先选中、再去工具栏点按钮」或「从左边拖进来」——
 * 都得先跑一趟别的地方，而这三件事的对象**就在你鼠标底下**（用户 2026-10-16 反馈）。
 *
 * <p>定位贴画布容器（所以要减掉容器偏移），并做一次边界收口：靠右/靠下时不至于跑出画布。
 */
interface MenuItem {
  label: string
  danger?: boolean
  run?: () => void
  /** 有子项 = 悬停展开的二级菜单（「接一个下游节点 ▸」） */
  children?: MenuItem[]
}

const canvasEl = ref<HTMLElement | null>(null)
const menu = ref<{ x: number; y: number; title: string; items: MenuItem[] } | null>(null)

/**
 * Vue Flow 的事件类型是 MouseEvent | TouchEvent（触屏没有 clientX）——
 * 右键菜单本来就来自鼠标；触屏那条路退回容器左上角，至少菜单出得来。
 */
function eventPoint(e: MouseTouchEvent): { x: number; y: number } {
  return 'clientX' in e ? { x: e.clientX, y: e.clientY } : { x: 0, y: 0 }
}

function openMenu(evt: MouseTouchEvent, title: string, items: MenuItem[]): void {
  evt.preventDefault()                      // Vue Flow **不**替我们挡浏览器原生的右键菜单
  const el = canvasEl.value
  if (!el) return
  const at = eventPoint(evt)
  const r = el.getBoundingClientRect()
  menu.value = {
    x: Math.max(4, Math.min(at.x - r.left, r.width - 190)),
    y: Math.max(4, Math.min(at.y - r.top, r.height - 40 * items.length - 40)),
    title,
    items,
  }
}

/** 点菜单项：**先关菜单再执行** —— 动作里多半会改动 nodes/edges，菜单不该悬在旧位置上 */
function runItem(item: MenuItem): void {
  menu.value = null
  item.run?.()
}

/** 在指定画布坐标上落一个新节点（右键空白处用）。 */
function insertNodeAt(type: string, pos: { x: number; y: number }): void {
  const id = nextId(type)
  nodes.value = [...nodes.value, { ...flowNode(id, type, '', {}, 0), position: pos }]
  selected.value = id
  selectedEdge.value = ''
}

function onPaneMenu(e: MouseTouchEvent): void {
  const pos = screenToFlowCoordinate(eventPoint(e))
  openMenu(e, '添加节点',
    nodeTypeList.value.map((t) => ({ label: t.label, run: () => insertNodeAt(t.type, pos) })))
}

function onNodeMenu(e: { event: MouseTouchEvent; node: { id: string } }): void {
  openMenu(e.event, e.node.id, [
    {
      label: '接一个下游节点',
      children: nodeTypeList.value.map((t) => ({ label: t.label, run: () => insertAfter(e.node.id, t.type) })),
    },
    {
      label: '删除节点',
      danger: true,
      run: () => {
        selected.value = e.node.id
        selectedEdge.value = ''
        void removeSelected()
      },
    },
  ])
}

function onEdgeMenu(e: { event: MouseTouchEvent; edge: { id: string } }): void {
  openMenu(e.event, '连线', [
    {
      label: '删除连线',
      danger: true,
      run: () => {
        selectedEdge.value = e.edge.id
        selected.value = ''
        void removeSelected()
      },
    },
  ])
}

/** 没连线的两个节点 = 并发。显式提示，否则用户以为"没连上"是错的。 */
const _unusedLabel = labelOf

const concurrentHint = computed(() => {
  if (nodes.value.length < 2) return ''
  const hasIncoming = new Set(edges.value.map((e) => e.target))
  const roots = nodes.value.filter((n) => !hasIncoming.has(n.id))
  return roots.length > 1 ? `${roots.length} 个节点没有上游 —— 它们会**并发**开始，不是没连上` : ''
})

// ---- 动作 ----

async function doPreview() {
  try {
    yamlPreview.value = await pipelineApi.preview(canvasJson())
    showPreview.value = true
  } catch {
    /* 拦截器已经弹过错了 */
  }
}

async function save(): Promise<string | null> {
  if (!form.value.name) {
    ElMessage.warning('流水线名必填')
    return null
  }
  saving.value = true
  try {
    const body = { ...form.value, canvasJson: canvasJson() }
    const saved = isNew.value
      ? await pipelineApi.create(body as never)
      : await pipelineApi.update(body as never)
    ElMessage.success('已保存')
    if (isNew.value && saved?.id) {
      await router.replace({ path: '/pipelines/editor', query: { ...route.query, id: saved.id } })
    }
    return saved?.id ?? null
  } catch {
    return null // 带环 / 未知节点类型都会在这里被后端拒掉
  } finally {
    saving.value = false
  }
}

async function run() {
  const id = await save()
  if (!id) return
  try {
    const runName = await pipelineApi.run(id, effectiveTenantId.value)
    ElMessage.success(`已触发：${runName}`)
    await router.push({ path: '/pipelines/runs', query: { id } })
  } catch {
    /* 拦截器已提示 */
  }
}

async function removePipeline() {
  if (!form.value.id) return
  await ElMessageBox.confirm('删除这条流水线？运行记录与日志**不会**跟着删（那是历史）。', '确认', {
    type: 'warning',
  })
  await pipelineApi.remove(form.value.id)
  ElMessage.success('已删除')
  await router.push('/pipelines')
}
</script>

<template>
  <div class="page">
    <div class="bar">
      <!-- 回列表：以前只能靠浏览器后退（运行详情页有，编辑页漏了） -->
      <el-button link type="primary" @click="router.push('/pipelines')">← 流水线</el-button>
      <el-input v-model="form.name" placeholder="流水线名（小写字母/数字/连字符）" style="width: 220px" />
      <el-input v-model="form.description" placeholder="描述（可选）" style="width: 260px" />
      <span class="loc">{{ form.clusterId }} / {{ form.namespace }}</span>
      <div class="spacer" />
      <el-button @click="doPreview">预览 YAML</el-button>
      <el-button v-if="canManage" :loading="saving" @click="save()">保存</el-button>
      <el-button v-if="canRun" type="primary" :disabled="capAvailable === false" @click="run">
        保存并运行
      </el-button>
      <el-button v-if="canManage && form.id" type="danger" plain @click="removePipeline">删除</el-button>
    </div>

    <!-- 门控只挡「保存并运行」：画布照常编辑与保存（渲染是纯函数，不需要集群装 Tekton） -->
    <div v-if="capAvailable === false" class="cap-banner">
      <span>这个集群现在跑不了 CI：{{ capReason }}。流水线可以照常编辑保存，但「运行」已禁用。</span>
    </div>

    <div v-if="selectedEdge" class="hint">
      已选中一条连线 —— 按 <b>Delete</b> 删掉它，或在它上面右键
    </div>
    <div v-if="concurrentHint" class="hint">{{ concurrentHint }}</div>

    <div class="canvas-wrap">
      <NodePalette />
      <div ref="canvasEl" class="canvas" style="position: relative" @drop="onDrop" @dragover.prevent>
        <!-- 自定义节点用**插槽**而不是 node-types 属性：插槽的 props 是 Vue Flow 自己的类型，
             属性那条路要把组件断言成内部 NodeComponent，纯粹是类型摩擦。 -->
        <VueFlow
          :nodes="flowNodes"
          :edges="flowEdgesBound"
          :default-edge-options="{
            type: 'smoothstep',
            markerEnd: MarkerType.ArrowClosed,
            style: { stroke: 'var(--border-strong)', strokeWidth: 1.5 },
          }"
          fit-view-on-init
          @nodes-change="onNodesChange"
          @edges-change="onEdgesChange"
          @connect="onConnect"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @node-context-menu="onNodeMenu"
          @edge-context-menu="onEdgeMenu"
          @pane-context-menu="onPaneMenu"
        >
          <template #node-pipeline="nodeProps">
            <PipelineNodeVue :id="nodeProps.id" :data="nodeProps.data" />
          </template>

          <Background :gap="18" :size="1.4" pattern-color="var(--border-strong)" />
          <Controls position="bottom-right" />
          <MiniMap v-if="nodes.length > 4" pannable zoomable :node-color="() => '#2563eb'" />
        </VueFlow>

        <!-- 右键菜单：一层透明背板先接住外面的点击（点一下就关），菜单本身在它上面 -->
        <div v-if="menu" class="ctx-backdrop"
             @click="menu = null" @contextmenu.prevent="menu = null" />
        <div v-if="menu" class="ctx-menu" :style="{ left: menu.x + 'px', top: menu.y + 'px' }">
          <div class="ctx-title">{{ menu.title }}</div>
          <template v-for="it in menu.items" :key="it.label">
            <div v-if="it.children?.length" class="ctx-item ctx-has-sub">
              <span>{{ it.label }}</span><span class="ctx-arrow">▸</span>
              <div class="ctx-sub">
                <div v-for="c in it.children" :key="c.label" class="ctx-item" @click="runItem(c)">
                  {{ c.label }}
                </div>
              </div>
            </div>
            <div v-else class="ctx-item" :class="{ danger: it.danger }" @click="runItem(it)">
              {{ it.label }}
            </div>
          </template>
        </div>

        <div v-if="!nodes.length" class="canvas-empty">
          <div class="canvas-empty-title">还是一条空流水线</div>
          <div class="canvas-empty-hint">
            把左边的节点拖进来 · 连线的顺序就是执行顺序 · <b>没连线的两个节点会并发开始</b>
          </div>
        </div>
      </div>
      <NodePropsPanel
        v-if="selectedNode"
        :node="selectedNode"
        :git-connections="gitConnections"
        :registry-connections="registryConnections"
        @change="onPropsChange"
        @rename="onRename"
      />
      <div v-else class="props-empty">点一个节点看它的属性</div>
    </div>

    <el-dialog v-model="showPreview" title="渲染出来的 Tekton YAML" width="70%">
      <YamlBlock :yaml="yamlPreview" max-height="50vh" />
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.bar {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 0;
}
.loc {
  font-size: 12px;
  color: var(--text-3);
}
.spacer {
  flex: 1;
}
.hint {
  font-size: 12px;
  color: var(--warning, #e6a23c);
  padding: 4px 0;
}
/* capability 门控横幅：与列表页、Calico 那几个页面同一形状 */
.cap-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
  color: var(--text-2);
  font-size: 13px;
}
.canvas-wrap {
  display: flex;
  flex: 1;
  min-height: 460px;
  border: 1px solid var(--border, #ebeef5);
  border-radius: 4px;
  overflow: hidden;
}
.canvas {
  flex: 1;
  min-width: 0;
}
/* ---- 右键菜单（贴画布容器定位；二级菜单悬停展开）---- */
.ctx-backdrop {
  position: absolute;
  inset: 0;
  z-index: 5;
}
.ctx-menu {
  position: absolute;
  z-index: 6;
  min-width: 160px;
  padding: 4px;
  background: var(--bg, #fff);
  border: 1px solid var(--border, #ebeef5);
  border-radius: 6px;
  box-shadow: 0 6px 20px rgb(0 0 0 / 18%);
  font-size: 13px;
}
.ctx-title {
  padding: 4px 8px 6px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--border, #ebeef5);
  color: var(--text-3);
  font-size: 12px;
  word-break: break-all;
}
.ctx-item {
  display: flex;
  gap: 12px;
  justify-content: space-between;
  padding: 6px 10px;
  border-radius: 4px;
  color: var(--text-1);
  white-space: nowrap;
  cursor: pointer;
}
.ctx-item:hover {
  background: var(--accent-soft, rgb(64 158 255 / 12%));
}
.ctx-item.danger {
  color: var(--danger, #f56c6c);
}
.ctx-arrow {
  color: var(--text-3);
}
.ctx-sub {
  display: none;
  position: absolute;
  left: 100%;
  top: -5px;
  min-width: 160px;
  padding: 4px;
  background: var(--bg, #fff);
  border: 1px solid var(--border, #ebeef5);
  border-radius: 6px;
  box-shadow: 0 6px 20px rgb(0 0 0 / 18%);
}
.ctx-has-sub:hover > .ctx-sub {
  display: block;
}
.canvas-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  pointer-events: none;
  color: var(--text-3);
}
.canvas-empty-title {
  font-size: 14px;
}
.canvas-empty-hint {
  font-size: 12px;
}
.props-empty {
  width: 300px;
  flex: 0 0 300px;
  border-left: 1px solid var(--border, #ebeef5);
  padding: 16px;
  font-size: 12px;
  color: var(--text-3);
}
</style>
