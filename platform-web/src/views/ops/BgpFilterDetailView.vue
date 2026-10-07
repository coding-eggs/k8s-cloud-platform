<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import KvTags from '@/components/KvTags.vue'
import BgpFilterRuleTable from './BgpFilterRuleTable.vue'
import { clusterApi, calicoApi } from '@/api'
import type { K8sCluster, BgpFilter } from '@/types'
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

const item = ref<BgpFilter | null>(null)
const loading = ref(false)
const loadError = ref(false)
async function load(): Promise<void> {
  if (!clusterId.value || !name) return
  loading.value = true
  loadError.value = false
  try {
    item.value = await calicoApi.bgpFilter.get(name, clusterId.value)
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
    yamlText.value = await calicoApi.bgpFilter.getYaml(name, clusterId.value)
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
    <PageHeader title="BGP 过滤器详情" description="Calico BGPFilter（集群级 CRD）——只读">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px" @change="load">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button :icon="Refresh" circle size="small" :disabled="!clusterId" @click="load" />
      <el-button @click="router.push('/ops/bgpfilters')">返回</el-button>
    </PageHeader>

    <EmptyState v-if="loadError" title="加载失败" description="该 BGP 过滤器可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="router.push('/ops/bgpfilters')">返回列表</el-button>
    </EmptyState>

    <div v-else-if="item" class="panel detail-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="详情" name="detail">
          <div class="info-name">{{ item.name }}</div>
          <div class="info-rows">
            <div class="info-row"><span class="k">创建时间</span><span class="v">{{ fmtDate(item.creationTime) }}</span></div>
            <div class="info-row col-row"><span class="k">标签</span><KvTags title="标签" :data="item.labels ?? {}" /></div>
          </div>

          <!-- 四条规则列表（v3.28+；旧版 Calico 无对应字段时整节隐藏） -->
          <h3 class="cond-title">导出规则 v4（{{ item.exportV4?.length ?? 0 }}）</h3>
          <BgpFilterRuleTable v-if="item.exportV4?.length" :rules="item.exportV4" />
          <div v-else class="muted rule-empty">—</div>

          <h3 class="cond-title">导入规则 v4（{{ item.importV4?.length ?? 0 }}）</h3>
          <BgpFilterRuleTable v-if="item.importV4?.length" :rules="item.importV4" />
          <div v-else class="muted rule-empty">—</div>

          <h3 class="cond-title">导出规则 v6（{{ item.exportV6?.length ?? 0 }}）</h3>
          <BgpFilterRuleTable v-if="item.exportV6?.length" :rules="item.exportV6" />
          <div v-else class="muted rule-empty">—</div>

          <h3 class="cond-title">导入规则 v6（{{ item.importV6?.length ?? 0 }}）</h3>
          <BgpFilterRuleTable v-if="item.importV6?.length" :rules="item.importV6" />
          <div v-else class="muted rule-empty">—</div>

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
.rule-empty { padding: 4px 0 8px; font-size: 13px; }
.cond-title { margin: 20px 0 10px; font-size: 13px; font-weight: 700; color: var(--text-2); }
.yaml-block {
  margin: 0; padding: 14px; border-radius: 8px; background: var(--panel-hover); border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; line-height: 1.6; color: var(--text-2);
  max-height: 70vh; overflow: auto; white-space: pre-wrap;
}
.loading-tip { padding: 48px; text-align: center; font-size: 13px; color: var(--text-3); }
</style>
