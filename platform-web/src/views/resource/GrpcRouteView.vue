<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import MeshStatusBanner from '@/components/mesh/MeshStatusBanner.vue'
import { grpcRouteApi } from '@/api'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { useResourceContext } from '@/stores/context'
import type { K8sGrpcRule, K8sGrpcRoute, K8sRouteParentRef } from '@/types'
import { fmtDate } from '@/utils/format'

/**
 * GRPCRoute 列表（命名空间级、租户域）。命名空间来自顶栏分配上下文（同 ServiceMonitor）。
 * <p>与 HTTPRoute 的差别在匹配维度：gRPC 按 <b>服务名/方法名 + metadata</b> 路由，没有 URL 路径。
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
const rows = ref<K8sGrpcRoute[]>([])

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    rows.value = await grpcRouteApi.list(ctxParams.value)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await mesh.refresh()
  await refresh()
}

function goCreate(): void { router.push({ name: 'httproute-editor' }) }
function goEdit(row: K8sGrpcRoute): void { router.push({ name: 'httproute-editor', query: { name: row.name } }) }

async function onDelete(row: K8sGrpcRoute): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 GRPCRoute「${row.name}」？其匹配的 gRPC 调用将回落到该 Gateway 的其他路由或失败。`,
      '提示',
      { type: 'warning' },
    )
  } catch { return }
  try {
    await grpcRouteApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

// ---------- 详情抽屉 ----------
const drawerVisible = ref(false)
const detail = ref<K8sGrpcRoute | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

function openDetail(row: K8sGrpcRoute): void {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await grpcRouteApi.getYaml(detail.value.name, ctxParams.value)
    } catch { /* 拦截器已提示 */ }
  }
})

// ---------- 展示辅助 ----------
/** 父引用摘要：namespace/name[:sectionName] */
function parentText(p: K8sRouteParentRef): string {
  const ns = p.namespace ? `${p.namespace}/` : ''
  const sec = p.sectionName ? `:${p.sectionName}` : ''
  return `${ns}${p.name ?? '?'}${sec}`
}

/** 单条 rule 的摘要：服务/方法匹配 → 后端 */
function ruleText(rule: K8sGrpcRule): string {
  const matches = rule.matches ?? []
  const matchText = matches.length
    ? matches.map((m) => {
        // gRPC 按 服务名/方法名 路由，没有 URL 路径
        const svc = m.method?.service ?? ''
        const mth = m.method?.method ?? ''
        const method = [svc, mth].filter(Boolean).join('/')
        const headers = (m.headers ?? []).length
        return [method || '*', headers ? `(${headers} 个 metadata)` : ''].filter(Boolean).join(' ')
      }).join(' | ')
    : '*'
  const backends = (rule.backendRefs ?? [])
    .map((b) => `${b.namespace ? b.namespace + '/' : ''}${b.name}:${b.port ?? '?'}${b.weight != null ? `(w=${b.weight})` : ''}`)
    .join(', ')
  return `${matchText} → ${backends || '—'}`
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
    <PageHeader title="GRPCRoute" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready || !canCreate" @click="goCreate">创建 GRPCRoute</el-button>
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
      title="该命名空间下暂无 GRPCRoute"
      description="GRPCRoute 挂载到 Gateway 上，按 gRPC 服务名/方法名 + metadata 分发流量（gateway.networking.k8s.io/v1，CRD 自 Gateway API v1.1 GA）。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="rows" stripe>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="主机名" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="h in (row.hostnames ?? [])" :key="h" size="small" effect="plain" class="tag-inline">{{ h }}</el-tag>
            <span v-if="!(row.hostnames ?? []).length" class="muted">全部</span>
          </template>
        </el-table-column>
        <el-table-column label="父 Gateway" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="(p, i) in (row.parentRefs ?? [])" :key="i" size="small" type="info" effect="plain" class="tag-inline">
              {{ parentText(p) }}
            </el-tag>
            <span v-if="!(row.parentRefs ?? []).length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="规则数" width="90">
          <template #default="{ row }">{{ (row.rules ?? []).length }}</template>
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
    <el-drawer v-model="drawerVisible" :title="`GRPCRoute · ${detail?.name ?? ''}`" size="720px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="主机名">{{ (detail?.hostnames ?? []).join(', ') || '全部主机名' }}</el-descriptions-item>
            <el-descriptions-item label="父 Gateway">{{ (detail?.parentRefs ?? []).map(parentText).join(', ') || '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-title">规则</div>
          <el-table :data="detail?.rules ?? []" stripe size="small">
            <el-table-column label="#" type="index" width="50" />
            <el-table-column label="名称" width="100">
              <template #default="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>
            <el-table-column label="匹配 → 后端" min-width="360">
              <template #default="{ row }"><span class="mono">{{ ruleText(row) }}</span></template>
            </el-table-column>
            <el-table-column label="过滤器" width="90">
              <template #default="{ row }">{{ (row.filters ?? []).length }}</template>
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
