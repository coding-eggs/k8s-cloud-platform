import { computed, reactive } from 'vue'
import { nodeApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'
import type { NodeAddress } from '@/types'

/**
 * 节点地址目录（模块级单例，按集群缓存）：工作负载列表页「点 nodePort 直开」用它拼 host。
 *
 * <p><b>权限位短路</b>：`/nodes/addresses` 的码是 `tenant:cluster:capability:view`（租户可读的集群只读快照，
 * 与 `/cluster/capability/get` 同码）。无该权限时不发请求、候选为空 —— 列表页上 nodePort 退化为纯文本，
 * 与 `useMeshStatus` / `useClusterCapability` 同一套约定（见 docs/development/frontend-permission-conventions.md）。
 *
 * <p>这份数据是**窄投影**（只回 name + 内网/外网 IP + Ready）：它回答的只是"哪台机器能连"，
 * 容量、标签、污点那些集群拓扑不在这里，也不该为了一个跳转链接下发。
 *
 * <p>失败静默降级（不阻断列表渲染）：拿不到节点地址只是"少一个快捷入口"，不是"列表不可用"。
 */
const perm = usePermission()

const state = reactive({
  clusterId: null as string | null,
  loaded: false,
  loading: false,
  nodes: [] as NodeAddress[],
})

/** 一个节点可用的直连 host：外网 IP 优先（跨网可达），否则内网 IP；都没有则 null（不可直连） */
export function nodeHost(n: NodeAddress | null | undefined): string | null {
  if (!n) return null
  return n.externalIp || n.internalIp || null
}

export function useNodeAddresses() {
  const nodes = computed(() => state.nodes)
  const loading = computed(() => state.loading)
  /** 该用户能不能拿节点地址（不能 => 页面上不显示直连链接） */
  const canUse = computed(() => perm.has(apiCodes.clusterCapabilityView))

  /**
   * 默认直连 host：第一个 **Ready 且有 IP** 的节点（externalIp 优先，回退 internalIp）；
   * 没有 Ready 的就退到第一个有 IP 的节点（集群全 NotReady 时用户仍可自己试）。
   */
  const defaultHost = computed(() => {
    const usable = state.nodes.filter((n) => nodeHost(n))
    return nodeHost(usable.find((n) => n.ready) ?? usable[0])
  })

  /** 拉取当前集群的节点地址；同集群已加载则跳过（force 强制刷新）。无权限 / 无 clusterId → 清空。 */
  async function load(clusterId: string | null, force = false): Promise<void> {
    if (!clusterId || !perm.has(apiCodes.clusterCapabilityView)) {
      state.clusterId = null
      state.nodes = []
      state.loaded = false
      return
    }
    if (!force && state.loaded && state.clusterId === clusterId) return
    state.loading = true
    try {
      state.nodes = await nodeApi.addresses(clusterId)
      state.clusterId = clusterId
      state.loaded = true
    } catch {
      state.nodes = []   // 拉取失败不阻断列表，只是没有直连链接
    } finally {
      state.loading = false
    }
  }

  // reactive() 包裹：模板里 `addr.defaultHost` 等能自动解包 ref（普通对象嵌套的 ref 在模板中不会自动解包）
  return reactive({ nodes, loading, canUse, defaultHost, load })
}
