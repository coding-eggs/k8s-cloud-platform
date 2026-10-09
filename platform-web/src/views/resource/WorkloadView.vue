<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workloadApi, hpaApi, gatewayApi } from '@/api'
import type { K8sGateway, K8sWorkload, K8sHpa } from '@/types'
import type { WorkloadKind } from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { isWaypointGateway, LABEL_DATAPLANE_MODE, LABEL_USE_WAYPOINT, USE_WAYPOINT_NONE } from '@/utils/waypoint'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import { fmtAge, fmtDate, workloadStatus } from '@/utils/format'
import { Refresh, MoreFilled, Search } from '@element-plus/icons-vue'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()
const perm = usePermission()

const loading = ref(false)
const list = ref<K8sWorkload[]>([])
/** canonical 键（小写 kind/name）→ 绑定的 HPA 名 */
const hpaBound = ref<Map<string, string>>(new Map())
/**客户端类型过滤：all / deployment / statefulset / daemonset */
const kindFilter = ref('all')
/** 名称模糊搜索（前端过滤） */
const keyword = ref('')

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

/** canonical 键：小写 kind/name —— 与 HpaEditorView 同一全局约定 */
function canonicalKey(kind: string | null | undefined, name: string | null | undefined): string {
  return `${(kind ?? '').toLowerCase()}/${name ?? ''}`
}

/** 该行工作负载绑定的 HPA 名（未绑定 → undefined） */
function hpaNameOf(row: K8sWorkload): string | undefined {
  return hpaBound.value.get(canonicalKey(row.kind, row.name))
}

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    // 终审 Important#2：badge 是次要数据——hpaApi.list 失败不得拖垮主列表（.catch 降级为空集，
    // 仅失去 HPA 标记与「添加 HPA」显隐，工作负载照常渲染）
    const [ws, hs] = await Promise.all([
      workloadApi.list(ctxParams.value),
      hpaApi.list(ctxParams.value).catch(() => [] as K8sHpa[]),
    ])
    list.value = ws
    hpaBound.value = new Map(hs.map((h) => [canonicalKey(h.scaleTargetRef?.kind, h.scaleTargetRef?.name), h.name] as const))
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

// ---------- 暂停/恢复更新（仅 Deployment） ----------
async function togglePause(row: K8sWorkload, paused: boolean): Promise<void> {
  try {
    await workloadApi.pause(row.name, ctxParams.value, paused)
    ElMessage.success(paused ? '已暂停更新' : '已恢复更新')
    await refresh()
  } catch {
    /* 拦截器已提示 */
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

// ---------- 服务网格开关（B3 §11.4：行内两个显式开关，不入下拉菜单） ----------
/**
 * 三态 label 与两态开关的对应（回显）：
 * - `ambient` → 开关开，文字「纳入」
 * - `none` → 开关关，文字「排除」
 * - 无标签 → 开关关，文字「跟随」（跟随命名空间/集群默认）
 * 开=写 ambient；关=写 none（<b>显式排除</b>，不是"跟随"）——回到「跟随」要在编辑器里选，
 * 这样列表上的"关"只有一个含义，不会因为命名空间后来开了 ambient 又被悄悄纳管。
 */
const mesh = useMeshStatus(computed(() => state.clusterId ?? null))
const meshAvailable = computed(() => mesh.hasIstio.value)
const canToggleMesh = computed(() => perm.has(apiCodes.workloadUpdate))

/** 本命名空间的 waypoint 候选（per-ns 至多一个，故常态 0/1 个） */
const waypointOptions = ref<string[]>([])
async function loadWaypoints(): Promise<void> {
  waypointOptions.value = []
  const { tenantId, clusterId, namespace } = state
  if (!tenantId || !clusterId || !namespace || !meshAvailable.value) return
  if (!perm.has(apiCodes.gatewayList)) return
  try {
    waypointOptions.value = ((await gatewayApi.list({ tenantId, clusterId, namespace })) ?? [])
      .filter((g: K8sGateway) => isWaypointGateway(g)).map((g) => g.name).sort()
  } catch {
    waypointOptions.value = [] // 未装 Gateway API（CRD 404）等：拦截器已提示，降级为空
  }
}
watch([() => state.clusterId, () => state.namespace, () => state.tenantId, meshAvailable],
  () => { void loadWaypoints() }, { immediate: true })

function ambientLabel(row: K8sWorkload): string {
  return row.podTemplate?.labels?.[LABEL_DATAPLANE_MODE] ?? ''
}
function waypointLabel(row: K8sWorkload): string {
  return row.podTemplate?.labels?.[LABEL_USE_WAYPOINT] ?? ''
}

/** 菜单分组标题里的「当前是什么」：两态开关表达不了三态，改由标题把三态说全 */
function ambientStateText(row: K8sWorkload): string {
  const a = ambientLabel(row)
  return a === 'ambient' ? '已纳入' : a === 'none' ? '已排除（none）' : '跟随命名空间'
}
function l7StateText(row: K8sWorkload): string {
  const w = waypointLabel(row)
  if (!w) return '跟随命名空间'
  return w === USE_WAYPOINT_NONE ? '不使用（none）' : `waypoint「${w}」`
}

/**
 * 可切换到的 waypoint（当前值不出现在列表里 —— 已经是它了）。
 * 当前值可能是平台外建的、不在候选里，故并进候选，否则"当前是什么"在菜单里就看不出来了。
 */
function waypointAlternatives(row: K8sWorkload): string[] {
  const cur = waypointLabel(row)
  const list = [...waypointOptions.value]
  if (cur && cur !== USE_WAYPOINT_NONE && !list.includes(cur)) list.push(cur)
  return list.filter((w) => w !== cur).sort()
}

/** 发一次 mesh-toggle：两个字段各自独立三态（不传=不动、空串=移除、有值=覆写），故只带被选的那一个 */
async function meshToggle(row: K8sWorkload, body: { dataplaneMode?: string; useWaypoint?: string }, tip: string): Promise<void> {
  try {
    await workloadApi.meshToggle(row.name, { ...ctxParams.value, kind: row.kind, ...body })
    ElMessage.success(tip)
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

/** 三个目标态各自的确认文案（都会改 pod template → 触发滚动更新，所以都要问一次） */
const AMBIENT_CONFIRM: Record<string, string> = {
  ambient: '纳入 ambient 网格？（写 istio.io/dataplane-mode: ambient）',
  none: '排除出 mesh？（写 istio.io/dataplane-mode: none —— 显式排除，不是"跟随"）',
  '': '删除该标签、回到跟随命名空间？（移除 istio.io/dataplane-mode）',
}

async function setAmbient(row: K8sWorkload, value: string): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `把「${row.name}」${AMBIENT_CONFIRM[value] ?? value}`,
      '提示',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
  } catch { return }
  await meshToggle(row, { dataplaneMode: value }, '已提交，工作负载将滚动更新')
}

async function setL7(row: K8sWorkload, value: string): Promise<void> {
  const ask = value === ''
    ? `删除「${row.name}」的 use-waypoint 标签、回到跟随命名空间？`
    : value === USE_WAYPOINT_NONE
      ? `让「${row.name}」显式不使用 waypoint？（写 istio.io/use-waypoint: none，压过命名空间级设置）`
      : `让「${row.name}」的七层流量经过 waypoint「${value}」？（写 istio.io/use-waypoint: ${value}）`
  try {
    await ElMessageBox.confirm(ask, '提示', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' })
  } catch { return }
  await meshToggle(row, { useWaypoint: value }, '已提交，工作负载将滚动更新')
}

/**
 * 该命名空间还没有 waypoint Gateway 时，不在菜单里瞎写一个名字 ——
 * 指到不存在的 waypoint，istio 会**静默放行**（L7 策略不生效却没有任何报错）。故引导去创建。
 */
async function goCreateWaypoint(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `命名空间「${state.namespace}」还没有 waypoint Gateway。L7 策略需要一个 waypoint 才有地方执行；`
      + `没有它，istio 会直接放行流量、策略静默不生效。是否现在去创建？`,
      '先创建 waypoint Gateway',
      { type: 'warning', confirmButtonText: '去创建', cancelButtonText: '取消' },
    )
  } catch { return }
  router.push({ name: 'gateway-editor', query: { waypoint: '1' } })
}

// ---------- 行操作下拉：YAML / 编辑 / 伸缩 / 删除 / 服务网格 ----------
function onRowCommand(cmd: string, row: K8sWorkload): void {
  if (cmd.startsWith('ambient-')) {
    void setAmbient(row, cmd === 'ambient-on' ? 'ambient' : cmd === 'ambient-none' ? 'none' : '')
    return
  }
  if (cmd.startsWith('l7-')) {
    if (cmd === 'l7-create') { void goCreateWaypoint(); return }
    if (cmd === 'l7-none') { void setL7(row, USE_WAYPOINT_NONE); return }
    if (cmd === 'l7-follow') { void setL7(row, ''); return }
    void setL7(row, cmd.slice('l7-use:'.length))   // l7-use:<waypoint 名>
    return
  }
  switch (cmd) {
    case 'yaml': openYaml(row); break
    case 'edit': if (!isOpManaged(row)) goEditor(row.name); break
    case 'scale': openScale(row); break
    case 'addHpa':
      // 传小写原值 kind，编辑器 canonicalKey 统一小写 + KIND_CAP 还原大写
      router.push({ name: 'hpa-editor', query: { targetKind: row.kind, targetName: row.name } })
      break
    case 'pause': togglePause(row, true); break
    case 'resume': togglePause(row, false); break
    case 'delete': onDelete(row); break
  }
}

// ---------- 上下文联动：顶栏 chip 变化或 ready false→true 时刷新（恢复的持久化选择字段不变、只有 ready 翻真，必须 watch） ----------
onMounted(() => {
  void load()
  void refresh()
})
watch(
  [() => [state.tenantId, state.clusterId, state.namespace], ready],
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
            <el-tag v-if="row.kind === 'deployment' && row.paused" type="info" size="small" effect="plain" class="op-tag">已暂停</el-tag>
            <el-tooltip v-if="hpaNameOf(row)" :content="`已绑定 HPA「${hpaNameOf(row)}」`" placement="top">
              <el-tag type="success" size="small" effect="plain" class="op-tag">HPA</el-tag>
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
        <!-- 服务网格（B3 §11.4）：两个显式开关，放在行操作栏（与「⋯」并列），**不入**行操作下拉。
             「关」写的是 none（显式排除），不是"跟随" —— 回去跟随要在编辑器里选，这样列表上的关只有一个含义。
             三态（跟随 / 纳入 / 排除、L7 的 waypoint 名）挤在一列里放不下完整措辞，故把完整状态挂 tooltip。 -->
        <el-table-column label="操作" width="64" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: string) => onRowCommand(cmd, row)">
              <el-button link type="primary" :icon="MoreFilled" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit" :disabled="isOpManaged(row)">编辑</el-dropdown-item>
                  <el-dropdown-item v-if="row.kind === 'deployment'" :command="row.paused ? 'resume' : 'pause'">
                    {{ row.paused ? '恢复更新' : '暂停更新' }}
                  </el-dropdown-item>
                  <el-dropdown-item command="scale">伸缩</el-dropdown-item>
                  <el-dropdown-item v-if="row.kind !== 'daemonset' && !hpaNameOf(row)" command="addHpa">添加 HPA</el-dropdown-item>
                  <el-dropdown-item command="yaml">Yaml</el-dropdown-item>

                  <!-- 服务网格（B3 §11.4）：两态开关表达不了三态（跟随 / 纳入 / 排除），故用带当前态的菜单项。
                       禁用项只作分组标题，把"当前是什么"写在标题里；下面是可点的目标态（当前态不出现在列表里）。
                       「关」一律写 none（显式排除），回到跟随是单独一项 —— 两者的区别对使用者是有意义的。 -->
                  <template v-if="meshAvailable">
                    <el-dropdown-item divided disabled>ambient 流量 · {{ ambientStateText(row) }}</el-dropdown-item>
                    <el-dropdown-item v-if="ambientLabel(row) !== 'ambient'" command="ambient-on" :disabled="!canToggleMesh">纳入 ambient</el-dropdown-item>
                    <el-dropdown-item v-if="ambientLabel(row) !== 'none'" command="ambient-none" :disabled="!canToggleMesh">排除出 mesh（none）</el-dropdown-item>
                    <el-dropdown-item v-if="ambientLabel(row)" command="ambient-follow" :disabled="!canToggleMesh">回到跟随命名空间</el-dropdown-item>

                    <el-dropdown-item divided disabled>L7 流量 · {{ l7StateText(row) }}</el-dropdown-item>
                    <el-dropdown-item
                      v-for="w in waypointAlternatives(row)" :key="w"
                      :command="`l7-use:${w}`" :disabled="!canToggleMesh"
                    >经过 waypoint「{{ w }}」</el-dropdown-item>
                    <el-dropdown-item v-if="!waypointOptions.length && !waypointLabel(row)" command="l7-create" :disabled="!canToggleMesh">创建 waypoint Gateway…</el-dropdown-item>
                    <el-dropdown-item v-if="!waypointOptions.length && waypointLabel(row)" disabled>该命名空间暂无 waypoint Gateway（当前值可能已被删除）</el-dropdown-item>
                    <el-dropdown-item v-if="waypointLabel(row) && waypointLabel(row) !== USE_WAYPOINT_NONE" command="l7-none" :disabled="!canToggleMesh">不使用 waypoint（none）</el-dropdown-item>
                    <el-dropdown-item v-if="waypointLabel(row)" command="l7-follow" :disabled="!canToggleMesh">回到跟随命名空间</el-dropdown-item>
                  </template>

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
