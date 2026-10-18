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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import TraceWaterfall from '@/components/trace/TraceWaterfall.vue'
import { traceApi } from '@/api'
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
})

const rows = ref<TraceSummary[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

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
      clusterName: filters.clusterName.trim() || undefined,
      serviceName: filters.serviceName.trim() || undefined,
      start: end - filters.range,
      end,
      limit: filters.limit,
    })
  } catch {
    error.value = 'Tempo 查询失败：检查 Tempo 是否已安装、platform.tempo.query-url 是否可达'
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function openTrace(row: TraceSummary): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  selectedSpan.value = null
  try {
    detail.value = await traceApi.get(row.traceId)
    selectedSpan.value = detail.value?.spans?.[0] ?? null
  } catch {
    ElMessage.error('trace 详情加载失败')
  } finally {
    detailLoading.value = false
  }
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

onMounted(search)
</script>

<template>
  <div class="trace-page">
    <PageHeader title="链路追踪" description="查询管理集群 Tempo 的调用链；集群 / 服务按 resource 属性过滤" />

    <div class="trace-filters">
      <el-form inline>
        <el-form-item label="集群">
          <el-input v-model="filters.clusterName" placeholder="coding-cluster" clearable
                    style="width: 190px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="服务">
          <el-input v-model="filters.serviceName" placeholder="service.name" clearable
                    style="width: 190px" @keyup.enter="search" />
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
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        </el-form-item>
      </el-form>
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
      <el-table-column label="Trace ID" min-width="300">
        <template #default="{ row }"><span class="trace-id">{{ row.traceId }}</span></template>
      </el-table-column>
      <template #empty>
        没有查到 trace（Tempo 可能未安装 / 未写入，或时间范围内没有数据）
      </template>
    </el-table>

    <el-drawer v-model="detailVisible" title="调用链详情" size="78%">
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
