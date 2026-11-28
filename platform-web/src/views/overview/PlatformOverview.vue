<script setup lang="ts">
/**
 * 平台总览：运行态、集群排查、容量与租户资源分布。
 */
import { computed, onMounted, ref } from 'vue'
import { clusterApi, overviewApi, tenantApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import AllocateNamespaceDialog from '@/components/AllocateNamespaceDialog.vue'
import QuickActions from '@/components/overview/QuickActions.vue'
import RefreshControl from '@/components/overview/RefreshControl.vue'
import CountCard from '@/components/overview/CountCard.vue'
import OverviewAlertSummary from '@/components/overview/OverviewAlertSummary.vue'
import StatusCards from '@/components/overview/StatusCards.vue'
import CapacityBullet from '@/components/overview/CapacityBullet.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import InventoryStrip, { type InventoryItem } from '@/components/overview/InventoryStrip.vue'
import { podSegments, segmentsOf, type Segment, workloadSegments as workloadSegmentsOf } from '@/components/overview/labels'
import type { K8sCluster, NamespaceAllocation, PlatformTenant } from '@/types'
import type { PlatformOverview } from '@/types/overview'
import { clusterConnectionStatus } from '@/utils/format'

const perm = usePermission()
const allocDialogVisible = ref(false)

const loading = ref(false)
/** 最近一次成功取数的时刻（页头「更新于 X 前」）；刷新失败不更新 */
const updatedAt = ref<number | null>(null)
/** 最近一次刷新失败但还有上一次的数可显示（自动刷新偶发失败不该把整页打成错误态） */
const refreshFailed = ref(false)
const clusters = ref<K8sCluster[]>([])
const clusterListFailed = ref(false)
const tenants = ref<PlatformTenant[]>([])
const allocations = ref<NamespaceAllocation[]>([])
const ov = ref<PlatformOverview | null>(null)
const canOverview = computed(() => perm.has(apiCodes.overviewView))
const canListClusters = computed(() => perm.has(apiCodes.clusterManage))

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  try {
    // 各块对应各自的端点：无权限点则跳过，避免 403 连弹。门控用的是 **API 域** 码。
    // 有总览码时，租户 / 分配数由总览接口给出，不再单独拉两份全量列表。
    const [c, t, a, o] = await Promise.all([
      canListClusters.value ? clusterApi.list().catch(() => null) : Promise.resolve([] as K8sCluster[]),
      !canOverview.value && perm.has(apiCodes.tenantRead) ? tenantApi.list() : Promise.resolve([] as PlatformTenant[]),
      !canOverview.value && perm.has(apiCodes.allocationList) ? tenantApi.namespaceList() : Promise.resolve([] as NamespaceAllocation[]),
      canOverview.value ? overviewApi.platform().catch(() => null) : Promise.resolve(null),
    ])
    clusterListFailed.value = c == null
    if (c != null) clusters.value = c
    tenants.value = t
    allocations.value = a
    if (canOverview.value && o == null && ov.value != null) {
      refreshFailed.value = true // 保留上一次的数，页头标红「刷新失败」
    } else {
      ov.value = o
      refreshFailed.value = canOverview.value && o == null
      if (!refreshFailed.value) updatedAt.value = Date.now()
    }
  } catch {
    refreshFailed.value = true
  } finally {
    loading.value = false
  }
}

const workloadSegments = computed<Segment[]>(() => {
  const w = ov.value?.workloads
  return w ? workloadSegmentsOf(w.total, w.unhealthy) : []
})
/** 清单条：租户规模（只有数、没有健康状态，不占状态卡位） */
const inventory = computed<InventoryItem[]>(() => {
  const t = ov.value?.tenants
  if (!t) return []
  return [
    { label: '租户', value: t.total, to: '/tenants' },
    { label: '启用', value: t.enabled, to: '/tenants?status=1' },
    { label: '停用', value: t.disabled, to: '/tenants?status=0' },
    { label: '成员', value: t.members },
    { label: '命名空间分配记录', value: t.allocatedNamespaces },
  ]
})
const clusterName = (id: string) => clusters.value.find(c => c.clusterId === id)?.clusterName ?? id
const nodeIssues = computed(() => (ov.value?.nodeIssues ?? []).slice().sort((a, b) => b.notReady - a.notReady))
const singleNodeIssue = computed(() => nodeIssues.value.length === 1 ? nodeIssues.value[0] : undefined)
const canViewNodes = computed(() => perm.has(pageCodes.node.list))
const nodeLocation = (clusterId: string) => ({ name: 'nodes', query: { clusterId, status: 'not-ready' } })
const unavailable = computed(() => new Set(ov.value?.clusters.unavailableIds ?? []))
const namespaceDistribution = computed(() => {
  const ns = ov.value?.namespaces
  if (!ns) return null
  const entries = [
    { label: '已分配', value: ns.allocated, tone: 'allocated' },
    { label: '未分配', value: ns.unallocated, tone: 'unallocated' },
    { label: '系统', value: ns.system, tone: 'system' },
  ]
  return { entries, total: entries.reduce((sum, entry) => sum + entry.value, 0) }
})

const clusterResourceCounts = computed(() => {
  const counts = new Map<string, { abnormalPods: number; unhealthyWorkloads: number }>()
  for (const row of ov.value?.byNamespace ?? []) {
    const previous = counts.get(row.clusterId)
    counts.set(row.clusterId, {
      abnormalPods: (previous?.abnormalPods ?? 0) + row.abnormal,
      unhealthyWorkloads: (previous?.unhealthyWorkloads ?? 0) + row.unhealthyWorkloads,
    })
  }
  return counts
})
const clusterAlertCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const row of ov.value?.alertsFiring?.byNamespace ?? []) {
    counts.set(row.clusterId, (counts.get(row.clusterId) ?? 0) + row.total)
  }
  return counts
})

function connectionFor(cluster: K8sCluster) {
  if (cluster.enabled === 1 && unavailable.value.has(cluster.clusterId)) {
    return { label: '不可用', type: 'danger' as const }
  }
  return clusterConnectionStatus(cluster.enabled, cluster.status)
}
const clusterRows = computed(() => clusters.value.map(cluster => {
  const overview = ov.value
  const sampled = overview && cluster.enabled === 1 && !unavailable.value.has(cluster.clusterId)
    && (overview.clusters.unavailableIds != null || overview.clusters.unavailable === 0)
  const resources = clusterResourceCounts.value.get(cluster.clusterId)
  return {
    ...cluster,
    connection: connectionFor(cluster),
    abnormalPods: sampled && overview.byNamespace != null ? (resources?.abnormalPods ?? 0) : null,
    unhealthyWorkloads: sampled && overview.byNamespace != null ? (resources?.unhealthyWorkloads ?? 0) : null,
    alerts: sampled && overview.alertsFiring?.byNamespace != null ? (clusterAlertCounts.value.get(cluster.clusterId) ?? 0) : null,
  }
}).sort((a, b) => Number(b.connection.type === 'danger') - Number(a.connection.type === 'danger')))

const clusterMetrics = [
  { key: 'abnormalPods', label: '异常 Pod' },
  { key: 'unhealthyWorkloads', label: '不健康工作负载' },
  { key: 'alerts', label: '触发中告警' },
] as const

const enabledClusters = computed(() => clusters.value.filter((c) => c.enabled === 1).length)
const enabledTenants = computed(() => tenants.value.filter((t) => t.status === 1).length)

const stats = computed(() => [
  { label: '集群', value: clusters.value.length, sub: `启用 ${enabledClusters.value}`, allowed: canListClusters.value && !clusterListFailed.value },
  { label: '租户', value: tenants.value.length, sub: `启用 ${enabledTenants.value}`, allowed: perm.has(apiCodes.tenantRead) },
  { label: '命名空间分配', value: allocations.value.length, sub: '跨集群总计', allowed: perm.has(apiCodes.allocationList) },
].filter(item => item.allowed))

const quickActions = computed(() =>
  [
    { title: '新增集群', path: '/clusters?do=create', icon: 'Monitor', perm: pageCodes.cluster },
    { title: '分配命名空间', run: () => { allocDialogVisible.value = true }, icon: 'FolderOpened', perm: apiCodes.allocationManage },
    { title: '新建租户', path: '/tenants?do=create', icon: 'OfficeBuilding', perm: pageCodes.tenant.list },
    { title: '查审计', path: '/audit', icon: 'Document', perm: pageCodes.audit },
  ].filter((a) => perm.has(a.perm)),
)

onMounted(load)
</script>

<template>
  <div class="platform-overview">
    <header class="page-header">
      <div>
        <h1>平台总览</h1>
        <p>全平台 · 服务、集群与资源状态</p>
      </div>
      <RefreshControl :updated-at="updatedAt" :loading="loading" :failed="refreshFailed && updatedAt != null" @refresh="load" />
    </header>

    <QuickActions :actions="quickActions" face="platform" />
    <AllocateNamespaceDialog v-model="allocDialogVisible" @success="load" />

    <el-skeleton v-if="loading && !ov && updatedAt == null" animated :rows="5" />
    <template v-else>
      <section v-if="ov" class="panel health" aria-label="平台健康">
        <div class="health-cell">
          <span>平台服务<FieldHelp tip="健康服务数 / 纳入统计的服务数。总览 API 响应后计入 platform-api；默认还探测认证服务 platform-auth 和集群管理服务 k8s-server，健康接口返回 UP 才计为正常。网关不计入。" /></span>
          <strong :class="{ danger: ov.services.down.length, healthy: ov.services.total && !ov.services.down.length }">
            {{ ov.services.total ? `${ov.services.up} / ${ov.services.total} 正常` : '—' }}
          </strong>
          <small v-if="ov.services.down.length">{{ ov.services.down.join('、') }}</small>
        </div>
        <div class="health-cell">
          <span>启用集群连接</span>
          <strong :class="ov.clusters.unavailable ? 'danger' : 'healthy'">
            {{ ov.clusters.connected }} / {{ ov.clusters.connected + ov.clusters.unavailable }} 已连接
          </strong>
          <small v-if="ov.clusters.unavailable">{{ ov.clusters.unavailable }} 个不可用 · {{ ov.clusters.unavailableNames.join('、') }}</small>
        </div>
        <div class="health-cell">
          <span>节点异常</span>
          <router-link v-if="canViewNodes && singleNodeIssue" class="node-issue-link" :to="nodeLocation(singleNodeIssue.clusterId)">
            {{ singleNodeIssue.notReady }} 个 NotReady
          </router-link>
          <details v-else-if="canViewNodes && nodeIssues.length > 1" class="node-issues">
            <summary class="node-issue-link">{{ ov.nodes?.notReady }} 个 NotReady</summary>
            <router-link v-for="issue in nodeIssues" :key="issue.clusterId" :to="nodeLocation(issue.clusterId)">
              {{ issue.clusterName || issue.clusterId }} · {{ issue.notReady }} 个
            </router-link>
          </details>
          <strong v-else :class="ov.nodes ? (ov.nodes.notReady ? 'danger' : 'healthy') : ''">
            {{ ov.nodes ? `${ov.nodes.notReady} 个 NotReady` : '—' }}
          </strong>
          <small v-if="ov.nodes">{{ ov.nodes.ready }} 个 Ready · 可用集群</small>
        </div>
        <div class="health-cell">
          <span>触发中告警</span>
          <OverviewAlertSummary :alerts="ov.alertsFiring" :cluster-name="clusterName" />
        </div>
      </section>

      <el-alert v-else-if="refreshFailed" type="warning" :closable="false" title="总览数据暂时取不到，请刷新重试" />
      <section v-if="!canOverview && !refreshFailed && stats.length" class="panel health" aria-label="平台统计">
        <div v-for="stat in stats" :key="stat.label" class="health-cell">
          <span>{{ stat.label }}</span><strong>{{ stat.value }}</strong><small>{{ stat.sub }}</small>
        </div>
      </section>

      <section v-if="ov" class="resource-panel">
        <header class="section-head resource-head"><h2>资源状态</h2><p>可用集群</p></header>
        <StatusCards>
          <CountCard title="工作负载" kind="workload" :total="ov.workloads?.total ?? null" :segments="workloadSegments" :ns-rows="ov.byNamespace" />
          <CountCard title="Pod" kind="pod" :total="ov.pods?.total ?? null" :segments="podSegments(ov.pods)" :ns-rows="ov.byNamespace" />
          <CountCard title="应用" kind="app" :total="ov.applications?.total ?? null" :segments="segmentsOf('app', ov.applications)" />
          <CountCard title="中间件" kind="middleware" :ns-rows="ov.byNamespace" :total="ov.middleware?.total ?? null" :segments="segmentsOf('middleware', ov.middleware)" />
        </StatusCards>
      </section>

      <section v-if="canListClusters" class="panel cluster-panel">
          <header class="section-head">
            <div><h2>集群运行</h2><p v-if="ov">异常计数来自可用集群的资源快照</p></div>
            <router-link v-if="perm.has(pageCodes.cluster)" class="manage-link" to="/clusters">集群管理</router-link>
          </header>
          <el-alert v-if="clusterListFailed" class="cluster-error" type="warning" :closable="false"
            :title="clusterRows.length ? '集群列表刷新失败，正在显示上次结果' : '集群列表暂时取不到'" />
          <el-table v-if="!clusterListFailed || clusterRows.length" :data="clusterRows" row-key="clusterId" :max-height="390" empty-text="还没有接入集群">
            <el-table-column prop="clusterName" label="集群" min-width="140" show-overflow-tooltip />
            <el-table-column label="连接状态" width="100">
              <template #default="{ row }"><el-tag :type="row.connection.type" size="small" effect="plain">{{ row.connection.label }}</el-tag></template>
            </el-table-column>
            <el-table-column label="管理状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small" effect="plain">{{ row.enabled === 1 ? '已启用' : '已禁用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="version" label="版本" min-width="90">
              <template #default="{ row }">{{ row.version || '—' }}</template>
            </el-table-column>
            <template v-if="ov">
              <el-table-column v-for="metric in clusterMetrics" :key="metric.key" :label="metric.label" min-width="108" align="right">
                <template #default="{ row }">
                  <span class="metric-value" :class="row[metric.key] > 0 ? 'danger' : 'metric-quiet'">{{ row[metric.key] ?? '—' }}</span>
                </template>
              </el-table-column>
            </template>
            <el-table-column v-if="perm.has(pageCodes.cluster)" label="操作" width="76" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="$router.push({ name: 'cluster-detail', query: { clusterId: row.clusterId } })">概览</el-button>
              </template>
            </el-table-column>
          </el-table>
      </section>

      <div v-if="ov" class="overview-split">
        <section class="panel capacity-panel">
          <header class="section-head">
            <div><h2>集群容量</h2><p>{{ ov.clusters.connected }} 个可用集群 · requests / 可分配</p></div>
          </header>
          <div class="capacity-bars">
            <CapacityBullet label="CPU" unit="core" :scale="ov.capacity?.cpuAllocatable" scale-name="可分配" :filled="ov.capacity?.cpuRequest" filled-name="requests" :limit="ov.capacity?.cpuLimit" missing-text="—" />
            <CapacityBullet label="内存" unit="byte" :scale="ov.capacity?.memAllocatable" scale-name="可分配" :filled="ov.capacity?.memRequest" filled-name="requests" :limit="ov.capacity?.memLimit" missing-text="—" />
          </div>
          <header class="section-head quota-head">
            <div><h3>命名空间配额</h3><p>配额已用 / 已配置上限，按资源分别汇总</p></div>
          </header>
          <div v-if="ov.quota" class="capacity-bars">
            <CapacityBullet label="CPU" unit="core" :scale="ov.quota.cpuHard" :filled="ov.quota.cpuUsed" filled-name="配额已用" />
            <CapacityBullet label="内存" unit="byte" :scale="ov.quota.memHard" :filled="ov.quota.memUsed" filled-name="配额已用" />
          </div>
          <p v-else class="metric-note">{{ ov.clusters.connected ? '可用集群没有已配置的 CPU 或内存配额' : '暂无可用集群数据' }}</p>
        </section>
        <section class="panel distribution-panel">
          <header class="section-head">
            <div>
              <h2>命名空间分布<FieldHelp tip="系统：名称精确匹配平台 protected-namespaces 配置的命名空间，优先计入系统。其余命名空间按租户分配记录归入「已分配」或「未分配」。" /></h2>
              <p>可用集群</p>
            </div>
            <strong v-if="namespaceDistribution" class="distribution-total">{{ namespaceDistribution.total }} 个</strong>
          </header>
          <div v-if="namespaceDistribution" class="distribution-content">
            <div class="distribution-track" aria-hidden="true">
              <span v-for="entry in namespaceDistribution.entries" :key="entry.tone" :class="entry.tone" :style="{ flexGrow: entry.value }" />
            </div>
            <dl class="distribution-legend">
              <div v-for="entry in namespaceDistribution.entries" :key="entry.tone">
                <dt><i :class="entry.tone" aria-hidden="true" />{{ entry.label }}</dt>
                <dd>{{ entry.value }}</dd>
              </div>
            </dl>
          </div>
          <p v-else class="metric-note">暂无可用集群数据</p>
          <header class="section-head quota-head"><h3>租户与分配</h3></header>
          <InventoryStrip class="tenant-inventory" :items="inventory" />
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.platform-overview { container-type: inline-size; }
.page-header, .section-head { display: flex; justify-content: space-between; gap: 8px 16px; flex-wrap: wrap; }
.page-header { align-items: center; margin-bottom: 12px; }
.page-header h1 { margin: 0; color: var(--text-1); font-size: 24px; line-height: 1.3; }
.page-header p, .section-head p { margin: 4px 0 0; font-size: 12px; color: var(--text-2); }
.health, .overview-split, .resource-panel, .cluster-panel { margin-top: 14px; }
.health { display: grid; grid-template-columns: repeat(auto-fit, minmax(145px, 1fr)); }
.health-cell { display: grid; align-content: start; gap: 5px; padding: 13px 16px; min-width: 0; }
.health-cell + .health-cell { border-inline-start: 1px solid var(--border); }
.health-cell span, .health-cell small { color: var(--text-2); font-size: 12px; overflow-wrap: anywhere; }
.health-cell strong { font-size: 16px; }
.healthy { color: var(--success); }
.danger { color: var(--danger); }
.node-issue-link { color: var(--danger); font-size: 16px; font-weight: 650; cursor: pointer; }
a.node-issue-link:hover, .node-issues a:hover { text-decoration: underline; }
.node-issues a { display: block; margin-top: 5px; color: var(--accent); font-size: 12px; overflow-wrap: anywhere; }
.overview-split { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); gap: 14px; }
.section-head { align-items: baseline; padding: 14px 16px 10px; }
.section-head h2, .section-head h3 { margin: 0; font-size: 15px; }
.section-head h3 { font-size: 13px; }
.resource-head { padding: 0 0 6px; }
.resource-head p { margin: 0; }
.cluster-panel :deep(.el-table) { border-radius: 0 0 10px 10px; }
.cluster-error { margin-bottom: 8px; }
.manage-link { font-size: 13px; color: var(--accent); }
.metric-value, .distribution-total, .distribution-legend dd { font-variant-numeric: tabular-nums; }
.metric-value.danger { font-weight: 650; }
.metric-quiet, .metric-note { color: var(--text-2); }
.metric-note { margin: 0; padding: 3px 16px 14px; font-size: 12px; }
.capacity-bars { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 22px; padding: 3px 16px 15px; }
.quota-head { border-top: 1px solid var(--border); }
.resource-panel :deep(.ov-status-wrap) { margin: 0; }
.resource-panel :deep(.ov-status-cards) { grid-auto-flow: row; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.distribution-content { padding: 4px 16px 14px; }
.distribution-track { display: flex; overflow: hidden; height: 9px; border-radius: 6px; background: var(--border); }
.distribution-track span { flex-basis: 0; }
.allocated { background: var(--success); }
.unallocated { background: var(--warning); }
.system { background: var(--accent); }
.distribution-legend { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 10px 0 0; }
.distribution-legend dt { display: flex; align-items: center; gap: 6px; color: var(--text-2); font-size: 12px; }
.distribution-legend i { width: 7px; height: 7px; border-radius: 2px; }
.distribution-legend dd { margin: 3px 0 0 13px; color: var(--text-1); font-size: 16px; font-weight: 650; }
.tenant-inventory { padding: 0 16px 15px; }
@container (max-width: 900px) {
  .overview-split { grid-template-columns: minmax(0, 1fr); }
}
@container (max-width: 660px) {
  .health { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .health-cell:nth-child(odd) { border-inline-start: 0; }
  .health-cell:nth-child(n + 3) { border-top: 1px solid var(--border); }
  .resource-panel :deep(.ov-status-cards) { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@container (max-width: 485px) {
  .capacity-bars { grid-template-columns: minmax(0, 1fr); }
}
</style>
