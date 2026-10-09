<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import { gatewayApi, meshApi } from '@/api'
import { useResourceContext } from '@/stores/context'
import { isWaypointClassName, LABEL_WAYPOINT_FOR as WAYPOINT_FOR_LABEL } from '@/utils/waypoint'
import type { K8sGateway, K8sGatewayClass, K8sGatewayListener, K8sLabelSelector } from '@/types'

/**
 * Gateway 独立编辑页（命名空间级、租户域）。
 *
 * <h2>两个"透传保留"字段</h2>
 * 它们的 UI 在 v1 不做，但必须原样回传 —— 不回传等于清空（后端 update 的语义是"表单没有 = 用户删了"）：
 * <ul>
 *   <li>{@code listeners[].tls.certificateRefs[].group/kind/namespace}：UI 只编辑 name，
 *       其余三元组从详情回填后原样带回。</li>
 *   <li>{@code listeners[].allowedRoutes.namespaces.selector}：标签选择器（from=Selector 时生效）。
 *       这是 advanced 用法，v1 只在 YAML tab 里看，但改别的字段时不能把它冲掉。</li>
 * </ul>
 * 同 ServiceMonitorEditorView 对 {@code bearerTokenFile} 的处理口径。
 *
 * <p>{@code spec.tls}（网关级前后端 TLS）、{@code allowedListeners}、{@code infrastructure.parametersRef}
 * 等未建模字段不经过前端 —— 后端 fetch-overlay 原样保留。
 */
const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>((route.query.name as string) || null)

const ctx3 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))
const ctx2 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
}))

/**
 * listener 协议候选。前 5 个是 Gateway API 的标准协议（注释里标 `Support: Core`）；
 * **HBONE 是 Istio 的扩展**（waypoint 的 mesh 监听器用它，配 15008 端口）。
 * <p>CRD 的 {@code protocol} 字段**没有 Enum 校验**（只有"这 5 个是 Core"的说明），
 * 实现私有协议是合法的 —— 所以下面那个下拉开了 `allow-create`：不给自定义口子，
 * 等于把非标准实现（含 Istio 自己的 waypoint）堵在门外。
 */
const PROTOCOLS = ['HTTP', 'HTTPS', 'TLS', 'TCP', 'UDP', 'HBONE'] as const

/** waypoint 的标准形状（Istio 官方文档 / `istioctl waypoint generate` 的输出） */
const WAYPOINT_CLASS = 'istio-waypoint'
const WAYPOINT_LISTENER_NAME = 'mesh'
const WAYPOINT_LISTENER_PORT = 15008
const WAYPOINT_LISTENER_PROTOCOL = 'HBONE'

/**
 * 决定这个 waypoint 处理**哪类流量**的 label。值：service（缺省，Kubernetes 服务）/
 * workload（Pod・VM IP）/ all / none（不处理，用于测试）。
 * <p>注意它**不是**"是不是 waypoint"的判据 —— 那看 gatewayClassName（见 looksLikeWaypointClass）；
 * 一个 waypoint 完全可以不带这个 label。
 */
const WAYPOINT_FOR_VALUES = ['service', 'workload', 'all', 'none'] as const
/** 下拉里的取值说明（判据是流量**最初**发往的目标类型，见 FieldHelp） */
const WAYPOINT_FOR_LABELS: Record<string, string> = {
  service: 'service（Kubernetes 服务，默认）',
  workload: 'workload（Pod / VM IP）',
  all: 'all（服务 + 工作负载）',
  none: 'none（不处理，用于测试）',
}

/** 「创建 Waypoint」入口带来的标记（?waypoint=1） */
const waypointPreset = ref(route.query.waypoint === '1')

/**
 * 类名看着像 waypoint 类别 —— 决定两件事：候选列表里的徽标、以及**是否进入 waypoint 锁定模式**。
 * 判定本体在 `utils/waypoint.ts`（与后端 `GatewayService.isWaypointGateway` 同口径：类名含 `-waypoint`），
 * 那里也是 Gateway 列表 / 工作负载 L7 选择器共用的同一份规则。
 */
function looksLikeWaypointClass(name: string | null | undefined): boolean {
  return isWaypointClassName(name)
}

/** listener 允许挂载的 Route kind 候选（CRD 支持的全部 5 种；本批 UI 只提供 HTTPRoute 的页面） */
const ROUTE_KINDS = ['HTTPRoute', 'GRPCRoute', 'TCPRoute', 'TLSRoute', 'UDPRoute'] as const
const NS_FROM = ['Same', 'All', 'Selector', 'None'] as const

/** GatewayClass 引用候选（窄投影：name/controllerName/description；任何登录用户可读） */
const classOptions = ref<K8sGatewayClass[]>([])
const classLoading = ref(false)

async function loadClasses(): Promise<void> {
  if (!ready.value) return
  classLoading.value = true
  try {
    classOptions.value = await meshApi.gatewayClassRefs(state.clusterId!)
  } catch {
    classOptions.value = [] // 候选拉取失败 → 退化为手填（下拉 filterable + allow-create）
  } finally {
    classLoading.value = false
  }
}

// ---------- 表单模型 ----------
interface CertRow {
  name: string
  /** 以下三项 UI 不编辑，仅回填透传（见类注释） */
  group?: string | null
  kind?: string | null
  namespace?: string | null
}
interface ListenerRow {
  name: string
  protocol: string
  port: number | null
  hostname: string
  /** '' = 不配 tls */
  tlsMode: string
  certRefs: CertRow[]
  allowedFrom: string
  allowedKinds: string[]
  /** UI 不编辑，仅回填透传（from=Selector 时的命名空间标签选择器） */
  allowedSelector: K8sLabelSelector | null
}

function newListener(): ListenerRow {
  return {
    name: '', protocol: 'HTTP', port: 80, hostname: '',
    tlsMode: '', certRefs: [], allowedFrom: '', allowedKinds: [], allowedSelector: null,
  }
}

const form = reactive({
  name: '',
  gatewayClassName: '',
  listeners: [newListener()] as ListenerRow[],
  // ---- waypoint 模式专用：形状固定的部分之外，waypoint 真正可配的只有下面这些 ----
  /** istio.io/waypoint-for：这个 waypoint 处理哪类流量（service / workload / all / none） */
  waypointFor: 'service' as string,
  /** allowedRoutes.namespaces.from —— 跨命名空间 waypoint 就靠它（Istio 1.23+） */
  waypointAllowedFrom: '' as string,
  /** from=Selector 时的命名空间标签选择器 */
  waypointSelector: {} as Record<string, string>,
})

function addListener(): void { form.listeners.push(newListener()) }
function removeListener(i: number): void { form.listeners.splice(i, 1) }
function addCertRef(l: ListenerRow): void { l.certRefs.push({ name: '' }) }
function removeCertRef(l: ListenerRow, i: number): void { l.certRefs.splice(i, 1) }

// ==================== waypoint 模式：形状固定，只让填名称 ====================

/**
 * 是否处于 waypoint 锁定模式。两个进入条件：①从「创建 Waypoint」进来（`?waypoint=1`）；
 * ②创建时把 GatewayClass 选成了 waypoint 类别（选了就锁 —— 类名正是"是不是 waypoint"的判据，
 * 允许"waypoint 类名 + 自造 listener"等于允许造出一个控制器认不出来的东西）。
 * <p>编辑存量 waypoint 时类名本身就含 `-waypoint` → 自然落入；K8s 对象名不可变，故编辑态除名称外
 * 本来也不可改，等于整表只读。
 */
const waypointMode = computed(() =>
  waypointPreset.value || looksLikeWaypointClass(form.gatewayClassName),
)

/** 固定的 listener 形状（页面上只读展示用） */
const waypointListenerLabel = `${WAYPOINT_LISTENER_NAME} · ${WAYPOINT_LISTENER_PORT} · ${WAYPOINT_LISTENER_PROTOCOL}`

/**
 * waypoint 的固定 listener。**name/port/protocol 由本函数现构造，不读表单** —— 形状是常量
 * （Istio 认的就是这一个），不给编辑留任何缝；但 {@code allowedRoutes} 是 waypoint 真正可配的两处之一
 * （跨命名空间、以及限制可挂载的 Route 种类），从表单读。
 */
function waypointListener(): ListenerRow {
  const l = newListener()
  l.name = WAYPOINT_LISTENER_NAME
  l.protocol = WAYPOINT_LISTENER_PROTOCOL
  l.port = WAYPOINT_LISTENER_PORT
  l.allowedFrom = form.waypointAllowedFrom
  l.allowedSelector = Object.keys(form.waypointSelector).length
    ? { matchLabels: { ...form.waypointSelector } }
    : null
  return l
}

/** 编辑态：线上那个 listener 是否已偏离标准形状 —— 偏离时保存会把它规范化，界面必须明说 */
const waypointListenerDeviates = computed(() => {
  if (!waypointMode.value || !editing.value) return false
  const l = form.listeners[0]
  return form.listeners.length !== 1 || !l
    || l.name !== WAYPOINT_LISTENER_NAME
    || l.port !== WAYPOINT_LISTENER_PORT
    || l.protocol !== WAYPOINT_LISTENER_PROTOCOL
})

/**
 * 命名空间唯一性的**前端预检**：权威判定在后端 `GatewayService.assertWaypointUniqueInNamespace`
 * （挡在 create/update 上）。这里只为了让用户在填表前就知道会被拒，而不是提交后才吃一个错误。
 * <p>拉列表失败（集群断开 / RBAC 未覆盖）→ 标 `unknown`：**不阻断**提交，交回后端权威判定。
 */
const existingWaypoint = ref<string | null>(null)
const waypointCheck = ref<'idle' | 'checking' | 'ok' | 'conflict' | 'unknown'>('idle')

async function checkNamespaceWaypoint(): Promise<void> {
  if (!waypointMode.value || editing.value || !ready.value) {
    waypointCheck.value = 'idle'
    return
  }
  waypointCheck.value = 'checking'
  try {
    const list = await gatewayApi.list({ ...ctx3.value })
    const hit = list.find((g) => looksLikeWaypointClass(g.gatewayClassName))
    existingWaypoint.value = hit?.name ?? null
    waypointCheck.value = hit ? 'conflict' : 'ok'
  } catch {
    waypointCheck.value = 'unknown'
  }
}

watch([waypointMode, () => ready.value], () => { void checkNamespaceWaypoint() }, { immediate: true })

/** 预检到冲突 → 禁用提交（后端仍会再判一次；这里是体验层，不是授权层） */
const waypointBlocked = computed(() => waypointMode.value && waypointCheck.value === 'conflict')

/** 按 waypoint 标准形状预填表单（名字取 Istio 的默认名 `waypoint`；流量类型取 istioctl 的默认 `service`） */
function applyWaypointPreset(): void {
  form.name = 'waypoint'
  form.gatewayClassName = WAYPOINT_CLASS
  form.waypointFor = 'service'
  form.listeners = [waypointListener()]
}

/**
 * **仅创建态**提供的逃生口：误点了「创建 Waypoint」还能改回普通 Gateway。
 * 做法是清掉 preset 与类名（类名正是 waypoint 模式的依据，不清就出不来）；若重新选回 waypoint 类名，
 * 模式会再次生效 —— 不留"曾经解锁过"的隐藏状态。
 * <p>编辑态不给这个按钮：用户要求 waypoint 内容不允许更改，允许在编辑器里改类名等于允许把一个 waypoint
 * 悄悄变成入口网关；要换类型请删除重建。
 */
function unlockWaypoint(): void {
  waypointPreset.value = false
  form.gatewayClassName = ''
  form.listeners = [newListener()]
}

/** 协议决定哪些字段有义：HTTP/TCP/UDP 不允许 tls；TCP/UDP 不允许 hostname；HTTPS 固定 Terminate。
 *  端口只在「还停在默认值 80」时才跟着协议改 —— HTTPS/TLS 挂 80 几乎必然写错，
 *  但用户已显式填过别的端口就不动它。 */
function onProtocolChange(l: ListenerRow): void {
  if (l.protocol === 'HTTP' || l.protocol === 'TCP' || l.protocol === 'UDP') {
    l.tlsMode = ''
    l.certRefs = []
  }
  if (l.protocol === 'TCP' || l.protocol === 'UDP') {
    l.hostname = ''
  }
  if (l.protocol === 'HTTPS') {
    l.tlsMode = 'Terminate'
  }
  if (l.protocol === 'TLS' && !l.tlsMode) {
    l.tlsMode = 'Terminate'
  }
  if ((l.protocol === 'HTTPS' || l.protocol === 'TLS') && l.port === 80) {
    l.port = 443
  }
  if (l.protocol === WAYPOINT_LISTENER_PROTOCOL && l.port === 80) {
    l.port = WAYPOINT_LISTENER_PORT // HBONE 挂 80 必然写错；同上面 HTTPS→443 的口径
  }
}
const tlsAllowed = (p: string): boolean => p === 'HTTPS' || p === 'TLS'
const hostnameAllowed = (p: string): boolean => p !== 'TCP' && p !== 'UDP'

// ---------- 编辑回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

function fromListener(l: K8sGatewayListener): ListenerRow {
  return {
    name: l.name ?? '',
    protocol: l.protocol ?? 'HTTP',
    port: l.port ?? null,
    hostname: l.hostname ?? '',
    tlsMode: l.tls?.mode ?? '',
    certRefs: (l.tls?.certificateRefs ?? []).map((r) => ({
      name: r.name ?? '', group: r.group, kind: r.kind, namespace: r.namespace,
    })),
    allowedFrom: l.allowedRoutes?.namespaces?.from ?? '',
    allowedKinds: (l.allowedRoutes?.kinds ?? []).map((k) => k.kind ?? '').filter(Boolean),
    allowedSelector: l.allowedRoutes?.namespaces?.selector ?? null,
  }
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await gatewayApi.get(editing.value, ctx3.value)
    form.name = d.name
    form.gatewayClassName = d.gatewayClassName ?? ''
    form.listeners = (d.listeners ?? []).map(fromListener)
    if (!form.listeners.length) form.listeners = [newListener()]
    // waypoint 模式专用字段：流量类型取自 label（缺省 = service 行为），allowedRoutes 取自首个 listener
    form.waypointFor = String((d.labels ?? {})[WAYPOINT_FOR_LABEL] ?? 'service')
    const first = (d.listeners ?? [])[0]
    form.waypointAllowedFrom = first?.allowedRoutes?.namespaces?.from ?? ''
    form.waypointSelector = { ...(first?.allowedRoutes?.namespaces?.selector?.matchLabels ?? {}) }
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

watch([() => ready.value, () => state.namespace], ([r]) => {
  if (!r) return
  void loadClasses()
  if (editing.value) void loadDetail()
}, { immediate: true })

// ---------- 提交 ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

/** 提交前软校验：CRD 侧还各自的 CEL 也会拦，但本地报错更可读。返回错误文案或 null。 */
function validate(): string | null {
  const name = (editing.value ?? form.name).trim()
  if (!name) return '请输入名称'
  if (!RFC1123_RE.test(name)) return '名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'
  // waypoint：GatewayClass 与 listener 都由平台按标准形状生成（见 waypointListener），没有用户可填的
  // listener 字段 —— 故不校验表单里那一份（编辑态它读的是线上旧值，与真正要提交的东西不是一回事）
  if (waypointMode.value) return null
  if (!form.gatewayClassName.trim()) return '请选择或填写 GatewayClass'
  if (!form.listeners.length) return '至少需要一个监听器'
  const seen = new Set<string>()
  for (const [i, l] of form.listeners.entries()) {
    const no = `监听器 #${i + 1}`
    const ln = l.name.trim()
    if (!ln) return `${no}：请填写名称`
    if (!RFC1123_RE.test(ln)) return `${no}：名称须为 RFC-1123`
    if (seen.has(ln)) return `监听器名重复：「${ln}」`
    seen.add(ln)
    if (!l.protocol) return `${no}：请选择协议`
    if (l.port == null || l.port < 1 || l.port > 65535) return `${no}：端口须为 1–65535`
    if (tlsAllowed(l.protocol) && !l.tlsMode) return `${no}：${l.protocol} 必须选择 TLS 模式`
    if (l.protocol === 'HTTPS' && l.tlsMode !== 'Terminate') return `${no}：HTTPS 的 TLS 模式只能是 Terminate`
    if (l.tlsMode === 'Terminate' && !l.certRefs.some((r) => r.name.trim())) {
      return `${no}：TLS 模式为 Terminate 时至少需要一个证书引用`
    }
  }
  return null
}

/** ListenerRow → 提交用的 listener 负载（waypoint 模式不走这里，见 listenersToSubmit） */
function toListenerPayload(l: ListenerRow): K8sGatewayListener {
  const listener: K8sGatewayListener = {
    name: l.name.trim(),
    port: l.port,
    protocol: l.protocol,
  }
  if (hostnameAllowed(l.protocol) && l.hostname.trim()) listener.hostname = l.hostname.trim()
  if (tlsAllowed(l.protocol) && l.tlsMode) {
    listener.tls = {
      mode: l.tlsMode,
      certificateRefs: l.certRefs
        .filter((r) => r.name.trim())
        // group/kind/namespace 原样带回（UI 不编辑）
        .map((r) => ({ name: r.name.trim(), group: r.group ?? null, kind: r.kind ?? null, namespace: r.namespace ?? null })),
    }
  }
  if (l.allowedFrom || l.allowedKinds.length || l.allowedSelector) {
    listener.allowedRoutes = {
      namespaces: (l.allowedFrom || l.allowedSelector)
        ? { from: l.allowedFrom || null, selector: l.allowedSelector }
        : null,
      kinds: l.allowedKinds.length ? l.allowedKinds.map((k) => ({ kind: k })) : null,
    }
  }
  return listener
}

/** 真正会提交的 listeners：waypoint 模式恒为标准形状那一个（不是表单里读到的那个） */
const listenersToSubmit = computed<K8sGatewayListener[]>(() =>
  waypointMode.value ? [toListenerPayload(waypointListener())] : form.listeners.map(toListenerPayload),
)

const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !ready.value) return
  if (waypointBlocked.value) {
    ElMessage.warning(`该命名空间已有 waypoint「${existingWaypoint.value}」，每个命名空间至多一个`)
    return
  }
  const err = validate()
  if (err) { ElMessage.warning(err); return }

  const body: K8sGateway = {
    name: (editing.value ?? form.name).trim(),
    // tenantId / clusterId 由 makeResourceApi 的 ctx 参数合并进请求体；namespace 必须来自 body（Create 的边界判据）
    namespace: state.namespace!,
    gatewayClassName: form.gatewayClassName.trim(),
    listeners: listenersToSubmit.value,
    // waypoint 模式才提交 label，且**只提交这一个 key**：SSA 对 metadata.labels 是按 key 细粒度合并的，
    // 只声明自己那一项就不会把 istiod 加的其它 label 一起接管/抹掉（故无需回传全量 labels）。
    // 非 waypoint 仍传 null —— 保持既有行为不变（平台不碰任何 label）。
    labels: waypointMode.value ? { [WAYPOINT_FOR_LABEL]: form.waypointFor } : null,
  }

  saving.value = true
  try {
    if (editing.value) {
      await gatewayApi.update(editing.value, ctx2.value, body)
      ElMessage.success('保存成功')
    } else {
      await gatewayApi.create(ctx2.value, body)
      ElMessage.success('创建成功')
    }
    router.push({ name: 'gateways' })
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push({ name: 'gateways' }) }

const pageTitle = computed(() => (editing.value ? '编辑 Gateway' : '创建 Gateway'))
const formVisible = computed(() => ready.value && (!editing.value || detailState.value === 'loaded'))
const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})

onMounted(() => {
  // 预填要在 load() 之前：表单是否渲染只看 ready，不等这里的异步
  if (!editing.value && waypointPreset.value) applyWaypointPreset()
  void load()
})
</script>

<template>
  <div class="editor-page">
    <PageHeader :title="pageTitle" :description="contextDesc">
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState
      v-if="detailState === 'error'"
      title="加载失败"
      description="该 Gateway 可能已被删除，或所选命名空间下不存在。"
    >
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <!-- waypoint 模式的三条提示：命名空间冲突（阻断）/ 线上 listener 偏离标准形状（会规范化）/ 常规说明 -->
      <el-alert
        v-if="waypointMode && waypointBlocked"
        type="error"
        show-icon
        :closable="false"
        class="waypoint-alert"
        title="该命名空间已有 waypoint，本次创建会被拒绝"
        :description="`已有：${existingWaypoint}。平台限制每个命名空间至多一个 waypoint —— 挂载方 istio.io/use-waypoint 是按名字指向它的，多个会让那个选择失去意义。请先删除它，或改装那个 Gateway。`"
      />
      <el-alert
        v-else-if="waypointMode && waypointListenerDeviates"
        type="warning"
        show-icon
        :closable="false"
        class="waypoint-alert"
        title="该 waypoint 的监听器不是标准形状"
        description="waypoint 的监听器固定为 { name: mesh, port: 15008, protocol: HBONE }，本页不提供编辑 —— 保存时会按标准形状规范化。"
      />
      <el-alert
        v-else-if="waypointMode"
        type="info"
        show-icon
        :closable="false"
        class="waypoint-alert"
        :title="editing ? 'waypoint（形状固定，不可编辑）' : '正在创建 waypoint（ambient 数据面的七层代理网关）'"
        description="waypoint 的形状固定：GatewayClass 为 istio-waypoint、单个 listener { name: mesh, port: 15008, protocol: HBONE }，都不可编辑；可填的只有名称、处理的流量类型、允许的 Route 命名空间。两个前置条件：所在命名空间需先打 istio.io/dataplane-mode: ambient 标签，消费方（命名空间 / 服务 / Pod）需打 istio.io/use-waypoint=<本 Gateway 名> 才会走它。⚠️ use-waypoint 只表达意图、不保证流量真过（waypoint 不存在或流量类型不匹配时 ztunnel 直接放行）—— 要强制走，需另配只放行本 waypoint 身份的 AuthorizationPolicy，其身份就是与本 Gateway 同名的 ServiceAccount。"
      />
      <el-form v-if="formVisible" label-position="left" label-width="200px" class="editor-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 main-gateway（RFC-1123）" style="max-width: 420px" />
          <FieldHelp tip="Gateway 名（本命名空间内唯一）。Route 的 parentRefs[].name 指向它。创建后不可改。" />
        </el-form-item>

        <!-- waypoint：形状固定的部分**只读展示**（不可编辑、不可增删），下面 v-else 才是普通 Gateway 的表单 -->
        <template v-if="waypointMode">
          <el-form-item label="GatewayClass">
            <el-input :model-value="form.gatewayClassName" disabled style="max-width: 420px" />
            <FieldHelp tip="waypoint 类型由 GatewayClass 决定（类名含 -waypoint，如 istio-waypoint）—— Istio 就是按这个认 waypoint 的，故平台代填、不可编辑。要换成普通入口网关请删除后重建。" />
          </el-form-item>
          <el-form-item label="监听器">
            <el-input :model-value="waypointListenerLabel" disabled style="max-width: 420px" />
            <FieldHelp tip="waypoint 的监听器固定为 { name: mesh, port: 15008, protocol: HBONE }：一个 mesh 监听器、15008 端口、HBONE 协议 —— Istio 认的就是这个形状，固定一个、不可增删改。业务流量的分流靠把 HTTPRoute / GRPCRoute / TCPRoute / TLSRoute 的 parentRefs 指向本 Gateway（它们就是挂到 waypoint 上的）。" />
          </el-form-item>

          <el-form-item label="处理的流量类型" required>
            <el-select v-model="form.waypointFor" style="width: 320px">
              <el-option v-for="v in WAYPOINT_FOR_VALUES" :key="v" :label="WAYPOINT_FOR_LABELS[v]" :value="v" />
            </el-select>
            <FieldHelp tip="对应 Gateway 上的 istio.io/waypoint-for 标签，决定这个 waypoint 处理哪类流量。判据是流量**最初**发往的目标类型 —— 即使最终解析到 Pod IP，发往服务的流量仍算 service，所以不会绕两次 waypoint。选错不会报错，只是那份 L7 策略静默不生效（例：只处理 service 时，直接打到 Pod IP 的请求会绕过它）。" />
          </el-form-item>

          <el-form-item label="允许的 Route 命名空间">
            <el-select v-model="form.waypointAllowedFrom" clearable placeholder="缺省 Same（仅本命名空间）" style="width: 260px">
              <el-option v-for="f in NS_FROM" :key="f" :label="f" :value="f" />
            </el-select>
            <FieldHelp tip="allowedRoutes.namespaces.from。留空 / Same = 只有本命名空间的 Route 能挂上来（Istio 生成 waypoint 时的默认）；想跨命名空间共用这个 waypoint（Istio 1.23+，例如把出口网关放在公共基础设施命名空间里）就选 All 或 Selector。消费方靠 istio.io/use-waypoint + istio.io/use-waypoint-namespace 两个标签指过来。" />
          </el-form-item>

          <el-form-item v-if="form.waypointAllowedFrom === 'Selector'" label="命名空间选择器">
            <LabelEditor v-model="form.waypointSelector" />
            <FieldHelp tip="from=Selector 时生效：按命名空间标签筛选，例如键 kubernetes.io/metadata.name、值写目标命名空间名。留空则没有任何命名空间能挂上来。" />
          </el-form-item>
        </template>

        <template v-else>
        <el-form-item label="GatewayClass" required>
          <el-select
            v-model="form.gatewayClassName"
            filterable
            allow-create
            default-first-option
            :loading="classLoading"
            :no-data-text="ready ? '未取到 GatewayClass 候选（可直接输入名称）' : '上下文未就绪'"
            placeholder="选择或输入 GatewayClass 名"
            style="max-width: 420px"
          >
            <el-option v-for="g in classOptions" :key="g.name" :label="g.name" :value="g.name">
              <span>{{ g.name }}</span>
              <span v-if="looksLikeWaypointClass(g.name)" class="opt-badge">waypoint</span>
              <span class="opt-sub">{{ g.controllerName }}</span>
            </el-option>
          </el-select>
          <FieldHelp tip="决定由哪个控制器接管本 Gateway（cluster-scoped 资源，由平台管理员维护）。候选为空时可直接输入 —— 但也意味着该集群可能还没装 Gateway API 控制器。⚠️ 若选的是 waypoint 类别（类名含 -waypoint，如 istio-waypoint）：平台限制每个命名空间至多一个 waypoint Gateway，该命名空间已有时本次会被拒绝 —— 挂载方 istio.io/use-waypoint 是按名字指向它的，多个会让那个选择失去意义。" />
        </el-form-item>

        <el-divider content-position="left">监听器（listeners）</el-divider>

        <div v-for="(l, i) in form.listeners" :key="i" class="sub-block">
          <div class="sub-head">
            <span class="sub-title">监听器 #{{ i + 1 }}</span>
            <el-button v-if="form.listeners.length > 1" link type="danger" @click="removeListener(i)">删除</el-button>
          </div>

          <el-form-item label="名称" required>
            <el-input v-model="l.name" placeholder="如 http / https（RFC-1123）" style="max-width: 300px" />
            <FieldHelp tip="监听器名（本 Gateway 内唯一）。Route 的 parentRefs[].sectionName 用它精确挂载到某个监听器。" />
          </el-form-item>

          <el-form-item label="协议" required>
            <el-select
              v-model="l.protocol"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入协议"
              style="width: 200px"
              @change="onProtocolChange(l)"
            >
              <el-option v-for="p in PROTOCOLS" :key="p" :label="p" :value="p" />
            </el-select>
            <FieldHelp tip="HTTP/HTTPS 是七层；TLS 是四层透传（不解密，按 SNI 路由）；TCP/UDP 是四层直转；HBONE 是 Istio 的扩展协议（waypoint 的 mesh 监听器用它）。CRD 不限制协议取值（上述 5 个是标准集的 Core），所以这里允许直接输入实现私有的协议。HTTP/TCP/UDP 不允许配 TLS；TCP/UDP 不允许配主机名。" />
          </el-form-item>

          <el-form-item label="端口" required>
            <el-input-number v-model="l.port" :min="1" :max="65535" controls-position="right" style="width: 160px" />
            <FieldHelp tip="监听端口（1–65535）。同一 Gateway 内「端口 + 协议 + 主机名」组合必须唯一。" />
          </el-form-item>

          <el-form-item v-if="hostnameAllowed(l.protocol)" label="主机名">
            <el-input v-model="l.hostname" placeholder="留空 = 所有主机名；支持 *.example.com" style="max-width: 360px" />
            <FieldHelp tip="只接受匹配该主机名的请求（DNS 名，可含前缀通配 *）。留空表示全部主机名。HTTPS 终止时通常要填。" />
          </el-form-item>

          <el-form-item v-if="tlsAllowed(l.protocol)" label="TLS 模式" required>
            <el-select v-model="l.tlsMode" style="width: 200px">
              <el-option label="Terminate（终止，需证书）" value="Terminate" />
              <el-option label="Passthrough（透传，不解密）" value="Passthrough" />
            </el-select>
            <FieldHelp tip="Terminate = 在网关上解密后再按 HTTP 规则路由（HTTPS 只能用它，且必须给证书）；Passthrough = 只按 SNI 转发到后端，由后端自己解密（TLS 协议用）。" />
          </el-form-item>

          <el-form-item v-if="l.tlsMode === 'Terminate'" label="证书引用（Secret 名）">
            <div class="list-rows">
              <div v-for="(r, ci) in l.certRefs" :key="ci" class="list-row">
                <el-input v-model="r.name" placeholder="如 wildcard-example-com-tls" style="flex: 1" />
                <el-button link type="danger" @click="removeCertRef(l, ci)">删除</el-button>
              </div>
              <el-button size="small" @click="addCertRef(l)">添加证书</el-button>
            </div>
            <FieldHelp tip="引用同命名空间（或显式指定命名空间）的 kubernetes.io/tls 类型 Secret，取其中的 tls.crt / tls.key。Secret 必须已存在，否则监听器一直停在未编程。" />
          </el-form-item>

          <el-form-item label="允许的 Route 命名空间">
            <el-select v-model="l.allowedFrom" clearable placeholder="缺省 Same（仅本命名空间）" style="width: 240px">
              <el-option label="Same（仅本命名空间）" value="Same" />
              <el-option label="All（全部命名空间）" value="All" />
              <el-option label="Selector（按命名空间标签）" value="Selector" />
              <el-option label="None（不允许任何 Route）" value="None" />
            </el-select>
            <FieldHelp tip="限制哪些命名空间的 Route 能挂到本监听器。跨命名空间挂载还需要目标命名空间里有 ReferenceGrant 放行；Selector 的具体标签选择器本版请在 YAML 里看/改（编辑其它字段不会覆盖它）。" />
          </el-form-item>

          <el-form-item label="允许的 Route 类型">
            <el-select v-model="l.allowedKinds" multiple clearable placeholder="留空 = 本 Gateway 支持的全部类型" style="max-width: 420px">
              <el-option v-for="k in ROUTE_KINDS" :key="k" :label="k" :value="k" />
            </el-select>
            <FieldHelp tip="限制哪些种类的 Route 能挂上来，例如只允许 HTTPRoute。留空表示不限制（由控制器按协议支持的类型决定）。" />
          </el-form-item>
        </div>

        <el-button size="small" @click="addListener">添加监听器</el-button>
        </template>

        <el-form-item class="submit-row">
          <el-button type="primary" :loading="saving" :disabled="waypointBlocked" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
          <el-button @click="goBack">取消</el-button>
          <!-- 仅创建态：误点「创建 Waypoint」后能改回普通 Gateway（编辑态不给 —— 不允许把 waypoint 改成入口网关） -->
          <el-button v-if="waypointMode && !editing" link type="primary" @click="unlockWaypoint">改为普通 Gateway</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.editor-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.editor-panel {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 24px;
}
.editor-form { max-width: 860px; }
.sub-block {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px 14px 2px;
  margin-bottom: 12px;
  background: var(--panel-hover);
}
.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.list-rows { display: flex; flex-direction: column; gap: 8px; width: 100%; max-width: 520px; }
.list-row { display: flex; align-items: center; gap: 10px; }
.opt-sub { float: right; color: var(--text-3); font-size: 12px; margin-left: 16px; }
.opt-badge {
  margin-left: 8px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  color: var(--accent);
  background: var(--accent-soft);
}
.waypoint-alert { margin-bottom: 16px; }
.submit-row { margin-top: 18px; }
</style>
