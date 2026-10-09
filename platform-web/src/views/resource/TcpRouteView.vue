<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import MeshStatusBanner from '@/components/mesh/MeshStatusBanner.vue'
import { tcpRouteApi } from '@/api'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { useResourceContext } from '@/stores/context'
import type { K8sL4RouteRule, K8sRouteBackendRef, K8sRouteParentRef, K8sRouteParentStatus, K8sTcpRoute } from '@/types'
import { fmtDate } from '@/utils/format'

/**
 * TCPRoute 列表（命名空间级、租户域）。四层直转，无七层匹配。
 * <p>CRD 版本（v1 / v1alpha2）由后端按集群 capability 分派，前端不感知。
 */
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

const mesh = useMeshStatus(computed(() => state.clusterId ?? null))
const canCreate = computed(() => mesh.hasGatewayApi.value)

const loading = ref(false)
const rows = ref<K8sTcpRoute[]>([])

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    rows.value = await tcpRouteApi.list(ctxParams.value)
  } catch {
    rows.value = [] // 集群未装 Gateway API 时 CRD 404：拦截器已提示，这里保留空列表
  } finally {
    loading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await mesh.refresh()
  await refresh()
}

function goCreate(): void { router.push({ name: 'tcproute-editor' }) }
function goEdit(row: K8sTcpRoute): void { router.push({ name: 'tcproute-editor', query: { name: row.name } }) }

async function onDelete(row: K8sTcpRoute): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 TCPRoute「${row.name}」？该端口上的 TCP 连接将不再被转发（后端连接随之中断）。`,
      '提示',
      { type: 'warning' },
    )
  } catch { return }
  try {
    await tcpRouteApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

// ---------- 详情抽屉 ----------
const drawerVisible = ref(false)
const detail = ref<K8sTcpRoute | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

function openDetail(row: K8sTcpRoute): void {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await tcpRouteApi.getYaml(detail.value.name, ctxParams.value)
    } catch { /* 拦截器已提示 */ }
  }
})

// ---------- 展示辅助 ----------
function parentText(p: K8sRouteParentRef): string {
  const ns = p.namespace ? `${p.namespace}/` : ''
  const sec = p.sectionName ? `:${p.sectionName}` : ''
  return `${ns}${p.name ?? '?'}${sec}`
}

/** 一条规则的后端摘要：name:port(w=weight) */
function backendText(rule: K8sL4RouteRule): string {
  const list = (rule.backendRefs ?? [])
    .map((b: K8sRouteBackendRef) =>
      `${b.namespace ? b.namespace + '/' : ''}${b.name}:${b.port ?? '?'}${b.weight != null ? `(w=${b.weight})` : ''}`)
    .join(', ')
  return list || '—'
}

/** 首个父资源的 Accepted 条件状态（L4 路由的可用性判据） */
function acceptedStatus(statuses?: K8sRouteParentStatus[] | null): string | null {
  return (statuses ?? []).find((s) => (s.conditions ?? []).some((c) => c.type === 'Accepted'))
    ?.conditions?.find((c) => c.type === 'Accepted')?.status ?? null
}

onMounted(() => {
  void load()
  void refresh()
})
watch(
  [() => [state.tenantId, state.clusterId, state.namespace], ready],
  () => { if (ready.value) void refresh() },
)

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader title="TCPRoute" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready || !canCreate" @click="goCreate">创建 TCPRoute</el-button>
    </PageHeader>

    <MeshStatusBanner
      v-if="ready && state.clusterId"
      :has-gateway-api="mesh.hasGatewayApi.value"
      :gateway-api-versions="mesh.gatewayApiVersions.value"
      :has-istio="mesh.hasIstio.value"
      :istio-ambient="mesh.istioAmbient.value"
      :ambient-known="mesh.ambientKnown.value"
      :probed="mesh.probed.value"
      :can-refresh="mesh.canRefresh.value"
      @refresh="refreshAll"
    />

    <EmptyState
      v-if="ready && !rows.length && !loading"
      title="该命名空间下暂无 TCPRoute"
      description="TCPRoute 把某个监听器端口上的 TCP 连接按权重分给后端 Service（四层直转，不解七层协议）。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="rows" stripe>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="父 Gateway" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="(p, i) in (row.parentRefs ?? [])" :key="i" size="small" type="info" effect="plain" class="tag-inline">
              {{ parentText(p) }}
            </el-tag>
            <span v-if="!(row.parentRefs ?? []).length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="后端" min-width="260">
          <template #default="{ row }">
            <span class="mono">{{ backendText((row.rules ?? [])[0] ?? {}) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag
              v-if="acceptedStatus(row.parentStatuses)"
              size="small"
              :type="acceptedStatus(row.parentStatuses) === 'True' ? 'success' : 'danger'"
            >{{ acceptedStatus(row.parentStatuses) }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
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
                  <el-dropdown-item @click="openDetail(row)">查看</el-dropdown-item>
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
    <el-drawer v-model="drawerVisible" :title="`TCPRoute · ${detail?.name ?? ''}`" size="680px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="父 Gateway">{{ (detail?.parentRefs ?? []).map(parentText).join(', ') || '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-title">规则（L4 只允许一条）</div>
          <el-table :data="detail?.rules ?? []" stripe size="small">
            <el-table-column label="名称" width="110">
              <template #default="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>
            <el-table-column label="后端" min-width="320">
              <template #default="{ row }"><span class="mono">{{ backendText(row) }}</span></template>
            </el-table-column>
          </el-table>

          <div class="section-title">父资源状态</div>
          <el-table :data="detail?.parentStatuses ?? []" stripe size="small">
            <el-table-column label="父资源" min-width="160">
              <template #default="{ row }">{{ row.parentRef ? parentText(row.parentRef) : '—' }}</template>
            </el-table-column>
            <el-table-column prop="controllerName" label="控制器" min-width="200" />
            <el-table-column label="条件" min-width="180">
              <template #default="{ row }">
                <el-tag
                  v-for="c in (row.conditions ?? [])"
                  :key="c.type"
                  size="small"
                  :type="c.status === 'True' ? 'success' : 'danger'"
                  class="tag-inline"
                >{{ c.type }}: {{ c.status }}</el-tag>
              </template>
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
.table-panel { padding: 8px; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.res-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 13px; color: var(--text-1); }
.name-link { cursor: pointer; color: var(--accent); }
.name-link:hover { text-decoration: underline; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
.tag-inline { margin-right: 4px; margin-bottom: 2px; }
.section-title { font-size: 13px; font-weight: 600; color: var(--text-2); margin: 14px 0 8px; }
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
