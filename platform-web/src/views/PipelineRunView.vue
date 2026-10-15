<script setup lang="ts">
/**
 * 运行历史与运行详情。
 *
 * ⚠️ **数据源是「归档了没有」的分界线**（spec §4.3）：历史一律读库 ——
 * 集群里的 `PipelineRun` 与它的 Pod 在归档那一刻就被删了（这正是「k8s 不留 Completed Pod」的做法）。
 * 只有**还在跑**的那一条，日志才是现抓的。
 *
 * 详情里的 DAG 直接复用画布（拿 pipeline 的 canvasJson 画），按每个节点的状态着色 ——
 * 归档后画布仍然在流水线定义里，所以历史运行也画得出来。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MarkerType, VueFlow } from '@vue-flow/core'
import type { Edge, Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { pipelineApi } from '@/api'
import type { Pipeline, PipelineCanvas, PipelineRun } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import PipelineNodeVue from '@/components/pipeline/PipelineNodeVue.vue'

const route = useRoute()
const router = useRouter()
const perm = usePermission()

const pipelineId = computed(() => (route.query.id as string) || '')
const pipeline = ref<Pipeline | null>(null)
const runs = ref<PipelineRun[]>([])
const selected = ref<PipelineRun | null>(null)
const logs = ref('')
const logNode = ref('')
const loading = ref(false)

const canRun = computed(() => perm.has(apiCodes.pipelineRun))
const canLogs = computed(() => perm.has(apiCodes.pipelineLogs))

/** run 的 `stepsJson` 是 `节点=状态` 逗号分隔（归档时抓的快照） */
function statusMap(run: PipelineRun | null): Record<string, string> {
  const out: Record<string, string> = {}
  for (const part of (run?.stepsJson ?? '').split(',')) {
    const [k, v] = part.split('=')
    if (k && v) out[k] = v
  }
  return out
}

/** 详情里的 DAG：画布 + 本次运行每个节点的状态 */
const dag = computed<{ nodes: Node[]; edges: Edge[] }>(() => {
  const c = pipeline.value?.canvasJson
  if (!c) return { nodes: [], edges: [] }
  let canvas: PipelineCanvas
  try {
    canvas = JSON.parse(c) as PipelineCanvas
  } catch {
    return { nodes: [], edges: [] }
  }
  const st = statusMap(selected.value)
  return {
    nodes: (canvas.nodes ?? []).map((n, i) => ({
      id: n.id,
      type: 'pipeline',
      position: { x: 60 + (i % 3) * 200, y: 40 + Math.floor(i / 3) * 110 },
      data: { nodeType: n.type, label: n.id, config: n.config, status: st[n.id] },
    })),
    edges: (canvas.edges ?? []).map((e) => ({
      id: `e-${e.from}-${e.to}`,
      source: e.from,
      target: e.to,
      type: 'smoothstep',
      markerEnd: MarkerType.ArrowClosed,
      style: { stroke: 'var(--border-strong)', strokeWidth: 1.5 },
    })),
  }
})

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

onMounted(load)

async function openRun(r: PipelineRun) {
  selected.value = r
  logs.value = ''
  logNode.value = ''
  if (canLogs.value) await loadLogs()
}

async function loadLogs(nodeName?: string) {
  if (!selected.value) return
  logNode.value = nodeName ?? ''
  try {
    logs.value = await pipelineApi.runLogs(selected.value.runName, nodeName)
  } catch {
    logs.value = ''
  }
}

async function run() {
  try {
    const runName = await pipelineApi.run(pipelineId.value)
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
  await pipelineApi.cancelRun(r.runName)
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
          <el-button v-for="n in dag.nodes" :key="n.id" link @click="loadLogs(n.id)">{{ n.id }}</el-button>
          <span v-if="!canLogs" class="dim">（没有『看运行日志』权限）</span>
        </div>
        <pre class="log">{{ logs || '（没有日志）' }}</pre>
      </div>
      <div v-else class="detail empty">选一条运行看它每个节点的状态与日志</div>
    </div>
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
