<script setup lang="ts">
/**
 * 链路追踪（平台自研视图）。
 *
 * 数据源是**管理集群的中心 Tempo**（业务集群的 collector 回传到这里）；平台管理员看全量，
 * 租户用户由后端按 `platform_tenant_namespace` 追加条件：列表在 Tempo 侧过滤，详情只返回
 * 落在其命名空间内的 span（Tempo 的 trace-by-ID 没有 span 级投影）。
 *
 * 注意：平台管理员的查询范围是整条中心 Tempo，不受顶栏租户/集群上下文影响（集群用页面里的
 * 下拉过滤，走 resource.cluster.name，gateway 统一 upsert）；租户用户的边界来自分配表。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, CopyDocument, InfoFilled, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import TraceWaterfall from '@/components/trace/TraceWaterfall.vue'
import { traceApi } from '@/api'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import { writeClipboard } from '@/utils/clipboard'
import type { TraceDetail, TraceSpan, TraceSummary } from '@/types'

const RANGES = [
  { label: '最近 15 分钟', value: 900 },
  { label: '最近 1 小时', value: 3600 },
  { label: '最近 6 小时', value: 21600 },
  { label: '最近 24 小时', value: 86400 },
]

const filters = reactive({
  /** 默认取顶栏上下文；平台管理员可以改（后端按该租户的分配表收窄） */
  tenantId: '',
  /** 默认取顶栏上下文 */
  clusterName: '',
  namespace: '',
  serviceName: '',
  range: 3600,
  limit: 20,
  /** 只看包含 error span 的 trace */
  errorsOnly: false,
  /** 只看耗时 > N ms；0 = 不限 */
  minDurationMs: 0,
})

/** Trace ID 直达输入：接受裸 ID 或含 ID 的链接（Tempo/Jaeger/日志里复制） */
const traceIdInput = ref('')

const rows = ref<TraceSummary[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const perm = usePermission()
const { state: resourceState, currentCluster, load: loadContext } = useResourceContext()
/** 服务下拉：Tempo 里当前上下文下实际出现过的 resource.service.name（可手输） */
const serviceOptions = ref<string[]>([])

/** 筛选范围直接取顶栏三级（页面上不再重复摆租户/集群/命名空间） */
function syncFromContext(): void {
  filters.tenantId = resourceState.tenantId ?? ''
  filters.clusterName = currentCluster.value?.clusterName ?? ''
  filters.namespace = resourceState.namespace ?? ''
}

async function loadServices(): Promise<void> {
  const end = Math.floor(Date.now() / 1000)
  try {
    serviceOptions.value = (await traceApi.services({
      tenantId: filters.tenantId || undefined,
      clusterName: String(filters.clusterName ?? '').trim() || undefined,
      namespace: String(filters.namespace ?? '').trim() || undefined,
      start: end - filters.range,
      end,
    })) ?? []
  } catch {
    serviceOptions.value = [] // 候选拿不到不影响手输
  }
}

// 换租户/集群/命名空间/改时间范围 → 重新取服务候选（服务名只在对应时间窗里有数据）。
// 注册点放在 onMounted 首次加载之后，避免 mount 时重复请求一轮。

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<TraceDetail | null>(null)
const selectedSpan = ref<TraceSpan | null>(null)

async function search(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const end = Math.floor(Date.now() / 1000)
    rows.value = await traceApi.search({
      tenantId: filters.tenantId || undefined,
      clusterName: String(filters.clusterName ?? '').trim() || undefined,
      namespace: String(filters.namespace ?? '').trim() || undefined,
      serviceName: String(filters.serviceName ?? '').trim() || undefined,
      start: end - filters.range,
      end,
      limit: filters.limit,
      errorsOnly: filters.errorsOnly || undefined,
      minDurationMs: filters.minDurationMs > 0 ? filters.minDurationMs : undefined,
    })
  } catch (e) {
    // 后端消息里可能是「租户没有分配任何命名空间」或「Tempo 不可达」，原样展示更有用
    error.value = (e as Error).message || 'Tempo 查询失败：检查 Tempo 是否已安装、platform.tempo.query-url 是否可达'
    rows.value = []
  } finally {
    loading.value = false
  }
}

const route = useRoute()
const router = useRouter()

/** 从裸 ID 或含 ID 的链接里取 traceId（Tempo / Jaeger 链接、日志里复制的都认） */
function extractTraceId(value: string): string | null {
  const match = value.trim().match(/([0-9a-fA-F]{32})|([0-9a-fA-F]{16})/)
  if (!match) return null
  const id = match[1] ?? match[2]
  // 全零是「无有效 trace 上下文」的占位值，拿去查必然 404
  if (!id || /^0+$/.test(id)) return null
  return id.toLowerCase()
}

/** 把当前查看的 trace 写进 URL：刷新/分享链接都能直接回到这条 trace */
function syncTraceQuery(traceId?: string): void {
  const query = { ...route.query }
  if (traceId) query.trace = traceId
  else delete query.trace
  void router.replace({ query })
}

async function openTrace(row: TraceSummary): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  selectedSpan.value = null
  traceIdInput.value = row.traceId
  try {
    detail.value = await traceApi.get(row.traceId)
    selectedSpan.value = detail.value?.spans?.[0] ?? null
    syncTraceQuery(row.traceId)
  } catch (e) {
    // 后端已区分「未采样/过期」与「Tempo 查询失败」，原样展示更有用
    detailVisible.value = false
    ElMessage.error((e as Error).message || 'trace 不存在，或已超出 Tempo 保留期')
  } finally {
    detailLoading.value = false
  }
}

/** Trace ID 直达：粘贴 ID（或含 ID 的链接）直接拉详情，不经过列表 */
async function openTraceById(): Promise<void> {
  const id = extractTraceId(traceIdInput.value)
  if (!id) {
    ElMessage.warning('请粘贴有效的 Trace ID（16/32 位十六进制），或包含它的链接')
    return
  }
  traceIdInput.value = id
  await openTrace({ traceId: id } as TraceSummary)
}

function fmtStart(nano?: string | null): string {
  if (!nano) return '—'
  const ms = Number(nano) / 1_000_000
  return Number.isFinite(ms) ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function fmtDuration(ms?: number | null): string {
  if (ms == null) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms}ms`
}

/** 结果范围（顶栏上下文）→ 标题行的小标签 */
const scopeChips = computed(() => {
  const chips: string[] = []
  if (filters.tenantId) {
    const tenant = resourceState.tenants.find((t) => t.tenantId === filters.tenantId)
    if (tenant) chips.push(tenant.name)
  }
  chips.push(filters.clusterName || '全部集群')
  chips.push(filters.namespace || '全部命名空间')
  return chips
})

/** 列表里最长的那条，用于耗时条的相对宽度 */
const maxDuration = computed(() => Math.max(1, ...rows.value.map((r) => r.durationMs ?? 0)))

/** 相对时间；精确时间放在 tooltip 里 */
function fmtRelative(nano?: string | null): string {
  if (!nano) return '—'
  const ms = Number(nano) / 1_000_000
  if (!Number.isFinite(ms)) return '—'
  const diff = Date.now() - ms
  if (diff < 0) return fmtStart(nano)
  const sec = Math.floor(diff / 1000)
  if (sec < 60) return `${Math.max(1, sec)} 秒前`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  return `${Math.floor(hour / 24)} 天前`
}

/** 慢请求着色：1s 起提醒，3s 起告警 */
function durationClass(ms?: number | null): string {
  if (ms == null) return ''
  if (ms >= 3000) return 'trace-dur-danger'
  if (ms >= 1000) return 'trace-dur-warn'
  return ''
}

function durationBar(ms?: number | null): string {
  return `${Math.max(3, Math.round(((ms ?? 0) / maxDuration.value) * 100))}%`
}

async function copyTraceId(id?: string | null): Promise<void> {
  if (!id) return
  if (await writeClipboard(id)) ElMessage.success('Trace ID 已复制')
  else ElMessage.warning('复制失败，请手动选择')
}

/** 详情汇总 */
const detailSpans = computed<TraceSpan[]>(() => detail.value?.spans ?? [])
const errorSpans = computed(() => detailSpans.value.filter((s) => isErrorStatus(s.statusCode)))
const traceServiceCount = computed(() => new Set(detailSpans.value.map((s) => s.serviceName || '—')).size)
const traceDuration = computed(() => {
  const spans = detailSpans.value
  if (!spans.length) return null
  const min = Math.min(...spans.map((s) => Number(s.startTimeUnixNano ?? 0)))
  const max = Math.max(...spans.map((s) => Number(s.endTimeUnixNano ?? s.startTimeUnixNano ?? 0)))
  return max > min ? Math.round((max - min) / 1_000_000) : null
})

function isErrorStatus(code?: string | null): boolean {
  return (code ?? '').toUpperCase().includes('ERROR')
}

function statusLabel(code?: string | null): string {
  if (isErrorStatus(code)) return 'ERROR'
  return (code ?? '').toUpperCase().includes('OK') ? 'OK' : 'UNSET'
}

function statusType(code?: string | null): 'danger' | 'success' | 'info' {
  if (isErrorStatus(code)) return 'danger'
  return (code ?? '').toUpperCase().includes('OK') ? 'success' : 'info'
}

/** 属性面板可收起，收起后瀑布图占满宽度 */
const attrsCollapsed = ref(false)

onMounted(async () => {
  // 筛选范围 = 顶栏三级（租户/集群/命名空间）。必须等树加载完再映射 clusterId → clusterName。
  await loadContext()
  syncFromContext()
  void loadServices()
  void search()

  // 顶栏切换后跟着走。watcher 在首次 sync 之后再注册，避免 mount 时重复请求一轮。
  watch(() => [resourceState.tenantId, resourceState.clusterId, resourceState.namespace], () => {
    syncFromContext()
    void loadServices()
    void search()
  })
  // 页面里能改的只剩「服务 / 时间范围」；时间范围变化要重取服务候选
  watch(() => filters.range, () => { void loadServices() })

  // 分享链接 / 刷新：?trace=<id> 直接打开详情
  const fromUrl = typeof route.query.trace === 'string' ? extractTraceId(route.query.trace) : null
  if (fromUrl) {
    traceIdInput.value = fromUrl
    void openTrace({ traceId: fromUrl } as TraceSummary)
  }
})
</script>

<template>
  <div class="trace-page">
    <PageHeader title="链路追踪" description="查询管理集群 Tempo 的调用链；范围取顶栏的租户 / 集群 / 命名空间" />

    <div class="trace-toolbar">
      <div class="trace-toolbar-main">
        <div class="trace-field">
          <span class="trace-label">服务</span>
          <el-select v-model="filters.serviceName" filterable allow-create clearable
                     placeholder="全部服务" style="width: 210px">
            <el-option v-for="s in serviceOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </div>
        <div class="trace-field">
          <span class="trace-label">时间</span>
          <el-select v-model="filters.range" style="width: 145px">
            <el-option v-for="r in RANGES" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </div>
        <div class="trace-field">
          <span class="trace-label">耗时 ≥</span>
          <el-input-number v-model="filters.minDurationMs" :min="0" :step="100"
                           controls-position="right" style="width: 112px" />
          <span class="trace-unit">ms</span>
        </div>
        <div class="trace-field">
          <span class="trace-label">条数</span>
          <el-select v-model="filters.limit" style="width: 88px">
            <el-option :value="20" label="20" />
            <el-option :value="50" label="50" />
            <el-option :value="100" label="100" />
          </el-select>
        </div>
        <el-checkbox v-model="filters.errorsOnly" border size="small">只看错误</el-checkbox>
        <el-button type="primary" :loading="loading" @click="search">
          <el-icon><Search /></el-icon>
          <span>查询</span>
        </el-button>
      </div>

      <div class="trace-toolbar-direct">
        <el-input v-model="traceIdInput" class="trace-direct-input" clearable
                  placeholder="Trace ID 或含 ID 的链接，回车直达"
                  @keyup.enter="openTraceById">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="openTraceById">直达</el-button>
      </div>

      <div class="trace-toolbar-hint">
        <el-tooltip placement="top-start" :show-after="150">
          <template #content>
            <div class="trace-tip">
              <p>范围跟随顶栏的租户 / 集群 / 命名空间，改顶栏即自动重查。</p>
              <p>服务名是 OTel 的 <code>service.name</code>（平台默认写工作负载名）；候选来自 Tempo 里实际出现过的值，也可以直接输入。</p>
              <p>日志里的 traceId 只有被采样（OTel 组件配置的采样率）的请求才有对应 trace；未采样的查不到。</p>
              <p v-if="!perm.isAdmin">你只能看到被分配命名空间内的 span；入口 / 网格等平台命名空间的 span 不返回，瀑布图可能缺节点。</p>
            </div>
          </template>
          <span class="trace-hint-inline"><el-icon><InfoFilled /></el-icon> 查询范围与采样说明</span>
        </el-tooltip>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="trace-alert" />

    <div class="trace-result">
      <div class="trace-result-head">
        <div class="trace-result-title">
          查询结果
          <span class="trace-result-count">{{ rows.length }}</span>
        </div>
        <div class="trace-result-scope">
          <el-tag v-for="part in scopeChips" :key="part" size="small" effect="plain" round>{{ part }}</el-tag>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows" class="trace-table" @row-click="openTrace">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <el-tooltip :content="fmtStart(row.startTimeUnixNano)" placement="top">
              <span class="trace-time">{{ fmtRelative(row.startTimeUnixNano) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="服务" width="180">
          <template #default="{ row }">
            <span class="trace-svc">{{ row.rootService || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="入口 Span" min-width="350">
          <template #default="{ row }">
            <el-tooltip :content="row.rootName || '—'" placement="top" :show-after="400">
              <span class="trace-op">{{ row.rootName || '—' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="140">
          <template #default="{ row }">
            <div class="trace-dur-cell" :class="durationClass(row.durationMs)">
              <span class="trace-dur-val">{{ fmtDuration(row.durationMs) }}</span>
              <span class="trace-dur-bar"><i :style="{ width: durationBar(row.durationMs) }"></i></span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="服务 / Span" width="140">
          <template #default="{ row }">
            <span class="trace-counts">{{ row.serviceCount ?? '—' }} 服务 · {{ row.spanCount ?? '—' }} span</span>
          </template>
        </el-table-column>
        <el-table-column label="Trace ID" min-width="300">
          <template #default="{ row }">
            <span class="trace-id-cell">
              <code class="trace-id">{{ row.traceId }}</code>
              <el-tooltip content="复制 Trace ID" placement="top">
                <el-button link class="trace-copy" @click.stop="copyTraceId(row.traceId)">
                  <el-icon><CopyDocument /></el-icon>
                </el-button>
              </el-tooltip>
            </span>
          </template>
        </el-table-column>
        <el-table-column width="56" align="right">
          <template #default>
            <el-icon class="trace-open"><ArrowRight /></el-icon>
          </template>
        </el-table-column>
        <template #empty>
          <div class="trace-empty">
            <div class="trace-empty-title">没有查到 trace</div>
            <div class="trace-empty-sub">Tempo 可能未安装 / 未写入，或该时间范围内没有数据；也可以直接粘贴 Trace ID 查</div>
          </div>
        </template>
      </el-table>
    </div>

    <el-drawer v-model="detailVisible" size="82%" class="trace-drawer" @closed="syncTraceQuery()">
      <template #header>
        <div class="trace-drawer-head">
          <div class="trace-drawer-title">调用链详情</div>
          <div v-if="detail" class="trace-drawer-id">
            <span class="trace-drawer-label">Trace ID</span>
            <code>{{ detail.traceId }}</code>
            <el-button link @click="copyTraceId(detail.traceId)">
              <el-icon><CopyDocument /></el-icon>
            </el-button>
          </div>
        </div>
      </template>

      <div v-loading="detailLoading" class="trace-detail">
        <div v-if="detail" class="trace-summary">
          <div class="trace-summary-item">
            <span>开始</span><b>{{ fmtStart(detail.spans[0]?.startTimeUnixNano) }}</b>
          </div>
          <div class="trace-summary-item">
            <span>总耗时</span><b>{{ fmtDuration(traceDuration) }}</b>
          </div>
          <div class="trace-summary-item">
            <span>服务</span><b>{{ traceServiceCount }}</b>
          </div>
          <div class="trace-summary-item">
            <span>Span</span><b>{{ detail.spans.length }}</b>
          </div>
          <div class="trace-summary-item" :class="{ 'trace-summary-error': errorSpans.length }">
            <span>错误 Span</span><b>{{ errorSpans.length }}</b>
          </div>
        </div>

        <div class="trace-detail-body" :class="{ 'trace-detail-body-wide': attrsCollapsed }">
          <TraceWaterfall
            v-if="detail"
            :spans="detail.spans"
            :selected-span-id="selectedSpan?.spanId"
            @select="selectedSpan = $event"
          />
          <div v-if="!attrsCollapsed" class="trace-attrs">
            <div class="trace-attrs-head">
              <span>Span 属性</span>
              <el-button link class="trace-attrs-toggle" @click="attrsCollapsed = true">收起</el-button>
            </div>
            <template v-if="selectedSpan">
              <div class="trace-attrs-title">
                <el-tag size="small" effect="plain">{{ selectedSpan.serviceName || '—' }}</el-tag>
                <span class="trace-attrs-op">{{ selectedSpan.name }}</span>
              </div>
              <div class="trace-attr-row">
                <span>状态</span>
                <el-tag size="small" effect="plain" :type="statusType(selectedSpan.statusCode)">
                  {{ statusLabel(selectedSpan.statusCode) }}
                </el-tag>
              </div>
              <div class="trace-attr-row"><span>耗时</span><code>{{ fmtDuration(selectedSpan.durationMs) }}</code></div>
              <div class="trace-attr-row"><span>spanId</span><code>{{ selectedSpan.spanId }}</code></div>
              <div class="trace-attr-row"><span>parent</span><code>{{ selectedSpan.parentSpanId || '—' }}</code></div>
              <div class="trace-attr-row">
                <span>集群 / 命名空间</span>
                <code>{{ selectedSpan.clusterName || '—' }} / {{ selectedSpan.namespace || '—' }}</code>
              </div>
              <template v-if="selectedSpan.attributes && Object.keys(selectedSpan.attributes).length">
                <div class="trace-attr-section">属性</div>
                <div v-for="(value, key) in selectedSpan.attributes" :key="key" class="trace-attr-row">
                  <span>{{ key }}</span><code>{{ value }}</code>
                </div>
              </template>
            </template>
            <div v-else class="trace-attrs-empty">点击左侧 span 查看属性</div>
          </div>
          <div v-else class="trace-attrs-collapsed">
            <el-button link @click="attrsCollapsed = false">属性</el-button>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.trace-page { display: flex; flex-direction: column; gap: 14px; }

/* ---------- 工具栏 ---------- */
.trace-toolbar {
  display: flex; flex-wrap: wrap; align-items: center; gap: 10px 18px;
  padding: 14px 16px;
  border: 1px solid var(--border); border-radius: 10px;
  background: linear-gradient(180deg, color-mix(in srgb, var(--accent) 5%, transparent), transparent 70%);
}
.trace-toolbar-main { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 16px; }
.trace-field { display: flex; align-items: center; gap: 8px; }
.trace-label { font-size: 12px; color: var(--text-3); white-space: nowrap; }
.trace-unit { font-size: 12px; color: var(--text-3); }

.trace-toolbar-direct {
  display: flex; align-items: center; gap: 8px;
  margin-left: auto; padding-left: 18px; border-left: 1px solid var(--border);
}
.trace-direct-input { width: 320px; }
.trace-direct-input :deep(.el-input__wrapper) { background: var(--bg); }
.trace-toolbar-hint { flex-basis: 100%; }
.trace-hint-inline {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; color: var(--text-3); cursor: help;
}
.trace-hint-inline:hover { color: var(--text-2); }
.trace-tip { max-width: 420px; }
.trace-tip p { margin: 0 0 6px; font-size: 12px; line-height: 1.65; }
.trace-tip p:last-child { margin-bottom: 0; }

/* ---------- 结果 ---------- */
.trace-result { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.trace-result-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 10px 14px; background: var(--bg);
}
.trace-result-title { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-2); }
.trace-result-count {
  min-width: 22px; padding: 0 7px; text-align: center; border-radius: 999px;
  background: var(--accent-soft); color: var(--accent); font-size: 12px;
}
.trace-result-scope { display: flex; flex-wrap: wrap; gap: 6px; }
.trace-table { width: 100%; }
.trace-table :deep(.el-table__row) { cursor: pointer; }
.trace-table :deep(.el-table__row:hover > td) {
  background: color-mix(in srgb, var(--accent) 6%, transparent) !important;
}
.trace-time { color: var(--text-2); }
.trace-svc {
  display: inline-flex; align-items: center; padding: 1px 8px; border-radius: 6px;
  background: var(--accent-soft); color: var(--accent); font-size: 12px;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.trace-op { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-1); }
.trace-dur-cell { display: flex; flex-direction: column; gap: 4px; }
.trace-dur-val { font-size: 12px; font-variant-numeric: tabular-nums; color: var(--text-1); }
.trace-dur-bar { width: 92px; height: 3px; border-radius: 2px; background: var(--border); overflow: hidden; }
.trace-dur-bar i { display: block; height: 100%; border-radius: 2px; background: var(--accent); }
.trace-dur-warn .trace-dur-val { color: var(--warning); }
.trace-dur-warn .trace-dur-bar i { background: var(--warning); }
.trace-dur-danger .trace-dur-val { color: var(--danger); }
.trace-dur-danger .trace-dur-bar i { background: var(--danger); }
.trace-counts { font-size: 12px; color: var(--text-3); white-space: nowrap; }
.trace-id-cell { display: inline-flex; align-items: center; gap: 6px; min-width: 0; }
.trace-id { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: var(--text-2); }
.trace-copy { opacity: 0; transition: opacity .15s; }
.trace-table :deep(.el-table__row:hover .trace-copy) { opacity: 1; }
.trace-open { color: var(--text-3); }
.trace-empty { padding: 22px 0; }
.trace-empty-title { font-size: 14px; color: var(--text-2); margin-bottom: 4px; }
.trace-empty-sub { font-size: 12px; color: var(--text-3); }

/* ---------- 详情抽屉 ---------- */
.trace-drawer :deep(.el-drawer__header) {
  margin-bottom: 0; padding-bottom: 12px; border-bottom: 1px solid var(--border);
}
.trace-drawer-head { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.trace-drawer-title { font-size: 16px; font-weight: 600; color: var(--text-1); }
.trace-drawer-id { display: flex; align-items: center; gap: 8px; min-width: 0; }
.trace-drawer-label { font-size: 11px; color: var(--text-3); }
.trace-drawer-id code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px;
  color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.trace-detail { display: flex; flex-direction: column; gap: 12px; min-height: 240px; }
.trace-summary { display: flex; flex-wrap: wrap; gap: 8px; }
.trace-summary-item {
  display: flex; flex-direction: column; gap: 2px; min-width: 104px;
  padding: 8px 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg);
}
.trace-summary-item span { font-size: 11px; color: var(--text-3); }
.trace-summary-item b { font-size: 14px; font-weight: 600; color: var(--text-1); font-variant-numeric: tabular-nums; }
.trace-summary-error b { color: var(--danger); }
.trace-detail-body { display: flex; gap: 14px; align-items: flex-start; }
.trace-detail-body > :first-child { flex: 1 1 auto; min-width: 0; }
.trace-attrs {
  flex: 0 0 360px; border: 1px solid var(--border); border-radius: 8px;
  padding: 10px 12px; max-height: 66vh; overflow: auto;
}
.trace-attrs-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12px; color: var(--text-2); margin-bottom: 8px;
}
.trace-attrs-toggle { font-size: 12px; }
.trace-attrs-title { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; min-width: 0; }
.trace-attrs-op { font-size: 12px; color: var(--text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trace-attrs-empty { font-size: 12px; color: var(--text-3); padding: 12px 0; }
.trace-attrs-collapsed { flex: 0 0 36px; display: flex; justify-content: center; padding-top: 4px; }
.trace-attr-row {
  display: flex; align-items: flex-start; gap: 10px; padding: 4px 0; font-size: 12px;
  border-bottom: 1px dashed var(--border);
}
.trace-attr-row > span { flex: 0 0 120px; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; }
.trace-attr-row > code {
  flex: 1 1 auto; color: var(--text-1); word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.trace-attr-section { margin: 10px 0 2px; font-size: 12px; color: var(--text-2); }

@media (max-width: 1200px) {
  .trace-toolbar-direct { margin-left: 0; padding-left: 0; border-left: none; flex-basis: 100%; }
  .trace-direct-input { width: min(420px, 100%); }
}
</style>
