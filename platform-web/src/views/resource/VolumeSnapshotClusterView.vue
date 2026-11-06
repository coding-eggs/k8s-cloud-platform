<script setup lang="ts">
/**
 * 快照类与快照内容（集群级，只读，<b>仅平台管理员</b>；spec §6.6）。
 *
 * <p>快照类由组件（如 NFS CSI 驱动）建出；快照内容随快照由 snapshot-controller 维护。
 * 本页的用处是发现 Retain 策略留下的孤儿内容：绑定的快照已不存在、底层数据还占着存储。
 * 平台不提供删除 —— 清理孤儿内容会动存储后端，本批不做。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { usePaletteAction } from '@/composables/usePaletteAction'
import { volumeSnapshotClusterApi } from '@/api'
import type { K8sVolumeSnapshotClass, K8sVolumeSnapshotContent } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { fmtDate } from '@/utils/format'
import { formatBytes } from '@/utils/quantity'
import { deletionPolicyText } from '@/utils/volumeSnapshot'

const { state, currentCluster, load, setTenant, setCluster } = useResourceContext()

const clusterReady = computed(() => !!(state.loaded && state.clusterId))

/** 页面自带的集群选择器（与存储类页同形）：各租户下的集群摊平去重，选定后顺带写回归属租户 */
const clusterOptions = computed(() => {
  const seen = new Map<string, { clusterId: string; clusterName: string; tenantId: string }>()
  for (const t of state.tenants) {
    for (const c of t.clusters) {
      if (!seen.has(c.clusterId)) {
        seen.set(c.clusterId, { clusterId: c.clusterId, clusterName: c.clusterName, tenantId: t.tenantId })
      }
    }
  }
  return [...seen.values()]
})

function onClusterChange(clusterId: string): void {
  const opt = clusterOptions.value.find((o) => o.clusterId === clusterId)
  if (!opt) return
  setTenant(opt.tenantId)
  setCluster(opt.clusterId)
}

const tab = ref<'classes' | 'contents'>('classes')
const loading = ref(false)
const classes = ref<K8sVolumeSnapshotClass[]>([])
const contents = ref<K8sVolumeSnapshotContent[]>([])

async function refresh(): Promise<void> {
  if (!clusterReady.value) return
  loading.value = true
  try {
    const ctx = { clusterId: state.clusterId! }
    const [c, s] = await Promise.all([
      volumeSnapshotClusterApi.listClasses(ctx),
      volumeSnapshotClusterApi.listContents(ctx),
    ])
    classes.value = c
    contents.value = s
  } catch {
    /* 拦截器已提示（集群没装快照 CRD 时也会走到这里） */
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
  void refresh()
})
watch(
  () => [state.loaded, state.clusterId],
  () => {
    if (clusterReady.value) void refresh()
  },
)

const contextDesc = computed(() =>
  clusterReady.value ? `${currentCluster.value?.clusterName ?? ''} · 只读` : '请先选择集群',
)

// 命令面板（Ctrl+K）页内功能：路由 meta.actions 的落点，条件与页面上对应按钮的禁用条件一致
usePaletteAction({
  contents: { run: () => { tab.value = 'contents' } },
})
</script>

<template>
  <div>
    <PageHeader title="卷快照（集群）" :description="contextDesc">
      <el-select
        :model-value="state.clusterId ?? ''"
        placeholder="选择集群"
        class="cluster-picker"
        @update:model-value="onClusterChange(String($event))"
      >
        <template #prefix>
          <el-icon><Monitor /></el-icon>
        </template>
        <el-option v-for="c in clusterOptions" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :disabled="!clusterReady" @click="refresh">刷新</el-button>
    </PageHeader>

    <div class="panel table-panel">
      <el-tabs v-model="tab">
        <el-tab-pane label="快照类" name="classes">
          <el-table v-loading="loading" :data="classes" stripe empty-text="该集群没有快照类">
            <el-table-column label="名称" min-width="200">
              <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
            </el-table-column>
            <el-table-column label="驱动" min-width="180">
              <template #default="{ row }"><span class="muted">{{ row.driver ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="删除策略" min-width="260">
              <template #default="{ row }">
                <span :title="deletionPolicyText(row.deletionPolicy)">{{ row.deletionPolicy ?? '—' }}</span>
                <div class="muted small">{{ deletionPolicyText(row.deletionPolicy) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="默认" width="90">
              <template #default="{ row }">{{ row.isDefault ? '是' : '—' }}</template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="快照内容" name="contents">
          <el-table v-loading="loading" :data="contents" stripe empty-text="该集群没有快照内容">
            <el-table-column label="名称" min-width="220">
              <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
            </el-table-column>
            <el-table-column label="驱动" min-width="150">
              <template #default="{ row }"><span class="muted">{{ row.driver ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="删除策略" width="100">
              <template #default="{ row }">{{ row.deletionPolicy ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="绑定的快照" min-width="220">
              <template #default="{ row }">
                <span class="muted">{{ row.snapshotNamespace ?? '—' }}/{{ row.snapshotName ?? '—' }}</span>
                <!-- orphan=null 表示后端没能对照，不标 -->
                <StatusBadge v-if="row.orphan === true" label="孤儿" type="warning" class="orphan" />
              </template>
            </el-table-column>
            <el-table-column label="就绪" width="80">
              <template #default="{ row }">{{ row.readyToUse === true ? '是' : row.readyToUse === false ? '否' : '—' }}</template>
            </el-table-column>
            <el-table-column label="大小" width="110">
              <template #default="{ row }">{{ row.restoreSize ? formatBytes(row.restoreSize) : '—' }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px 16px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.muted {
  color: var(--text-3);
}
.small {
  font-size: 12px;
}
.orphan {
  margin-left: 8px;
}
.cluster-picker {
  width: 220px;
}
</style>
