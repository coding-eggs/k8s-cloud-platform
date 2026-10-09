<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { workloadApi, calicoApi, namespaceApi, clusterApi, gatewayApi } from '@/api'
import type {
  Affinity,
  ContainerDef,
  ContainerPort,
  NodeSelectorTerm,
  PodSpec,
  PvcTemplate,
  Strategy,
  Toleration,
  VolumeDef,
  WorkloadDetail,
  WorkloadKind,
} from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import type { K8sIpReservation } from '@/types'
import { useNodeCatalog } from '@/stores/nodeCatalog'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import NodeLabelEditor from '@/components/workload/NodeLabelEditor.vue'
import StrategyEditor from '@/components/workload/StrategyEditor.vue'
import ContainerListEditor from '@/components/workload/ContainerListEditor.vue'
import PortEditor from '@/components/workload/PortEditor.vue'
import AffinityEditor from '@/components/workload/AffinityEditor.vue'
import TolerationEditor from '@/components/workload/TolerationEditor.vue'
import VolumeEditor from '@/components/workload/VolumeEditor.vue'
import PvcTemplateEditor from '@/components/workload/PvcTemplateEditor.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { useClusterCapability } from '@/composables/useClusterCapability'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { containsInCidr, expandCidrToIps, isIp } from '@/utils/ipUtil'
import { CALICO_POOL_LABEL, DEFAULT_IPV4_POOL, DEFAULT_IPV6_POOL } from '@/utils/calico'
import {
  DATAPLANE_MODES,
  isWaypointGateway,
  LABEL_DATAPLANE_MODE,
  LABEL_USE_WAYPOINT,
  RESERVED_MESH_LABELS,
  USE_WAYPOINT_NONE,
} from '@/utils/waypoint'
import type { K8sGateway } from '@/types'

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

/** 节点目录：调度策略里 nodeName / nodeSelector / 亲和的下拉数据源（按当前集群拉取，切换集群自动刷新） */
const nodeCatalog = useNodeCatalog()
function refreshNodes(): void { void nodeCatalog.load(state.clusterId) }
watch(() => state.clusterId, refreshNodes)

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>(route.query.name as string | null)

// ---------- form state（骨架保证 podTemplate.spec + 非空 containers） ----------
function defaultForm(): WorkloadDetail {
  return {
    kind: 'deployment',
    name: '',
    namespace: '',
    labels: null,
    description: null,
    replicas: 1,
    serviceName: null,
    minReadySeconds: null,
    paused: null,
    podManagementPolicy: null,
    persistentVolumeClaimRetentionPolicy: null,
    ordinals: null,
    strategy: { type: 'RollingUpdate' },
    volumeClaimTemplates: null,
    podTemplate: { spec: { containers: [{ name: '', image: '', imagePullPolicy: 'IfNotPresent' }], restartPolicy: 'Always' } },
  }
}

const form = reactive<WorkloadDetail>(defaultForm())

/** 保证 podTemplate 存在后取 spec（类型上 spec/containers 必在，编辑回填同样保证） */
function ensureSpec(): PodSpec {
  if (!form.podTemplate) form.podTemplate = { spec: { containers: [] } }
  return form.podTemplate.spec
}

/** 两个容器 tab 列表的 ref（v-show 常驻 → B4 校验覆盖全部容器，与当前激活模块无关） */
const mainTabsRef = ref<InstanceType<typeof ContainerListEditor> | null>(null)
const initTabsRef = ref<InstanceType<typeof ContainerListEditor> | null>(null)

/** 右侧导航：当前显示的模块。用 v-show 切换（全部常驻 DOM）→ 子编辑器 ref / B4 校验不受切模块影响 */
const activeModule = ref<'basic' | 'containers' | 'init' | 'strategy' | 'scheduling' | 'storage'>('basic')

/** 模块导航项：icon + 文字；收起态只显示 icon，展开态显示 icon + 文字 */
const modules = [
  { key: 'basic', label: '基础信息', icon: 'Document' },
  { key: 'containers', label: 'Pod 容器', icon: 'Box' },
  { key: 'init', label: '初始化容器', icon: 'SetUp' },
  { key: 'strategy', label: '更新策略', icon: 'Refresh' },
  { key: 'scheduling', label: '调度策略', icon: 'Compass' },
  { key: 'storage', label: '存储', icon: 'Files' },
] as const

/**
 * 侧栏收敛态：默认展开。窄屏（≤1024px）自动收起成图标窄栏；跨阈值时重置为自动状态；
 * 手动点边界按钮可随时翻转（覆盖自动状态，直到下次跨阈值）。
 */
const collapsed = ref(false)
const mql = window.matchMedia('(max-width: 1024px)')
collapsed.value = mql.matches
function onBreakpointChange(e: MediaQueryListEvent): void {
  collapsed.value = e.matches
}

/** el-menu 选中 → 切模块（index 即模块 key） */
function onModuleSelect(index: string): void {
  activeModule.value = index as typeof activeModule.value
}

// ---------- 主容器（containers[0]）常用字段：与「Pod 容器」模块的 ContainerEditor 绑同一对象，自动同步 ----------
function ensurePrimary(): ContainerDef {
  if (!form.podTemplate) form.podTemplate = { spec: { containers: [] } }
  const spec = form.podTemplate.spec
  if (!spec.containers || spec.containers.length === 0) spec.containers = [{ name: '', image: '' }]
  return spec.containers[0]! // guard 已保证非空
}
const pName = computed<string>({ get: () => ensurePrimary().name, set: (v) => { ensurePrimary().name = v } })
const pImage = computed<string>({ get: () => ensurePrimary().image ?? '', set: (v) => { ensurePrimary().image = v } })
const pWorkingDir = computed<string>({ get: () => ensurePrimary().workingDir ?? '', set: (v) => { ensurePrimary().workingDir = v || null } })
const pPullPolicy = computed<string | undefined>({ get: () => ensurePrimary().imagePullPolicy ?? undefined, set: (v) => { ensurePrimary().imagePullPolicy = v } })
const pPorts = computed<ContainerPort[] | undefined>({ get: () => ensurePrimary().ports ?? undefined, set: (v) => { ensurePrimary().ports = v } })
/** command/args 多值：textarea 按行/逗号切分（与 ContainerEditor 同一做法） */
const pCommandText = computed<string>({ get: () => ensurePrimary().command?.join('\n') ?? '', set: (t) => { ensurePrimary().command = t.split(/[\n,]/).map((s) => s.trim()).filter(Boolean) } })
const pArgsText = computed<string>({ get: () => ensurePrimary().args?.join('\n') ?? '', set: (t) => { ensurePrimary().args = t.split(/[\n,]/).map((s) => s.trim()).filter(Boolean) } })

/** C2 跨列表重名检测：把另一侧容器名传入各自 tab 组件 */
const mainNames = computed<string[]>(() => (form.podTemplate?.spec.containers ?? []).map((c) => c.name))
const initNames = computed<string[]>(() => (form.podTemplate?.spec.initContainers ?? []).map((c) => c.name))

// ---------- null↔undefined 桥接：WorkloadDetail 字段为 X|null，子编辑器 defineModel 为 X|undefined ----------
const kind = computed<WorkloadKind>({
  get: () => form.kind,
  set: (v) => { form.kind = v as WorkloadKind },
})

const labels = computed<Record<string, string> | undefined>({
  get: () => form.labels ?? undefined,
  set: (v) => { form.labels = v },
})

const strategy = computed<Strategy | undefined>({
  get: () => form.strategy ?? undefined,
  set: (v) => { form.strategy = v },
})

const description = computed<string>({
  get: () => form.description ?? '',
  set: (v) => { form.description = v || null },
})

/** replicas 可清空（undefined 回显为空）；daemonset 不展示，提交时置 null */
const replicas = computed<number | undefined>({
  get: () => form.replicas ?? undefined,
  set: (v) => { form.replicas = v ?? null },
})

// ---------- 固定 IP（Calico ipAddrs 注解；双栈 v4+v6） ----------
/** capability 门禁：集群无 projectcalico.org → 整字段隐藏 */
const { hasCalico } = useClusterCapability(computed(() => state.clusterId ?? null))
/** 启用门禁：kind ∈ {deploy, sts} && replicas==1（未填按 K8s 默认 1）；不满足则禁用 + 说明 */
const staticIpAllowed = computed(() => form.kind !== 'daemonset' && (form.replicas ?? 1) === 1)

function ensurePt(): NonNullable<WorkloadDetail['podTemplate']> {
  if (!form.podTemplate) form.podTemplate = { spec: { containers: [] } }
  return form.podTemplate
}
const staticIps = computed<string[]>({
  get: () => form.podTemplate?.staticIps ?? [],
  set: (v) => { ensurePt().staticIps = v.length > 0 ? v : null },
})

/** 候选 = 本命名空间可用池（ns 绑定池，未绑定→默认池）内的保留 IP 展开；超大段截断防下拉爆炸。同集群+命名空间只查一次 */
const RESERVED_IP_CAP_PER_CIDR = 1024
interface ReservedIpOption { ip: string; pool: string }
const reservedIpOptions = ref<ReservedIpOption[]>([])
/** 当前生效的可用池名（v4+v6；展示用） */
const allowedPoolNames = ref<string[]>([])
const reservedIpsTruncated = ref(false)
/** 被「不在本 ns 可用池」过滤掉的保留 IP 所属池（去重；空候选提示用） */
const filteredOutPoolNames = ref<string[]>([])
let reservedIpsLoadedFor: string | null = null
const perm = usePermission()

async function loadReservedIpOptions(): Promise<void> {
  const clusterId = state.clusterId
  const nsName = state.namespace
  if (!clusterId || !perm.has(apiCodes.clusterManage)) return
  const cacheKey = `${clusterId}/${nsName ?? ''}`
  if (reservedIpsLoadedFor === cacheKey) return
  try {
    // 本命名空间绑定池（B3 §11：ns annotation cni.projectcalico.org/ipv{4,6}pools）；查询失败 → null → 按未绑定处理
    const ns = nsName
      ? await namespaceApi.get(clusterId, nsName).catch(() => null)
      : null
    const [reservations, pools, cluster] = await Promise.all([
      calicoApi.ipreservation.list({ clusterId }),
      calicoApi.ippool.list({ clusterId }),
      clusterApi.get(clusterId).catch(() => null), // IP 栈门禁用；失败 → null → 不限制（降级）
    ])
    if (state.clusterId !== clusterId) return // 加载期间已切换集群 → 丢弃过期结果
    // 可用池：绑定 → 精确集合；未绑定 → Calico 默认池（default-ipv4-ippool / default-ipv6-ippool）
    const allowedV4 = ns?.ipv4Pools?.length ? new Set(ns.ipv4Pools) : new Set<string>([DEFAULT_IPV4_POOL])
    const allowedV6 = ns?.ipv6Pools?.length ? new Set(ns.ipv6Pools) : new Set<string>([DEFAULT_IPV6_POOL])
    // IP 栈门禁（k8s_cluster.ip_stack）：非双栈集群不给另一族的候选；栈未知 → 不限制
    const ipStack = cluster?.ipStack ?? null
    if (ipStack && ipStack !== 'IPV4' && ipStack !== 'IPV4_AND_IPV6') allowedV4.clear()
    if (ipStack && ipStack !== 'IPV6' && ipStack !== 'IPV4_AND_IPV6') allowedV6.clear()
    const seen = new Set<string>()
    const opts: ReservedIpOption[] = []
    let truncated = false
    const filteredOutPools = new Set<string>()
    for (const r of reservations ?? []) {
      // 池归属：label 优先（创建时写入）；缺失才反查（CIDR 包含，Calico 禁池重叠→无歧义），反查后反写防下次再查
      let pool = r.labels?.[CALICO_POOL_LABEL] ?? null
      if (!pool) {
        const first = (r.reservedCidrs ?? [])[0]
        const ip = first ? (first.includes('/') ? first.split('/')[0]! : first) : ''
        pool = pools.find((pl) => pl.cidr && ip && containsInCidr(pl.cidr, ip))?.name ?? null
        if (pool) void writeBackPoolLabel(r, clusterId, pool)
      }
      if (!pool) continue // 无池归属 → 无法判定是否属于本命名空间可用范围
      const poolObj = pools.find((pl) => pl.name === pool)
      if (!poolObj?.cidr) continue
      const allowed = poolObj.cidr.includes(':') ? allowedV6 : allowedV4
      if (!allowed.has(pool)) { filteredOutPools.add(pool); continue } // 不在本 ns 可用池内 → 不可选
      for (const cidr of r.reservedCidrs ?? []) {
        const exp = expandCidrToIps(cidr, RESERVED_IP_CAP_PER_CIDR)
        if (!exp) continue
        truncated ||= exp.truncated
        for (const ip of exp.ips) if (!seen.has(ip)) { seen.add(ip); opts.push({ ip, pool }) }
      }
    }
    reservedIpOptions.value = opts.sort((a, b) => a.ip.localeCompare(b.ip))
    allowedPoolNames.value = [...allowedV4, ...allowedV6]
    reservedIpsTruncated.value = truncated
    filteredOutPoolNames.value = [...filteredOutPools]
    reservedIpsLoadedFor = cacheKey
  } catch {
    /* 无权限/查询失败 → 空候选（已有值仍可回显），不阻断 */
  }
}

/** 反查后反写：补池归属 label（fire-and-forget；失败=下次再反查，不影响本次展示） */
async function writeBackPoolLabel(r: K8sIpReservation, clusterId: string, pool: string): Promise<void> {
  try {
    await calicoApi.ipreservation.update(r.name, clusterId, {
      ...r,
      labels: { ...(r.labels ?? {}), [CALICO_POOL_LABEL]: pool },
    })
  } catch { /* 无权限/对象已删 → 忽略 */ }
}

watch(() => state.clusterId, () => { reservedIpsLoadedFor = null })
watch([() => state.clusterId, () => state.namespace, staticIpAllowed, hasCalico], () => { void loadReservedIpOptions() }, { immediate: true })

/** 软校验（不阻断提交）：对照地址池 / 保留 IP / 空闲点查给提示；无权限（403）或查询失败 → 不提示 */
interface StaticIpWarning { ip: string; level: 'warning' | 'info'; text: string }
const staticIpWarnings = ref<StaticIpWarning[]>([])
let staticIpCheckTimer: number | undefined

async function runStaticIpChecks(): Promise<void> {
  const clusterId = state.clusterId
  const ips = staticIps.value.map((s) => s.trim()).filter(Boolean)
  // 候选查询走 /calico/**（platform:cluster:manage）；无此权限不发请求，避免 403 弹提示
  if (!clusterId || !staticIpAllowed.value || ips.length === 0 || !perm.has(apiCodes.clusterManage)) {
    staticIpWarnings.value = []
    return
  }
  try {
    const [pools, reservations] = await Promise.all([
      calicoApi.ippool.list({ clusterId }),
      calicoApi.ipreservation.list({ clusterId }),
    ])
    const reservedCidrs = (reservations ?? []).flatMap((r) => r.reservedCidrs ?? [])
    const warnings: StaticIpWarning[] = []
    for (const ip of ips) {
      const inPool = (pools ?? []).some((p) => p.cidr && containsInCidr(p.cidr, ip))
      if (!inPool) {
        warnings.push({ ip, level: 'warning', text: `${ip} 不在任何地址池内（Calico 仍会按注解生效，请确认该 IP 可用）` })
        continue
      }
      if (reservedCidrs.some((c) => containsInCidr(c, ip))) {
        warnings.push({ ip, level: 'info', text: `${ip} 已保留` })
        continue
      }
      let free = true
      try { free = await calicoApi.ipam.isFree(clusterId, ip) } catch { /* 点查失败按未知处理，不断言占用 */ }
      if (!free) warnings.push({ ip, level: 'warning', text: `${ip} 已被分配或保留` })
      else warnings.push({ ip, level: 'info', text: `${ip} 空闲但未保留——建议在「保留 IP」页先保留，防止被他人抢占` })
    }
    staticIpWarnings.value = warnings
  } catch {
    staticIpWarnings.value = [] // 拦截器已提示；不阻断提交
  }
}

watch(staticIps, () => {
  window.clearTimeout(staticIpCheckTimer)
  staticIpCheckTimer = window.setTimeout(() => { void runStaticIpChecks() }, 500)
}, { deep: true })
watch(() => state.clusterId, () => {
  window.clearTimeout(staticIpCheckTimer)
  staticIpWarnings.value = []
})

// ---------- 服务网格 Ambient（B3 §11；pod template 的 istio 两个保留 label） ----------
/**
 * 门禁：租户侧只能从集群能力快照知道「装没装 Istio」（`istio.io` / `networking.istio.io`），
 * **ambient 是否真的在跑只有平台侧的 ztunnel 活探测知道**（见 useMeshStatus）。故这里以 hasIstio 为准，
 * 不拿 ambient 当硬门禁 —— 写这两个标签本身无害（没装 ambient 时 istio 直接忽略），
 * 而"完全不显示开关"会让租户连表达意图的机会都没有。
 */
const mesh = useMeshStatus(computed(() => state.clusterId ?? null))
const meshAvailable = computed(() => mesh.hasIstio.value)

/** 本命名空间的 waypoint 候选（租户域 /gateways，前端按 -waypoint 类名筛；判定规则同后端） */
const waypointOptions = ref<string[]>([])
const waypointLoaded = ref(false)
async function loadWaypointOptions(): Promise<void> {
  waypointOptions.value = []
  waypointLoaded.value = false
  const { tenantId, clusterId, namespace } = state
  if (!tenantId || !clusterId || !namespace || !meshAvailable.value) return
  // 无 tenant:gateway:list → 不发注定 403 的请求（下拉降级为空，仍可手输）
  if (!perm.has(apiCodes.gatewayList)) { waypointLoaded.value = true; return }
  try {
    const list = await gatewayApi.list({ tenantId, clusterId, namespace })
    waypointOptions.value = (list ?? []).filter((g: K8sGateway) => isWaypointGateway(g)).map((g) => g.name).sort()
  } catch {
    waypointOptions.value = [] // 集群未装 Gateway API（CRD 404）等：拦截器已提示，这里降级为空
  } finally {
    waypointLoaded.value = true
  }
}
watch([() => state.clusterId, () => state.namespace, () => state.tenantId, meshAvailable],
  () => { void loadWaypointOptions() }, { immediate: true })

/** 写一个 istio 保留 label（空串 / 未选 → 删除该键，回到"跟随命名空间"） */
function setMeshLabel(key: string, value: string): void {
  const next: Record<string, string> = { ...(form.labels ?? {}) }
  if (value) next[key] = value
  else delete next[key]
  form.labels = Object.keys(next).length ? next : undefined
}

const dataplaneMode = computed<string>({
  get: () => form.labels?.[LABEL_DATAPLANE_MODE] ?? '',
  set: (v) => setMeshLabel(LABEL_DATAPLANE_MODE, v),
})
const useWaypoint = computed<string>({
  get: () => form.labels?.[LABEL_USE_WAYPOINT] ?? '',
  set: (v) => setMeshLabel(LABEL_USE_WAYPOINT, v),
})

/** 选中的 waypoint 不在候选里 → 软提示（Istio 对不存在的 waypoint 静默放行，不报错，故必须自己提示） */
const waypointMissing = computed(() =>
  waypointLoaded.value
  && !!useWaypoint.value && useWaypoint.value !== USE_WAYPOINT_NONE
  && !waypointOptions.value.includes(useWaypoint.value))

const serviceName = computed<string>({
  get: () => form.serviceName ?? '',
  set: (v) => { form.serviceName = v || null },
})

/** minReadySeconds（deployment + statefulset）：可清空 → null */
const minReadySeconds = computed<number | undefined>({
  get: () => form.minReadySeconds ?? undefined,
  set: (v) => { form.minReadySeconds = v ?? null },
})

/** paused（deployment）：switch 恒显式布尔（避免取消勾选时因 null 被 SSA 省略而「无法恢复」） */
const paused = computed<boolean>({
  get: () => form.paused === true,
  set: (v) => { form.paused = v },
})

/** podManagementPolicy（statefulset）：OrderedReady / Parallel */
const podManagementPolicy = computed<string | undefined>({
  get: () => form.podManagementPolicy ?? undefined,
  set: (v) => { form.podManagementPolicy = (v || null) as WorkloadDetail['podManagementPolicy'] },
})

function ensurePvcPol(): NonNullable<WorkloadDetail['persistentVolumeClaimRetentionPolicy']> {
  if (!form.persistentVolumeClaimRetentionPolicy) form.persistentVolumeClaimRetentionPolicy = {}
  return form.persistentVolumeClaimRetentionPolicy
}
const whenDeleted = computed<string | undefined>({
  get: () => form.persistentVolumeClaimRetentionPolicy?.whenDeleted ?? undefined,
  set: (v) => { ensurePvcPol().whenDeleted = v || null },
})
const whenScaled = computed<string | undefined>({
  get: () => form.persistentVolumeClaimRetentionPolicy?.whenScaled ?? undefined,
  set: (v) => { ensurePvcPol().whenScaled = v || null },
})

/** ordinals.start（statefulset，默认 0） */
const ordinalsStart = computed<number | undefined>({
  get: () => form.ordinals?.start ?? undefined,
  set: (v) => { if (!form.ordinals) form.ordinals = {}; form.ordinals.start = v ?? null },
})

const mainContainers = computed<ContainerDef[] | undefined>({
  get: () => form.podTemplate?.spec.containers,
  set: (v) => { ensureSpec().containers = v ?? [] },
})

const initContainers = computed<ContainerDef[] | undefined>({
  get: () => form.podTemplate?.spec.initContainers ?? undefined,
  set: (v) => { ensureSpec().initContainers = v },
})

const serviceAccountName = computed<string>({
  get: () => form.podTemplate?.spec.serviceAccountName ?? '',
  set: (v) => { ensureSpec().serviceAccountName = v || null },
})

const nodeName = computed<string>({
  get: () => form.podTemplate?.spec.nodeName ?? '',
  set: (v) => { ensureSpec().nodeName = v || null },
})

const nodeSelector = computed<Record<string, string> | undefined>({
  get: () => form.podTemplate?.spec.nodeSelector ?? undefined,
  set: (v) => { ensureSpec().nodeSelector = v },
})

const affinity = computed<Affinity | undefined>({
  get: () => form.podTemplate?.spec.affinity ?? undefined,
  set: (v) => { ensureSpec().affinity = v },
})

const tolerations = computed<Toleration[] | undefined>({
  get: () => form.podTemplate?.spec.tolerations ?? undefined,
  set: (v) => { ensureSpec().tolerations = v },
})

const volumes = computed<VolumeDef[] | undefined>({
  get: () => form.podTemplate?.spec.volumes ?? undefined,
  set: (v) => { ensureSpec().volumes = v },
})

/** imagePullSecrets：简单 name 行列表；增删走 setter（null 时 get 返回临时数组，直接改会丢） */
const imagePullSecretsList = computed<{ name: string }[]>({
  get: () => form.podTemplate?.spec.imagePullSecrets ?? [],
  set: (v) => { ensureSpec().imagePullSecrets = v },
})

function addImagePullSecret(): void {
  imagePullSecretsList.value = [...imagePullSecretsList.value, { name: '' }]
}

function removeImagePullSecret(i: number): void {
  const next = [...imagePullSecretsList.value]
  next.splice(i, 1)
  imagePullSecretsList.value = next
}

const volumeClaimTemplates = computed<PvcTemplate[] | undefined>({
  get: () => form.volumeClaimTemplates ?? undefined,
  set: (v) => { form.volumeClaimTemplates = v },
})

// ---------- volumeNames：spec.volumes ∪ STS volumeClaimTemplates（模板名同样可被容器按名挂载） ----------
const volumeNames = computed(() => [
  ...(form.podTemplate?.spec.volumes ?? []).map((v) => v.name),
  ...(form.kind === 'statefulset' ? (form.volumeClaimTemplates ?? []).map((t) => t.name) : []),
])

// ---------- kind 切换：清掉各 kind 专属字段，避免提交不一致 body ----------
watch(
  () => form.kind,
  (k) => {
    if (k !== 'statefulset') {
      form.serviceName = null
      form.volumeClaimTemplates = null
      form.podManagementPolicy = null
      form.persistentVolumeClaimRetentionPolicy = null
      form.ordinals = null
    }
    if (k !== 'deployment') {
      form.paused = null
    }
  },
)

// ---------- 编辑回填（deep-merge 到骨架，缺省字段落回默认） ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

/**
 * platform-api 的 JsonMapper 全局约定：null 对象/数组序列化为 {} / []。编辑器按真值判断互斥子结构
 * （卷类型、投影来源种类、probe handler 等），空壳会被误判为「已选」——如 configMap 投影来源因
 * serviceAccountToken:{} 恒真而渲染成空的 serviceAccountToken。回填前把空对象/数组归一化为 null；
 * "" / 0 / false 是合法值（如 emptyDir.medium=""），不动。
 */
function normalizeEmpty(v: unknown): unknown {
  if (Array.isArray(v)) {
    const arr = v.map(normalizeEmpty)
    return arr.length > 0 ? arr : null
  }
  if (v !== null && typeof v === 'object') {
    const obj: Record<string, unknown> = {}
    let empty = true
    for (const [k, x] of Object.entries(v as Record<string, unknown>)) {
      const nx = normalizeEmpty(x)
      obj[k] = nx
      if (nx !== null) empty = false
    }
    return empty ? null : obj
  }
  return v
}

function applyDetail(d: WorkloadDetail): void {
  Object.assign(form, normalizeEmpty(d) as WorkloadDetail)
  const spec = form.podTemplate?.spec
  // 保证 podTemplate.spec + 非空 containers；B1：restartPolicy 固定 Always
  if (!form.podTemplate || !spec || (spec.containers?.length ?? 0) === 0) {
    form.podTemplate = defaultForm().podTemplate
  } else {
    spec.restartPolicy = 'Always'
  }
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await workloadApi.get(editing.value, {
      tenantId: state.tenantId!,
      clusterId: state.clusterId!,
      namespace: state.namespace!,
    })
    applyDetail(d)
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

onMounted(() => {
  void load()
  refreshNodes()
  if (editing.value && ready.value) void loadDetail()
  mql.addEventListener('change', onBreakpointChange)
})
onBeforeUnmount(() => {
  mql.removeEventListener('change', onBreakpointChange)
})
// 上下文晚于挂载才选齐（未持久化上次选择）时补拉详情
watch(ready, (r) => {
  if (r && editing.value && detailState.value === 'idle') void loadDetail()
})

const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

// ---------- 提交：本地校验 → 归一化 → 组装 body ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

/** NodeSelectorTerm 是否有实际内容（空 term 在 OR 语义下匹配所有节点，会令整个 required 失效） */
function termHasContent(t: NodeSelectorTerm): boolean {
  return (t.matchExpressions?.length ?? 0) > 0 || (t.matchFields?.length ?? 0) > 0
}

/** 提交前归一化：imagePullPolicy '' → null；无 handler（httpGet/tcpSocket/exec）的 probe 壳 → null（A2 兜底）；affinity 剔除空 term（空 nodeSelectorTerm 序列化为 {}，OR 语义下静默废掉整个 required） */
function normalizeForSubmit(): void {
  // STS：空壳的保留策略 / ordinals 归一化为 null（避免提交 {} 被 SSA 建出空对象）
  const pol = form.persistentVolumeClaimRetentionPolicy
  if (pol && !pol.whenDeleted && !pol.whenScaled) form.persistentVolumeClaimRetentionPolicy = null
  if (form.ordinals && form.ordinals.start == null) form.ordinals = null

  // 固定 IP：trim 去空；门控不满足（daemonset/多副本）强制置 null，防旧值随行提交
  const pt0 = form.podTemplate
  if (pt0?.staticIps) {
    const kept = pt0.staticIps.map((s) => s.trim()).filter(Boolean)
    pt0.staticIps = staticIpAllowed.value && kept.length > 0 ? kept : null
  }

  const spec = form.podTemplate?.spec
  if (!spec) return
  for (const c of [...spec.containers, ...(spec.initContainers ?? [])]) {
    if (c.imagePullPolicy === '') c.imagePullPolicy = null
    for (const k of ['livenessProbe', 'readinessProbe', 'startupProbe'] as const) {
      const p = c[k]
      if (p && !p.httpGet && !p.tcpSocket && !p.exec) c[k] = null
    }
    // 端口默认补的一行可能没填 containerPort → 剔除空端口，全空则置 null（避免提交 {containerPort:null}）
    if (c.ports && c.ports.length > 0) {
      const kept = c.ports.filter((p) => p.containerPort != null)
      c.ports = kept.length > 0 ? kept : null
    }
  }
  const aff = spec.affinity
  if (!aff) return
  const na = aff.nodeAffinity
  if (na) {
    if (na.required) {
      const kept = na.required.nodeSelectorTerms.filter(termHasContent)
      na.required = kept.length > 0 ? { nodeSelectorTerms: kept } : null
    }
    if (na.preferred) {
      const keptPref = na.preferred.filter((p) => termHasContent(p.preference))
      na.preferred = keptPref.length > 0 ? keptPref : null
    }
    if (!na.required && !na.preferred) aff.nodeAffinity = null
  }
  const pa = aff.podAntiAffinity
  if (pa) {
    if (pa.required && pa.required.length === 0) pa.required = null
    if (pa.preferred && pa.preferred.length === 0) pa.preferred = null
    if (!pa.required && !pa.preferred) aff.podAntiAffinity = null
  }
  const pf = aff.podAffinity
  if (pf) {
    if (pf.required && pf.required.length === 0) pf.required = null
    if (pf.preferred && pf.preferred.length === 0) pf.preferred = null
    if (!pf.required && !pf.preferred) aff.podAffinity = null
  }
  if (!aff.nodeAffinity && !aff.podAffinity && !aff.podAntiAffinity) spec.affinity = null
}

const saving = ref(false)

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return

  // 1) name RFC1123
  const name = form.name.trim()
  if (!name || !RFC1123_RE.test(name)) {
    ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾')
    return
  }

  const spec = form.podTemplate?.spec
  const mains = spec?.containers ?? []
  const inits = spec?.initContainers ?? []

  // 2) 至少一个主容器
  if (mains.length === 0) {
    ElMessage.warning('至少需要一个容器')
    return
  }

  // 3) 容器名在 main ∪ init 内唯一（空名 = 未填写，交给后端提示）
  const seen = new Set<string>()
  for (const c of [...mains, ...inits]) {
    const n = c.name.trim()
    if (!n) continue
    if (seen.has(n)) {
      ElMessage.warning(`容器名重复：${n}`)
      return
    }
    seen.add(n)
  }

  // 4) volumeMount 引用完整性（含 STS 模板名）
  const defined = new Set(volumeNames.value)
  for (const c of [...mains, ...inits]) {
    for (const m of c.volumeMounts ?? []) {
      if (!defined.has(m.name)) {
        ElMessage.warning(`volumeMount「${m.name}」没有对应的 Volume（容器：${c.name || '未命名'}）`)
        return
      }
    }
  }

  // 5) STS C4：每个 volumeClaimTemplate 须被某容器以同名 volumeMount 引用
  if (form.kind === 'statefulset') {
    const mounted = new Set([...mains, ...inits].flatMap((c) => (c.volumeMounts ?? []).map((m) => m.name)))
    for (const t of form.volumeClaimTemplates ?? []) {
      if (!mounted.has(t.name)) {
        ElMessage.warning(`volumeClaimTemplate「${t.name || '未命名'}」未被任何容器以同名 volumeMount 引用`)
        return
      }
    }
  }

  // 6) B4：requests ≤ limits（主容器 + init 容器 tab 列表 → ResourcesEditor.isValid）
  if (!(mainTabsRef.value?.isValid() ?? true) || !(initTabsRef.value?.isValid() ?? true)) {
    ElMessage.warning('资源 requests 不能大于 limits')
    return
  }

  // 6.5) 固定 IP 格式（v4/v6）；单副本门控后端 A6 再兜底
  for (const raw of form.podTemplate?.staticIps ?? []) {
    const t = raw.trim()
    if (t && !isIp(t)) {
      ElMessage.warning(`固定 IP 格式不正确：${t}`)
      return
    }
  }

  normalizeForSubmit()
  const body: WorkloadDetail = { ...form, namespace: state.namespace!, kind: form.kind }
  if (body.kind === 'daemonset') body.replicas = null

  saving.value = true
  try {
    const ctx2 = { tenantId: state.tenantId!, clusterId: state.clusterId! }
    if (editing.value) {
      await workloadApi.update(editing.value, ctx2, body)
      ElMessage.success('保存成功')
    } else {
      await workloadApi.create(ctx2, body)
      ElMessage.success('创建成功')
    }
    router.push('/resources/workloads')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function goBack(): void {
  router.push('/resources/workloads')
}

// ---------- 页面元信息 ----------
const pageTitle = computed(() => (editing.value ? `编辑工作负载` : '创建工作负载'))

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader :title="pageTitle">
      <!-- 类型 kind：与标题同行、靠右，但不顶到最右（与「返回」之间留间距） -->

      <el-button class="ph-back" @click="goBack">返回</el-button>
    </PageHeader>

    <!-- 上下文未选齐 -->
    <EmptyState
      v-if="!ready"
      title="尚未选择上下文"
      description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑工作负载。"
    />

    <template v-else>
      <div v-if="formVisible" class="editor-layout">
        <!-- 主内容区：模块用 v-show 切换，全部常驻 DOM（子编辑器 ref / B4 校验不受切模块影响） -->
        <div class="editor-main">
          <!-- 模块①：基础信息（含更新策略、主容器常用字段、镜像拉取/账号） -->
          <div v-show="activeModule === 'basic'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header><span class="sec-title">基础信息</span></template>
              <el-form label-width="140px" label-position="left">
                <el-form-item>
                  <template #label>类型 <FieldHelp tip="工作负载类型：Deployment（无状态）、StatefulSet（有状态，稳定网络标识+独立存储）、DaemonSet（每个节点一个副本）。创建后不可修改。" /></template>
                    <el-select v-model="kind" :disabled="!!editing" style="width: 180px">
                      <el-option label="Deployment" value="deployment" />
                      <el-option label="StatefulSet" value="statefulset" />
                      <el-option label="DaemonSet" value="daemonset" />
                    </el-select>
                </el-form-item>

                <el-form-item required>
                  <template #label>名称 <FieldHelp tip="工作负载名称，须符合 RFC1123（小写字母/数字/-，以字母或数字开头结尾）。创建后不可修改。" /></template>
                  <el-input v-model="form.name" :disabled="!!editing" placeholder="例如 web-app" style="width: 360px" />
                </el-form-item>
                <el-form-item>
                  <template #label>描述 <FieldHelp tip="可选的描述信息，会存到 K8s 注解里，便于识别用途。" /></template>
                  <el-input v-model="description" type="textarea" :rows="2" placeholder="可选" style="width: 360px" />
                </el-form-item>

                  <!-- #3 名称在最上 -->
                  <el-form-item required>
                    <template #label>容器名称 <FieldHelp tip="主容器的名字，须为 DNS_LABEL（小写字母/数字/连字符，≤63 位）。同一 Pod 内唯一。" /></template>
                    <el-input v-model="pName" placeholder="如 web" style="width: 360px" />
                  </el-form-item>
                  <!-- #3 镜像在名称下；#5 拉取策略与镜像同行、在右侧 -->

                    <el-form-item required>
                      <template #label>镜像 <FieldHelp tip="主容器运行的容器镜像，如 nginx:1.27。" /></template>
                      <el-input v-model="pImage" placeholder="如 nginx:1.27" style="width: 360px" />
                    </el-form-item>
                    <el-form-item>
                      <template #label>拉取策略 <FieldHelp tip="imagePullPolicy：Always=每次拉取 / IfNotPresent=本地有则用 / Never=仅本地。留空由 K8s 按 tag 决定。" /></template>
                      <el-select v-model="pPullPolicy" clearable placeholder="默认" style="width: 360px">
                        <el-option v-for="p in ['IfNotPresent', 'Always', 'Never']" :key="p" :label="p" :value="p" />
                      </el-select>
                    </el-form-item>


                  <!-- #6 端口在镜像和命令中间 -->
                  <el-form-item required>
                    <template #label>端口 <FieldHelp tip="容器监听的端口，供 Service / 探针按名或按号引用。" /></template>
                    <PortEditor v-model="pPorts" />
                  </el-form-item>
                  <!-- #4 命令在镜像下；参数与命令同行、在命令左侧 -->
                  <div class="field-pair">
                    <el-form-item>
                      <template #label>Command <FieldHelp tip="覆盖镜像默认的启动命令，每行一个或用逗号分隔。" /></template>
                      <el-input v-model="pCommandText" type="textarea" :rows="2" placeholder="每行一个（或逗号分隔）" style="width: 360px" />
                    </el-form-item>
                    <el-form-item label-width="60px">
                      <template #label>Args <FieldHelp tip="传给启动命令的参数，每行一个或用逗号分隔。" /></template>
                      <el-input v-model="pArgsText" type="textarea" :rows="2" placeholder="每行一个（或逗号分隔）" style="width: 360px" />
                    </el-form-item>
                  </div>
                  <!-- #7 工作目录在命令 / 参数下面 -->
                  <el-form-item>
                    <template #label>工作目录 <FieldHelp tip="workingDir：容器内进程的工作目录（可选）。" /></template>
                    <el-input v-model="pWorkingDir" placeholder="可选，如 /app" style="width: 360px" />
                  </el-form-item>
                <el-form-item>
                  <template #label>标签<FieldHelp tip="键值标签，用于筛选与分组（kubectl -l、Service selector 等）。" /></template>
                  <LabelEditor v-model="labels" :exclude-keys="RESERVED_MESH_LABELS" class="sub-editor" style="max-width: 520px" />
                </el-form-item>
                <el-form-item v-if="form.kind !== 'daemonset'">
                  <template #label>副本 <FieldHelp tip="期望的副本数量。DaemonSet 由节点数决定，不设置此项。" /></template>
                  <el-input-number v-model="replicas" :min="0" :max="64" controls-position="right" />
                </el-form-item>
                <el-form-item v-if="hasCalico">
                  <template #label>固定 IP<FieldHelp tip="从本命名空间绑定地址池（未绑定则 Calico 默认池）的保留 IP 中为 Pod 指定固定 IP（Calico ipAddrs 注解，支持 IPv4/IPv6 双栈）。仅单副本（replicas=1）的 Deployment/StatefulSet 支持；IPv6 候选仅双栈集群（IPV4_AND_IPV6）提供。请先在「保留 IP」页保留目标 IP。" /></template>
                  <el-select
                    v-model="staticIps" multiple filterable clearable :disabled="!staticIpAllowed"
                    placeholder="从保留 IP 中选择（可搜索）" style="width: 420px"
                  >
                    <el-option v-for="o in reservedIpOptions" :key="o.ip" :label="`${o.ip}（${o.pool}）`" :value="o.ip" />
                  </el-select>
                  <div v-if="!staticIpAllowed" class="form-tip warn">仅单副本（replicas=1）的 Deployment/StatefulSet 可配置固定 IP（当前：{{ form.kind === 'daemonset' ? 'DaemonSet 不支持' : `replicas=${form.replicas ?? 1}` }}）</div>
                  <template v-else>
                    <div v-if="perm.has(apiCodes.clusterManage) && allowedPoolNames.length > 0" class="form-tip">本命名空间可用池：{{ allowedPoolNames.join('、') }}<template v-if="reservedIpOptions.length === 0"><template v-if="filteredOutPoolNames.length">——集群已有保留 IP，但都不在以上池内（当前涉及：{{ filteredOutPoolNames.join('、') }}），请在「保留 IP」页把目标 IP 保留到上述池</template><template v-else>——暂无保留 IP 候选，请先在「保留 IP」页从这些池保留目标 IP</template></template></div>
                    <div v-else-if="!perm.has(apiCodes.clusterManage)" class="form-tip">无集群管理权限，取不到保留 IP 候选（已有值仍可回显提交）</div>
                    <div v-if="reservedIpsTruncated" class="form-tip">部分保留段超过 {{ RESERVED_IP_CAP_PER_CIDR }} 个 IP，未全部展开</div>
                  </template>
                  <el-alert v-for="w in staticIpWarnings" :key="w.ip" :title="w.text" :type="w.level" :closable="false" class="static-ip-warn" />
                </el-form-item>
                <!-- 服务网格 Ambient（B3 §11）：写 pod template 的两个 istio 保留 label -->
                <el-form-item v-if="meshAvailable">
                  <template #label>服务网格（Ambient）<FieldHelp tip="pod template 标签 istio.io/dataplane-mode（纳入 / 排除 ambient）与 istio.io/use-waypoint（本工作负载的七层流量走哪个 waypoint）。都留空 = 跟随命名空间设置 —— Pod 上的标签优先于命名空间。⚠️ 改的是 pod template，保存后会触发一次滚动更新，存量 Pod 需重建才带新标签。" /></template>
                  <div class="pool-selects">
                    <el-select v-model="dataplaneMode" style="width: 100%" placeholder="跟随命名空间（不设标签）">
                      <el-option label="跟随命名空间（不设标签）" value="" />
                      <el-option v-for="m in DATAPLANE_MODES" :key="m.value" :label="m.label" :value="m.value" />
                    </el-select>
                    <el-select v-model="useWaypoint" style="width: 100%" placeholder="跟随命名空间（不设标签）">
                      <el-option label="跟随命名空间（不设标签）" value="" />
                      <el-option label="不使用 waypoint（none）" :value="USE_WAYPOINT_NONE" />
                      <el-option v-for="w in waypointOptions" :key="w" :label="w" :value="w" />
                    </el-select>
                  </div>
                  <div v-if="!perm.has(apiCodes.gatewayList)" class="form-tip">无 Gateway 列表读权限，候选取不到（已有值仍可回显提交）。</div>
                  <div v-else-if="!waypointLoaded" class="form-tip">正在加载本命名空间的 waypoint 候选…</div>
                  <div v-else-if="!waypointOptions.length" class="form-tip">
                    本命名空间还没有 waypoint Gateway——L7 需要先有 waypoint（「Gateway」页 →「创建 Waypoint」，每命名空间至多一个）；此处仍可手输名字，但名字不存在时 istio 会静默放行、L7 策略不生效。
                  </div>
                  <div v-if="waypointMissing" class="form-tip warn">「{{ useWaypoint }}」不在本命名空间的 waypoint 候选里（可能已被删除）——istio 对不存在的 waypoint 静默放行，L7 策略不会生效。</div>
                </el-form-item>
                <el-form-item v-if="form.kind !== 'daemonset'">
                  <template #label>就绪最短秒数 <FieldHelp tip="minReadySeconds：控制 Pod 被标记为“可用（Available）”之前，必须保持 Ready 状态的最短时间。" /></template>
                  <el-input-number v-model="minReadySeconds" :min="0" controls-position="right" placeholder="默认 0" />
                </el-form-item>
                <el-form-item v-if="form.kind === 'deployment'">
                  <template #label>暂停更新 <FieldHelp tip="paused：暂停 Deployment 滚动更新。暂停后修改不会触发新 rollout；取消勾选即恢复。" /></template>
                  <el-switch v-model="paused" />
                </el-form-item>
                <el-form-item v-if="form.kind === 'statefulset'">
                  <template #label>Headless Service<FieldHelp tip="StatefulSet 关联的 Headless Service 名称，提供稳定网络标识；留空默认等于工作负载名。创建后不可修改。" /></template>
                  <el-input v-model="serviceName" :disabled="!!editing" placeholder="留空默认 = 工作负载名" style="width: 360px" />
                </el-form-item>
                <el-form-item v-if="form.kind === 'statefulset'">
                  <template #label>Pod 管理策略 <FieldHelp tip="podManagementPolicy：OrderedReady=按序号依次就绪；Parallel=并行创建/销毁（缩容从最大序号开始）。默认 OrderedReady。" /></template>
                  <el-select v-model="podManagementPolicy" clearable placeholder="默认 OrderedReady" style="width: 240px">
                    <el-option label="OrderedReady（默认）" value="OrderedReady" />
                    <el-option label="Parallel" value="Parallel" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="form.kind === 'statefulset'">
                  <template #label>PVC 保留策略 <FieldHelp tip="persistentVolumeClaimRetentionPolicy：StatefulSet 删除（whenDeleted）或缩容（whenScaled）时对应 PVC 的处理。Retain=保留，Delete=删除。默认均 Retain。" /></template>
                  <div class="retention-row">
                    <div class="retention-field">
                      <span class="retention-label">whenDeleted:</span>
                      <el-select v-model="whenDeleted" clearable placeholder="默认 Retain" style="width: 180px">
                        <el-option label="Retain（默认）" value="Retain" />
                        <el-option label="Delete" value="Delete" />
                      </el-select>
                    </div>
                    <div class="retention-field">
                      <span class="retention-label">whenScaled:</span>
                      <el-select v-model="whenScaled" clearable placeholder="默认 Retain" style="width: 180px">
                        <el-option label="Retain（默认）" value="Retain" />
                        <el-option label="Delete" value="Delete" />
                      </el-select>
                    </div>
                  </div>
                </el-form-item>
                <el-form-item v-if="form.kind === 'statefulset'">
                  <template #label>编号起始值 <FieldHelp tip="ordinals.start：StatefulSet 副本编号的起始值（Pod 名 -0、-1…）。默认 0。" /></template>
                  <el-input-number v-model="ordinalsStart" :min="0" controls-position="right" placeholder="默认 0" />
                </el-form-item>

                <el-form-item>
                  <template #label>ServiceAccount <FieldHelp tip="Pod 使用的 ServiceAccount，决定其访问 K8s API 的权限；留空默认 default。" /></template>
                  <el-input v-model="serviceAccountName" placeholder="可选，默认 default" style="width: 360px" />
                </el-form-item>
              </el-form>
            </el-card>

          </div>

          <!-- 模块②：Pod 容器（主容器 tab） -->
          <div v-show="activeModule === 'containers'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header>
                <div class="sec-head">
                  <span class="sec-title">容器 containers</span>
                  <el-button plain size="medium" @click="mainTabsRef?.add()">+ 添加容器</el-button>
                </div>
              </template>
              <ContainerListEditor
                ref="mainTabsRef"
                v-model="mainContainers"
                :is-init="false"
                :volume-names="volumeNames"
                :external-names="initNames"
              />
            </el-card>
          </div>

          <!-- 模块③：初始化容器（init 容器 tab） -->
          <div v-show="activeModule === 'init'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header>
                <div class="sec-head">
                  <span class="sec-title">Init 容器 initContainers（可选）</span>
                  <el-button plain  @click="initTabsRef?.add()">+ 添加容器</el-button>
                </div>
              </template>
              <ContainerListEditor
                ref="initTabsRef"
                v-model="initContainers"
                :is-init="true"
                :volume-names="volumeNames"
                :external-names="mainNames"
              />
            </el-card>
          </div>

          <!-- 模块：更新策略（不常用，单独一屏；放在初始化容器之后） -->
          <div v-show="activeModule === 'strategy'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header><span class="sec-title">更新策略</span></template>
              <StrategyEditor v-model="strategy" :kind="form.kind" />
            </el-card>
          </div>

          <!-- 模块④：调度策略 -->
          <div v-show="activeModule === 'scheduling'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header><span class="sec-title">调度策略</span></template>
              <el-form label-width="220px" label-position="left">
                <el-form-item>
                  <template #label>节点名称（nodeName） <FieldHelp tip="把 Pod 固定调度到指定节点（一般不用）。设置后会忽略 nodeSelector / 亲和。" /></template>
                  <el-select
                    v-model="nodeName" filterable allow-create default-first-option clearable
                    :loading="nodeCatalog.loading" placeholder="可选，选择或输入某节点名" style="width: 280px"
                  >
                    <el-option v-for="n in nodeCatalog.nodeNames" :key="n" :label="n" :value="n" />
                  </el-select>
                  <div v-if="nodeName" class="form-tip warn">设置 nodeName 后，nodeSelector / 亲和的节点选择将被忽略</div>
                </el-form-item>
                <el-form-item>
                  <template #label>节点选择器（nodeSelector） <FieldHelp tip="按节点标签筛选可调度节点（所有键值须全部匹配）。键 / 值可从集群现有节点标签中选择，也可自行输入。" /></template>
                  <NodeLabelEditor v-model="nodeSelector" class="sub-editor" />
                </el-form-item>
                <el-form-item>
                  <template #label>亲和性（affinity） <FieldHelp tip="更灵活的节点 / Pod 亲和与反亲和规则（required 必须满足，preferred 尽量满足）。" /></template>
                  <AffinityEditor v-model="affinity" />
                </el-form-item>
                <el-form-item>
                  <template #label>容忍（tolerations） <FieldHelp tip="容忍度：让 Pod 可调度到带有对应污点（taint）的节点。" /></template>
                  <TolerationEditor v-model="tolerations" />
                </el-form-item>
              </el-form>
            </el-card>
          </div>

          <!-- 模块⑤：存储（volumes 全 kind；pvc 模板仅 statefulset） -->
          <div v-show="activeModule === 'storage'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header><span class="sec-title">卷 volumes <FieldHelp tip="Pod 级卷定义，供容器按名挂载（emptyDir / configMap / secret / PVC 等）。" /></span></template>
              <VolumeEditor v-model="volumes" />
            </el-card>
            <el-card v-if="form.kind === 'statefulset'" shadow="never" class="sec-card">
              <template #header><span class="sec-title">存储卷模板 volumeClaimTemplates <FieldHelp tip="StatefulSet 专属：为每个副本自动创建独立 PVC 的模板。" /></span></template>
              <PvcTemplateEditor v-model="volumeClaimTemplates" :disabled="!!editing" />
            </el-card>
          </div>

          <!-- 操作 -->
          <div class="form-actions">
            <el-button @click="goBack">取消 / 返回</el-button>
            <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
          </div>
        </div>

        <!-- 右侧模块导航：一整块面板 + el-menu（:collapse 驱动 icon 轨 / 完整两态，与左侧主菜单同机制） -->
        <nav class="editor-nav" :class="{ 'is-collapsed': collapsed }">
          <el-menu
            class="module-menu"
            :collapse="collapsed"
            :default-active="activeModule"
            @select="onModuleSelect"
          >
            <el-menu-item v-for="m in modules" :key="m.key" :index="m.key">
              <el-icon><component :is="m.icon" /></el-icon>
              <template #title>{{ m.label }}</template>
            </el-menu-item>
          </el-menu>
          <!-- 展开/收起：贴在面板左缘垂直中间（= 内容区右边缘正中） -->
          <button type="button" class="nav-toggle" :aria-label="collapsed ? '展开' : '收起'" @click="collapsed = !collapsed">
            <el-icon><ArrowLeft v-if="!collapsed" /><ArrowRight v-else /></el-icon>
          </button>
        </nav>
      </div>

      <!-- 编辑加载失败：不渲染可编辑表单 -->
      <EmptyState
        v-else-if="detailState === 'error'"
        title="加载工作负载失败"
        description="请返回列表重试；若该工作负载已被删除，刷新列表即可。"
      >
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </EmptyState>

      <div v-else class="loading-tip">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
.editor-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  position: relative;
}


.editor-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.module-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 一行内多字段：按内容宽度顺位排列（不平分整行） */
.field-pair {
  display: flex;
  gap: 5rem;
  align-items: flex-start;
}
/* PVC 保留策略：whenDeleted / whenScaled 各带 label + 下拉 */
.retention-row {
  display: flex;
  gap: 2rem;
  align-items: center;
}
.retention-field {
  display: flex;
  align-items: center;
  gap: 8px;
}
.retention-label {
  font-size: 13px;
  color: var(--text-2);
  white-space: nowrap;
}
/* 类型选择器：与标题同行、靠右（不顶到最右） */
.ph-kind {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-right: 5.5rem;
}
.ph-kind-label {
  font-size: 13px;
  color: var(--text-2);
  white-space: nowrap;
}
/* 「返回」与 kind 之间留间距，让 kind 靠右但不贴最右边缘 */
.ph-back {
  margin-left: 40px;
}
/* 卡片头：标题左、操作按钮右 */
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
/* ---------- 右侧模块导航：一整块面板 + el-menu（与左侧主菜单同一套视觉语言） ---------- */
.editor-nav {
  flex-shrink: 0;
  position: sticky;
  top: 16px;
  background: var(--bg-elevated);
  border-radius: 10px;
  transition: width .2s ease;
}
/* 收起态：EP el-menu collapse 的 icon 轨（64px）+ 面板留白 → 65px */
.editor-nav.is-collapsed {
  width: 65px;
}
.module-menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--text-2);
  --el-menu-active-color: var(--accent);
  --el-menu-hover-bg-color: var(--panel-hover);
  border-right: none;

}

.module-menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  border-radius: 8px;
  margin: 2px 0;
}
.module-menu :deep(.el-menu-item.is-active) {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
/* 展开/收起切换按钮：贴在面板左缘垂直中间（= 内容区右边缘正中） */
.nav-toggle {
  position: absolute;
  left: -13px;
  top: 50%;
  transform: translateY(-50%);
  width: 26px;
  height: 26px;
  padding: 0;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  color: var(--text-2);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 3;
  transition: all .15s;
}
.nav-toggle:hover {
  border-color: var(--accent);
  color: var(--accent);
}
/* 窄分辨率（≤1024px）：展开态悬浮覆盖在内容右缘（不挤开内容）；收起态留在流内。
   只锚定 top（不设 bottom）→ 高度 = 自身菜单内容，恒定，不随内容区高度拉伸 */
@media (max-width: 1024px) {
  .editor-nav:not(.is-collapsed) {
    right: 0;
    top: 0px;
    box-shadow: -8px 0 24px -8px rgba(0, 0, 0, .35);
    z-index: 20;
  }
}
.sec-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.sec-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.sub-editor {
  width: 100%;
}
/* 与 NamespaceEditorView 的绑定池选择器同款（仓库无共享编辑器样式表，各页复制为既有惯例） */
.pool-selects {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 420px;
}
.form-tip {
  width: 100%;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.form-tip.warn {
  color: var(--el-color-warning);
}
.static-ip-warn {
  margin-top: 6px;
  max-width: 560px;
}
.kv-editor {
  width: 100%;
}
.kv-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
/* add 按钮按内容自适应宽度，不再铺满整行 */
.add-row-btn {
  width: auto;
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
