<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { clusterApi, nodeApi } from '@/api'
import type { K8sCluster, K8sNode, K8sPod, NodeEvent, NodeTaint } from '@/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import KvTags from '@/components/KvTags.vue'
import NodeMetricsPanel from '@/components/node/NodeMetricsPanel.vue'
import { fmtDate, podPhaseType } from '@/utils/format'
import { parseCpuCores, parseMemBytes, humanizeBytes } from '@/utils/metrics'

const route = useRoute()
const router = useRouter()

// ---- 上下文：集群级，无租户/命名空间。clusterId 来自 query + 顶栏下拉可切换 ----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
const name = computed(() => (route.query.name as string) || '')

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

// ---- 节点本体 ----
const node = ref<K8sNode | null>(null)
const loading = ref(false)
const loadError = ref(false)
const metricsRef = ref<InstanceType<typeof NodeMetricsPanel> | null>(null)

async function refresh(): Promise<void> {
  if (!clusterId.value || !name.value) return
  const target = name.value
  loading.value = true
  loadError.value = false
  try {
    node.value = await nodeApi.get(target, clusterId.value)
    if (target !== name.value) return
    // 切换节点后重置懒加载 tab 的缓存
    podsLoaded.value = eventsLoaded.value = yamlLoaded.value = false
    initLabelTaintForm()
  } catch {
    if (target === name.value) loadError.value = true
  } finally {
    loading.value = false
  }
}

function goBack(): void {
  router.push('/nodes')
}

// ---- 自动刷新（顶栏）----
const REFRESH_OPTIONS: { label: string; value: number }[] = [
  { label: '不刷新', value: 0 },
  { label: '30s', value: 30_000 },
  { label: '1m', value: 60_000 },
]
const autoRefresh = ref<number>(0)
let timer: number | null = null
function stopAutoRefresh(): void {
  if (timer != null) { window.clearInterval(timer); timer = null }
}
function scheduleAutoRefresh(): void {
  stopAutoRefresh()
  if (autoRefresh.value > 0) {
    timer = window.setInterval(() => { if (!loading.value) void refreshAll() }, autoRefresh.value)
  }
}
watch(autoRefresh, scheduleAutoRefresh)
onBeforeUnmount(stopAutoRefresh)

async function refreshAll(): Promise<void> {
  await refresh()
  metricsRef.value?.refresh()
}

// ---- Tab 状态 + 懒加载（Pod / 事件 / YAML）----
const activeTab = ref('conditions')
const pods = ref<K8sPod[]>([])
const podsLoaded = ref(false)
const events = ref<NodeEvent[]>([])
const eventsLoaded = ref(false)
const yamlText = ref('')
const yamlLoaded = ref(false)

async function loadPods(): Promise<void> {
  if (!clusterId.value || !name.value) return
  pods.value = await nodeApi.pods(name.value, clusterId.value)
  podsLoaded.value = true
}
async function loadEvents(): Promise<void> {
  if (!clusterId.value || !name.value) return
  events.value = await nodeApi.events(name.value, clusterId.value)
  eventsLoaded.value = true
}
async function loadYaml(): Promise<void> {
  if (!clusterId.value || !name.value) return
  yamlText.value = await nodeApi.getYaml(name.value, clusterId.value)
  yamlLoaded.value = true
}

watch(activeTab, (tab) => {
  if (tab === 'pods' && !podsLoaded.value) void loadPods()
  else if (tab === 'events' && !eventsLoaded.value) void loadEvents()
  else if (tab === 'yaml' && !yamlLoaded.value) void loadYaml()
})

// ---- 概览派生（监控配额）----
const instance = computed(() => (node.value?.internalIp ? node.value.internalIp + ':9100' : ''))
const cpuLimit = computed(() => parseCpuCores(node.value?.cpuAllocatable))
const memoryLimit = computed(() => parseMemBytes(node.value?.memoryAllocatable))

function capacityText(n: K8sNode, kind: 'cpu' | 'mem'): string {
  const cap = kind === 'cpu' ? n.cpuCapacity : n.memoryCapacity
  const alloc = kind === 'cpu' ? n.cpuAllocatable : n.memoryAllocatable
  const fmt = (v?: string | null) => {
    if (!v) return '—'
    return kind === 'cpu' ? `${parseCpuCores(v) ?? v} 核` : humanizeBytes(parseMemBytes(v) ?? 0)
  }
  return `容量 ${fmt(cap)} / 可分配 ${fmt(alloc)}`
}

// ---- 标签 & Taint 编辑（整体替换）----
interface LabelRow { key: string; value: string }
interface TaintRow { key: string; value: string; effect: string }
const TAINT_EFFECTS = ['NoSchedule', 'PreferNoSchedule', 'NoExecute']
const labelRows = ref<LabelRow[]>([])
const taintRows = ref<TaintRow[]>([])
const savingLabels = ref(false)

function initLabelTaintForm(): void {
  const n = node.value
  labelRows.value = Object.entries(n?.labels ?? {}).map(([key, value]) => ({ key, value }))
  taintRows.value = (n?.taints ?? []).map((t: NodeTaint) => ({ key: t.key ?? '', value: t.value ?? '', effect: t.effect ?? 'NoSchedule' }))
}
function addLabelRow(): void { labelRows.value.push({ key: '', value: '' }) }
function removeLabelRow(i: number): void { labelRows.value.splice(i, 1) }
function addTaintRow(): void { taintRows.value.push({ key: '', value: '', effect: 'NoSchedule' }) }
function removeTaintRow(i: number): void { taintRows.value.splice(i, 1) }

async function saveLabelsTaints(): Promise<void> {
  if (!clusterId.value || !name.value) return
  const labels: Record<string, string> = {}
  for (const r of labelRows.value) {
    if (r.key.trim()) labels[r.key.trim()] = r.value
  }
  const taints: NodeTaint[] = taintRows.value
    .filter((r) => r.key.trim())
    .map((r) => ({ key: r.key.trim(), value: r.value, effect: r.effect }))
  savingLabels.value = true
  try {
    node.value = await nodeApi.updateLabelsTaints(name.value, clusterId.value, { labels, taints })
    ElMessage.success('已保存')
    initLabelTaintForm()
  } catch {
    /* 拦截器提示 */
  } finally {
    savingLabels.value = false
  }
}

// ---- 上下文联动 ----
onMounted(async () => {
  await loadClusters()
  if (clusterId.value) await refresh()
})
watch(clusterId, () => { if (name.value) void refresh() })
watch(name, () => { if (clusterId.value) void refresh() })
</script>

<template>
  <div v-loading="loading" class="node-page">
    <PageHeader :title="`节点详情 · ${name || '—'}`">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <span class="refresh-bar">
        <el-tooltip content="立即刷新" placement="bottom" :show-after="100">
          <el-button :icon="Refresh" circle size="small" :disabled="!clusterId" @click="refreshAll" />
        </el-tooltip>
        <el-select v-model="autoRefresh" size="small" style="width: 96px">
          <el-option v-for="o in REFRESH_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </span>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="loadError" title="加载失败" description="该节点可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else-if="node" class="node-detail">
      <!-- 左：基本信息（窄栏） -->
      <aside class="col col-side">
        <section class="panel info-card">
          <h3 class="info-title">基本信息</h3>
          <div class="info-name">{{ node.name }}</div>
          <div class="info-rows">
            <div class="info-row"><span class="k">状态</span><StatusBadgeTip :label="node.status === 'True' ? 'Ready' : 'NotReady'" :type="node.status === 'True' ? 'success' : 'danger'" /></div>
            <div class="info-row"><span class="k">可调度</span><el-tag size="small" :type="node.unschedulable ? 'warning' : 'success'">{{ node.unschedulable ? '不可调度（Cordon）' : '可调度' }}</el-tag></div>
            <div class="info-row"><span class="k">角色</span><span class="v">{{ (node.roles && node.roles.length) ? node.roles.join(', ') : 'worker' }}</span></div>
            <div class="info-row"><span class="k">Kubelet 版本</span><code class="mono v">{{ node.kubeletVersion ?? '—' }}</code></div>
            <div class="info-row"><span class="k">OS / 架构</span><span class="v">{{ [node.os, node.arch].filter(Boolean).join(' / ') || '—' }}</span></div>
            <div class="info-row"><span class="k">内核版本</span><code class="mono v">{{ node.kernelVersion ?? '—' }}</code></div>
            <div class="info-row"><span class="k">容器运行时</span><code class="mono v">{{ node.containerRuntimeVersion ?? '—' }}</code></div>
            <div class="info-row"><span class="k">OS 镜像</span><span class="v">{{ node.osImage ?? '—' }}</span></div>
            <div class="info-row"><span class="k">Pod CIDR</span><code class="mono v">{{ node.podCidr ?? '—' }}</code></div>
            <div class="info-row"><span class="k">内网 IP</span><code class="mono v">{{ node.internalIp ?? '—' }}</code></div>
            <div class="info-row"><span class="k">外网 IP</span><code class="mono v">{{ node.externalIp ?? '—' }}</code></div>
            <div class="info-row"><span class="k">CPU</span><span class="v">{{ capacityText(node, 'cpu') }}</span></div>
            <div class="info-row"><span class="k">内存</span><span class="v">{{ capacityText(node, 'mem') }}</span></div>
            <div class="info-row"><span class="k">Pod 上限</span><span class="v">{{ node.podsLimit ?? '—' }}</span></div>
            <div class="info-row"><span class="k">加入时间</span><span class="v">{{ fmtDate(node.creationTime) }}</span></div>
            <div class="info-row col-row"><span class="k">标签</span><KvTags title="标签" :data="node.labels ?? {}" /></div>
          </div>
        </section>
      </aside>

      <!-- 右：监控（上）+ Tab（下） -->
      <main class="col col-main">
        <div class="main-board panel">
          <NodeMetricsPanel ref="metricsRef" :name="node.name" :cluster-id="clusterId" :instance="instance" :cpu-limit="cpuLimit" :memory-limit="memoryLimit" />

          <el-tabs v-model="activeTab">
            <!-- Conditions -->
            <el-tab-pane label="Conditions" name="conditions">
              <el-table :data="node.conditions ?? []" stripe size="small">
                <el-table-column prop="type" label="类型" width="200" />
                <el-table-column label="状态" width="120">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.status === 'True' ? 'success' : row.status === 'False' ? 'info' : 'danger'">{{ row.status }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="reason" label="原因" width="180" />
                <el-table-column prop="message" label="说明" min-width="240" show-overflow-tooltip />
                <el-table-column label="最近心跳" width="160">
                  <template #default="{ row }">{{ fmtDate(row.lastHeartbeatTime) }}</template>
                </el-table-column>
                <el-table-column label="最近变更" width="160">
                  <template #default="{ row }">{{ fmtDate(row.lastTransitionTime) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- Pod -->
            <el-tab-pane label="Pod" name="pods">
              <div class="list-head">
                <h3 class="info-title">节点上的 Pod</h3>
                <span class="muted count">{{ pods.length }} 个（上限 {{ node.podsLimit ?? '—' }}）</span>
              </div>
              <el-table v-if="podsLoaded" :data="pods" stripe size="small">
                <el-table-column prop="name" label="名称" min-width="240" show-overflow-tooltip />
                <el-table-column prop="namespace" label="命名空间" width="160" />
                <el-table-column label="状态" width="110">
                  <template #default="{ row }"><StatusBadgeTip :label="row.phase ?? 'Unknown'" :type="podPhaseType(row.phase)" :reason="row.statusReason" /></template>
                </el-table-column>
                <el-table-column prop="podIp" label="IP" width="140">
                  <template #default="{ row }"><span class="muted">{{ row.podIp ?? '—' }}</span></template>
                </el-table-column>
                <el-table-column prop="restarts" label="重启" width="70" />
                <el-table-column label="创建时间（存活时长）" width="150">
                  <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- 标签 & Taint -->
            <el-tab-pane label="标签 & Taint" name="labels">
              <div class="lt-block">
                <div class="list-head">
                  <h3 class="info-title">标签（Labels）</h3>
                  <el-button size="small" @click="addLabelRow">添加</el-button>
                </div>
                <div v-if="!labelRows.length" class="muted empty-line">无标签</div>
                <div v-for="(r, i) in labelRows" :key="'l' + i" class="lt-row">
                  <el-input v-model="r.key" placeholder="key" style="width: 260px" />
                  <el-input v-model="r.value" placeholder="value" style="flex: 1" />
                  <el-button link type="danger" @click="removeLabelRow(i)">删除</el-button>
                </div>

                <div class="list-head mt-4">
                  <h3 class="info-title">污点（Taints）</h3>
                  <el-button size="small" @click="addTaintRow">添加</el-button>
                </div>
                <div v-if="!taintRows.length" class="muted empty-line">无污点</div>
                <div v-for="(r, i) in taintRows" :key="'t' + i" class="lt-row">
                  <el-input v-model="r.key" placeholder="key" style="width: 200px" />
                  <el-input v-model="r.value" placeholder="value" style="flex: 1" />
                  <el-select v-model="r.effect" style="width: 170px">
                    <el-option v-for="e in TAINT_EFFECTS" :key="e" :label="e" :value="e" />
                  </el-select>
                  <el-button link type="danger" @click="removeTaintRow(i)">删除</el-button>
                </div>

                <div class="lt-actions">
                  <el-button type="primary" :loading="savingLabels" @click="saveLabelsTaints">保存（整体替换）</el-button>
                </div>
              </div>
            </el-tab-pane>

            <!-- 事件 -->
            <el-tab-pane label="事件" name="events">
              <el-table v-if="eventsLoaded" :data="events" stripe size="small">
                <el-table-column prop="reason" label="原因" width="180" />
                <el-table-column label="类型" width="100">
                  <template #default="{ row }"><el-tag size="small" :type="row.type === 'Warning' ? 'danger' : 'info'">{{ row.type ?? 'Normal' }}</el-tag></template>
                </el-table-column>
                <el-table-column prop="message" label="说明" min-width="300" show-overflow-tooltip />
                <el-table-column prop="count" label="次数" width="70" />
                <el-table-column label="首次" width="160">
                  <template #default="{ row }">{{ fmtDate(row.firstTimestamp) }}</template>
                </el-table-column>
                <el-table-column label="最近" width="160">
                  <template #default="{ row }">{{ fmtDate(row.lastTimestamp) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- YAML -->
            <el-tab-pane label="YAML" name="yaml">
              <pre v-if="yamlLoaded && yamlText" class="yaml-block">{{ yamlText }}</pre>
              <div v-else class="muted">加载中…</div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </main>
    </div>

    <div v-else class="loading-tip">加载中…</div>
  </div>
</template>

<style scoped>
/* 页面占满一屏：PageHeader 固定 + grid 撑满剩余，各列各自内部滚动（宽屏） */
.node-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }

.node-detail {
  flex: 1 1 auto;
  min-height: 0;                 /* 允许收缩 → 各列可内部滚动 */
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;          /* 两列等高填满 */
}

.col { min-width: 0; min-height: 0; display: flex; flex-direction: column; gap: 16px; }

/* 侧栏：固定高度，内容多则内部滚动 */
.col-side { overflow-y: auto; }

/* 右侧：一个大背板囊括监控 + Tab，整体内部滚动 */
.col-main { overflow: hidden; }
.main-board {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 窄屏：恢复单列堆叠 + 整页滚动（不固定一屏高） */
@media (max-width: 960px) {
  .node-page { height: auto; overflow: visible; }
  .node-detail { flex: none; grid-template-columns: 1fr; align-items: start; }
  .col, .col-side, .col-main { overflow: visible; }
  .main-board { flex: none; overflow: visible; }
}

.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }

.info-card { padding: 16px; }
.info-title { margin: 0 0 12px; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.info-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 16px; font-weight: 600; color: var(--text-1); word-break: break-all; margin-bottom: 14px; }
.info-rows { display: flex; flex-direction: column; gap: 12px; }
.info-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.info-row.col-row { flex-direction: column; align-items: flex-start; gap: 6px; }
.info-row .k { color: var(--text-3); flex-shrink: 0; }
.info-row .v { color: var(--text-1); text-align: right; word-break: break-all; }
.col-row .v { text-align: left; width: 100%; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }

.refresh-bar { display: inline-flex; align-items: center; gap: 8px; margin-right: 12px; }
.list-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 10px; }
.list-head .info-title { margin: 0; }
.count { font-size: 12px; }
.muted { color: var(--text-3); }

/* 标签 & Taint 编辑 */
.lt-block { padding: 4px 0; }
.lt-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.lt-actions { margin-top: 16px; }
.empty-line { font-size: 13px; margin-bottom: 8px; }
.mt-4 { margin-top: 24px; }

.yaml-block {
  margin: 0; padding: 14px; border-radius: 8px; background: var(--panel-hover); border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; line-height: 1.6; color: var(--text-2);
  max-height: 70vh; overflow: auto; white-space: pre-wrap;
}
.loading-tip { padding: 48px; text-align: center; font-size: 13px; color: var(--text-3); }
</style>
