<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workloadApi } from '@/api'
import type { K8sWorkload } from '@/types'
import type { WorkloadKind } from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import { fmtAge, fmtDate, workloadStatus } from '@/utils/format'
import { Refresh, MoreFilled, Search } from '@element-plus/icons-vue'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()

const loading = ref(false)
const list = ref<K8sWorkload[]>([])
/**客户端类型过滤：all / deployment / statefulset / daemonset */
const kindFilter = ref('all')
/** 名称模糊搜索（前端过滤） */
const keyword = ref('')

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await workloadApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

const KINDS = [
  { value: 'all', label: '全部' },
  { value: 'deployment', label: 'Deployment' },
  { value: 'statefulset', label: 'StatefulSet' },
  { value: 'daemonset', label: 'DaemonSet' },
]

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return list.value.filter(
    (w) =>
      (kindFilter.value === 'all' || w.kind === kindFilter.value) &&
      (!kw || (w.name ?? '').toLowerCase().includes(kw)),
  )
})

function kindLabel(kind?: string | null): string {
  switch (kind) {
    case 'deployment': return 'Deployment'
    case 'statefulset': return 'StatefulSet'
    case 'daemonset': return 'DaemonSet'
    default: return kind ?? '—'
  }
}

function tagLabel(kind?: string | null): string {
  switch (kind) {
    case 'deployment': return 'plain'
    case 'statefulset': return 'success'
    case 'daemonset': return 'warning'
    default: return kind ?? 'plain'
  }
}

function replicasText(row: K8sWorkload): string {
  if (row.kind === 'daemonset') {
    return row.readyReplicas != null ? `${row.readyReplicas} 就绪` : '—'
  }
  const total = row.replicas ?? 0
  const readyCount = row.readyReplicas ?? 0
  return `${readyCount}/${total}`
}

/** 是否由 Operator/控制器管理（ownerReferences 非空）→ 禁用编辑 */
function isOpManaged(row: K8sWorkload): boolean {
  return (row.ownerReferences?.length ?? 0) > 0
}

/** 对外暴露端口行：port:nodePort（跨所有绑定的 NodePort/LB Service 拍平）；无则空数组 */
function exposeLines(row: K8sWorkload) {
  const out = []
  for (const svc of row.exposedServices ?? []) {
    for (const p of svc.ports ?? []) {
      out.push({"port": p.port, "nodePort": p.nodePort})
    }
  }
  return out
}

// ---------- 跳转：编辑器（新建 / 编辑） / 详情页 ----------
function goEditor(name: string | null): void {
  router.push(name ? `/resources/workloads/editor?name=${encodeURIComponent(name)}` : '/resources/workloads/editor')
}

function goDetail(name: string): void {
  router.push(`/resources/workloads/detail?name=${encodeURIComponent(name)}`)
}

// ---------- 伸缩：基础表单仅伸缩副本数（DaemonSet 无副本概念） ----------
const scaleVisible = ref(false)
const scaling = ref(false)
const scaleForm = reactive({ row: null as K8sWorkload | null, replicas: 1 })

function openScale(row: K8sWorkload): void {
  if (row.kind === 'daemonset') {
    ElMessage.info('DaemonSet 无副本数概念，每个节点一个 Pod')
    return
  }
  scaleForm.row = row
  scaleForm.replicas = row.replicas ?? 1
  scaleVisible.value = true
}

async function submitScale(): Promise<void> {
  const row = scaleForm.row
  if (!row) return
  scaling.value = true
  try {
    await workloadApi.update(row.name, { tenantId: state.tenantId!, clusterId: state.clusterId! }, {
      kind: row.kind as WorkloadKind,
      name: row.name,
      namespace: state.namespace!,
      replicas: scaleForm.replicas,
    })
    ElMessage.success(`已伸缩到 ${scaleForm.replicas} 副本`)
    scaleVisible.value = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    scaling.value = false
  }
}

// ---------- 删除（跨 kind 查找） ----------
async function onDelete(row: K8sWorkload): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 ${kindLabel(row.kind)}「${row.name}」？其 Pod 会被一并回收。`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await workloadApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- YAML 只读抽屉（替代原「查看」概览） ----------
const yamlVisible = ref(false)
const yamlRow = ref<K8sWorkload | null>(null)
const yamlText = ref('')

async function openYaml(row: K8sWorkload): Promise<void> {
  yamlRow.value = row
  yamlText.value = ''
  yamlVisible.value = true
  try {
    yamlText.value = await workloadApi.getYaml(row.name, ctxParams.value)
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 行操作下拉：YAML / 编辑 / 伸缩 / 删除 ----------
function onRowCommand(cmd: string, row: K8sWorkload): void {
  switch (cmd) {
    case 'yaml': openYaml(row); break
    case 'edit': if (!isOpManaged(row)) goEditor(row.name); break
    case 'scale': openScale(row); break
    case 'delete': onDelete(row); break
  }
}

// ---------- 上下文联动：顶栏 chip 变化时刷新 ----------
onMounted(() => {
  void load()
  void refresh()
})
watch(
  () => [state.tenantId, state.clusterId, state.namespace],
  () => {
    if (ready.value) void refresh()
  },
)

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader title="工作负载" >
      <el-button type="primary" :disabled="!ready" @click="goEditor(null)">创建工作负载</el-button>
    </PageHeader>

    <div v-if="ready" class="panel table-panel">
      <div class="field-pair">
        <div class="kind-filter ">
          <el-radio-group v-model="kindFilter" >
            <el-radio-button size="small" v-for="k in KINDS" :key="k.value" :value="k.value">{{ k.label }}</el-radio-button>
          </el-radio-group>
        </div>
        <div class="toolbar-right">
          <el-input v-model="keyword" placeholder="按名称搜索…" clearable :prefix-icon="Search" class="search-input" />
          <el-button :icon="Refresh" circle :disabled="!ready" @click="refresh" />
        </div>
      </div>

      <EmptyState
        v-if="filtered.length === 0 && !loading"
        title="该命名空间下暂无工作负载"
        description="点击右上「创建工作负载」新建 Deployment / StatefulSet / DaemonSet。"
      />
      <el-table v-else v-loading="loading || !ready" :data="filtered" stripe>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag  :effect="tagLabel(row.kind)">{{ kindLabel(row.kind) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名称" width="350">
          <template #default="{ row }">
            <code class="res-name name-link" @click="goDetail(row.name)">{{ row.name }}</code>
            <el-tooltip v-if="isOpManaged(row)" content="由 Operator 管理，不可编辑" placement="top">
              <el-tag type="warning" size="small" effect="plain" class="op-tag">op</el-tag>
            </el-tooltip>
            <div v-if="row.exposedServices.length" class="expose-ports">
              <div class="expose-line">
                <span v-for="(line, i) in exposeLines(row)" :key="i">
                  {{ line.port }}:<span class="name-link">{{line.nodePort}}</span>
                </span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="镜像" min-width="500">
          <template #default="{ row }">
            <code v-for="(img, i) in row.images ?? []" :key="i" class="res-name img-cell">{{ img }}</code>
          </template>
        </el-table-column>
        <el-table-column label="副本" width="100">
          <template #default="{ row }">{{ replicasText(row) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <StatusBadgeTip :label="workloadStatus(row.kind, row.replicas, row.readyReplicas).label" :type="workloadStatus(row.kind, row.replicas, row.readyReplicas).type" :reason="row.statusReason" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            <div class="age-cell">
              <div>{{ fmtDate(row.creationTime) }}</div>
              <div class="muted">{{ fmtAge(row.creationTime) }}</div>
            </div>

          </template>
        </el-table-column>
        <el-table-column  width="64" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: string) => onRowCommand(cmd, row)">
              <el-button link type="primary" :icon="MoreFilled" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit" :disabled="isOpManaged(row)">编辑</el-dropdown-item>
                  <el-dropdown-item command="scale">伸缩</el-dropdown-item>
                  <el-dropdown-item command="yaml">Yaml</el-dropdown-item>
                  <el-dropdown-item divided style="color: var(--el-color-danger)" command="delete">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 伸缩（基础表单仅支持副本数） -->
    <el-dialog v-model="scaleVisible" :title="`伸缩 · ${scaleForm.row?.name ?? ''}`" width="420px">
      <el-form label-width="80px">
        <el-form-item label="副本数">
          <el-input-number v-model="scaleForm.replicas" :min="0" :max="64" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scaleVisible = false">取消</el-button>
        <el-button type="primary" :loading="scaling" @click="submitScale">确定</el-button>
      </template>
    </el-dialog>

    <!-- YAML 只读 -->
    <el-drawer v-model="yamlVisible" :title="`YAML · ${yamlRow?.name ?? ''}`" size="640px">
      <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
      <div v-else class="muted">加载中…</div>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.kind-filter {
  padding: 0.25rem;
  padding-top: 0;
  min-width: 300px;
}
.row-caret {
  margin-left: 2px;
  vertical-align: -2px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 14px;
  color: var(--text-1);
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
.op-tag {
  margin-left: 6px;
  vertical-align: middle;
}
.expose-ports {
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.expose-line {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.4;
}
.img-cell {
  display: inline-block;
}
.muted {
  color: var(--text-3);
}
.age-cell {
  line-height: 1.5;
}
.field-pair {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.search-input {
  width: 240px;
}
.yaml-block {
  margin: 0;
  padding: 14px;
  border-radius: 8px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
}
</style>
