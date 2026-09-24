<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import { hpaApi } from '@/api'
import type { K8sHpa, K8sHpaMetricTarget } from '@/types'
import { useResourceContext } from '@/stores/context'
import { useClusterCapability } from '@/composables/useClusterCapability'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()

const loading = ref(false)
const list = ref<K8sHpa[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await hpaApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

// ---------- 集群 API 能力门禁 ----------
// useClusterCapability 返回普通对象：模板里也必须显式 .value（漏掉会让门禁静默恒真/恒假）
const cap = useClusterCapability(computed(() => state.clusterId))

const refreshingCap = ref(false)
async function onRefreshCapability(): Promise<void> {
  refreshingCap.value = true
  try {
    await cap.refresh()
  } catch {
    /* 拦截器已提示；对齐 ClusterView 先例吞掉 rejection */
  } finally {
    refreshingCap.value = false
  }
  await refresh()
}

// ---------- 创建 / 编辑 → 独立编辑页 ----------
function goCreate(): void {
  router.push({ name: 'hpa-editor' })
}
function goEdit(row: K8sHpa): void {
  router.push({ name: 'hpa-editor', query: { name: row.name } })
}

// ---------- 删除 ----------
async function onDelete(row: K8sHpa): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 HPA「${row.name}」？`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await hpaApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

// ---------- 行操作下拉：查看 / 编辑 / 删除 ----------
function onRowCommand(cmd: string, row: K8sHpa): void {
  switch (cmd) {
    case 'view': void openDetail(row); break
    case 'edit': goEdit(row); break
    case 'delete': void onDelete(row); break
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sHpa | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sHpa): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try { yamlText.value = await hpaApi.getYaml(detail.value.name, ctxParams.value) } catch { /* 拦截器已提示 */ }
  }
})

function targetText(t?: K8sHpaMetricTarget | null): string {
  if (!t) return ''
  if (t.type === 'Utilization') return `${t.averageUtilization}%`
  const v = t.value ?? t.averageValue
  return v == null ? '' : String(v)
}

function metricSummary(row: K8sHpa): string {
  const parts: string[] = []
  for (const m of row.metrics ?? []) {
    if (m.resource) parts.push(`${m.resource.name} ${targetText(m.resource.target)}`.trim())
    else if (m.containerResource) parts.push(`${m.containerResource.container}/${m.containerResource.name} ${targetText(m.containerResource.target)}`.trim())
    else if (m.pods) parts.push(`Pods:${m.pods.metricName}`)
    else if (m.object) parts.push(`Object:${m.object.metricName}`)
    else if (m.external) parts.push(`External:${m.external.metricName}`)
  }
  return parts.join('，') || '—'
}

// ---------- 上下文联动：顶栏 chip 变化时刷新 ----------
onMounted(() => { void load(); void refresh() })
watch(
  () => [state.tenantId, state.clusterId, state.namespace],
  () => { if (ready.value) void refresh() },
)

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader title="HPA" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready || !cap.metricsAvailable.value" @click="goCreate">创建 HPA</el-button>
    </PageHeader>

    <!-- 能力门禁提示（探测过且三类 metrics 全无 → 禁创建；未探测 → 软提示） -->
    <el-alert
      v-if="ready && cap.probed.value && !cap.metricsAvailable.value"
      type="warning"
      :closable="false"
      class="cap-banner"
    >
      <template #title>
        <div class="cap-banner-inner">
          <span>该集群未安装 metrics-server / prometheus-adapter，HPA 指标不可用，创建已禁用</span>
          <el-button size="small" type="primary" plain :loading="refreshingCap" @click="onRefreshCapability">刷新能力</el-button>
        </div>
      </template>
    </el-alert>
    <el-alert
      v-else-if="ready && !cap.probed.value && !cap.loading.value"
      type="info"
      :closable="false"
      class="cap-banner"
    >
      <template #title>
        <div class="cap-banner-inner">
          <span>集群 API 能力尚未探测，指标可用性未知；点「刷新能力」后按实际集群能力门禁。</span>
          <el-button size="small" type="primary" plain :loading="refreshingCap" @click="onRefreshCapability">刷新能力</el-button>
        </div>
      </template>
    </el-alert>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 HPA"
      description="点击右上「创建 HPA」为工作负载配置自动扩缩容。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }"><code class="res-name name-link" @click="onRowCommand('view', row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="目标工作负载" min-width="180">
          <template #default="{ row }">
            <span class="muted">{{ row.scaleTargetRef?.kind ?? '—' }} / </span><code class="res-name">{{ row.scaleTargetRef?.name ?? '—' }}</code>
          </template>
        </el-table-column>
        <el-table-column label="副本" width="150">
          <template #default="{ row }">
            {{ row.minReplicas ?? 1 }}–{{ row.maxReplicas ?? '—' }}
            <span v-if="row.currentReplicas != null" class="muted">（当前 {{ row.currentReplicas }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="指标" min-width="200">
          <template #default="{ row }"><span class="port-text">{{ metricSummary(row) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="64" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: string) => onRowCommand(cmd, row)">
              <el-button link type="primary" :icon="MoreFilled" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item divided style="color: var(--el-color-danger)" command="delete">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`HPA · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="目标工作负载">{{ detail?.scaleTargetRef?.kind ?? '—' }} / {{ detail?.scaleTargetRef?.name ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="副本范围">{{ detail?.minReplicas ?? 1 }} – {{ detail?.maxReplicas ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="当前副本">{{ detail?.currentReplicas ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
          <div class="section-title">指标</div>
          <div class="muted">{{ metricSummary(detail!) }}</div>
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
.table-panel { padding: 8px; }
.res-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 13px; color: var(--text-1); }
.port-text { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; color: var(--text-2); }
.muted { color: var(--text-3); }
.section-title { font-size: 13px; font-weight: 600; color: var(--text-2); margin: 14px 0 8px; }
.cap-banner { margin-bottom: 10px; }
.cap-banner-inner { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.yaml-block {
  margin: 0; padding: 14px; border-radius: 8px; background: var(--panel-hover);
  border: 1px solid var(--border); font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px; line-height: 1.6; color: var(--text-2); max-height: 60vh; overflow: auto; white-space: pre-wrap;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
</style>
