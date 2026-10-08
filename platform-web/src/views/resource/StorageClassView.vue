<script setup lang="ts">
/**
 * 存储类（StorageClass，集群级，只读）。<b>仅平台管理员可见</b>。
 *
 * <p>为什么没有租户视图：StorageClass 无命名空间维度，无法像 PV 那样按 claimRef.namespace 收窄到租户
 * —— name / provisioner / parameters 是整集群共享的。故访问面整体收在权限表：
 * 三个端点的码在 V2026_10_07_3 从 tenant:storageclass:* 改为 platform:cluster:manage。
 * 前端对应地用 Page 域码 platform:page:storageclass.list 收敛菜单与路由。
 *
 * <p>存储类由集群/基础设施管理，本页不提供创建/编辑/删除入口。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { storageClassApi } from '@/api'
import type { K8sStorageClass } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sStorageClass[]>([])

/**集群级资源只需两级上下文（无 namespace），故不直接用 ready */
const clusterReady = computed(() => !!(state.loaded && state.tenantId && state.clusterId))
const ctx2 = computed(() => ({ tenantId: state.tenantId!, clusterId: state.clusterId! }))

async function refresh(): Promise<void> {
  if (!clusterReady.value) return
  loading.value = true
  try {
    list.value = await storageClassApi.list(ctx2.value)
  } finally {
    loading.value = false
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sStorageClass | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

function openDetail(row: K8sStorageClass): void {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    yamlText.value = await storageClassApi.getYaml(detail.value.name, ctx2.value)
  }
})

onMounted(() => {
  void load()
  void refresh()
})
watch(
  [() => [state.tenantId, state.clusterId], ready],
  () => {
    if (clusterReady.value) void refresh()
  },
)

const contextDesc = computed(() => {
  if (!clusterReady.value) return '请在顶栏选择租户 / 集群'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''}`
})
</script>

<template>
  <div>
    <PageHeader title="存储类" :description="contextDesc">
      <el-button @click="refresh" :disabled="!clusterReady">刷新</el-button>
    </PageHeader>

    <EmptyState
      v-if="clusterReady && list.length === 0 && !loading"
      title="该集群下暂无存储类"
      description="StorageClass 由集群/基础设施（CSI 驱动）管理，本页只读。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !clusterReady" :data="list" stripe>
        <el-table-column label="名称" min-width="220">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="供应方（provisioner）" min-width="240">
          <template #default="{ row }">{{ row.provisioner ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="回收策略" width="110">
          <template #default="{ row }">{{ row.reclaimPolicy ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="允许扩容" width="100">
          <template #default="{ row }">
            <span v-if="row.allowVolumeExpansion === true" class="yes">是</span>
            <span v-else class="muted">{{ row.allowVolumeExpansion === false ? '否' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`StorageClass · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="供应方">{{ detail?.provisioner ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="回收策略">{{ detail?.reclaimPolicy ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="允许扩容">
              {{ detail?.allowVolumeExpansion === true ? '是' : detail?.allowVolumeExpansion === false ? '否' : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
          <div class="drawer-tip">
            供应方参数（parameters）可能含凭据，仅在 YAML 中按原样展示，请谨慎外传。
          </div>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
.yes {
  color: var(--success);
}
.muted {
  color: var(--text-3);
}
.drawer-tip {
  margin-top: 14px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.6;
}
.yaml-block {
  margin: 0;
  padding: 14px;
  border-radius: 8px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
}
</style>
