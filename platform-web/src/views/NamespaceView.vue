<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, Refresh } from '@element-plus/icons-vue'
import { clusterApi, namespaceApi } from '@/api'
import type { K8sCluster, NamespaceView } from '@/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const router = useRouter()
const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
const loading = ref(false)
const list = ref<NamespaceView[]>([])

const enabledClusters = computed(() => clusters.value.filter((c) => c.enabled === 1))

async function load(): Promise<void> {
  if (!clusterId.value) {
    list.value = []
    return
  }
  loading.value = true
  try {
    list.value = await namespaceApi.list(clusterId.value)
  } finally {
    loading.value = false
  }
}

watch(clusterId, load)

function phaseTag(phase?: string | null): { type: 'success' | 'danger' | 'info' | 'warning'; text: string } {
  switch (phase) {
    case 'Active': return { type: 'success', text: 'Active' }
    case 'Terminating': return { type: 'warning', text: 'Terminating' }
    default: return { type: 'info', text: phase ?? '未知' }
  }
}

// ---------- 创建 / 编辑 / 详情 → 独立页面 ----------
function goCreate(): void {
  router.push({ name: 'namespace-editor', query: { clusterId: clusterId.value } })
}
function goDetail(row: NamespaceView): void {
  router.push({ name: 'namespace-detail', query: { clusterId: clusterId.value, name: row.name } })
}
function goEdit(row: NamespaceView): void {
  router.push({ name: 'namespace-editor', query: { clusterId: clusterId.value, name: row.name } })
}

// ---------- 行操作约束（禁用 + tooltip 说明原因） ----------
function canDelete(row: NamespaceView): boolean {
  return row.editable && !row.allocatedTenantName
}
function deleteHint(row: NamespaceView): string {
  if (!row.editable) return '受保护的系统命名空间不可删除'
  if (row.allocatedTenantName) return `该命名空间已分配给租户「${row.allocatedTenantName}」，请先取消分配`
  return ''
}

async function onDelete(row: NamespaceView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除命名空间「${row.name}」？非受保护系统命名空间且未分配给任何租户的可删除。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await namespaceApi.delete({ clusterId: clusterId.value, namespace: row.name })
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器提示 */
  }
}

// ---------- 行操作下拉：查看 / 编辑 / 删除 ----------
function onRowCommand(cmd: string, row: NamespaceView): void {
  switch (cmd) {
    case 'view': goDetail(row); break
    case 'edit': goEdit(row); break
    case 'delete': void onDelete(row); break
  }
}

onMounted(async () => {
  clusters.value = await clusterApi.list()
  const first = enabledClusters.value[0]
  if (first) {
    clusterId.value = first.clusterId // watch 触发 load
  }
})
</script>

<template>
  <div>
    <PageHeader title="命名空间管理" description="集群级命名空间：创建 / 编辑（描述、标签、配额、限制范围）/ 概览">
      <el-select v-model="clusterId" placeholder="选择启用状态的集群" style="width: 240px">
        <el-option v-for="c in enabledClusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :icon="Refresh" circle :disabled="!clusterId" @click="load" />
      <el-button type="primary" :disabled="!clusterId" @click="goCreate">创建命名空间</el-button>
    </PageHeader>

    <EmptyState
      v-if="!loading && list.length === 0"
      title="暂无命名空间"
      description="点击右上「创建命名空间」新建，或选择其他集群查看。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }">
            <code class="res-name name-link" @click="goDetail(row)">{{ row.name }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="phaseTag(row.phase).type">{{ phaseTag(row.phase).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTimestamp) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.managedBy" size="small">平台创建</el-tag>
            <span v-else>集群既有</span>
          </template>
        </el-table-column>
        <el-table-column label="已分配租户" min-width="140">
          <template #default="{ row }">
            <el-tag v-if="row.allocatedTenantName" size="small" type="info">{{ row.allocatedTenantName }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column width="64" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: string) => onRowCommand(cmd, row)">
              <el-button link type="primary" :icon="MoreFilled" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="view">查看</el-dropdown-item>
                  <el-tooltip :disabled="row.editable" content="受保护的系统命名空间不可编辑" placement="left">
                    <span>
                      <el-dropdown-item command="edit" :disabled="!row.editable">编辑</el-dropdown-item>
                    </span>
                  </el-tooltip>
                  <el-tooltip :disabled="canDelete(row)" :content="deleteHint(row)" placement="left">
                    <span>
                      <el-dropdown-item command="delete" :disabled="!canDelete(row)" divided style="color: var(--el-color-danger)">删除</el-dropdown-item>
                    </span>
                  </el-tooltip>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.table-panel { padding: 8px; }
.res-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 13px; color: var(--text-1); }
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
</style>
