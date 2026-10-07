<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { clusterApi, calicoApi } from '@/api'
import type { K8sCluster, K8sIpool, PoolIpamSummary, IpamBlockStat, IpamIpDetail } from '@/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import KvTags from '@/components/KvTags.vue'
import { useClusterCapability } from '@/composables/useClusterCapability'
import { fmtDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()

// ---- 上下文：集群级（clusterId 来自 query + 下拉切换）；name 来自 query，不可改 ----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
const name = computed(() => (route.query.name as string) || '')

// ---- capability 门禁：crd.projectcalico.org（ipamblocks）缺失 → IPAM 派生段降级「—」----
const capClusterId = computed(() => clusterId.value || null)
const { hasCalicoCrd } = useClusterCapability(capClusterId)

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

// ---- 池本体 + IPAM 汇总（summary 依赖 crd，缺失则降级 null）----
const pool = ref<K8sIpool | null>(null)
const summary = ref<PoolIpamSummary | null>(null)
const loading = ref(false)
const loadError = ref(false)

async function loadPool(): Promise<void> {
  if (!clusterId.value || !name.value) return
  const target = name.value
  pool.value = await calicoApi.ippool.get(target, clusterId.value)
  if (target !== name.value) return // 切换期间丢弃过期结果
}

async function loadSummary(): Promise<void> {
  if (!clusterId.value || !name.value) return
  if (!hasCalicoCrd.value) { summary.value = null; return }
  try {
    summary.value = await calicoApi.ipam.summary(clusterId.value, name.value)
  } catch {
    summary.value = null // 拦截器已提示；按不可用降级
  }
}

async function refresh(): Promise<void> {
  if (!clusterId.value || !name.value) return
  loading.value = true
  loadError.value = false
  try {
    await loadPool()
    await loadSummary()
  } catch {
    if (name.value) loadError.value = true
  } finally {
    loading.value = false
  }
}

// ---- Tab 状态 + IPAM 块表懒加载（服务端 search：按 CIDR/节点过滤）----
const activeTab = ref('overview')
const blocks = ref<IpamBlockStat[]>([])
const blocksLoaded = ref(false)
const blockKeyword = ref('')

async function loadBlocks(): Promise<void> {
  if (!clusterId.value || !name.value) return
  try {
    blocks.value = await calicoApi.ipam.blocks(clusterId.value, name.value, blockKeyword.value.trim() || undefined)
  } catch { /* 拦截器提示 */ } finally {
    blocksLoaded.value = true
  }
}

watch(activeTab, (tab) => {
  if (tab === 'ipam' && !blocksLoaded.value && hasCalicoCrd.value) void loadBlocks()
})

// ---- IP 点查（isFree：单 CIDR/IP，返回是否空闲）----
const checkInput = ref('')
const checkResult = ref<boolean | null>(null)
const checking = ref(false)
async function doCheck(): Promise<void> {
  const v = checkInput.value.trim()
  if (!v || !clusterId.value) return
  checking.value = true
  checkResult.value = null
  try {
    checkResult.value = await calicoApi.ipam.isFree(clusterId.value, v)
  } catch { /* 拦截器提示 */ } finally {
    checking.value = false
  }
}

// ---- 下一批空闲块（nextFreeBlocks 分页：offset/limit）----
const FREE_LIMIT = 20
const freeBlocks = ref<string[]>([])
const freeOffset = ref(0)
const freeHasMore = ref(false)
const loadingFree = ref(false)
async function loadNextFree(): Promise<void> {
  if (!clusterId.value || !name.value) return
  loadingFree.value = true
  try {
    const next = await calicoApi.ipam.nextFreeBlocks(clusterId.value, name.value, freeOffset.value, FREE_LIMIT)
    freeBlocks.value = next
    freeHasMore.value = next.length === FREE_LIMIT // 满页 → 可能还有
  } catch { /* 拦截器提示 */ } finally {
    loadingFree.value = false
  }
}
function nextFreePage(): void { freeOffset.value += FREE_LIMIT; void loadNextFree() }
function prevFreePage(): void { if (freeOffset.value > 0) { freeOffset.value -= FREE_LIMIT; void loadNextFree() } }

// ---- 单块 per-IP 下钻（blockIps：未物化合成全 free）----
const ipsVisible = ref(false)
const ipsCidr = ref('')
const ips = ref<IpamIpDetail[]>([])
const ipsLoading = ref(false)
async function openBlockIps(row: IpamBlockStat): Promise<void> {
  if (!clusterId.value) return
  ipsCidr.value = row.cidr
  ips.value = []
  ipsVisible.value = true
  ipsLoading.value = true
  try {
    ips.value = await calicoApi.ipam.blockIps(clusterId.value, row.cidr)
  } catch { /* 拦截器提示 */ } finally {
    ipsLoading.value = false
  }
}

function ipStatusLabel(s: IpamIpDetail['status']): string {
  return s === 'free' ? '空闲' : s === 'reserved' ? '保留' : '已分配'
}
function ipStatusType(s: IpamIpDetail['status']): 'success' | 'warning' | 'danger' {
  return s === 'free' ? 'success' : s === 'reserved' ? 'warning' : 'danger'
}

// ---- 概览 stat tile（IPAM 汇总；IPv6 饱和值按量级展示）----
function fmtNum(n?: number | null): string {
  if (n == null) return '—'
  if (n >= 1e15) return n.toExponential(2)
  return n.toLocaleString()
}
const summaryTiles = computed(() => {
  const s = summary.value
  if (!s) return null
  return [
    { label: '容量 capacity', value: fmtNum(s.capacity) },
    { label: '已分配 allocated', value: fmtNum(s.allocated) },
    { label: '空闲 free', value: fmtNum(s.free) },
    { label: '保留 reserved', value: fmtNum(s.reserved) },
    { label: '块数 blocks', value: fmtNum(s.blockCount) },
  ]
})

function goBack(): void { router.push('/ops/ippools') }
/** 直接为本池创建保留 IP（编辑器带 ?pool= 预选所属池） */
function goReserve(): void {
  if (!clusterId.value || !name.value) return
  router.push({ path: '/ops/ipreservations/editor', query: { clusterId: clusterId.value, pool: name.value } })
}

// ---- 上下文联动：切换集群重置派生缓存；capability 就绪后补拉 summary / 块表 ----
onMounted(async () => {
  await loadClusters()
  if (clusterId.value && name.value) await refresh()
})
watch(clusterId, () => {
  if (!name.value) return
  summary.value = null
  blocksLoaded.value = false
  void refresh()
})
watch(hasCalicoCrd, (v) => {
  if (!v || !name.value) return
  void loadSummary()
  if (activeTab.value === 'ipam' && !blocksLoaded.value) void loadBlocks()
})
</script>

<template>
  <div v-loading="loading" class="ippool-page">
    <PageHeader title="地址池详情" description="Calico IPPool（集群级 CRD）+ IPAM 派生视图">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-tooltip content="立即刷新" placement="bottom" :show-after="100">
        <el-button :icon="Refresh" circle size="small" :disabled="!clusterId" @click="refresh" />
      </el-tooltip>
      <el-button type="primary" :disabled="!pool" @click="goReserve">保留 IP</el-button>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="loadError" title="加载失败" description="该地址池可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else-if="pool" class="ippool-detail panel">
      <el-tabs v-model="activeTab">
        <!-- 概览：基本信息（左）+ IPAM 汇总 stat tile（右） -->
        <el-tab-pane label="概览" name="overview">
          <div class="overview-grid">
            <aside class="ov-side">
              <section class="info-card">
                <h3 class="info-title">基本信息</h3>
                <div class="info-name">{{ pool.name }}</div>
                <div class="info-rows">
                  <div class="info-row"><span class="k">CIDR</span><code class="mono v">{{ pool.cidr ?? '—' }}</code></div>
                  <div class="info-row"><span class="k">块大小</span><span class="v">{{ pool.blockSize ?? '默认' }}</span></div>
                  <div class="info-row"><span class="k">NAT 出网</span><el-tag size="small" :type="pool.natOutgoing ? 'success' : 'info'">{{ pool.natOutgoing ? '是' : '否' }}</el-tag></div>
                  <div class="info-row"><span class="k">禁用</span><el-tag v-if="pool.disabled" size="small" type="danger">是</el-tag><span v-else class="v muted">否</span></div>
                  <div class="info-row"><span class="k">IPv4 分层端口分配</span><el-tag size="small" :type="pool.ipv4hierarchicalPortAllocation ? 'success' : 'info'">{{ pool.ipv4hierarchicalPortAllocation ? '是' : '否' }}</el-tag></div>
                  <div class="info-row"><span class="k">节点选择器</span><code class="mono v">{{ (pool.nodeSelector && pool.nodeSelector.length) ? pool.nodeSelector.join(' ; ') : '—（所有节点）' }}</code></div>
                  <div class="info-row"><span class="k">固定块</span><code class="mono v">{{ (pool.blocks && pool.blocks.length) ? pool.blocks.join(', ') : '—' }}</code></div>
                  <div class="info-row"><span class="k">创建时间</span><span class="v">{{ fmtDate(pool.creationTime) }}</span></div>
                  <div class="info-row col-row"><span class="k">标签</span><KvTags title="标签" :data="pool.labels ?? {}" /></div>
                </div>
              </section>
            </aside>

            <main class="ov-main">
              <h3 class="info-title">IPAM 汇总（派生，非 CRD）</h3>
              <div v-if="hasCalicoCrd && summaryTiles" class="stat-grid">
                <div v-for="t in summaryTiles" :key="t.label" class="stat-tile">
                  <div class="stat-value">{{ t.value }}</div>
                  <div class="stat-label">{{ t.label }}</div>
                </div>
              </div>
              <div v-else-if="!hasCalicoCrd" class="muted notice">未探测到 crd.projectcalico.org（ipamblocks）：IPAM 派生汇总不可用。可在集群能力中刷新后重试。</div>
              <div v-else class="muted notice">IPAM 汇总加载中…</div>
            </main>
          </div>
        </el-tab-pane>

        <!-- IP 分配 / 可保留（★ M 锚定派生；空闲靠缺席判定，永不枚举补集） -->
        <el-tab-pane label="IP 分配 / 可保留" name="ipam">
          <div v-if="!hasCalicoCrd" class="muted notice pad">未探测到 crd.projectcalico.org（ipamblocks）：IPAM 派生块视图不可用。可在集群能力中刷新后重试。</div>

          <div v-else class="ipam-panel">
            <!-- 点查：单 CIDR/IP 是否空闲 -->
            <div class="check-bar">
              <el-input v-model="checkInput" placeholder="输入 CIDR 或单个 IP，判断是否空闲（如 10.48.0.5 或 10.48.0.0/26）" style="width: 440px" clearable @keyup.enter="doCheck" />
              <el-button :icon="Search" :loading="checking" @click="doCheck">点查</el-button>
              <el-tag v-if="checkResult === true" type="success">空闲（可分配 / 可保留）</el-tag>
              <el-tag v-else-if="checkResult === false" type="danger">已占用 / 未物化</el-tag>
            </div>

            <!-- 下一批空闲块（分页候选，尚未物化） -->
            <div class="free-bar">
              <span class="muted">下一批空闲块（每批 {{ FREE_LIMIT }}，尚未物化）：</span>
              <el-button size="small" :disabled="freeOffset <= 0" @click="prevFreePage">上一页</el-button>
              <el-button size="small" type="primary" :loading="loadingFree" @click="loadNextFree">查询</el-button>
              <el-button size="small" :disabled="!freeHasMore" @click="nextFreePage">下一页</el-button>
            </div>
            <div v-if="freeBlocks.length" class="free-list">
              <code v-for="c in freeBlocks" :key="c" class="mono free-chip">{{ c }}</code>
            </div>

            <!-- 已物化块表（可搜索） -->
            <div class="list-head mt-4">
              <h3 class="info-title">已物化块（materialized blocks）</h3>
              <div class="search-bar-inline">
                <el-input v-model="blockKeyword" placeholder="按 CIDR / 节点过滤…" clearable :prefix-icon="Search" style="width: 240px" @keyup.enter="loadBlocks" />
                <el-button :icon="Refresh" circle size="small" @click="loadBlocks" />
              </div>
            </div>
            <el-table v-if="blocksLoaded" :data="blocks" stripe size="small">
              <el-table-column label="块 CIDR" min-width="200">
                <template #default="{ row }"><code class="mono">{{ row.cidr }}</code></template>
              </el-table-column>
              <el-table-column label="节点" min-width="160">
                <template #default="{ row }"><span class="muted">{{ row.node ?? '—' }}</span></template>
              </el-table-column>
              <el-table-column prop="totalIps" label="总 IP" width="90" />
              <el-table-column prop="allocated" label="已分配" width="90" />
              <el-table-column prop="free" label="空闲" width="90" />
              <el-table-column prop="reserved" label="保留" width="90" />
              <el-table-column width="90" fixed="right">
                <template #default="{ row }"><el-button link type="primary" @click="openBlockIps(row)">下钻</el-button></template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- 单块 per-IP 下钻（未物化合成全 free） -->
      <el-dialog v-model="ipsVisible" :title="`块明细 · ${ipsCidr}`" width="780px">
        <el-table v-loading="ipsLoading" :data="ips" stripe size="small" max-height="520">
          <el-table-column prop="ip" label="IP" min-width="180">
            <template #default="{ row }"><code class="mono">{{ row.ip }}</code></template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><el-tag size="small" :type="ipStatusType(row.status)">{{ ipStatusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="podName" label="Pod" min-width="180">
            <template #default="{ row }"><span class="muted">{{ row.podName ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="podNamespace" label="命名空间" width="140">
            <template #default="{ row }"><span class="muted">{{ row.podNamespace ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="node" label="节点" min-width="160">
            <template #default="{ row }"><span class="muted">{{ row.node ?? '—' }}</span></template>
          </el-table-column>
        </el-table>
      </el-dialog>
    </div>

    <div v-else class="loading-tip">加载中…</div>
  </div>
</template>

<style scoped>
.ippool-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.ippool-detail {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 16px 16px;
}
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }

/* 概览：基本信息（左，窄）+ IPAM 汇总（右）；小分辨率上下堆叠 */
.overview-grid {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.ov-side, .ov-main { min-width: 0; }
@media (max-width: 960px) {
  .overview-grid { grid-template-columns: 1fr; }
}
.info-card { padding: 16px; }
.info-title { margin: 0 0 12px; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.info-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 16px; font-weight: 600; color: var(--text-1); word-break: break-all; margin-bottom: 14px; }
.info-rows { display: flex; flex-direction: column; gap: 12px; max-width: 720px; }
.info-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.info-row.col-row { flex-direction: column; align-items: flex-start; gap: 6px; }
.info-row .k { color: var(--text-3); flex-shrink: 0; }
.info-row .v { color: var(--text-1); text-align: right; word-break: break-all; }
.col-row .v { text-align: left; width: 100%; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }

/* IPAM 汇总 stat tile */
.stat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; margin-top: 4px; }
.stat-tile { padding: 14px 16px; border: 1px solid var(--border); border-radius: 8px; background: var(--panel-hover); }
.stat-value { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 20px; font-weight: 600; color: var(--text-1); word-break: break-all; }
.stat-label { margin-top: 4px; font-size: 12px; color: var(--text-3); }

/* IPAM tab */
.ipam-panel { padding: 4px 0; }
.notice { font-size: 13px; line-height: 1.6; }
.pad { padding: 12px 0; }
.check-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.free-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.free-list { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
.free-chip { padding: 3px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--panel-hover); font-size: 12.5px; }
.list-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.list-head .info-title { margin: 0; }
.search-bar-inline { display: inline-flex; align-items: center; gap: 8px; }
.mt-4 { margin-top: 20px; }

.loading-tip { padding: 48px; text-align: center; font-size: 13px; color: var(--text-3); }
</style>
