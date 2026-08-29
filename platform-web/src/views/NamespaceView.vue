<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, namespaceApi } from '@/api'
import type { K8sCluster, NamespaceView } from '@/types'
import { fmtDate } from '@/utils/format'

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

async function onDelete(row: NamespaceView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除命名空间「${row.name}」？仅平台创建（带 managed-by 标签）且未分配给任何租户的命名空间可删除。`,
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
    <div class="toolbar">
      <el-select v-model="clusterId" placeholder="选择启用状态的集群" style="width: 240px">
        <el-option v-for="c in enabledClusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="name" label="命名空间" min-width="160" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="phaseTag(row.phase).type">{{ phaseTag(row.phase).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ fmtDate(row.creationTimestamp) }}</template>
      </el-table-column>
      <el-table-column label="管理方式" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.managedBy" size="small">平台管理</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="已分配租户" min-width="140">
        <template #default="{ row }">
          <el-tag v-if="row.allocatedTenantName" size="small" type="info">{{ row.allocatedTenantName }}</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.managedBy && !row.allocatedTenantName" link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
</style>
