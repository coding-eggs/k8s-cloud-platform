<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { serviceApi } from '@/api'
import type { K8sService } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'
import {MoreFilled, Search} from "@element-plus/icons-vue";

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()
const route = useRoute()
/** 从工作负载详情跳来（?open=）时，首次加载后自动打开对应抽屉 */
let autoOpened = false

const loading = ref(false)
const list = ref<K8sService[]>([])
/** 名称模糊搜索（前端过滤） */
const keyword = ref('')
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter((r) => (r.name ?? '').toLowerCase().includes(kw))
})

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await serviceApi.list(ctxParams.value)
    // 首次加载后，若带 ?open= 则自动打开对应详情抽屉，并清掉该参数（避免后续刷新重复触发）
    if (!autoOpened && route.query.open) {
      autoOpened = true
      const target = list.value.find((s) => s.name === route.query.open)
      if (target) await openDetail(target)
      const q = { ...route.query }
      delete q.open
      router.replace({ query: q })
    }
  } finally {
    loading.value = false
  }
}

// ---------- 跳转编辑器（新建 / 编辑独立页） ----------
function goEditor(name: string | null): void {
  router.push(name ? `/resources/services/editor?name=${encodeURIComponent(name)}` : '/resources/services/editor')
}

// ---------- 删除 ----------
async function onDelete(row: K8sService): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 Service「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await serviceApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sService | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sService): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await serviceApi.getYaml(detail.value.name, ctxParams.value)
    } catch {
      /* 拦截器已提示 */
    }
  }
})

function portSummary(row: K8sService): string {
  return (row.ports ?? [])
    .map((p) => `${p.port}→${p.targetPort}${p.nodePort ? `:${p.nodePort}` : ''} ${p.protocol ?? 'TCP'}`)
    .join('，') || '—'
}

/** Cluster IP 展示：None=headless（无虚拟 IP）；空=— */
function clusterIpDisplay(row: K8sService | null): string {
  if (!row) return '—'
  if (row.clusterIp === 'None') return 'headless（无虚拟 IP）'
  return row.clusterIp ?? '—'
}

/** Service 类型 → tag 颜色 */
function typeTag(type?: string | null): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  switch (type) {
    case 'NodePort': return 'warning'
    case 'LoadBalancer': return 'success'
    case 'ExternalName': return 'danger'
    default: return 'info' // ClusterIP / 其它
  }
}

/** 字符串列表展示：空=—，否则中文逗号连接 */
function joinList(arr?: string[] | null): string {
  if (!arr || arr.length === 0) return '—'
  return arr.join('，')
}

/** selector → "k=v" chips；无选择器返回空数组 */
function selectorChips(row: K8sService | null): string[] {
  const sel = row?.selector
  if (!sel) return []
  return Object.entries(sel).map(([k, v]) => `${k}=${v}`)
}

/** 会话保持展示：ClientIP 时附超时秒数 */
function sessionAffinityDisplay(row: K8sService | null): string {
  const a = row?.sessionAffinity || 'None'
  if (a === 'ClientIP') return `ClientIP（${row?.sessionAffinityTimeoutSeconds ?? 10800}s）`
  return a
}

/** 布尔展示：null/undefined 视为否 */
function yesNo(v?: boolean | null): string {
  return v ? '是' : '否'
}

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
    <PageHeader title="Service" >
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="goEditor(null)">创建 Service</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 Service"
      description="点击右上「创建 Service」新建，或到顶栏切换上下文查看其他命名空间。"
    />

    <div v-else class="panel table-panel">
      <div class="search-bar">
        <el-input v-model="keyword" placeholder="按名称搜索…" clearable :prefix-icon="Search" class="search-input" />
      </div>
      <el-table v-loading="loading || !ready" :data="filtered" stripe>
        <el-table-column label="名称" width="300">
          <template #default="{ row }">
            <code class="res-name name-link"  @click="openDetail(row)">{{ row.name }}</code>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="typeTag(row.type)">{{ row.type || 'ClusterIP' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Cluster IP" width="200">
          <template #default="{ row }">
            <el-tag v-if="row.type === 'ExternalName'" size="small" type="info" effect="plain">{{ row.externalName || '—' }}</el-tag>
            <el-tag v-else-if="row.clusterIp === 'None'" size="small" type="info" effect="plain">headless</el-tag>
            <span v-else class="muted">{{ clusterIpDisplay(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="端口" min-width="200">
          <template #default="{ row }"><span class="port-text">{{ portSummary(row) }}</span></template>
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
                  <el-dropdown-item @click="goEditor(row.name)">编辑</el-dropdown-item>
                  <el-dropdown-item divided style="color: var(--el-color-danger)" @click="onDelete(row)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`Service · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="类型">{{ detail?.type || 'ClusterIP' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail?.type === 'ExternalName'" label="externalName">{{ detail?.externalName || '—' }}</el-descriptions-item>
            <el-descriptions-item v-else label="Cluster IP">{{ clusterIpDisplay(detail) }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>

          <!-- selector + 网络 / 流量（非 ExternalName） -->
          <template v-if="detail?.type !== 'ExternalName'">
            <div class="section-title">selector</div>
            <div v-if="selectorChips(detail).length" class="sel-chips">
              <el-tag v-for="c in selectorChips(detail)" :key="c" size="small" effect="plain" class="sel-chip">{{ c }}</el-tag>
            </div>
            <div v-else class="muted">—（端点由外部管理）</div>

            <el-descriptions :column="1" border class="sub-desc">
              <el-descriptions-item label="externalIPs">{{ joinList(detail?.externalIps) }}</el-descriptions-item>
              <el-descriptions-item v-if="detail?.ipFamilies?.length" label="ipFamilies / 策略">{{ joinList(detail?.ipFamilies) }} · {{ detail?.ipFamilyPolicy || 'SingleStack' }}</el-descriptions-item>
              <el-descriptions-item label="internalTrafficPolicy">{{ detail?.internalTrafficPolicy || 'Cluster' }}</el-descriptions-item>
              <el-descriptions-item v-if="detail?.type === 'NodePort' || detail?.type === 'LoadBalancer'" label="externalTrafficPolicy">{{ detail?.externalTrafficPolicy || 'Cluster' }}</el-descriptions-item>
              <el-descriptions-item label="sessionAffinity">{{ sessionAffinityDisplay(detail) }}</el-descriptions-item>
              <el-descriptions-item label="publishNotReadyAddresses">{{ yesNo(detail?.publishNotReadyAddresses) }}</el-descriptions-item>
            </el-descriptions>

            <!-- LoadBalancer 专属 -->
            <template v-if="detail?.type === 'LoadBalancer'">
              <div class="section-title">LoadBalancer</div>
              <el-descriptions :column="1" border class="sub-desc">
                <el-descriptions-item label="自动分配 NodePort">{{ yesNo(detail?.allocateLoadBalancerNodePorts ?? true) }}</el-descriptions-item>
                <el-descriptions-item v-if="detail?.healthCheckNodePort != null" label="healthCheckNodePort">{{ detail?.healthCheckNodePort }}</el-descriptions-item>
                <el-descriptions-item v-if="detail?.loadBalancerClass" label="loadBalancerClass">{{ detail?.loadBalancerClass }}</el-descriptions-item>
                <el-descriptions-item v-if="detail?.loadBalancerSourceRanges?.length" label="LB 源 IP 限制">{{ joinList(detail?.loadBalancerSourceRanges) }}</el-descriptions-item>
              </el-descriptions>
            </template>
          </template>

          <div class="section-title">端口</div>
          <el-table :data="detail?.ports ?? []" stripe size="small">
            <el-table-column prop="name" label="名称" width="100">
              <template #default="{ row }">{{ row.name ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="port" label="Port" width="80" />
            <el-table-column prop="targetPort" label="TargetPort" width="110" />
            <el-table-column prop="nodePort" label="NodePort" width="90">
              <template #default="{ row }">{{ row.nodePort ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="protocol" label="协议" width="70">
              <template #default="{ row }">{{ row.protocol ?? 'TCP' }}</template>
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
.search-bar {
  margin-bottom: 10px;
}
.search-input {
  width: 260px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.port-text {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  color: var(--text-2);
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
.sub-desc {
  margin-top: 8px;
}
.sel-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.sel-chip {
  font-family: Consolas, 'JetBrains Mono', monospace;
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
