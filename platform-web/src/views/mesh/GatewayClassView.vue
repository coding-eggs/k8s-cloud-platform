<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import MeshStatusBanner from '@/components/mesh/MeshStatusBanner.vue'
import { clusterApi, meshApi } from '@/api'
import { useMeshStatus } from '@/composables/useMeshStatus'
import type { K8sCluster, K8sCondition, K8sGatewayClass } from '@/types'
import { fmtDate } from '@/utils/format'

/**
 * GatewayClass 列表（集群级，平台管理面）。
 * <p>无命名空间维度，故本页的集群选择是页内的下拉（同「地址池」页），而不是顶栏级联。
 */
const router = useRouter()

// ---- 集群选择（集群级资源，无租户/命名空间维度）----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
  if (first && !clusterId.value) clusterId.value = first.clusterId
}

// ---- 网格状态（横幅 + create 门禁）----
const mesh = useMeshStatus(computed(() => clusterId.value || null))
/** 未探测到 Gateway API → 禁创建；未探测 → 同样保守禁用 */
const canCreate = computed(() => mesh.hasGatewayApi.value)

// ---- 列表 ----
const loading = ref(false)
const rows = ref<K8sGatewayClass[]>([])

async function load(): Promise<void> {
  if (!clusterId.value) return
  loading.value = true
  try {
    rows.value = await meshApi.gatewayClass.list({ clusterId: clusterId.value })
  } catch {
    rows.value = [] // 集群未装 Gateway API 时透传 404：拦截器已提示，这里保留空列表
  } finally {
    loading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await mesh.refresh()
  await load()
}

watch(clusterId, load)
onMounted(async () => {
  await loadClusters()
  if (clusterId.value) await load()
})

// ---- 行操作 ----
/** Accepted 条件的 status（列表列用；无该条件 → null，模板据此显示「—」） */
function acceptedStatus(conditions?: K8sCondition[] | null): string | null {
  return (conditions ?? []).find((c) => c.type === 'Accepted')?.status ?? null
}

function onCreate(): void {
  if (!clusterId.value) return
  router.push({ name: 'gatewayclass-editor', query: { clusterId: clusterId.value } })
}
function goEdit(row: K8sGatewayClass): void {
  router.push({ name: 'gatewayclass-editor', query: { clusterId: clusterId.value, name: row.name } })
}

const deleting = ref('')
async function onDelete(row: K8sGatewayClass): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 GatewayClass「${row.name}」？仍引用它的 Gateway 将失去控制器（现有路由会停止生效）。`,
      '删除 GatewayClass',
      { type: 'warning' },
    )
  } catch { return }
  deleting.value = row.name
  try {
    await meshApi.gatewayClass.delete(row.name, clusterId.value)
    ElMessage.success('已删除')
    await load()
  } catch { /* 拦截器提示 */ } finally {
    deleting.value = ''
  }
}

// ---- 详情抽屉（概览 / YAML 只读全保真）----
const drawerVisible = ref(false)
const detail = ref<K8sGatewayClass | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

function openDetail(row: K8sGatewayClass): void {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value && clusterId.value) {
    try {
      yamlText.value = await meshApi.gatewayClass.getYaml(detail.value.name, clusterId.value)
    } catch { /* 拦截器已提示 */ }
  }
})
</script>

<template>
  <div>
    <PageHeader title="GatewayClass" description="Gateway API 入口类别（集群级；决定由哪个控制器接管 Gateway）">
      <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :icon="Refresh" circle @click="load" />
      <el-button type="primary" :icon="Plus" :disabled="!canCreate" @click="onCreate">创建 GatewayClass</el-button>
    </PageHeader>

    <MeshStatusBanner
      v-if="clusterId"
      :has-gateway-api="mesh.hasGatewayApi.value"
      :gateway-api-versions="mesh.gatewayApiVersions.value"
      :has-istio="mesh.hasIstio.value"
      :istio-ambient="mesh.istioAmbient.value"
      :ambient-known="mesh.ambientKnown.value"
      :probed="mesh.probed.value"
      :can-refresh="mesh.canRefresh.value"
      @refresh="refreshAll"
    />

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="控制器" min-width="240">
        <template #default="{ row }"><code class="mono">{{ row.controllerName ?? '—' }}</code></template>
      </el-table-column>
      <el-table-column label="说明" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.description || '—' }}</template>
      </el-table-column>
      <el-table-column label="配置引用" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.parametersRef" class="mono muted">
            {{ row.parametersRef.kind }}/{{ row.parametersRef.name }}
          </span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="140">
        <template #default="{ row }">
          <el-tag
            v-if="acceptedStatus(row.conditions)"
            size="small"
            :type="acceptedStatus(row.conditions) === 'True' ? 'success' : 'danger'"
          >{{ acceptedStatus(row.conditions) }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
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
                <el-dropdown-item @click="goEdit(row)">编辑</el-dropdown-item>
                <el-dropdown-item divided style="color: var(--el-color-danger)" :loading="deleting === row.name" @click="onDelete(row)">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <EmptyState
      v-if="!loading && !rows.length"
      title="暂无 GatewayClass"
      description="该集群下没有 GatewayClass，或未安装 Gateway API（gateway.networking.k8s.io）。"
    />

    <!-- 详情：概览 / YAML（只读全保真 —— described/parametersRef 之外的字段都在这里） -->
    <el-drawer v-model="drawerVisible" :title="`GatewayClass · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="controllerName">
              <code class="mono">{{ detail?.controllerName ?? '—' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="说明">{{ detail?.description || '—' }}</el-descriptions-item>
            <el-descriptions-item label="配置引用">
              <span v-if="detail?.parametersRef" class="mono">
                {{ detail.parametersRef.group }}/{{ detail.parametersRef.kind }}/{{ detail.parametersRef.name }}
                <template v-if="detail.parametersRef.namespace">（ns: {{ detail.parametersRef.namespace }}）</template>
              </span>
              <span v-else class="muted">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
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
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  margin: 14px 0 8px;
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
