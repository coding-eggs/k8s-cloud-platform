<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import KvTags from '@/components/KvTags.vue'
import { clusterApi, calicoApi } from '@/api'
import type { K8sCluster, BgpPeer } from '@/types'
import { fmtDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()

// ---- 上下文：集群级（clusterId 来自 query + 下拉切换）；name 来自 query，不可改 ----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
const name = (route.query.name as string) || ''

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

const item = ref<BgpPeer | null>(null)
const loading = ref(false)
const loadError = ref(false)
async function load(): Promise<void> {
  if (!clusterId.value || !name) return
  loading.value = true
  loadError.value = false
  try {
    item.value = await calicoApi.bgpPeer.get(name, clusterId.value)
  } catch {
    item.value = null // 拦截器已提示（含集群未装 Calico 的 404）
    loadError.value = true
  } finally {
    loading.value = false
  }
}

// ---- YAML tab（懒加载）----
const activeTab = ref('detail')
const yamlText = ref('')
const yamlLoaded = ref(false)
async function loadYaml(): Promise<void> {
  if (!clusterId.value || !name || yamlLoaded.value) return
  try {
    yamlText.value = await calicoApi.bgpPeer.getYaml(name, clusterId.value)
    yamlLoaded.value = true
  } catch { /* 拦截器已提示 */ }
}
watch(activeTab, (tab) => {
  if (tab === 'yaml') void loadYaml()
})

onMounted(async () => {
  await loadClusters()
  if (clusterId.value) await load()
})
</script>

<template>
  <div v-loading="loading" class="bgp-page">
    <PageHeader title="BGP 对等体详情" description="Calico BGPPeer（集群级 CRD）——只读">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px" @change="load">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :icon="Refresh" circle size="small" :disabled="!clusterId" @click="load" />
      <el-button @click="router.push('/ops/bgppeers')">返回</el-button>
    </PageHeader>

    <EmptyState v-if="loadError" title="加载失败" description="该 BGP 对等体可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="router.push('/ops/bgppeers')">返回列表</el-button>
    </EmptyState>

    <div v-else-if="item" class="panel detail-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="详情" name="detail">
          <div class="info-name">{{ item.name }}</div>
          <div class="info-rows">
            <div class="info-row"><span class="k">目标节点</span><code class="mono v">{{ item.node ?? '—' }}</code></div>
            <div class="info-row"><span class="k">节点选择器</span><code class="mono v">{{ item.nodeSelector ?? '—' }}</code></div>
            <div class="info-row"><span class="k">对端 IP</span><code class="mono v">{{ item.peerIp ?? '—' }}</code></div>
            <div class="info-row"><span class="k">对端 AS 号</span><code class="mono v">{{ item.asNumber ?? '—' }}</code></div>
            <div class="info-row"><span class="k">本地 AS 号</span><code class="mono v">{{ item.localAsNumber ?? '—（用 default 配置）' }}</code></div>
            <div class="info-row"><span class="k">对端选择器 peerSelector</span><code class="mono v">{{ item.peerSelector ?? '—' }}</code></div>
            <div class="info-row"><span class="k">保留原始 NextHop</span>
              <el-tag size="small" :type="item.keepOriginalNextHop ? 'warning' : 'info'">{{ item.keepOriginalNextHop ? '是（已废弃）' : '否' }}</el-tag>
            </div>
            <div class="info-row"><span class="k">NextHop 模式</span><span class="v muted">{{ item.nextHopMode ?? '—（BGP 默认）' }}</span></div>
            <div class="info-row"><span class="k">BGP 密码</span>
              <code v-if="item.password" class="mono v">{{ item.password.namespace ?? 'default' }}/{{ item.password.name }}:{{ item.password.key }}</code>
              <span v-else class="v muted">—</span>
            </div>
            <div class="info-row"><span class="k">源地址策略</span><span class="v muted">{{ item.sourceAddress ?? '—（默认 UseNodeIP）' }}</span></div>
            <div class="info-row"><span class="k">Graceful Restart 超时</span><span class="v muted">{{ item.maxRestartTime ?? '—（BIRD 默认 120s）' }}</span></div>
            <div class="info-row"><span class="k">Keepalive 间隔</span><span class="v muted">{{ item.keepaliveTime ?? '—' }}</span></div>
            <div class="info-row"><span class="k">允许本地 AS 数</span><span class="v muted">{{ item.numAllowedLocalASNumbers ?? '—' }}</span></div>
            <div class="info-row"><span class="k">TTL 安全（GTSM）跳数</span><span class="v muted">{{ item.ttlSecurity ?? '—' }}</span></div>
            <div class="info-row"><span class="k">静态路由网关 reachableBy</span><code class="mono v">{{ item.reachableBy ?? '—' }}</code></div>
            <div class="info-row col-row"><span class="k">应用的 BGPFilter（有序）</span>
              <div class="chip-wrap"><code v-for="f in item.filters" :key="f" class="mono chip">{{ f }}</code><span v-if="!item.filters?.length" class="muted">—</span></div>
            </div>
            <div class="info-row"><span class="k">本地 Workload 选择器</span><code class="mono v">{{ item.localWorkloadSelector ?? '—' }}</code></div>
            <div class="info-row"><span class="k">反向 Peering</span><span class="v muted">{{ item.reversePeering ?? '—（默认 Auto）' }}</span></div>
            <div class="info-row"><span class="k">创建时间</span><span class="v">{{ fmtDate(item.creationTime) }}</span></div>
            <div class="info-row col-row"><span class="k">标签</span><KvTags title="标签" :data="item.labels ?? {}" /></div>
          </div>

          <h3 v-if="item.conditions?.length" class="cond-title">Conditions</h3>
          <el-table v-if="item.conditions?.length" :data="item.conditions" size="small" stripe>
            <el-table-column prop="type" label="类型" width="160" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="reason" label="原因" min-width="140" />
            <el-table-column prop="message" label="信息" min-width="240" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="YAML" name="yaml">
          <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <div v-else class="loading-tip">加载中…</div>
  </div>
</template>

<style scoped>
.bgp-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.detail-panel { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 8px 16px 16px; }
.info-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 16px; font-weight: 600; color: var(--text-1); word-break: break-all; margin-bottom: 14px; }
.info-rows { display: flex; flex-direction: column; gap: 12px; max-width: 780px; }
.info-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.info-row.col-row { flex-direction: column; align-items: flex-start; gap: 6px; }
.info-row .k { color: var(--text-3); flex-shrink: 0; }
.info-row .v { color: var(--text-1); text-align: right; word-break: break-all; }
.col-row .v { text-align: left; width: 100%; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
.chip-wrap { display: flex; flex-wrap: wrap; gap: 6px; width: 100%; }
.chip { padding: 2px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--panel-hover); font-size: 12.5px; }
.cond-title { margin: 20px 0 10px; font-size: 13px; font-weight: 700; color: var(--text-2); }
.yaml-block {
  margin: 0; padding: 14px; border-radius: 8px; background: var(--panel-hover); border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; line-height: 1.6; color: var(--text-2);
  max-height: 70vh; overflow: auto; white-space: pre-wrap;
}
.loading-tip { padding: 48px; text-align: center; font-size: 13px; color: var(--text-3); }
</style>
