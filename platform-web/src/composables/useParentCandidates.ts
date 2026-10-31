import { reactive } from 'vue'
import { meshApi, serviceApi } from '@/api'
import { useResourceContext } from '@/stores/context'
import type { K8sService, ParentGatewayRef } from '@/types'

/**
 * Route 父引用（parentRefs）的**名称候选**目录：按 `(kind, 命名空间)` 缓存。
 *
 * <p><b>为什么要有它</b>：父资源可以是 Gateway 也可以是 Service（Gateway API 的两种 Core 父资源），
 * 候选来源、段名语义都随 kind 变。5 个路由编辑器原本各写一份"只查本命名空间 Gateway"的加载逻辑，
 * 本批把这块收口到这里，编辑器只负责渲染。
 *
 * <p><b>缓存键含 tenant/cluster</b>：切集群或切租户不会串数据。旧键不主动清理（量级 = 访问过的命名空间数）。
 *
 * <p><b>失败静默降级</b>：拉不到候选（CRD 未装 / 无权限 / 集群断开）只置 `failed`，由调用方在
 * `no-data-text` 里说明原因并让用户手输 —— 候选是便利，不是保存的前提。
 *
 * <p>Service 侧过滤掉 ExternalName 与 headless：Gateway API 的 Mesh profile 只认 ClusterIP。
 */
export interface ParentCandidate {
  name: string
  /** Gateway：`istio.io/waypoint-for` label 值（无则 null）；Service 恒 null */
  waypointFor: string | null
  /** Gateway：监听器名（去重保序）；Service 恒 [] */
  listeners: string[]
  /** Service：**有名字的**端口（label 形如 `http（80/TCP）`，与 ServiceMonitorEditorView 同格式）；Gateway 恒 [] */
  namedPorts: { name: string; label: string }[]
  /** Service：端口号（用于区分"有端口但都没命名"与"根本没有端口"）；Gateway 恒 [] */
  ports: number[]
}

export interface CandidateBucket {
  list: ParentCandidate[]
  loading: boolean
  failed: boolean
}

/** 未加载 / 未建模 kind 的统一空桶（共享只读对象，不要往里写） */
const EMPTY: CandidateBucket = { list: [], loading: false, failed: false }

/**
 * 候选的保鲜期：超过它再 `ensure` 会**重新拉取**（旧的列表继续可见，直到新数据回来）。
 * <p>没有它就会出现一个回退：改造前每次进编辑器都会重新拉，改完变成"整个 SPA 会话只拉一次"，
 * 于是"刚创建的 Gateway/Service 不出现在下拉里，直到刷新页面"。取值与 platform-api 的权限闭包缓存同口径（TTL-only 30s）。
 */
const TTL_MILLIS = 30_000

const KIND_GATEWAY = ''
const KIND_SERVICE = 'Service'

/**
 * 父资源 kind 的归一：**空串与显式 `'Gateway'` 等价**（后者就是 CRD 的默认值）。
 * <p>为什么要归一：一条"合法但显式写了 `kind: Gateway`"的父引用（用户可能在 YAML tab 或 kubectl 里写过）
 * 若按原值进表单，就会被本模块当作"未建模 kind"——候选不列、行名变「段名」、还挂一条"本页不改它"的
 * 只读提示；用户为"修好它"去类型下拉里选回默认值时，那段清空逻辑会连带清掉名称，
 * 保存时该行被 `filter(p => p.name.trim())` 丢掉 → **父引用静默消失**。写法上归一成 `''` 后，写盘仍是
 * CRD 默认 Gateway，语义完全不变（SSA 会移除显式 kind，apiserver 补默认值）。
 */
export function normalizeParentKind(kind: string | null | undefined): string {
  return !kind || kind === 'Gateway' ? KIND_GATEWAY : kind
}

const buckets = reactive(new Map<string, CandidateBucket>())
/** 每个 key 上次**发起**拉取的时刻（拉取失败会清掉，让下一次 ensure 立刻重试） */
const fetchedAt = new Map<string, number>()
const { state } = useResourceContext()

function keyOf(kind: string, namespace: string): string {
  return `${state.clusterId ?? ''}|${state.tenantId ?? ''}|${kind}|${namespace}`
}

/**
 * Gateway 候选走 `/mesh/parent-gateways`，**不是**租户域的 `/gateways/list`。
 *
 * 候选里必须包含平台代管的 `istio-system/external-gateway`（对外网关），而租户对那个命名空间
 * 没有任何 K8s 权限 —— 走租户端点它永远不会出现，用户只能手打名字，打错还静默不挂载。
 * 白名单（固定 `istio-system`）与字段投影都在后端写死，见 spec 2026-10-31 §5.3。
 */
function gatewayCandidate(g: ParentGatewayRef): ParentCandidate {
  return {
    name: g.name,
    waypointFor: g.waypointFor ?? null,
    listeners: [...new Set(g.listenerNames ?? [])],
    namedPorts: [],
    ports: [],
  }
}

function serviceCandidate(s: K8sService): ParentCandidate {
  const ports = s.ports ?? []
  // 只有**有名字**的端口能作为 sectionName（spec 里 Service 父引用的 sectionName = 端口名）
  const named = ports.filter((p) => (p.name ?? '').trim())
  return {
    name: s.name,
    waypointFor: null,
    listeners: [],
    namedPorts: named.map((p) => ({
      name: p.name!.trim(),
      // 与 ServiceMonitorEditorView 的端口下拉同一展示格式
      label: `${p.name!.trim()}（${p.port ?? '?'}/${p.protocol ?? 'TCP'}）`,
    })),
    ports: [...new Set(ports.map((p) => p.port).filter((n): n is number => typeof n === 'number'))],
  }
}

/** Mesh profile 只认 ClusterIP：排除 ExternalName 与 headless（clusterIp=None） */
function isClusterIpService(s: K8sService): boolean {
  return s.type !== 'ExternalName' && s.clusterIp !== 'None'
}

export function useParentCandidates() {
  /** 读：未加载过 / 未建模 kind → 空桶（不触发请求） */
  function bucket(kind: string, namespace: string): CandidateBucket {
    return buckets.get(keyOf(kind, namespace)) ?? EMPTY
  }

  /** 触发加载：同一个 (kind, 命名空间) 在 TTL 内只发一次；未建模 kind 与空命名空间直接返回 */
  function ensure(kind: string, namespace: string): void {
    if (!namespace) return
    if (kind !== KIND_GATEWAY && kind !== KIND_SERVICE) return
    const key = keyOf(kind, namespace)
    const at = fetchedAt.get(key)
    if (at != null && Date.now() - at < TTL_MILLIS) return // 命中且未过期
    const known = buckets.get(key)
    if (known?.loading) return // 正在拉取，别叠请求

    if (known) {
      known.loading = true // 过期重拉：保留旧列表（SWR），只翻 loading
    } else {
      buckets.set(key, { list: [], loading: true, failed: false })
    }
    // 必须从 map 里取回来再用（reactive Map 的 get 返回响应式代理）：
    // 直接改 set 进去的那个裸对象不保证模板收到通知
    const b = buckets.get(key)!
    fetchedAt.set(key, Date.now())
    const ctx = { tenantId: state.tenantId!, clusterId: state.clusterId!, namespace }
    const onFail = () => {
      b.failed = true
      fetchedAt.delete(key) // 失败不留 TTL：下一次 ensure 立刻重试（与改造前"每次进页面都重拉"一致）
    }
    if (kind === KIND_SERVICE) {
      serviceApi
        .list(ctx)
        .then((items) => {
          b.list = items.filter(isClusterIpService).map(serviceCandidate)
          b.failed = false
        })
        .catch(onFail)
        .finally(() => {
          b.loading = false
        })
    } else {
      // namespace 参数是**父引用指向**的命名空间；路由自己那个（边界）在 state.namespace 上。
      // 两者不能混：混了就等于拿"自己有分配行的 ns"当通行证去读任意命名空间。
      const routeNs = state.namespace
      if (!routeNs) {
        b.loading = false
        return
      }
      meshApi
        .parentGateways({
          tenantId: state.tenantId ?? undefined,
          clusterId: state.clusterId!,
          namespace: routeNs,
          targetNamespace: namespace,
        })
        .then((items) => {
          b.list = items.map(gatewayCandidate)
          b.failed = false
        })
        .catch(onFail)
        .finally(() => {
          b.loading = false
        })
    }
  }

  return { bucket, ensure }
}
