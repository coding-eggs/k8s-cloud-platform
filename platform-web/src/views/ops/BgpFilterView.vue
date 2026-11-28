<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { usePaletteAction } from '@/composables/usePaletteAction'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { calicoApi } from '@/api'
import { useClusterCapability } from '@/composables/useClusterCapability'
import { useClusterListSelection } from '@/composables/useClusterListSelection'
import type { BgpFilter } from '@/types'
import { fmtDate } from '@/utils/format'

const router = useRouter()

// ---- 集群选择（Calico 集群级，无租户/命名空间维度）----
const { clusters, clusterId } = useClusterListSelection()

// ---- capability 门禁：hasCalico（无 Calico 能力时禁用创建）----
const capClusterId = computed(() => clusterId.value || null)
const { hasCalico, refresh: refreshCap } = useClusterCapability(capClusterId)

// ---- 列表（写权限 platform:cluster:manage，进本页即可写）----
const loading = ref(false)
const items = ref<BgpFilter[]>([])
let loadSequence = 0
async function load(): Promise<void> {
  const targetClusterId = clusterId.value
  const request = ++loadSequence
  if (!targetClusterId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await calicoApi.bgpFilter.list({ clusterId: targetClusterId })
    if (request === loadSequence) items.value = result
  } catch {
    if (request === loadSequence) items.value = [] // 拦截器已提示
  } finally {
    if (request === loadSequence) loading.value = false
  }
}

watch(clusterId, () => {
  items.value = []
  void load()
})

function onDetail(row: BgpFilter): void {
  router.push({ path: '/ops/bgpfilters/detail', query: { clusterId: clusterId.value, name: row.name } })
}
function onEdit(row: BgpFilter): void {
  router.push({ path: '/ops/bgpfilters/editor', query: { clusterId: clusterId.value, name: row.name } })
}
function onCreate(): void {
  if (!clusterId.value) return
  router.push({ path: '/ops/bgpfilters/editor', query: { clusterId: clusterId.value } })
}

const deleting = ref('')
async function onDelete(row: BgpFilter): Promise<void> {
  const targetClusterId = clusterId.value
  try {
    await ElMessageBox.confirm(
      `确认删除 BGP 过滤器「${row.name}」？若已被 BGPPeer 引用，对应 peering 的路由过滤将立即失效。`,
      '删除 BGP 过滤器',
      { type: 'warning' },
    )
  } catch { return }
  if (targetClusterId !== clusterId.value) return
  deleting.value = row.name
  try {
    await calicoApi.bgpFilter.delete(row.name, targetClusterId)
    ElMessage.success('已删除')
    await load()
  } catch { /* 拦截器提示 */ } finally {
    deleting.value = ''
  }
}
function ruleCount(list?: { length: number } | null): string {
  return list && list.length ? String(list.length) : '—'
}

// 命令面板（Ctrl+K）页内功能：路由 meta.actions 的落点，条件与页面上对应按钮的禁用条件一致
usePaletteAction({
  create: { run: onCreate, ready: () => hasCalico.value },
})
</script>

<template>
  <div>
    <PageHeader title="BGP 过滤器" description="Calico BGPFilter（集群级 CRD）">
      <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" :disabled="c.enabled !== 1" />
      </el-select>
      <el-button :icon="Refresh" circle @click="load" />
      <el-button type="primary" :icon="Plus" :disabled="!hasCalico" @click="onCreate">创建 BGP 过滤器</el-button>
    </PageHeader>

    <div v-if="clusterId && !hasCalico" class="cap-banner">
      <span>未探测到 Calico（projectcalico.org）能力：该集群可能未安装 Calico，或 API 能力快照过期。</span>
      <el-button size="small" @click="refreshCap">刷新能力</el-button>
    </div>

    <el-table v-loading="loading" :data="items" stripe class="list-table">
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <el-link type="primary" @click="onDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="导出 v4" width="90">
        <template #default="{ row }">{{ ruleCount(row.exportV4) }}</template>
      </el-table-column>
      <el-table-column label="导入 v4" width="90">
        <template #default="{ row }">{{ ruleCount(row.importV4) }}</template>
      </el-table-column>
      <el-table-column label="导出 v6" width="90">
        <template #default="{ row }">{{ ruleCount(row.exportV6) }}</template>
      </el-table-column>
      <el-table-column label="导入 v6" width="90">
        <template #default="{ row }">{{ ruleCount(row.importV6) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="160">
        <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
      </el-table-column>
      <el-table-column width="64" fixed="right">
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

    <EmptyState v-if="!loading && !items.length" title="暂无 BGP 过滤器" description="该集群下没有 BGPFilter，或尚未探测到 Calico 能力。" />
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
