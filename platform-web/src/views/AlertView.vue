<script setup lang="ts">
/**
 * 告警列表页（S2.2，块 3）。
 *
 * - 数据是**平台库里的表**（platform_alert_event），不是 K8s 对象，所以本页没有命名空间上下文依赖，
 *   只需要选好集群；租户身份由后端按 token 自动收窄（零分配 = 空集，不是"不过滤"）。
 * - 游标分页：告警表持续追加，offset 翻页会漏读/重读 → 用「上一页最后一条的 (startsAt, id)」。
 *   游标语义下没有「上一页」：首查替换、加载更多追加。
 * - labels / annotations 是 **JSON 串**，展示前要 parse —— 脏数据不能让整页崩（try/catch + 原文兜底）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alertApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import type { AlertQuery, PlatformAlertEvent } from '@/types'
import { usePermission } from '@/stores/permission'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentCluster, load } = useResourceContext()
const perm = usePermission()

/**
 * 按钮门控（接口才是权威判定，这里只是别让用户点到注定 403 的按钮）：
 * - 自检会往真实命名空间写两条测试告警 → 仅 `platform:alert:test`（内置 admin）；
 * - 删除用 `platform:alert:read` → 租户角色没有该码，整列对租户隐藏。
 * 列表本身租户能看（`tenant:alert:list`），数据由后端按分配表收窄。
 */
const canSelfTest = computed(() => perm.has(apiCodes.alertTest))
const canDelete = computed(() => perm.has(apiCodes.alertRead))
/** 「全部命名空间」开关的可见性 = 平台侧告警读码（管理员）。租户永远只看当前命名空间。 */
const canSeeAll = computed(() => perm.has(apiCodes.alertRead))

const PAGE_SIZE = 50

const rows = ref<PlatformAlertEvent[]>([])
const loading = ref(false)
/** 上一页是否满页；不满说明已到底（游标语义下没有「总数」可问） */
const hasMore = ref(false)

const filters = reactive({
  /**
   * ⚠️ 存**字符串**不存 Date：后端的 Date 反序列化用的是全局 `yyyy-MM-dd HH:mm:ss`
   * （JsonMapperConfig），发 ISO（`toISOString()` 那种带 T 和 Z 的）会解析失败，
   * 症状是「筛选后永远是空列表」。`value-format` 让日期选择器直接吐这个格式。
   */
  range: null as [string, string] | null,
  status: '',
  severity: '',
  alertname: '',
  showWatchdog: false,
  /**
   * 全部命名空间（**仅管理员**）。默认关 —— 告警页跟导航栏的命名空间走；
   * 跨命名空间的多是集群配置 / 节点类告警（kube-system、monitor 等），不是业务告警，
   * 所以这个开关只给 `platform:alert:read` 的人。
   */
  allNamespaces: false,
})

const STATUS_OPTIONS = [
  { value: 'firing', label: 'firing（未恢复）' },
  { value: 'resolved', label: 'resolved（已恢复）' },
]

const SEVERITY_OPTIONS = ['critical', 'warning', 'info'].map((v) => ({ value: v, label: v }))

/**
 * 去掉空串与空值再发请求。
 *
 * ⚠️ 不是洁癖：`el-input` / `el-select` 的 clearable 清空后给的是**空串 `''`**，
 * 而后端过滤条件是 `if (xxx != null)` —— 空串不为 null，于是变成 `and status = ''`，**静默 0 行**。
 * 注意 `showWatchdog: false` 必须保留（false 有意义，不能当空值丢掉）。
 */
function pruned(q: Record<string, unknown>): AlertQuery {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(q)) {
    if (v === '' || v === null || v === undefined) continue
    out[k] = v
  }
  return out as unknown as AlertQuery
}

function buildQuery(extra?: Partial<AlertQuery>): AlertQuery {
  return pruned({
    clusterId: state.clusterId,
    limit: PAGE_SIZE,
    showWatchdog: filters.showWatchdog,
    from: filters.range ? filters.range[0] : null,
    to: filters.range ? filters.range[1] : null,
    status: filters.status,
    severity: filters.severity,
    // 命名空间取**导航栏**的当前选择；管理员可切「全部命名空间」→ 不传该条件（后端按 token 范围给全量）
    namespace: filters.allNamespaces && canSeeAll.value ? null : state.namespace,
    alertname: filters.alertname,
    ...extra,
  })
}

async function refresh(): Promise<void> {
  if (!ready.value || !state.clusterId) return
  loading.value = true
  try {
    rows.value = await alertApi.list(buildQuery())
    hasMore.value = rows.value.length === PAGE_SIZE
  } catch {
    rows.value = []
    hasMore.value = false
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  const last = rows.value[rows.value.length - 1]
  if (!last) return
  loading.value = true
  try {
    const next = await alertApi.list(buildQuery({ cursorStartsAt: last.startsAt, cursorId: last.id }))
    rows.value = [...rows.value, ...next]
    hasMore.value = next.length === PAGE_SIZE
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

// ---------- 自检 ----------
const selfTesting = ref(false)
async function onSelfTest(): Promise<void> {
  if (!ready.value || !state.clusterId) return
  selfTesting.value = true
  try {
    const r = await alertApi.selfTest(state.clusterId)
    if (r.status === 200 && !r.error) {
      ElMessage.success(`自检完成：HTTP ${r.status}，落库 ${r.rowCount} 行`)
    } else {
      ElMessage.warning(
        `自检返回 HTTP ${r.status}，落库 ${r.rowCount} 行${r.error ? `：${r.error}` : ''}`,
      )
    }
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    selfTesting.value = false
  }
}

// ---------- 删除（清测试数据） ----------
async function onDelete(row: PlatformAlertEvent): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除告警记录「${row.alertname}」？这只是清平台库里的展示数据。`, '提示', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await alertApi.remove(row.id)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉 ----------
const drawerVisible = ref(false)
const detail = ref<PlatformAlertEvent | null>(null)

function openDetail(row: PlatformAlertEvent): void {
  detail.value = row
  drawerVisible.value = true
}

/** labels / annotations 是 JSON 串；解析失败返回 null（页面显示原文，不崩）。 */
function safeParse(json?: string | null): Record<string, string> | null {
  if (!json) return null
  try {
    const v = JSON.parse(json)
    return v && typeof v === 'object' ? (v as Record<string, string>) : null
  } catch {
    return null
  }
}

const detailLabels = computed(() => safeParse(detail.value?.labels))
const detailAnnotations = computed(() => safeParse(detail.value?.annotations))

/** Record → 表格行（模板里不写 Object.entries，避免 vue-tsc 对 null 收窄的分歧）。 */
function kvRows(m: Record<string, string> | null): { k: string; v: string }[] {
  return m ? Object.entries(m).map(([k, v]) => ({ k, v: String(v) })) : []
}

// ---------- 展示辅助 ----------
function severityType(s?: string | null): 'danger' | 'warning' | 'info' | 'success' {
  if (s === 'critical') return 'danger'
  if (s === 'warning') return 'warning'
  if (s === 'info') return 'info'
  return 'success'
}

/** 多个候选时给悬停看全量 */
function sourceTitle(row: PlatformAlertEvent): string {
  const src = row.sources ?? []
  if (!src.length) return '规则可能已改名或删除（历史告警），或当前身份读不到该命名空间的 PrometheusRule'
  return src.map((x) => `${x.cr} / ${x.group}`).join('; ')
}

/** 命名空间为空 = 规则没经平台下发，归属不明；悬停说明它是"不属于任何租户"。 */
const noNamespaceTip = '这条告警没有命名空间标签 —— 规则多半是 kubectl 直连建的，不属于任何租户；管理员仍能在这里看到它。'

// ---------- 上下文联动 ----------
onMounted(() => {
  void load()
  void refresh()
})
watch(
  // ⚠️ 必须连 namespace 一起监听：它是查询条件（取自导航栏），切了不刷新会让人看着旧 ns 的数据
  [() => state.clusterId, () => state.namespace, ready],
  () => {
    if (ready.value) void refresh()
  },
)

const clusterDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择集群'
  const ns = filters.allNamespaces && canSeeAll.value ? '全部命名空间' : (state.namespace ?? '')
  return `${currentCluster.value?.clusterName ?? state.clusterId ?? ''} / ${ns}`
})
</script>

<template>
  <div>
    <PageHeader title="告警" :description="clusterDesc">
      <el-button v-if="canSelfTest" :loading="selfTesting" :disabled="!ready" @click="onSelfTest">自检</el-button>
      <el-button :disabled="!ready" @click="refresh">刷新</el-button>
    </PageHeader>

    <EmptyState
      v-if="!ready"
      title="尚未选择集群"
      description="告警是平台库里的数据，按集群查询 —— 请在顶栏选择集群。"
    />

    <template v-else>
      <div class="panel filter-panel">
        <div class="filter-row">
          <el-date-picker
            v-model="filters.range"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始时间 ≥"
            end-placeholder="开始时间 <"
            style="width: 380px"
          />
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 160px">
            <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-select v-model="filters.severity" placeholder="严重度" clearable style="width: 120px">
            <el-option v-for="o in SEVERITY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-input v-model="filters.alertname" placeholder="告警名（模糊）" clearable style="width: 180px" />
          <el-button @click="refresh">查询</el-button>
        </div>
        <div class="filter-row">
          <template v-if="canSeeAll">
            <span class="muted">全部命名空间</span>
            <el-switch v-model="filters.allNamespaces" @change="refresh" />
            <span class="muted tip">跨命名空间的多是集群配置 / 节点类告警，不是业务告警。</span>
            <span class="sep">|</span>
          </template>
          <span class="muted">显示心跳告警</span>
          <el-switch v-model="filters.showWatchdog" @change="refresh" />
          <span class="muted tip">默认隐藏 Watchdog —— 它是「一切正常」的心跳，会淹没真实告警。</span>
        </div>
      </div>

      <EmptyState
        v-if="rows.length === 0 && !loading"
        title="没有匹配的告警"
        description="集群侧 Alertmanager 还没推到平台？可以点右上角「自检」验一下平台这一侧的链路。"
      />

      <div v-else class="panel table-panel">
        <el-table v-loading="loading" :data="rows" stripe>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'firing'" size="small" type="danger" effect="plain">firing</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">resolved</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="告警名" min-width="220">
            <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.alertname }}</code></template>
          </el-table-column>
          <el-table-column label="来源" min-width="220">
            <template #default="{ row }">
              <span v-if="row.sources && row.sources.length" :title="sourceTitle(row)">
                <code class="res-name">{{ row.sources![0]!.cr }}</code>
                <span class="muted"> / {{ row.sources![0]!.group }}</span>
                <el-tag v-if="row.sources.length > 1" size="small" type="info" effect="plain" class="src-more">+{{ row.sources.length - 1 }}</el-tag>
              </span>
              <span v-else class="muted" :title="sourceTitle(row)">—</span>
            </template>
          </el-table-column>
          <el-table-column label="通知方式" width="160">
            <template #default="{ row }">
              <span v-if="row.notifyChannelName">{{ row.notifyChannelName }}</span>
              <span v-else class="muted" title="规则没有绑定通知方式，按默认渠道发送">—（默认）</span>
            </template>
          </el-table-column>
          <el-table-column label="投递" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.deliveryStatus === 'SENT'" size="small" type="success" effect="plain">已发送</el-tag>
              <el-tag v-else-if="row.deliveryStatus === 'FAILED'" size="small" type="danger" effect="plain" :title="row.deliveryError">失败</el-tag>
              <el-tag v-else-if="row.deliveryStatus === 'PENDING'" size="small" type="info" effect="plain">发送中</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="严重度" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.severity" size="small" :type="severityType(row.severity)" effect="plain">{{ row.severity }}</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="命名空间" width="180">
            <template #default="{ row }">
              <span v-if="row.namespace">{{ row.namespace }}</span>
              <span v-else class="muted" :title="noNamespaceTip">—</span>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" width="170">
            <template #default="{ row }">{{ fmtDate(row.startsAt) }}</template>
          </el-table-column>
          <el-table-column label="结束时间" width="170">
            <template #default="{ row }">
              <span v-if="row.endsAt">{{ fmtDate(row.endsAt) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canDelete" label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="danger" @click="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="hasMore" class="more-row">
          <el-button :loading="loading" @click="loadMore">加载更多</el-button>
        </div>
      </div>
    </template>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`告警 · ${detail?.alertname ?? ''}`" size="640px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="状态">
          <el-tag v-if="detail?.status === 'firing'" size="small" type="danger" effect="plain">firing</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">resolved</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="严重度">{{ detail?.severity ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="集群">{{ detail?.clusterName ?? detail?.clusterId ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="租户">{{ detail?.tenantName ?? '—（命名空间未分配给任何租户）' }}</el-descriptions-item>
        <el-descriptions-item label="命名空间">{{ detail?.namespace ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="来源">
          <template v-if="detail?.sources?.length">
            <div v-for="sv in detail.sources" :key="`${sv.cr}/${sv.group}`">
              <code class="res-name">{{ sv.cr }}</code><span class="muted"> / {{ sv.group }}</span>
            </div>
          </template>
          <span v-else class="muted" :title="detail ? sourceTitle(detail) : ''">—</span>
        </el-descriptions-item>
        <el-descriptions-item label="通知方式">
          <span v-if="detail?.notifyChannelName">{{ detail.notifyChannelName }}</span>
          <span v-else class="muted">—（默认渠道）</span>
        </el-descriptions-item>
        <el-descriptions-item label="投递">
          <el-tag v-if="detail?.deliveryStatus === 'SENT'" size="small" type="success" effect="plain">已发送</el-tag>
          <el-tag v-else-if="detail?.deliveryStatus === 'FAILED'" size="small" type="danger" effect="plain">失败</el-tag>
          <el-tag v-else-if="detail?.deliveryStatus === 'PENDING'" size="small" type="info" effect="plain">发送中</el-tag>
          <span v-else class="muted">—</span>
          <div v-if="detail?.deliveryError" class="muted">{{ detail.deliveryError }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ fmtDate(detail?.startsAt) }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ detail?.endsAt ? fmtDate(detail.endsAt) : '—' }}</el-descriptions-item>
        <el-descriptions-item label="收到时间">{{ fmtDate(detail?.receivedAt) }}</el-descriptions-item>
        <el-descriptions-item label="fingerprint">
          <code class="res-name">{{ detail?.fingerprint ?? '—' }}</code>
        </el-descriptions-item>
      </el-descriptions>

      <div class="section-title">标签 labels</div>
      <el-table v-if="detailLabels" :data="kvRows(detailLabels)" size="small" stripe>
        <el-table-column prop="k" label="键" width="220" />
        <el-table-column prop="v" label="值" />
      </el-table>
      <pre v-else-if="detail?.labels" class="raw">{{ detail.labels }}</pre>
      <div v-else class="muted">—</div>

      <div class="section-title">注解 annotations</div>
      <el-table v-if="detailAnnotations" :data="kvRows(detailAnnotations)" size="small" stripe>
        <el-table-column prop="k" label="键" width="220" />
        <el-table-column prop="v" label="值" />
      </el-table>
      <pre v-else-if="detail?.annotations" class="raw">{{ detail.annotations }}</pre>
      <div v-else class="muted">—</div>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-panel {
  padding: 12px 16px;
  margin-bottom: 12px;
}
.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.filter-row + .filter-row {
  margin-top: 10px;
}
.tip {
  font-size: 12px;
}
.sep {
  color: var(--text-3);
  margin: 0 4px;
}
.src-more {
  margin-left: 6px;
}
.table-panel {
  padding: 8px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.muted {
  color: var(--text-3);
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  margin: 14px 0 8px;
}
.more-row {
  display: flex;
  justify-content: center;
  padding: 12px 0;
}
.raw {
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
</style>
