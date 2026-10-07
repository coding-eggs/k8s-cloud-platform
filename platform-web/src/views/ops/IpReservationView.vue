<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { clusterApi, calicoApi } from '@/api'
import { useClusterCapability } from '@/composables/useClusterCapability'
import type { K8sCluster, K8sIpool, K8sIpReservation } from '@/types'
import { containsInCidr } from '@/utils/ipUtil'
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

// ---- capability 门禁：hasCalico（create）----
const capClusterId = computed(() => clusterId.value || null)
const { hasCalico, refresh: refreshCap } = useClusterCapability(capClusterId)

// ---- 保留段列表 + 池（用于「所属池」反查：块 CIDR ⊆ 某池，Calico 禁池重叠 → 无歧义）----
const loading = ref(false)
const reservations = ref<K8sIpReservation[]>([])
const pools = ref<K8sIpool[]>([])

async function load(): Promise<void> {
  if (!clusterId.value) return
  loading.value = true
  try {
    const [res, ps] = await Promise.all([
      calicoApi.ipreservation.list({ clusterId: clusterId.value }),
      // 池列表失败不影响保留段展示（所属池降级「—」）
      calicoApi.ippool.list({ clusterId: clusterId.value }).catch(() => [] as K8sIpool[]),
    ])
    reservations.value = res
    pools.value = ps
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
function rangeText(r: K8sIpReservation): string {
  const c = r.reservedCidrs
  if (!c || !c.length) return '—'
  return c.join(', ')
}
function poolOf(r: K8sIpReservation): string {
  const first = r.reservedCidrs?.[0]
  if (!first) return '—'
  const ip = first.includes('/') ? (first.split('/')[0] ?? first) : first
  const p = pools.value.find((pl) => pl.cidr && containsInCidr(pl.cidr, ip))
  return p ? p.name : '—（未落在任何池）'
}

// ---- 行操作 ----
function onEdit(row: K8sIpReservation): void {
  router.push({ path: '/ops/ipreservations/editor', query: { clusterId: clusterId.value, name: row.name } })
}
function onCreate(): void {
  if (!clusterId.value) return
  router.push({ path: '/ops/ipreservations/editor', query: { clusterId: clusterId.value } })
}

const yamlVisible = ref(false)
const yamlName = ref('')
const yamlText = ref('')
async function onYaml(row: K8sIpReservation): Promise<void> {
  if (!clusterId.value) return
  yamlName.value = row.name
  yamlText.value = ''
  yamlVisible.value = true
  try {
    yamlText.value = await calicoApi.ipreservation.getYaml(row.name, clusterId.value)
  } catch { /* 拦截器提示 */ }
}

const deleting = ref('')
async function onDelete(row: K8sIpReservation): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除保留段「${row.name}」（${rangeText(row)}）？删除后该段 IP 回到自动分配，可能被重新分配。`,
      '删除保留 IP',
      { type: 'warning' },
    )
  } catch { return }
  deleting.value = row.name
  try {
    await calicoApi.ipreservation.delete(row.name, clusterId.value)
    ElMessage.success('已删除')
    await load()
  } catch { /* 拦截器提示 */ } finally {
    deleting.value = ''
  }
}
</script>

<template>
  <div>
    <PageHeader title="保留 IP" description="Calico IPReservation（集群级 CRD）——保留段不会被自动分配">
      <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :icon="Refresh" circle @click="load" />
      <el-button type="primary" :icon="Plus" :disabled="!hasCalico" @click="onCreate">创建保留 IP</el-button>
    </PageHeader>

    <div v-if="clusterId && !hasCalico" class="cap-banner">
      <span>未探测到 Calico（projectcalico.org）能力：该集群可能未安装 Calico，或 API 能力快照过期。创建已禁用。</span>
      <el-button size="small" @click="refreshCap">刷新能力</el-button>
    </div>

    <el-table v-loading="loading" :data="reservations" stripe class="resv-table">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="onEdit(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="保留 CIDR" min-width="240">
        <template #default="{ row }"><code class="mono">{{ rangeText(row) }}</code></template>
      </el-table-column>
      <el-table-column label="所属池" min-width="160">
        <template #default="{ row }"><span class="muted">{{ poolOf(row) }}</span></template>
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
                <el-dropdown-item @click="onEdit(row)">编辑</el-dropdown-item>
                <el-dropdown-item @click="onYaml(row)">YAML</el-dropdown-item>
                <el-dropdown-item divided style="color: var(--el-color-danger)" :loading="deleting === row.name" @click="onDelete(row)">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <EmptyState v-if="!loading && !reservations.length" title="暂无保留 IP" description="该集群下没有 IPReservation，或尚未探测到 Calico 能力。" />

    <el-dialog v-model="yamlVisible" :title="`YAML · ${yamlName}`" width="760px">
      <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
      <div v-else class="muted">加载中…</div>
    </el-dialog>
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
.resv-table { margin-top: 4px; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
.yaml-block {
  margin: 0; padding: 14px; border-radius: 8px; background: var(--panel-hover); border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; line-height: 1.6; color: var(--text-2);
  max-height: 70vh; overflow: auto; white-space: pre-wrap;
}
</style>
