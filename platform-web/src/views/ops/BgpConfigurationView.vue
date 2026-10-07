<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { clusterApi, calicoApi } from '@/api'
import { useClusterCapability } from '@/composables/useClusterCapability'
import { usePermission } from '@/stores/permission'
import type { K8sCluster, BgpConfiguration } from '@/types'
import { fmtDate } from '@/utils/format'

const router = useRouter()
const perm = usePermission()

// ---- 集群选择（Calico 集群级，无租户/命名空间维度）----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
  if (first && !clusterId.value) clusterId.value = first.clusterId
}

// ---- capability 门禁：hasCalico（无 Calico 能力时禁用创建）----
const capClusterId = computed(() => clusterId.value || null)
const { hasCalico, refresh: refreshCap } = useClusterCapability(capClusterId)

// ---- 列表（写操作仅平台管理员：platform:bgp:config:manage）----
const loading = ref(false)
const items = ref<BgpConfiguration[]>([])
async function load(): Promise<void> {
  if (!clusterId.value) return
  loading.value = true
  try {
    items.value = await calicoApi.bgpConfiguration.list({ clusterId: clusterId.value })
  } catch {
    items.value = [] // 拦截器已提示（含集群未装 Calico 的 404）
  } finally {
    loading.value = false
  }
}

watch(clusterId, load)
onMounted(async () => {
  await loadClusters()
  if (clusterId.value) await load()
})

function onDetail(row: BgpConfiguration): void {
  router.push({ path: '/ops/bgpconfigurations/detail', query: { clusterId: clusterId.value, name: row.name } })
}
function onEdit(row: BgpConfiguration): void {
  router.push({ path: '/ops/bgpconfigurations/editor', query: { clusterId: clusterId.value, name: row.name } })
}
function onCreate(): void {
  if (!clusterId.value) return
  router.push({ path: '/ops/bgpconfigurations/editor', query: { clusterId: clusterId.value } })
}

const deleting = ref('')
async function onDelete(row: BgpConfiguration): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 BGP 配置「${row.name}」？若为集群唯一配置，节点将回落到 Calico 内置默认值（AS 64512、mesh 开启），等同一次全局变更。`,
      '删除 BGP 配置',
      { type: 'warning' },
    )
  } catch { return }
  deleting.value = row.name
  try {
    await calicoApi.bgpConfiguration.delete(row.name, clusterId.value)
    ElMessage.success('已删除')
    await load()
  } catch { /* 拦截器提示 */ } finally {
    deleting.value = ''
  }
}
</script>

<template>
  <div>
    <PageHeader title="BGP 配置" description="Calico BGPConfiguration（集群级 CRD）——写操作仅平台管理员">
      <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :icon="Refresh" circle @click="load" />
      <el-button v-if="perm.isAdmin" type="primary" :icon="Plus" :disabled="!hasCalico" @click="onCreate">创建 BGP 配置</el-button>
    </PageHeader>

    <div v-if="clusterId && !hasCalico" class="cap-banner">
      <span>未探测到 Calico（projectcalico.org）能力：该集群可能未安装 Calico，或 API 能力快照过期。</span>
      <el-button size="small" @click="refreshCap">刷新能力</el-button>
    </div>

    <el-table v-loading="loading" :data="items" stripe class="list-table">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="onDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="AS 号" width="120">
        <template #default="{ row }"><code class="mono">{{ row.asNumber ?? '—' }}</code></template>
      </el-table-column>
      <el-table-column label="节点 Mesh" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.nodeToNodeMeshEnabled != null" size="small" :type="row.nodeToNodeMeshEnabled ? 'success' : 'info'">{{ row.nodeToNodeMeshEnabled ? '启用' : '禁用' }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="监听端口" width="100">
        <template #default="{ row }">{{ row.listenPort ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="日志级别" width="110">
        <template #default="{ row }"><span class="muted">{{ row.logSeverityScreen ?? '—' }}</span></template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="160">
        <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
      </el-table-column>
      <el-table-column v-if="perm.isAdmin" width="64" fixed="right">
        <template #default="{ row }">
          <el-dropdown trigger="click">
            <el-button link type="primary" :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="onDetail(row)">查看</el-dropdown-item>
                <el-dropdown-item @click="onEdit(row)">编辑</el-dropdown-item>
                <el-dropdown-item divided style="color: var(--el-color-danger)" :loading="deleting === row.name" @click="onDelete(row)">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <EmptyState v-if="!loading && !items.length" title="暂无 BGP 配置" description="该集群下没有 BGPConfiguration，或尚未探测到 Calico 能力。" />
  </div>
</template>

<style scoped>
.cap-banner {
  display: flex; align-items: center; gap: 12px; margin-bottom: 12px;
  padding: 10px 14px; border: 1px solid var(--el-color-warning-light-5); border-radius: 8px;
  background: var(--el-color-warning-light-9); color: var(--text-2); font-size: 13px;
}
.list-table { margin-top: 4px; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
</style>
