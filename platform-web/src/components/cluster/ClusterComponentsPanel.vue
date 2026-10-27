<script setup lang="ts">
/**
 * 集群组件清单（S1.2）。数据源 `POST /cluster/components`（平台侧全量），由 `ClusterDetailView`
 * 用 `useClusterComponents` 取好后经 props 传入 —— 一个页面只探测一次，面板不是第二个数据源
 * （同 `MeshStatusBanner` 的约定）。
 *
 * ⚠️ 四态必须分开渲染 —— {@code UNKNOWN}（探测不到）与 {@code NOT_INSTALLED}（没装）是相反结论，
 *    都渲染成灰 tag 就等于把"不知道"说成了"没有"（本仓修过的坑）。
 * ⚠️ 安装/升级（S3.6）：能不能装由后端下发的 `availableVersions` 决定（空 = 没有 recipe），
 *    前端不再硬编码组件清单。
 *    已装但**不是平台装的**（无平台操作记录）只读 —— 与「已装的一律只探测不接管」一致。
 *    升级只在**有别的版本可选**时给按钮，点开先选版本（只有一个版本时显示「已是最新」）。
 *    UNKNOWN 不给装：「不知道」不是「没有」。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import type {
  K8sCalicoInstall, K8sComponentOperation, K8sComponentState, K8sComponentStatus, K8sEditableField,
  K8sEsConfig, K8sHarborInstall, K8sOtelInstall, K8sSeaweedInstall, K8sTempoStorage,
  K8sThanosInstall, SecretRefOption,
} from '@/types'
import HarborProjectsDialog from './HarborProjectsDialog.vue'

const props = defineProps<{
  /** 集群 id（Harbor 镜像项目弹窗要它） */
  clusterId: string
  statuses: K8sComponentStatus[]
  loading: boolean
  /** 「重新探测」需要 platform:cluster:manage（写操作） */
  canRefresh: boolean
  /** 各组件最近一次操作（由页面统一加载） */
  operations: Record<string, K8sComponentOperation>
  /** 正在执行的组件 code（按钮 loading） */
  applying?: string | null
  /** 集群 StorageClass 候选名称（安装表单的存储类下拉；由页面提供，面板不发请求） */
  loadStorageClasses?: () => Promise<string[]>
  /** 某命名空间下的 Secret 候选（name + keys；凭证数据不会下发） */
  loadSecretOptions?: (namespace: string) => Promise<SecretRefOption[]>
}>()
const emit = defineEmits<{
  (e: 'refresh'): void
  (e: 'apply', payload: {
    code: string
    action: 'INSTALL' | 'UPGRADE'
    targetVersion?: string
    editableValues?: Record<string, unknown>
    /** 平台告警回调地址（仅 platformAlerting 的组件） */
    alertWebhookUrl?: string
    /** Thanos Receive 地址（仅 thanosRemoteWrite 的组件） */
    thanosReceiveUrl?: string
    /** Thanos 安装参数（仅 thanosInstall 的组件，管理集群） */
    thanosInstall?: K8sThanosInstall
    /** Calico 网络参数（仅 calicoNetwork 的组件） */
    calicoInstall?: K8sCalicoInstall
    /** Tekton profile（仅 tektonProfile 的组件：lite / basic / all） */
    tektonProfile?: string
    /** OTel 采集栈参数（仅 otelInstrumentation 的组件） */
    otel?: K8sOtelInstall
    /** 对象存储参数（仅 seaweedStorage 的组件：StorageClass / 容量 / 副本数） */
    seaweed?: K8sSeaweedInstall
    /** Harbor 安装参数（仅 harborRegistry 的组件：访问域名） */
    harbor?: K8sHarborInstall
  }): void
  /** 中止一条进行中的操作（由页面调接口并刷新操作列表） */
  (e: 'abandon', id: number): void
}>()

/** 四态 → 徽标。⚠️ UNKNOWN 用虚线边（effect=plain）与 NOT_INSTALLED（实心灰）区分开 */
const STATE: Record<K8sComponentState, {
  label: string
  type: 'success' | 'warning' | 'info'
  effect: 'light' | 'plain'
}> = {
  INSTALLED_HEALTHY: { label: '已装', type: 'success', effect: 'light' },
  INSTALLED_UNHEALTHY: { label: '已装 · 不健康', type: 'warning', effect: 'light' },
  NOT_INSTALLED: { label: '未安装', type: 'info', effect: 'light' },
  UNKNOWN: { label: '未探测', type: 'info', effect: 'plain' },
}

/** 功能 code → 展示文案。code 是稳定 id（门控按它查），文案改这里不影响门控 */
const FEATURE_LABELS: Record<string, string> = {
  'hpa-resource-metrics': 'HPA 资源指标',
  'metrics-charts': '监控曲线',
  servicemonitor: 'ServiceMonitor / PodMonitor',
  'service-mesh': '服务网格',
  routes: '路由',
  'ambient-mesh': 'ambient 流量接管',
  ci: 'CI 流水线',
  network: '网络',
  ipam: 'IPAM',
  'gateway-tls': '网关 TLS 证书',
  'otel-collection': 'OTel 采集',
  'object-storage': '对象存储',
  'container-registry': '镜像仓库',
}
function featureLabel(code: string): string {
  return FEATURE_LABELS[code] ?? code
}

/** 只滤掉与「健康」列重复的 `n/m 就绪`，其余 detail 一律显示 */
function showDetail(detail?: string | null): boolean {
  return !!detail && !/^\d+\/\d+ 就绪$/.test(detail)
}

/** 平台能不能装它：由后端下发的可安装 chart 版本决定（空 = 没有 recipe） */
function installable(row: K8sComponentStatus): boolean {
  return (row.availableVersions?.length ?? 0) > 0
}

/**
 * **已装**的 chart 版本（组件本体 = 主 release），后端从该组件最近一次**成功**操作记录里带出来。
 * 没有记录（外部安装 / 从未操作）→ null，页面显示「—」。
 *
 * <p>⚠️ 不要用 {@code row.releases} 顶替：那是 recipe 的**平台内置**版本，回答的是"能装什么"。
 * recipe 升到 1.31.3 之后，一个还停在 1.30.6 的集群若照它显示，用户会以为已经升过了
 * —— 2026-10-26 用户就是这么被误导的（这条原本为了绕开一条脏记录才读 recipe，方向反了）。
 * 脏记录（历史 bug 把随附 chart 的版本记成组件版本）由后端识别后返回 null。
 */
function installedChartVersion(row: K8sComponentStatus): string | null {
  return row.installedChartVersion ?? null
}

/** 可升级到的版本：recipe 里除已装版本之外的 —— 只有一个版本时不出现升级按钮 */
function upgradeTargets(row: K8sComponentStatus): string[] {
  const current = installedChartVersion(row)
  return (row.availableVersions ?? []).filter((v) => v !== current)
}

interface ApplyDialog {
  visible: boolean
  code: string
  name: string
  /** EDIT = 已装组件的"编辑并重放"（不带版本选择，按当前版本重放） */
  action: 'INSTALL' | 'UPGRADE' | 'EDIT'
  version: string
  versions: string[]
  /** 当前组件本体的 chart 版本（编辑弹窗展示；取主 release，不用历史记录里的 toVersion） */
  chartVersion: string
  fields: K8sEditableField[]
  /** 非 JSON 字段的当前值（动态类型，模板按 type 渲染不同控件） */
  values: Record<string, any>
  /** JSON 字段的文本（textarea 里编辑，提交时解析） */
  jsonText: Record<string, string>
  /** 端口列表（type=ports）的行数据，按字段 key 存 */
  portRows: Record<string, PortRow[]>
  /**
   * 打开时的初始值快照。提交时**只发改动过的字段** —— 未改的沿用后端合并规则里的
   * 「上次快照」基线，否则每次升级都会把用户上次改的值重置成 recipe 默认值。
   */
  initial: Record<string, string>
  /** 该组件装完是否要下发告警联动（Prometheus）：显示回调地址输入框 */
  showWebhook: boolean
  webhookUrl: string
  /** 打开时的预填值：没改就不提交，后端会沿用上次成功操作里记的地址 */
  initialWebhook: string
  /** 该组件是否需要 Thanos Receive 地址（Prometheus 的 remoteWrite 目标） */
  showThanos: boolean
  thanosUrl: string
  initialThanos: string
  /** 该组件是否同时安装 Thanos（管理集群的 Prometheus） */
  showThanosInstall: boolean
  thanos: K8sThanosInstall
  initialThanosInstall: string
  storageClasses: string[]
  secretOptions: SecretRefOption[]
  /** Istio 对外入口 Gateway 的证书候选（istio-system） */
  gatewaySecretOptions: SecretRefOption[]
  /** OTel·Tempo 的 MinIO 凭证 Secret 候选（只查 monitor 命名空间） */
  otelMinioSecretOptions: SecretRefOption[]
  /** OTel·日志的 ES 凭证 Secret 候选（按表单里填的 ES 命名空间现查） */
  esSecretOptions: SecretRefOption[]
  /** 该组件是否要填 Calico 网络参数（CIDR / 封装方式） */
  showCalico: boolean
  calico: K8sCalicoInstall
  initialCalico: string
  /** 该组件是否要选 Tekton profile */
  showTektonProfile: boolean
  tektonProfile: string
  initialTektonProfile: string
  /** 该组件是否是 OTel 采集栈（Tempo / 采样 / MinIO / ES 表单） */
  showOtel: boolean
  /** 是否显示 Tempo 的 MinIO 存储区块（管理集群） */
  showTempoInstall: boolean
  otel: OtelForm
  initialOtel: string
  /** 该组件是否是对象存储（SeaweedFS：StorageClass / 容量 / 副本数） */
  showSeaweed: boolean
  seaweed: K8sSeaweedInstall
  initialSeaweed: string
  /** 该组件是否是 Harbor（访问域名） */
  showHarbor: boolean
  harbor: K8sHarborInstall
  initialHarbor: string
}

/** OTel 表单里两个嵌套对象始终存在（后端可以为空，但表单控件需要可绑定） */
type OtelForm = Omit<K8sOtelInstall, 'tempoStorage' | 'es' | 'tempoRetention' | 'tempoTlsMode'> & {
  tempoStorage: K8sTempoStorage
  es: K8sEsConfig
  tempoRetention: string
  tempoTlsMode: 'plaintext' | 'tls' | 'tls-skip-verify'
}

/**
 * Calico 支持的封装方式（Installation CR 的 calicoNetwork.ipPools[].encapsulation）。
 * 每种都写清"它到底做什么、什么时候选"，因为选错 = 跨节点 Pod 不通 / 直接断网。
 */
interface EncapsulationOption {
  value: string
  /** 下拉里的一行：名字 + 一句话 */
  label: string
  /** 选中后显示的完整说明 */
  hint: string
}

const ENCAPSULATIONS: EncapsulationOption[] = [
  {
    value: 'IPIP',
    label: 'IPIP —— 跨节点全封装（IP-in-IP）',
    hint: '所有跨节点 Pod 流量都走 IP-in-IP 隧道，同节点直通。对底层网络要求最低（节点间 IP 可达即可），'
      + '额外 20 字节头、节点网络需允许 IP 协议号 4。自建机房/裸金属通常选它。',
  },
  {
    value: 'VXLAN',
    label: 'VXLAN —— 跨节点全封装（UDP 4789）',
    hint: '跨节点流量封进 UDP 包。IPIP 被禁或被丢包的云环境（多数公有云）选它；'
      + '额外 50 字节头，开销比 IPIP 略高。',
  },
  {
    value: 'IPIPCrossSubnet',
    label: 'IPIPCrossSubnet —— 只在跨子网时 IPIP',
    hint: '同子网的节点之间走原生路由（性能更好），只有跨子网才封装。'
      + '前提是同子网节点二层可达；同网段集群/机房适用。',
  },
  {
    value: 'VXLANCrossSubnet',
    label: 'VXLANCrossSubnet —— 只在跨子网时 VXLAN',
    hint: '同上，但跨子网时用 VXLAN 封装；适合跨子网、又不希望同子网有封装开销的环境。',
  },
  {
    value: 'None',
    label: 'None —— 不封装（纯路由 / BGP）',
    hint: '性能最好，但要求网络能把每个节点的 Pod CIDR 路由过去（BGP 对等或静态路由）。'
      + '不满足时跨节点 Pod 完全不通，选前务必确认底层路由已打通。',
  },
]

function encapsulationHint(value: string): string {
  return ENCAPSULATIONS.find((e) => e.value === value)?.hint ?? ''
}

/** 默认封装方式（后端下发的 platform.calico.* 为空时兜底） */
const DEFAULT_ENCAPSULATION = 'IPIP'

const ACTION_LABEL: Record<ApplyDialog['action'], string> = {
  INSTALL: '安装', UPGRADE: '升级', EDIT: '编辑',
}

/** 容量写法（K8s quantity）：50Gi / 500Mi / 1Ti；不带单位按字节，几乎不会是本意，所以默认要求带单位 */
const SIZE_RE = /^[1-9]\d*(\.\d+)?(Ki|Mi|Gi|Ti|Pi|Ei|k|M|G|T|P|E)?$/
/** Harbor 访问地址：IP 或域名，不带 scheme/path/端口（与后端 HARBOR_HOST 同一条） */
const HARBOR_HOST_RE = /^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$/
const IPV4_RE = /^\d{1,3}(\.\d{1,3}){3}$/
/** 外部 Redis 单实例地址 host:port（与后端 HARBOR_REDIS_ADDR 同一条） */
const REDIS_ADDR_RE = /^[a-z0-9]([a-z0-9.-]*[a-z0-9])?:[0-9]{1,5}$/

/** 资源限制表单：requests/limits × cpu/memory 四格，提交时拼回 K8s 的 ResourceRequirements */
interface ResourceFormValues {
  requestsCpu: string
  requestsMemory: string
  limitsCpu: string
  limitsMemory: string
}

/** K8s quantity：CPU 100m / 0.5 / 1，内存 256Mi / 1Gi（不带单位按核/字节） */
const QUANTITY_RE = /^\d+(\.\d+)?(m|k|M|G|T|P|E|Ki|Mi|Gi|Ti|Pi|Ei)?$/

/** Prometheus/Thanos 时长：30d / 12h / 1h30m；留空 = 不设置（chart 默认） */
const DURATION_RE = /^(?:\d+(?:ms|s|m|h|d|w|y))+$/

/**
 * Thanos compactor 的保留期下限（thanos v0.42.4 `cmd/thanos/compact.go`）：
 * raw 非 0 时必须 ≥ 40h（否则 5m 降采样永远不发生）、5m 非 0 时必须 ≥ 10d（否则 1h 降采样永远不发生）。
 * 违反时 helm 装成功、compactor 却 CrashLoopBackOff（2026-10-20 实锤：三档全填 1d）。
 * 前端先拦一道给出人话，后端在下发 helm 前同样会拒。
 */
const RETENTION_MINIMUMS: Record<string, { ms: number; label: string; why: string }> = {
  'compactor.retention.resolutionRaw': {
    ms: 40 * 3_600_000,
    label: '40h',
    why: 'Thanos 要保留 40 小时以上的原始块才能做 5m 降采样',
  },
  'compactor.retention.resolution5m': {
    ms: 10 * 86_400_000,
    label: '10d',
    why: 'Thanos 要保留 10 天以上的 5m 块才能做 1h 降采样',
  },
}

const DURATION_UNIT_MS: Record<string, number> = {
  ms: 1,
  s: 1_000,
  m: 60_000,
  h: 3_600_000,
  d: 86_400_000,
  w: 604_800_000,
  y: 31_536_000_000,
}

/** 把 30d / 1h30m 这样的时长换成毫秒；格式不合法返回 null */
function durationMs(text: string): number | null {
  const value = text.trim()
  if (!value || !DURATION_RE.test(value)) return null
  let total = 0
  for (const m of value.matchAll(/(\d+)(ms|s|m|h|d|w|y)/g)) {
    const amount = Number(m[1] ?? 0)
    const unit = m[2] ?? ''
    total += amount * (DURATION_UNIT_MS[unit] ?? 0)
  }
  return total
}

/** Service 端口表单：一行一个端口（同时描述 Gateway listener） */
interface PortRow {
  name: string
  port: string
  targetPort: string
  protocol: string
  nodePort: string
  /** Gateway listener 协议：HTTP / HTTPS / TLS / TCP / UDP；空 = 仅 Service，不监听 */
  listener: string
  /** HTTPS/TLS 的 TLS 模式：Terminate / Passthrough */
  tlsMode: string
  /** Terminate 的证书 Secret；Passthrough 不需要 */
  tlsSecret: string
}

const PORT_PROTOCOLS = ['TCP', 'UDP', 'SCTP']
const LISTENER_PROTOCOLS = ['HTTP', 'HTTPS', 'TLS', 'TCP', 'UDP']

function toPortRows(value: unknown): PortRow[] {
  if (!Array.isArray(value)) return []
  return value.map((p: any) => ({
    name: String(p?.name ?? ''),
    port: p?.port == null ? '' : String(p.port),
    targetPort: p?.targetPort == null ? '' : String(p.targetPort),
    protocol: String(p?.protocol ?? 'TCP'),
    nodePort: p?.nodePort == null ? '' : String(p.nodePort),
    listener: String(p?.listener ?? ''),
    // HTTPS 只能是 Terminate（Gateway API 定义）；历史快照里就算是别的值也归一
    tlsMode: p?.listener === 'HTTPS' ? 'Terminate'
      : String(p?.tlsMode ?? (p?.listener === 'TLS' ? 'Passthrough' : '')),
    tlsSecret: String(p?.tlsSecret ?? ''),
  }))
}

/** 空字段不写进 values（targetPort 省略时 K8s 默认等于 port；nodePort 只有 NodePort 场景才填） */
function fromPortRows(rows: PortRow[]): Array<Record<string, unknown>> {
  return rows.map((r) => {
    const out: Record<string, unknown> = {
      name: r.name.trim(),
      port: Number(r.port.trim()),
      protocol: r.protocol,
    }
    const target = r.targetPort.trim()
    if (target) out.targetPort = /^\d+$/.test(target) ? Number(target) : target
    const node = r.nodePort.trim()
    if (node) out.nodePort = Number(node)
    const listener = r.listener.trim()
    // ⚠️ 空串也要提交：用来区分「用户选了仅 Service」和「老快照里没有 listener 字段」——
    //    后端只给"缺失"的行补默认监听，空串是明确的"不监听"（2026-10-20 实报：选仅 Service 仍被加进 CR）
    out.listener = listener
    if (listener === 'HTTPS' || listener === 'TLS') {
      const mode = r.tlsMode.trim() || (listener === 'HTTPS' ? 'Terminate' : 'Passthrough')
      out.tlsMode = mode
      if (mode === 'Terminate' && r.tlsSecret.trim()) out.tlsSecret = r.tlsSecret.trim()
    }
    return out
  })
}

/** 端口表单校验：返回错误文案，null = 通过（名称/端口在 Service 里都要求唯一）
 *  只校验格式与唯一性，不做范围限制 —— nodePort 可用范围由集群的 --service-node-port-range 决定，
 *  前端写死 30000-32767 会误伤自定义范围的集群（2026-10-20 用户要求去掉）。 */
function validatePortRows(rows: PortRow[]): string | null {
  if (!rows.length) return '至少保留一个端口'
  const names = new Set<string>()
  const keys = new Set<string>()
  const nodePorts = new Set<string>()
  for (const r of rows) {
    const name = r.name.trim()
    const port = r.port.trim()
    if (!name) return '端口名称不能为空'
    if (names.has(name)) return `端口名称重复：${name}`
    names.add(name)
    if (!/^\d+$/.test(port)) return `「${name}」的端口需要是数字`
    const key = `${port}/${r.protocol}`
    if (keys.has(key)) return `端口重复：${key}`
    keys.add(key)
    const target = r.targetPort.trim()
    if (target && !/^\d+$/.test(target) && !/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(target)) {
      return `「${name}」的 targetPort 需要是数字或端口名`
    }
    const node = r.nodePort.trim()
    if (node) {
      if (!/^\d+$/.test(node)) return `「${name}」的 nodePort 需要是数字`
      if (nodePorts.has(node)) return `nodePort 重复：${node}`
      nodePorts.add(node)
    }
    const listener = r.listener.trim()
    if (listener && !LISTENER_PROTOCOLS.includes(listener)) {
      return `「${name}」的监听协议不支持：${listener}`
    }
    if (listener === 'HTTPS' || listener === 'TLS') {
      const mode = r.tlsMode.trim() || (listener === 'HTTPS' ? 'Terminate' : 'Passthrough')
      if (mode !== 'Terminate' && mode !== 'Passthrough') {
        return `「${name}」的 TLS 模式不支持：${mode}`
      }
      if (listener === 'HTTPS' && mode === 'Passthrough') {
        return `「${name}」的 HTTPS 只能 Terminate`
      }
      if (mode === 'Terminate' && !r.tlsSecret.trim()) {
        return `「${name}」选 Terminate 时必须填证书 Secret`
      }
    }
  }
  return null
}

function toResourceForm(value: unknown): ResourceFormValues {
  const v = (value ?? {}) as {
    requests?: { cpu?: unknown; memory?: unknown }
    limits?: { cpu?: unknown; memory?: unknown }
  }
  const text = (x: unknown): string => (x == null ? '' : String(x))
  return {
    requestsCpu: text(v.requests?.cpu),
    requestsMemory: text(v.requests?.memory),
    limitsCpu: text(v.limits?.cpu),
    limitsMemory: text(v.limits?.memory),
  }
}

/** 空值不写进 values（空串不是合法 quantity；缺的键由后端深合并回落到平台默认值） */
function fromResourceForm(f: ResourceFormValues): Record<string, Record<string, string>> {
  const requests: Record<string, string> = {}
  const limits: Record<string, string> = {}
  if (f.requestsCpu.trim()) requests.cpu = f.requestsCpu.trim()
  if (f.requestsMemory.trim()) requests.memory = f.requestsMemory.trim()
  if (f.limitsCpu.trim()) limits.cpu = f.limitsCpu.trim()
  if (f.limitsMemory.trim()) limits.memory = f.limitsMemory.trim()
  const out: Record<string, Record<string, string>> = {}
  if (Object.keys(requests).length) out.requests = requests
  if (Object.keys(limits).length) out.limits = limits
  return out
}

const EMPTY_THANOS: K8sThanosInstall = {
  namespace: 'monitor', secretName: '', endpoint: '', bucket: '', insecureSkipVerify: false,
}

/**
 * 对象存储（SeaweedFS）表单默认值。
 * StorageClass 有意留空（必须由用户从集群下拉里选）—— 空串会被后端拒，
 * 否则 chart 会把空串原样写进 storageClassName（K8s 里 = 不用动态供给 → PVC 永远 Pending）。
 */
const EMPTY_SEAWEED: K8sSeaweedInstall = {
  storageClass: '',
  masterSize: '1Gi',
  volumeSize: '30Gi',
  filerSize: '10Gi',
  masterReplicas: 1,
  volumeReplicas: 1,
  s3Replicas: 1,
  adminEnabled: false,
  s3Endpoint: 'http://seaweedfs-s3.platform-storage:8333',
}

/** Harbor 表单默认值：默认 nodePort（无需 istio，可直接用 IP） */
const EMPTY_HARBOR: K8sHarborInstall = {
  exposeMode: 'nodePort', host: '', nodePort: 30002, port: 80,
  databaseMode: 'internal', dbPort: 5432, dbName: 'registry', dbSslMode: 'disable',
  redisMode: 'internal',
}

/** OTel 表单的默认值：地址只做预填，全部可改（spec §6.2） */
function emptyOtel(): OtelForm {
  return {
    tempoEndpoint: '',
    samplingPercentage: 10,
    gatewayReplicas: 1,
    logsEnabled: false,
    tempoRetention: '168h',
    tempoTlsMode: 'plaintext',
    tempoStorage: {
      endpoint: '192.168.31.80:9002', bucket: 'tempo',
      credentialSecret: { namespace: 'monitor', name: '' },
      insecureSkipVerify: true, plaintext: false,
    },
    es: {
      endpoint: 'https://es-es-http.default.svc:9200',
      credentialSecret: { namespace: 'monitor', name: 'es-es-elastic-user' },
      insecureSkipVerify: true,
    },
  }
}

const harborProjects = ref(false)
function openHarborProjects(): void {
  harborProjects.value = true
}

const applyDialog = ref<ApplyDialog>({
  visible: false, code: '', name: '', action: 'INSTALL',
  version: '', versions: [], chartVersion: '', fields: [], values: {}, jsonText: {}, portRows: {}, initial: {},
  showWebhook: false, webhookUrl: '', initialWebhook: '',
  showThanos: false, thanosUrl: '', initialThanos: '',
  showThanosInstall: false, thanos: { ...EMPTY_THANOS },
  initialThanosInstall: JSON.stringify(EMPTY_THANOS),
  storageClasses: [], secretOptions: [], gatewaySecretOptions: [],
  otelMinioSecretOptions: [], esSecretOptions: [],
  showCalico: false, calico: { podCidr: '', encapsulation: DEFAULT_ENCAPSULATION },
  initialCalico: '',
  showTektonProfile: false, tektonProfile: 'all', initialTektonProfile: 'all',
  showOtel: false, showTempoInstall: false, otel: emptyOtel(), initialOtel: JSON.stringify(emptyOtel()),
  showSeaweed: false, seaweed: { ...EMPTY_SEAWEED }, initialSeaweed: JSON.stringify(EMPTY_SEAWEED),
  showHarbor: false, harbor: { ...EMPTY_HARBOR }, initialHarbor: JSON.stringify(EMPTY_HARBOR),
})
function openInstall(row: K8sComponentStatus): void {
  openApply(row, 'INSTALL')
}

/** Thanos 对象存储分组：这个组的特殊块（凭证/桶）和保留期字段在同一节里渲染 */
const THANOS_GROUP = 'Thanos 对象存储（管理集群）'

/** 可编辑字段的分组顺序 = recipe 里字段首次出现的顺序（后端下发 group） */
const genericGroups = computed(() => {
  const groups: string[] = []
  for (const f of applyDialog.value.fields) {
    const g = f.group || '其他配置'
    if (g === THANOS_GROUP) continue // 跟 Thanos 凭据块一起渲染
    if (!groups.includes(g)) groups.push(g)
  }
  return groups
})

function fieldsOf(group: string): K8sEditableField[] {
  return applyDialog.value.fields.filter((f) => (f.group || '其他配置') === group)
}

/** Thanos 保留期（compactor.retention.*）：放在 Thanos 凭据块之后 */
const thanosFields = computed(() => fieldsOf(THANOS_GROUP))

/** 端口列表加一行（默认 TCP，空值等用户填） */
function addPortRow(key: string): void {
  const rows = applyDialog.value.portRows[key] ?? []
  applyDialog.value.portRows[key] = rows
  rows.push({ name: '', port: '', targetPort: '', protocol: 'TCP', nodePort: '',
    listener: '', tlsMode: '', tlsSecret: '' })
}

/** 切监听协议：HTTPS 固定 Terminate；TLS 默认 Passthrough；非 TLS 协议清空证书 */
function onListenerChange(p: PortRow): void {
  if (p.listener === 'HTTPS') {
    p.tlsMode = 'Terminate'
  } else if (p.listener === 'TLS') {
    p.tlsMode = p.tlsMode || 'Passthrough'
  } else {
    p.tlsMode = ''
    p.tlsSecret = ''
  }
}

function openUpgrade(row: K8sComponentStatus): void {
  openApply(row, 'UPGRADE')
}

function openApply(row: K8sComponentStatus, action: 'INSTALL' | 'UPGRADE' | 'EDIT'): void {
  const versions = action === 'UPGRADE' ? upgradeTargets(row) : []
  if (action === 'UPGRADE' && !versions.length) return
  const editing = action === 'EDIT'
  // 编辑表单预填**当前生效**的地址（上次成功操作的快照）；安装表单预填平台默认值
  const webhookInitial = (editing
    ? row.alertWebhookUrl ?? row.defaultAlertWebhookUrl
    : row.defaultAlertWebhookUrl) ?? ''
  const thanosInitial = (editing
    ? row.thanosReceiveUrl ?? row.defaultThanosReceiveUrl
    : row.defaultThanosReceiveUrl) ?? ''
  const fields = row.editableFields ?? []
  const values: Record<string, any> = {}
  const jsonText: Record<string, string> = {}
  const portRows: Record<string, PortRow[]> = {}
  const initial: Record<string, string> = {}
  for (const f of fields) {
    // 有安装记录时预填**当前生效值**（后端从上次成功快照里取）；否则用 recipe 默认值。
    // 只显示默认值会让用户改完再打开看到旧值，以为没生效。
    // ⚠️ 后端没值时字段**不出现**；这里仍按 `!= null` 兜底（null 也视为无值），
    //    否则 `"currentValue": null` 会让输入框显示为空。
    const start = f.currentValue != null ? f.currentValue : f.defaultValue
    if (f.type === 'json') {
      const text = JSON.stringify(start ?? {}, null, 2)
      jsonText[f.key] = text
      initial[f.key] = text
    } else if (f.type === 'resources') {
      // 四个输入框平铺在 values 里（key 加后缀），初始快照也走同一个拼装函数，
      // 保证「有没有改动」的比较口径一致
      const form = toResourceForm(start)
      values[`${f.key}.requestsCpu`] = form.requestsCpu
      values[`${f.key}.requestsMemory`] = form.requestsMemory
      values[`${f.key}.limitsCpu`] = form.limitsCpu
      values[`${f.key}.limitsMemory`] = form.limitsMemory
      initial[f.key] = JSON.stringify(fromResourceForm(form))
    } else if (f.type === 'ports') {
      const rows = toPortRows(start)
      portRows[f.key] = rows
      initial[f.key] = JSON.stringify(fromPortRows(rows))
    } else {
      values[f.key] = start
      initial[f.key] = JSON.stringify(start)
    }
  }
  // 编辑时回填当前生效的 Thanos 参数（凭证 Secret / MinIO 地址与桶 / 跳过 HTTPS 校验都能改）
  const thanos: K8sThanosInstall = editing && row.thanosInstallCurrent
    ? { ...EMPTY_THANOS, ...row.thanosInstallCurrent, namespace: THANOS_NAMESPACE }
    : { ...EMPTY_THANOS }
  const calico: K8sCalicoInstall = {
    podCidr: row.defaultPodCidr ?? '',
    encapsulation: row.defaultEncapsulation || DEFAULT_ENCAPSULATION,
  }
  // 编辑时用当前生效的 profile；安装时用后端下发的默认值（all）
  const tektonProfile = editing
    ? (row.tektonProfileCurrent ?? row.defaultTektonProfile ?? 'all')
    : (row.defaultTektonProfile ?? 'all')
  // OTel：编辑时回填上次成功操作的值；安装时用默认值预填（地址/桶/索引都可改）
  const baseOtel = emptyOtel()
  const otelCurrent = editing ? row.otelInstallCurrent : null
  const otel: OtelForm = {
    tempoEndpoint: ((editing ? row.tempoEndpoint ?? row.defaultTempoEndpoint : row.defaultTempoEndpoint) ?? ''),
    samplingPercentage: otelCurrent?.samplingPercentage ?? baseOtel.samplingPercentage,
    gatewayReplicas: otelCurrent?.gatewayReplicas ?? baseOtel.gatewayReplicas,
    logsEnabled: otelCurrent?.logsEnabled ?? baseOtel.logsEnabled,
    tempoRetention: otelCurrent?.tempoRetention ?? baseOtel.tempoRetention,
    tempoTlsMode: (otelCurrent?.tempoTlsMode as OtelForm['tempoTlsMode']) ?? baseOtel.tempoTlsMode,
    tempoStorage: { ...baseOtel.tempoStorage, ...(otelCurrent?.tempoStorage ?? {}) },
    es: { ...baseOtel.es, ...(otelCurrent?.es ?? {}) },
  }
  // 凭证 Secret 只能放 monitor（表单不提供命名空间选择）；旧快照里别的命名空间值在这里归一
  otel.tempoStorage.credentialSecret.namespace = OTEL_NAMESPACE
  otel.es.credentialSecret.namespace = OTEL_NAMESPACE
  // 对象存储：编辑时回填上次成功的参数（容量/副本数/管理台可改）；StorageClass 没记录时保持空、必须重选
  const seaweedMerged: K8sSeaweedInstall = editing && row.seaweedCurrent
    ? { ...EMPTY_SEAWEED, ...row.seaweedCurrent }
    : { ...EMPTY_SEAWEED }
  // 后端快照里缺省字段是 null（不是 undefined），统一收敛成默认值，开关与数字控件才不会显示空
  const seaweed: K8sSeaweedInstall = {
    ...seaweedMerged,
    masterReplicas: seaweedMerged.masterReplicas ?? EMPTY_SEAWEED.masterReplicas,
    volumeReplicas: seaweedMerged.volumeReplicas ?? EMPTY_SEAWEED.volumeReplicas,
    s3Replicas: seaweedMerged.s3Replicas ?? EMPTY_SEAWEED.s3Replicas,
    adminEnabled: seaweedMerged.adminEnabled ?? false,
    s3Endpoint: seaweedMerged.s3Endpoint || EMPTY_SEAWEED.s3Endpoint,
  }
  // Harbor：编辑时回填上次成功的域名；安装时留空必填
  const harborMerged: K8sHarborInstall = editing && row.harborCurrent
    ? { ...EMPTY_HARBOR, ...row.harborCurrent }
    : { ...EMPTY_HARBOR }
  const harbor: K8sHarborInstall = {
    ...harborMerged,
    exposeMode: harborMerged.exposeMode || 'nodePort',
    host: harborMerged.host || '',
    nodePort: harborMerged.nodePort ?? 30002,
    port: harborMerged.port ?? 80,
    databaseMode: harborMerged.databaseMode || 'internal',
    dbPort: harborMerged.dbPort ?? 5432,
    dbName: harborMerged.dbName || 'registry',
    dbSslMode: harborMerged.dbSslMode || 'disable',
    redisMode: harborMerged.redisMode || 'internal',
  }
  applyDialog.value = {
    visible: true, code: row.code, name: row.name, action,
    version: versions[0] ?? '', versions, chartVersion: installedChartVersion(row) ?? '',
    fields, values, jsonText, portRows, initial,
    showWebhook: !!row.platformAlerting,
    webhookUrl: webhookInitial,
    initialWebhook: webhookInitial.trim(),
    showThanos: !!row.thanosRemoteWrite,
    thanosUrl: thanosInitial,
    initialThanos: thanosInitial.trim(),
    // 编辑也能改 Thanos 的凭证/MinIO 参数；Calico 网络参数是安装期决定，编辑时不显示
    showThanosInstall: !!row.thanosInstall,
    thanos,
    initialThanosInstall: JSON.stringify(thanos),
    storageClasses: [], secretOptions: [], gatewaySecretOptions: [],
    otelMinioSecretOptions: [], esSecretOptions: [],
    showCalico: !!row.calicoNetwork && !editing,
    calico,
    initialCalico: JSON.stringify(calico),
    showTektonProfile: !!row.tektonProfile,
    tektonProfile,
    initialTektonProfile: tektonProfile,
    showOtel: !!row.otelInstrumentation,
    showTempoInstall: !!row.tempoInstall,
    otel,
    initialOtel: JSON.stringify(otel),
    showSeaweed: !!row.seaweedStorage,
    seaweed,
    initialSeaweed: JSON.stringify(seaweed),
    showHarbor: !!row.harborRegistry,
    harbor,
    initialHarbor: JSON.stringify(harbor),
  }
  if (row.thanosInstall) {
    void loadThanosOptions()
  }
  if (row.seaweedStorage || fields.some((f) => f.type === 'storageClass')) {
    void loadStorageClassOptions()
  }
  if (fields.some((f) => f.key === 'service.ports')) {
    void loadGatewayOptions()
  }
  if (row.otelInstrumentation) {
    // Secret 候选懒加载；拿不到就留空 —— 下拉是 filterable + allow-create，仍可手输
    if (row.tempoInstall) void loadOtelMinioSecrets()
    if (otel.logsEnabled) void loadEsSecrets()
  }
}

/** Thanos 的部署命名空间（recipe 固定为 monitor，凭证只允许在这个命名空间里找） */
const THANOS_NAMESPACE = 'monitor'
/** OTel 的凭证 Secret 统一放 monitor（MinIO 与 ES 都是，后端同样钉死） */
const OTEL_NAMESPACE = 'monitor'
/** Istio 对外入口 Gateway 的证书 Secret 从 istio-system 选（Gateway CR 在同命名空间） */
const ISTIO_NAMESPACE = 'istio-system'

/** 端口表的证书候选：istio-system 下的 Secret；失败留空（下拉可手输） */
async function loadGatewayOptions(): Promise<void> {
  const d = applyDialog.value
  if (!d.fields.some((f) => f.key === 'service.ports')) return
  try {
    d.gatewaySecretOptions = (await props.loadSecretOptions?.(ISTIO_NAMESPACE)) ?? []
  } catch {
    d.gatewaySecretOptions = []
  }
}

/** Thanos 表单的 Secret 候选：懒加载，失败就留空（用户可以手输） */
async function loadThanosOptions(): Promise<void> {
  const d = applyDialog.value
  if (!d.showThanosInstall) return
  d.thanos.namespace = THANOS_NAMESPACE
  try {
    d.secretOptions = (await props.loadSecretOptions?.(THANOS_NAMESPACE)) ?? []
  } catch {
    d.secretOptions = []
  }
}

/**
 * Tempo 的 MinIO 凭证 Secret 候选：只查 monitor（后端 resolveTempoStorage 同样钉死）。
 */
async function loadOtelMinioSecrets(): Promise<void> {
  const d = applyDialog.value
  if (!d.showOtel || !d.showTempoInstall) return
  try {
    d.otelMinioSecretOptions = (await props.loadSecretOptions?.(OTEL_NAMESPACE)) ?? []
  } catch {
    d.otelMinioSecretOptions = []
  }
}

/**
 * ES 凭证 Secret 候选：同样只查 monitor（后端 resolveEs 校验）。
 * ECK 的 {@code es-es-elastic-user} 默认在 default 命名空间，需要管理员先复制到 monitor。
 */
async function loadEsSecrets(): Promise<void> {
  const d = applyDialog.value
  if (!d.showOtel || !d.otel.logsEnabled) return
  try {
    d.esSecretOptions = (await props.loadSecretOptions?.(OTEL_NAMESPACE)) ?? []
  } catch {
    d.esSecretOptions = []
  }
}

/** 存储类候选：集群里有什么就列什么；拿不到就留空（仍可手输） */
async function loadStorageClassOptions(): Promise<void> {
  const d = applyDialog.value
  try {
    d.storageClasses = (await props.loadStorageClasses?.()) ?? []
  } catch {
    d.storageClasses = []
  }
}

function confirmApply(): void {
  const d = applyDialog.value
  // 对象存储：StorageClass 必填（后端也会拒；这里先挡一下，省一次往返）
  if (d.showSeaweed && !d.seaweed.storageClass) {
    ElMessage.error('请选择对象存储的 StorageClass（PVC 需要动态供给）')
    return
  }
  if (d.showHarbor) {
    const h = d.harbor
    const host = h.host.trim()
    if (!HARBOR_HOST_RE.test(host)) {
      ElMessage.error('Harbor 访问地址填 IP 或域名（不带 http://、路径与端口）')
      return
    }
    if (h.exposeMode === 'route') {
      if (host.includes(':')) {
        ElMessage.error('route 模式的域名不能带端口')
        return
      }
      if (IPV4_RE.test(host)) {
        ElMessage.error('route 模式必须用域名（Gateway API 不允许 IP）；IP 直连请选 NodePort / LoadBalancer')
        return
      }
    }
    if (h.exposeMode === 'nodePort' && (!h.nodePort || h.nodePort < 1 || h.nodePort > 65535)) {
      ElMessage.error('NodePort 必须在 1~65535（默认 30002）')
      return
    }
    if (h.exposeMode === 'loadBalancer' && (!h.port || h.port < 1 || h.port > 65535)) {
      ElMessage.error('Service 端口必须在 1~65535（默认 80）')
      return
    }
    if (h.databaseMode === 'external') {
      if (!h.dbHost || !HARBOR_HOST_RE.test(h.dbHost.trim())) {
        ElMessage.error('外部数据库地址填 IP 或域名（不带端口）')
        return
      }
      if (!h.dbPort || h.dbPort < 1 || h.dbPort > 65535) {
        ElMessage.error('外部数据库端口必须在 1~65535（默认 5432）')
        return
      }
      if (!h.dbUsername?.trim()) {
        ElMessage.error('请填写外部数据库用户名')
        return
      }
      if (!h.dbPassword && d.action === 'INSTALL') {
        ElMessage.error('首次安装必须填写外部数据库口令（编辑时留空 = 不改）')
        return
      }
    }
    if (h.redisMode === 'external') {
      if (!h.redisAddr || !REDIS_ADDR_RE.test(h.redisAddr.trim())) {
        ElMessage.error('外部 Redis 地址应形如 redis.internal:6379')
        return
      }
      if (!h.redisPassword && d.action === 'INSTALL') {
        ElMessage.error('首次安装必须填写外部 Redis 口令（编辑时留空 = 不改）')
        return
      }
    }
  }
  const editable: Record<string, unknown> = {}
  for (const f of d.fields) {
    if (f.type === 'resources') {
      const form: ResourceFormValues = {
        requestsCpu: String(d.values[`${f.key}.requestsCpu`] ?? ''),
        requestsMemory: String(d.values[`${f.key}.requestsMemory`] ?? ''),
        limitsCpu: String(d.values[`${f.key}.limitsCpu`] ?? ''),
        limitsMemory: String(d.values[`${f.key}.limitsMemory`] ?? ''),
      }
      const cpuFields = [form.requestsCpu, form.limitsCpu].map((v) => v.trim()).filter(Boolean)
      const memFields = [form.requestsMemory, form.limitsMemory].map((v) => v.trim()).filter(Boolean)
      if (cpuFields.some((v) => !QUANTITY_RE.test(v))) {
        ElMessage.error(`「${f.label}」的 CPU 需要写成 100m、0.5、1 这样的值`)
        return
      }
      if (memFields.some((v) => !QUANTITY_RE.test(v))) {
        ElMessage.error(`「${f.label}」的内存需要写成 256Mi、1Gi 这样的值`)
        return
      }
      const value = fromResourceForm(form)
      if (JSON.stringify(value) === d.initial[f.key]) continue
      editable[f.key] = value
    } else if (f.type === 'ports') {
      const rows = d.portRows[f.key] ?? []
      const error = validatePortRows(rows)
      if (error) {
        ElMessage.error(`「${f.label}」${error}`)
        return
      }
      const value = fromPortRows(rows)
      if (JSON.stringify(value) === d.initial[f.key]) continue
      editable[f.key] = value
    } else if (f.type === 'duration') {
      const value = String(d.values[f.key] ?? '').trim()
      if (JSON.stringify(value) === d.initial[f.key]) continue
      if (value && !DURATION_RE.test(value)) {
        ElMessage.error(`「${f.label}」需要写成 30d、12h 这样的时长（留空 = 不改动）`)
        return
      }
      // Thanos compactor 起不来的组合先在这里拦住（后端也会拒，这里是即时反馈）
      const minimum = RETENTION_MINIMUMS[f.key]
      if (value && minimum) {
        const ms = durationMs(value)
        if (ms !== null && ms < minimum.ms) {
          ElMessage.error(`「${f.label}」不能小于 ${minimum.label}：${minimum.why}（否则 compactor 启动即崩溃）`)
          return
        }
      }
      editable[f.key] = value
    } else if (f.type === 'json') {
      const text = d.jsonText[f.key] ?? ''
      if (text === d.initial[f.key]) continue
      try {
        editable[f.key] = JSON.parse(text)
      } catch {
        ElMessage.error(`「${f.label}」不是合法 JSON`)
        return
      }
    } else if (f.type === 'storageClass') {
      // 空 = 跟随集群默认存储类 → 不提交。空串写进 values 会被 K8s 当成"不用动态供给"（PVC 永远 Pending）
      const value = String(d.values[f.key] ?? '').trim()
      if (!value || JSON.stringify(value) === d.initial[f.key]) continue
      editable[f.key] = value
    } else if (f.type === 'size') {
      const value = String(d.values[f.key] ?? '').trim()
      if (JSON.stringify(value) === d.initial[f.key]) continue
      if (!SIZE_RE.test(value)) {
        ElMessage.error(`「${f.label}」需要写成 50Gi、500Mi 这样的容量`)
        return
      }
      editable[f.key] = value
    } else {
      const value = typeof d.values[f.key] === 'string' ? d.values[f.key].trim() : d.values[f.key]
      if (JSON.stringify(value) === d.initial[f.key]) continue
      editable[f.key] = value
    }
  }
  d.visible = false
  emit('apply', {
    code: d.code,
    // EDIT 复用 UPGRADE：按**平台内置版本**重放（幂等），只是不带版本选择。
    // ⚠️ 不要回传「已装版本」：存量操作记录可能记着历史/错误的版本（Istio 曾把 Gateway API 的
    //    1.6.2 记成组件版本），回传它会被后端的「目标版本必须是主 release」校验拒掉
    //    （报「未知的目标版本：1.6.2」）。不传时后端按主 release 解析，顺带把记录修正回来。
    action: d.action === 'EDIT' ? 'UPGRADE' : d.action,
    targetVersion: d.action === 'UPGRADE' ? d.version : undefined,
    editableValues: Object.keys(editable).length ? editable : undefined,
    // 两个地址：只发改动过的 —— 没改的由后端沿用上次成功操作里记的值（否则升级会被平台默认值覆盖）
    alertWebhookUrl: d.showWebhook && d.webhookUrl.trim() && d.webhookUrl.trim() !== d.initialWebhook
      ? d.webhookUrl.trim() : undefined,
    thanosReceiveUrl: d.showThanos && d.thanosUrl.trim() && d.thanosUrl.trim() !== d.initialThanos
      ? d.thanosUrl.trim() : undefined,
    // Thanos 安装参数：整组没改动就不发，后端沿用上次成功操作里记的
    thanosInstall: d.showThanosInstall && JSON.stringify(d.thanos) !== d.initialThanosInstall
      ? { ...d.thanos } : undefined,
    calicoInstall: d.showCalico && JSON.stringify(d.calico) !== d.initialCalico
      ? { ...d.calico } : undefined,
    tektonProfile: d.showTektonProfile && d.tektonProfile !== d.initialTektonProfile
      ? d.tektonProfile : undefined,
    // OTel：安装时整组下发（表单就是默认值的来源）；编辑时只发改动过的，没改的沿用上次快照
    otel: d.showOtel && (d.action === 'INSTALL' || JSON.stringify(d.otel) !== d.initialOtel)
      ? JSON.parse(JSON.stringify(d.otel)) as K8sOtelInstall : undefined,
    // 对象存储：安装时整组下发；编辑时只发改动过的（没改的由后端沿用上次快照）
    seaweed: d.showSeaweed && (d.action === 'INSTALL' || JSON.stringify(d.seaweed) !== d.initialSeaweed)
      ? { ...d.seaweed } : undefined,
    // Harbor：安装时整组下发；编辑时只发改动过的
    harbor: d.showHarbor && (d.action === 'INSTALL' || JSON.stringify(d.harbor) !== d.initialHarbor)
      ? { ...d.harbor, host: d.harbor.host.trim() } : undefined,
  })
}

/** 操作状态 → 色标（列与详情弹窗共用） */
const OP_STATUS: Record<K8sComponentOperation['status'], {
  label: string
  type: 'success' | 'info' | 'warning' | 'danger'
}> = {
  PENDING: { label: '排队中', type: 'info' },
  RUNNING: { label: '进行中', type: 'warning' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  UNKNOWN: { label: '中断', type: 'info' },
  CANCELED: { label: '已中止', type: 'info' },
}

/** 上次操作色标（模板里 Safe 访问，避免 Record 索引的 undefined 类型） */
function opTag(code: string): {
  present: boolean
  label: string
  type: 'success' | 'info' | 'warning' | 'danger'
  tooltip: string
} {
  const op = props.operations[code]
  if (!op) {
    return { present: false, label: '', type: 'info', tooltip: '' }
  }
  const meta = OP_STATUS[op.status]
  return { present: true, label: meta.label, type: meta.type,
    tooltip: `${op.finishedAt ?? '进行中'} · ${op.operator ?? ''}` }
}

const operationDialog = ref<{
  visible: boolean
  name: string
  op: K8sComponentOperation | null
}>({ visible: false, name: '', op: null })

function openOperation(row: K8sComponentStatus): void {
  const op = props.operations[row.code]
  if (!op) return
  operationDialog.value = { visible: true, name: row.name, op }
}

/**
 * 中止进行中的操作：只把平台侧记录置为终态（CANCELED），**不会 kill helm**。
 * 典型场景：安装途中重启了 platform-api，worker 随进程消失，记录一直占着并发锁。
 */
async function abandonOperation(): Promise<void> {
  const op = operationDialog.value.op
  if (!op) return
  try {
    await ElMessageBox.confirm(
      '中止只会释放平台侧的并发锁，不会终止正在运行的 helm。\n'
      + '如果 helm 其实还在执行，下一次提交会被 helm 的 release 锁拒绝（报错可见，不会静默并发）；'
      + '稍后可在集群里确认 release 状态。',
      '中止操作',
      { type: 'warning', confirmButtonText: '中止', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  emit('abandon', op.id)
  operationDialog.value.visible = false
}

/** 复制 helm 输出：http 内网下 navigator.clipboard 不存在，必须带 execCommand 兜底 */
async function copyLog(): Promise<void> {
  const text = operationDialog.value.op?.logTail ?? ''
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      document.body.removeChild(area)
    }
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

const rows = computed(() => props.statuses)

const healthyCount = computed(() =>
  props.statuses.filter((s) => s.state === 'INSTALLED_HEALTHY' || s.state === 'INSTALLED_UNHEALTHY').length)
const missingCount = computed(() => props.statuses.filter((s) => s.state === 'NOT_INSTALLED').length)
const unknownCount = computed(() => props.statuses.filter((s) => s.state === 'UNKNOWN').length)
/** 缺组件波及的功能数（去重） —— 这就是台账说的"新的那一层" */
const affectedFeatures = computed(() => {
  const codes = new Set<string>()
  for (const s of props.statuses) {
    if (s.state === 'NOT_INSTALLED') s.features.forEach((f) => codes.add(f))
  }
  return codes.size
})

/** 摘要一行：`11 个组件中 10 个已装，缺 1 个会影响 1 项平台功能` */
const summary = computed(() => {
  if (!props.statuses.length) return props.loading ? '正在探测…' : '—'
  const total = props.statuses.length
  const parts = [`${total} 个组件中 ${healthyCount.value} 个已装`]
  if (missingCount.value) parts.push(`缺 ${missingCount.value} 个会影响 ${affectedFeatures.value} 项平台功能`)
  if (unknownCount.value) parts.push(`${unknownCount.value} 个未探测`)
  return parts.join('，')
})

const anyUnknown = computed(() => unknownCount.value > 0)
</script>

<template>
  <div class="cc-panel">
    <div class="cc-head">
      <span class="cc-summary">{{ summary }}</span>
      <el-button size="small" :loading="loading" :disabled="!canRefresh" @click="emit('refresh')">
        重新探测
      </el-button>
    </div>

    <div v-if="anyUnknown" class="cc-warn">
      集群当前探测不到，以下标记「未探测」的组件状态可能不准确。
    </div>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column label="组件" min-width="190">
        <template #default="{ row }">
          <span class="cc-name">{{ row.name }}</span>
          <code class="cc-code">{{ row.code }}</code>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <!-- 四态各一态：UNKNOWN 用虚线边与 NOT_INSTALLED 区分开 -->
          <el-tag size="small" :type="STATE[row.state as K8sComponentState].type"
                  :effect="STATE[row.state as K8sComponentState].effect">
            {{ STATE[row.state as K8sComponentState].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="190">
        <template #header>
          <span class="cc-ver-head">
            版本
            <el-tooltip placement="top"
                        content="应用 version 来自集群探测（镜像 tag / 版本标签）；chart 是已装的 chart 版本（组件本体 / 主 release，取最近一次成功记录，不是平台内置的版本）。kube-prometheus-stack 这类 chart 版本与应用版本是两套编号；多 chart 组件点「N 个 chart」看逐项版本">
              <el-icon class="cc-ver-help"><QuestionFilled /></el-icon>
            </el-tooltip>
          </span>
        </template>
        <template #default="{ row }">
          <!-- 两个版本都显示并各自标注：只显示一个时，92.10.1（chart）与 v0.93.1（应用）
               会让人以为是同一个东西的不同写法 -->
          <div class="cc-ver">
            <span class="cc-ver-line">
              <span class="cc-ver-kind">应用</span>
              <code class="mono">{{ row.version ?? '—' }}</code>
            </span>
            <span class="cc-ver-line">
              <span class="cc-ver-kind">chart</span>
              <el-tooltip v-if="!installedChartVersion(row)" placement="top"
                          content="非平台安装，或没有成功记录——已装的 chart 版本无从得知">
                <code class="mono cc-ver-unknown">—</code>
              </el-tooltip>
              <code v-else class="mono">{{ installedChartVersion(row) }}</code>
            </span>
            <!-- 多 chart 组件（Istio 六个、Prometheus 管理集群两个）逐个声明版本 -->
            <span v-if="(row.releases?.length ?? 0) > 1" class="cc-ver-line">
              <el-popover placement="bottom-start" trigger="click" :width="400">
                <template #reference>
                  <span class="cc-ver-more">{{ row.releases?.length }} 个 chart</span>
                </template>
                <div class="cc-rel-list">
                  <div v-for="r in row.releases ?? []" :key="r.releaseName" class="cc-rel-row">
                    <span class="cc-rel-name">
                      {{ r.releaseName }}
                      <span v-if="r.primary" class="cc-rel-primary">主</span>
                    </span>
                    <span class="cc-rel-ver">chart {{ r.chartVersion }}</span>
                    <span class="cc-rel-ver">应用 {{ r.appVersion ?? '—' }}</span>
                  </div>
                  <div class="cc-form-hint">
                    平台内置版本，安装/升级按这组 chart 执行；「主」是组件本体，
                    升级目标与版本号只认它
                  </div>
                </div>
              </el-popover>
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="命名空间" width="180">
        <template #default="{ row }">{{ row.namespace ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="健康" width="100">
        <template #default="{ row }">
          {{ row.ready == null ? '—' : `${row.ready}/${row.desired}` }}
        </template>
      </el-table-column>
      <el-table-column label="上次操作" width="100">
        <template #default="{ row }">
          <el-tooltip v-if="opTag(row.code).present" :content="opTag(row.code).tooltip" placement="top">
            <el-tag size="small" effect="plain" class="cc-op-tag"
                    :type="opTag(row.code).type"
                    @click="openOperation(row)">
              {{ opTag(row.code).label }}
            </el-tag>
          </el-tooltip>
          <span v-else class="cc-readonly">—</span>
        </template>
      </el-table-column>
      <el-table-column label="影响的功能" min-width="260">
        <template #default="{ row }">
          <span v-for="f in row.features" :key="f" class="cc-feature">{{ featureLabel(f) }}</span>
          <!-- 缺组件给指引；安装按钮在右侧「操作」列 -->
          <div v-if="row.state === 'NOT_INSTALLED' && row.features.length" class="cc-guide">
            缺它 → {{ row.features.map(featureLabel).join(' / ') }} 不可用（安装需 cluster-admin）
          </div>
          <!-- ⚠️ detail 必须显示：Review Focus #4（"在平台已知的位置没有找到"）与 #5（"命中 2 个，取 X"）
               都只活在这个字段里。藏掉它们 = UI 变回「断言没装」/「静默挑一个」。 -->
          <div v-if="showDetail(row.detail)" class="cc-guide cc-detail">{{ row.detail }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="row.state === 'NOT_INSTALLED' && installable(row)">
            <el-button link type="primary" :loading="props.applying === row.code"
                       @click="openInstall(row)">
              安装
            </el-button>
          </template>
          <template v-else-if="row.state === 'INSTALLED_HEALTHY' || row.state === 'INSTALLED_UNHEALTHY'">
            <el-tooltip v-if="!operations[row.code]" content="该组件由外部安装，平台不接管" placement="top">
              <span class="cc-readonly">只读</span>
            </el-tooltip>
            <template v-else>
              <!-- 平台装的都能编辑：告警回调地址 / Thanos Receive / 可编辑字段，按当前版本重放一次 -->
              <el-button link type="primary" :loading="props.applying === row.code"
                         @click="openApply(row, 'EDIT')">
                编辑
              </el-button>
              <!-- 有可升级版本才给按钮；只有一个版本时不是"能升级"，说清楚 -->
              <el-button v-if="upgradeTargets(row).length" link type="primary"
                         :loading="props.applying === row.code" @click="openUpgrade(row)">
                升级
              </el-button>
              <el-tooltip v-else content="平台内置的就是当前版本，暂无可升级版本" placement="top">
                <span class="cc-readonly">已是最新</span>
              </el-tooltip>
              <el-button v-if="row.code === 'harbor'" link type="primary"
                         @click="openHarborProjects()">
                镜像项目
              </el-button>
            </template>
          </template>
          <!-- UNKNOWN / 无 recipe：不给装 —— "不知道"不是"没有"；没有 recipe 就明确说"暂不支持" -->
          <span v-else-if="row.state === 'UNKNOWN'" class="cc-readonly">—</span>
          <span v-else class="cc-readonly">暂不支持</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="applyDialog.visible" width="1280px" append-to-body
               :title="`${ACTION_LABEL[applyDialog.action]} ${applyDialog.name}`">
      <div v-if="applyDialog.action === 'EDIT'" class="cc-upgrade-tip">
        保存后会按当前 chart 版本重放一次 helm（幂等），并重新下发告警联动；没改的项沿用上次成功操作的值。
      </div>
      <div v-if="applyDialog.action === 'UPGRADE'" class="cc-form-row">
        <span class="cc-form-label">目标 chart 版本</span>
        <div class="cc-form-control">
          <div class="cc-upgrade-tip">
            当前 chart 版本：<code>{{ applyDialog.chartVersion || '未知' }}</code>
            <span class="cc-upgrade-note">（应用版本由集群探测，与 chart 版本是两套编号）</span>
          </div>
          <el-radio-group v-model="applyDialog.version">
            <el-radio v-for="v in applyDialog.versions" :key="v" :value="v" class="cc-upgrade-option">
              {{ v }}
            </el-radio>
          </el-radio-group>
        </div>
      </div>
      <!-- 组件字段：按后端下发的 group 分节，避免所有输入框平铺在一起 -->
      <template v-for="g in genericGroups" :key="g">
        <div class="cc-section-title">{{ g }}</div>
        <div v-for="f in fieldsOf(g)" :key="f.key" class="cc-form-row">
          <span class="cc-form-label">{{ f.label }}</span>
          <div class="cc-form-control">
            <!-- 资源限制：requests/limits × cpu/memory 四格，不再用 JSON 文本域 -->
            <div v-if="f.type === 'resources'" class="cc-resources">
              <div class="cc-resource-line">
                <span class="cc-resource-label">requests</span>
                <el-input v-model="applyDialog.values[f.key + '.requestsCpu']"
                          placeholder="CPU，如 100m" spellcheck="false" />
                <el-input v-model="applyDialog.values[f.key + '.requestsMemory']"
                          placeholder="内存，如 256Mi" spellcheck="false" />
              </div>
              <div class="cc-resource-line">
                <span class="cc-resource-label">limits</span>
                <el-input v-model="applyDialog.values[f.key + '.limitsCpu']"
                          placeholder="CPU，如 1" spellcheck="false" />
                <el-input v-model="applyDialog.values[f.key + '.limitsMemory']"
                          placeholder="内存，如 1Gi" spellcheck="false" />
              </div>
              <div class="cc-form-hint">
                写法：CPU 100m / 0.5 / 1；内存 256Mi / 1Gi。留空的项不写入 values，回落到平台默认值
              </div>
            </div>
            <!-- Service 端口列表：一行一个端口（名称 / 端口 / targetPort / 协议 / nodePort） -->
            <div v-else-if="f.type === 'ports'" class="cc-ports">
              <div class="cc-port-row cc-port-head">
                <span>名称</span><span>端口</span><span>targetPort</span><span>服务协议</span>
                <span>nodePort</span><span>监听协议</span><span>TLS 模式</span><span>证书 Secret</span><span></span>
              </div>
              <div v-for="(p, i) in (applyDialog.portRows[f.key] ?? [])" :key="i" class="cc-port-row">
                <el-input v-model="p.name" placeholder="http2" spellcheck="false" />
                <el-input v-model="p.port" placeholder="80" spellcheck="false" />
                <el-input v-model="p.targetPort" placeholder="留空 = 同端口" spellcheck="false" />
                <el-select v-model="p.protocol">
                  <el-option v-for="proto in PORT_PROTOCOLS" :key="proto" :label="proto" :value="proto" />
                </el-select>
                <el-input v-model="p.nodePort" placeholder="可选" spellcheck="false" />
                <el-select v-model="p.listener" placeholder="仅 Service" @change="onListenerChange(p)">
                  <el-option label="仅 Service（不监听）" value="" />
                  <el-option v-for="l in LISTENER_PROTOCOLS" :key="l" :label="l" :value="l" />
                </el-select>
                <el-select v-model="p.tlsMode" placeholder="—"
                           :disabled="p.listener !== 'TLS'">
                  <el-option label="Terminate" value="Terminate" />
                  <el-option label="Passthrough" value="Passthrough" />
                </el-select>
                <el-select v-if="p.listener === 'HTTPS' || p.listener === 'TLS'"
                           v-model="p.tlsSecret" :disabled="p.tlsMode === 'Passthrough'"
                           filterable allow-create clearable placeholder="证书 Secret">
                  <el-option v-for="s in applyDialog.gatewaySecretOptions" :key="s.name"
                             :label="s.name" :value="s.name" />
                </el-select>
                <span v-else class="cc-port-na">—</span>
                <el-button link type="danger" @click="applyDialog.portRows[f.key]?.splice(i, 1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="addPortRow(f.key)">+ 添加端口</el-button>
              <div class="cc-form-hint">
                每个端口可选择是否生成 Gateway listener（空 = 仅 Service，如 15021）。
                HTTP/HTTPS 才能挂 HTTPRoute；HTTPS = 终止 TLS，必须填证书 Secret；TLS = Passthrough（不挂证书）；
                listener 端口取 targetPort。选 NodePort 时可在对应列固定端口，留空则随机分配
              </div>
            </div>
            <el-select v-else-if="f.type === 'select'" v-model="applyDialog.values[f.key]"
                       style="width: 260px">
              <el-option v-for="o in f.options ?? []" :key="o" :label="o" :value="o" />
            </el-select>
            <el-switch v-else-if="f.type === 'boolean'" v-model="applyDialog.values[f.key]" />
            <el-input-number v-else-if="f.type === 'number'" v-model="applyDialog.values[f.key]" :min="0" />
            <!-- 存储类：候选来自集群的 StorageClass；清空 = 跟随集群默认存储类（不提交该键） -->
            <el-select v-else-if="f.type === 'storageClass'" v-model="applyDialog.values[f.key]"
                       filterable allow-create clearable style="width: 100%"
                       :disabled="applyDialog.action === 'EDIT'"
                       placeholder="跟随集群默认存储类">
              <el-option v-for="sc in applyDialog.storageClasses" :key="sc" :label="sc" :value="sc" />
            </el-select>
            <el-input v-else-if="f.type === 'size'" v-model="applyDialog.values[f.key]"
                      placeholder="如 50Gi" spellcheck="false"
                      :disabled="applyDialog.action === 'EDIT'" />
            <el-input v-else-if="f.type === 'json'" v-model="applyDialog.jsonText[f.key]"
                      type="textarea" :rows="5" spellcheck="false" />
            <el-input v-else-if="f.type === 'duration'" v-model="applyDialog.values[f.key]"
                      placeholder="如 30d / 12h（留空 = 不改动）" spellcheck="false" />
            <el-input v-else v-model="applyDialog.values[f.key]" />
            <div v-if="(f.type === 'size' || f.type === 'storageClass') && applyDialog.action === 'EDIT'"
                 class="cc-form-hint">
              存储类与容量不能在这里改：StatefulSet 的 volumeClaimTemplates 不可变；扩容需 StorageClass 支持并在集群侧操作 PVC
            </div>
            <div v-else-if="f.type === 'size'" class="cc-form-hint">容量带单位：50Gi / 500Mi / 1Ti</div>
          </div>
        </div>
      </template>
      <div v-if="applyDialog.showCalico" class="cc-section-title">网络（Calico）</div>
      <div v-if="applyDialog.showCalico" class="cc-form-row">
        <span class="cc-form-label">Pod CIDR</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.calico.podCidr"
                    placeholder="192.168.0.0/16" spellcheck="false" />
          <div class="cc-form-hint">必须与集群现有 Pod CIDR 一致；写错会立即中断集群网络</div>
        </div>
      </div>
      <div v-if="applyDialog.showCalico" class="cc-form-row">
        <span class="cc-form-label">封装方式</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.calico.encapsulation" style="width: 100%">
            <el-option v-for="e in ENCAPSULATIONS" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
          <div v-if="encapsulationHint(applyDialog.calico.encapsulation)" class="cc-form-hint">
            {{ encapsulationHint(applyDialog.calico.encapsulation) }}
          </div>
          <div class="cc-form-hint">
            决定跨节点 Pod 流量怎么走：选错 = 跨节点 Pod 不通，甚至整集群断网；只能在安装前定
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showTektonProfile" class="cc-section-title">Tekton</div>
      <div v-if="applyDialog.showTektonProfile" class="cc-form-row">
        <span class="cc-form-label">Tekton profile</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.tektonProfile" style="width: 100%">
            <el-option label="all —— Pipeline + Trigger + Result + Chain + Dashboard" value="all" />
            <el-option label="basic —— Pipeline + Trigger + Result + Chain" value="basic" />
            <el-option label="lite —— 只有 Pipeline" value="lite" />
          </el-select>
          <div class="cc-form-hint">
            Tekton Operator 按 profile 安装对应组件；默认 all（完整 Tekton）
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showHarbor" class="cc-section-title">镜像仓库（Harbor）</div>
      <div v-if="applyDialog.showHarbor" class="cc-form-row">
        <span class="cc-form-label">暴露方式</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.harbor.exposeMode" style="width: 100%">
            <el-option label="NodePort（节点 IP + 端口，不需要 istio）" value="nodePort" />
            <el-option label="LoadBalancer（外部地址，不需要 istio）" value="loadBalancer" />
            <el-option label="HTTPRoute（域名，经 istio external-gateway）" value="route" />
          </el-select>
          <div class="cc-form-hint">
            三种都只走 HTTP（<code>tls.enabled=false</code>）：docker/podman 客户端要配
            <code>insecure-registries</code>。route 模式必须用域名，不能是 IP
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showHarbor" class="cc-form-row">
        <span class="cc-form-label">{{ applyDialog.harbor.exposeMode === 'route' ? '域名'
          : (applyDialog.harbor.exposeMode === 'nodePort' ? '节点地址' : '外部地址') }}</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.harbor.host"
                    :placeholder="applyDialog.harbor.exposeMode === 'route' ? 'harbor.example.com' : '10.0.0.5'"
                    spellcheck="false" />
          <div class="cc-form-hint">
            不带 <code>http://</code>、路径与端口（端口在下面单独填）。它决定 Harbor 的
            <code>externalURL</code>（token 服务返回给 docker 客户端的地址），必须能从集群内 Pod、
            平台、外部 docker 客户端三边访问
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showHarbor && applyDialog.harbor.exposeMode === 'nodePort'"
           class="cc-form-row">
        <span class="cc-form-label">NodePort</span>
        <div class="cc-form-control">
          <el-input-number v-model="applyDialog.harbor.nodePort" :min="1" :max="65535"
                           controls-position="right" />
          <div class="cc-form-hint">
            默认 30002（K8s 默认 NodePort 范围 30000-32767）。实际访问地址是
            <code>http://&lt;节点地址&gt;:&lt;NodePort&gt;</code>
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showHarbor && applyDialog.harbor.exposeMode === 'loadBalancer'"
           class="cc-form-row">
        <span class="cc-form-label">Service 端口</span>
        <div class="cc-form-control">
          <el-input-number v-model="applyDialog.harbor.port" :min="1" :max="65535"
                           controls-position="right" />
          <div class="cc-form-hint">
            默认 80。集群需有 LoadBalancer 实现，否则 EXTERNAL-IP 一直 pending
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showHarbor && applyDialog.harbor.exposeMode === 'route'"
           class="cc-form-row">
        <span class="cc-form-label">路由</span>
        <div class="cc-form-control">
          <div class="cc-form-hint">
            走 <code>istio-system/external-gateway</code> 的 HTTPRoute；集群必须先装 istio 且健康
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showHarbor" class="cc-form-row">
        <span class="cc-form-label">数据库</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.harbor.databaseMode" style="width: 100%"
                     :disabled="applyDialog.action !== 'INSTALL'">
            <el-option label="Chart 内置 PostgreSQL（默认）" value="internal" />
            <el-option label="外部 PostgreSQL" value="external" />
          </el-select>
          <div v-if="applyDialog.harbor.databaseMode === 'external'" class="cc-harbor-ext">
            <el-input v-model="applyDialog.harbor.dbHost" placeholder="pg.internal（IP 或域名）"
                      spellcheck="false" />
            <el-input-number v-model="applyDialog.harbor.dbPort" :min="1" :max="65535"
                             controls-position="right" />
            <el-input v-model="applyDialog.harbor.dbUsername" placeholder="用户名" spellcheck="false" />
            <el-input v-model="applyDialog.harbor.dbName" placeholder="库名（默认 registry）"
                      spellcheck="false" />
            <el-select v-model="applyDialog.harbor.dbSslMode" style="width: 100%">
              <el-option label="disable（不加密）" value="disable" />
              <el-option label="require（要 SSL，不校验证书）" value="require" />
              <el-option label="verify-ca（校验证书 CA）" value="verify-ca" />
              <el-option label="verify-full（校验证书与主机名）" value="verify-full" />
            </el-select>
            <el-input v-model="applyDialog.harbor.dbPassword" type="password" show-password
                      spellcheck="false"
                      :placeholder="applyDialog.action === 'INSTALL' ? '数据库口令（必填）' : '留空 = 不改'" />
          </div>
          <div class="cc-form-hint">
            外部库要先建好库（默认 registry）与账号并授权。来源只能在首次安装时选，编辑时锁定
            （内置 ↔ 外部需要迁移数据）。口令由平台写进 Secret，不进 values 快照
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showHarbor" class="cc-form-row">
        <span class="cc-form-label">Redis</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.harbor.redisMode" style="width: 100%"
                     :disabled="applyDialog.action !== 'INSTALL'">
            <el-option label="Chart 内置 Redis（默认）" value="internal" />
            <el-option label="外部 Redis" value="external" />
          </el-select>
          <div v-if="applyDialog.harbor.redisMode === 'external'" class="cc-harbor-ext">
            <el-input v-model="applyDialog.harbor.redisAddr" placeholder="redis.internal:6379"
                      spellcheck="false" />
            <el-input v-model="applyDialog.harbor.redisUsername" placeholder="ACL 用户名（可空）"
                      spellcheck="false" />
            <el-input v-model="applyDialog.harbor.redisPassword" type="password" show-password
                      spellcheck="false"
                      :placeholder="applyDialog.action === 'INSTALL' ? 'Redis 口令（必填）' : '留空 = 不改'" />
          </div>
          <div class="cc-form-hint">
            外部 Redis 用独立 DB index（0/1/2/5），不要和别的应用共用；sentinel 暂不支持。
            口令同样由平台写进 Secret
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showSeaweed" class="cc-section-title">对象存储（SeaweedFS）</div>
      <div v-if="applyDialog.showSeaweed" class="cc-form-row">
        <span class="cc-form-label">StorageClass</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.seaweed.storageClass" filterable allow-create
                     placeholder="选择集群的 StorageClass" class="cc-sf-select">
            <el-option v-for="sc in applyDialog.storageClasses" :key="sc" :label="sc" :value="sc" />
          </el-select>
          <div class="cc-form-hint">
            三块 PVC（master 的 Raft 数据 / volume 数据盘 / filer 元数据）共用；
            必须选，留空会装出一组永远 Pending 的卷
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showSeaweed" class="cc-form-row">
        <span class="cc-form-label">卷容量</span>
        <div class="cc-form-control">
          <div class="cc-sf-grid">
            <div class="cc-sf-item">
              <span class="cc-sf-label">volume 数据盘</span>
              <el-input v-model="applyDialog.seaweed.volumeSize" class="cc-sf-input" placeholder="30Gi" />
            </div>
            <div class="cc-sf-item">
              <span class="cc-sf-label">filer 元数据</span>
              <el-input v-model="applyDialog.seaweed.filerSize" class="cc-sf-input" placeholder="10Gi" />
            </div>
            <div class="cc-sf-item">
              <span class="cc-sf-label">master（Raft）</span>
              <el-input v-model="applyDialog.seaweed.masterSize" class="cc-sf-input" placeholder="1Gi" />
            </div>
          </div>
          <div class="cc-form-hint">
            格式 30Gi / 200Mi。数据盘是每个 volume server 一块；master 存 Raft 日志与集群身份，必须持久化
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showSeaweed" class="cc-form-row">
        <span class="cc-form-label">副本数</span>
        <div class="cc-form-control">
          <div class="cc-sf-grid">
            <div class="cc-sf-item">
              <span class="cc-sf-label">master（1 或 3）</span>
              <el-input-number v-model="applyDialog.seaweed.masterReplicas" :min="1" :max="3" :step="2"
                               controls-position="right" class="cc-sf-input" />
            </div>
            <div class="cc-sf-item">
              <span class="cc-sf-label">volume</span>
              <el-input-number v-model="applyDialog.seaweed.volumeReplicas" :min="1" :max="20"
                               controls-position="right" class="cc-sf-input" />
            </div>
            <div class="cc-sf-item">
              <span class="cc-sf-label">S3 网关</span>
              <el-input-number v-model="applyDialog.seaweed.s3Replicas" :min="1" :max="10"
                               controls-position="right" class="cc-sf-input" />
            </div>
          </div>
          <div class="cc-form-hint">
            Raft 要奇数成员（默认 1）；集群内 S3 入口
            <code>seaweedfs-s3.platform-storage:8333</code>，凭据在 Secret <code>seaweedfs-s3-secret</code>
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showSeaweed" class="cc-form-row">
        <span class="cc-form-label">S3 对外地址</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.seaweed.s3Endpoint" class="cc-sf-endpoint" spellcheck="false" />
          <div class="cc-form-hint">
            这个 S3 对外提供服务的地址（给应用、给其它集群的组件引用）。集群内用默认值；要让集群外访问，
            先把 <code>seaweedfs-s3</code> 暴露成 NodePort/LB 再填这里。
            <strong>平台自己连 S3 不走这个值</strong>，走部署配置
            <code>platform.object-storage.default-endpoint</code>（环境变量 <code>OBJECT_STORAGE_S3_ENDPOINT</code>）
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showSeaweed" class="cc-form-row">
        <span class="cc-form-label">管理台</span>
        <div class="cc-form-control">
          <el-switch v-model="applyDialog.seaweed.adminEnabled" active-text="安装 admin UI（端口 23646）" />
          <div class="cc-form-hint">
            登录口令由平台生成并写入 Secret <code>platform-seaweedfs-admin</code>（键 username / password），
            升级不轮换；只看集群/卷状态用 master UI（9333）也可以
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showOtel" class="cc-section-title">OTel 采集栈</div>
      <!-- OpenTelemetry 采集栈：Tempo 地址 + 采样/副本/日志 + MinIO/ES -->
      <div v-if="applyDialog.showOtel" class="cc-form-row">
        <span class="cc-form-label">Tempo 地址</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.otel.tempoEndpoint"
                    placeholder="platform-tempo.monitor.svc:4317" spellcheck="false" />
          <div class="cc-form-hint">
            collector 的 traces 导出目标（gRPC host:port）。管理集群默认指向集群内 Tempo；
            业务集群要填平台侧可达的地址
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showOtel" class="cc-form-row">
        <span class="cc-form-label">Tempo TLS</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.otel.tempoTlsMode" style="width: 100%">
            <el-option label="明文 gRPC（集群内 / 内网）" value="plaintext" />
            <el-option label="TLS（校验证书）" value="tls" />
            <el-option label="TLS（跳过证书校验，自签）" value="tls-skip-verify" />
          </el-select>
          <div class="cc-form-hint">
            collector 连 Tempo 的传输方式。管理员用明文 4317 入口时选第一个；用 TLS 终止时按证书情况选后两个
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showOtel" class="cc-form-row">
        <span class="cc-form-label">采样率 / 副本数</span>
        <div class="cc-form-control" style="display: flex; align-items: center; gap: 10px; padding-top: 2px">
          <el-input-number v-model="applyDialog.otel.samplingPercentage" :min="1" :max="100" />
          <span class="cc-form-hint">% 采样</span>
          <el-input-number v-model="applyDialog.otel.gatewayReplicas" :min="1" :max="10" />
          <span class="cc-form-hint">collector 副本</span>
        </div>
      </div>
      <div v-if="applyDialog.showOtel" class="cc-form-row">
        <span class="cc-form-label">应用日志</span>
        <div class="cc-form-control">
          <el-switch v-model="applyDialog.otel.logsEnabled" @change="loadEsSecrets" />
          <div class="cc-form-hint">
            打开后 collector 增加 logs 管道，Java（Logback/Log4j2）与 Python（logging）的日志写入 ES。
            具体工作负载还要在「可观测」里单独打开日志开关
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showOtel && applyDialog.showTempoInstall" class="cc-section-title">Tempo 存储</div>
      <template v-if="applyDialog.showOtel && applyDialog.showTempoInstall">
        <div class="cc-form-row">
          <span class="cc-form-label">Tempo 对象存储</span>
          <div class="cc-form-control">
            <div class="cc-form-hint">
              Tempo 把 traces 写进 <b>MinIO</b>（S3 兼容）；凭证只在集群内转换成
              <code>platform-tempo-objstore</code>，密钥不经过 platform-api、不进操作记录。
            </div>
          </div>
        </div>
        <div class="cc-form-row">
          <span class="cc-form-label">MinIO 地址</span>
          <div class="cc-form-control" style="display: flex; gap: 8px">
            <el-input v-model="applyDialog.otel.tempoStorage.endpoint"
                      placeholder="192.168.31.80:9002" spellcheck="false" />
            <el-select v-model="applyDialog.otel.tempoStorage.plaintext" style="width: 150px">
              <el-option label="HTTPS" :value="false" />
              <el-option label="HTTP（明文）" :value="true" />
            </el-select>
          </div>
          <div class="cc-form-hint" style="flex-basis: 100%">
            地址只填 <b>host:port</b>；传输方式看右侧选择（地址里误写的 http:// / https:// 会被剥掉，不参与判断）
          </div>
        </div>
        <div class="cc-form-row">
          <span class="cc-form-label">Bucket</span>
          <div class="cc-form-control">
            <el-input v-model="applyDialog.otel.tempoStorage.bucket" placeholder="tempo" spellcheck="false" />
          </div>
          <div class="cc-form-hint" style="flex-basis: 100%">
            Tempo 用<b>独立桶</b>（默认 <code>tempo</code>，需先在 MinIO 建好），不要与 Thanos 的
            <code>thanos</code> 桶共用；不设前缀，block 直接写在桶根
          </div>
        </div>
        <div class="cc-form-row">
          <span class="cc-form-label">凭证 Secret</span>
          <div class="cc-form-control">
            <el-select v-model="applyDialog.otel.tempoStorage.credentialSecret.name"
                       filterable allow-create style="width: 100%"
                       placeholder="选择 monitor 命名空间下的 Secret">
              <el-option v-for="s in applyDialog.otelMinioSecretOptions" :key="s.name" :value="s.name"
                         :label="s.keys?.length ? `${s.name}（${s.keys.join(', ')}）` : s.name" />
            </el-select>
          </div>
          <div class="cc-form-hint" style="flex-basis: 100%">
            Secret 固定从 <b>monitor</b> 命名空间选（不可切换命名空间），内容为 MinIO access key / secret key
          </div>
        </div>
        <div class="cc-form-row">
          <span class="cc-form-label">跳过 TLS 校验</span>
          <div class="cc-form-control">
            <el-switch v-model="applyDialog.otel.tempoStorage.insecureSkipVerify"
                       :disabled="applyDialog.otel.tempoStorage.plaintext === true" />
            <div class="cc-form-hint">
              只在 <b>HTTPS + 自签证书</b>时打开；选「HTTP（明文）」时该开关无意义
            </div>
          </div>
        </div>
        <div class="cc-form-row">
          <span class="cc-form-label">Trace 保留期</span>
          <div class="cc-form-control">
            <el-select v-model="applyDialog.otel.tempoRetention" style="width: 220px">
              <el-option label="24 小时" value="24h" />
              <el-option label="7 天" value="168h" />
              <el-option label="14 天" value="336h" />
            </el-select>
            <div class="cc-form-hint">Tempo 的 block retention；多集群汇总后别用 chart 默认的 24h</div>
          </div>
        </div>
      </template>
      <div v-if="applyDialog.showOtel && applyDialog.otel.logsEnabled" class="cc-section-title">应用日志（Elasticsearch）</div>
      <template v-if="applyDialog.showOtel && applyDialog.otel.logsEnabled">
        <div class="cc-form-row">
          <span class="cc-form-label">ES 地址</span>
          <div class="cc-form-control">
            <el-input v-model="applyDialog.otel.es.endpoint"
                      placeholder="https://es-es-http.default.svc:9200" spellcheck="false" />
            <div class="cc-form-hint">完整 URL，含 http:// 或 https://</div>
          </div>
        </div>
        <div class="cc-form-row">
          <span class="cc-form-label">ES 凭证 Secret</span>
          <div class="cc-form-control">
            <el-select v-model="applyDialog.otel.es.credentialSecret.name"
                       filterable allow-create style="width: 100%"
                       placeholder="选择 monitor 命名空间下的 Secret">
              <el-option v-for="s in applyDialog.esSecretOptions" :key="s.name" :value="s.name"
                         :label="s.keys?.length ? `${s.name}（${s.keys.join(', ')}）` : s.name" />
            </el-select>
          </div>
          <div class="cc-form-hint" style="flex-basis: 100%">
            Secret 必须放在 <b>monitor</b>，且包含 <code>username</code> / <code>password</code> 两个 key；
            平台在集群内复制成 <code>platform-otel-es</code>，密码不经过 platform-api
          </div>
        </div>
        <div class="cc-form-row">
          <span class="cc-form-label">索引 / TLS</span>
          <div class="cc-form-control" style="display: flex; align-items: center; gap: 10px">
            <code>otel-logs</code>
            <span class="cc-form-hint">固定的索引前缀（按天自动加日期后缀：otel-logs-YYYY.MM.DD）</span>
            <el-switch v-model="applyDialog.otel.es.insecureSkipVerify" />
            <span class="cc-form-hint">跳过 HTTPS 校验</span>
          </div>
        </div>
      </template>
      <div v-if="applyDialog.showThanosInstall" class="cc-section-title">Thanos 对象存储（管理集群）</div>
      <!-- Thanos（管理集群）：Prometheus 的长期指标写进 MinIO，由 Thanos 查询 -->
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">Thanos 对象存储</span>
        <div class="cc-form-control">
          <div class="cc-form-hint">
            Thanos 装在 <code>monitor</code> 命名空间，用 <b>MinIO</b>（S3 兼容对象存储）保存 Prometheus
            的长期指标；平台只读取下面的凭证用于生成存储配置，密钥不经过 platform-api、不进操作记录。
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">对象存储凭证 Secret</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.thanos.secretName" filterable allow-create style="width: 100%"
                     placeholder="monitor 命名空间下的 Secret">
            <el-option v-for="s in applyDialog.secretOptions" :key="s.name" :value="s.name"
                       :label="s.keys?.length ? `${s.name}（${s.keys.join(', ')}）` : s.name" />
          </el-select>
          <div class="cc-form-hint">
            必须是 <b>monitor</b> 命名空间的 Secret，内容是 <b>MinIO 的 access key / secret key</b>。
            支持的 key 组合：access-key+secret-key、accessKey+secretKey、MINIO_ACCESS_KEY+MINIO_SECRET_KEY 等。
            平台在集群内拼成 objstore.yml，写成 Secret <code>platform-thanos-objstore</code>
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">MinIO 地址</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.thanos.endpoint"
                    placeholder="minio.default.svc:9000" spellcheck="false" />
          <div class="cc-form-hint">
            Thanos 把指标写进这个 S3 端点（可带 http:// / https://；不带协议默认 https）
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">MinIO Bucket</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.thanos.bucket" placeholder="thanos" spellcheck="false" />
          <div class="cc-form-hint">存储桶必须已存在，平台不会创建</div>
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">跳过 HTTPS 校验</span>
        <div class="cc-form-control">
          <el-switch v-model="applyDialog.thanos.insecureSkipVerify" />
          <div class="cc-form-hint">MinIO 用自签证书时打开</div>
        </div>
      </div>
      <!-- Thanos 保留期（compactor.retention.*）：与凭据/桶同一节 -->
      <template v-if="applyDialog.showThanosInstall">
        <div v-for="f in thanosFields" :key="f.key" class="cc-form-row">
          <span class="cc-form-label">{{ f.label }}</span>
          <div class="cc-form-control">
            <el-input v-model="applyDialog.values[f.key]" placeholder="如 30d / 12h（留空 = 不改动）"
                      spellcheck="false" />
            <div class="cc-form-hint">
              <template v-if="RETENTION_MINIMUMS[f.key]">
                Thanos 下限 {{ RETENTION_MINIMUMS[f.key]?.label }}：{{ RETENTION_MINIMUMS[f.key]?.why }}；
              </template>
              MinIO 里按分辨率的保留上限：raw 默认 30d、5m 降采样 90d、1h 降采样 365d。
              <b>改小会立即删除更早的数据且不可逆</b>；改大只是不再删
            </div>
          </div>
        </div>
      </template>
      <div v-if="applyDialog.showThanos || applyDialog.showWebhook" class="cc-section-title">平台联动</div>
      <div v-if="applyDialog.showThanos" class="cc-form-row">
        <span class="cc-form-label">Thanos Receive 地址</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.thanosUrl"
                    placeholder="http://platform-thanos-receive.monitor.svc:10908/api/v1/receive"
                    spellcheck="false" />
          <div class="cc-form-hint">
            Prometheus 的 remoteWrite 目标。同集群用 Service 名即可；跨集群要填集群网络可达的外部地址
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showWebhook" class="cc-form-row">
        <span class="cc-form-label">平台告警回调地址</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.webhookUrl"
                    placeholder="http://<平台地址>:8081/alert/webhook" spellcheck="false" />
          <div class="cc-form-hint">
            集群里的 Alertmanager 需要能访问到；后端会自动追加 ?clusterId=…
          </div>
        </div>
      </div>
      <div v-if="!applyDialog.fields.length && !applyDialog.showWebhook && !applyDialog.showThanos
                && !applyDialog.showThanosInstall && !applyDialog.showCalico
                && !applyDialog.showTektonProfile && !applyDialog.showOtel && !applyDialog.showSeaweed
                && applyDialog.action === 'INSTALL'"
           class="cc-upgrade-tip">
        该组件没有可编辑配置，将使用平台默认值安装。
      </div>
      <template #footer>
        <el-button @click="applyDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmApply">确定</el-button>
      </template>
    </el-dialog>

    <!-- 上次操作详情：失败原因 + helm 输出（排查日志的唯一入口） -->
    <el-dialog v-model="operationDialog.visible" :title="`操作详情 · ${operationDialog.name}`"
               width="760px" append-to-body>
      <template v-if="operationDialog.op">
        <div class="cc-op-meta">
          <span>动作：{{ operationDialog.op.action === 'INSTALL' ? '安装' : '升级' }}</span>
          <span>chart 版本：{{ operationDialog.op.fromVersion || '—' }} → {{ operationDialog.op.toVersion || '—' }}</span>
          <span>结果：
            <el-tag size="small" :type="OP_STATUS[operationDialog.op.status].type">
              {{ OP_STATUS[operationDialog.op.status].label }}
            </el-tag>
          </span>
          <span>操作人：{{ operationDialog.op.operator || '—' }}</span>
          <span>时间：{{ operationDialog.op.startedAt || '—' }} ~ {{ operationDialog.op.finishedAt || '未结束' }}</span>
        </div>
        <div v-if="operationDialog.op.message" class="cc-op-message">{{ operationDialog.op.message }}</div>
        <div class="cc-op-log-head">
          <span>helm 输出</span>
          <el-button size="small" link type="primary" :disabled="!operationDialog.op.logTail"
                     @click="copyLog">复制</el-button>
        </div>
        <pre class="cc-op-log">{{ operationDialog.op.logTail || '（无输出）' }}</pre>
      </template>
      <template #footer>
        <el-button v-if="operationDialog.op && ['PENDING', 'RUNNING'].includes(operationDialog.op.status)"
                   type="danger" plain @click="abandonOperation">中止操作</el-button>
        <el-button @click="operationDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
    <HarborProjectsDialog v-model:visible="harborProjects" :cluster-id="props.clusterId" />
</template>

<style scoped>
.cc-harbor-ext { display: grid; gap: 8px; margin-top: 8px; }
.cc-panel { display: flex; flex-direction: column; gap: 10px; }
.cc-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.cc-summary { font-size: 13px; color: var(--text-2); }
.cc-warn {
  font-size: 12px;
  color: var(--warning);
  padding: 6px 10px;
  border: 1px dashed var(--warning);
  border-radius: 6px;
}
.cc-name { margin-right: 8px; }
.cc-code { font-size: 12px; color: var(--text-3); }
.cc-feature { margin-right: 8px; font-size: 12px; color: var(--text-2); }
.cc-guide { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.cc-detail { font-style: italic; }
.cc-readonly { font-size: 12px; color: var(--text-3); }
/* 版本列：应用版本（探测）与 chart 版本（平台安装）分两行各自标注 */
.cc-ver-head { display: inline-flex; align-items: center; gap: 4px; }
.cc-ver-help { color: var(--text-3); font-size: 13px; }
.cc-ver { display: flex; flex-direction: column; line-height: 1.5; }
.cc-ver-line { display: flex; align-items: center; gap: 6px; }
.cc-ver-kind { font-size: 11px; color: var(--text-3); min-width: 30px; }
.cc-ver-unknown { color: var(--text-3); cursor: help; }
.cc-ver-more { font-size: 11px; color: var(--el-color-primary); cursor: pointer; }
.cc-ver-more:hover { text-decoration: underline; }
.cc-rel-list { display: flex; flex-direction: column; gap: 6px; }
.cc-rel-row {
  display: grid; grid-template-columns: minmax(150px, 1fr) auto auto;
  gap: 12px; align-items: center; font-size: 12px;
}
.cc-rel-name { color: var(--text-2); word-break: break-all; }
.cc-rel-primary {
  margin-left: 4px; padding: 0 4px; border-radius: 3px; font-size: 10px;
  color: var(--el-color-primary); border: 1px solid var(--el-color-primary);
}
.cc-rel-ver { color: var(--text-3); white-space: nowrap; }
.cc-upgrade-note { color: var(--text-3); font-size: 12px; }
</style>

<style>
/* ⚠️ el-dialog 内部节点没有 data-v-*，scoped 匹配不到 —— 非 scoped + 独有前缀（本仓既有约定） */
.cc-upgrade-tip { font-size: 13px; color: var(--text-2); margin-bottom: 10px; }
.cc-upgrade-option { display: block; margin: 6px 0; }
.cc-form-row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 14px; }
.cc-section-title {
  margin: 18px 0 10px; padding-bottom: 6px;
  border-bottom: 1px solid var(--border);
  font-size: 13px; font-weight: 600; color: var(--text-2);
}
.cc-section-title:first-of-type { margin-top: 4px; }
.cc-form-label { flex: 0 0 150px; font-size: 13px; color: var(--text-2); padding-top: 6px; }
.cc-form-control { flex: 1 1 auto; min-width: 300px; }
.cc-sf-select { max-width: 360px; width: 100%; }
/* 对象存储表单：三列字段，label 在上、控件在下，宽度统一 */
.cc-sf-grid { display: flex; flex-wrap: wrap; gap: 16px 24px; }
.cc-sf-item { display: flex; flex-direction: column; gap: 4px; }
.cc-sf-label { font-size: 12px; color: var(--text-3); }
.cc-sf-input { width: 140px; }
.cc-sf-endpoint { max-width: 420px; }
.cc-form-hint { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.cc-resources { display: flex; flex-direction: column; gap: 8px; }
.cc-resource-line { display: flex; align-items: center; gap: 8px; }
.cc-resource-label { flex: 0 0 64px; font-size: 12px; color: var(--text-3); }
.cc-ports { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; overflow-x: auto; }
.cc-port-row {
  display: grid;
  grid-template-columns: minmax(110px, 1fr) 70px 90px 90px 90px 110px 110px 140px auto;
  gap: 6px; align-items: center; width: 100%; min-width: 820px;
}
.cc-port-head span { font-size: 11px; color: var(--text-3); }
.cc-port-na { font-size: 12px; color: var(--text-3); text-align: center; }
.cc-op-tag { cursor: pointer; }
.cc-op-meta { display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 13px; color: var(--text-2); margin-bottom: 10px; }
.cc-op-message { font-size: 13px; color: var(--danger); margin-bottom: 10px; white-space: pre-wrap; }
.cc-op-log-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 13px; color: var(--text-2); margin-bottom: 6px;
}
.cc-op-log {
  max-height: 380px; overflow: auto; margin: 0; padding: 10px; border-radius: 6px;
  background: var(--bg); font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
</style>
