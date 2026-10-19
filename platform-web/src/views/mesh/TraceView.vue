<script setup lang="ts">
/**
 * 链路追踪（平台自研视图）。
 *
 * 数据源是**管理集群的中心 Tempo**（业务集群的 collector 回传到这里）；页面只给平台管理员
 * （pageCodes.mesh.trace + 后端 /traces/** 的权限行）。
 *
 * 注意：查询范围是整条中心 Tempo，不是当前顶栏的租户/集群上下文 —— 集群用输入框过滤，
 * 走 resource.cluster.name（gateway 统一 upsert）。
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import TraceWaterfall from '@/components/trace/TraceWaterfall.vue'
import { clusterApi, traceApi } from '@/api'
import type { TraceDetail, TraceSpan, TraceSummary } from '@/types'

const RANGES = [
  { label: '最近 15 分钟', value: 900 },
  { label: '最近 1 小时', value: 3600 },
  { label: '最近 6 小时', value: 21600 },
  { label: '最近 24 小时', value: 86400 },
]

const filters = reactive({
  clusterName: '',
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

/** 集群下拉：窄投影（clusterId/clusterName），页面本身就是 admin-only */
const clusterOptions = ref<{ clusterId: string; clusterName: string }[]>([])
/** 服务下拉：Tempo 里该集群实际出现过的 resource.service.name（可手输） */
const serviceOptions = ref<string[]>([])

async function loadClusters(): Promise<void> {
  try {
    clusterOptions.value = (await clusterApi.options()) ?? []
  } catch {
    clusterOptions.value = []
  }
}

async function loadServices(): Promise<void> {
  const end = Math.floor(Date.now() / 1000)
  try {
    serviceOptions.value = (await traceApi.services({
      clusterName: String(filters.clusterName ?? '').trim() || undefined,
      start: end - filters.range,
      end,
    })) ?? []
  } catch {
    serviceOptions.value = [] // 候选拿不到不影响手输
  }
}

// 换集群/改时间范围 → 重新取服务候选（服务名只在对应时间窗里有数据）
watch(() => [filters.clusterName, filters.range], () => { void loadServices() })

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
      clusterName: String(filters.clusterName ?? '').trim() || undefined,
      serviceName: String(filters.serviceName ?? '').trim() || undefined,
      start: end - filters.range,
      end,
      limit: filters.limit,
      errorsOnly: filters.errorsOnly || undefined,
      minDurationMs: filters.minDurationMs > 0 ? filters.minDurationMs : undefined,
    })
  } catch {
    error.value = 'Tempo 查询失败：检查 Tempo 是否已安装、platform.tempo.query-url 是否可达'
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
  return id ? id.toLowerCase() : null
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
  } catch {
    ElMessage.error('trace 不存在，或已超出 Tempo 保留期')
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

onMounted(() => {
  void loadClusters()
  void loadServices()
  void search()
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
    <PageHeader title="链路追踪" description="查询管理集群 Tempo 的调用链；集群 / 服务按 resource 属性过滤" />

    <div class="trace-filters">
      <el-form inline>
        <el-form-item label="集群">
          <el-select v-model="filters.clusterName" filterable clearable placeholder="全部集群"
                     style="width: 190px">
            <el-option v-for="c in clusterOptions" :key="c.clusterId"
                       :label="c.clusterName" :value="c.clusterName" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务">
          <el-select v-model="filters.serviceName" filterable allow-create clearable
                     placeholder="全部服务" style="width: 220px">
            <el-option v-for="s in serviceOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-select v-model="filters.range" style="width: 150px">
            <el-option v-for="r in RANGES" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="条数">
          <el-select v-model="filters.limit" style="width: 100px">
            <el-option :value="20" label="20" />
            <el-option :value="50" label="50" />
            <el-option :value="100" label="100" />
          </el-select>
        </el-form-item>
        <el-form-item label="只看错误">
          <el-checkbox v-model="filters.errorsOnly">包含 error span</el-checkbox>
        </el-form-item>
        <el-form-item label="耗时 ≥">
          <el-input-number v-model="filters.minDurationMs" :min="0" :step="100"
                           controls-position="right" style="width: 130px" />
          <span class="trace-hint">ms，0 = 不限</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        </el-form-item>
        <el-form-item label="Trace ID">
          <el-input v-model="traceIdInput" clearable style="width: 320px"
                    placeholder="粘贴 Trace ID 或含 ID 的链接" @keyup.enter="openTraceById" />
        </el-form-item>
        <el-form-item>
          <el-button @click="openTraceById">查看</el-button>
        </el-form-item>
      </el-form>
      <div class="trace-hint">
        服务名是 OTel 的 <code>service.name</code>（平台默认写入工作负载名；ingress / waypoint 报的是它们自己的服务名）。
        下拉候选来自 Tempo 里该集群实际出现过的值，也可以直接输入
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="trace-alert" />

    <el-table v-loading="loading" :data="rows" class="trace-table" @row-click="openTrace">
      <el-table-column label="时间" width="180">
        <template #default="{ row }">{{ fmtStart(row.startTimeUnixNano) }}</template>
      </el-table-column>
      <el-table-column prop="rootService" label="服务" width="180" />
      <el-table-column prop="rootName" label="入口 Span" min-width="220" />
      <el-table-column label="耗时" width="110">
        <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
      </el-table-column>
      <el-table-column label="服务 / span" width="110">
        <template #default="{ row }">{{ row.serviceCount ?? '—' }} / {{ row.spanCount ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="Trace ID" min-width="300">
        <template #default="{ row }"><span class="trace-id">{{ row.traceId }}</span></template>
      </el-table-column>
      <template #empty>
        没有查到 trace（Tempo 可能未安装 / 未写入，或时间范围内没有数据）
      </template>
    </el-table>

    <el-drawer v-model="detailVisible" title="调用链详情" size="78%" @closed="syncTraceQuery()">
      <div v-loading="detailLoading" class="trace-detail">
        <div class="trace-detail-head">
          <span>Trace ID：<code>{{ detail?.traceId || '—' }}</code></span>
          <span v-if="selectedSpan" class="trace-detail-selected">
            {{ selectedSpan.serviceName || '—' }} · {{ selectedSpan.name }}
          </span>
        </div>
        <div class="trace-detail-body">
          <TraceWaterfall
            v-if="detail"
            :spans="detail.spans"
            :selected-span-id="selectedSpan?.spanId"
            @select="selectedSpan = $event"
          />
          <div class="trace-attrs">
            <div class="trace-attrs-head">Span 属性</div>
            <template v-if="selectedSpan">
              <div class="trace-attr-row"><span>spanId</span><code>{{ selectedSpan.spanId }}</code></div>
              <div class="trace-attr-row"><span>parent</span><code>{{ selectedSpan.parentSpanId || '—' }}</code></div>
              <div class="trace-attr-row"><span>服务</span><code>{{ selectedSpan.serviceName || '—' }}</code></div>
              <div class="trace-attr-row"><span>集群</span><code>{{ selectedSpan.clusterName || '—' }}</code></div>
              <div class="trace-attr-row"><span>命名空间</span><code>{{ selectedSpan.namespace || '—' }}</code></div>
              <div class="trace-attr-row"><span>状态</span><code>{{ selectedSpan.statusCode || '—' }}</code></div>
              <div class="trace-attr-row"><span>耗时</span><code>{{ fmtDuration(selectedSpan.durationMs) }}</code></div>
              <div v-for="(value, key) in selectedSpan.attributes || {}" :key="key" class="trace-attr-row">
                <span>{{ key }}</span><code>{{ value }}</code>
              </div>
            </template>
            <div v-else class="trace-attrs-empty">点击左侧 span 查看属性</div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.trace-page { display: flex; flex-direction: column; gap: 12px; }
.trace-filters { padding: 4px 0; }
.trace-hint { font-size: 12px; color: var(--text-3); padding: 0 0 4px 4px; }
.trace-alert { margin-bottom: 4px; }
.trace-table { cursor: pointer; }
.trace-id { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.trace-detail { display: flex; flex-direction: column; gap: 12px; min-height: 240px; }
.trace-detail-head {
  display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap;
  font-size: 13px; color: var(--text-2);
}
.trace-detail-selected { color: var(--accent); }
.trace-detail-body { display: flex; gap: 16px; align-items: flex-start; }
.trace-detail-body > :first-child { flex: 1 1 auto; min-width: 0; }
.trace-attrs {
  flex: 0 0 360px; border: 1px solid var(--border); border-radius: 8px;
  padding: 10px 12px; max-height: 640px; overflow: auto;
}
.trace-attrs-head { font-size: 12px; color: var(--text-2); margin-bottom: 8px; }
.trace-attrs-empty { font-size: 12px; color: var(--text-3); padding: 12px 0; }
.trace-attr-row {
  display: flex; gap: 10px; padding: 3px 0; font-size: 12px;
  border-bottom: 1px dashed var(--border);
}
.trace-attr-row > span { flex: 0 0 120px; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; }
.trace-attr-row > code { flex: 1 1 auto; color: var(--text-1); word-break: break-all; }
</style>
