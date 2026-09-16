<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { podMonitorApi } from '@/api'
import type { K8sPodMonitor } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'
import {MoreFilled} from "@element-plus/icons-vue";

const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sPodMonitor[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await podMonitorApi.list(ctxParams.value)
  } catch (e) {
    //集群未装 Prometheus Operator 时 CRD 404：拦截器已提示，这里保留空列表
    list.value = []
  } finally {
    loading.value = false
  }
}

// ---------- 创建 / 编辑：跳独立编辑页 ----------
function goCreate(): void {
  router.push({ name: 'podmonitor-editor' })
}
function goEdit(row: K8sPodMonitor): void {
  router.push({ name: 'podmonitor-editor', query: { name: row.name } })
}

// ---------- 删除 ----------
async function onDelete(row: K8sPodMonitor): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 PodMonitor「${row.name}」？Prometheus 将停止抓取对应目标。`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await podMonitorApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sPodMonitor | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sPodMonitor): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await podMonitorApi.getYaml(detail.value.name, ctxParams.value)
    } catch {
      /* 拦截器已提示 */
    }
  }
})

// ---------- 上下文联动：顶栏 chip 变化时刷新 ----------
onMounted(() => {
  void load()
  void refresh()
})
watch(
  () => [state.tenantId, state.clusterId, state.namespace],
  () => {
    if (ready.value) void refresh()
  },
)

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader title="PodMonitor" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="goCreate">创建 PodMonitor</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 PodMonitor"
      description="PodMonitor 由 Prometheus Operator 消费（monitoring.coreos.com/v1 CRD），直接抓取无 Service 的 Pod；集群未安装时操作会提示错误。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="端点" width="90">
          <template #default="{ row }">{{ (row.podMetricsEndpoints ?? []).length }} 个</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>

        <el-table-column width="64" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click">
              <el-button link type="primary" :icon="MoreFilled" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="goEdit(row)">编辑</el-dropdown-item>
                  <el-dropdown-item divided style="color: var(--el-color-danger)" @click="onDelete(row)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>

      </el-table>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`PodMonitor · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="选择标签">
              <el-tag
                v-for="(v, k) in detail?.matchLabels ?? {}"
                :key="k"
                size="small"
                effect="plain"
                class="label-tag"
              >{{ k }}={{ v }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
          <div class="section-title">抓取端点</div>
          <el-table :data="detail?.podMetricsEndpoints ?? []" stripe size="small">
            <el-table-column prop="port" label="Port" width="100" />
            <el-table-column prop="path" label="Path" min-width="160">
              <template #default="{ row }">{{ row.path ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="interval" label="Interval" width="100">
              <template #default="{ row }">{{ row.interval ?? '—' }}</template>
            </el-table-column>
          </el-table>
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
.label-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.muted {
  color: var(--text-3);
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  margin: 14px 0 8px;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
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
