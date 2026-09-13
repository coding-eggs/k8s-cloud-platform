<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { clusterApi, nodeApi } from '@/api'
import { nodeMetrics } from '@/api/metrics'
import type { K8sCluster, K8sNode, NodeCurrentMetric, NodePodStat } from '@/types'
import { fmtDate } from '@/utils/format'
import { humanizeBytes, parseCpuCores, parseMemBytes } from '@/utils/metrics'
import {MoreFilled} from "@element-plus/icons-vue";

const router = useRouter()

// ---- 集群选择（节点为集群级，无租户/命名空间上下文）----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
  if (first && !clusterId.value) clusterId.value = first.clusterId
}

// ---- 节点列表 + Pod 聚合 + 当前用量 ----
const loading = ref(false)
const nodes = ref<K8sNode[]>([])
const stats = ref<Map<string, NodePodStat>>(new Map())
const currents = ref<Map<string, NodeCurrentMetric>>(new Map())

async function load(): Promise<void> {
  if (!clusterId.value) return
  loading.value = true
  try {
    const list = await nodeApi.list({ clusterId: clusterId.value })
    nodes.value = list
    // Pod 聚合（按 nodeName）
    const statsList = await nodeApi.podstats(clusterId.value)
    stats.value = new Map(statsList.map((s) => [s.nodeName, s]))
    // 当前用量：instance = <internalIp>:9100
    const instances: string[] = []
    for (const n of list) {
      if (n.internalIp) instances.push(n.internalIp + ':9100')
    }
    currents.value = new Map()
    if (instances.length) {
      const cur = await nodeMetrics.current(clusterId.value, instances)
      for (const [k, v] of Object.entries(cur)) currents.value.set(k, v)
    }
  } finally {
    loading.value = false
  }
}

watch(clusterId, load)
onMounted(async () => {
  await loadClusters()
  if (clusterId.value) await load()
})

// ---- 展示辅助 ----
function instanceOf(n: K8sNode): string | null {
  return n.internalIp ? n.internalIp + ':9100' : null
}
function statOf(n: K8sNode): NodePodStat | undefined {
  return stats.value.get(n.name)
}

function cpuText(n: K8sNode): string {
  const alloc = parseCpuCores(n.cpuAllocatable)
  const req = (statOf(n)?.cpuRequestMillicores ?? 0) / 1000
  const allocS = alloc != null ? alloc.toFixed(1) : '-'
  return `${req.toFixed(1)} / ${allocS}`
}

function memText(n: K8sNode): string {
  const alloc = parseMemBytes(n.memoryAllocatable)
  const req = statOf(n)?.memRequestBytes ?? 0
  return `${humanizeBytes(req)}/${alloc != null ? humanizeBytes(alloc) : '-'}`
}

function podCountText(n: K8sNode): string {
  const actual = statOf(n)?.podCount ?? 0
  const limit = n.podsLimit ?? '-'
  return `${actual} / ${limit}`
}

function roleText(n: K8sNode): string {
  return n.roles && n.roles.length ? n.roles.join(', ') : 'worker'
}

// ---- 行操作 ----
async function onDetail(row: K8sNode): Promise<void> {
  router.push({ path: '/nodes/detail', query: { clusterId: clusterId.value, name: row.name } })
}

const cordonBusy = ref('')
async function onCordon(row: K8sNode): Promise<void> {
  const toCordon = !row.unschedulable
  try {
    await ElMessageBox.confirm(
      toCordon ? `确认将节点「${row.name}」标记为不可调度？（已有 Pod 不受影响，新 Pod 不再调度到此）` : `确认恢复节点「${row.name}」可调度？`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  cordonBusy.value = row.name
  try {
    if (toCordon) await nodeApi.cordon(clusterId.value, row.name)
    else await nodeApi.uncordon(clusterId.value, row.name)
    ElMessage.success(toCordon ? '已标记不可调度' : '已恢复可调度')
    await load()
  } catch {
    /* 拦截器提示 */
  } finally {
    cordonBusy.value = ''
  }
}

// ---- Drain 对话框（--force 默认关）----
const drainVisible = ref(false)
const draining = ref(false)
const drainTarget = ref<K8sNode | null>(null)
const drainForce = ref(false)
const drainDeleteEmptyDir = ref(false)
const drainResult = ref<{ evicted: string[]; skipped: string[]; errors: string[] } | null>(null)

function openDrain(row: K8sNode): void {
  drainTarget.value = row
  drainForce.value = false
  drainDeleteEmptyDir.value = false
  drainResult.value = null
  drainVisible.value = true
}

async function submitDrain(): Promise<void> {
  if (!drainTarget.value) return
  draining.value = true
  try {
    const res = await nodeApi.drain({
      clusterId: clusterId.value,
      name: drainTarget.value.name,
      force: drainForce.value,
      deleteEmptyDir: drainDeleteEmptyDir.value,
    })
    drainResult.value = {
      evicted: res.evicted ?? [],
      skipped: res.skipped ?? [],
      errors: res.errors ?? [],
    }
    await load()
  } catch {
    /* 拦截器提示 */
  } finally {
    draining.value = false
  }
}

const drainSummary = computed(() => {
  const r = drainResult.value
  if (!r) return ''
  return `已驱逐 ${r.evicted.length}，跳过 ${r.skipped.length}，失败 ${r.errors.length}`
})
</script>

<template>
  <div>
    <PageHeader title="节点管理" description="集群级节点（Kubernetes Node）：状态 / 用量 / Cordon / Drain">
      <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </PageHeader>

    <el-table v-loading="loading" :data="nodes" stripe>
      <el-table-column label="名称" width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="onDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'True' ? 'success' : 'danger'" size="small">
            {{ row.status === 'True' ? 'Ready' : 'NotReady' }}
          </el-tag>
          <el-tag v-if="row.unschedulable" type="warning" size="small" class="ml-1">SchedulingDisabled</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="120">
        <template #default="{ row }">{{ roleText(row) }}</template>
      </el-table-column>
      <el-table-column prop="kubeletVersion" label="版本" min-width="120" />
      <el-table-column label="CPU（req/alloc）" min-width="180">
        <template #default="{ row }">{{ cpuText(row) }}</template>
      </el-table-column>
      <el-table-column label="内存（req/alloc）" min-width="200">
        <template #default="{ row }">{{ memText(row) }}</template>
      </el-table-column>
      <el-table-column prop="internalIp" label="IP" min-width="140" />
      <el-table-column label="Pod 数（实际/上限）" min-width="150">
        <template #default="{ row }">{{ podCountText(row) }}</template>
      </el-table-column>
      <el-table-column label="加入时间" min-width="160">
        <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
      </el-table-column>


      <el-table-column width="64" fixed="right">
        <template #default="{ row }">
          <el-dropdown trigger="click">
            <el-button link type="primary" :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="onCordon(row)">
                  <el-button link :type="row.unschedulable ? 'success' : 'warning'" :loading="cordonBusy === row.name">
                  {{ row.unschedulable ? 'Uncordon' : 'Cordon' }}
                </el-button></el-dropdown-item>
                <el-dropdown-item divided style="color: var(--el-color-danger)" @click="openDrain(row)">
                  <el-button link type="danger">Drain</el-button>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <!-- Drain 对话框 -->
    <el-dialog v-model="drainVisible" title="驱逐节点 Pod（Drain）" width="520px">
      <p class="drain-tip">
        将驱逐节点「{{ drainTarget?.name }}」上的 Pod（保留 DaemonSet / 静态 / mirror Pod）。此操作会中断业务，请确认。
      </p>
      <el-checkbox v-model="drainForce">强制驱逐（--force，PDB 阻断时直接删除）</el-checkbox>
      <el-checkbox v-model="drainDeleteEmptyDir" class="mt-2">同时删除使用 emptyDir 的 Pod</el-checkbox>

      <el-alert
        v-if="drainResult"
        :title="drainSummary"
        type="success"
        :closable="false"
        class="mt-3"
      />
      <div v-if="drainResult && drainResult.errors.length" class="drain-errors mt-2">
        <div v-for="(e, i) in drainResult.errors" :key="i" class="drain-error-line">{{ e }}</div>
      </div>

      <template #footer>
        <el-button @click="drainVisible = false">关闭</el-button>
        <el-button v-if="!drainResult" type="danger" :loading="draining" @click="submitDrain">确认驱逐</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mt-2 { margin-top: 8px; }
.mt-3 { margin-top: 12px; }
.ml-1 { margin-left: 6px; }
.drain-tip {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.6;
}
.drain-errors {
  max-height: 160px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  background: var(--panel-hover);
}
.drain-error-line {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--text-2);
  line-height: 1.7;
}
</style>
