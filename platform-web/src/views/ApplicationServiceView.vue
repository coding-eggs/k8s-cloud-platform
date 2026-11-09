<script setup lang="ts">
/**
 * 服务详情（设计 §8）：概览 / 配置 / Pod 与日志 / 监控 四个页签。
 *
 * 配置页签改的是「期望配置」，不碰线上；保存带读到的 version（乐观锁）。
 * 冲突时不丢用户输入：提示条给两条路 —— 载入最新（丢弃我的修改）/ 保留我的修改并取最新版本。
 * Pod / 日志 / 监控复用资源域的 PodLogDialog、WorkloadLogsDialog、MetricsPanel，
 * 工作负载名按台账约定 `<slug>-<key>`。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { appReleaseApi, appServiceApi, applicationApi, podApi, serviceApi, workloadApi } from '@/api'
import { useNodeAddresses } from '@/composables/useNodeAddresses'
import { useApplicationContext } from '@/composables/useApplicationContext'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { podLabelSelector } from '@/utils/podSelector'
import { fmtAge, fmtDate, podPhaseType } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import EmptyState from '@/components/EmptyState.vue'
import PodLogDialog from '@/components/PodLogDialog.vue'
import WorkloadLogsDialog from '@/components/WorkloadLogsDialog.vue'
import MetricsPanel from '@/components/workload/MetricsPanel.vue'
import AppEnvEditor from '@/components/app-release/AppEnvEditor.vue'
import StringListEditor from '@/components/app-release/StringListEditor.vue'
import AppTagInput from '@/components/app-release/AppTagInput.vue'
import AppReleaseGuide from '@/components/app-release/AppReleaseGuide.vue'
import AppQuantityInput from '@/components/app-release/AppQuantityInput.vue'
import AppServiceOtelForm from '@/components/app-release/AppServiceOtelForm.vue'
import AppServiceRuntimeRows from '@/components/app-release/AppServiceRuntimeRows.vue'
import { useClusterComponents } from '@/composables/useClusterComponents'
import { parseQuantity } from '@/utils/quantity'
import { FILE_REF_FIELDS, refToken } from '@/components/app-release/dependencyUtil'
import { useResourceContext } from '@/stores/context'
import type { AppDependencyRow, AppServiceDesired, AppServiceRow, AppServiceState, AppSettings, ApplicationView, K8sPod, K8sService } from '@/types'
import type { WorkloadDetail } from '@/types/workload'

const route = useRoute()
const router = useRouter()
const { tenantId, ensure } = useApplicationContext()
const { setNamespace } = useResourceContext()
const perm = usePermission()
const canWrite = computed(() => perm.has(apiCodes.applicationWrite))

const appId = computed(() => String(route.params.id ?? ''))
const key = computed(() => String(route.params.key ?? ''))

const app = ref<ApplicationView | null>(null)
const row = ref<AppServiceRow | null>(null)
const pendingKeys = ref<string[]>([])
const allRows = ref<AppServiceRow[]>([])
/** 应用设置（期望值）：可观测页签据此说明本服务是否会被链路追踪 */
const appSettings = ref<AppSettings>({})
const deps = ref<AppDependencyRow[]>([])
const loading = ref(false)
const notFound = ref(false)

const STATE_META: Record<AppServiceState, { text: string; type: 'success' | 'warning' | 'danger' | 'info' | 'neutral' }> = {
  READY: { text: '运行正常', type: 'success' },
  PENDING: { text: '启动中', type: 'warning' },
  FAILED: { text: '异常', type: 'danger' },
  MISSING: { text: '线上缺失', type: 'danger' },
  UNKNOWN: { text: '状态未知', type: 'neutral' },
  NOT_RELEASED: { text: '未发布', type: 'info' },
}
const PENDING_TEXT: Record<string, string> = { ADDED: '新增，待发布', CHANGED: '有待发布改动', DELETED: '待删除' }

/** 孤儿服务（线上有、台账无行）：version 为 null，配置只读 */
const orphan = computed(() => !!row.value && row.value.version == null)
/** 待删除的服务后端拒绝修改；没有写权限同样只读 */
const pendingDelete = computed(() => row.value?.pending === 'DELETED')
const readonly = computed(() => orphan.value || pendingDelete.value || !canWrite.value)
const workloadName = computed(() => (app.value ? `${app.value.slug}-${key.value}` : ''))

// ---------- 表单 ----------
const form = ref<AppServiceDesired>({ imageRepo: '', imageTag: '' })
const baseline = ref('')
/** 读到/保存后的期望配置原样副本：用来区分「原本就有的空串参数」和「用户新加了没填的空行」 */
let original: AppServiceDesired = { imageRepo: '', imageTag: '' }
const version = ref<number | null>(null)
/** 已保存的期望配置（响应式副本）：组件门禁只拦「从关到开」，保存里已开的始终可关 / 可保留 */
const saved = ref<AppServiceDesired | null>(null)

// ---------- 组件门禁（spec 2026-11-07 §2）：未探测 = 不可用 ----------
const comps = useClusterComponents(computed(() => app.value?.clusterId))
const GATED_CODES = ['hpa-resource-metrics', 'servicemonitor', 'otel-collection', 'otel-logs', 'routes']
const flagAvailable = computed<Record<string, boolean>>(() =>
  Object.fromEntries(GATED_CODES.map((c) => [c, comps.available(c)])))
const flagReasons = computed<Record<string, string>>(() =>
  Object.fromEntries(GATED_CODES.map((c) => [c, comps.unavailableReason(c)])))
const hpaLocked = computed(() =>
  !form.value.autoscalingEnabled && !saved.value?.autoscalingEnabled && !flagAvailable.value['hpa-resource-metrics'])
const metricsLocked = computed(() =>
  !form.value.metricsEnabled && !saved.value?.metricsEnabled && !flagAvailable.value.servicemonitor)
/** 网关暴露（HTTPRoute / TCPRoute）依赖 Istio 的 routes 旗标；已保存为网关方式的始终可选（不追溯） */
const GATEWAY_EXPOSES = ['gateway-http', 'gateway-tcp']
const isGatewayExpose = (v: unknown): boolean => typeof v === 'string' && GATEWAY_EXPOSES.includes(v)
const gatewayLocked = computed(() => !isGatewayExpose(saved.value?.expose) && !flagAvailable.value.routes)

function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T
}
function snapshotOf(): string {
  return JSON.stringify(buildDesired())
}
function fillForm(d: AppServiceDesired): void {
  original = clone(d)
  saved.value = clone(d)
  const c = clone(d)
  c.env = (c.env ?? []).map((e) => ({ ...e }))
  form.value = c
  baseline.value = snapshotOf()
}
const dirty = computed(() => !!row.value && snapshotOf() !== baseline.value)

/** 组装要提交的 desired：未识别的字段原样保留（后端白名单，不认的会拒绝，所以只带读到的）；空串不发 */
function buildDesired(): AppServiceDesired {
  const d: AppServiceDesired = clone(form.value)
  d.imageRepo = (d.imageRepo ?? '').trim()
  d.imageTag = (d.imageTag ?? '').trim()
  // 命令 / 参数：用户新加了没填的空项在保存时丢掉；原本就有的空串（按个数）原样保留
  for (const k of ['command', 'args'] as const) {
    const list = d[k]
    if (!Array.isArray(list)) continue
    const orig = original[k]
    let allowed = Array.isArray(orig) ? orig.filter((x) => x === '').length : 0
    d[k] = list.filter((x) => x !== '' || allowed-- > 0)
  }
  for (const k of Object.keys(d)) {
    const v = d[k]
    if (v === '' || v == null || (Array.isArray(v) && v.length === 0 && k !== 'env')) delete d[k]
  }
  d.env = (d.env ?? []).filter((e) => e.name || e.value || e.secretName).map((e) => {
    const o = { ...e }
    if (o.kind !== 'SECRET') { delete o.secretName; delete o.secretKey } else { delete o.value }
    return o
  })
  // 配置文件：路径去首尾空白；路径和内容都空的新行丢掉；没有文件就不带这个键（后端同样不存空列表）
  const files = (form.value.configFiles ?? [])
    .map((f) => ({ path: (f.path ?? '').trim(), content: f.content ?? '' }))
    .filter((f) => f.path || f.content)
  if (files.length) d.configFiles = files
  else delete d.configFiles
  // 对外暴露：只有有端口的服务能暴露；不暴露就不带这两个键（后端同样不存 none）。
  // nodePort 只属于 NodePort 方式；网关方式的端口由平台分配，不在 desired 里
  if (!d.port || (d.expose !== 'nodeport' && !isGatewayExpose(d.expose))) {
    delete d.expose
    delete d.nodePort
  } else if (d.expose !== 'nodeport') {
    delete d.nodePort
  }
  return d
}

/** 探针类型（缺省 http 不存；tcp 存 'tcp'）。用户手动选过就不再跟着暴露方式自动切 */
const probeTypeTouched = ref(false)
const probeTypeModel = computed<'http' | 'tcp'>({
  get: () => (form.value.probeType === 'tcp' ? 'tcp' : 'http'),
  set: (v) => {
    probeTypeTouched.value = true
    if (v === 'tcp') form.value.probeType = 'tcp'
    else delete form.value.probeType
  },
})

/** 对外暴露方式（表单里用 none 表示「不暴露」，保存时去掉） */
const exposeMode = computed<string>({
  get: () => (form.value.expose === 'nodeport' || isGatewayExpose(form.value.expose)
    ? form.value.expose as string : 'none'),
  set: (v) => {
    if (v === 'nodeport') form.value.expose = 'nodeport'
    else if (isGatewayExpose(v)) {
      form.value.expose = v
      delete form.value.nodePort
      // 纯 TCP 服务用 HTTP 探针会一直失败：用户没手动选过探针类型时跟着切成 TCP
      if (v === 'gateway-tcp' && !probeTypeTouched.value) form.value.probeType = 'tcp'
    }
    else { delete form.value.expose; delete form.value.nodePort }
  },
})
/** 网关方式的访问地址：节点 IP + 平台分到的端口（与 NodePort 地址同一个节点 IP 来源） */
const gatewayAddress = computed(() => {
  const ing = row.value?.ingress
  if (!ing || !addr.defaultHost) return ''
  return ing.protocol === 'HTTP' ? `http://${addr.defaultHost}:${ing.port}` : `${addr.defaultHost}:${ing.port}`
})
const gatewayStatus = computed<{ text: string; type: 'success' | 'danger' | 'info' }>(() => {
  const ing = row.value?.ingress
  if (!ing || ing.programmed == null) return { text: '状态未知', type: 'info' }
  if (ing.conflicted) return { text: '端口冲突', type: 'danger' }
  return ing.programmed ? { text: '已生效', type: 'success' } : { text: '未生效', type: 'danger' }
})
const nodePortModel = computed<number | undefined>({
  get: () => (typeof form.value.nodePort === 'number' ? form.value.nodePort : undefined),
  set: (v) => { if (v == null) delete form.value.nodePort; else form.value.nodePort = v },
})

/** 软校验：请求不能大于上限（K8s 会拒绝，发布时才报就晚了） */
const resourceProblem = computed(() => {
  const over = (req?: string | null, lim?: string | null): boolean => {
    const r = parseQuantity(req); const l = parseQuantity(lim)
    return Number.isFinite(r) && Number.isFinite(l) && r > l
  }
  const out: string[] = []
  if (over(form.value.cpuRequest, form.value.cpuLimit)) out.push('CPU 请求大于上限')
  if (over(form.value.memoryRequest, form.value.memoryLimit)) out.push('内存请求大于上限')
  return out.join('；')
})

// ---------- 配置文件 ----------
/**
 * 软校验，与后端 AppServiceDesired.normalizeConfigFiles 同一套规则（权威校验在后端）：
 * 路径形状与字符集、长度、重复 / 嵌套、与持久化挂载路径冲突、引用只能是 FILE_REF_FIELDS。
 */
const PATH_SEGMENT = /^[A-Za-z0-9._@+=,-]+$/
const FILE_REF = /\$\{([^.${}]+)\.([^${}]+)\}/g
/** 持久化数据卷挂载点：只有「有端口 + 开持久化」才有，默认 /data（同后端 dataMountPath） */
const dataMount = computed(() => {
  if (!form.value.port || !form.value.persistenceEnabled) return null
  const p = (form.value.mountPath ?? '').trim() || '/data'
  return p.length > 1 && p.endsWith('/') ? p.slice(0, -1) : p
})
function fileProblem(i: number): string {
  const list = form.value.configFiles ?? []
  const f = list[i]
  if (!f) return ''
  const path = (f.path ?? '').trim()
  if (!path) return f.content ? '请填写路径' : ''
  if (!path.startsWith('/')) return '路径必须以 / 开头'
  if (path.length > 255) return '路径过长（最多 255 个字符）'
  if (path.endsWith('/')) return '路径要指向文件，不能以 / 结尾'
  if (path.split('/').slice(1).some((seg) => seg === '' || seg === '.' || seg === '..' || !PATH_SEGMENT.test(seg))) {
    return '路径只允许字母、数字和 . _ - @ + = ,，不能有空段、. 或 ..'
  }
  const others = list.filter((_, j) => j !== i).map((o) => (o.path ?? '').trim()).filter(Boolean)
  if (others.includes(path)) return '路径重复'
  const nested = others.find((o) => path.startsWith(`${o}/`) || o.startsWith(`${path}/`))
  if (nested) return `与 ${nested} 互相嵌套`
  const mount = dataMount.value
  if (mount && (path === mount || path.startsWith(`${mount}/`) || mount.startsWith(`${path}/`))) {
    return `与持久化挂载路径 ${mount} 冲突`
  }
  for (const m of (f.content ?? '').matchAll(FILE_REF)) {
    if (!FILE_REF_FIELDS.includes(m[2] ?? '')) {
      return `${m[0]} 不能用在配置文件里：内容会明文写进 ConfigMap，只能引用 ${FILE_REF_FIELDS.join(' / ')}；凭据请用环境变量`
    }
  }
  return ''
}
const fileProblems = computed(() => (form.value.configFiles ?? []).map((_, i) => fileProblem(i)))
const filesInvalid = computed(() => fileProblems.value.some(Boolean))
const fileRefHint = computed(() => {
  const keys = [...deps.value.map((d) => d.key), ...selfRefs.value]
  const sample = keys[0] ? refToken(keys[0], 'host') : '${order-db.host}'
  return `内容可引用 ${FILE_REF_FIELDS.map((f) => `.${f}`).join(' / ')}，如 ${sample}；发布时替换成实际值。`
    + '不能引用 username / password（文件明文进 ConfigMap），凭据请用环境变量。'
})
function addFile(): void {
  form.value.configFiles = [...(form.value.configFiles ?? []), { path: '', content: '' }]
}
function removeFile(i: number): void {
  form.value.configFiles = (form.value.configFiles ?? []).filter((_, j) => j !== i)
}

async function load(opts: { keepForm?: boolean } = {}): Promise<void> {
  if (!appId.value || !key.value) return
  loading.value = true
  notFound.value = false
  const target = key.value
  try {
    const [a, o] = await Promise.all([
      applicationApi.get(appId.value, tenantId.value),
      appServiceApi.overview(appId.value, tenantId.value),
    ])
    if (target !== key.value) return
    app.value = a
    allRows.value = o.services
    pendingKeys.value = o.pendingKeys
    deps.value = o.dependencies
    appSettings.value = o.settings?.settings ?? {}
    const r = o.services.find((s) => s.key === target) ?? null
    row.value = r
    if (!r) { notFound.value = true; return }
    if (opts.keepForm) {
      version.value = r.version
    } else {
      version.value = r.version
      fillForm(r.desired)
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

// ---------- 保存 / 冲突 ----------
const saving = ref(false)
const conflict = ref(false)
const saveError = ref('')

async function save(): Promise<void> {
  if (version.value == null || !row.value) return
  saving.value = true
  saveError.value = ''
  try {
    const out = await appServiceApi.update({
      appId: appId.value,
      serviceKey: key.value,
      desired: buildDesired(),
      version: version.value,
      tenantId: tenantId.value,
    })
    conflict.value = false
    ElMessage.success('已保存，发布后生效')
    fillForm(out.desired)
    version.value = out.version
    await load({ keepForm: true })
    baseline.value = snapshotOf()
  } catch (e) {
    const msg = e instanceof Error ? e.message : '保存失败'
    saveError.value = msg
    // 用户输入原样保留在表单里；冲突另给明确的两条路
    conflict.value = msg.includes('冲突')
  } finally {
    saving.value = false
  }
}
/** 他人改过：丢弃我的修改，载入最新 */
async function discardAndReload(): Promise<void> {
  conflict.value = false
  saveError.value = ''
  await load()
}
/** 他人改过：保留我的修改，只取最新版本号，再点保存即覆盖对方 */
async function keepMineWithLatest(): Promise<void> {
  conflict.value = false
  saveError.value = ''
  await load({ keepForm: true })
  ElMessage.info('已取得最新版本，你的修改仍在表单中；再次保存将覆盖对方的改动')
}
function resetForm(): void {
  if (row.value) fillForm(row.value.desired)
  conflict.value = false
  saveError.value = ''
}

// ---------- 导航 / 移除 ----------
function back(): void {
  router.push(`/applications/${appId.value}`)
}
async function remove(): Promise<void> {
  const r = row.value
  if (!r || r.version == null) return
  const published = r.state !== 'NOT_RELEASED'
  try {
    await ElMessageBox.confirm(
      published ? `移除服务「${r.key}」？标记待删除，下次发布时勾选后才从集群移除。` : `移除服务「${r.key}」？尚未发布，直接删除配置。`,
      '移除服务', { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await appServiceApi.remove({ appId: appId.value, serviceKey: r.key, version: r.version, tenantId: tenantId.value })
    // 接口不返回走了哪条路径（直接删行 / 标记待删除），文案对两种都成立
    ElMessage.success('已移除；若该服务已发布过，需在下次发布时勾选才会从集群删除')
    back()
  } catch {
    // 失败（多半是冲突）不能清掉表单里没保存的输入
    await load({ keepForm: true })
  }
}

// ---------- Pod 与日志 / 监控 ----------
const tab = ref('overview')
const cfgTab = ref('basic')
const wl = ref<WorkloadDetail | null>(null)
const pods = ref<K8sPod[]>([])
const podLoading = ref(false)
const podError = ref(false)
const ctx = computed(() => ({
  tenantId: tenantId.value ?? '',
  clusterId: app.value?.clusterId ?? '',
  namespace: app.value?.namespace ?? '',
}))
const runtimeReady = computed(() => !!row.value && row.value.state !== 'NOT_RELEASED' && !!app.value)

/** 线上快照里本服务的期望配置与应用设置（概览三行只看它们：改了没发布的不算） */
const liveDesired = ref<AppServiceDesired | null>(null)
const liveSettings = ref<AppSettings | null>(null)
async function loadLiveDesired(): Promise<void> {
  const no = app.value?.liveReleaseNo
  if (!runtimeReady.value || no == null) { liveDesired.value = null; liveSettings.value = null; return }
  try {
    const r = await appReleaseApi.get(appId.value, no, tenantId.value ?? undefined)
    liveDesired.value = r?.snapshot?.services?.[key.value] ?? null
    liveSettings.value = r?.snapshot?.app ?? null
  } catch {
    liveDesired.value = null
    liveSettings.value = null
  }
}
watch([runtimeReady, () => app.value?.liveReleaseNo, key], () => { void loadLiveDesired() }, { immediate: true })

async function loadPods(): Promise<void> {
  if (!runtimeReady.value) return
  podLoading.value = true
  podError.value = false
  try {
    const d = await workloadApi.get(workloadName.value, ctx.value)
    wl.value = d
    pods.value = (await podApi.list({ ...ctx.value, labelSelector: podLabelSelector(d.selector, workloadName.value) })) ?? []
  } catch {
    podError.value = true
  } finally {
    podLoading.value = false
  }
}
watch([tab, runtimeReady], ([t, ok]) => { if ((t === 'pods' || t === 'monitor') && ok && !wl.value) void loadPods() })

// ---------- 对外访问（读线上 Service：自动分配的 NodePort 只有集群知道） ----------
const liveSvc = ref<K8sService | null>(null)
const addr = useNodeAddresses()
async function loadLiveService(): Promise<void> {
  liveSvc.value = null
  if (!runtimeReady.value || row.value?.kind !== 'SERVICE') return
  void addr.load(ctx.value.clusterId || null)
  try {
    liveSvc.value = await serviceApi.get(workloadName.value, ctx.value)
  } catch {
    // 读不到就不显示访问地址（拦截器已提示）
  }
}
const liveNodePort = computed(() => (liveSvc.value?.type === 'NodePort' ? liveSvc.value.ports?.[0]?.nodePort ?? null : null))
const nodePortUrl = computed(() => (liveNodePort.value && addr.defaultHost
  ? `http://${addr.defaultHost}:${liveNodePort.value}` : ''))
watch([runtimeReady, workloadName], ([ok]) => { if (ok) void loadLiveService(); else liveSvc.value = null }, { immediate: true })

const logVisible = ref(false)
const logPod = ref<K8sPod | null>(null)
const allLogsVisible = ref(false)
function openLogs(p: K8sPod): void { logPod.value = p; logVisible.value = true }
function readyCount(p: K8sPod): string {
  const cs = (p.containerDetails ?? []).filter((c) => !c.init)
  return `${cs.filter((c) => c.ready).length}/${cs.length}`
}
function openWorkloadPage(): void {
  if (!app.value) return
  // 资源页按顶栏命名空间取数：先把上下文对到应用所在的命名空间
  setNamespace(app.value.namespace)
  router.push(`/resources/workloads/detail?name=${encodeURIComponent(workloadName.value)}`)
}
function openPodPage(p: K8sPod): void {
  if (!app.value || !p.name) return
  setNamespace(app.value.namespace)
  router.push(`/resources/pods/detail?name=${encodeURIComponent(p.name)}&namespace=${encodeURIComponent(app.value.namespace)}`)
}

const selfRefs = computed(() => allRows.value.filter((s) => s.kind === 'SERVICE' && s.key !== key.value).map((s) => s.key))

onMounted(async () => {
  await ensure()
  await load()
})
watch(key, () => { wl.value = null; pods.value = []; tab.value = 'overview'; void load() })
</script>

<template>
  <div class="app-svc" v-loading="loading && !row">
    <PageHeader :title="`服务 ${key}`" :description="app ? `${app.displayName || app.name} · ${app.namespace}` : undefined">
      <AppReleaseGuide />
      <el-button @click="back">返回应用</el-button>
      <el-button type="danger" plain :disabled="!row || readonly" @click="remove">移除服务</el-button>
    </PageHeader>

    <EmptyState v-if="notFound" title="服务不存在" description="它可能已被移除，或应用中没有这个名称。">
      <el-button @click="back">返回应用</el-button>
    </EmptyState>

    <el-tabs v-else-if="row && app" v-model="tab" class="app-svc__tabs">
      <!-- 概览 -->
      <el-tab-pane label="概览" name="overview">
        <section class="panel app-svc__card">
          <div class="app-svc__kv"><span class="k">状态</span>
            <StatusBadge :label="STATE_META[row.state]?.text ?? row.state" :type="STATE_META[row.state]?.type ?? 'neutral'" />
          </div>
          <div class="app-svc__kv"><span class="k">类型</span>{{ row.kind === 'SERVICE' ? '服务（有端口）' : '后台进程（无端口）' }}</div>
          <div class="app-svc__kv"><span class="k">期望镜像</span><code>{{ row.image }}</code></div>
          <div class="app-svc__kv"><span class="k">线上镜像</span><code v-if="row.liveImage">{{ row.liveImage }}</code><span v-else class="muted">未发布</span></div>
          <div class="app-svc__kv"><span class="k">副本数</span>{{ row.replicas ?? '—' }}
            <span v-if="liveDesired?.autoscalingEnabled" class="muted">（由 HPA 管理）</span>
          </div>
          <div class="app-svc__kv"><span class="k">待发布</span>
            <el-tag v-if="row.pending" type="warning" effect="light" size="small">{{ PENDING_TEXT[row.pending] }}</el-tag>
            <span v-else class="muted">无</span>
          </div>
          <div v-if="row.kind === 'SERVICE'" class="app-svc__kv"><span class="k">集群内地址</span>
            <code>{{ workloadName }}:{{ row.desired.port }}</code>
          </div>
          <div v-if="row.kind === 'SERVICE'" class="app-svc__kv"><span class="k">对外访问</span>
            <template v-if="row.ingress">
              <span>网关 {{ row.ingress.protocol }} 端口 <code>{{ row.ingress.port }}</code>（任一节点 IP:{{ row.ingress.port }}）</span>
              <el-link v-if="gatewayAddress && row.ingress.protocol === 'HTTP'" :href="gatewayAddress" target="_blank"
                       rel="noopener" type="primary" :underline="false">{{ gatewayAddress }}</el-link>
              <code v-else-if="gatewayAddress">{{ gatewayAddress }}</code>
              <el-tag :type="gatewayStatus.type" effect="light" size="small">{{ gatewayStatus.text }}</el-tag>
              <span v-if="row.ingress.conflicted || (row.ingress.programmed === false && row.ingress.message)" class="muted">
                {{ row.ingress.reason }} {{ row.ingress.message }}
              </span>
            </template>
            <template v-else-if="liveNodePort">
              <span>NodePort <code>{{ liveNodePort }}</code>（任一节点 IP:{{ liveNodePort }}）</span>
              <el-link v-if="nodePortUrl" :href="nodePortUrl" target="_blank" rel="noopener" type="primary" :underline="false">{{ nodePortUrl }}</el-link>
            </template>
            <span v-else-if="runtimeReady" class="muted">不对外暴露（仅集群内可访问）</span>
            <span v-else class="muted">未发布</span>
          </div>
          <div class="app-svc__kv"><span class="k">工作负载</span>
            <code>{{ workloadName }}</code>
            <el-button v-if="runtimeReady" link type="primary" @click="openWorkloadPage">查看工作负载详情</el-button>
          </div>
          <AppServiceRuntimeRows v-if="runtimeReady" :live="liveDesired" :live-settings="liveSettings" :workload-name="workloadName" :ctx="ctx" />
          <el-alert v-if="orphan" class="app-svc__alert" type="warning" :closable="false" show-icon
                    title="线上有这个服务，但应用里已没有它的配置记录。配置不可编辑，可在发布时勾选删除。" />
        </section>
      </el-tab-pane>

      <!-- 配置 -->
      <el-tab-pane label="配置" name="config">
        <el-alert v-if="orphan" class="app-svc__alert" type="warning" :closable="false" show-icon
                  title="孤儿服务：配置来自线上快照，只读，不能保存。" />
        <el-alert v-else-if="pendingDelete" class="app-svc__alert" type="warning" :closable="false" show-icon
                  title="该服务已标记待删除，配置只读；下次发布勾选后会从集群移除。" />
        <el-alert v-else-if="!canWrite" class="app-svc__alert" type="info" :closable="false" show-icon
                  title="你没有应用写权限，配置只读。" />
        <el-alert v-if="conflict" class="app-svc__alert" type="error" :closable="false" show-icon
                  title="配置冲突：服务已被他人修改">
          <div>你的修改还在表单里，没有丢。可以载入最新版本（放弃你的修改），或保留你的修改并取得最新版本号后再次保存（会覆盖对方的改动）。</div>
          <div class="app-svc__conflict-actions">
            <el-button size="small" @click="discardAndReload">载入最新，放弃我的修改</el-button>
            <el-button size="small" type="primary" @click="keepMineWithLatest">保留我的修改，取最新版本</el-button>
          </div>
        </el-alert>
        <el-alert v-else-if="saveError" class="app-svc__alert" type="error" :closable="false" show-icon :title="saveError" />

        <el-tabs v-model="cfgTab" type="card" class="app-svc__cfg">
          <el-tab-pane label="基本" name="basic">
            <el-form label-width="110px" :disabled="readonly" @submit.prevent>
              <el-form-item label="镜像仓库" required><el-input v-model="form.imageRepo" placeholder="不含 tag" /></el-form-item>
              <el-form-item label="镜像 tag" required>
                <AppTagInput v-model="form.imageTag" :repo="form.imageRepo" />
                <div v-if="row?.newTag && row.newTag !== form.imageTag" class="app-svc__newtag">
                  镜像仓库里有更晚推送的 tag「{{ row.newTag }}」
                  <el-link type="warning" :underline="false" :disabled="readonly" @click="form.imageTag = row.newTag">采用</el-link>
                </div>
              </el-form-item>
              <el-form-item label="端口">
                <el-input-number v-model="form.port" :min="1" :max="65535" :controls="false" placeholder="留空" />
                <span class="app-svc__tip">填了端口就是服务，清空则变为后台进程</span>
              </el-form-item>
              <el-form-item v-if="form.port" label="对外暴露">
                <el-radio-group v-model="exposeMode">
                  <el-radio value="none">不暴露</el-radio>
                  <el-radio value="nodeport">NodePort</el-radio>
                  <el-tooltip :disabled="!gatewayLocked" :content="flagReasons.routes" placement="top">
                    <span class="app-svc__radio-wrap">
                      <el-radio value="gateway-http" :disabled="gatewayLocked">网关 HTTP</el-radio>
                      <el-radio value="gateway-tcp" :disabled="gatewayLocked">网关 TCP</el-radio>
                    </span>
                  </el-tooltip>
                </el-radio-group>
                <span v-if="exposeMode === 'nodeport'" class="app-svc__tip">NodePort：每台节点都开放同一个端口，集群外用「节点 IP:端口」访问</span>
                <span v-else-if="exposeMode === 'gateway-http' || exposeMode === 'gateway-tcp'" class="app-svc__tip">
                  经集群入口网关转发，集群外用「节点 IP:端口」访问。网关 HTTP 只支持 HTTP，HTTPS 服务请选网关 TCP（TLS 透传）；
                  端口由平台分配，改暴露方式为不暴露后端口会被回收
                </span>
              </el-form-item>
              <el-form-item v-if="form.port && (exposeMode === 'gateway-http' || exposeMode === 'gateway-tcp')" label="对外端口">
                <span v-if="row?.ingress && isGatewayExpose(saved?.expose)">
                  <code>{{ row.ingress.port }}</code>（保存时由平台分配，之后不变）
                </span>
                <span v-else class="muted">保存后分配</span>
              </el-form-item>
              <el-form-item v-if="form.port && exposeMode === 'nodeport'" label="NodePort 端口">
                <el-input-number v-model="nodePortModel" :min="30000" :max="32767" :controls="false" placeholder="留空由平台分配" />
                <span class="app-svc__tip">30000-32767；留空则保存时由平台挑一个空闲端口填上，之后不变。已被其他服务占用的端口保存时会被拒绝</span>
              </el-form-item>
              <el-form-item label="副本数">
                <el-input-number v-model="form.replicas" :min="0" :max="100" :disabled="readonly || !!form.autoscalingEnabled" />
                <span v-if="form.autoscalingEnabled" class="app-svc__tip">副本数由 HPA 在 {{ form.minReplicas ?? 1 }}–{{ form.maxReplicas ?? '?' }} 间调整（见「高级」）</span>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="环境变量" name="env">
            <AppEnvEditor v-model="form.env" :dependencies="deps" :service-keys="selfRefs" :ctx="ctx" :disabled="readonly" />
          </el-tab-pane>

          <el-tab-pane label="配置文件" name="files">
            <div class="app-svc__files-hint">
              每个文件以只读方式挂载到容器里的指定路径（ConfigMap 卷，按文件挂，不覆盖所在目录的其他文件）；内容改了发布时会滚动 Pod。
              {{ fileRefHint }}
            </div>
            <div v-for="(f, i) in form.configFiles ?? []" :key="i" class="app-svc__file">
              <div class="app-svc__file-head">
                <el-input v-model="f.path" :disabled="readonly" placeholder="容器内绝对路径，如 /etc/app/application.yaml" />
                <el-button link type="danger" :disabled="readonly" @click="removeFile(i)">删除</el-button>
              </div>
              <el-input
                v-model="f.content"
                type="textarea"
                :disabled="readonly"
                :autosize="{ minRows: 4, maxRows: 16 }"
                class="app-svc__file-body"
                placeholder="文件内容"
              />
              <div v-if="fileProblems[i]" class="app-svc__file-err">{{ fileProblems[i] }}</div>
            </div>
            <EmptyState v-if="!(form.configFiles ?? []).length" title="没有配置文件" description="需要把配置文件放进容器时在这里添加，例如 nginx.conf、application.yaml。" />
            <el-button v-if="!readonly" plain class="app-svc__file-add" @click="addFile">+ 添加配置文件</el-button>
          </el-tab-pane>

          <el-tab-pane label="资源与命令" name="resources">
            <el-form label-width="110px" :disabled="readonly" @submit.prevent>
              <el-form-item label="CPU 请求"><AppQuantityInput v-model="form.cpuRequest" kind="cpu" :disabled="readonly" placeholder="如 0.1，留空不设" /></el-form-item>
              <el-form-item label="CPU 上限"><AppQuantityInput v-model="form.cpuLimit" kind="cpu" :disabled="readonly" placeholder="如 1，留空不设" /></el-form-item>
              <el-form-item label="内存请求"><AppQuantityInput v-model="form.memoryRequest" kind="memory" :disabled="readonly" placeholder="如 128，留空不设" /></el-form-item>
              <el-form-item label="内存上限">
                <AppQuantityInput v-model="form.memoryLimit" kind="memory" :disabled="readonly" placeholder="如 512，留空不设" />
                <div v-if="resourceProblem" class="app-svc__file-err">{{ resourceProblem }}</div>
              </el-form-item>
              <el-form-item label="启动命令"><StringListEditor v-model="form.command" :disabled="readonly" placeholder="一个参数，如 sh；留空则用镜像默认" /></el-form-item>
              <el-form-item label="启动参数"><StringListEditor v-model="form.args" :disabled="readonly" placeholder="一个参数，如 -c" /></el-form-item>
              <el-form-item label="拉取策略">
                <el-select v-model="form.imagePullPolicy" clearable placeholder="跟随默认" style="width: 200px">
                  <el-option label="Always" value="Always" />
                  <el-option label="IfNotPresent" value="IfNotPresent" />
                  <el-option label="Never" value="Never" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="高级" name="advanced">
            <el-form label-width="110px" :disabled="readonly" @submit.prevent>
              <el-form-item v-if="form.port" label="持久化"><el-switch v-model="form.persistenceEnabled" /></el-form-item>
              <template v-if="form.port && form.persistenceEnabled">
                <el-form-item label="存储大小"><AppQuantityInput v-model="form.storageSize" kind="storage" :disabled="readonly" placeholder="如 10" /></el-form-item>
                <el-form-item label="存储类"><el-input v-model="form.storageClass" placeholder="留空使用默认" /></el-form-item>
                <el-form-item label="挂载路径"><el-input v-model="form.mountPath" placeholder="如 /data" /></el-form-item>
              </template>
              <el-form-item label="自动扩缩容">
                <el-tooltip :disabled="!hpaLocked" :content="flagReasons['hpa-resource-metrics']" placement="top">
                  <span><el-switch v-model="form.autoscalingEnabled" :disabled="hpaLocked" /></span>
                </el-tooltip>
                <span v-if="hpaLocked" class="app-svc__tip warn">{{ flagReasons['hpa-resource-metrics'] }}</span>
              </el-form-item>
              <template v-if="form.autoscalingEnabled">
                <el-form-item label="最小副本"><el-input-number v-model="form.minReplicas" :min="1" /></el-form-item>
                <el-form-item label="最大副本"><el-input-number v-model="form.maxReplicas" :min="1" /></el-form-item>
                <el-form-item label="目标 CPU %"><el-input-number v-model="form.targetCpu" :min="1" :max="100" placeholder="默认 60" /></el-form-item>
                <el-form-item label="目标内存 %">
                  <el-input-number v-model="form.targetMemory" :min="1" :max="100" placeholder="不设" />
                  <span class="app-svc__tip">按内存请求的百分比；不填只按 CPU 扩缩。需设置内存请求</span>
                </el-form-item>
              </template>
              <!-- 后端缺省=开启（!FALSE.equals）：没写 probeEnabled 就是开；切换时一律写显式 true/false -->
              <el-form-item label="健康探针">
                <el-switch :model-value="form.probeEnabled !== false" @update:model-value="(v: string | number | boolean) => (form.probeEnabled = v === true)" />
              </el-form-item>
              <el-form-item v-if="form.probeEnabled !== false" label="探针类型">
                <el-radio-group v-model="probeTypeModel">
                  <el-radio value="http">HTTP</el-radio>
                  <el-radio value="tcp">TCP 端口</el-radio>
                </el-radio-group>
                <span class="app-svc__tip">HTTP 请求探针路径；TCP 只检查服务端口能否连上（非 HTTP 服务用）</span>
              </el-form-item>
              <el-form-item v-if="form.probeEnabled !== false && probeTypeModel === 'http'" label="探针路径"><el-input v-model="form.probePath" placeholder="默认 /，如 /healthz" /></el-form-item>
              <el-form-item label="暴露指标">
                <el-tooltip :disabled="!metricsLocked || !form.port" :content="flagReasons.servicemonitor" placement="top">
                  <span><el-switch v-model="form.metricsEnabled" :disabled="!!form.port && metricsLocked" /></span>
                </el-tooltip>
                <span v-if="form.port && metricsLocked" class="app-svc__tip warn">{{ flagReasons.servicemonitor }}</span>
              </el-form-item>
              <el-form-item v-if="form.metricsEnabled" label="指标路径"><el-input v-model="form.metricsPath" placeholder="如 /metrics" /></el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="可观测" name="observability">
            <AppServiceOtelForm v-model="form.otel" :original="saved?.otel" :workload-name="workloadName"
                                :readonly="readonly" :available="flagAvailable" :reasons="flagReasons"
                                :app-tracing="!!appSettings.tracing" />
          </el-tab-pane>
        </el-tabs>

        <div class="app-svc__save">
          <el-button type="primary" :loading="saving" :disabled="readonly || !dirty || filesInvalid" :title="filesInvalid ? '配置文件有问题，见「配置文件」页签' : ''" @click="save">保存</el-button>
          <el-button :disabled="readonly || !dirty" @click="resetForm">还原</el-button>
          <span class="muted">保存只改期望配置，发布后才会生效到集群。</span>
        </div>
      </el-tab-pane>

      <!-- Pod 与日志 -->
      <el-tab-pane label="Pod 与日志" name="pods" lazy>
        <EmptyState v-if="!runtimeReady" title="尚未发布" description="服务发布后，这里显示它的 Pod 和日志。" />
        <EmptyState v-else-if="podError" title="读取失败" description="工作负载读取失败，可能线上还没有创建完成。">
          <el-button @click="loadPods">重试</el-button>
        </EmptyState>
        <template v-else>
          <div class="app-svc__bar">
            <el-button :loading="podLoading" @click="loadPods">刷新</el-button>
            <el-button :disabled="!pods.length" @click="allLogsVisible = true">聚合日志</el-button>
          </div>
          <el-table v-loading="podLoading" :data="pods" size="small" empty-text="当前没有 Pod">
            <el-table-column label="名称" min-width="300">
              <template #default="{ row: p }"><el-button link type="primary" @click="openPodPage(p)"><code>{{ p.name }}</code></el-button></template>
            </el-table-column>
            <el-table-column label="就绪" width="80"><template #default="{ row: p }">{{ readyCount(p) }}</template></el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row: p }"><StatusBadgeTip :label="p.phase ?? 'Unknown'" :type="podPhaseType(p.phase)" :reason="p.statusReason" /></template>
            </el-table-column>
            <el-table-column label="重启" width="70"><template #default="{ row: p }">{{ p.restarts ?? 0 }}</template></el-table-column>
            <el-table-column label="节点" min-width="120"><template #default="{ row: p }">{{ p.nodeName ?? '—' }}</template></el-table-column>
            <el-table-column label="存活" min-width="240">
              <template #default="{ row: p }">{{ fmtDate(p.creationTime) }}（{{ fmtAge(p.creationTime) }}）</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row: p }"><el-button link type="primary" @click="openLogs(p)">日志</el-button></template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <!-- 监控 -->
      <el-tab-pane label="监控" name="monitor" lazy>
        <EmptyState v-if="!runtimeReady" title="尚未发布" description="服务发布后才有监控数据。" />
        <MetricsPanel
          v-else-if="wl"
          dimension="workload"
          :name="wl.name"
          :kind="wl.kind"
          :tenant-id="ctx.tenantId"
          :cluster-id="ctx.clusterId"
          :namespace="ctx.namespace"
        />
        <EmptyState v-else-if="podError" title="读取失败" description="工作负载读取失败，无法取得监控。" />
        <div v-else class="muted">加载中…</div>
      </el-tab-pane>
    </el-tabs>

    <PodLogDialog v-if="app" v-model="logVisible" :pod="logPod" :tenant-id="ctx.tenantId" :cluster-id="ctx.clusterId" :namespace="ctx.namespace" />
    <WorkloadLogsDialog v-if="app" v-model="allLogsVisible" :pods="pods" :tenant-id="ctx.tenantId" :cluster-id="ctx.clusterId" :namespace="ctx.namespace" />
  </div>
</template>

<style scoped>
.muted { color: var(--text-3); font-size: 12.5px; }
.app-svc__tabs { margin: 0 10px; }
.app-svc__card { padding: 16px 18px; }
.app-svc__kv { display: flex; align-items: center; gap: 12px; padding: 6px 0; font-size: 13.5px; }
.app-svc__kv .k { width: 90px; flex-shrink: 0; color: var(--text-3); }
.app-svc__alert { margin-bottom: 12px; }
.app-svc__conflict-actions { margin-top: 8px; }
.app-svc__cfg { margin-top: 4px; }
.app-svc__tip { margin-left: 10px; color: var(--text-3); font-size: 12px; }
.app-svc__radio-wrap { display: inline-flex; gap: 0; }
.app-svc__radio-wrap .el-radio { margin-right: 30px; }
.app-svc__tip.warn { color: var(--el-color-warning); }
.app-svc__save { display: flex; align-items: center; gap: 10px; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.app-svc__bar { display: flex; gap: 8px; margin-bottom: 10px; }
.app-svc__files-hint { margin-bottom: 12px; color: var(--text-3); font-size: 12.5px; line-height: 1.6; }
.app-svc__file { margin-bottom: 14px; padding: 10px 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.app-svc__file-head { display: flex; align-items: center; gap: 10px; }
.app-svc__file-body { margin-top: 8px; font-family: var(--el-font-family-mono, monospace); }
.app-svc__file-err { margin-top: 6px; color: var(--el-color-danger); font-size: 12px; }
.app-svc__file-add { margin-top: 4px; }
.app-svc__newtag { width: 100%; font-size: 12px; color: var(--el-color-warning); margin-top: 4px; }
</style>
