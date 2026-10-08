import { computed, reactive } from 'vue'
import { resourceContextApi } from '@/api'
import type { ResourceContextCluster, ResourceContextTenant } from '@/types'

/**
 * 资源管理全局上下文（顶栏 chip）：租户 → 集群 → 命名空间。
 * 模块级单例，所有资源页共享；选择持久化到 localStorage，记住上次操作位置。
 */
const STORAGE_KEY = 'platform_resource_context'

interface StoredContext {
  tenantId?: string | null
  clusterId?: string | null
  namespace?: string | null
}

const state = reactive({
  loaded: false,
  tenants: [] as ResourceContextTenant[],
  tenantId: null as string | null,
  clusterId: null as string | null,
  namespace: null as string | null,
})

// 恢复上次选择（load() 后会按级联数据校验并清除失效项）
try {
  const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null') as StoredContext | null
  if (stored) {
    state.tenantId = stored.tenantId ?? null
    state.clusterId = stored.clusterId ?? null
    state.namespace = stored.namespace ?? null
  }
} catch {
  /* 忽略损坏的持久化数据 */
}

function persist(): void {
  const payload: StoredContext = { tenantId: state.tenantId, clusterId: state.clusterId, namespace: state.namespace }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
}

export function useResourceContext() {
  const currentTenant = computed(
    () => state.tenants.find((t) => t.tenantId === state.tenantId) ?? null,
  )
  const clustersOfTenant = computed<ResourceContextCluster[]>(
    () => currentTenant.value?.clusters ?? [],
  )
  const currentCluster = computed(
    () => clustersOfTenant.value.find((c) => c.clusterId === state.clusterId) ?? null,
  )
  const namespacesOfCluster = computed<string[]>(() => currentCluster.value?.namespaces ?? [])
  /** 三级选齐且级联树已加载校验过才可操作资源。
   *  loaded 门槛是防串号关键：localStorage 恢复的旧选择可能在 /context 返回前就让三级齐全，
   *  若此时放行，切租户后的首屏会用「旧租户 + 新帽 token」发请求 → k8s-server TENANT_MISMATCH */
  const ready = computed(() => !!(state.loaded && state.tenantId && state.clusterId && state.namespace))

  async function load(): Promise<void> {
    if (state.loaded) return
    let data: Awaited<ReturnType<typeof resourceContextApi.get>>
    try {
      data = await resourceContextApi.get()
    } catch {
      return // 拉取失败：loaded 保持 false，下次进页面可重试；ready 维持 false，页面停在「请选择」占位
    }
    state.tenants = data?.tenants ?? []
    state.loaded = true

    // 校验持久化选择：任一级失效则逐级清空
    if (!currentTenant.value) {
      state.tenantId = null
      state.clusterId = null
      state.namespace = null
      // 帽态下服务端只返回本租户；若持久化选择是切换前的旧租户被清空，则自动落到唯一租户，
      // 使顶栏级联随切租户自动跟随（否则切完租户树变空选，用户以为没生效）
      const only = state.tenants.length === 1 ? state.tenants[0] : undefined
      if (only) {
        state.tenantId = only.tenantId
      }
    } else if (!currentCluster.value) {
      state.clusterId = null
      state.namespace = null
    } else if (!namespacesOfCluster.value.includes(state.namespace ?? '')) {
      state.namespace = null
    }

    // 缺级默认补到第一个可用节点（见 ensureDefaults）；load 刚完成校验，loaded 必为 true
    ensureDefaults()
  }

  /** 缺级默认补到第一个可用节点（仅树已加载后有效）：进入页面 / 切换租户后无需手动点级联，资源页直接加载首个命名空间的数据。
   *  树里能出现的集群必有 ≥1 个已分配 ns（ResourceContextService 按分配行建节点），故补到集群必能补到 ns。
   *  load() 末尾与 ContextSelector 每次挂载都会调用——选择器每次进资源页重新挂载，等价于每次进页面自动补齐。 */
  function ensureDefaults(): void {
    if (!state.loaded) return
    const tenant = currentTenant.value
    if (tenant && !currentCluster.value) {
      const firstCluster = tenant.clusters[0]
      if (firstCluster) state.clusterId = firstCluster.clusterId
    }
    if (currentCluster.value && !state.namespace) {
      const firstNs = namespacesOfCluster.value[0]
      if (firstNs) state.namespace = firstNs
    }
    persist()
  }

  function setTenant(tenantId: string | null): void {
    state.tenantId = tenantId
    state.clusterId = null
    state.namespace = null
    persist()
  }

  function setCluster(clusterId: string | null): void {
    state.clusterId = clusterId
    state.namespace = null
    persist()
  }

  function setNamespace(namespace: string | null): void {
    state.namespace = namespace
    persist()
  }

  return {
    state,
    currentTenant,
    clustersOfTenant,
    currentCluster,
    namespacesOfCluster,
    ready,
    load,
    ensureDefaults,
    setTenant,
    setCluster,
    setNamespace,
  }
}
