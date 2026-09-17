<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { serviceMonitorApi, serviceApi, serviceMonitorRelabelApi } from '@/api'
import type { K8sService, K8sServiceMonitor, K8sServicePort, K8sSmEndpoint, K8sSmMatchExpression, K8sSmRelabeling } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, namespacesOfCluster, load } = useResourceContext()

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>(route.query.name as string | null)

const ctx3 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))
const ctx2 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
}))

const RELABEL_ACTIONS = [
  { value: 'replace', desc: '匹配则用 replacement 写入 targetLabel（默认）' },
  { value: 'keep', desc: '拼接值匹配才保留，否则丢弃' },
  { value: 'drop', desc: '拼接值匹配即丢弃' },
  { value: 'hashmod', desc: '对拼接值哈希取模 modulus，结果写入 targetLabel' },
  { value: 'labelmap', desc: '按标签名匹配改名（不看值）' },
  { value: 'labeldrop', desc: '按标签名匹配删除命中的标签' },
  { value: 'labelkeep', desc: '按标签名匹配保留命中的标签' },
] as const
const SCHEMES = ['http', 'https']

// ---------- 表单模型 ----------
interface RelabelingRow {
  sourceLabels: string[] // 源标签名列表（可多个）；编辑态由 Prometheus discovery 提供候选，均可手输
  targetLabel: string
  regex: string
  replacement: string
  separator: string
  modulus: number | null
  action: string
}
interface EndpointRow {
  port: string
  path: string
  interval: string
  scrapeTimeout: string
  scheme: string // ''=默认 http / https
  paramsRows: { key: string; values: string }[] // values 逗号分隔 → 提交时拆成数组
  basicAuthEnabled: boolean
  basicUsernameName: string
  basicUsernameKey: string
  basicPasswordName: string
  basicPasswordKey: string
  bearerEnabled: boolean
  bearerName: string
  bearerKey: string
  bearerTokenFile: string // 透传保留（UI 不编辑），避免 atomic list 整体替换时丢失
  tlsEnabled: boolean
  tlsInsecureSkipVerify: boolean
  tlsServerName: string
  relabelings: RelabelingRow[]
  metricRelabelings: RelabelingRow[]
}

function newRelabeling(): RelabelingRow {
  return { sourceLabels: [], targetLabel: '', regex: '', replacement: '', separator: '', modulus: null, action: 'replace' }
}
function newEndpoint(): EndpointRow {
  return {
    port: '', path: '', interval: '', scrapeTimeout: '', scheme: '',
    paramsRows: [],
    basicAuthEnabled: false, basicUsernameName: '', basicUsernameKey: '', basicPasswordName: '', basicPasswordKey: '',
    bearerEnabled: false, bearerName: '', bearerKey: '', bearerTokenFile: '',
    tlsEnabled: false, tlsInsecureSkipVerify: false, tlsServerName: '',
    relabelings: [], metricRelabelings: [],
  }
}

interface MatchExpressionRow { key: string; operator: string; values: string } // values 逗号分隔 → 提交时拆成数组
function newMatchExpr(): MatchExpressionRow { return { key: '', operator: 'In', values: '' } }
const MATCH_EXPR_OPERATORS = ['In', 'NotIn', 'Exists', 'DoesNotExist']

const form = reactive({
  name: '',
  labels: {} as Record<string, string>,
  matchLabels: {} as Record<string, string>, // 回显用（只读展示）；后端据 serviceRef 解析覆盖
  serviceRef: null as { name: string; namespace: string } | null, // 选择的目标 Service（覆盖选择器）
  matchExpressions: [] as MatchExpressionRow[], // spec.selector.matchExpressions：按表达式选 Service（与 matchLabels ANDed）
  nsMatchNames: [] as string[],
  jobLabel: '',
  podTargetLabels: [] as string[],
  sampleLimit: null as number | null,
  targetLimit: null as number | null,
  labelLimit: null as number | null,
  bodySizeLimit: '',
  attachNode: false,
  endpoints: [newEndpoint()] as EndpointRow[],
})

// ---------- 目标 Service 下拉（#5）：按命名空间集合查询 Service 列表，供选择后由后端解析成选择器 ----------
const svcOptions = ref<{ name: string; namespace: string; ports: K8sServicePort[] }[]>([])
const svcLoading = ref(false)

/** 需查询的命名空间：选了 matchNames 就查那些（多命名空间），否则仅本命名空间 */
const targetNamespaces = computed<string[]>(() => {
  const ns = cleanList(form.nsMatchNames)
  return ns.length ? ns : [state.namespace!]
})

async function loadServices(): Promise<void> {
  if (!ready.value) return
  svcLoading.value = true
  try {
    const lists = await Promise.all(
      targetNamespaces.value.map((ns) =>
        serviceApi.list({ tenantId: state.tenantId!, clusterId: state.clusterId!, namespace: ns }).catch(() => [] as K8sService[]),
      ),
    )
    svcOptions.value = lists.flat().map((s) => ({ name: s.name, namespace: s.namespace, ports: s.ports ?? [] }))
  } finally {
    svcLoading.value = false
  }
}

// el-select 绑定值：`namespace/name`（Service 名不含 /，可安全拆分）
const svcSelectValue = computed<string | ''>({
  get: () => (form.serviceRef ? `${form.serviceRef.namespace}/${form.serviceRef.name}` : ''),
  set: (v) => {
    if (!v) { form.serviceRef = null; return }
    const idx = v.indexOf('/')
    form.serviceRef = { namespace: v.slice(0, idx), name: v.slice(idx + 1) }
  },
})

/** 当前所选 Service 的端口（供端点 Port 下拉）：有名字用名字，否则用端口号 */
const portOptions = computed(() => {
  const ref = form.serviceRef
  if (!ref) return [] as { value: string; label: string }[]
  const svc = svcOptions.value.find((s) => s.name === ref.name && s.namespace === ref.namespace)
  return (svc?.ports ?? []).map((p) => ({
    value: p.name ? String(p.name) : String(p.port),
    label: p.name ? `${p.name}（${p.port}/${p.protocol ?? 'TCP'}）` : `${p.port}/${p.protocol ?? 'TCP'}`,
  }))
})

// 上下文就绪或命名空间集合变化时重新拉取 Service 列表（immediate：SPA 内跳转时 ready 可能已为 true）
watch([() => ready.value, () => targetNamespaces.value.join('|')], ([r]) => { if (r) void loadServices() }, { immediate: true })

// ---------- 编辑回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

function fromRelabeling(r: K8sSmRelabeling): RelabelingRow {
  return {
    sourceLabels: [...(r.sourceLabels ?? [])],
    targetLabel: r.targetLabel ?? '',
    regex: r.regex ?? '',
    replacement: r.replacement ?? '',
    separator: r.separator ?? '',
    modulus: r.modulus ?? null,
    action: r.action ?? 'replace',
  }
}

/** CRD 时长（如 "30s"）→ 秒数纯数字字符串；非纯秒数（如 1m30s）留空让用户重填 */
function toSecondsNum(v: string | null | undefined): string {
  if (!v) return ''
  const t = v.trim()
  const m = t.match(/^(\d+(?:\.\d+)?)s$/)
  const num = m?.[1]
  if (num) return num
  return /^\d+(?:\.\d+)?$/.test(t) ? t : ''
}

/** 大小字符串（如 "50MB"）→ 纯数字字符串；无数字留空 */
function toSizeNum(v: string | null | undefined): string {
  if (!v) return ''
  const m = v.trim().match(/^(\d+(?:\.\d+)?)/)
  return m?.[1] ?? ''
}

function fromEndpoint(e: K8sSmEndpoint): EndpointRow {
  const base = newEndpoint()
  base.port = e.port ?? ''
  base.path = e.path ?? '/metrics'
  base.interval = toSecondsNum(e.interval) || '30'
  base.scrapeTimeout = toSecondsNum(e.scrapeTimeout)
  base.scheme = e.scheme ?? ''
  const params = e.params ?? {}
  base.paramsRows = Object.entries(params).map(([k, vals]) => ({ key: k, values: (vals ?? []).join(',') }))
  if (e.basicAuth) {
    base.basicAuthEnabled = true
    base.basicUsernameName = e.basicAuth.username?.name ?? ''
    base.basicUsernameKey = e.basicAuth.username?.key ?? ''
    base.basicPasswordName = e.basicAuth.password?.name ?? ''
    base.basicPasswordKey = e.basicAuth.password?.key ?? ''
  }
  if (e.bearerTokenSecret) {
    base.bearerEnabled = true
    base.bearerName = e.bearerTokenSecret.name ?? ''
    base.bearerKey = e.bearerTokenSecret.key ?? ''
  }
  base.bearerTokenFile = e.bearerTokenFile ?? ''
  if (e.tlsConfig) {
    base.tlsEnabled = true
    base.tlsInsecureSkipVerify = e.tlsConfig.insecureSkipVerify === true
    base.tlsServerName = e.tlsConfig.serverName ?? ''
  }
  base.relabelings = (e.relabelings ?? []).map(fromRelabeling)
  base.metricRelabelings = (e.metricRelabelings ?? []).map(fromRelabeling)
  return base
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await serviceMonitorApi.get(editing.value, ctx3.value)
    form.name = d.name
    form.labels = { ...(d.labels ?? {}) }
    form.matchLabels = { ...(d.matchLabels ?? {}) }
    form.serviceRef = null // 编辑时仅回显选择器；重新选择 Service 才覆盖
    form.matchExpressions = (d.matchExpressions ?? []).map((m) => ({
      key: m.key ?? '',
      operator: m.operator ?? 'In',
      values: (m.values ?? []).join(','),
    }))
    form.nsMatchNames = [...(d.namespaceSelector?.matchNames ?? [])]
    form.jobLabel = d.jobLabel ?? ''
    form.podTargetLabels = [...(d.podTargetLabels ?? [])]
    form.sampleLimit = d.sampleLimit ?? null
    form.targetLimit = d.targetLimit ?? null
    form.labelLimit = d.labelLimit ?? null
    form.bodySizeLimit = toSizeNum(d.bodySizeLimit)
    form.attachNode = d.attachMetadata?.node === true
    const eps = (d.endpoints ?? []).map(fromEndpoint)
    form.endpoints = eps.length ? eps : [newEndpoint()]
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

// ---------- Relabeling sourceLabels 候选（编辑态，来自集群 Prometheus discovery） ----------
const EMPTY_DISCOVERY = { relabeling: [] as string[], metricRelabeling: [] as string[] }
const discovery = ref<{ relabeling: string[]; metricRelabeling: string[] }>({ ...EMPTY_DISCOVERY })
/** Relabeling：服务发现原始标签（discoveredLabels，__meta_* / __address__ …） */
const relabelSourceOptions = computed(() => discovery.value.relabeling)
/** MetricRelabeling：恒含 __name__ + 最终目标标签（labels） */
const metricRelabelSourceOptions = computed(() => ['__name__', ...discovery.value.metricRelabeling.filter((l) => l !== '__name__')])

async function loadDiscoveryLabels(): Promise<void> {
  if (!editing.value || !ready.value) return
  try {
    discovery.value = await serviceMonitorRelabelApi.labels({
      clusterId: state.clusterId!, namespace: state.namespace!, name: editing.value,
    }) ?? { ...EMPTY_DISCOVERY }
  } catch {
    discovery.value = { ...EMPTY_DISCOVERY } // Prometheus 不可达 → 无候选（下拉仍可手输）
  }
}

// ---------- MetricRelabeling regex 的 __name__（指标名）候选（编辑态，scoped 到本 SM 活跃 target） ----------
const metricNameOptions = ref<string[]>([])
async function loadMetricNames(): Promise<void> {
  if (!editing.value || !ready.value) return
  try {
    metricNameOptions.value = await serviceMonitorRelabelApi.metricNames({
      clusterId: state.clusterId!, namespace: state.namespace!, name: editing.value,
    }) ?? []
  } catch {
    metricNameOptions.value = [] // Prometheus 不可达 / 无活跃 target → 无候选（输入框仍可手输）
  }
}

onMounted(() => {
  void load()
  if (ready.value && editing.value) { void loadDetail(); void loadDiscoveryLabels(); void loadMetricNames() }
})
// 上下文晚于挂载才选齐时补拉详情 + 标签候选 + 指标名候选
watch(ready, (r) => {
  if (r && editing.value) {
    if (detailState.value === 'idle') void loadDetail()
    void loadDiscoveryLabels()
    void loadMetricNames()
  }
})

const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

// ---------- 提交：本地校验 → 组装 body（update 为整对象替换） ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const saving = ref(false)

function cleanList(arr: string[]): string[] {
  return arr.map((s) => s.trim()).filter(Boolean)
}
function splitCsv(s: string): string[] {
  return s.split(',').map((x) => x.trim()).filter(Boolean)
}

// ---------- replacement 占位符（纯前端解析：保存时把 {{token}} 换成真实值；$1 等捕获组不受影响） ----------
const REPLACE_TOKENS = [
  { token: 'cluster', label: '集群名', ph: '{{cluster}}', resolve: () => currentCluster.value?.clusterName ?? '' },
  { token: 'tenant', label: '租户名', ph: '{{tenant}}', resolve: () => currentTenant.value?.name ?? '' },
  { token: 'monitor', label: 'ServiceMonitor 名', ph: '{{monitor}}', resolve: () => form.name || editing.value || '' },
  { token: 'namespace', label: '命名空间', ph: '{{namespace}}', resolve: () => state.namespace ?? '' },
  { token: 'service', label: '目标 Service 名（需已绑定）', ph: '{{service}}', resolve: () => form.serviceRef?.name ?? '' },
]

/** 把 replacement 里的 {{token}} 换成真实值；未识别的占位符清空，避免字面量漏进 k8s-server */
function resolveReplacement(s: string): string {
  if (!s) return s
  let out = s
  for (const t of REPLACE_TOKENS) out = out.split(`{{${t.token}}}`).join(t.resolve())
  return out.replace(/\{\{[A-Za-z0-9_]+\}\}/g, '')
}

/** 在 replacement 末尾追加一个占位符 */
function insertToken(r: RelabelingRow, token: string): void {
  r.replacement += `{{${token}}}`
}

// ---------- regex 常用预设（纯前端；点选=整串替换，仍可在输入框继续改） ----------
const commonItems =
    {
      group: '值匹配（replace / keep / drop / hashmod）',
      items: [
        { pattern: '(.*)', desc: '匹配全部（默认 / 透传）' },
        { pattern: '^([a-zA-Z0-9]+)(.*)$', desc: '拆「字母数字前缀」+「其余」→ $1/$2' },
        { pattern: '(.*)-(.*)', desc: '按最后一个 - 拆两段（主机名 / 实例名）' },
        { pattern: '^(.*)\\.(.*)$', desc: '按最后一个 . 拆两段（FQDN host.tld）' },
        { pattern: '^(\\d+)$', desc: '纯数字' },
        { pattern: '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$', desc: 'RFC1123 合法名 / label 值' },
      ],
    };
const RELABEL_REGEX_PRESETS = [
  commonItems,
  {
    group: '标签名匹配（labelmap / labeldrop / labelkeep）',
    items: [
      { pattern: '__meta_kubernetes_(.*)', desc: '去 meta 前缀 → 干净 label' },
      { pattern: '__meta_kubernetes_pod_label_(.*)', desc: 'Pod 自定义 label' },
      { pattern: '__meta_kubernetes_service_label_(.*)', desc: 'Service label' },
      { pattern: '^_.*$', desc: '所有下划线开头（内部）标签，常配 labeldrop' },
      { pattern: '^__.*$', desc: '所有双下划线（临时）标签，常配 labeldrop' },
    ],
  },
]


const METRICS_RELABEL_REGEX_PRESETS = [
  commonItems,
  {
    group: '标签名匹配（labelmap / labeldrop / labelkeep）',
    items: [
      { pattern: 'container_cpu_usage_seconds_total(.*)', desc: 'container cpu label' },
      { pattern: 'node_dmi_info(.*)', desc: 'node label' },
      { pattern: 'kube_pod_container_info(.*)', desc: 'pod container label' },
    ],
  },
]

/** 点选预设：把 regex 整串设为该 pattern */
function applyRegexPreset(r: RelabelingRow, pattern: string): void {
  r.regex = pattern
}

/** MetricRelabeling regex 的指标名候选（el-autocomplete）：按输入前缀/子串过滤本 SM 活跃 target 的真实 __name__，最多 50 条；无候选时输入框仍可手输 */
function fetchMetricNames(query: string, cb: (items: { value: string }[]) => void): void {
  const q = query.trim().toLowerCase()
  const list = q ? metricNameOptions.value.filter((n) => n.toLowerCase().includes(q)) : metricNameOptions.value
  cb(list.slice(0, 50).map((v) => ({ value: v })))
}

function assembleRelabelings(rows: RelabelingRow[]): K8sSmRelabeling[] {
  const out: K8sSmRelabeling[] = []
  for (const r of rows) {
    const item: K8sSmRelabeling = {}
    if (r.sourceLabels.length) item.sourceLabels = [...r.sourceLabels]
    if (r.targetLabel.trim()) item.targetLabel = r.targetLabel.trim()
    if (r.regex.trim()) item.regex = r.regex.trim()
    if (r.replacement.trim()) item.replacement = resolveReplacement(r.replacement.trim())
    if (r.separator.trim()) item.separator = r.separator.trim()
    if (r.modulus != null) item.modulus = r.modulus
    if (r.action) item.action = r.action
    if (Object.keys(item).length) out.push(item)
  }
  return out
}

function assembleEndpoint(e: EndpointRow): K8sSmEndpoint {
  const ep: K8sSmEndpoint = { port: e.port.trim() }
  if (e.path.trim()) ep.path = e.path.trim()
  if (e.interval.trim()) ep.interval = e.interval.trim() + 's'
  if (e.scrapeTimeout.trim()) ep.scrapeTimeout = e.scrapeTimeout.trim() + 's'
  if (e.scheme) ep.scheme = e.scheme

  const params: Record<string, string[]> = {}
  for (const pr of e.paramsRows) {
    const k = pr.key.trim()
    if (!k) continue
    params[k] = splitCsv(pr.values)
  }
  if (Object.keys(params).length) ep.params = params

  if (e.basicAuthEnabled) {
    const ba: NonNullable<K8sSmEndpoint['basicAuth']> = {}
    if (e.basicUsernameName.trim()) ba.username = { name: e.basicUsernameName.trim(), key: e.basicUsernameKey.trim() || null }
    if (e.basicPasswordName.trim()) ba.password = { name: e.basicPasswordName.trim(), key: e.basicPasswordKey.trim() || null }
    if (ba.username || ba.password) ep.basicAuth = ba
  }
  if (e.bearerEnabled && e.bearerName.trim()) {
    ep.bearerTokenSecret = { name: e.bearerName.trim(), key: e.bearerKey.trim() || null }
  }
  if (e.bearerTokenFile.trim()) ep.bearerTokenFile = e.bearerTokenFile.trim()
  if (e.tlsEnabled) {
    const tls: NonNullable<K8sSmEndpoint['tlsConfig']> = {}
    if (e.tlsInsecureSkipVerify) tls.insecureSkipVerify = true
    if (e.tlsServerName.trim()) tls.serverName = e.tlsServerName.trim()
    if (tls.insecureSkipVerify || tls.serverName) ep.tlsConfig = tls
  }

  const rel = assembleRelabelings(e.relabelings)
  if (rel.length) ep.relabelings = rel
  const mrel = assembleRelabelings(e.metricRelabelings)
  if (mrel.length) ep.metricRelabelings = mrel
  return ep
}

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }

  // #5：创建必选目标 Service；编辑可沿用回显的选择器（未改则原样保留），或重新选择覆盖；也可用 matchExpressions 表达式
  const hasExprs = form.matchExpressions.some((m) => m.key.trim())
  if (!form.serviceRef?.name && !Object.keys(form.matchLabels).length && !hasExprs) { ElMessage.warning('请选择要抓取的目标 Service（或添加选择器表达式）'); return }

  const endpoints: K8sSmEndpoint[] = []
  for (const e of form.endpoints) {
    if (!e.port.trim()) { ElMessage.warning('每个端点都需填写 Port'); return }
    endpoints.push(assembleEndpoint(e))
  }
  if (!endpoints.length) { ElMessage.warning('至少需要一个抓取端点（spec.endpoints，Port 必填）'); return }

  const body: K8sServiceMonitor = { name, namespace: state.namespace!, labels: form.labels, matchLabels: form.matchLabels, endpoints }
  if (form.serviceRef?.name) body.serviceRef = form.serviceRef

  // spec.selector.matchExpressions：按表达式选 Service（与 matchLabels ANDed）；Exists/DoesNotExist 不带 values
  const exprs: K8sSmMatchExpression[] = form.matchExpressions
    .filter((m) => m.key.trim())
    .map((m) => {
      const e: K8sSmMatchExpression = { key: m.key.trim(), operator: m.operator }
      if (m.operator !== 'Exists' && m.operator !== 'DoesNotExist') {
        const vals = m.values.split(',').map((s) => s.trim()).filter(Boolean)
        if (vals.length) e.values = vals
      }
      return e
    })
  if (exprs.length) body.matchExpressions = exprs

  const nsNames = cleanList(form.nsMatchNames)
  if (nsNames.length) {
    body.namespaceSelector = { matchNames: nsNames }
  }
  if (form.jobLabel.trim()) body.jobLabel = form.jobLabel.trim()
  const ptl = cleanList(form.podTargetLabels)
  if (ptl.length) body.podTargetLabels = ptl
  if (form.sampleLimit != null) body.sampleLimit = form.sampleLimit
  if (form.targetLimit != null) body.targetLimit = form.targetLimit
  if (form.labelLimit != null) body.labelLimit = form.labelLimit
  if (form.bodySizeLimit.trim()) body.bodySizeLimit = form.bodySizeLimit.trim() + 'MB'
  if (form.attachNode) body.attachMetadata = { node: true }

  saving.value = true
  try {
    if (editing.value) {
      await serviceMonitorApi.update(editing.value, ctx2.value, body)
      ElMessage.success('保存成功')
    } else {
      await serviceMonitorApi.create(ctx2.value, body)
      ElMessage.success('创建成功')
    }
    router.push('/resources/servicemonitors')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/resources/servicemonitors') }

const pageTitle = computed(() => (editing.value ? '编辑 ServiceMonitor' : '创建 ServiceMonitor'))
const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div class="res-editor">
    <PageHeader :title="pageTitle" >
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑 ServiceMonitor。" />

    <template v-else>
      <div v-if="formVisible" class="editor-body">
        <!-- 基础信息 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">{{ editing ? `ServiceMonitor · ${form.name}` : '基础信息' }}</span></template>
          <el-form label-width="200px" label-position="left">
            <el-form-item label="名称" required>
              <template #label>名称 <FieldHelp tip="K8s 资源名创建后不可修改；命名空间 = 当前上下文" /></template>
              <el-input v-model="form.name" :disabled="!!editing" placeholder="小写字母/数字/-，例如 app-monitor" style="width: 360px" />
            </el-form-item>
            <el-form-item label="标签">
              <LabelEditor v-model="form.labels" class="sub-editor" style="max-width: 1080px" />
            </el-form-item>
            <el-form-item label="jobLabel">
              <template #label>jobLabel <FieldHelp tip="Prometheus job 名；留空 = &lt;namespace&gt;-&lt;name&gt;。" /></template>
              <el-input v-model="form.jobLabel" placeholder="留空 = 命名空间-名称" style="width: 360px" />
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 目标选择 selector -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">目标选择（selector）</span></template>
          <el-form label-width="200px" label-position="left">
            <el-form-item label="目标 Service" required>
              <template #label>目标 Service <FieldHelp tip="选择要抓取的目标 Service；后端据其 labels 自动生成选择器（spec.selector）。编辑时重新选择即可覆盖。" /></template>
              <el-select v-model="svcSelectValue" filterable clearable :loading="svcLoading" placeholder="选择目标 Service（必填）" style="width: 420px">
                <el-option
                  v-for="s in svcOptions"
                  :key="`${s.namespace}/${s.name}`"
                  :label="targetNamespaces.length > 1 ? `${s.name} · ${s.namespace}` : s.name"
                  :value="`${s.namespace}/${s.name}`"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="选择器（只读）">
              <template #label>选择器 <FieldHelp tip="spec.selector.matchLabels：由所选 Service 的 labels 决定，不可手改；重新选择 Service 才会覆盖。" /></template>
              <div v-if="Object.keys(form.matchLabels).length" class="chip-row">
                <el-tag v-for="(v, k) in form.matchLabels" :key="k" size="small" effect="plain" class="label-tag">{{ k }}={{ v }}</el-tag>
              </div>
              <span v-else-if="form.serviceRef?.name" class="muted">将使用 Service「{{ form.serviceRef.name }}」的 labels 生成</span>
              <span v-else class="muted">尚未选择（创建时必选目标 Service）</span>
            </el-form-item>
            <el-form-item label="选择器表达式">
              <template #label>选择器表达式 <FieldHelp tip="spec.selector.matchExpressions：按标签表达式选 Service，与 matchLabels ANDed。Exists / DoesNotExist 无需填值。" /></template>
              <div class="expr-list">
                <div v-for="(m, idx) in form.matchExpressions" :key="idx" class="expr-row">
                  <el-input v-model="m.key" placeholder="标签 key（如 app）" style="width: 200px" />
                  <el-select v-model="m.operator" style="width: 150px">
                    <el-option v-for="op in MATCH_EXPR_OPERATORS" :key="op" :label="op" :value="op" />
                  </el-select>
                  <el-input v-if="m.operator !== 'Exists' && m.operator !== 'DoesNotExist'" v-model="m.values" placeholder="值（逗号分隔）" style="width: 220px" />
                  <el-button link type="danger" @click="form.matchExpressions.splice(idx, 1)">删除</el-button>
                </div>
                <el-button class="add-row-btn" plain @click="form.matchExpressions.push(newMatchExpr())">+ 添加表达式</el-button>
              </div>
            </el-form-item>
            <el-form-item label="命名空间范围">
              <template #label>命名空间范围 <FieldHelp tip="spec.namespaceSelector：留空 = 仅本命名空间；勾选多个后跨所选命名空间抓取，并据此加载 Service 列表。" /></template>
              <div class="ns-editor">
                <el-select v-model="form.nsMatchNames" multiple filterable clearable placeholder="留空 = 仅本命名空间" style="width: 420px">
                  <el-option v-for="n in namespacesOfCluster" :key="n" :label="n" :value="n" />
                </el-select>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 抓取端点 endpoints -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">抓取端点（endpoints）</span></template>
          <div v-for="(e, idx) in form.endpoints" :key="idx" class="ep-block">
            <div class="ep-head">
              <span class="ep-title">端点 #{{ idx + 1 }}</span>
              <el-button link type="danger" :disabled="form.endpoints.length <= 1" @click="form.endpoints.splice(idx, 1)">删除端点</el-button>
            </div>

            <!-- 抓取参数（两行：scheme/port/path + interval/scrapeTimeout） -->
            <div class="ep-grid">
              <div class="field">
                <label>Scheme <FieldHelp tip="抓取协议：http / https；留空默认 http。" /></label>
                <el-select v-model="e.scheme" clearable placeholder="http（默认）">
                  <el-option v-for="s in SCHEMES" :key="s" :label="s" :value="s" />
                </el-select>
              </div>
              <div class="field">
                <label>Port * <FieldHelp tip="目标 Service 的端口（名字或端口号）；从所选 Service 选择，也可手输。" /></label>
                <el-select v-model="e.port" filterable allow-create clearable :placeholder="form.serviceRef ? '选择端口' : '先选择目标 Service'" no-data-text="无匹配端口">
                  <el-option v-for="p in portOptions" :key="p.value" :label="p.label" :value="p.value" />
                </el-select>
              </div>
              <div class="field">
                <label>Path <FieldHelp tip="抓取路径，缺省 /metrics。" /></label>
                <el-input v-model="e.path" placeholder="/metrics" />
              </div>
              <div class="field">
                <label>Interval <FieldHelp tip="抓取间隔（秒），如 30。" /></label>
                <el-input v-model="e.interval"  placeholder="30"
                          :formatter="(v: string) => v.replace(/\D/g, '')"
                          :parser="(v: string) => v.replace(/\D/g, '')">
                  <template #append>s</template>
                </el-input>
              </div>
              <div class="field">
                <label>ScrapeTimeout <FieldHelp tip="单次抓取超时（秒），须小于 Interval，如 10。" /></label>
                <el-input v-model="e.scrapeTimeout"  placeholder="10"
                          :formatter="(v: string) => v.replace(/\D/g, '')"
                          :parser="(v: string) => v.replace(/\D/g, '')">
                  <template #append>s</template>
                </el-input>
              </div>
            </div>

            <!-- 高级（折叠） -->
            <el-collapse class="ep-adv">
              <el-collapse-item title="URL 参数 params" name="params">
                <div class="kv-editor">
                  <div v-for="(pr, pi) in e.paramsRows" :key="pi" class="kv-row">
                    <el-input v-model="pr.key" placeholder="参数名（如 format）" class="kv-key" />
                    <el-input v-model="pr.values" placeholder="值，多个用逗号分隔（如 json,prometheus）" />
                    <el-button link type="danger" @click="e.paramsRows.splice(pi, 1)">删除</el-button>
                  </div>
                  <el-button class="add-row-btn" plain @click="e.paramsRows.push({ key: '', values: '' })">+ 添加参数</el-button>
                </div>
              </el-collapse-item>

              <el-collapse-item title="鉴权 / TLS" name="auth">
                <div class="auth-block">
                  <label class="sw"><el-switch v-model="e.basicAuthEnabled" /> Basic 鉴权（basicAuth）</label>
                  <template v-if="e.basicAuthEnabled">
                    <div class="kv-row">
                      <span class="auth-sub">username</span>
                      <el-input v-model="e.basicUsernameName" placeholder="Secret 名" style="width: 200px" />
                      <el-input v-model="e.basicUsernameKey" placeholder="key（可空）" style="width: 160px" />
                    </div>
                    <div class="kv-row">
                      <span class="auth-sub">password</span>
                      <el-input v-model="e.basicPasswordName" placeholder="Secret 名" style="width: 200px" />
                      <el-input v-model="e.basicPasswordKey" placeholder="key（可空）" style="width: 160px" />
                    </div>
                  </template>

                  <label class="sw"><el-switch v-model="e.bearerEnabled" /> Bearer Token（bearerTokenSecret）</label>
                  <div v-if="e.bearerEnabled" class="kv-row">
                    <span class="auth-sub">secret</span>
                    <el-input v-model="e.bearerName" placeholder="Secret 名" style="width: 200px" />
                    <el-input v-model="e.bearerKey" placeholder="key（可空）" style="width: 160px" />
                  </div>

                  <label class="sw"><el-switch v-model="e.tlsEnabled" /> TLS（tlsConfig）</label>
                  <template v-if="e.tlsEnabled">
                    <div class="kv-row">
                      <span class="auth-sub">insecureSkipVerify</span>
                      <el-switch v-model="e.tlsInsecureSkipVerify" />
                    </div>
                    <div class="kv-row">
                      <span class="auth-sub">serverName</span>
                      <el-input v-model="e.tlsServerName" placeholder="SNI 服务器名（可空）" style="width: 260px" />
                    </div>
                  </template>
                </div>
              </el-collapse-item>

              <el-collapse-item :title="`Relabeling（${e.relabelings.length}）`" name="rel">
                <div class="rel-doc">
                  <span class="rel-doc-k">replacement 占位符</span>（保存时替换为真实值；<code>$1</code> 引用正则捕获组，可混用）：
                  <span v-for="t in REPLACE_TOKENS" :key="t.token" class="rel-doc-item"><code>{{ t.ph }}</code>{{ t.label }} </span>
                </div>
                <div v-for="(r, ri) in e.relabelings" :key="ri" class="rel-block">
                  <div class="rel-head">
                    <span class="rel-idx">规则 {{ ri + 1 }}</span>
                    <el-button link type="danger" @click="e.relabelings.splice(ri, 1)">删除</el-button>
                  </div>

                  <div class="rel-field">
                    <label>Action<FieldHelp tip="动作类型，决定这条规则对标签做什么。replace=匹配则用 replacement 写入 targetLabel（默认）；keep=拼接值匹配才保留、否则丢弃；drop=匹配即丢弃；hashmod=对拼接值哈希取模 modulus 后写入 targetLabel；labelmap/labeldrop/labelkeep=按标签名匹配改名/删除/保留（不看值）。" /></label>
                    <el-select v-model="r.action" class="rel-ctl rel-ctl-grow">
                      <el-option v-for="a in RELABEL_ACTIONS" :key="a.value" :value="a.value">
                        <div class="rel-opt"><span class="rel-opt-name">{{ a.value }}</span><span class="rel-opt-desc">{{ a.desc }}</span></div>
                      </el-option>
                    </el-select>
                  </div>

                  <div class="rel-field">
                    <label>sourceLabels<FieldHelp tip="参与匹配的源标签名（可多个，按顺序用 separator 拼成一个串再套 regex）。Relabeling 作用于服务发现阶段的原始标签（discoveredLabels：__meta_*、__address__、__metrics_path__ 等）；候选来自 Prometheus discovery，也可手输补充。" /></label>
                    <el-select v-model="r.sourceLabels" multiple filterable allow-create default-first-option placeholder="选择或输入源标签（可多个）" class="rel-ctl rel-ctl-grow">
                      <el-option v-for="opt in relabelSourceOptions" :key="opt" :label="opt" :value="opt" />
                    </el-select>
                  </div>

                  <div class="rel-field">
                    <label>targetLabel<FieldHelp tip="结果写入的目标标签名。replace/hashmod 用；不填时 replace 写回 sourceLabels[0]。keep/drop/label* 不需要。" /></label>
                    <el-input v-model="r.targetLabel" placeholder="可空" class="rel-ctl rel-ctl-grow" />
                  </div>

                  <div class="rel-field">
                    <label>regex<FieldHelp tip="匹配正则（RE2，留空=默认 (.*)）。作用于 sourceLabels 拼接值；labelmap/labeldrop/labelkeep 作用于标签名。右侧「常用正则」可选常用写法，选后仍可继续改。" /></label>
                    <div class="rel-input-wrap">
                      <el-input v-model="r.regex" placeholder="默认 (.*)" class="rel-ctl rel-ctl-grow" >
                        <template #append>
                          <el-dropdown trigger="click" @command="(p: string) => applyRegexPreset(r, p)">
                            <el-button size="small" plain>查看常用正则</el-button>
                            <template #dropdown>
                              <el-dropdown-menu>
                                <template v-for="g in RELABEL_REGEX_PRESETS" :key="g.group">
                                  <li class="rel-regex-grp" aria-hidden="true">{{ g.group }}</li>
                                  <el-dropdown-item v-for="it in g.items" :key="it.pattern" :command="it.pattern">
                                    <div class="rel-opt rel-opt-regex"><span class="rel-opt-name">{{ it.pattern }}</span><span class="rel-opt-desc">{{ it.desc }}</span></div>
                                  </el-dropdown-item>
                                </template>
                              </el-dropdown-menu>
                            </template>
                          </el-dropdown>
                        </template>
                      </el-input>

                    </div>
                  </div>

                  <div class="rel-field">
                    <label>replacement<FieldHelp tip="replace/hashmod 的替换模板。$1 引用正则捕获组（默认 $1）；可用占位符注入平台信息（见上方说明），保存时替换为真实值，右侧「＋变量」可插入。" /></label>
                    <div class="rel-input-wrap">
                      <el-input v-model="r.replacement" placeholder="默认 $1" class="rel-ctl rel-ctl-grow" >
                        <template #append>
                          <el-dropdown trigger="click" @command="(tk: string) => insertToken(r, tk)">
                          <el-button size="small" plain >查看可用变量</el-button>
                          <template #dropdown>
                            <el-dropdown-menu>
                              <el-dropdown-item v-for="t in REPLACE_TOKENS" :key="t.token" :command="t.token">{{ t.label }}（{{ t.ph }}）</el-dropdown-item>
                            </el-dropdown-menu>
                          </template>
                        </el-dropdown></template>
                      </el-input>

                    </div>
                  </div>

                  <div class="rel-field">
                    <label>separator<FieldHelp tip="多源标签拼接用的分隔符（默认 ;），仅 sourceLabels 有多个时才有意义。" /></label>
                    <el-input v-model="r.separator" placeholder="默认 ;" class="rel-ctl rel-ctl-sm" />
                  </div>

                  <div v-if="r.action === 'hashmod'" class="rel-field">
                    <label>modulus<FieldHelp tip="仅 hashmod：取模值（≥1）。" /></label>
                    <el-input-number v-model="r.modulus" :min="1" controls-position="right" placeholder="modulus" class="rel-ctl rel-ctl-sm" />
                  </div>
                </div>
                <el-button class="add-row-btn" plain @click="e.relabelings.push(newRelabeling())">+ 添加规则</el-button>
              </el-collapse-item>

              <el-collapse-item :title="`MetricRelabeling（${e.metricRelabelings.length}）`" name="mrel">
                <div class="rel-doc">
                  <span class="rel-doc-k">replacement 占位符</span>（保存时替换为真实值；<code>$1</code> 引用正则捕获组，可混用）：
                  <span v-for="t in REPLACE_TOKENS" :key="t.token" class="rel-doc-item"><code>{{ t.ph }}</code>{{ t.label }} </span>
                </div>
                <div v-for="(r, ri) in e.metricRelabelings" :key="ri" class="rel-block">
                  <div class="rel-head">
                    <span class="rel-idx">规则 {{ ri + 1 }}</span>
                    <el-button link type="danger" @click="e.metricRelabelings.splice(ri, 1)">删除</el-button>
                  </div>

                  <div class="rel-field">
                    <label>Action<FieldHelp tip="动作类型，决定这条规则对指标样本做什么。replace=匹配则用 replacement 写入 targetLabel（默认）；keep=拼接值匹配才保留、否则丢弃；drop=匹配即丢弃；hashmod=对拼接值哈希取模 modulus 后写入 targetLabel；labelmap/labeldrop/labelkeep=按标签名匹配改名/删除/保留（不看值）。" /></label>
                    <el-select v-model="r.action" class="rel-ctl rel-ctl-grow">
                      <el-option v-for="a in RELABEL_ACTIONS" :key="a.value" :value="a.value">
                        <div class="rel-opt"><span class="rel-opt-name">{{ a.value }}</span><span class="rel-opt-desc">{{ a.desc }}</span></div>
                      </el-option>
                    </el-select>
                  </div>

                  <div class="rel-field">
                    <label>sourceLabels<FieldHelp tip="参与匹配的源标签名（可多个，按顺序用 separator 拼成一个串再套 regex）。MetricRelabeling 作用于指标样本的标签 = 目标最终 labels + __name__（指标名）；候选已含 __name__ 与目标标签，也可手输补充。" /></label>
                    <el-select v-model="r.sourceLabels" multiple filterable allow-create default-first-option placeholder="选择或输入源标签（可多个）" class="rel-ctl rel-ctl-grow">
                      <el-option v-for="opt in metricRelabelSourceOptions" :key="opt" :label="opt" :value="opt" />
                    </el-select>
                  </div>

                  <div class="rel-field">
                    <label>targetLabel<FieldHelp tip="结果写入的目标标签名。replace/hashmod 用；不填时 replace 写回 sourceLabels[0]。keep/drop/label* 不需要。" /></label>
                    <el-input v-model="r.targetLabel" placeholder="可空" class="rel-ctl rel-ctl-grow" />
                  </div>

                  <div class="rel-field">
                    <label>regex<FieldHelp tip="匹配正则（RE2，留空=默认 (.*)）。作用于 sourceLabels 拼接值；labelmap/labeldrop/labelkeep 作用于标签名。输入时可从本 SM 真实暴露的指标名（__name__）里选，也可手输；右侧「常用正则」可选常用写法，选后仍可继续改。" /></label>
                    <div class="rel-input-wrap">
                      <el-autocomplete v-model="r.regex" :fetch-suggestions="fetchMetricNames" placeholder="默认 (.*)（可选手填指标名）" clearable class="rel-ctl rel-ctl-grow" >
                        <template #append>
                          <el-dropdown trigger="click" @command="(p: string) => applyRegexPreset(r, p)">
                            <el-button size="small" plain>查看常用正则</el-button>
                            <template #dropdown>
                              <el-dropdown-menu>
                                <template v-for="g in METRICS_RELABEL_REGEX_PRESETS" :key="g.group">
                                  <li class="rel-regex-grp" aria-hidden="true">{{ g.group }}</li>
                                  <el-dropdown-item v-for="it in g.items" :key="it.pattern" :command="it.pattern">
                                    <div class="rel-opt rel-opt-regex"><span class="rel-opt-name">{{ it.pattern }}</span><span class="rel-opt-desc">{{ it.desc }}</span></div>
                                  </el-dropdown-item>
                                </template>
                              </el-dropdown-menu>
                            </template>
                          </el-dropdown>
                        </template>
                      </el-autocomplete>

                    </div>
                  </div>

                  <div class="rel-field">
                    <label>replacement<FieldHelp tip="replace/hashmod 的替换模板。$1 引用正则捕获组（默认 $1）；可用占位符注入平台信息（见上方说明），保存时替换为真实值，右侧「＋变量」可插入。" /></label>
                    <div class="rel-input-wrap">
                      <el-input v-model="r.replacement" placeholder="默认 $1" class="rel-ctl rel-ctl-grow" >
                        <template #append>
                          <el-dropdown trigger="click" @command="(tk: string) => insertToken(r, tk)">
                            <el-button size="small" plain>查看可用变量</el-button>
                            <template #dropdown>
                              <el-dropdown-menu>
                                <el-dropdown-item v-for="t in REPLACE_TOKENS" :key="t.token" :command="t.token">{{ t.label }}（{{ t.ph }}）</el-dropdown-item>
                              </el-dropdown-menu>
                            </template>
                          </el-dropdown>
                        </template>
                      </el-input>


                    </div>
                  </div>

                  <div class="rel-field">
                    <label>separator<FieldHelp tip="多源标签拼接用的分隔符（默认 ;），仅 sourceLabels 有多个时才有意义。" /></label>
                    <el-input v-model="r.separator" placeholder="默认 ;" class="rel-ctl rel-ctl-sm" />
                  </div>

                  <div v-if="r.action === 'hashmod'" class="rel-field">
                    <label>modulus<FieldHelp tip="仅 hashmod：取模值（≥1）。" /></label>
                    <el-input-number v-model="r.modulus" :min="1" controls-position="right" placeholder="modulus" class="rel-ctl rel-ctl-sm" />
                  </div>
                </div>
                <el-button class="add-row-btn" plain @click="e.metricRelabelings.push(newRelabeling())">+ 添加规则</el-button>
              </el-collapse-item>
            </el-collapse>
          </div>
          <el-button class="add-ep-btn" plain @click="form.endpoints.push(newEndpoint())">+ 添加端点</el-button>
        </el-card>

        <!-- 其他配置 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">其他配置</span></template>
          <el-form label-width="200px" label-position="left">
            <el-form-item label="podTargetLabels">
              <template #label>podTargetLabels <FieldHelp tip="从目标 Service 的标签透传到抓取目标的 label 列表。" /></template>
              <div class="list-editor">
                <div v-for="(t, i) in form.podTargetLabels" :key="i" class="list-row">
                  <el-input :model-value="t ?? ''" @update:model-value="(v: string) => (form.podTargetLabels[i] = v)" placeholder="label 名（如 app）" style="width: 300px" />
                  <el-button link type="danger" @click="form.podTargetLabels.splice(i, 1)">删除</el-button>
                </div>
                <el-button plain size="small" @click="form.podTargetLabels.push('')">+ 添加 label</el-button>
              </div>
            </el-form-item>
            <el-form-item label="sampleLimit">
              <template #label>sampleLimit <FieldHelp tip="每个 target 的最大样本数（超限丢弃）。" /></template>
              <el-input-number v-model="form.sampleLimit" :min="1" controls-position="right" placeholder="可空" class="num-input" />
            </el-form-item>
            <el-form-item label="targetLimit">
              <template #label>targetLimit <FieldHelp tip="每次 scrape 的最大 target 数。" /></template>
              <el-input-number v-model="form.targetLimit" :min="1" controls-position="right" placeholder="可空" class="num-input" />
            </el-form-item>
            <el-form-item label="labelLimit">
              <template #label>labelLimit <FieldHelp tip="每个 target 的最大 label 数。" /></template>
              <el-input-number v-model="form.labelLimit" :min="1" controls-position="right" placeholder="可空" class="num-input" />
            </el-form-item>
            <el-form-item label="bodySizeLimit">
              <template #label>bodySizeLimit <FieldHelp tip="响应体大小上限（MB），如 50。" /></template>
              <el-input v-model="form.bodySizeLimit" type="number" placeholder="如 50（可空）" style="width: 240px"><template #append>MB</template></el-input>
            </el-form-item>
            <el-form-item label="attachMetadata">
              <template #label>attachMetadata.node <FieldHelp tip="true 时把目标所在 node 名附加到抓取目标的标签。" /></template>
              <el-switch v-model="form.attachNode" />
            </el-form-item>
          </el-form>
        </el-card>

        <div class="form-actions">
          <el-button @click="goBack">取消 / 返回</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
      </div>

      <EmptyState v-else-if="detailState === 'error'" title="加载 ServiceMonitor 失败" description="请返回列表重试；若该资源已被删除，刷新列表即可。">
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </EmptyState>

      <div v-else class="loading-tip">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
.editor-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sec-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.sec-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.form-tip {
  width: 100%;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.sub-editor {
  width: 100%;
}
.num-input {
  width: 200px;
}

/* kv / list 编辑器 */
.kv-editor,
.list-editor,
.ns-editor {
  width: 100%;
}
.kv-row,
.list-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.kv-key {
  width: 40%;
  flex-shrink: 0;
}
.add-row-btn {
  width: auto;
}

/* 只读选择器 chips / 提示 */
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.expr-list {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.expr-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.label-tag {
  margin: 0;
}
.muted {
  color: var(--text-3);
  font-size: 13px;
}

/* 端点块 */
.ep-block {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.ep-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.ep-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}
.ep-grid {
  display: grid;
  grid-template-columns: repeat(3, 200px);
  gap: 8px 12px;
  margin-bottom: 10px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 200px;
}
.field label {
  font-size: 12px;
  color: var(--text-3);
}
.sw {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
}
.ep-adv {
  border-top: none;
  margin-top: 10px;
}
.ep-adv :deep(.el-collapse-item__header) {
  font-size: 13px;
  color: var(--text-2);
}

.add-ep-btn {
  width: auto;
}

/* 鉴权 */
.auth-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.auth-sub {
  width: 120px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-3);
}

/* relabeling：竖向、每字段一行 + FieldHelp */
.rel-block {
  border: 1px dashed var(--border);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.rel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.rel-idx {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
}
.rel-field {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  max-width: 1280px;
}
.rel-field > label {
  width: 104px;
  flex-shrink: 0;
  text-align: left;
  font-size: 13px;
  color: var(--text-2);
}

/* action 下拉选项：名称 + 说明两行 */
.rel-opt {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  padding: 2px 0;
}
.rel-opt-name {
  font-size: 13px;
}
.rel-opt-desc {
  font-size: 12px;
  color: var(--text-3);
}

/* replacement 占位符说明（Relabeling / MetricRelabeling 顶部） */
.rel-doc {
  margin-bottom: 10px;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 2;
  color: var(--text-2);
}
.rel-doc code {
  padding: 1px 5px;
  margin-right: 2px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-size: 11.5px;
  color: var(--text-1, #303133);
}
.rel-doc-k {
  font-weight: 600;
}
.rel-doc-item {
  margin-right: 8px;
  white-space: nowrap;
}

/* 输入框 + 右侧下拉（replacement「＋变量」/ regex「常用正则」）：占满整行剩余宽度，与无按钮的字段对齐 */
.rel-input-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}
/* wrap 内的输入框吃掉按钮以外的剩余宽度（flex 覆盖 el-input 默认 width:100%） */
.rel-input-wrap > .rel-ctl {
  flex: 1;
  min-width: 0;
}

/* regex 预设项：pattern 用等宽字体、可换行 */
.rel-opt-regex .rel-opt-name {
  font-family: ui-monospace, 'Cascadia Code', Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

/* regex 预设分组标题（菜单内 <li>） */
.rel-regex-grp {
  padding: 6px 12px 4px;
  margin-top: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
  list-style: none;
  cursor: default;
}
.rel-regex-grp:first-child {
  margin-top: 0;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 4px 0 16px;
}
.loading-tip {
  padding: 48px;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}
</style>
