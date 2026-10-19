<script setup lang="ts">
/**
 * 日志查询中心：查 collector 写进 ES 的应用日志。
 *
 * 数据源是 ES 里按天分割的 `otel-logs-YYYY.MM.DD`（platform.logs.es.* 配置）。
 * 筛选维度：租户 / 集群 / 命名空间 / 工作负载 / 关键词 / 级别 / 时间范围。
 *
 * ⚠️ 租户隔离在后端：token 带租户帽时 platform-api 按分配表把查询收窄到本租户的命名空间，
 *    前端的租户选择只是**平台管理员**用来筛选的。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { logApi } from '@/api'
import type { K8sLogEntry } from '@/types'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'

const RANGES = [
  { label: '最近 15 分钟', value: 900 },
  { label: '最近 1 小时', value: 3600 },
  { label: '最近 6 小时', value: 21600 },
  { label: '最近 24 小时', value: 86400 },
  { label: '最近 7 天', value: 604800 },
  { label: '自定义', value: -1 },
]

const SEVERITIES = ['ERROR', 'WARN', 'INFO', 'DEBUG']
const PAGE_SIZE = 100

const router = useRouter()
const { state, load } = useResourceContext()
const perm = usePermission()

const filters = reactive({
  tenantId: state.tenantId ?? '',
  clusterName: state.clusterId
    ? (state.tenants.find((t) => t.tenantId === state.tenantId)?.clusters
        .find((c) => c.clusterId === state.clusterId)?.clusterName ?? '')
    : '',
  namespace: '',
  workload: '',
  keyword: '',
  severity: '',
  range: 3600,
})
const customRange = ref<[Date, Date] | null>(null)

const rows = ref<K8sLogEntry[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const cursor = ref<unknown[] | null>(null)
const hasMore = ref(false)

const detailVisible = ref(false)
const selected = ref<K8sLogEntry | null>(null)

/** 租户下拉（管理员可按租户筛选；租户用户只有一个） */
const tenantOptions = computed(() => state.tenants)
const clusterOptions = computed(() => {
  const tenants = filters.tenantId
    ? state.tenants.filter((t) => t.tenantId === filters.tenantId)
    : state.tenants
  const seen = new Map<string, string>()
  for (const t of tenants) {
    for (const c of t.clusters) {
      if (!seen.has(c.clusterName)) seen.set(c.clusterName, c.clusterName)
    }
  }
  return [...seen.values()].sort()
})
const namespaceOptions = computed(() => {
  const tenants = filters.tenantId
    ? state.tenants.filter((t) => t.tenantId === filters.tenantId)
    : state.tenants
  const out = new Set<string>()
  for (const t of tenants) {
    for (const c of t.clusters) {
      if (filters.clusterName && c.clusterName !== filters.clusterName) continue
      c.namespaces.forEach((n) => out.add(n))
    }
  }
  return [...out].sort()
})

function bounds(): { start: number; end: number } {
  const now = Date.now()
  if (filters.range === -1 && customRange.value) {
    return { start: customRange.value[0].getTime(), end: customRange.value[1].getTime() }
  }
  return { start: now - filters.range * 1000, end: now }
}

async function query(reset = true): Promise<void> {
  if (filters.range === -1 && !customRange.value) {
    ElMessage.warning('请选择自定义的时间范围')
    return
  }
  loading.value = true
  error.value = null
  if (reset) {
    rows.value = []
    cursor.value = null
  }
  const { start, end } = bounds()
  try {
    const next = (await logApi.search({
      tenantId: perm.isAdmin && filters.tenantId ? filters.tenantId : undefined,
      clusterName: filters.clusterName || undefined,
      namespace: filters.namespace || undefined,
      workload: filters.workload.trim() || undefined,
      keyword: filters.keyword.trim() || undefined,
      severity: filters.severity || undefined,
      startMillis: start,
      endMillis: end,
      limit: PAGE_SIZE,
      searchAfter: reset ? undefined : cursor.value,
    })) ?? []
    rows.value = reset ? next : [...rows.value, ...next]
    const last = next.length ? next[next.length - 1] : undefined
    cursor.value = last?.sort ?? null
    hasMore.value = next.length === PAGE_SIZE && !!cursor.value
  } catch (e) {
    error.value = (e as Error).message || '日志查询失败'
    if (reset) rows.value = []
  } finally {
    loading.value = false
  }
}

function openDetail(row: K8sLogEntry): void {
  selected.value = row
  detailVisible.value = true
}

function gotoTraces(traceId: string): void {
  void router.push({ path: '/mesh/traces', query: { trace: traceId } })
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const ms = Date.parse(value)
  return Number.isFinite(ms) ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : value
}

function severityType(sev?: string | null): 'danger' | 'warning' | 'info' | 'success' {
  switch ((sev ?? '').toUpperCase()) {
    case 'ERROR': case 'FATAL': return 'danger'
    case 'WARN': return 'warning'
    case 'INFO': return 'info'
    default: return 'success'
  }
}

function resetScope(): void {
  filters.namespace = ''
}

onMounted(async () => {
  await load() // 载入租户树（页面可能不是从资源页进的）
  // 默认落到顶栏当前上下文
  if (!filters.tenantId && state.tenantId) filters.tenantId = state.tenantId
  const cluster = state.tenants.find((t) => t.tenantId === filters.tenantId)?.clusters
    .find((c) => c.clusterId === state.clusterId)
  if (!filters.clusterName && cluster) filters.clusterName = cluster.clusterName
  if (!filters.namespace && state.namespace) filters.namespace = state.namespace
  void query()
})
</script>

<template>
  <div class="logs-page">
    <PageHeader title="日志查询" description="查询应用日志（OTel → ES）；按租户 / 集群 / 命名空间 / 工作负载 / 关键词 / 时间过滤" />

    <div class="logs-filters">
      <el-form inline>
        <el-form-item v-if="perm.isAdmin" label="租户">
          <el-select v-model="filters.tenantId" filterable clearable placeholder="全部租户"
                     style="width: 170px" @change="filters.clusterName = ''; resetScope()">
            <el-option v-for="t in tenantOptions" :key="t.tenantId" :label="t.name" :value="t.tenantId" />
          </el-select>
        </el-form-item>
        <el-form-item label="集群">
          <el-select v-model="filters.clusterName" filterable clearable placeholder="全部集群"
                     style="width: 180px" @change="resetScope">
            <el-option v-for="c in clusterOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="命名空间">
          <el-select v-model="filters.namespace" filterable allow-create clearable
                     placeholder="全部命名空间" style="width: 180px">
            <el-option v-for="n in namespaceOptions" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="工作负载">
          <el-input v-model="filters.workload" clearable placeholder="按名称模糊匹配"
                    style="width: 170px" @keyup.enter="query()" />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="正文 / 属性，多个词 AND"
                    style="width: 220px" @keyup.enter="query()" />
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="filters.severity" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间">
          <el-select v-model="filters.range" style="width: 140px">
            <el-option v-for="r in RANGES" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="filters.range === -1">
          <el-date-picker v-model="customRange" type="datetimerange"
                          start-placeholder="开始" end-placeholder="结束"
                          :shortcuts="[]" style="width: 360px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="query()">查询</el-button>
        </el-form-item>
      </el-form>
      <div class="logs-hint">
        工作负载按 service.name / deployment / statefulset / daemonset / pod 名模糊匹配；
        日志来自 collector 的按天索引，时间范围最多 31 天
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="logs-alert" />

    <el-table v-loading="loading" :data="rows" class="logs-table" @row-click="openDetail">
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.time) }}</template>
      </el-table-column>
      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.severity" size="small" :type="severityType(row.severity)">{{ row.severity }}</el-tag>
          <span v-else class="logs-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="消息" min-width="320">
        <template #default="{ row }"><div class="logs-body">{{ row.body || '（无正文）' }}</div></template>
      </el-table-column>
      <el-table-column prop="service" label="服务" width="160" />
      <el-table-column prop="namespace" label="命名空间" width="130" />
      <el-table-column label="工作负载 / Pod" width="200">
        <template #default="{ row }">
          <div class="logs-wl">{{ row.workload || '—' }}</div>
          <div class="logs-muted">{{ row.pod || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="Trace" width="120">
        <template #default="{ row }">
          <el-button v-if="row.traceId" link type="primary" @click.stop="gotoTraces(row.traceId)">
            查看链路
          </el-button>
          <span v-else class="logs-muted">—</span>
        </template>
      </el-table-column>
      <template #empty>
        没有查到日志（ES 可能未配置 / 该时间范围没有数据 / 采集未开启）
      </template>
    </el-table>

    <div v-if="hasMore" class="logs-more">
      <el-button :loading="loading" @click="query(false)">加载更多</el-button>
    </div>

    <el-drawer v-model="detailVisible" title="日志详情" size="52%">
      <template v-if="selected">
        <div class="logs-detail-meta">
          <div><span>时间</span><code>{{ fmtTime(selected.time) }}</code></div>
          <div><span>级别</span><code>{{ selected.severity || '—' }}（{{ selected.severityNumber ?? '—' }}）</code></div>
          <div><span>服务</span><code>{{ selected.service || '—' }}</code></div>
          <div><span>集群 / 命名空间</span><code>{{ selected.cluster || '—' }} / {{ selected.namespace || '—' }}</code></div>
          <div><span>工作负载</span><code>{{ selected.workload || '—' }}</code></div>
          <div><span>Pod</span><code>{{ selected.pod || '—' }}</code></div>
          <div>
            <span>Trace</span>
            <el-button v-if="selected.traceId" link type="primary" @click="gotoTraces(selected.traceId)">
              {{ selected.traceId }}
            </el-button>
            <code v-else>—</code>
          </div>
          <div><span>Span</span><code>{{ selected.spanId || '—' }}</code></div>
        </div>
        <div class="logs-detail-head">正文</div>
        <pre class="logs-detail-body">{{ selected.body || '（无正文）' }}</pre>
        <template v-if="selected.attributes">
          <div class="logs-detail-head">属性</div>
          <div class="logs-attr" v-for="(value, key) in selected.attributes" :key="key">
            <span>{{ key }}</span><code>{{ value }}</code>
          </div>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.logs-page { display: flex; flex-direction: column; gap: 12px; }
.logs-filters { padding: 4px 0; }
.logs-hint { font-size: 12px; color: var(--text-3); padding: 0 0 4px 4px; }
.logs-alert { margin-bottom: 4px; }
.logs-table { cursor: pointer; }
.logs-body {
  white-space: pre-wrap; word-break: break-all;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.logs-wl { line-height: 1.4; }
.logs-muted { font-size: 12px; color: var(--text-3); }
.logs-more { display: flex; justify-content: center; padding: 8px 0; }
.logs-detail-meta { display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
.logs-detail-meta > div { display: flex; gap: 12px; }
.logs-detail-meta span { flex: 0 0 120px; color: var(--text-3); }
.logs-detail-head { margin: 14px 0 6px; font-size: 12px; color: var(--text-2); }
.logs-detail-body {
  background: var(--panel-hover); border: 1px solid var(--border); border-radius: 6px;
  padding: 10px; font-size: 12.5px; white-space: pre-wrap; word-break: break-all;
  max-height: 420px; overflow: auto;
}
.logs-attr { display: flex; gap: 10px; padding: 3px 0; font-size: 12px; border-bottom: 1px dashed var(--border); }
.logs-attr > span { flex: 0 0 200px; color: var(--text-3); }
.logs-attr > code { flex: 1 1 auto; word-break: break-all; }
</style>
