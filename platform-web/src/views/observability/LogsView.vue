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
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, CopyDocument, InfoFilled, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { logApi, podApi, workloadApi } from '@/api'
import type { K8sLogEntry } from '@/types'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import { pageCodes } from '@/pageCodes'
import { writeClipboard } from '@/utils/clipboard'

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
const { state, currentCluster, load } = useResourceContext()
const perm = usePermission()

const filters = reactive({
  /** 以下三项不在页面上展示，直接取顶栏上下文（改顶栏即重查） */
  tenantId: '',
  clusterName: '',
  namespace: '',
  workload: '',
  /** Pod 名只在查询区出现，表格不展示（太长） */
  pod: '',
  keyword: '',
  severity: '',
  range: 3600,
})
const customRange = ref<[Date, Date] | null>(null)

/** 工作负载 / Pod 下拉候选：按当前上下文的 namespace 懒加载（展开下拉时才拉），失败仍可手输 */
const workloadOptions = ref<string[]>([])
const podOptions = ref<string[]>([])
const workloadLoading = ref(false)
const podLoading = ref(false)

async function loadWorkloadOptions(): Promise<void> {
  if (!state.tenantId || !state.clusterId || !state.namespace) return
  workloadLoading.value = true
  try {
    const list = await workloadApi.list({
      tenantId: state.tenantId,
      clusterId: state.clusterId,
      namespace: state.namespace,
    })
    workloadOptions.value = [...new Set((list ?? []).map((w) => w.name).filter(Boolean))].sort()
  } catch {
    workloadOptions.value = []
  } finally {
    workloadLoading.value = false
  }
}

async function loadPodOptions(): Promise<void> {
  if (!state.tenantId || !state.clusterId || !state.namespace) return
  podLoading.value = true
  try {
    const list = await podApi.list({
      tenantId: state.tenantId,
      clusterId: state.clusterId,
      namespace: state.namespace,
    })
    podOptions.value = [...new Set((list ?? []).map((p) => p.name).filter(Boolean))].sort()
  } catch {
    podOptions.value = []
  } finally {
    podLoading.value = false
  }
}

function onWorkloadDropdown(visible: boolean): void {
  if (visible) void loadWorkloadOptions()
}

function onPodDropdown(visible: boolean): void {
  if (visible) void loadPodOptions()
}

/** 筛选范围 = 顶栏三级；页面只保留工作负载 / Pod / 关键词 / 级别 / 时间。 */
function syncFromContext(): void {
  const changed = filters.tenantId !== (state.tenantId ?? '')
    || filters.clusterName !== (currentCluster.value?.clusterName ?? '')
    || filters.namespace !== (state.namespace ?? '')
  filters.tenantId = state.tenantId ?? ''
  filters.clusterName = currentCluster.value?.clusterName ?? ''
  filters.namespace = state.namespace ?? ''
  if (changed) {
    // 候选跟着命名空间走，换了范围就重取
    workloadOptions.value = []
    podOptions.value = []
  }
}

const rows = ref<K8sLogEntry[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const cursor = ref<unknown[] | null>(null)
const hasMore = ref(false)

const detailVisible = ref(false)
const selected = ref<K8sLogEntry | null>(null)

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
      tenantId: filters.tenantId || undefined,
      clusterName: filters.clusterName || undefined,
      namespace: filters.namespace || undefined,
      workload: filters.workload.trim() || undefined,
      pod: filters.pod.trim() || undefined,
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

function gotoTraces(traceId?: string | null): void {
  if (traceId) void router.push({ path: '/mesh/traces', query: { trace: traceId } })
}

/**
 * 只有被采样的 trace 才会导出到 Tempo。采样率 < 100% 时大部分日志行带着
 * 「未采样」的 traceId，点进链路页必然 404 —— 这类不给入口，只标「未采样」。
 * 没有链路页权限（平台码 / 租户码任一）时同理不给入口。
 */
const canOpenTrace = computed(() =>
  perm.hasAny([pageCodes.mesh.trace, pageCodes.mesh.traceTenant]))

function traceState(row: K8sLogEntry): 'link' | 'forbidden' | 'unsampled' | 'none' {
  const id = row.traceId
  if (!id || /^0+$/.test(id)) return 'none'
  if (!canOpenTrace.value) return 'forbidden'
  return row.sampled === false ? 'unsampled' : 'link'
}

function parseTime(value?: string | null): Date | null {
  if (!value) return null
  const ms = Date.parse(value)
  return Number.isFinite(ms) ? new Date(ms) : null
}

function fmtTime(value?: string | null): string {
  const d = parseTime(value)
  return d ? d.toLocaleString('zh-CN', { hour12: false }) : (value ?? '—')
}

/** 表格里的两行时间：时分秒 + 月日（完整时间放 tooltip） */
function fmtClock(value?: string | null): string {
  const d = parseTime(value)
  if (!d) return '—'
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function fmtDay(value?: string | null): string {
  const d = parseTime(value)
  if (!d) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 表格里的单行时间：MM-DD HH:mm:ss */
function fmtStamp(value?: string | null): string {
  const d = parseTime(value)
  if (!d) return value ?? '—'
  return `${fmtDay(value)} ${fmtClock(value)}`
}

/** 结果范围（顶栏上下文）→ 标题行的小标签 */
const scopeChips = computed(() => {
  const chips: string[] = []
  if (filters.tenantId) {
    const tenant = state.tenants.find((t) => t.tenantId === filters.tenantId)
    if (tenant) chips.push(tenant.name)
  }
  chips.push(filters.clusterName || '全部集群')
  chips.push(filters.namespace || '全部命名空间')
  return chips
})

/** 错误 / 警告行整行着色，扫日志时先看到问题 */
function rowClass({ row }: { row: K8sLogEntry }): string {
  const sev = (row.severity ?? '').toUpperCase()
  if (sev === 'ERROR' || sev === 'FATAL') return 'logs-row-error'
  if (sev === 'WARN') return 'logs-row-warn'
  return ''
}

function severityType(sev?: string | null): 'danger' | 'warning' | 'info' | 'success' {
  switch ((sev ?? '').toUpperCase()) {
    case 'ERROR': case 'FATAL': return 'danger'
    case 'WARN': return 'warning'
    case 'INFO': return 'info'
    default: return 'success'
  }
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 关键词高亮。日志正文是不可信输入：先整体转义，再把「转义后的关键词」包进 mark，
 * 不拼接任何原始 HTML。
 */
function highlightBody(text?: string | null): string {
  const escaped = escapeHtml(text ?? '')
  const terms = filters.keyword.trim().split(/\s+/).filter(Boolean)
  if (!terms.length) return escaped
  const pattern = new RegExp(`(${terms.map((t) => escapeRegExp(escapeHtml(t))).join('|')})`, 'gi')
  return escaped.replace(pattern, '<mark class="logs-mark">$1</mark>')
}

async function copyBody(): Promise<void> {
  const text = selected.value?.body
  if (!text) return
  if (await writeClipboard(text)) ElMessage.success('日志正文已复制')
  else ElMessage.warning('复制失败，请手动选择')
}

onMounted(async () => {
  await load() // 载入租户树（页面可能不是从资源页进的）
  syncFromContext()
  void query()
  // 顶栏切换 → 重查。watcher 在首次 sync 之后注册，避免 mount 时重复打一轮
  watch(() => [state.tenantId, state.clusterId, state.namespace], () => {
    syncFromContext()
    void query()
  })
})
</script>

<template>
  <div class="logs-page">
    <PageHeader title="日志查询" description="查询应用日志（OTel → ES）；范围取顶栏的租户 / 集群 / 命名空间，另可按工作负载 / 关键词 / 时间过滤" />

    <div class="logs-toolbar">
      <div class="logs-toolbar-main">
        <div class="logs-field">
          <span class="logs-label">工作负载</span>
          <el-select v-model="filters.workload" filterable allow-create clearable :loading="workloadLoading"
                     placeholder="选择或输入名称" style="width: 190px"
                     @visible-change="onWorkloadDropdown">
            <el-option v-for="w in workloadOptions" :key="w" :label="w" :value="w" />
          </el-select>
        </div>
        <div class="logs-field">
          <span class="logs-label">Pod</span>
          <el-select v-model="filters.pod" filterable allow-create clearable :loading="podLoading"
                     placeholder="选择或输入 Pod 名" style="width: 210px"
                     @visible-change="onPodDropdown">
            <el-option v-for="p in podOptions" :key="p" :label="p" :value="p" />
          </el-select>
        </div>
        <div class="logs-field">
          <span class="logs-label">关键词</span>
          <el-input v-model="filters.keyword" clearable placeholder="正文 / 属性，多个词 AND"
                    style="width: 230px" @keyup.enter="query()" />
        </div>
        <div class="logs-field">
          <span class="logs-label">级别</span>
          <el-select v-model="filters.severity" clearable placeholder="全部" style="width: 108px">
            <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
          </el-select>
        </div>
        <div class="logs-field">
          <span class="logs-label">时间</span>
          <el-select v-model="filters.range" style="width: 140px">
            <el-option v-for="r in RANGES" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </div>
        <el-date-picker v-if="filters.range === -1" v-model="customRange" type="datetimerange"
                        start-placeholder="开始" end-placeholder="结束" :shortcuts="[]"
                        style="width: 340px" />
        <el-button type="primary" :loading="loading" @click="query()">
          <el-icon><Search /></el-icon>
          <span>查询</span>
        </el-button>
      </div>
      <div class="logs-toolbar-hint">
        <el-tooltip placement="top-start" :show-after="150">
          <template #content>
            <div class="logs-tip">
              <p>范围跟随顶栏的租户 / 集群 / 命名空间，改顶栏即自动重查。</p>
              <p>工作负载 / Pod 可从下拉选，也可以直接输入一段做模糊匹配；工作负载同时匹配 service.name / deployment / statefulset / daemonset / Pod 名，关键词在正文和属性里做 AND。</p>
              <p>日志来自 collector 的按天索引（otel-logs-YYYY.MM.DD），时间范围最多 31 天。</p>
            </div>
          </template>
          <span class="logs-hint-inline"><el-icon><InfoFilled /></el-icon> 查询范围与索引说明</span>
        </el-tooltip>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="logs-alert" />

    <div class="logs-result">
      <div class="logs-result-head">
        <div class="logs-result-title">
          查询结果
          <span class="logs-result-count">{{ rows.length }}</span>
          <span v-if="hasMore" class="logs-result-more">还有更多</span>
        </div>
        <div class="logs-result-scope">
          <el-tag v-for="part in scopeChips" :key="part" size="small" effect="plain" round>{{ part }}</el-tag>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows" class="logs-table" :row-class-name="rowClass"
                @row-click="openDetail">
        <el-table-column label="时间" width="140">
          <template #default="{ row }">
            <el-tooltip :content="fmtTime(row.time)" placement="top">
              <span class="logs-time">{{ fmtStamp(row.time) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="86">
          <template #default="{ row }">
            <el-tag v-if="row.severity" size="small" effect="dark" :type="severityType(row.severity)">
              {{ row.severity }}
            </el-tag>
            <span v-else class="logs-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="消息" min-width="400">
          <template #default="{ row }">
            <div class="logs-body" v-html="highlightBody(row.body)"></div>
          </template>
        </el-table-column>
        <el-table-column label="命名空间" width="140">
          <template #default="{ row }"><span class="logs-ns">{{ row.namespace || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="工作负载" width="150">
          <template #default="{ row }"><span class="logs-wl">{{ row.workload || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="Trace" width="110">
          <template #default="{ row }">
            <el-button v-if="traceState(row) === 'link'" link type="primary" @click.stop="gotoTraces(row.traceId)">
              查看链路
            </el-button>
            <el-tooltip v-else-if="traceState(row) === 'unsampled'" placement="top"
                        content="该请求未被链路采样，Tempo 中没有对应 trace">
              <span class="logs-muted logs-unsampled">未采样</span>
            </el-tooltip>
            <el-tooltip v-else-if="traceState(row) === 'forbidden'" placement="top"
                        content="当前角色没有链路追踪权限">
              <span class="logs-muted logs-unsampled">无权限</span>
            </el-tooltip>
            <span v-else class="logs-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column width="52" align="right">
          <template #default>
            <el-icon class="logs-open"><ArrowRight /></el-icon>
          </template>
        </el-table-column>
        <template #empty>
          <div class="logs-empty">
            <div class="logs-empty-title">没有查到日志</div>
            <div class="logs-empty-sub">ES 可能未配置 / 该时间范围没有数据 / 采集未开启</div>
          </div>
        </template>
      </el-table>
    </div>

    <div class="logs-more">
      <span class="logs-more-count">已加载 {{ rows.length }} 条</span>
      <el-button v-if="hasMore" :loading="loading" @click="query(false)">加载更多</el-button>
    </div>

    <el-drawer v-model="detailVisible" size="56%" class="logs-drawer">
      <template #header>
        <div class="logs-drawer-head">
          <div class="logs-drawer-title">
            日志详情
            <el-tag v-if="selected?.severity" size="small" effect="dark" :type="severityType(selected.severity)">
              {{ selected.severity }}
            </el-tag>
          </div>
          <div v-if="selected" class="logs-drawer-sub">{{ fmtTime(selected.time) }}</div>
        </div>
      </template>

      <template v-if="selected">
        <div class="logs-summary">
          <div class="logs-summary-item"><span>服务</span><b>{{ selected.service || '—' }}</b></div>
          <div class="logs-summary-item"><span>集群 / 命名空间</span><b>{{ selected.cluster || '—' }} / {{ selected.namespace || '—' }}</b></div>
          <div class="logs-summary-item"><span>工作负载</span><b>{{ selected.workload || '—' }}</b></div>
          <div class="logs-summary-item"><span>Pod</span><b>{{ selected.pod || '—' }}</b></div>
          <div class="logs-summary-item"><span>级别数值</span><b>{{ selected.severityNumber ?? '—' }}</b></div>
        </div>

        <div class="logs-section-head">
          <span>正文</span>
          <el-button link @click="copyBody">
            <el-icon><CopyDocument /></el-icon> 复制
          </el-button>
        </div>
        <pre class="logs-detail-body" v-html="highlightBody(selected.body || '（无正文）')"></pre>

        <div class="logs-section-head"><span>Trace / Span</span></div>
        <div class="logs-meta">
          <div class="logs-meta-row">
            <span>Trace</span>
            <template v-if="selected.traceId">
              <el-button v-if="traceState(selected) === 'link'" link type="primary"
                         @click="gotoTraces(selected.traceId)">
                {{ selected.traceId }}
              </el-button>
              <template v-else>
                <code>{{ selected.traceId }}</code>
                <el-tooltip v-if="traceState(selected) === 'unsampled'" placement="top"
                            content="该请求未被链路采样，Tempo 中没有对应 trace">
                  <span class="logs-muted logs-unsampled">未采样</span>
                </el-tooltip>
                <el-tooltip v-else-if="traceState(selected) === 'forbidden'" placement="top"
                            content="当前角色没有链路追踪权限">
                  <span class="logs-muted logs-unsampled">无权限</span>
                </el-tooltip>
              </template>
            </template>
            <code v-else>—</code>
          </div>
          <div class="logs-meta-row"><span>Span</span><code>{{ selected.spanId || '—' }}</code></div>
        </div>

        <template v-if="selected.attributes && Object.keys(selected.attributes).length">
          <div class="logs-section-head"><span>属性</span></div>
          <div class="logs-attr" v-for="(value, key) in selected.attributes" :key="key">
            <span>{{ key }}</span><code>{{ value }}</code>
          </div>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.logs-page { display: flex; flex-direction: column; gap: 14px; }

/* ---------- 工具栏 ---------- */
.logs-toolbar {
  display: flex; flex-wrap: wrap; align-items: center; gap: 10px 18px;
  padding: 14px 16px;
  border: 1px solid var(--border); border-radius: 10px;
  background: linear-gradient(180deg, color-mix(in srgb, var(--accent) 5%, transparent), transparent 70%);
}
.logs-toolbar-main { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 16px; }
.logs-field { display: flex; align-items: center; gap: 8px; }
.logs-label { font-size: 12px; color: var(--text-3); white-space: nowrap; }
.logs-toolbar-hint { flex-basis: 100%; }
.logs-hint-inline {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; color: var(--text-3); cursor: help;
}
.logs-hint-inline:hover { color: var(--text-2); }
.logs-tip { max-width: 420px; }
.logs-tip p { margin: 0 0 6px; font-size: 12px; line-height: 1.65; }
.logs-tip p:last-child { margin-bottom: 0; }

/* ---------- 结果 ---------- */
.logs-result { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.logs-result-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 10px 14px; background: var(--bg);
}
.logs-result-title { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-2); }
.logs-result-count {
  min-width: 22px; padding: 0 7px; text-align: center; border-radius: 999px;
  background: var(--accent-soft); color: var(--accent); font-size: 12px;
}
.logs-result-more { font-size: 11px; color: var(--text-3); }
.logs-result-scope { display: flex; flex-wrap: wrap; gap: 6px; }
.logs-table { width: 100%; }
.logs-table :deep(.el-table__row) { cursor: pointer; }
.logs-table :deep(.logs-row-error > td) { background: color-mix(in srgb, var(--danger) 5%, transparent); }
.logs-table :deep(.logs-row-warn > td) { background: color-mix(in srgb, var(--warning) 4%, transparent); }

.logs-time { font-size: 12px; color: var(--text-1); font-variant-numeric: tabular-nums; white-space: nowrap; }
.logs-body {
  white-space: pre-wrap; word-break: break-all;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  font-size: 12px; line-height: 1.55; color: var(--text-1);
}
.logs-table :deep(.logs-mark) {
  background: color-mix(in srgb, var(--warning) 35%, transparent);
  color: var(--text-1); border-radius: 2px; padding: 0 1px;
}
.logs-ns { line-height: 1.4; font-size: 12px; color: var(--text-1); }
.logs-wl { font-size: 12px; color: var(--text-1); }
.logs-muted { font-size: 12px; color: var(--text-3); }
.logs-unsampled { cursor: help; }
.logs-open { color: var(--text-3); }
.logs-empty { padding: 22px 0; }
.logs-empty-title { font-size: 14px; color: var(--text-2); margin-bottom: 4px; }
.logs-empty-sub { font-size: 12px; color: var(--text-3); }
.logs-more { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 4px 0 10px; }
.logs-more-count { font-size: 12px; color: var(--text-3); }

/* ---------- 详情抽屉 ---------- */
.logs-drawer :deep(.el-drawer__header) {
  margin-bottom: 0; padding-bottom: 12px; border-bottom: 1px solid var(--border);
}
.logs-drawer-head { display: flex; flex-direction: column; gap: 4px; }
.logs-drawer-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 16px; font-weight: 600; color: var(--text-1);
}
.logs-drawer-sub { font-size: 12px; color: var(--text-3); }
.logs-summary { display: flex; flex-wrap: wrap; gap: 8px; }
.logs-summary-item {
  display: flex; flex-direction: column; gap: 2px; min-width: 110px; max-width: 100%;
  padding: 8px 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg);
}
.logs-summary-item span { font-size: 11px; color: var(--text-3); }
.logs-summary-item b {
  font-size: 13px; font-weight: 600; color: var(--text-1);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.logs-section-head {
  display: flex; align-items: center; justify-content: space-between;
  margin: 16px 0 8px; font-size: 12px; color: var(--text-2);
}
.logs-detail-body {
  background: var(--panel-hover); border: 1px solid var(--border); border-radius: 8px;
  padding: 12px; margin: 0; font-size: 12.5px; line-height: 1.65;
  white-space: pre-wrap; word-break: break-all; max-height: 46vh; overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.logs-detail-body :deep(.logs-mark) {
  background: color-mix(in srgb, var(--warning) 35%, transparent);
  color: var(--text-1); border-radius: 2px;
}
.logs-meta { display: flex; flex-direction: column; gap: 6px; font-size: 12px; }
.logs-meta-row {
  display: flex; align-items: flex-start; gap: 10px; padding: 4px 0;
  border-bottom: 1px dashed var(--border);
}
.logs-meta-row > span { flex: 0 0 120px; color: var(--text-3); }
.logs-meta-row > code {
  flex: 1 1 auto; color: var(--text-1); word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.logs-attr { display: flex; gap: 10px; padding: 4px 0; font-size: 12px; border-bottom: 1px dashed var(--border); }
.logs-attr > span { flex: 0 0 200px; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; }
.logs-attr > code {
  flex: 1 1 auto; word-break: break-all; color: var(--text-1);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
