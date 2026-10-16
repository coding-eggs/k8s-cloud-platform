<script setup lang="ts">
/**
 * 运行历史与运行详情。
 *
 * ⚠️ **数据源是「归档了没有」的分界线**（spec §4.3）：历史一律读库 ——
 * 集群里的 `PipelineRun` 与它的 Pod 在归档那一刻就被删了（这正是「k8s 不留 Completed Pod」的做法）。
 * 只有**还在跑**的那一条，日志才是现抓的。
 *
 * 详情里的 DAG 画的是**这条运行自己的画布**（触发时留在 run 行上的 `canvasJson`），
 * 不是今天的流水线定义 —— 改过名字、换过镜像之后，拿今天那份画出来的图会把排障的人带沟里。
 * 还在跑的运行每 5 秒对一次集群拿节点状态（归档后那状态就没了，改读归档快照 `stepsJson`）。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MarkerType, VueFlow } from '@vue-flow/core'
import type { Edge, Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { pipelineApi } from '@/api'
import type { Pipeline, PipelineCanvas, PipelineCanvasNode, PipelineRun, PipelineStepRef } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useResourceContext } from '@/stores/context'
import { usePipelineNodeTypes } from '@/composables/usePipelineNodeTypes'
import YamlBlock from '@/components/YamlBlock.vue'
import PipelineNodeVue from '@/components/pipeline/PipelineNodeVue.vue'

const route = useRoute()
const router = useRouter()
const perm = usePermission()
/** 节点清单（图标 / 类型名 / 配色）—— 运行详情里画的是同一套卡片 */
const { load: loadNodeTypes } = usePipelineNodeTypes()

/** 生效租户：走 k8s-server 的那几个端点都要它（平台管理员代管轨必须显式给） */
const { state: resourceContext } = useResourceContext()
const effectiveTenantId = computed(() => resourceContext.tenantId ?? undefined)

const pipelineId = computed(() => (route.query.id as string) || '')
const pipeline = ref<Pipeline | null>(null)
const runs = ref<PipelineRun[]>([])
const selected = ref<PipelineRun | null>(null)
const logs = ref('')
const logNode = ref('')
const loading = ref(false)
/** 还在跑时现抓的节点落点（归档后它就没有了 —— 那时状态在 run 行的 stepsJson 里） */
const liveSteps = ref<PipelineStepRef[]>([])
const runYamlText = ref('')
const showYaml = ref(false)
const yamlLoading = ref(false)

const canRun = computed(() => perm.has(apiCodes.pipelineRun))
const canLogs = computed(() => perm.has(apiCodes.pipelineLogs))

/** 失败/超时才把 reason 当报错显示（取消是用户自己点的，Succeeded 更不是报错） */
const failed = computed(
  () => selected.value?.status === 'FAILED' || selected.value?.status === 'TIMEOUT',
)

/**
 * run 的 `stepsJson` 是 `节点=状态` 逗号分隔（归档时抓的快照）—— 键是**节点 id**。
 */
function statusMap(run: PipelineRun | null): Record<string, string> {
  const out: Record<string, string> = {}
  for (const part of (run?.stepsJson ?? '').split(',')) {
    const [k, v] = part.split('=')
    if (k && v) out[nodeIdOf(k, run?.runName)] = v
  }
  return out
}

/**
 * 快照里的键 → **画布节点 id**。
 *
 * <p>⚠️ 两种键都认：新的是画布节点 id；2026-10-16 之前归档的是 **TaskRun 名**
 * （`<运行名>-<节点 id>`）—— 那时后端把节点名取成了 TaskRun 名（根因见
 * `PipelineRunOperations.nodeNameOf`），于是老运行的状态键与画布节点 id 对不上，
 * 运行详情里**所有节点都没有状态**。不认老键的话这些历史运行永远不着色。
 */
function nodeIdOf(stored: string, runName?: string): string {
  const prefix = runName ? `${runName}-` : ''
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

/**
 * 画布：**这条运行自己的那一份**（触发时留下的 `canvasJson`）。
 *
 * ⚠️ 不能画「今天的流水线定义」：改过名字、换过镜像、甚至删掉过节点之后，
 * 那样画出来的图与当年跑的对不上 —— 排障时会被彻底误导（这正是留下 canvasJson 的理由）。
 */
const runCanvas = computed<PipelineCanvas | null>(() => parseCanvas(selected.value?.canvasJson))

/** 老运行（本功能上线前触发的）没留下画布 —— 退回画当前定义，并在界面上**明说** */
const canvasIsCurrent = computed(() => !runCanvas.value && !!pipeline.value?.canvasJson)
const canvas = computed<PipelineCanvas | null>(
  () => runCanvas.value ?? parseCanvas(pipeline.value?.canvasJson),
)

/** 每个节点的状态：归档后看快照（stepsJson），还在跑看现抓的落点 —— 两者键都是节点 id */
const nodeStatus = computed<Record<string, string>>(() => {
  const out = statusMap(selected.value)
  for (const s of liveSteps.value) out[s.nodeName] = s.status
  return out
})

/** 详情里的 DAG：画布 + 每个节点的状态（状态按节点 **id** 对） */
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

/** 日志按钮：**显示名字，过滤仍按 id**（日志在库里是按 id 存的，名字只是给人看的） */
const logTargets = computed(() =>
  (canvas.value?.nodes ?? []).map((n) => ({ id: n.id, label: (n.name ?? '').trim() || n.id })),
)

/**
 * 被跳过的节点 —— **光有「已跳过」三个字不够，得说清楚为什么不跑**。
 *
 * <p>⚠️ 为什么这里的「为什么」是**推出来**的，而不是读 Tekton 的原话：归档之后每个节点的状态
 * 只剩 `stepsJson` 里的 `节点=状态` 那几对（**没有原因那一栏**），而运行详情绝大多数时候看的
 * 是归档后的运行。好在两条口径都在我们自己的数据里 —— 跳过的原因只有两类（前面没成功 /
 * 运行提前结束），而「这个节点是不是收尾、时机选的是哪个」就写在**这条运行当年那张画布**上
 * （`canvasJson` 是随运行存下来的，见 `skipWhy`）。
 *
 * <p>⚠️ Tekton 的原话**不能拿来当判据**：真跑过的那条（2026-10-15，git 失败 → 镜像节点被跳过）
 * 归档 YAML 里写的是 {@code reason: "PipelineRun was stopping"} —— 既不是文档里的
 * `Parent Tasks had failed`，也不像人话。原话仍在时（还在跑、现抓得到）挂到 `title` 上，不丢。
 */
const skippedNodes = computed(() => {
  const c = canvas.value
  if (!c) return []
  const st = nodeStatus.value
  const raw = new Map(liveSteps.value.map((s) => [s.nodeName, s.message ?? '']))
  const anyFailed = Object.values(st).some((s) => s === 'FAILED')
  return (c.nodes ?? [])
    .filter((n) => st[n.id] === 'SKIPPED')
    .map((n) => ({
      id: n.id,
      label: (n.name ?? '').trim() || n.id,
      why: skipWhy(n, anyFailed),
      raw: raw.get(n.id) ?? '',
    }))
})

/**
 * 跳过的两种情形 —— 与后端 `PipelineRunConverter.SKIPPED` 的说明成对：
 * 前面没成功被跟着跳过；或收尾节点的执行时机不满足。
 *
 * @param anyFailed 这一趟里有没有节点失败 —— 有失败就是「跟着被跳过」；
 *                  一个都没失败（取消/超时结束的运行）就是「没轮到它」
 */
function skipWhy(n: PipelineCanvasNode, anyFailed: boolean): string {
  if (n.finallyTask === true) {
    const when = n.finallyWhen ?? 'always'
    if (when === 'failed') return '收尾时机是「仅前面有节点失败时」，这次前面是成功的'
    if (when === 'succeeded') return '收尾时机是「仅前面全部成功时」，这次前面有节点失败了'
    // when=always 却还被跳过，只可能是运行提前结束了（取消/超时）
    return '运行提前结束，没轮到它'
  }
  // tasks[] 里我们**不渲染任何 when**（Tekton 也不让引用执行状态），所以普通节点被跳过只有两种可能
  return anyFailed ? '上游没成功，跟着被跳过' : '运行提前结束（取消或超时），没轮到它'
}

const STATUS_TAG: Record<string, string> = {
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
  TIMEOUT: 'warning',
  RUNNING: 'primary',
}

function tagType(s: string): string {
  return STATUS_TAG[s] ?? 'info'
}

function duration(r: PipelineRun): string {
  if (!r.startTime || !r.completionTime) return '—'
  const ms = new Date(r.completionTime).getTime() - new Date(r.startTime).getTime()
  return ms > 0 ? `${Math.round(ms / 1000)}s` : '—'
}

async function load() {
  if (!pipelineId.value) return
  loading.value = true
  try {
    pipeline.value = await pipelineApi.get(pipelineId.value)
    runs.value = await pipelineApi.runs(pipelineId.value)
  } catch {
    runs.value = []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // ⚠️ 这里也要拉一次节点清单：直接打开运行页（没先进过编辑器）时那份单例缓存是空的，
  // 卡片会退化成通用图标 + 原始 type（`git`），看着像"没名字"
  loadNodeTypes()
  await load()
})

onUnmounted(stopPolling)

async function openRun(r: PipelineRun) {
  selected.value = r // 先用列表行把右栏填上，别让它空着
  logs.value = ''
  logNode.value = ''
  liveSteps.value = []
  runYamlText.value = ''
  stopPolling()
  // ⚠️ 列表行**不带**画布与 YAML（那是两个大字段，几十条历史不该每条都背着）—— 详情单独取一次
  try {
    const detail = await pipelineApi.runGet(r.runName)
    if (selected.value?.runName === r.runName && detail) selected.value = detail
  } catch {
    /* 拦截器已提示；退回列表行（画布退回当前定义） */
  }
  if (selected.value?.status === 'RUNNING') startPolling()
  if (canLogs.value) await loadLogs()
}

// ---- 还在跑的运行：每 5 秒对一次集群 ----

let timer: number | undefined

/**
 * 还在跑的运行要**有颜色**：节点状态只有 k8s-server 现抓才知道（归档才写 stepsJson）。
 * 同时顺手看一眼它终结了没有 —— 终结后 watch 会归档它，状态与 stepsJson 就都落库了。
 */
function startPolling() {
  stopPolling()
  timer = window.setInterval(refreshRunning, 5000)
}

function stopPolling() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function refreshRunning() {
  const r = selected.value
  if (!r || r.status !== 'RUNNING') {
    stopPolling()
    return
  }
  try {
    const [steps, fresh] = await Promise.all([
      pipelineApi.runSteps(r.runName, effectiveTenantId.value),
      pipelineApi.runGet(r.runName),
    ])
    if (selected.value?.runName !== r.runName) return // 期间换了选中的运行 → 丢弃
    liveSteps.value = steps ?? []
    if (fresh) selected.value = fresh
    if (selected.value?.status !== 'RUNNING') {
      // 归档了：现抓的落点已经没了（Pod 被删），状态改由 stepsJson 提供
      liveSteps.value = []
      stopPolling()
      void load() // 列表里那一行的状态也跟上
    }
  } catch {
    /* 拦截器已提示；下一次 tick 再来 */
  }
}

/**
 * 看这条运行**渲染出来的 YAML**（spec §6 的排查利器）。
 *
 * 还在跑 → 集群里那个真实对象；已归档 → 归档那一刻抓下来的那一份（对象早删了）。
 * 老运行没留下 → 面板里明说「没有留下」，**不**拿今天的定义编一份出来。
 */
async function openYaml() {
  if (!selected.value) return
  showYaml.value = true
  if (runYamlText.value) return // 同一条运行不重复拉
  yamlLoading.value = true
  try {
    runYamlText.value = (await pipelineApi.runYaml(selected.value.runName, effectiveTenantId.value)) ?? ''
  } catch {
    runYamlText.value = ''
  } finally {
    yamlLoading.value = false
  }
}

async function loadLogs(nodeName?: string) {
  if (!selected.value) return
  logNode.value = nodeName ?? ''
  // 跳过的节点**没有日志**（它没有 Pod）—— 与其回一句「（没有日志）」让人以为日志丢了，
  // 不如把「为什么没跑」原地说一遍
  if (nodeName) {
    const skipped = skippedNodes.value.find((s) => s.id === nodeName)
    if (skipped) {
      logs.value = `这个节点被跳过了，没有日志：${skipped.why}`
      return
    }
  }
  try {
    logs.value = await pipelineApi.runLogs(selected.value.runName, nodeName, effectiveTenantId.value)
  } catch {
    logs.value = ''
  }
}

async function run() {
  try {
    const runName = await pipelineApi.run(pipelineId.value, effectiveTenantId.value)
    ElMessage.success(`已触发：${runName}`)
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function cancel(r: PipelineRun) {
  await ElMessageBox.confirm(
    '取消这次运行？**会先抓一次日志再取消** —— 所以取消之后日志仍然看得到。',
    '确认',
    { type: 'warning' },
  )
  await pipelineApi.cancelRun(r.runName, effectiveTenantId.value)
  ElMessage.success('已取消')
  await load()
  // 取消后平台会归档，稍等一下再看那一条的状态
  setTimeout(load, 2000)
}
</script>

<template>
  <div class="page">
    <div class="bar">
      <el-button link type="primary" @click="router.push('/pipelines')">← 流水线</el-button>
      <span class="title">{{ pipeline?.name ?? '—' }}</span>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button v-if="canRun" type="primary" @click="run">运行</el-button>
    </div>

    <div class="body">
      <div class="runs">
        <el-table v-loading="loading" :data="runs" row-key="id" highlight-current-row
                  @current-change="(r: PipelineRun) => r && openRun(r)">
          <el-table-column label="运行" min-width="220">
            <template #default="{ row }">
              <span class="run-name">{{ row.runName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="tagType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="触发人" width="110" prop="triggeredBy" />
          <el-table-column label="耗时" width="80">
            <template #default="{ row }">{{ duration(row) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button v-if="canRun && row.status === 'RUNNING'" link type="danger" @click.stop="cancel(row)">
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="selected" class="detail">
        <div class="detail-head">
          <el-tag :type="tagType(selected.status)" size="small">{{ selected.status }}</el-tag>
          <span class="dim">{{ selected.startTime ?? '—' }} → {{ selected.completionTime ?? '（未完）' }}</span>
          <span v-if="selected.reason && !failed" class="dim">{{ selected.reason }}</span>
          <div class="spacer" />
          <el-button link type="primary" @click="openYaml">渲染出的 YAML</el-button>
        </div>

        <!-- ⚠️ 失败原因必须显眼：Tekton 顶层只会给一句 Failed（统计口径），
             真正的报错是后端的快照从**失败那个节点**的 TaskRun 上带下来的原话 -->
        <el-alert
          v-if="failed && selected.reason"
          type="error"
          :closable="false"
          show-icon
          :title="selected.reason"
        />

        <div v-if="selected.status === 'RUNNING'" class="dim">
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

        <div class="dag">
          <VueFlow :nodes="dag.nodes" :edges="dag.edges" fit-view-on-init
                   :nodes-draggable="false" :edges-updatable="false">
            <template #node-pipeline="nodeProps">
              <PipelineNodeVue :id="nodeProps.id" :data="nodeProps.data" />
            </template>
          </VueFlow>
        </div>

        <div class="log-head">
          <span>日志</span>
          <el-button link @click="loadLogs()">全部</el-button>
          <el-button v-for="t in logTargets" :key="t.id" link @click="loadLogs(t.id)">{{ t.label }}</el-button>
          <span v-if="!canLogs" class="dim">（没有『看运行日志』权限）</span>
        </div>
        <pre class="log">{{ logs || '（没有日志）' }}</pre>
      </div>
      <div v-else class="detail empty">选一条运行看它每个节点的状态与日志</div>
    </div>

    <el-dialog v-model="showYaml" title="这次运行渲染出来的 PipelineRun" width="70%">
      <div v-if="yamlLoading" class="dim">正在取……</div>
      <template v-else>
        <div class="dim yaml-note">
          <template v-if="selected?.status === 'RUNNING'">
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
.title {
  font-weight: 600;
}
.spacer {
  flex: 1;
}
.body {
  display: flex;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.runs {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
.detail {
  width: 560px;
  flex: 0 0 560px;
  border-left: 1px solid var(--border, #ebeef5);
  padding-left: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
}
.detail.empty {
  color: var(--text-3);
  font-size: 12px;
}
.detail-head {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
}
.yaml-note {
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 8px;
}
.dim {
  color: var(--text-3);
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
.dag {
  height: 220px;
  border: 1px solid var(--border, #ebeef5);
  border-radius: 4px;
}
.log-head {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  flex-wrap: wrap;
}
.log {
  flex: 1;
  margin: 0;
  min-height: 160px;
  max-height: 40vh;
  overflow: auto;
  background: var(--bg);
  color: var(--text-1);
  font-size: 12px;
  padding: 8px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}
.run-name {
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
