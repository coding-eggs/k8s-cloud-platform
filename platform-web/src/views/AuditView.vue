<script setup lang="ts">
/**
 * 审计日志页（S0.1，平台管理面，仅 admin）。
 *
 * - 游标分页：审计表持续追加，offset 会漏读/重读，所以用「上一页最后一条的 (occurredAt, id)」做游标。
 *   游标语义下没有「上一页」——首查替换、加载更多追加，往前翻靠重新查询。
 * - 详情 = 行展开，不额外开后端端点（列表已返回全部字段）。
 * - 动作列语义随 result 变：写操作看动作码 + 目标对象；DENIED 看被拒权限码 + 请求路径。
 * - 页面门控走 pageCodes（菜单/路由/守卫同源），不写字面量。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditApi } from '@/api'
import type { AuditLog, AuditQuery } from '@/types'

const rows = ref<AuditLog[]>([])
const loading = ref(false)
const cursor = ref<{ occurredAt: string; id: number } | null>(null)
/** 上一页是否满页；不满说明已到底（游标语义下没有「总数」可问） */
const hasMore = ref(false)

const PAGE_SIZE = 50

const filters = reactive<AuditQuery>({
  from: null,
  to: null,
  actorUsername: null,
  clusterId: null,
  namespace: null,
  result: null,
  action: null,
})

const RESULT_OPTIONS = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'DENIED', label: '被拒' },
]

function resultType(r: AuditLog['result']): 'success' | 'danger' | 'warning' {
  if (r === 'SUCCESS') return 'success'
  if (r === 'FAILED') return 'danger'
  return 'warning'
}

function fmtDate(v?: string | null): string {
  if (!v) return '—'
  return new Date(v).toLocaleString('zh-CN')
}

/** 动作列：写操作 = 动作码 → 目标对象；DENIED = 被拒权限码 + 方法 + 路径 */
function actionText(row: AuditLog): string {
  if (row.result === 'DENIED') {
    return `${row.action ?? '（无权限码）'} ${row.httpMethod ?? ''} ${row.requestPath ?? ''}`.trim()
  }
  return `${row.action ?? '—'}${row.targetName ? ' → ' + row.targetName : ''}`
}

async function load(reset = false): Promise<void> {
  if (reset) {
    cursor.value = null
    hasMore.value = false
  }
  loading.value = true
  try {
    const q: AuditQuery = { ...filters, limit: PAGE_SIZE }
    if (cursor.value) {
      q.cursorOccurredAt = cursor.value.occurredAt
      q.cursorId = cursor.value.id
    }
    const page = await auditApi.list(q)
    rows.value = reset ? page : [...rows.value, ...page]
    hasMore.value = page.length === PAGE_SIZE
    const last = page[page.length - 1]
    cursor.value = last ? { occurredAt: last.occurredAt, id: last.id } : null
  } finally {
    loading.value = false
  }
}

async function purge(): Promise<void> {
  if (!filters.from || !filters.to) {
    ElMessage.warning('请先在上方选择起始与结束时间')
    return
  }
  await ElMessageBox.confirm(
    `将删除 ${fmtDate(filters.from)} 至 ${fmtDate(filters.to)} 之间的全部审计记录，不可恢复。`,
    '删除审计记录',
    { type: 'warning' },
  )
  const deleted = await auditApi.purge(filters.from, filters.to)
  ElMessage.success(`已删除 ${deleted} 条`)
  void load(true)
}

onMounted(() => void load(true))
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="ph-title">审计日志</h2>
        <p class="ph-desc">写操作与 403 被拒请求的留痕；按时间倒序，游标分页</p>
      </div>
      <el-button type="danger" plain @click="purge">按时间范围删除</el-button>
    </div>

    <div class="panel filters">
      <el-date-picker
        v-model="filters.from"
        type="datetime"
        value-format="YYYY-MM-DDTHH:mm:ss"
        placeholder="起始时间"
      />
      <el-date-picker
        v-model="filters.to"
        type="datetime"
        value-format="YYYY-MM-DDTHH:mm:ss"
        placeholder="结束时间"
      />
      <el-input v-model="filters.actorUsername" placeholder="操作者账号" clearable />
      <el-input v-model="filters.clusterId" placeholder="集群 ID" clearable />
      <el-input v-model="filters.namespace" placeholder="命名空间" clearable />
      <el-select v-model="filters.result" placeholder="结果" clearable>
        <el-option v-for="o in RESULT_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-input v-model="filters.action" placeholder="动作码 / 权限码" clearable />
      <el-button type="primary" @click="load(true)">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <div><span class="k">错误码</span>{{ row.errorCode ?? '—' }}</div>
            <div><span class="k">错误信息</span>{{ row.errorMsg ?? '—' }}</div>
            <div><span class="k">请求路径</span>{{ row.requestPath ?? '—' }}</div>
            <div><span class="k">User-Agent</span>{{ row.userAgent ?? '—' }}</div>
            <div><span class="k">来源 IP</span>{{ row.clientIp ?? '—' }}</div>
            <div><span class="k">耗时</span>{{ row.durationMs != null ? row.durationMs + ' ms' : '—' }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="180">
        <template #default="{ row }">{{ fmtDate(row.occurredAt) }}</template>
      </el-table-column>
      <el-table-column label="操作者" width="190">
        <template #default="{ row }">
          {{ row.actorDisplayName || row.actorUsername }}
          <span v-if="row.actorDisplayName" class="sub">{{ row.actorUsername }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="resultType(row.result)" effect="plain">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="tenantId" label="租户" width="120" show-overflow-tooltip />
      <el-table-column prop="clusterId" label="集群" width="120" show-overflow-tooltip />
      <el-table-column prop="namespace" label="命名空间" width="140" show-overflow-tooltip />
      <el-table-column label="动作 / 目标" min-width="280" show-overflow-tooltip>
        <template #default="{ row }">
          <code class="mono">{{ actionText(row) }}</code>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-button :disabled="!cursor || !hasMore || loading" @click="load(false)">加载更多</el-button>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.ph-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
}
.ph-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 12px;
  margin-bottom: 12px;
}
.filters :deep(.el-input),
.filters :deep(.el-select) {
  width: 180px;
}
.detail {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 16px;
  padding: 4px 8px;
}
.detail .k {
  display: inline-block;
  width: 96px;
  color: var(--text-3);
}
.sub {
  margin-left: 6px;
  color: var(--text-3);
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.pager {
  margin-top: 12px;
  text-align: right;
}
</style>
