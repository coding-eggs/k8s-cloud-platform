<script setup lang="ts">
/**
 * 流水线列表（**租户级** —— 跨集群/命名空间展示，不挂顶栏级联）。
 *
 * <p>流水线定义本身是命名空间级资源（集群 + 命名空间是它的归属），所以列表把这两项作为列标出；
 * 租户仍来自上下文（token / 代管轨），新建时在编辑器里选位置。
 *
 * <p>构建缓存、待重试工作区、CI 能力探测都是**命名空间级**的，2026-11-14 起挪到编辑器页；
 * 这里点「运行」时按该行位置现探一次能力，不可用就给出原因、不弹运行对话框。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { usePaletteAction } from '@/composables/usePaletteAction'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import { pipelineApi } from '@/api'
import type { Pipeline, PipelineCanvas, PipelineCacheStatus, PipelineRetryWorkspaces, PipelineSourceRef } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useApplicationContext } from '@/composables/useApplicationContext'
import PipelineRunDialog from '@/components/pipeline/PipelineRunDialog.vue'
import { fmtBytes, fmtDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const perm = usePermission()
// 租户取自上下文（token / 代管轨）；集群与命名空间不跟顶栏 —— 列表跨集群列出本租户全部流水线
const { tenantId, ensure } = useApplicationContext()

const rows = ref<Pipeline[]>([])
const loading = ref(false)
const keyword = ref('')
/** 首轮加载完成前不响应租户变化（onMounted 自己会按上下文加载一次），避免重复请求 */
const ready = ref(false)

/** 触发对话框：取消勾选 = 本次屏蔽该节点及其下游 */
const runDialogOpen = ref(false)
const runTarget = ref<Pipeline | null>(null)
const runCanvas = computed<PipelineCanvas>(() => {
  try {
    return JSON.parse(runTarget.value?.canvasJson ?? '{"nodes":[],"edges":[]}')
  } catch {
    return { nodes: [], edges: [] }
  }
})

const canManage = computed(() => perm.has(apiCodes.pipelineManage))
const canRun = computed(() => perm.has(apiCodes.pipelineRun))

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return k ? rows.value.filter((r) => r.name.toLowerCase().includes(k)) : rows.value
})

async function load() {
  loading.value = true
  try {
    // 不带集群/命名空间 = 本租户全部集群
    rows.value = (await pipelineApi.list(undefined, undefined, tenantId.value)) ?? []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
  // 两列命名空间级状态**不阻塞表格**：行先画出来，这两格各自填（见 loadNamespaceState）
  void loadNamespaceState()
}

onMounted(async () => {
  await ensure()
  await load()
  ready.value = true
})
watch(tenantId, () => {
  if (!ready.value) return
  void load()
})

/** 新建 = 只带租户进编辑器（位置在编辑器里选）；编辑 = 带上该行的位置 */
function toEditor(row?: Pipeline) {
  router.push({
    path: '/pipelines/editor',
    query: {
      id: row?.id,
      clusterId: row?.clusterId ?? '',
      namespace: row?.namespace ?? '',
      tenantId: tenantId.value ?? '',
    },
  })
}

function toRuns(row: Pipeline) {
  router.push({ path: '/pipelines/runs', query: { id: row.id } })
}

/** 运行前按该行的集群 + 命名空间现探 CI 能力（列表不再有页面级能力横幅） */
async function openRun(row: Pipeline) {
  if (row.clusterId && row.namespace) {
    try {
      const cap = await pipelineApi.capability(row.clusterId, tenantId.value, row.namespace)
      if (cap?.available === false) {
        ElMessage.warning(`这个集群现在跑不了 CI：${cap.reason ?? '未探测到原因'}`)
        return
      }
    } catch {
      // 探测失败按「不知道」放行 —— 真正的拦截在后端
    }
  }
  runTarget.value = row
  runDialogOpen.value = true
  void loadPreview(row)
}

// ---- 源码预览（触发前：这次会构建哪个 commit）—— 失败不挡运行 ----

const previewSources = ref<PipelineSourceRef[]>([])
const previewLoading = ref(false)
/** 「查看变更」那一次请求在飞（要下载整棵快照，比打开时那次慢得多） */
const previewDiffLoading = ref(false)
const previewError = ref<string | null>(null)

/**
 * @param withDiff 用户点了「查看变更」：额外浅取提交对象并算文件级变更。
 *                 失败只提示、**不抹掉已经显示的 sha** —— 变更清单是附加信息，不是运行的前提。
 *
 * <p>⚠️ 这一档会**重新解析一次引用**并整份替换结果，所以分支在两次请求之间前进了的话，
 * 行上的 sha 会跟着变。这是对的：显示什么就钉住什么，用户最后看到的那行才是要构建的提交。
 */
async function loadPreview(row: Pipeline, withDiff = false) {
  if (!row.id) return

  if (withDiff) {
    if (previewDiffLoading.value) return
    previewDiffLoading.value = true
    try {
      previewSources.value = (await pipelineApi.previewSource(row.id, tenantId.value, undefined, true)) ?? []
    } catch (e) {
      ElMessage.warning(`变更清单不可用：${(e as Error)?.message || '未知原因'}`)
    } finally {
      previewDiffLoading.value = false
    }
    return
  }

  previewSources.value = []
  previewError.value = null
  previewLoading.value = true
  try {
    previewSources.value = (await pipelineApi.previewSource(row.id, tenantId.value)) ?? []
  } catch (e) {
    // 拦截器已提示；这里只留一句话，运行按钮照常可用
    previewError.value = (e as Error)?.message || '无法解析仓库'
  } finally {
    previewLoading.value = false
  }
}

function requestDiff() {
  if (runTarget.value) void loadPreview(runTarget.value, true)
}

async function confirmRun(maskedNodeIds: string[], pinnedShas: Record<string, string>) {
  const row = runTarget.value
  if (!row?.id) return
  try {
    const pins = Object.keys(pinnedShas ?? {}).length ? pinnedShas : undefined
    const runName = await pipelineApi.run(row.id, tenantId.value, maskedNodeIds, pins)
    ElMessage.success(`已触发：${runName}`)
    runDialogOpen.value = false
    toRuns(row)
  } catch {
    /* 拦截器已提示 */
  }
}

// ---- 命名空间级资源（构建缓存 / 待重试工作区，两列）----

/**
 * 两列都是**命名空间级**的（与具体哪条流水线无关），所以按「集群 + 命名空间」去重后
 * 各探一次；同一命名空间下的多行共享同一份结果。
 *
 * <p>⚠️ **不阻塞列表渲染**：`/pipeline/capability` 要连集群探 Tekton 与 StorageClass，
 * 一个离线集群能挂几十秒 —— 先把表格画出来，这些列再各自填（探不到就是「—」）。
 */
interface NsState {
  cache: PipelineCacheStatus | null
  workspaces: PipelineRetryWorkspaces | null
  loading: boolean
  clearing: boolean
}

const nsStates = ref<Record<string, NsState>>({})

/** 一个命名空间一份状态；行与命名空间是多对一 */
function nsKey(clusterId?: string | null, namespace?: string | null): string {
  return `${clusterId ?? ''}|${namespace ?? ''}`
}

function nsOf(row: Pipeline): NsState | undefined {
  if (!row.clusterId || !row.namespace) return undefined
  return nsStates.value[nsKey(row.clusterId, row.namespace)]
}

function patchNs(key: string, patch: Partial<NsState>) {
  const cur = nsStates.value[key] ?? { cache: null, workspaces: null, loading: false, clearing: false }
  nsStates.value = { ...nsStates.value, [key]: { ...cur, ...patch } }
}

async function loadNamespaceState() {
  const pairs = new Map<string, { clusterId: string; namespace: string }>()
  for (const r of rows.value) {
    if (!r.clusterId || !r.namespace) continue
    pairs.set(nsKey(r.clusterId, r.namespace), { clusterId: r.clusterId, namespace: r.namespace })
  }
  const next: Record<string, NsState> = {}
  for (const key of pairs.keys()) {
    next[key] = { cache: null, workspaces: null, loading: true, clearing: false }
  }
  nsStates.value = next
  await Promise.all([...pairs.entries()].map(([key, p]) =>
    refreshNamespace(key, p.clusterId, p.namespace)))
}

async function refreshNamespace(key: string, clusterId: string, namespace: string) {
  patchNs(key, { loading: true })
  try {
    const cap = await pipelineApi.capability(clusterId, tenantId.value, namespace)
    patchNs(key, { cache: cap?.cache ?? null })
  } catch {
    // 探不到就说不知道（集群可能离线）—— 不是错误，这一格留「—」
  }
  try {
    const ws = await pipelineApi.retryWorkspaces(clusterId, namespace, tenantId.value)
    patchNs(key, { workspaces: ws })
  } catch {
    // 同上
  } finally {
    patchNs(key, { loading: false })
  }
}

// ---- 两列的文案（模板里不直接摸索引，省得每处都判空） ----

/** 构建缓存那格：`已启用 10Gi` / `未启用`；null = 没探到 */
function cacheText(row: Pipeline): string | null {
  const c = nsOf(row)?.cache
  if (!c) return null
  return c.ready ? `已启用 ${c.capacity}` : '未启用'
}

function cacheReady(row: Pipeline): boolean {
  return nsOf(row)?.cache?.ready === true
}

/** 卷名 / 前提 / 不可用原因都进 tooltip —— 表格里放不下，但排查时正需要 */
function cacheTip(row: Pipeline): string {
  const c = nsOf(row)?.cache
  if (!c) return '没探到（集群可能离线）'
  return [
    c.claimName ? `卷 ${c.claimName}` : null,
    `容量 ${c.capacity}`,
    c.prerequisite,
    c.reason,
    '要清空请到编辑器页',
  ].filter(Boolean).join('\n')
}

/** 待重试工作区那格：`2 块 / 3.5 GiB` / `无`；null = 没探到 */
function wsText(row: Pipeline): string | null {
  const w = nsOf(row)?.workspaces
  if (!w) return null
  return w.count > 0 ? `${w.count} 块 / ${fmtBytes(w.totalBytes)}` : '无'
}

function wsCount(row: Pipeline): number {
  return nsOf(row)?.workspaces?.count ?? 0
}

/** 明细放进 tooltip：哪几次运行还占着工作区、各多大、保留到什么时候 */
function wsTip(row: Pipeline): string {
  const w = nsOf(row)?.workspaces
  if (!w) return '没探到（集群可能离线）'
  if (!w.count) return '没有待重试的工作区（只有失败的运行会留下）'
  return w.items.map((i) =>
    `${i.runName ?? i.name} · ${fmtBytes(i.sizeBytes)}`
    + (i.retainUntil ? ` · 保留至 ${fmtDate(i.retainUntil)}` : '')).join('\n')
}

function nsLoading(row: Pipeline): boolean {
  return nsOf(row)?.loading === true
}

function nsClearing(row: Pipeline): boolean {
  return nsOf(row)?.clearing === true
}

/**
 * 清掉这个命名空间的待重试工作区。
 *
 * <p>**不可恢复**：清掉之后那些失败运行不能再从断点继续，只能整条重跑 —— 所以确认框
 * 把这句话原样说出来，而不是只问「确定吗」。
 */
async function clearWorkspaces(row: Pipeline) {
  if (!row.clusterId || !row.namespace) return
  const key = nsKey(row.clusterId, row.namespace)
  if (nsStates.value[key]?.clearing) return
  await ElMessageBox.confirm(
    `清理「${row.namespace}」里全部待重试工作区？清掉之后，那些运行不能再从断点继续，只能整条重跑。`,
    '确认清理',
    { type: 'warning', confirmButtonText: '清理', cancelButtonText: '算了' },
  )
  patchNs(key, { clearing: true })
  try {
    await pipelineApi.clearRetryWorkspaces(row.clusterId, row.namespace, tenantId.value)
    ElMessage.success('已清理')
    await refreshNamespace(key, row.clusterId, row.namespace)
  } catch {
    // 拦截器已提示
  } finally {
    patchNs(key, { clearing: false })
  }
}

async function remove(row: Pipeline) {
  await ElMessageBox.confirm(
    `删除流水线「${row.name}」？运行记录与日志**不会**跟着删（那是历史）。`,
    '确认',
    { type: 'warning' },
  )
  await pipelineApi.remove(row.id!)
  ElMessage.success('已删除')
  await load()
}

/** `?id=` 让运行历史页能跳回来（面包屑用） */
const focusId = computed(() => (route.query.id as string) || '')

// 命令面板（Ctrl+K）页内功能：路由 meta.actions 的落点，条件与页面上对应按钮的禁用条件一致
usePaletteAction({
  create: { run: () => toEditor(), ready: () => canManage.value },
})
</script>

<template>
  <div class="page">
    <div class="bar">
      <el-input v-model="keyword" placeholder="按名字筛选" clearable style="width: 220px" />
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button v-if="canManage" type="primary" @click="toEditor()">新建流水线</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="filtered"
      row-key="id"
      size="default"
      empty-text="还没有流水线"
    >
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <a class="name" :class="{ focus: row.id === focusId }" @click="toEditor(row)">{{ row.name }}</a>
        </template>
      </el-table-column>
      <!-- 列表按租户跨集群：位置作为列标出（集群名拿不到时退回 id） -->
      <el-table-column label="集群" width="160">
        <template #default="{ row }">{{ row.clusterName || row.clusterId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="namespace" label="命名空间" width="150" />
      <!-- 命名空间级的两块资源：跟着「集群 + 命名空间」走，同一命名空间的多行看到同一份。
           探测在表格画出来之后才跑（见 loadNamespaceState），所以这里会有「探测中…」 -->
      <el-table-column label="构建缓存" width="130">
        <template #default="{ row }">
          <el-tooltip v-if="cacheText(row)" :content="cacheTip(row)" placement="top" raw-content>
            <el-tag :type="cacheReady(row) ? 'success' : 'info'" size="small">
              {{ cacheText(row) }}
            </el-tag>
          </el-tooltip>
          <span v-else class="dim-cell">{{ nsLoading(row) ? '探测中…' : '—' }}</span>
        </template>
      </el-table-column>
      <!-- 待重试工作区是**要经常清**的那一个：量少但会攒，所以清除按钮直接放在格子里 -->
      <el-table-column label="待重试工作区" width="200">
        <template #default="{ row }">
          <span v-if="wsText(row)" class="ws-cell" :title="wsTip(row)">
            <span :class="{ dim: wsCount(row) === 0 }">{{ wsText(row) }}</span>
            <el-button
              v-if="canManage && wsCount(row) > 0"
              link
              type="warning"
              size="small"
              :loading="nsClearing(row)"
              @click="clearWorkspaces(row)"
            >
              清除
            </el-button>
          </span>
          <span v-else class="dim-cell">{{ nsLoading(row) ? '探测中…' : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="createdBy" label="创建人" width="140" />
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ row.updatedAt ?? row.createdAt ?? '—' }}</template>
      </el-table-column>
      <!-- 行操作摊开成行内按钮（与运行历史页同一套，2026-11-04）：
           不再收进下拉；「运行」在点的时候按该行位置现探能力 -->
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="toEditor(row)">编辑</el-button>
          <el-button link type="primary" @click="toRuns(row)">运行历史</el-button>
          <el-button v-if="canRun" link type="primary" title="运行" @click="openRun(row)">
            <el-icon :size="18"><VideoPlay /></el-icon>
          </el-button>
          <el-button v-if="canManage" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <PipelineRunDialog
      v-model="runDialogOpen"
      :nodes="runCanvas.nodes"
      :edges="runCanvas.edges"
      :sources="previewSources"
      :preview-loading="previewLoading"
      :preview-error="previewError"
      :diff-loading="previewDiffLoading"
      :title="`触发运行 · ${runTarget?.name ?? ''}`"
      @confirm="confirmRun"
      @request-diff="requestDiff"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.bar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.spacer {
  flex: 1;
}
.name {
  color: var(--el-color-primary);
  cursor: pointer;
}
.name.focus {
  font-weight: 600;
}

/* ---- 命名空间级的两列 ---- */
.dim-cell {
  font-size: 12px;
  color: var(--text-3);
}
.ws-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.dim {
  color: var(--text-3);
}
</style>
