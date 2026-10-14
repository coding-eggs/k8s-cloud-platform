<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workloadApi, hpaApi, gatewayApi } from '@/api'
import type { K8sGateway, K8sWorkload, K8sHpa, WaypointRef } from '@/types'
import type { WorkloadKind } from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { useImageSuggest } from '@/composables/useImageSuggest'
import { nodeHost, useNodeAddresses } from '@/composables/useNodeAddresses'
import {
  canHandleWorkload,
  isWaypointGateway,
  LABEL_DATAPLANE_MODE,
  LABEL_USE_WAYPOINT,
  toWaypointRef,
  USE_WAYPOINT_NONE,
} from '@/utils/waypoint'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import WorkloadRollbackDialog from '@/components/workload/WorkloadRollbackDialog.vue'
import { fmtAge, fmtDate, workloadStatus } from '@/utils/format'
import { Refresh, MoreFilled, Search, EditPen, TopRight } from '@element-plus/icons-vue'
import YamlBlock from '@/components/YamlBlock.vue'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()
const perm = usePermission()
/** 镜像候选补全（「更新镜像」弹窗的输入框） */
const { fetchImageSuggestions } = useImageSuggest()

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
    void addr.load(state.clusterId)   // 节点地址（NodePort 直连用）：并行拉，不 await（拿不到就是没有链接）
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

// ---------- 点 nodePort 直开：host = 集群里任一台可用节点的 IP ----------
/**
 * 节点地址（窄投影：name + 内网/外网 IP + Ready），按集群缓存。拿不到（无权限/集群不可达）时
 * `defaultHost` 为空 → nodePort 退化成今天的纯文本，不做"点了没反应"的假链接。
 */
const addr = useNodeAddresses()
/** 被选作直连 host 的那个节点（tooltip 上写清楚"从哪台机器进"，用户排障时第一眼要看的就是它） */
const hostNode = computed(() => addr.nodes.find((n) => nodeHost(n) === addr.defaultHost) ?? null)
/** 这个 nodePort 能不能变成链接 */
function linkable(nodePort?: number | null): boolean {
  return !!nodePort && addr.canUse && !!addr.defaultHost
}

/**
 * 打开 http://<节点IP>:<nodePort>。host 取第一个 Ready 且有 IP 的节点（外网 IP 优先）——
 * NodePort 在每台节点上都生效，随便挑一台即可；挑的是 Ready 的，避免默认落到一台已经挂了的机器上。
 */
function openNodePort(nodePort?: number | null): void {
  if (!linkable(nodePort)) return
  window.open(`http://${addr.defaultHost}:${nodePort}`, '_blank', 'noopener')
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

// ---------- 镜像就地改（直接发版） ----------
/** 镜像列的容器行：容器名 + 镜像。podTemplate 缺失时退化成扁平的 images（只有值，不可编辑） */
function containerRows(row: K8sWorkload): { name: string | null; image: string }[] {
  const cs = row.podTemplate?.spec?.containers ?? []
  if (cs.length) return cs.map((c) => ({ name: c.name, image: c.image ?? '' }))
  return (row.images ?? []).map((img) => ({ name: null, image: img }))
}

const imgVisible = ref(false)
const imgSaving = ref(false)
const imgForm = reactive({ row: null as K8sWorkload | null, containerName: '', image: '' })

function openImageEdit(row: K8sWorkload, containerName: string, image: string): void {
  imgForm.row = row
  imgForm.containerName = containerName
  imgForm.image = image
  imgVisible.value = true
}

/**
 * 提交镜像：只改这一个容器的 image（后端读回线上对象改完再写回，未建模字段不会因此丢），
 * 因为改的是 pod template → 控制器会滚一次新版本。
 */
async function submitImage(): Promise<void> {
  const row = imgForm.row
  const image = imgForm.image.trim()
  if (!row || !imgForm.containerName) return
  if (!image) {
    ElMessage.warning('镜像不能为空')
    return
  }
  imgSaving.value = true
  try {
    await workloadApi.updateImage(row.name, { ...ctxParams.value, containerName: imgForm.containerName, image })
    ElMessage.success(`已提交 ${imgForm.containerName} → ${image}，工作负载将滚动更新`)
    imgVisible.value = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    imgSaving.value = false
  }
}

// ---------- 回滚（历史版本由后端按 kind 归一：Deployment→ReplicaSet、STS/DS→ControllerRevision） ----------
const rollbackVisible = ref(false)
const rollbackRow = ref<K8sWorkload | null>(null)

function openRollback(row: K8sWorkload): void {
  rollbackRow.value = row
  rollbackVisible.value = true
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
/** 工作负载写权限：编辑/伸缩/网格开关/改镜像/回滚共用同一个动词码 */
const canWrite = computed(() => perm.has(apiCodes.workloadUpdate))

/**
 * 网格菜单项是否可点：**与「编辑」同口径** —— 由 Operator/Helm 管理的对象（有 ownerReferences）不写。
 * 改它的 pod template 标签，上游下一次 reconcile 就会改回去（用户看到"开了又自己关了"），
 * 中间还可能因字段所有权打架白滚一次 Pod。
 */
function meshEditable(row: K8sWorkload): boolean {
  return canWrite.value && !isOpManaged(row)
}

/** 本命名空间的 waypoint 候选（名字 + 处理哪类流量；数量不设上限） */
const waypointOptions = ref<WaypointRef[]>([])
async function loadWaypoints(): Promise<void> {
  waypointOptions.value = []
  const { tenantId, clusterId, namespace } = state
  if (!tenantId || !clusterId || !namespace || !meshAvailable.value) return
  if (!perm.has(apiCodes.gatewayList)) return
  try {
    waypointOptions.value = ((await gatewayApi.list({ tenantId, clusterId, namespace })) ?? [])
      .filter((g: K8sGateway) => isWaypointGateway(g))
      .map((g) => toWaypointRef(g))
      .sort((a, b) => a.name.localeCompare(b.name))
  } catch {
    waypointOptions.value = [] // 未装 Gateway API（CRD 404）等：拦截器已提示，降级为空
  }
}
watch([() => state.clusterId, () => state.namespace, () => state.tenantId, meshAvailable],
  () => { void loadWaypoints() }, { immediate: true })

/** 能承接 Pod/VM 直连流量的候选（workload / all）。Pod 上的 use-waypoint 只对这个方向有效 */
const workloadWaypoints = computed(() => waypointOptions.value.filter((r) => canHandleWorkload(r.waypointFor)))
/** 有没有任何可用候选 —— 用来在"一个都不能选"时给一句说明，而不是让 L7 分组空着 */
const hasUsableWaypoint = computed(() => workloadWaypoints.value.length > 0)

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
/** L7 当前态；顺带把"当前值不会生效"（类型不匹配 / 已删除）直接写在标题里 —— 这是最容易被漏看的一格 */
function l7StateText(row: K8sWorkload): string {
  const w = waypointLabel(row)
  if (!w) return '跟随命名空间'
  if (w === USE_WAYPOINT_NONE) return '不使用（none）'
  const ref = waypointOptions.value.find((r) => r.name === w)
  if (!ref) return `waypoint「${w}」· 已不存在，不会生效`
  return canHandleWorkload(ref.waypointFor)
    ? `waypoint「${w}」`
    : `waypoint「${w}」· ${ref.waypointFor} 型不处理 Pod 流量，不会生效`
}

/** 可切换到的 waypoint：只剩能处理 Pod 流量的，且排除当前值（已经是它了） */
function waypointAlternatives(row: K8sWorkload): WaypointRef[] {
  const cur = waypointLabel(row)
  return workloadWaypoints.value.filter((r) => r.name !== cur)
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
    case 'rollback': openRollback(row); break
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
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag  :effect="tagLabel(row.kind)">{{ kindLabel(row.kind) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名称" width="320">
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
                  {{ line.port }}:
                  <!-- 点 nodePort 直连：host = 集群里第一个 Ready 且有 IP 的节点（NodePort 每台节点都生效）。
                       拿不到节点地址（无权限/集群不可达）时保持纯文本，不做"点了没反应"的假链接。 -->
                  <el-tooltip v-if="linkable(line.nodePort)"
                              :content="`在新窗口打开 http://${addr.defaultHost}:${line.nodePort}（节点 ${hostNode?.name ?? ''}）`"
                              placement="top">
                    <span class="name-link np-link" @click="openNodePort(line.nodePort)">
                      {{ line.nodePort }}<el-icon class="np-icon"><TopRight /></el-icon>
                    </span>
                  </el-tooltip>
                  <span v-else>{{ line.nodePort }}</span>
                </span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="镜像" min-width="500">
          <template #default="{ row }">
            <div v-for="(c, i) in containerRows(row)" :key="i" class="img-row">
              <span v-if="c.name" class="img-container">{{ c.name }}</span>
              <code class="res-name img-cell">{{ c.image }}</code>
              <!-- 改这一个容器的镜像（直接发版）。op 管理的不给点：改了上游下一次 reconcile 就改回去 -->
              <el-tooltip v-if="c.name" content="改这个容器的镜像（触发滚动更新）" placement="top">
                <el-icon class="img-edit" :class="{ disabled: !canWrite || isOpManaged(row) }"
                         @click="canWrite && !isOpManaged(row) && openImageEdit(row, c.name!, c.image)">
                  <EditPen />
                </el-icon>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="副本" width="70">
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
                  <!-- 回滚：选一个历史版本，把它的 pod template 覆盖回工作负载本身。op 管理的不给（同上） -->
                  <el-dropdown-item command="rollback" :disabled="!canWrite || isOpManaged(row)">回滚</el-dropdown-item>
                  <el-dropdown-item v-if="row.kind !== 'daemonset' && !hpaNameOf(row)" command="addHpa">添加 HPA</el-dropdown-item>
                  <el-dropdown-item command="yaml">Yaml</el-dropdown-item>

                  <!-- 服务网格（B3 §11.4）：两态开关表达不了三态（跟随 / 纳入 / 排除），故用带当前态的菜单项。
                       禁用项只作分组标题，把"当前是什么"写在标题里；下面是可点的目标态（当前态不出现在列表里）。
                       「关」一律写 none（显式排除），回到跟随是单独一项 —— 两者的区别对使用者是有意义的。 -->
                  <template v-if="meshAvailable">
                    <el-dropdown-item divided disabled>ambient 流量 · {{ ambientStateText(row) }}</el-dropdown-item>
                    <el-dropdown-item v-if="ambientLabel(row) !== 'ambient'" command="ambient-on" :disabled="!meshEditable(row)">纳入 ambient</el-dropdown-item>
                    <el-dropdown-item v-if="ambientLabel(row) !== 'none'" command="ambient-none" :disabled="!meshEditable(row)">排除出 mesh（none）</el-dropdown-item>
                    <el-dropdown-item v-if="ambientLabel(row)" command="ambient-follow" :disabled="!meshEditable(row)">回到跟随命名空间</el-dropdown-item>

                    <el-dropdown-item divided disabled>L7 流量 · {{ l7StateText(row) }}</el-dropdown-item>
                    <!-- 只列能承接 Pod 流量的候选：不匹配的既不可选、也不作为条目出现（只在"一个都不能选"时给一句说明） -->
                    <el-dropdown-item
                      v-for="r in waypointAlternatives(row)" :key="r.name"
                      :command="`l7-use:${r.name}`" :disabled="!meshEditable(row)"
                    >经过 waypoint「{{ r.name }}」（{{ r.waypointFor }}）</el-dropdown-item>
                    <el-dropdown-item v-if="waypointOptions.length && !hasUsableWaypoint" disabled>本命名空间的 waypoint 都不处理 Pod 流量（waypoint-for 需为 workload 或 all）</el-dropdown-item>
                    <el-dropdown-item v-if="!waypointOptions.length && !waypointLabel(row)" command="l7-create" :disabled="!meshEditable(row)">创建 waypoint Gateway…</el-dropdown-item>
                    <el-dropdown-item v-if="waypointLabel(row) && waypointLabel(row) !== USE_WAYPOINT_NONE" command="l7-none" :disabled="!meshEditable(row)">不使用 waypoint（none）</el-dropdown-item>
                    <el-dropdown-item v-if="waypointLabel(row)" command="l7-follow" :disabled="!meshEditable(row)">回到跟随命名空间</el-dropdown-item>
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
      <YamlBlock v-if="yamlText" :yaml="yamlText" />
      <div v-else class="muted">加载中…</div>
    </el-drawer>

    <!-- 改镜像（直接发版：只改这一个容器的 image） -->
    <el-dialog v-model="imgVisible" :title="`改镜像 · ${imgForm.row?.name ?? ''}`" width="760px">
      <el-form label-width="72px">
        <el-form-item label="容器">
          <code class="res-name">{{ imgForm.containerName }}</code>
        </el-form-item>
        <el-form-item label="镜像">
          <el-autocomplete v-model="imgForm.image"
                           :fetch-suggestions="fetchImageSuggestions"
                           placeholder="如 nginx:1.27 或 registry.example.com/app"
                           style="width: 100%"
                           @keyup.enter="submitImage">
            <!-- 候选项由 el-autocomplete 自己渲染（它按 index 建 key），
                 所以「两条连接返回同一个串」不会重复 key、也不会丢行。
                 这里只负责把 value 与来源连接名摆出来。 -->
            <template #default="{ item }">
              <div class="suggest-row">
                <span class="suggest-value">{{ item.value }}</span>
                <span class="suggest-source">{{ item.connectionName }}</span>
              </div>
            </template>
          </el-autocomplete>
        </el-form-item>
      </el-form>
      <div class="dialog-hint">
        只改这一个容器的镜像，其余字段取线上现值回写。改的是 pod template → 控制器会滚一次新版本
        （存量 Pod 逐步替换）。
      </div>
      <template #footer>
        <el-button @click="imgVisible = false">取消</el-button>
        <el-button type="primary" :loading="imgSaving" @click="submitImage">提交</el-button>
      </template>
    </el-dialog>

    <!-- 回滚（选历史版本） -->
    <WorkloadRollbackDialog v-model="rollbackVisible" :row="rollbackRow" :ctx="ctxParams" @done="refresh" />
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
/* nodePort 直连链接：与名称同为可点样式，另带一个「新窗口」小图标 */
.np-link {
  white-space: nowrap;
}
.np-icon {
  margin-left: 2px;
  vertical-align: -2px;
  font-size: 11px;
}
.img-row {
  display: flex;
  align-items: center;
  gap: 8px;
  line-height: 1.9;
}
.img-container {
  color: var(--text-3);
  font-size: 12px;
  min-width: 64px;
}
.img-edit {
  cursor: pointer;
  color: var(--accent);
  font-size: 13px;
}
.img-edit.disabled {
  cursor: not-allowed;
  color: var(--text-3);
  opacity: 0.6;
}
.dialog-hint {
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
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

/* 镜像补全下拉：左侧完整镜像串（长了省略号截断），右侧来源连接名 */
.suggest-row {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.suggest-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.suggest-source {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
