import { computed, reactive } from 'vue'
import { nodeApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'
import type { K8sNode } from '@/types'

/**
 * 节点目录（模块级单例）：为工作负载编辑页「调度策略」提供真实节点的下拉数据源。
 * nodeName / nodeSelector / 节点亲和 的下拉都从这里取，避免各组件重复拉取。
 *
 * <p><b>权限位短路</b>：`/nodes/list` 的码是 `platform:cluster:manage`（节点是集群管理面），
 * 而本 store 的消费者（工作负载编辑器）是租户可达页。无该权限时不发请求、候选为空，
 * 各下拉退化为纯自由输入（它们的 `allow-create` 本来就是这个设计）——
 * 与 `useResourceOptions` / `useClusterCapability` 同一套约定。
 *
 * <p>⚠️ 若日后要让租户也能拿到节点候选（调度策略是真实需求），正确做法是新增**窄投影只读端点**
 * （只返回 name + labels），不要直接对租户开放 `/nodes/list`（它回带容量/可分配/IP 等
 * 集群拓扑信息），也不要靠前端门控"借" `platform:cluster:manage`。
 * 见 docs/development/frontend-permission-conventions.md §5。
 */
const state = reactive({
  clusterId: null as string | null,
  loaded: false,
  loading: false,
  nodes: [] as K8sNode[],
})

const perm = usePermission()

/** 全部节点 label key（去重、排序）——供 nodeSelector / 亲和 matchExpression 的 key 下拉 */
function distinctLabelKeys(nodes: K8sNode[]): string[] {
  const s = new Set<string>()
  for (const n of nodes) for (const k of Object.keys(n.labels ?? {})) s.add(k)
  return [...s].sort()
}

/** 某 label key 在所有节点上的取值（去重、排序）——供 value 下拉 */
function distinctLabelValues(nodes: K8sNode[], key: string): string[] {
  const s = new Set<string>()
  for (const n of nodes) {
    const v = n.labels?.[key]
    if (v != null && v !== '') s.add(v)
  }
  return [...s].sort()
}

/** matchFields 常用的节点字段 key（allow-create 仍可自由输入其它字段） */
export const NODE_FIELD_KEYS = [
  'metadata.name',
  'kubernetes.io/hostname',
  'kubernetes.io/arch',
  'kubernetes.io/os',
  'kubernetes.io/kernel',
]

/** matchFields 中「取值即节点名」的字段（其 value 下拉给节点名候选） */
const FIELD_IS_NODE_NAME = new Set(['metadata.name', 'kubernetes.io/hostname'])

export function useNodeCatalog() {
  const nodes = computed(() => state.nodes)
  const loading = computed(() => state.loading)
  /** 节点名列表——nodeName 下拉、matchField(hostname/name) value 下拉 */
  const nodeNames = computed(() => state.nodes.map((n) => n.name))
  /** 全部节点 label key——nodeSelector / matchExpression 的 key 下拉 */
  const labelKeyOptions = computed(() => distinctLabelKeys(state.nodes))
  /** matchFields 的字段 key 候选（固定常用集，可自由输入扩展） */
  const fieldKeyOptions = computed(() => NODE_FIELD_KEYS)

  /** 某 label key 的取值候选——nodeSelector / matchExpression 的 value 下拉 */
  function labelValueOptions(key: string): string[] {
    return key ? distinctLabelValues(state.nodes, key) : []
  }
  /** matchField 的 value 候选：节点名类字段返回节点名，其余为空（自由输入） */
  function fieldValueOptions(key: string): string[] {
    return FIELD_IS_NODE_NAME.has(key) ? nodeNames.value : []
  }

  /** 拉取当前集群节点；同集群已加载则跳过（force 强制刷新）。clusterId 为空 / 无节点读权限 → 清空。 */
  async function load(clusterId: string | null, force = false): Promise<void> {
    if (!clusterId || !perm.has(apiCodes.clusterManage)) {
      state.clusterId = null
      state.nodes = []
      state.loaded = false
      return
    }
    if (!force && state.loaded && state.clusterId === clusterId) return
    state.loading = true
    try {
      state.nodes = await nodeApi.list({ clusterId })
      state.clusterId = clusterId
      state.loaded = true
    } catch {
      state.nodes = []   // 拉取失败不阻断编辑，下拉退化为纯自由输入
    } finally {
      state.loading = false
    }
  }

  // reactive() 包裹：模板里 `cat.nodeNames` / `cat.loading` 等能自动解包 ref（普通对象嵌套的 ref 在模板中不会自动解包，会拿到 ComputedRef 本体 → v-for 迭代到内部 ReactiveEffect/函数）。函数属性不受影响。
  return reactive({ nodes, loading, nodeNames, labelKeyOptions, fieldKeyOptions, labelValueOptions, fieldValueOptions, load })
}
