<script setup lang="ts">
/**
 * 持久卷（PersistentVolume，集群级，只读）。
 *
 * <p><b>只看得到属于本租户的 PV</b>：PV 是集群级对象、没有 namespace，但 spec.claimRef.namespace 有归属。
 * 后端 PersistentVolumeService 已按「claimRef.namespace ∈ 本租户在该集群的已分配命名空间」过滤，
 * 并按名寻址时对不可见的 PV 返回 not-found（不泄露存在性）。平台管理员（无租户帽）看到全量。
 * 未绑定的 PV 属集群存储池，对租户不显示 —— 本页不需要在前端重复这个判断。
 *
 * <p>PV 由集群/存储供应方管理，本页不提供创建/编辑/删除入口。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { persistentVolumeApi } from '@/api'
import type { K8sPersistentVolume } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { fmtDate } from '@/utils/format'
import { formatBytes } from '@/utils/quantity'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sPersistentVolume[]>([])

/**集群级资源只需两级上下文（无 namespace），故不直接用 ready —— 它多要求了 namespace */
const clusterReady = computed(() => !!(state.loaded && state.tenantId && state.clusterId))
const ctx2 = computed(() => ({ tenantId: state.tenantId!, clusterId: state.clusterId! }))

async function refresh(): Promise<void> {
  if (!clusterReady.value) return
  loading.value = true
  try {
    list.value = await persistentVolumeApi.list(ctx2.value)
  } finally {
    loading.value = false
  }
}

/**容量展示：基础单位字节 → 人性化（16Gi…）；null → — */
function fmtStorage(v?: number | null): string {
  return v == null ? '—' : formatBytes(v)
}

function phaseType(phase?: string | null): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (phase) {
    case 'Bound': return 'success'
    case 'Available': return 'info'
    case 'Released': return 'warning'
    case 'Failed': return 'danger'
    default: return 'neutral'
  }
}

/**前端过滤：按命名空间/存储类/状态收窄（PV 数量在单集群内通常不大，客户端过滤足够） */
const filterNs = ref('')
const filterSc = ref('')
const filterPhase = ref('')
const nsOptions = computed(() => [...new Set(list.value.map((p) => p.claimNamespace ?? '').filter(Boolean))].sort())
const scOptions = computed(() => [...new Set(list.value.map((p) => p.storageClassName ?? '').filter(Boolean))].sort())
const filtered = computed(() =>
  list.value.filter(
    (p) =>
      (!filterNs.value || p.claimNamespace === filterNs.value) &&
      (!filterSc.value || p.storageClassName === filterSc.value) &&
      (!filterPhase.value || p.phase === filterPhase.value),
  ),
)

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sPersistentVolume | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

function openDetail(row: K8sPersistentVolume): void {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    yamlText.value = await persistentVolumeApi.getYaml(detail.value.name, ctx2.value)
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
    <PageHeader title="持久卷" :description="contextDesc">
      <el-button @click="refresh" :disabled="!clusterReady">刷新</el-button>
    </PageHeader>

    <EmptyState
      v-if="clusterReady && list.length === 0 && !loading"
      title="该集群下没有属于本租户的持久卷"
      description="PV 由集群/存储供应方管理。本页只显示绑定到本租户已分配命名空间的 PV —— 未绑定的卷属集群存储池，对租户不可见。"
    />

    <div v-else class="panel table-panel">
      <div class="filters">
        <el-select v-model="filterNs" placeholder="全部命名空间" clearable style="width: 190px">
          <el-option v-for="n in nsOptions" :key="n" :label="n" :value="n" />
        </el-select>
        <el-select v-model="filterSc" placeholder="全部存储类" clearable style="width: 190px">
          <el-option v-for="s in scOptions" :key="s" :label="s" :value="s" />
        </el-select>
        <el-select v-model="filterPhase" placeholder="全部状态" clearable style="width: 150px">
          <el-option v-for="p in ['Bound', 'Available', 'Released', 'Failed']" :key="p" :label="p" :value="p" />
        </el-select>
        <span class="count">共 {{ filtered.length }} 个</span>
      </div>

      <el-table v-loading="loading || !clusterReady" :data="filtered" stripe>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="容量" width="100">
          <template #default="{ row }">{{ fmtStorage(row.capacity) }}</template>
        </el-table-column>
        <el-table-column label="访问模式" min-width="130">
          <template #default="{ row }">{{ (row.accessModes ?? []).join('，') || '—' }}</template>
        </el-table-column>
        <el-table-column label="存储类" min-width="150">
          <template #default="{ row }">{{ row.storageClassName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="回收策略" width="100">
          <template #default="{ row }">{{ row.reclaimPolicy ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><StatusBadge :label="row.phase ?? 'Unknown'" :type="phaseType(row.phase)" /></template>
        </el-table-column>
        <el-table-column label="绑定" min-width="200">
          <template #default="{ row }">
            <span v-if="row.claimName">{{ row.claimNamespace }} / {{ row.claimName }}</span>
            <span v-else class="muted">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`PersistentVolume · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="状态">
              <StatusBadge :label="detail?.phase ?? 'Unknown'" :type="phaseType(detail?.phase)" />
            </el-descriptions-item>
            <el-descriptions-item label="容量">{{ fmtStorage(detail?.capacity) }}</el-descriptions-item>
            <el-descriptions-item label="访问模式">{{ (detail?.accessModes ?? []).join('，') || '—' }}</el-descriptions-item>
            <el-descriptions-item label="存储类">{{ detail?.storageClassName ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="回收策略">{{ detail?.reclaimPolicy ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="绑定的 PVC">
              {{ detail?.claimName ? `${detail?.claimNamespace} / ${detail?.claimName}` : '未绑定' }}
            </el-descriptions-item>
            <el-descriptions-item label="标签">
              <el-tag
                v-for="(v, k) in detail?.labels ?? {}"
                :key="k"
                size="small"
                effect="plain"
                class="label-tag"
              >{{ k }}={{ v }}</el-tag>
              <span v-if="!Object.keys(detail?.labels ?? {}).length" class="muted">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
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
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 6px 10px;
}
.count {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
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
.label-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.muted {
  color: var(--text-3);
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
