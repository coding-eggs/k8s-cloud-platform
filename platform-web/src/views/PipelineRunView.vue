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
import type { Pipeline, PipelineCanvas, PipelineRun, PipelineStepRef } from '@/types'
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

/** run 的 `stepsJson` 是 `节点=状态` 逗号分隔（归档时抓的快照）—— 键是**节点 id** */
function statusMap(run: PipelineRun | null): Record<string, string> {
  const out: Record<string, string> = {}
  for (const part of (run?.stepsJson ?? '').split(',')) {
    const [k, v] = part.split('=')
    if (k && v) out[k] = v
  }
  return out
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
      position: { x: 60 + (i % 3) * 200, y: 40 + Math.floor(i / 3) * 110 },
      data: { nodeType: n.type, name: n.name ?? '', config: n.config, status: st[n.id] },
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
          <span v-if="selected.reason" class="dim">{{ selected.reason }}</span>
          <div class="spacer" />
          <el-button link type="primary" @click="openYaml">渲染出的 YAML</el-button>
        </div>

        <div v-if="selected.status === 'RUNNING'" class="dim">
          正在跑 —— 每 5 秒自动刷新节点状态。归档后集群对象会被删，历史从这里读。
        </div>
        <div v-if="canvasIsCurrent" class="dim">
          （这条运行没有留下当时的画布，下面画的是「当前」的流水线定义）
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
