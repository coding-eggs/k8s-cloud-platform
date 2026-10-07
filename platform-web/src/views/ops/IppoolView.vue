<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { clusterApi, calicoApi } from '@/api'
import { useClusterCapability } from '@/composables/useClusterCapability'
import type { K8sCluster, K8sIpool, PoolIpamSummary } from '@/types'
import { fmtDate } from '@/utils/format'

const router = useRouter()

// ---- 集群选择（Calico 集群级，无租户/命名空间维度）----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
  if (first && !clusterId.value) clusterId.value = first.clusterId
}

// ---- capability 门禁：hasCalico（create）/ hasCalicoCrd（IPAM 派生，含利用率列）----
const capClusterId = computed(() => clusterId.value || null)
const { hasCalico, hasCalicoCrd, refresh: refreshCap } = useClusterCapability(capClusterId)

// ---- 池列表 + 利用率（每池一次 summary：M 锚定 + 后端 45s TTL 缓存，单池失败降级「—」）----
const loading = ref(false)
const pools = ref<K8sIpool[]>([])
const utilMap = ref<Map<string, PoolIpamSummary>>(new Map())

async function load(): Promise<void> {
  if (!clusterId.value) return
  loading.value = true
  try {
    pools.value = await calicoApi.ippool.list({ clusterId: clusterId.value })
    utilMap.value = new Map()
    // 利用率：仅当探测到 IPAM CRD 时并发取各池汇总（否则全降级「—」，避免 N 次注定失败的调用）
    if (hasCalicoCrd.value) {
      const entries = await Promise.all(pools.value.map(async (p) => {
        try { return [p.name, await calicoApi.ipam.summary(clusterId.value, p.name)] as const }
        catch { return [p.name, null] as const }
      }))
      for (const [name, s] of entries) if (s) utilMap.value.set(name, s)
    }
  } finally {
    loading.value = false
  }
}

watch(clusterId, load)
onMounted(async () => {
  await loadClusters()
  if (clusterId.value) await load()
})

// ---- 展示辅助 ----
function nodeSelectorText(p: K8sIpool): string {
  return p.nodeSelector && p.nodeSelector.length ? p.nodeSelector.join(' ; ') : '—'
}
function utilizationOf(p: K8sIpool): string {
  const s = utilMap.value.get(p.name)
  if (!s || !s.capacity || s.capacity <= 0) return '—'
  return (((s.allocated ?? 0) / s.capacity) * 100).toFixed(1) + '%'
}
function fmtNum(n?: number | null): string {
  if (n == null) return '—'
  if (n >= 1e15) return n.toExponential(2) // IPv6 饱和值按量级展示
  return n.toLocaleString()
}

// ---- 行操作 ----
function onDetail(row: K8sIpool): void {
  router.push({ path: '/ops/ippools/detail', query: { clusterId: clusterId.value, name: row.name } })
}
function onEdit(row: K8sIpool): void {
  router.push({ path: '/ops/ippools/editor', query: { clusterId: clusterId.value, name: row.name } })
}
/** 直接为该池创建保留 IP（编辑器带 ?pool= 预选所属池） */
function onReserve(row: K8sIpool): void {
  router.push({ path: '/ops/ipreservations/editor', query: { clusterId: clusterId.value, pool: row.name } })
}
function onCreate(): void {
  if (!clusterId.value) return
  router.push({ path: '/ops/ippools/editor', query: { clusterId: clusterId.value } })
}

const deleting = ref('')
async function onDelete(row: K8sIpool): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除地址池「${row.name}」？若池内仍有已分配 IP，将被拒绝（请先释放/迁移）。`,
      '删除地址池',
      { type: 'warning' },
    )
  } catch { return }
  deleting.value = row.name
  try {
    await calicoApi.ippool.delete(row.name, clusterId.value)
    ElMessage.success('已删除')
    await load()
  } catch { /* 拦截器提示（含后端删除守卫拒绝） */ } finally {
    deleting.value = ''
  }
}
</script>

<template>
  <div>
    <PageHeader title="地址池" description="Calico IPPool（集群级 CRD）+ IPAM 派生块视图">
      <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :icon="Refresh" circle @click="load" />
      <el-button type="primary" :icon="Plus" :disabled="!hasCalico" @click="onCreate">创建地址池</el-button>
    </PageHeader>

    <div v-if="clusterId && !hasCalico" class="cap-banner">
      <span>未探测到 Calico（projectcalico.org）能力：该集群可能未安装 Calico，或 API 能力快照过期。创建已禁用。</span>
      <el-button size="small" @click="refreshCap">刷新能力</el-button>
    </div>

    <el-table v-loading="loading" :data="pools" stripe class="pool-table">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="onDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="CIDR" min-width="160">
        <template #default="{ row }"><code class="mono">{{ row.cidr ?? '—' }}</code></template>
      </el-table-column>
      <el-table-column label="块大小" width="90">
        <template #default="{ row }">{{ row.blockSize ?? '默认' }}</template>
      </el-table-column>
      <el-table-column label="NAT 出网" width="100">
        <template #default="{ row }"><el-tag size="small" :type="row.natOutgoing ? 'success' : 'info'">{{ row.natOutgoing ? '是' : '否' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="禁用" width="80">
        <template #default="{ row }"><el-tag v-if="row.disabled" size="small" type="danger">是</el-tag><span v-else class="muted">否</span></template>
      </el-table-column>
      <el-table-column label="节点选择器" min-width="200" show-overflow-tooltip>
        <template #default="{ row }"><span class="muted">{{ nodeSelectorText(row) }}</span></template>
      </el-table-column>
      <el-table-column label="已分配 / 容量" min-width="180">
        <template #default="{ row }">
          <span v-if="utilMap.has(row.name)">
            {{ fmtNum(utilMap.get(row.name)?.allocated) }} / {{ fmtNum(utilMap.get(row.name)?.capacity) }}
            <el-tag size="small" type="info" class="ml-1">{{ utilizationOf(row) }}</el-tag>
          </span>
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
                <el-dropdown-item @click="onDetail(row)">查看</el-dropdown-item>
                <el-dropdown-item @click="onEdit(row)">编辑</el-dropdown-item>
                <el-dropdown-item @click="onReserve(row)">保留 IP</el-dropdown-item>
                <el-dropdown-item divided style="color: var(--el-color-danger)" :loading="deleting === row.name" @click="onDelete(row)">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <EmptyState v-if="!loading && !pools.length" title="暂无地址池" description="该集群下没有 IPPool，或尚未探测到 Calico 能力。" />
  </div>
</template>

<style scoped>
.cap-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
  color: var(--text-2);
  font-size: 13px;
}
.pool-table { margin-top: 4px; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
.ml-1 { margin-left: 6px; }
</style>
