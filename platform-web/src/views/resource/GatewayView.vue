<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import MeshStatusBanner from '@/components/mesh/MeshStatusBanner.vue'
import { gatewayApi } from '@/api'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { useResourceContext } from '@/stores/context'
import type { K8sCondition, K8sGateway, K8sGatewayListener, K8sGatewayRouteGroupKind } from '@/types'
import { fmtDate } from '@/utils/format'

/**
 * Gateway 列表（命名空间级、租户域）。命名空间来自顶栏分配上下文（同 ServiceMonitor）。
 */
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

/** 网格状态（横幅 + create 门禁）：租户侧沿能力快照派生，ambient 仅在平台侧可知 */
const mesh = useMeshStatus(computed(() => state.clusterId ?? null))
const canCreate = computed(() => mesh.hasGatewayApi.value)

const loading = ref(false)
const rows = ref<K8sGateway[]>([])

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    rows.value = await gatewayApi.list(ctxParams.value)
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

// ---------- 创建 / 编辑：跳独立编辑页 ----------
function goCreate(): void { router.push({ name: 'gateway-editor' }) }
/** 「创建 Waypoint」：只是带 waypoint 标准形状预填的同一张表单（?waypoint=1）。
 *  waypoint 不是独立资源类型 —— 它就是一个 gatewayClassName 为 istio-waypoint 的普通 Gateway。 */
function goCreateWaypoint(): void { router.push({ name: 'gateway-editor', query: { waypoint: '1' } }) }
function goEdit(row: K8sGateway): void { router.push({ name: 'gateway-editor', query: { name: row.name } }) }

// ---------- 删除 ----------
async function onDelete(row: K8sGateway): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 Gateway「${row.name}」？挂载到它的 Route 将失去父资源（流量入口随之下线）。`,
      '提示',
      { type: 'warning' },
    )
  } catch { return }
  try {
    await gatewayApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

// ---------- 详情抽屉（概览 / YAML 只读全保真） ----------
const drawerVisible = ref(false)
const detail = ref<K8sGateway | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

function openDetail(row: K8sGateway): void {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await gatewayApi.getYaml(detail.value.name, ctxParams.value)
    } catch { /* 拦截器已提示 */ }
  }
})

// ---------- 展示辅助 ----------
/** listener 摘要：protocol:port/hostname [tlsMode] */
function listenerText(l: K8sGatewayListener): string {
  const host = l.hostname ? ` · ${l.hostname}` : ''
  const tls = l.tls?.mode ? ` [${l.tls.mode}]` : ''
  return `${l.protocol ?? '?'}:${l.port ?? '?'}${host}${tls}`
}

/** Programmed 条件的 status（列表列用；无该条件 → null，模板据此显示「—」） */
function programmedStatus(conditions?: K8sCondition[] | null): string | null {
  return (conditions ?? []).find((c) => c.type === 'Programmed')?.status ?? null
}

/** RouteGroupKind 列表 → 「HTTPRoute, GRPCRoute」 */
function kindText(kinds?: K8sGatewayRouteGroupKind[] | null): string {
  return (kinds ?? []).map((k) => k.kind ?? '').filter(Boolean).join(', ')
}

// ---------- 上下文联动 ----------
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
    <PageHeader title="Gateway" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button :disabled="!ready || !canCreate" @click="goCreateWaypoint">创建 Waypoint</el-button>
      <el-button type="primary" :disabled="!ready || !canCreate" @click="goCreate">创建 Gateway</el-button>
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
      title="该命名空间下暂无 Gateway"
      description="Gateway 由 Gateway API 控制器接管（gateway.networking.k8s.io/v1 CRD）；集群未安装时操作会提示错误。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="rows" stripe>
        <el-table-column label="名称" min-width="180">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="GatewayClass" min-width="160">
          <template #default="{ row }"><code class="mono">{{ row.gatewayClassName ?? '—' }}</code></template>
        </el-table-column>
        <el-table-column label="监听器" min-width="300">
          <template #default="{ row }">
            <el-tag v-for="(l, i) in (row.listeners ?? [])" :key="i" size="small" effect="plain" class="listener-tag">
              {{ listenerText(l) }}
            </el-tag>
            <span v-if="!(row.listeners ?? []).length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag
              v-if="programmedStatus(row.conditions)"
              size="small"
              :type="programmedStatus(row.conditions) === 'True' ? 'success' : 'danger'"
            >{{ programmedStatus(row.conditions) }}</el-tag>
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
    <el-drawer v-model="drawerVisible" :title="`Gateway · ${detail?.name ?? ''}`" size="680px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="GatewayClass">
              <code class="mono">{{ detail?.gatewayClassName ?? '—' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-title">监听器</div>
          <el-table :data="detail?.listeners ?? []" stripe size="small">
            <el-table-column prop="name" label="名称" width="110" />
            <el-table-column prop="protocol" label="协议" width="80" />
            <el-table-column prop="port" label="端口" width="70" />
            <el-table-column label="主机名" min-width="140">
              <template #default="{ row }">{{ row.hostname || '—' }}</template>
            </el-table-column>
            <el-table-column label="TLS" width="110">
              <template #default="{ row }">{{ row.tls?.mode ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="允许的 Route" min-width="150">
              <template #default="{ row }">
                <span class="mono">
                  {{ row.allowedRoutes?.namespaces?.from ?? 'Same' }}
                  <template v-if="kindText(row.allowedRoutes?.kinds)">（{{ kindText(row.allowedRoutes?.kinds) }}）</template>
                </span>
              </template>
            </el-table-column>
          </el-table>

          <div class="section-title">监听器状态</div>
          <el-table :data="detail?.listenerStatuses ?? []" stripe size="small">
            <el-table-column prop="name" label="名称" width="110" />
            <el-table-column prop="attachedRoutes" label="已挂载 Route" width="120" />
            <el-table-column label="支持 Kind" min-width="180">
              <template #default="{ row }">{{ kindText(row.supportedKinds) || '—' }}</template>
            </el-table-column>
          </el-table>

          <div class="section-title">状态条件</div>
          <el-table :data="detail?.conditions ?? []" stripe size="small">
            <el-table-column prop="type" label="Type" width="140" />
            <el-table-column prop="status" label="Status" width="90" />
            <el-table-column prop="reason" label="Reason" min-width="140" />
            <el-table-column prop="message" label="Message" min-width="200" show-overflow-tooltip />
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
.listener-tag { margin-right: 4px; margin-bottom: 2px; }
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
