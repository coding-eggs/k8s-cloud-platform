<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { clusterApi, namespaceApi } from '@/api'
import type { K8sClusterOption, K8sLimitRange, K8sLimitRangeItem, K8sResourceQuota, K8sResourceQuotaUsed, NamespaceView, ResourcePair } from '@/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import KvTags from '@/components/KvTags.vue'
import NamespaceMetricsPanel from '@/components/namespace/NamespaceMetricsPanel.vue'
import { fmtDate } from '@/utils/format'
import { formatQuantity } from '@/utils/quantity'

const route = useRoute()
const router = useRouter()

// ---- 平台上下文：集群级，无租户（刻意不用 stores/context.ts 的租户优先单例）----
const clusters = ref<K8sClusterOption[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
const name = computed(() => (route.query.name as string) || '')
const ready = computed(() => !!clusterId.value && !!name.value)

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.options()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

// ---- 数据（quota / limitrange / yaml 均为懒加载 tab）----
const ns = ref<NamespaceView | null>(null)
const quota = ref<K8sResourceQuota | null>(null)
const lr = ref<K8sLimitRange | null>(null)
const yamlText = ref('')
const loading = ref(false)
const tabLoading = ref(false)
const loadError = ref(false)

const activeTab = ref('overview')
const quotaLoaded = ref(false)
const lrLoaded = ref(false)
const yamlLoaded = ref(false)
/** 受保护系统命名空间：写操作被后端拒，配额/限制范围降级为「不可修改」空态 */
const isProtected = computed(() => ns.value != null && ns.value.editable === false)

/** 概览页监控指标面板（4 图）；随「立即刷新」一起重取 */
const metricsRef = ref<InstanceType<typeof NamespaceMetricsPanel> | null>(null)

const lrItems = computed<K8sLimitRangeItem[]>(() => lr.value?.limits ?? [])
const lrMultiple = computed(() => lr.value?.multiple === true)

const MULTIPLE_QUOTA_TIP = '该命名空间存在多份 ResourceQuota，平台仅管理名为 default 的这份，建议自行收敛'
const MULTIPLE_LR_TIP = '该命名空间存在多份 LimitRange，平台仅管理名为 default 的这份，建议自行收敛'

// ---- 加载 ----
async function refresh(): Promise<void> {
  if (!ready.value) return
  const cid = clusterId.value
  const target = name.value
  loading.value = true
  try {
    ns.value = await namespaceApi.get(cid, target)
    if (cid !== clusterId.value || target !== name.value) return
    // 命名空间本体或上下文变化 → 清空并重置全部懒加载 tab
    quota.value = null
    lr.value = null
    yamlText.value = ''
    quotaLoaded.value = lrLoaded.value = yamlLoaded.value = false
    // 受保护系统命名空间：写操作端点会拒，跳过 quota/limitrange 加载 → 模板渲染「不可修改」空态而非永远「加载中…」
    if (isProtected.value) {
      quotaLoaded.value = true
      lrLoaded.value = true
    }
    loadError.value = ns.value == null
    if (!loadError.value) await loadActiveTab()
  } catch {
    if (cid === clusterId.value && target === name.value) loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadQuota(): Promise<void> {
  if (!ready.value) return
  // 受保护系统命名空间：后端会拒，直接跳过
  if (isProtected.value) { quotaLoaded.value = true; return }
  const cid = clusterId.value
  const target = name.value
  tabLoading.value = true
  try {
    const res = await namespaceApi.quotaGet(cid, target)
    if (cid === clusterId.value && target === name.value) {
      quota.value = res
      quotaLoaded.value = true
    }
  } catch {
    // 兜底：任何未来失败都降级为「读取失败」空态，而非永远「加载中…」
    quotaLoaded.value = true
  } finally {
    tabLoading.value = false
  }
}

async function loadLimitRange(): Promise<void> {
  if (!ready.value) return
  // 受保护系统命名空间：后端会拒，直接跳过
  if (isProtected.value) { lrLoaded.value = true; return }
  const cid = clusterId.value
  const target = name.value
  tabLoading.value = true
  try {
    const res = await namespaceApi.limitrangeGet(cid, target)
    if (cid === clusterId.value && target === name.value) {
      lr.value = res
      lrLoaded.value = true
    }
  } catch {
    // 兜底：任何未来失败都降级为「读取失败」空态，而非永远「加载中…」
    lrLoaded.value = true
  } finally {
    tabLoading.value = false
  }
}

async function loadYaml(): Promise<void> {
  if (!ready.value) return
  const cid = clusterId.value
  const target = name.value
  tabLoading.value = true
  try {
    const text = await namespaceApi.yaml(cid, target)
    if (cid === clusterId.value && target === name.value) {
      yamlText.value = text ?? ''
      yamlLoaded.value = true
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    tabLoading.value = false
  }
}

/** 进入 / 重拉当前 tab 的数据（概览依赖配额，故也拉 quota） */
function loadActiveTab(): Promise<void> {
  const tab = activeTab.value
  if (tab === 'quota' && !quotaLoaded.value) return loadQuota()
  if (tab === 'limitrange' && !lrLoaded.value) return loadLimitRange()
  if (tab === 'yaml' && !yamlLoaded.value) return loadYaml()
  if (tab === 'overview' && !quotaLoaded.value) return loadQuota()
  return Promise.resolve()
}

watch(activeTab, () => { void loadActiveTab() })

function goBack(): void {
  router.push('/namespaces')
}

/** 「立即刷新」：本体 + 懒加载 tab + 概览监控指标一起重取 */
function refreshAll(): void {
  void refresh()
  void metricsRef.value?.refresh()
}

function goEditor(tab: 'overview' | 'quota' | 'limitrange'): void {
  void router.push({ name: 'namespace-editor', query: { clusterId: clusterId.value, name: name.value, tab } })
}

// ---- 展示辅助 ----
/** 基础单位数值 → 展示文本（cpu=核、memory=字节、count=整数）；空值 → '—' */
function numText(v: number | null | undefined, kind: 'cpu' | 'memory' | 'count'): string {
  if (v == null) return '—'
  return formatQuantity(v, kind) || '—'
}

/** ResourcePair（cpu/memory）单维度 → 展示文本 */
function pairText(pair: ResourcePair | null | undefined, kind: 'cpu' | 'memory'): string {
  return numText(pair?.[kind], kind)
}

function phaseType(phase?: string | null): 'success' | 'warning' | 'info' {
  if (phase === 'Active') return 'success'
  if (phase === 'Terminating') return 'warning'
  return 'info'
}

// ---- 资源配额表格：6 行，顺序 = 后端 MODELED_HARD_KEYS ----
type QuotaKind = 'cpu' | 'memory' | 'count'
interface QuotaRowDef { key: keyof K8sResourceQuota & keyof K8sResourceQuotaUsed; label: string; kind: QuotaKind }
const QUOTA_ROWS: QuotaRowDef[] = [
  { key: 'pods', label: 'pods', kind: 'count' },
  { key: 'limitsCpu', label: 'limits.cpu', kind: 'cpu' },
  { key: 'limitsMemory', label: 'limits.memory', kind: 'memory' },
  { key: 'requestsCpu', label: 'requests.cpu', kind: 'cpu' },
  { key: 'requestsMemory', label: 'requests.memory', kind: 'memory' },
  { key: 'persistentVolumeClaims', label: 'persistentvolumeclaims', kind: 'count' },
]

interface QuotaRow {
  key: string
  label: string
  usedText: string
  hardText: string
  percent: number | null
  over: boolean
}

/** 使用率 = used / hard × 100（1 位小数）；hard 缺失或 ≤0 → null（显示 —） */
function usagePercent(used: number | null, hard: number | null): number | null {
  if (used == null || hard == null || hard <= 0) return null
  return Math.round((used / hard) * 1000) / 10
}

const quotaRows = computed<QuotaRow[]>(() => {
  const q = quota.value
  if (!q) return []
  const used = q.used ?? null
  return QUOTA_ROWS.map((def) => {
    const hard = q[def.key] ?? null
    const u = used?.[def.key] ?? null
    const percent = usagePercent(u, hard)
    return {
      key: def.key as string,
      label: def.label,
      usedText: numText(u, def.kind),
      hardText: numText(hard, def.kind),
      percent,
      over: percent != null && percent > 100,
    }
  })
})

/** 概览用量摘要：requests.cpu / requests.memory / pods 的 used / hard 三行 */
const usageSummary = computed<QuotaRow[]>(() =>
  (['requestsCpu', 'requestsMemory', 'pods'] as const)
    .map((k) => quotaRows.value.find((r) => r.key === k))
    .filter((r): r is QuotaRow => r != null),
)

// ---- 限制范围：按类型分组（行 = max/min/default/defaultRequest，列 = cpu/memory）----
const LIMIT_FIELDS: { field: keyof Omit<K8sLimitRangeItem, 'type'>; label: string }[] = [
  { field: 'max', label: 'max' },
  { field: 'min', label: 'min' },
  { field: 'defaultValue', label: 'default' },
  { field: 'defaultRequest', label: 'defaultRequest' },
]
const LIMIT_TYPES = ['Container', 'PersistentVolumeClaim']

interface LimitRow { label: string; cpu: string; memory: string }
interface LimitSection { type: string; rows: LimitRow[] }

const limitSections = computed<LimitSection[]>(() => {
  const items = lrItems.value
  // 只展示平台建模的两类（Container / PersistentVolumeClaim）；集群里其它类型（Pod/ContainerFixed 等）
  // 由 overlay 原样保留，不在结构化表格呈现（完整内容见 YAML tab）
  return LIMIT_TYPES.map((type) => {
    const item = items.find((i) => i.type === type)
    const rows: LimitRow[] = []
    for (const def of LIMIT_FIELDS) {
      const pair = item?.[def.field]
      if (pair?.cpu == null && pair?.memory == null) continue // 整行无值 → 不展示
      rows.push({ label: def.label, cpu: pairText(pair, 'cpu'), memory: pairText(pair, 'memory') })
    }
    return { type, rows }
  })
})

// ---- 上下文联动 ----
onMounted(async () => {
  await loadClusters()
  if (ready.value) await refresh()
})
watch(clusterId, () => { if (name.value) void refresh() })
watch(name, () => { if (clusterId.value) void refresh() })
</script>

<template>
  <div v-loading="loading" class="ns-page">
    <PageHeader title="命名空间详情">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-tooltip content="立即刷新" placement="bottom" :show-after="100">
        <el-button :icon="Refresh" circle size="small" :disabled="!ready" @click="refreshAll" />
      </el-tooltip>
      <el-button :disabled="!ns || !ns.editable" @click="goEditor('overview')">编辑</el-button>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="缺少上下文" description="请从命名空间列表进入本页（需要集群与命名空间）。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>
    <EmptyState v-else-if="loadError" title="加载失败" description="该命名空间可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else-if="ns" class="ns-detail panel">
      <el-tabs v-model="activeTab">
        <!-- 概览：基本信息（左）+ 用量摘要（右） -->
        <el-tab-pane label="概览" name="overview">
          <div class="overview-grid">
            <aside class="ov-side">
              <section class="info-card">
                <h3 class="info-title">基本信息</h3>
                <div class="info-name">{{ ns.name }}</div>
                <div class="info-rows">
                  <div class="info-row">
                    <span class="k">状态</span>
                    <StatusBadgeTip :label="ns.phase ?? '未知'" :type="phaseType(ns.phase)" />
                  </div>
                  <div class="info-row"><span class="k">描述</span><span class="v">{{ ns.description || '—' }}</span></div>
                  <div class="info-row col-row"><span class="k">标签</span><KvTags title="标签" :data="ns.labels ?? {}" /></div>
                  <!-- 服务网格（B3 §11.7）：把 istio 两个保留标签单列出来读 —— 它们在「标签」行里也有，
                       但裸键值看不出"纳入/排除/跟随"这层意思；此处与命名空间编辑器同一套措辞 -->
                  <div class="info-row">
                    <span class="k">服务网格</span>
                    <span class="v">
                      <template v-if="ns.dataplaneMode === 'ambient'">
                        <el-tag size="small" type="success" effect="light">已纳入 ambient</el-tag>
                      </template>
                      <template v-else-if="ns.dataplaneMode === 'none'">
                        <el-tag size="small" type="info" effect="light">显式排除（none）</el-tag>
                      </template>
                      <span v-else class="muted">未设（跟随集群默认）</span>
                      <el-tag v-if="ns.useWaypoint" size="small" effect="plain" class="mesh-tag">
                        L7 · {{ ns.useWaypoint === 'none' ? '不使用 waypoint（none）' : `waypoint: ${ns.useWaypoint}` }}
                      </el-tag>
                    </span>
                  </div>
                  <div class="info-row">
                    <span class="k">来源</span>
                    <span class="v">
                      <el-tag v-if="ns.managedBy" size="small" type="primary" effect="light">平台创建</el-tag>
                      <span v-else>集群既有</span>
                    </span>
                  </div>
                  <div class="info-row">
                    <span class="k">已分配租户</span>
                    <span class="v">
                      <el-tag v-if="ns.allocatedTenantName" size="small" type="info">{{ ns.allocatedTenantName }}</el-tag>
                      <span v-else class="muted">未分配</span>
                    </span>
                  </div>
                  <div class="info-row"><span class="k">创建时间</span><span class="v">{{ fmtDate(ns.creationTimestamp) }}</span></div>
                </div>
              </section>

              <!-- 用量摘要：与基本信息同列（左）；随配额懒加载 -->
              <section v-loading="tabLoading && !quotaLoaded" class="info-card ov-usage">
                <h3 class="info-title">用量摘要</h3>
                <div v-if="isProtected" class="muted ov-na">受保护的系统命名空间，不可修改</div>
                <div v-else-if="quotaLoaded && quota" class="info-rows">
                  <div v-for="r in usageSummary" :key="r.key" class="info-row">
                    <span class="k">{{ r.label }}</span>
                    <span class="v">
                      <span :class="{ over: r.over }">{{ r.usedText }}</span>
                      <span class="muted"> / </span>{{ r.hardText }}
                      <span v-if="r.percent != null" class="muted" :class="{ over: r.over }">（{{ r.percent }}%）</span>
                    </span>
                  </div>
                </div>
                <EmptyState v-else-if="quotaLoaded" title="未设置配额" description="该命名空间未配置 ResourceQuota，用量不受约束。">
                  <el-button type="primary" @click="goEditor('quota')">设置配额</el-button>
                </EmptyState>
                <div v-else class="muted">加载中…</div>
              </section>
            </aside>

            <main class="ov-main">
              <!-- 监控指标：跨该命名空间全部 pod 聚合（只读，受保护 ns 同样开放） -->
              <NamespaceMetricsPanel ref="metricsRef" :cluster-id="clusterId" :namespace="name" />
            </main>
          </div>
        </el-tab-pane>

        <!-- 资源配额 -->
        <el-tab-pane label="资源配额" name="quota">
          <div v-loading="tabLoading" class="tab-body">
            <EmptyState v-if="isProtected" title="不可修改" description="受保护的系统命名空间，不可设置配额。" />
            <template v-else-if="quota">
              <el-alert v-if="quota?.multiple === true" :title="MULTIPLE_QUOTA_TIP" type="warning" :closable="false" show-icon class="mb-3" />
              <div class="list-head">
                <h3 class="info-title">资源配额（default）</h3>
                <el-button size="small" @click="goEditor('quota')">编辑配额</el-button>
              </div>
              <div class="obj-meta muted">
                resourceVersion <code class="mono">{{ quota?.resourceVersion ?? '—' }}</code>
                <span class="dot">·</span>
                创建于 {{ fmtDate(quota.creationTime) }}
              </div>
              <el-table :data="quotaRows" stripe size="small">
                <el-table-column label="约束项" min-width="180">
                  <template #default="{ row }"><code class="mono">{{ row.label }}</code></template>
                </el-table-column>
                <el-table-column label="已用" min-width="120">
                  <template #default="{ row }"><span :class="{ over: row.over }">{{ row.usedText }}</span></template>
                </el-table-column>
                <el-table-column label="上限" min-width="120">
                  <template #default="{ row }">{{ row.hardText }}</template>
                </el-table-column>
                <el-table-column label="使用率" min-width="110">
                  <template #default="{ row }">
                    <span :class="{ over: row.over }">{{ row.percent == null ? '—' : `${row.percent}%` }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </template>
            <EmptyState v-else-if="quotaLoaded" title="未配置" description="该命名空间没有平台管理的 ResourceQuota（对象名 default）。">
              <el-button type="primary" @click="goEditor('quota')">设置配额</el-button>
            </EmptyState>
            <div v-else class="muted">加载中…</div>
          </div>
        </el-tab-pane>

        <!-- 限制范围 -->
        <el-tab-pane label="限制范围" name="limitrange">
          <div v-loading="tabLoading" class="tab-body">
            <EmptyState v-if="isProtected" title="不可修改" description="受保护的系统命名空间，不可设置限制范围。" />
            <template v-else>
              <el-alert v-if="lrMultiple" :title="MULTIPLE_LR_TIP" type="warning" :closable="false" show-icon class="mb-3" />
              <template v-if="lrLoaded && lr">
              <div class="list-head">
                <h3 class="info-title">限制范围（default）</h3>
                <el-button size="small" @click="goEditor('limitrange')">编辑限制范围</el-button>
              </div>
              <div class="obj-meta muted">
                resourceVersion <code class="mono">{{ lr?.resourceVersion ?? '—' }}</code>
                <span class="dot">·</span>
                创建于 {{ fmtDate(lr?.creationTime) }}
              </div>
              <section v-for="sec in limitSections" :key="sec.type" class="lr-section">
                <h4 class="lr-title">{{ sec.type }}</h4>
                <el-table v-if="sec.rows.length" :data="sec.rows" stripe size="small">
                  <el-table-column label="约束" min-width="200">
                    <template #default="{ row }"><code class="mono">{{ row.label }}</code></template>
                  </el-table-column>
                  <el-table-column label="cpu" min-width="140">
                    <template #default="{ row }">{{ row.cpu }}</template>
                  </el-table-column>
                  <el-table-column label="memory" min-width="140">
                    <template #default="{ row }">{{ row.memory }}</template>
                  </el-table-column>
                </el-table>
                <div v-else class="muted empty-line">未设置</div>
              </section>
            </template>
            <EmptyState v-else-if="lrLoaded" title="未配置" description="该命名空间没有平台管理的 LimitRange（对象名 default）。">
              <el-button type="primary" @click="goEditor('limitrange')">设置限制范围</el-button>
            </EmptyState>
            <div v-else class="muted">加载中…</div>
            </template>
          </div>
        </el-tab-pane>

        <!-- YAML -->
        <el-tab-pane label="YAML" name="yaml">
          <div v-loading="tabLoading">
            <pre v-if="yamlLoaded && yamlText" class="yaml-block">{{ yamlText }}</pre>
            <div v-else class="muted">加载中…</div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <div v-else class="loading-tip">加载中…</div>
  </div>
</template>

<style scoped>
.ns-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.ns-detail {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 16px 16px;
}
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }

/* 概览 tab：基本信息（左，窄）+ 用量摘要（右）；小分辨率上下堆叠 */
.overview-grid {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.ov-side, .ov-main { min-width: 0; }
.ov-usage { margin-top: 16px; }
@media (max-width: 960px) {
  .overview-grid { grid-template-columns: 1fr; }
}
.info-card { padding: 16px; background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.info-title { margin: 0 0 12px; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.info-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 16px; font-weight: 600; color: var(--text-1); word-break: break-all; margin-bottom: 14px; }
.info-rows { display: flex; flex-direction: column; gap: 12px; }
.info-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.info-row.col-row { flex-direction: column; align-items: flex-start; gap: 6px; }
.info-row .k { color: var(--text-3); flex-shrink: 0; }
.info-row .v { color: var(--text-1); text-align: right; word-break: break-all; }
.col-row .v { text-align: left; width: 100%; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.mesh-tag { margin-left: 6px; }
.muted { color: var(--text-3); }
/* 超量（>100%）标红 */
.over { color: var(--danger); font-weight: 600; }

.tab-body { padding: 4px 0; }
.mb-3 { margin-bottom: 12px; }
.list-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 10px; }
.list-head .info-title { margin: 0; }
.obj-meta { font-size: 12px; margin: -4px 0 10px; }
.obj-meta .dot { margin: 0 6px; }
.lr-section { margin-bottom: 18px; }
.lr-title { margin: 0 0 8px; font-size: 12.5px; font-weight: 700; color: var(--text-2); }
.empty-line { font-size: 13px; }

.yaml-block {
  margin: 0; padding: 14px; border-radius: 8px; background: var(--panel-hover); border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; line-height: 1.6; color: var(--text-2);
  max-height: 70vh; overflow: auto; white-space: pre-wrap;
}
.loading-tip { padding: 48px; text-align: center; font-size: 13px; color: var(--text-3); }
.ov-na { padding: 24px 0; font-size: 13px; }
</style>
