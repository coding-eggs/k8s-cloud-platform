<script setup lang="ts">
/**
 * 平台视图总览：平台管理员的健康摘要 + 快捷操作 + 最近访问。
 *
 * 首屏只打 `/cluster/list` 一次。**不逐集群调 `/cluster/overview`** ——
 * 那是 9 次 list 的聚合，N 个集群就是 N 次重活，健康明细留给集群详情页。
 */
import { computed, onMounted, ref } from 'vue'
import { clusterApi, tenantApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import RecentVisits from '@/components/RecentVisits.vue'
import type { K8sCluster, NamespaceAllocation, PlatformTenant } from '@/types'

const perm = usePermission()

const loading = ref(false)
const clusters = ref<K8sCluster[]>([])
const tenants = ref<PlatformTenant[]>([])
const allocations = ref<NamespaceAllocation[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    // 三块统计各对应一个管理端点：无权限点则跳过，避免 403 连弹。
    // 门控用的是 **API 域** 码（拉数据的能力），不是页面码。
    ;[clusters.value, tenants.value, allocations.value] = await Promise.all([
      perm.has(apiCodes.clusterManage) ? clusterApi.list() : Promise.resolve([] as K8sCluster[]),
      perm.has(apiCodes.tenantRead) ? tenantApi.list() : Promise.resolve([] as PlatformTenant[]),
      perm.has(apiCodes.allocationList) ? tenantApi.namespaceList() : Promise.resolve([] as NamespaceAllocation[]),
    ])
  } finally {
    loading.value = false
  }
}

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

    <!-- 统计 -->
    <div class="stat-grid">
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

    <!-- 快捷操作 -->
    <h3 class="section-title">快捷操作</h3>
    <div class="shortcut-grid">
      <router-link v-for="a in quickActions" :key="a.path" :to="a.path" class="panel shortcut-card">
        <el-icon class="sc-icon"><component :is="a.icon" /></el-icon>
        <span class="sc-title">{{ a.title }}</span>
      </router-link>
    </div>

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
.section-title {
  margin: 26px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-2);
}
.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.shortcut-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  text-decoration: none;
  transition: all .15s;
}
.shortcut-card:hover {
  border-color: var(--accent);
  transform: translateY(-2px);
  box-shadow: 0 6px 18px color-mix(in srgb, var(--accent) 18%, transparent);
}
.sc-icon {
  font-size: 20px;
  color: var(--accent);
  flex-shrink: 0;
}
.sc-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
@media (max-width: 1200px) {
  .shortcut-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
