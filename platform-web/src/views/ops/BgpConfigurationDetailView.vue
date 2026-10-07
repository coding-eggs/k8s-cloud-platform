<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import KvTags from '@/components/KvTags.vue'
import { clusterApi, calicoApi } from '@/api'
import type { K8sCluster, BgpConfiguration } from '@/types'
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

const item = ref<BgpConfiguration | null>(null)
const loading = ref(false)
const loadError = ref(false)
async function load(): Promise<void> {
  if (!clusterId.value || !name) return
  loading.value = true
  loadError.value = false
  try {
    item.value = await calicoApi.bgpConfiguration.get(name, clusterId.value)
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
    yamlText.value = await calicoApi.bgpConfiguration.getYaml(name, clusterId.value)
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
    <PageHeader title="BGP 配置详情" description="Calico BGPConfiguration（集群级 CRD）——只读">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px" @change="load">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :icon="Refresh" circle size="small" :disabled="!clusterId" @click="load" />
      <el-button @click="router.push('/ops/bgpconfigurations')">返回</el-button>
    </PageHeader>

    <EmptyState v-if="loadError" title="加载失败" description="该 BGP 配置可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="router.push('/ops/bgpconfigurations')">返回列表</el-button>
    </EmptyState>

    <div v-else-if="item" class="panel detail-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="详情" name="detail">
          <div class="info-name">{{ item.name }}</div>
          <div class="info-rows">
            <div class="info-row"><span class="k">AS 号</span><code class="mono v">{{ item.asNumber ?? '—' }}</code></div>
            <div class="info-row"><span class="k">节点 Mesh</span>
              <el-tag size="small" :type="item.nodeToNodeMeshEnabled ? 'success' : 'info'">{{ item.nodeToNodeMeshEnabled ? '启用' : '禁用' }}</el-tag>
            </div>
            <div class="info-row"><span class="k">监听端口</span><span class="v">{{ item.listenPort ?? '—（默认 179）' }}</span></div>
            <div class="info-row"><span class="k">日志级别</span><span class="v muted">{{ item.logSeverityScreen ?? '—' }}</span></div>
            <div class="info-row"><span class="k">绑定模式 bindMode</span><span class="v muted">{{ item.bindMode ?? '—（默认全部地址）' }}</span></div>
            <div class="info-row col-row"><span class="k">Service ClusterIPs</span>
              <div class="chip-wrap"><code v-for="b in item.serviceClusterIPs" :key="'c' + b.cidr" class="mono chip">{{ b.cidr }}</code><span v-if="!item.serviceClusterIPs?.length" class="muted">—</span></div>
            </div>
            <div class="info-row col-row"><span class="k">Service ExternalIPs</span>
              <div class="chip-wrap"><code v-for="b in item.serviceExternalIPs" :key="'e' + b.cidr" class="mono chip">{{ b.cidr }}</code><span v-if="!item.serviceExternalIPs?.length" class="muted">—</span></div>
            </div>
            <div class="info-row col-row"><span class="k">Service LoadBalancerIPs</span>
              <div class="chip-wrap"><code v-for="b in item.serviceLoadBalancerIPs" :key="'l' + b.cidr" class="mono chip">{{ b.cidr }}</code><span v-if="!item.serviceLoadBalancerIPs?.length" class="muted">—</span></div>
            </div>
            <div class="info-row col-row"><span class="k">Communities（名称 = 值）</span>
              <div class="chip-wrap"><code v-for="(c, i) in item.communities" :key="i" class="mono chip">{{ c.name }} = {{ c.value }}</code><span v-if="!item.communities?.length" class="muted">—</span></div>
            </div>
            <div class="info-row col-row"><span class="k">PrefixAdvertisements</span>
              <div class="chip-wrap"><code v-for="(p, i) in item.prefixAdvertisements" :key="i" class="mono chip">{{ p.cidr }}（{{ (p.communities ?? []).join(', ') || '—' }}）</code><span v-if="!item.prefixAdvertisements?.length" class="muted">—</span></div>
            </div>
            <div class="info-row"><span class="k">NodeMesh 密码</span>
              <code v-if="item.nodeMeshPassword" class="mono v">{{ item.nodeMeshPassword.namespace ?? 'default' }}/{{ item.nodeMeshPassword.name }}:{{ item.nodeMeshPassword.key }}</code>
              <span v-else class="v muted">—</span>
            </div>
            <div class="info-row col-row"><span class="k">忽略网卡 ignoredInterfaces</span>
              <div class="chip-wrap"><code v-for="i in item.ignoredInterfaces" :key="i" class="mono chip">{{ i }}</code><span v-if="!item.ignoredInterfaces?.length" class="muted">—</span></div>
            </div>
            <!-- 新版字段（旧版 Calico 缺席时整行隐藏） -->
            <template v-if="item.serviceLoadBalancerAggregation || item.programClusterRoutes || item.localWorkloadPeeringIPV4 || item.localWorkloadPeeringIPV6">
              <div class="info-row"><span class="k">LB IP 聚合</span><span class="v muted">{{ item.serviceLoadBalancerAggregation ?? '—' }}</span></div>
              <div class="info-row"><span class="k">集群路由编程</span><span class="v muted">{{ item.programClusterRoutes ?? '—' }}</span></div>
              <div class="info-row"><span class="k">Workload Peering v4/v6</span><code class="mono v">{{ [item.localWorkloadPeeringIPV4, item.localWorkloadPeeringIPV6].filter(Boolean).join(', ') || '—' }}</code></div>
            </template>
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
