<script setup lang="ts">
/**
 * 审计日志页（S0.1，平台管理面，仅 admin）。
 *
 * - 游标分页：审计表持续追加，offset 会漏读/重读，所以用「上一页最后一条的 (occurredAt, id)」做游标。
 *   游标语义下没有「上一页」—— 首查替换、加载更多追加，往前翻靠重新查询。
 * - 详情 = 行展开，不额外开后端端点（列表已返回全部字段）。
 * - **租户 / 集群列显示名字**：表里存的是 id（审计记录自包含），名字由 lookups 映射；
 *   映射未命中（对象已删除 / 无读权限）回退显示原始 id，鼠标悬停始终能看到 id。
 * - **动作码与目标对象是两列，且排在前面**：它们是最常看的两列，放最右边会因表格横向滚动而看不见。
 * - 页面门控走 pageCodes；接口能力门控走 apiCodes（避免发出注定被拒的请求，与总览页同做法）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditApi, clusterApi, namespaceApi, tenantApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'
import type { AuditLog, AuditQuery, K8sClusterOption, NamespaceView, PlatformTenant } from '@/types'

const perm = usePermission()

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

// ---- 下拉选项与 id→名字 映射：同一次拉取同时喂这两件事 ----

const clusters = ref<K8sClusterOption[]>([])
const tenants = ref<PlatformTenant[]>([])
const namespaces = ref<NamespaceView[]>([])

const clusterNames = computed(() => new Map(clusters.value.map((c) => [c.clusterId, c.clusterName])))
const tenantNames = computed(() => new Map(tenants.value.map((t) => [t.id, t.name])))

function clusterLabel(id?: string | null): string {
  if (!id) return '—'
  return clusterNames.value.get(id) ?? id
}

function tenantLabel(id?: string | null): string {
  if (!id) return '—'
  return tenantNames.value.get(id) ?? id
}

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

/** 目标对象：写操作取 targetName；被拒记录没有目标（拒绝发生在方法调用前），退而显示请求路径 */
function targetText(row: AuditLog): string {
  if (row.targetName) return row.targetName
  if (row.result === 'DENIED' && row.requestPath) return row.requestPath
  return '—'
}

/**
 * 去掉空串与空值再发请求。
 *
 * ⚠️ 这不是洁癖：`el-input` / `el-select` 的 `clearable` 清空后给的是**空串 `''`**，
 * 而后端过滤条件是 `if (xxx != null)` —— 空串不为 null，于是变成 `and cluster_id = ''`，
 * **静默返回 0 行**。必须在这里归一化。
 */
function pruned(q: AuditQuery): AuditQuery {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(q)) {
    if (v === '' || v === null || v === undefined) continue
    out[k] = v
  }
  return out as AuditQuery
}

// ---- 加载 ----

/** 下拉选项 + 名字映射。各按自己的能力码门控，缺权限就静默跳过（后端权威不变）。 */
async function loadLookups(): Promise<void> {
  if (perm.has(apiCodes.allocationList)) {
    clusters.value = await clusterApi.options()
  }
  if (perm.has(apiCodes.tenantRead)) {
    tenants.value = await tenantApi.list()
  }
}

async function loadNamespaces(): Promise<void> {
  const clusterId = filters.clusterId
  if (!clusterId || !perm.has(apiCodes.allocationList)) {
    namespaces.value = []
    return
  }
  namespaces.value = await namespaceApi.list(clusterId)
}

/** 换集群 → 清掉已选命名空间（它属于旧集群，留着会是一条永不命中的过滤条件） */
function onClusterChange(): void {
  filters.namespace = null
  void loadNamespaces()
}

// ---- 动作码候选值（autocomplete） ----

const actionOptions = ref<string[]>([])
let actionsLoaded = false

/**
 * 候选值来自后端 `/audit/actions`（审计表里实际出现过的码），**不在前端写死一份** ——
 * 写死会与后端 AuditAction 漂移。取一次后本地过滤（表里 distinct 的码通常几十个）。
 */
async function fetchActionSuggestions(
  query: string,
  cb: (items: { value: string }[]) => void,
): Promise<void> {
  try {
    if (!actionsLoaded) {
      actionOptions.value = await auditApi.actions()
      actionsLoaded = true
    }
  } catch {
    // 候选值拉不到不该挡住手输 —— 静默降级为自由文本（同 Docker Hub tag 候选的处理原则）
    cb([])
    return
  }
  const q = query.trim().toLowerCase()
  const hit = q ? actionOptions.value.filter((a) => a.toLowerCase().includes(q)) : actionOptions.value
  cb(hit.map((a) => ({ value: a })))
}

async function load(reset = false): Promise<void> {
  if (reset) {
    cursor.value = null
    hasMore.value = false
  }
  loading.value = true
  try {
    const q: AuditQuery = pruned({ ...filters, limit: PAGE_SIZE })
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

onMounted(async () => {
  await loadLookups()
  await load(true)
})
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
      <el-select v-model="filters.clusterId" placeholder="集群" clearable @change="onClusterChange">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-select
        v-model="filters.namespace"
        placeholder="命名空间"
        clearable
        :disabled="!filters.clusterId"
      >
        <el-option v-for="n in namespaces" :key="n.name" :label="n.name" :value="n.name" />
      </el-select>
      <el-select v-model="filters.result" placeholder="结果" clearable>
        <el-option v-for="o in RESULT_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-autocomplete
        v-model="filters.action"
        :fetch-suggestions="fetchActionSuggestions"
        :debounce="200"
        placeholder="动作码 / 权限码"
        clearable
      />
      <el-button type="primary" @click="load(true)">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <div><span class="k">动作码</span>{{ row.action ?? '—' }}</div>
            <div><span class="k">目标对象</span>{{ row.targetName ?? '—' }}</div>
            <div><span class="k">错误码</span>{{ row.errorCode ?? '—' }}</div>
            <div><span class="k">错误信息</span>{{ row.errorMsg ?? '—' }}</div>
            <div><span class="k">请求</span>{{ row.httpMethod }} {{ row.requestPath }}</div>
            <div><span class="k">租户 ID</span>{{ row.tenantId ?? '—' }}</div>
            <div><span class="k">集群 ID</span>{{ row.clusterId ?? '—' }}</div>
            <div><span class="k">User-Agent</span>{{ row.userAgent ?? '—' }}</div>
            <div><span class="k">来源 IP</span>{{ row.clientIp ?? '—' }}</div>
            <div><span class="k">耗时</span>{{ row.durationMs != null ? row.durationMs + ' ms' : '—' }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ fmtDate(row.occurredAt) }}</template>
      </el-table-column>
      <el-table-column label="操作者" width="150">
        <template #default="{ row }">
          {{ row.actorDisplayName || row.actorUsername }}
          <span v-if="row.actorDisplayName && row.actorDisplayName !== row.actorUsername" class="sub">
            {{ row.actorUsername }}
          </span>
        </template>
      </el-table-column>
      <!-- ⚠️ 宽度不得低于 110：SUCCESS tag 实宽约 74px，加左右各 12px padding 需 ≥98px，
           窄了 tag 会溢出单元格（曾在 width=90 时被压在 padding 边界上） -->
      <el-table-column label="结果" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="resultType(row.result)" effect="plain">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="动作码" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <code class="mono">{{ row.action ?? '—' }}</code>
        </template>
      </el-table-column>
      <el-table-column label="目标对象" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ targetText(row) }}</template>
      </el-table-column>
      <el-table-column label="租户" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <span :title="row.tenantId ?? ''">{{ tenantLabel(row.tenantId) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="集群" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <span :title="row.clusterId ?? ''">{{ clusterLabel(row.clusterId) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="namespace" label="命名空间" width="130" show-overflow-tooltip />
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
.filters :deep(.el-select),
.filters :deep(.el-autocomplete) {
  width: 180px;
}
.detail {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px 16px;
  padding: 4px 8px;
}
.detail .k {
  display: inline-block;
  width: 86px;
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
