<script setup lang="ts">
/**
 * **一次运行的详情**（独立页面）。
 *
 * 原先它是运行历史列表右侧那 560px 的一栏 —— DAG 与日志都被挤在一个小框里，看着不方便
 * （2026-10-16 用户的反馈），所以拆成自己的路由：`/pipelines/run?name=<运行名>&id=<流水线 id>`。
 * 顺带一个好处：这一页的 URL 可以直接发给别人（运行名本身就是唯一标识）。
 *
 * ⚠️ 数据源仍是「归档了没有」那条分界线（spec §4.3）：历史一律读库 ——
 * 集群里的 `PipelineRun` 与它的 Pod 在归档那一刻就被删了（这正是「k8s 不留 Completed Pod」的做法）。
 * 只有**还在跑**的那一条，日志与节点落点才是现抓的。
 *
 * 详情里的 DAG 画的是**这条运行自己的画布**（触发时留在 run 行上的 `canvasJson`），
 * 不是今天的流水线定义 —— 改过名字、换过镜像之后，拿今天那份画出来的图会把排障的人带沟里。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FullScreen, ScaleToOriginal } from '@element-plus/icons-vue'
import { MarkerType, VueFlow } from '@vue-flow/core'
import type { Edge, Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { pipelineApi } from '@/api'
import type { Pipeline, PipelineCanvas, PipelineCanvasEdge, PipelineCanvasNode, PipelineRun, PipelineStepRef } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useResourceContext } from '@/stores/context'
import { usePipelineNodeTypes } from '@/composables/usePipelineNodeTypes'
import { fmtDate } from '@/utils/format'
import YamlBlock from '@/components/YamlBlock.vue'
import PipelineNodeVue from '@/components/pipeline/PipelineNodeVue.vue'

const route = useRoute()
const router = useRouter()
const perm = usePermission()
/** 节点清单（图标 / 类型名 / 配色）—— 详情里画的是同一套卡片 */
const { load: loadNodeTypes } = usePipelineNodeTypes()

/** 生效租户：走 k8s-server 的那几个端点都要它（平台管理员代管轨必须显式给） */
const { state: resourceContext } = useResourceContext()
const effectiveTenantId = computed(() => resourceContext.tenantId ?? undefined)

const runName = computed(() => (route.query.name as string) || '')
const pipelineId = computed(() => (route.query.id as string) || '')

const run = ref<PipelineRun | null>(null)
/** 只在「这条运行没留下画布」时用得上（退回画当前的定义，并在界面上明说） */
const pipeline = ref<Pipeline | null>(null)
const logs = ref('')
/** 当前在看的那个节点 —— 日志区标题与节点高亮都看它（用户 2026-10-16：不要下面那排 tab） */
const logNodeId = ref('')
const loading = ref(false)
/** 还在跑时现抓的节点落点（归档后它就没有了 —— 那时状态在 run 行的 stepsJson 里） */
const liveSteps = ref<PipelineStepRef[]>([])
const runYamlText = ref('')
const showYaml = ref(false)
const yamlLoading = ref(false)

const canRun = computed(() => perm.has(apiCodes.pipelineRun))
const canLogs = computed(() => perm.has(apiCodes.pipelineLogs))
/** 删除要的是**改流水线**那个码（后端 `/pipeline/run/delete` 用的就是它） */
const canManage = computed(() => perm.has(apiCodes.pipelineManage))
const isRunning = computed(() => run.value?.status === 'RUNNING')

/** 失败/超时才把 reason 当报错显示（取消是用户自己点的，Succeeded 更不是报错） */
const failed = computed(() => run.value?.status === 'FAILED' || run.value?.status === 'TIMEOUT')

/** 回列表：**带上流水线 id**，否则列表页不知道查哪一条流水线的历史 */
function backToList(): void {
  const id = run.value?.pipelineId || pipelineId.value
  void router.push({ path: '/pipelines/runs', query: id ? { id } : {} })
}

// ---- 状态键 ----

/**
 * 一次运行里每个节点的状态。两个来源，**键都归一化成画布节点 id**：
 * 归档后读 `stepsJson`，还在跑读现抓的落点。
 */
const nodeStatus = computed<Record<string, string>>(() => {
  const out = statusMap(run.value)
  for (const s of liveSteps.value) out[s.nodeName] = s.status
  return out
})

/** run 的 `stepsJson` 是 `节点=状态` 逗号分隔（归档时抓的快照）—— 键是**节点 id** */
function statusMap(r: PipelineRun | null): Record<string, string> {
  const out: Record<string, string> = {}
  for (const part of (r?.stepsJson ?? '').split(',')) {
    const [k, v] = part.split('=')
    if (k && v) out[nodeIdOf(k, r?.runName)] = v
  }
  return out
}

/**
 * 快照里的键 → **画布节点 id**。
 *
 * ⚠️ 两种键都认：新的是画布节点 id；2026-10-16 之前归档的是 **TaskRun 名**
 * （`<运行名>-<节点 id>`）—— 那时后端把节点名取成了 TaskRun 名（根因见
 * `PipelineRunOperations.nodeNameOf`）。不认老键的话那些历史运行**所有节点都没状态**。
 */
function nodeIdOf(stored: string, name?: string): string {
  const prefix = name ? `${name}-` : ''
  return prefix && stored.startsWith(prefix) ? stored.slice(prefix.length) : stored
}

function parseCanvas(json?: string | null): PipelineCanvas | null {
  if (!json) return null
  try {
    return JSON.parse(json) as PipelineCanvas
  } catch {
    return null
  }
}

/** 这条运行自己的画布（触发时留下的那一份） */
const runCanvas = computed<PipelineCanvas | null>(() => parseCanvas(run.value?.canvasJson))
/** 老运行（本功能上线前触发的）没留下画布 —— 退回画当前定义，并在界面上**明说** */
const canvasIsCurrent = computed(() => !runCanvas.value && !!pipeline.value?.canvasJson)
const canvas = computed<PipelineCanvas | null>(
  () => runCanvas.value ?? parseCanvas(pipeline.value?.canvasJson),
)

/** DAG：画布 + 每个节点的状态（状态按节点 **id** 对） */
const dag = computed<{ nodes: Node[]; edges: Edge[] }>(() => {
  const c = canvas.value
  if (!c) return { nodes: [], edges: [] }
  const st = nodeStatus.value
  return {
    nodes: (c.nodes ?? []).map((n, i) => ({
      id: n.id,
      type: 'pipeline',
      // 用**当时那张画布**里的坐标 —— 用户在编辑器里怎么摆的，这里就怎么画；
      // 老画布没存坐标才退回网格
      position: n.position
        ? { x: n.position.x, y: n.position.y }
        : { x: 60 + (i % 3) * 200, y: 40 + Math.floor(i / 3) * 110 },
      data: {
        nodeType: n.type,
        name: n.name ?? '',
        config: n.config,
        // 收尾标记在运行详情里也要看得出来（它与节点状态无关，是画布属性）
        finallyTask: n.finallyTask === true,
        status: st[n.id],
        // 正在看它的日志 —— 卡片描边跟着亮，免得"日志区换了但不知道换成了谁"
        selected: n.id === logNodeId.value,
      },
    })),
    edges: (c.edges ?? []).map((e) => ({
      id: `e-${e.from}-${e.to}`,
      source: e.from,
      target: e.to,
      type: 'smoothstep',
      markerEnd: MarkerType.ArrowClosed,
      style: { stroke: 'var(--border-strong)', strokeWidth: 1.5 },
    })),
  }
})

/**
 * 日志区标题上那个名字：用**用户起的名字**（与卡片、跳过说明同一套），没起名才退回 id。
 */
const logNodeLabel = computed(() => {
  const n = (canvas.value?.nodes ?? []).find((x) => x.id === logNodeId.value)
  return n ? (n.name ?? '').trim() || n.id : logNodeId.value
})

/**
 * 被跳过的节点 —— **光有「已跳过」三个字不够，得说清楚为什么不跑**。
 *
 * ⚠️ 为什么「为什么」是**推出来**的，而不是读 Tekton 的原话：归档之后每个节点的状态只剩
 * `stepsJson` 里的 `节点=状态` 那几对（**没有原因那一栏**），而绝大多数时候看的是归档后的运行。
 * 好在两条口径都在我们自己的数据里 —— 跳过的原因只有两类（前面没成功 / 运行提前结束），
 * 而「这个节点是不是收尾、时机选的是哪个」就写在**这条运行当年那张画布**上。
 *
 * ⚠️ Tekton 的原话**不能拿来当判据**：真跑过的那条归档 YAML 里写的是
 * `reason: "PipelineRun was stopping"` —— 既不是文档里的 `Parent Tasks had failed`，也不像人话。
 * 原话仍在时（还在跑、现抓得到）挂到 `title` 上，不丢。
 */
const skippedNodes = computed(() => {
  const c = canvas.value
  if (!c) return []
  const st = nodeStatus.value
  const raw = new Map(liveSteps.value.map((s) => [s.nodeName, s.message ?? '']))
  const edges = c.edges ?? []
  return (c.nodes ?? [])
    .filter((n) => st[n.id] === 'SKIPPED')
    .map((n) => ({
      id: n.id,
      label: (n.name ?? '').trim() || n.id,
      why: skipWhy(n, st, edges),
      raw: raw.get(n.id) ?? '',
    }))
})

/**
 * 一个节点的**传递上游**（沿连线反向找祖先，去重）。
 *
 * <p>⚠️ 只看直接上游不够：`A(失败) → B(被跳过) → C(被跳过)` 里 C 的直接上游是 B，
 * 而"为什么"要落到 A 失败这件事上 —— 所以要把整条链都找出来。
 */
function ancestorsOf(edges: PipelineCanvasEdge[], id: string): string[] {
  const up = new Map<string, string[]>()
  for (const e of edges) {
    const list = up.get(e.to)
    if (list) list.push(e.from)
    else up.set(e.to, [e.from])
  }
  const seen = new Set<string>()
  const stack = [...(up.get(id) ?? [])]
  while (stack.length) {
    const cur = stack.pop()!
    if (seen.has(cur)) continue
    seen.add(cur)
    stack.push(...(up.get(cur) ?? []))
  }
  return [...seen]
}

/**
 * 跳过的两种情形 —— 与后端 `PipelineRunConverter.SKIPPED` 的说明成对。
 *
 * <p>⚠️ 2026-10-16 用户报的形态（fork 两条分支）：
 * `git(成功) → auth-build(失败) → auth-image(跳过)` 与
 * `git(成功) → platform-web(成功) → platform-web-image(跳过)`。
 * `platform-web-image` 被跳过时屏幕上写的是「上游没成功」——**它的上游明明成功了**，那句话是假的。
 *
 * <p>真因是 Tekton 的语义：**任何没勾「失败后继续」的节点失败，整条运行就停下**，
 * 还没开始的节点一律跳过（`reason: PipelineRun was stopping`）—— 它不会"只停自己那条分支"。
 * 所以说法必须按**自己的传递上游**分：
 * <ul>
 *   <li>自己的上游里有失败 →「上游没成功」（这条路径确实断在自己前面）；</li>
 *   <li>自己的上游全成功、但**别处**有失败 →「别的分支失败了，整条运行提前结束」；</li>
 *   <li>一个节点都没失败 → 取消/超时导致（见下）。</li>
 * </ul>
 */
function skipWhy(
  n: PipelineCanvasNode,
  st: Record<string, string>,
  edges: PipelineCanvasEdge[],
): string {
  if (n.finallyTask === true) {
    const when = n.finallyWhen ?? 'always'
    if (when === 'failed') return '收尾时机是「仅前面有节点失败时」，这次前面是成功的'
    if (when === 'succeeded') return '收尾时机是「仅前面全部成功时」，这次前面有节点失败了'
    // when=always 却还被跳过，只可能是运行提前结束了（取消/超时）
    return '运行提前结束，没轮到它'
  }
  // ⚠️ 只认 **FAILED**，不把被跳过的上游也算进来：上游"被跳过"往往也是别处的失败造成的，
  //    把它算成"自己的上游没成功"就又回到了那条假话
  const upstreamFailed = ancestorsOf(edges, n.id).some((id) => st[id] === 'FAILED')
  if (upstreamFailed) {
    return '上游没成功，跟着被跳过'
  }
  const anyFailed = Object.values(st).some((s) => s === 'FAILED')
  return anyFailed
    ? '别的分支失败了，整条运行提前结束，没轮到它'
    : '运行提前结束（取消或超时），没轮到它'
}

/**
 * 日志全屏：把日志块变成**铺满窗口的浮层**（用户 2026-10-16 要的）。
 *
 * <p>⚠️ 有意用 `position: fixed` 的浮层，而不是浏览器的 Fullscreen API：这一页大多数时候在
 * 应用窗口里读，铺满窗口就是想要的效果；而真正的全屏要处理权限、Esc 与状态同步三件事，
 * 在嵌入式窗口里还可能直接被拒。Esc 与按钮都能退出。
 */
const logFullscreen = ref(false)

function toggleLogFullscreen(): void {
  logFullscreen.value = !logFullscreen.value
}

/** Esc 退出 —— 读长日志时手在键盘上，不该为了退出把鼠标挪回去 */
function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape' && logFullscreen.value) {
    logFullscreen.value = false
  }
}

const STATUS_TAG: Record<string, string> = {
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
  TIMEOUT: 'warning',
  RUNNING: 'primary',
}

/**
 * 展示用的日志文本：**把回车（`\r`）也当成换行**。
 *
 * <p>⚠️ 真跑撞到（2026-10-16 用户报「部分日志不换行」）：`git clone` 的进度输出是
 * `Updating files: 15%…\rUpdating files: 16%…\r…` —— `\r` 是「回到行首覆盖」，
 * **在真终端里它不换行**。而浏览器在 `<pre>` 里**直接把 `\r` 丢掉**（实测：
 * `'a\rb\rc\nd'` 只渲染出 2 行，不是 4 行），于是几十段进度被粘成一行，
 * 看起来就是「这段日志不换行」。（真数据：git 那个节点的日志里 90 个 `\r`、只有 2 个 `\n`。）
 *
 * <p>这里选择**换行**，而不是「按终端的覆盖语义把中间态折叠掉」（后者只剩最后一行、
 * 读起来更干净，但会丢内容）—— 日志宁可啰嗦，也别悄悄少东西。
 * ⚠️ 只在展示这一层做：库里存的仍是原始字节（那是它本来的样子，与终端一致）。
 */
const logText = computed(() => (logs.value ?? '').replace(/\r\n?/g, '\n'))

function tagType(s: string): string {
  return STATUS_TAG[s] ?? 'info'
}

// ---- 载入与刷新 ----

async function load(): Promise<void> {
  if (!runName.value) return
  loading.value = true
  try {
    run.value = await pipelineApi.runGet(runName.value)
  } catch {
    run.value = null
  } finally {
    loading.value = false
  }
}

/** 一条运行的完整初始化：取详情（+ 需要时取流水线定义）、起轮询、拉一次日志 */
async function init(): Promise<void> {
  // ⚠️ 也要拉一次节点清单：直接打开这一页（没先进过编辑器）时那份单例缓存是空的，
  // 卡片会退化成通用图标 + 原始 type（`git`），看着像"没名字"
  loadNodeTypes()
  await load()
  // 「没留下画布」时要退回画当前的定义 —— 得先把流水线定义取回来
  const id = run.value?.pipelineId || pipelineId.value
  if (id && !run.value?.canvasJson) {
    pipeline.value = await pipelineApi.get(id).catch(() => null)
  }
  if (isRunning.value) startPolling()
  // ⚠️ 这里**不再预拉日志**：日志按节点看，而"看哪个节点"由用户点（下面那排 tab 已经去掉）
}

/**
 * ⚠️ `?name=` 变了要**重新初始化**：同一条路由只换 query 时组件**不会重挂**，
 * 不看着它就会继续显示**上一条运行**的数据（最迷惑的那种错）——
 * 而这一页的卖点正是「URL 可以直接改/分享」，所以这条路真会被走到。
 */
watch(runName, async () => {
  stopPolling()
  run.value = null
  logs.value = ''
  logNodeId.value = ''
  liveSteps.value = []
  runYamlText.value = ''
  await init()
})

onMounted(init)

onUnmounted(() => {
  stopPolling()
  window.removeEventListener('keydown', onKeydown)
})

// ⚠️ 常驻注册（不是只在全屏时注册）：处理函数自己看 `logFullscreen` 那个开关，
//    不生效时按 Esc 什么也不做；换来的是不用管"什么时候加/什么时候摘"这条容易漏的分支
window.addEventListener('keydown', onKeydown)

// ---- 还在跑的运行：每 5 秒对一次集群 ----

let timer: number | undefined

/**
 * 还在跑的运行要**有颜色**：节点状态只有 k8s-server 现抓才知道（归档才写 stepsJson）。
 * 同时顺手看一眼它终结了没有 —— 终结后 watch 会归档它，状态与 stepsJson 就都落库了。
 */
function startPolling(): void {
  stopPolling()
  timer = window.setInterval(refreshRunning, 5000)
}

function stopPolling(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function refreshRunning(): Promise<void> {
  const r = run.value
  if (!r || r.status !== 'RUNNING') {
    stopPolling()
    return
  }
  try {
    const [steps, fresh] = await Promise.all([
      pipelineApi.runSteps(r.runName, effectiveTenantId.value),
      pipelineApi.runGet(r.runName),
    ])
    if (run.value?.runName !== r.runName) return // 期间换了运行 → 丢弃
    liveSteps.value = steps ?? []
    if (fresh) run.value = fresh
    if (run.value?.status !== 'RUNNING') {
      // 归档了：现抓的落点已经没了（Pod 被删），状态改由 stepsJson 提供
      liveSteps.value = []
      stopPolling()
    }
  } catch {
    /* 拦截器已提示；下一次 tick 再来 */
  }
}

/** 取消：**先抓一次日志再取消**（见后端 cancel 的说明），所以取消之后日志仍然看得到 */
async function cancel(): Promise<void> {
  const r = run.value
  if (!r) return
  await ElMessageBox.confirm(
    '取消这次运行？会先抓一次日志再取消 —— 所以取消之后日志仍然看得到。',
    '确认',
    { type: 'warning' },
  )
  try {
    await pipelineApi.cancelRun(r.runName, effectiveTenantId.value)
    ElMessage.success('已取消')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

/**
 * 删掉这条运行记录，然后回列表（留着这一页没有意义：它查的那条已经不存在了）。
 *
 * <p>⚠️ **不可恢复**：归档那一刻集群里的对象与 Pod 就删了，库里的这一行是它的日志、
 * YAML 与节点状态的**唯一留存**。还在跑的那条后端会拒（先取消），所以这里对它不显示这个按钮。
 */
async function removeRun(): Promise<void> {
  const r = run.value
  if (!r) return
  // ⚠️ 运行名放**标题**、不放正文：ElMessageBox 的正文是纯文本，`\n` 会被折成空格
  await ElMessageBox.confirm(
    '删掉这条运行记录？不可恢复 —— 归档时集群里那个对象就已经删了，这行是它的日志与 YAML 的唯一留存。',
    `删除 ${r.runName}`,
    { type: 'warning', confirmButtonText: '删除' },
  )
  try {
    await pipelineApi.runDelete(r.runName)
    ElMessage.success('已删除')
    backToList()
  } catch {
    /* 拦截器已提示 */
  }
}

/**
 * 看这条运行**渲染出来的 YAML**（spec §6 的排查利器）。
 *
 * 还在跑 → 集群里那个真实对象；已归档 → 归档那一刻抓下来的那一份（对象早删了）。
 * 老运行没留下 → 面板里明说「没有留下」，**不**拿今天的定义编一份出来。
 */
async function openYaml(): Promise<void> {
  if (!run.value) return
  showYaml.value = true
  if (runYamlText.value) return // 同一条运行不重复拉
  yamlLoading.value = true
  try {
    runYamlText.value = (await pipelineApi.runYaml(run.value.runName, effectiveTenantId.value)) ?? ''
  } catch {
    runYamlText.value = ''
  } finally {
    yamlLoading.value = false
  }
}

/**
 * 看**某个节点**的日志。点上面流程里的节点就是它（用户 2026-10-16 定的形态：
 * 不要「全部」那排 tab，日志区只用标题说明当前看的是谁）。
 *
 * <p>⚠️ 跳过的节点**没有日志**（它没有 Pod）：与其回一句「（没有日志）」让人以为日志丢了，
 * 不如把「为什么没跑」原地说一遍。
 */
async function loadLogs(nodeId: string): Promise<void> {
  const r = run.value
  if (!r || !nodeId) return
  logNodeId.value = nodeId
  const skipped = skippedNodes.value.find((s) => s.id === nodeId)
  if (skipped) {
    logs.value = `这个节点被跳过了，没有日志：${skipped.why}`
    return
  }
  try {
    const text = await pipelineApi.runLogs(r.runName, nodeId, effectiveTenantId.value)
    // 期间又点了别的节点 → 丢掉这次的结果，免得标题与内容对不上
    if (logNodeId.value === nodeId) logs.value = text
  } catch {
    if (logNodeId.value === nodeId) logs.value = ''
  }
}
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="bar">
      <!-- 回列表：详情拆出来之后必须有它（否则只能靠浏览器后退） -->
      <el-button link type="primary" @click="backToList">← 运行历史</el-button>
      <span class="run-name">{{ runName }}</span>
      <el-tag v-if="run" :type="tagType(run.status)" size="small">{{ run.status }}</el-tag>
      <span class="dim">
        {{ fmtDate(run?.startTime) }} → {{ run?.completionTime ? fmtDate(run.completionTime) : '（未完）' }}
      </span>
      <div class="spacer" />
      <el-button v-if="canRun && isRunning" type="danger" plain @click="cancel">取消</el-button>
      <el-button @click="openYaml">渲染出的 YAML</el-button>
      <!-- 删除：**已结束**的运行才给（还在跑的先取消 —— 后端也拒） -->
      <el-button v-if="canManage && !isRunning" type="danger" plain @click="removeRun">删除</el-button>
    </div>

    <div v-if="!run && !loading" class="dim empty">没有这条运行（运行名：{{ runName || '（空）' }}）</div>

    <template v-if="run">
      <!-- ⚠️ 失败原因必须显眼：Tekton 顶层只会给一句 Failed（统计口径），
           真正的报错是后端从**失败那个节点**的 TaskRun 上带下来的原话 -->
      <el-alert v-if="failed && run.reason" type="error" :closable="false" show-icon :title="run.reason" />

      <div v-if="isRunning" class="dim">
        正在跑 —— 每 5 秒自动刷新节点状态。归档后集群对象会被删，历史从这里读。
      </div>
      <div v-if="canvasIsCurrent" class="dim">
        （这条运行没有留下当时的画布，下面画的是「当前」的流水线定义）
      </div>

      <!-- 跳过的节点：画布上那三个字说不清「为什么不跑」，这里逐条讲 -->
      <div v-if="skippedNodes.length" class="skipped">
        <span class="skipped-head">已跳过</span>
        <span v-for="s in skippedNodes" :key="s.id" class="skipped-item" :title="s.raw">
          {{ s.label }}（{{ s.why }}）
        </span>
      </div>

      <!-- DAG：**撑满剩下的上半屏** —— 详情独立成页就是为了让它别再挤在一个小框里。
           ⭐ 点节点 = 看那个节点的日志（用户 2026-10-16 定的形态：不要下面那排 tab） -->
      <div class="dag">
        <VueFlow :nodes="dag.nodes" :edges="dag.edges" fit-view-on-init
                 :nodes-draggable="false" :edges-updatable="false"
                 @node-click="(e: { node: { id: string } }) => loadLogs(e.node.id)">
          <template #node-pipeline="nodeProps">
            <PipelineNodeVue :id="nodeProps.id" :data="nodeProps.data" />
          </template>
        </VueFlow>
      </div>

      <!-- 日志区只剩一行标题（当前看的是哪个节点）—— 节点在上面点，不再罗列一排 tab。
           全屏 = 这一块变成铺满窗口的浮层（Esc 或按钮退出） -->
      <div class="log-panel" :class="{ full: logFullscreen }">
        <div class="log-head">
          <span>日志</span>
          <span v-if="logNodeId" class="log-node">{{ logNodeLabel }}</span>
          <span v-else class="dim">点上面的节点看它的日志</span>
          <span v-if="!canLogs" class="dim">（没有『看运行日志』权限）</span>
          <div class="spacer" />
          <el-button link :icon="logFullscreen ? ScaleToOriginal : FullScreen" @click="toggleLogFullscreen">
            {{ logFullscreen ? '退出全屏' : '全屏' }}
          </el-button>
        </div>
        <pre class="log">{{ logText || (logNodeId ? '（没有日志）' : '') }}</pre>
      </div>
    </template>

    <el-dialog v-model="showYaml" title="这次运行渲染出来的 PipelineRun" width="70%">
      <div v-if="yamlLoading" class="dim">正在取……</div>
      <template v-else>
        <div class="dim yaml-note">
          <template v-if="isRunning">
            还在跑：下面是集群里那个真实对象现在的样子（含 workspace 绑定与凭据 Secret 名）。
            归档之后对象会被删，届时显示的是归档那一刻抓下来的那一份。
          </template>
          <template v-else>
            对象在归档时已被删除 —— 下面是归档那一刻抓下来的那一份（当年跑的到底是什么）。
          </template>
        </div>
        <YamlBlock v-if="runYamlText" :yaml="runYamlText" max-height="60vh" />
        <div v-else class="dim yaml-note">
          这条运行没有留下 YAML —— 它跑在「归档时抓 YAML」这个功能上线之前。
          （这里不会拿今天的流水线定义重新渲染一份出来充数。）
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
}
.bar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.run-name {
  font-weight: 600;
  word-break: break-all;
}
.spacer {
  flex: 1;
}
.dim {
  color: var(--text-3);
}
.empty {
  padding: 20px 0;
}
/* 跳过的节点：一条一行地列出来（画布上的节点太小，写不下原因） */
.skipped {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
  font-size: 12px;
  color: var(--text-2);
}
.skipped-head {
  color: var(--text-3);
}
.skipped-item {
  padding: 1px 8px;
  border: 1px dashed var(--border-strong);
  border-radius: 999px;
}
/* ⭐ 详情独立成页的意义就在这里：DAG 与日志**各自撑开**，跟着窗口长
   （原先挤在 560px 的一栏里，DAG 只有 220px 高） */
.dag {
  flex: 1 1 45%;
  min-height: 320px;
  border: 1px solid var(--border, #ebeef5);
  border-radius: 4px;
}
/* 日志块：平时占下半屏（标题 + 内容），全屏时变成铺满窗口的浮层 */
.log-panel {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1 1 55%;
  min-height: 240px;
}
.log-panel.full {
  position: fixed;
  inset: 0;
  z-index: 2000;
  padding: 12px 16px;
  /* 必须有底色：不然浮层底下那一页会透上来（字叠字完全没法读） */
  background: var(--bg);
}
.log-head {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  flex-wrap: wrap;
}
/* 标题上那个名字：当前看的是哪个节点（用户 2026-10-16：不要那排 tab，就用这一行说清） */
.log-head .log-node {
  color: var(--text-1);
  font-weight: 600;
}
/* 详情里的节点是**可点的**（点它看日志）—— 不只手型，光标本身就是提示 */
.dag :deep(.vue-flow__node) {
  cursor: pointer;
}
.log {
  /* 高度由**父容器**（.log-panel）决定：这里必须能缩能长，`min-height: 0` 是让它别顶破浮层 */
  flex: 1 1 auto;
  min-height: 0;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--border, #ebeef5);
  border-radius: 4px;
  /* 与老视图那一栏里的日志区同一套颜色（底色用应用底色，字用主文本色 —— 对比度最高的一档） */
  background: var(--bg);
  color: var(--text-1);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.yaml-note {
  margin-bottom: 8px;
  line-height: 1.6;
}
</style>
