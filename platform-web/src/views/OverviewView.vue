<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { clusterApi, tenantApi } from '@/api'
import type { K8sCluster, NamespaceAllocation, PlatformTenant } from '@/types'

const loading = ref(false)
const clusters = ref<K8sCluster[]>([])
const tenants = ref<PlatformTenant[]>([])
const allocations = ref<NamespaceAllocation[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    ;[clusters.value, tenants.value, allocations.value] = await Promise.all([
      clusterApi.list(),
      tenantApi.list(),
      tenantApi.namespaceList(),
    ])
  } finally {
    loading.value = false
  }
}

const enabledClusters = computed(() => clusters.value.filter((c) => c.enabled === 1).length)
const enabledTenants = computed(() => tenants.value.filter((t) => t.status === 1).length)

interface StatCard {
  label: string
  value: number
  sub: string
  icon: string
  tone: 'cyan' | 'violet' | 'green'
}

const stats = computed<StatCard[]>(() => [
  { label: '集群', value: clusters.value.length, sub: `启用 ${enabledClusters.value}`, icon: 'Monitor', tone: 'cyan' },
  { label: '租户', value: tenants.value.length, sub: `启用 ${enabledTenants.value}`, icon: 'OfficeBuilding', tone: 'violet' },
  { label: '命名空间分配', value: allocations.value.length, sub: '跨集群总计', icon: 'Connection', tone: 'green' },
])

const shortcuts = [
  { title: '集群管理', desc: '接入 / 开通 / 停用 K8s 集群', path: '/clusters', icon: 'Monitor' },
  { title: '命名空间管理', desc: '查看集群内命名空间，删除未分配命名空间', path: '/namespaces', icon: 'FolderOpened' },
  { title: '工作负载', desc: 'Deployment / StatefulSet / DaemonSet', path: '/resources/workloads', icon: 'Box' },
  { title: 'RBAC 模板', desc: '维护租户权限规则模板', path: '/templates', icon: 'CollectionTag' },
]

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">总览</h2>
        <p class="ph-desc">平台资源概况与快捷入口</p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <!-- 统计卡 -->
    <div class="stat-grid">
      <div v-for="s in stats" :key="s.label" class="panel stat-card">
        <div class="stat-icon" :class="`tone-${s.tone}`">
          <el-icon><component :is="s.icon" /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-value">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}<span class="stat-sub">{{ s.sub }}</span></div>
        </div>
      </div>
    </div>

    <!-- 快捷入口 -->
    <h3 class="section-title">快捷入口</h3>
    <div class="shortcut-grid">
      <router-link v-for="s in shortcuts" :key="s.path" :to="s.path" class="panel shortcut-card">
        <el-icon class="sc-icon"><component :is="s.icon" /></el-icon>
        <div>
          <div class="sc-title">{{ s.title }}</div>
          <div class="sc-desc">{{ s.desc }}</div>
        </div>
      </router-link>
    </div>
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
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
}
.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: #fff;
  flex-shrink: 0;
}
.tone-cyan {
  background: linear-gradient(135deg, #22d3ee, #0ea5e9);
  box-shadow: 0 4px 14px rgba(34, 211, 238, .3);
}
.tone-violet {
  background: linear-gradient(135deg, #8b5cf6, #6d28d9);
  box-shadow: 0 4px 14px rgba(139, 92, 246, .3);
}
.tone-green {
  background: linear-gradient(135deg, #34d399, #059669);
  box-shadow: 0 4px 14px rgba(52, 211, 153, .3);
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
  font-size: 22px;
  color: var(--accent);
  flex-shrink: 0;
}
.sc-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.sc-desc {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.5;
}

@media (max-width: 1200px) {
  .stat-grid { grid-template-columns: repeat(3, 1fr); }
  .shortcut-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
