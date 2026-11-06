<script setup lang="ts">
/**
 * 平台视图总览：平台与集群健康条 + 按资源类型的整体汇总（不按集群拆）+ 集群表 / 快捷操作 / 最近访问。
 *
 * 汇总一次请求 `/overview/platform`（后端复用 45s 聚合缓存；不逐集群调 `/cluster/overview`）。
 * 没有 `platform:overview:view` 时不请求，只显示原有区块。单个集群的明细去集群概览看。
 */
import { computed, onMounted, ref } from 'vue'
import { clusterApi, overviewApi, tenantApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import RecentVisits from '@/components/RecentVisits.vue'
import QuickActions from '@/components/overview/QuickActions.vue'
import CountCard from '@/components/overview/CountCard.vue'
import StatusPie from '@/components/overview/StatusPie.vue'
import WaterBar from '@/components/overview/WaterBar.vue'
import { podSegments, segmentsOf, type Segment } from '@/components/overview/labels'
import type { K8sCluster, NamespaceAllocation, PlatformTenant } from '@/types'
import type { PlatformOverview } from '@/types/overview'

const perm = usePermission()

const loading = ref(false)
const clusters = ref<K8sCluster[]>([])
const tenants = ref<PlatformTenant[]>([])
const allocations = ref<NamespaceAllocation[]>([])
const ov = ref<PlatformOverview | null>(null)
const canOverview = computed(() => perm.has(apiCodes.overviewView))

async function load(): Promise<void> {
  loading.value = true
  try {
    // 各块对应各自的端点：无权限点则跳过，避免 403 连弹。门控用的是 **API 域** 码。
    // 有总览码时，租户 / 分配数由总览接口给出，不再单独拉两份全量列表。
    ;[clusters.value, tenants.value, allocations.value, ov.value] = await Promise.all([
      perm.has(apiCodes.clusterManage) ? clusterApi.list() : Promise.resolve([] as K8sCluster[]),
      !canOverview.value && perm.has(apiCodes.tenantRead) ? tenantApi.list() : Promise.resolve([] as PlatformTenant[]),
      !canOverview.value && perm.has(apiCodes.allocationList) ? tenantApi.namespaceList() : Promise.resolve([] as NamespaceAllocation[]),
      canOverview.value ? overviewApi.platform().catch(() => null) : Promise.resolve(null),
    ])
  } finally {
    loading.value = false
  }
}

const pct = (used: number | null | undefined, total: number | null | undefined): string =>
  used == null || total == null || total <= 0 ? '—' : `${((used / total) * 100).toFixed(1)}%`

const workloadSegments = computed<Segment[]>(() => {
  const w = ov.value?.workloads
  return w ? [{ label: '不健康', value: w.unhealthy, tone: 'danger' }] : []
})
const tenantSegments = computed<Segment[]>(() => {
  const t = ov.value?.tenants
  return t
    ? [
        { label: '启用', value: t.enabled, tone: 'success' },
        { label: '停用', value: t.disabled, tone: 'warning' },
      ]
    : []
})
const nodePie = computed<Segment[]>(() => {
  const n = ov.value?.nodes
  return n
    ? [
        { label: 'Ready', value: n.ready, tone: 'success' },
        { label: 'NotReady', value: n.notReady, tone: 'danger' },
      ]
    : []
})
const nsPie = computed<Segment[]>(() => {
  const n = ov.value?.namespaces
  return n
    ? [
        { label: '已分配', value: n.allocated, tone: 'success' },
        { label: '未分配', value: n.unallocated, tone: 'warning' },
        { label: '平台系统', value: n.system, tone: 'info' },
      ]
    : []
})
const nodeNote = computed(() => {
  const c = ov.value?.capacity
  return c ? `整体 requests：CPU ${pct(c.cpuRequest, c.cpuAllocatable)} · 内存 ${pct(c.memRequest, c.memAllocatable)}` : '无可用集群'
})
const nsNote = computed(() => {
  const q = ov.value?.quota
  return q ? `配额整体用量：CPU ${pct(q.cpuUsed, q.cpuHard)} · 内存 ${pct(q.memUsed, q.memHard)}` : '没有命名空间配置配额'
})

const enabledClusters = computed(() => clusters.value.filter((c) => c.enabled === 1).length)
const enabledTenants = computed(() => tenants.value.filter((t) => t.status === 1).length)

const stats = computed(() => [
  { label: '集群', value: clusters.value.length, sub: `启用 ${enabledClusters.value}` },
  { label: '租户', value: tenants.value.length, sub: `启用 ${enabledTenants.value}` },
  { label: '命名空间分配', value: allocations.value.length, sub: '跨集群总计' },
])

function statusTag(status?: K8sCluster['status']): { type: 'success' | 'danger' | 'info' | 'warning'; text: string } {
  switch (status) {
    case 'CONNECTED': return { type: 'success', text: '已连接' }
    case 'ERROR': return { type: 'danger', text: '错误' }
    case 'CONNECTING': return { type: 'warning', text: '连接中' }
    default: return { type: 'info', text: status ?? '未知' }
  }
}

const quickActions = computed(() =>
  [
    { title: '新增集群', path: '/clusters', icon: 'Monitor', perm: pageCodes.cluster },
    { title: '分配命名空间', path: '/namespaces', icon: 'FolderOpened', perm: pageCodes.namespace.list },
    { title: '新建租户', path: '/tenants', icon: 'OfficeBuilding', perm: pageCodes.tenant.list },
    { title: '查审计', path: '/audit', icon: 'Document', perm: pageCodes.audit },
  ].filter((a) => perm.has(a.perm)),
)

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">平台总览</h2>
        <p class="ph-desc">集群健康、平台对象与最近去过的地方</p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <QuickActions :actions="quickActions" />

    <template v-if="ov">
      <!-- 健康条 -->
      <div class="panel health-bar">
        <div class="hb-item">
          <span class="hb-label">平台服务</span>
          <el-tooltip :disabled="!ov.services.down.length" :content="`异常：${ov.services.down.join('、')}`">
            <b :class="ov.services.down.length ? 'bad' : 'good'">{{ ov.services.up }}/{{ ov.services.total }}</b>
          </el-tooltip>
        </div>
        <div class="hb-item">
          <span class="hb-label">集群</span>
          <b class="good">已连接 {{ ov.clusters.connected }}</b>
          <el-tooltip v-if="ov.clusters.unavailable" :content="ov.clusters.unavailableNames.join('、')">
            <b class="bad">不可用 {{ ov.clusters.unavailable }}</b>
          </el-tooltip>
        </div>
        <div class="hb-item">
          <span class="hb-label">告警触发中</span>
          <b :class="ov.alertsFiring?.total ? 'bad' : 'good'">{{ ov.alertsFiring?.total ?? '—' }}</b>
          <span v-if="ov.alertsFiring?.byStatus.CRITICAL" class="hb-sub">严重 {{ ov.alertsFiring.byStatus.CRITICAL }}</span>
        </div>
      </div>

      <!-- 资源汇总 -->
      <div class="ov-grid5">
        <CountCard title="应用" :total="ov.applications?.total ?? null" :segments="segmentsOf('app', ov.applications)" />
        <CountCard title="中间件" :total="ov.middleware?.total ?? null" :segments="segmentsOf('middleware', ov.middleware)" />
        <CountCard title="工作负载" :total="ov.workloads?.total ?? null" :segments="workloadSegments" />
        <CountCard title="Pod" :total="ov.pods?.total ?? null" :segments="podSegments(ov.pods)" />
        <CountCard title="租户" :total="ov.tenants.total" :segments="tenantSegments">
          <span>成员 {{ ov.tenants.members }}</span>
          <span>已分配命名空间 {{ ov.tenants.allocatedNamespaces }}</span>
        </CountCard>
      </div>

      <div class="panel ov-water-row">
        <WaterBar label="集群 CPU（requests / 可分配）" :used="ov.capacity?.cpuRequest" :total="ov.capacity?.cpuAllocatable" unit="core" missing-text="—" />
        <WaterBar label="集群内存（requests / 可分配）" :used="ov.capacity?.memRequest" :total="ov.capacity?.memAllocatable" unit="byte" missing-text="—" />
      </div>

      <div class="ov-grid2">
        <StatusPie title="节点" :data="nodePie" :note="nodeNote" />
        <StatusPie title="命名空间" :data="nsPie" :note="nsNote" />
      </div>
    </template>

    <!-- 有总览权限但取数失败：明说，不退回旧摘要（那里的租户 / 分配数本次没拉，会显示成 0） -->
    <el-alert
      v-else-if="canOverview && !loading"
      type="warning"
      :closable="false"
      title="平台总览数据暂时取不到"
      description="其余区块不受影响，可点右上角刷新重试。"
      class="ov-load-error"
    />

    <!-- 统计（无总览权限时的旧摘要） -->
    <div v-else-if="!canOverview" class="stat-grid">
      <div v-for="s in stats" :key="s.label" class="panel stat-card">
        <div class="stat-value">{{ s.value }}</div>
        <div class="stat-label">{{ s.label }}<span class="stat-sub">{{ s.sub }}</span></div>
      </div>
    </div>

    <!-- 集群状态 -->
    <h3 class="section-title">集群状态</h3>
    <el-table :data="clusters" class="panel" empty-text="还没有接入集群">
      <el-table-column prop="clusterName" label="名称" min-width="180" />
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status).type" size="small" effect="plain">{{ statusTag(row.status).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="120">
        <template #default="{ row }">{{ row.version || '—' }}</template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">{{ row.enabled === 1 ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push({ name: 'cluster-detail', query: { clusterId: row.clusterId } })">
            概览
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 最近访问 -->
    <h3 class="section-title">最近访问</h3>
    <RecentVisits face="platform" />
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.ph-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
}
.ph-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}
.stat-card {
  padding: 18px;
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-1);
  line-height: 1.1;
}
.stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-2);
}
.stat-sub {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-3);
}
.ov-load-error { margin-bottom: 14px; }
.health-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 36px;
  padding: 14px 18px;
  margin-bottom: 14px;
}
.hb-item { display: flex; align-items: baseline; gap: 8px; font-size: 13px; }
.hb-label { color: var(--text-2); }
.hb-sub { color: var(--text-3); font-size: 12px; }
.hb-item b { font-size: 16px; }
.hb-item b.good { color: var(--success); }
.hb-item b.bad { color: var(--danger); }
.ov-grid5 {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}
.ov-grid2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.ov-water-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  padding: 16px 18px;
  margin: 14px 0;
}
@media (max-width: 1200px) {
  .ov-grid5 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
.section-title {
  margin: 26px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-2);
}
</style>
