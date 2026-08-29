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
  /** 三级选齐才可操作资源 */
  const ready = computed(() => !!(state.tenantId && state.clusterId && state.namespace))

  async function load(): Promise<void> {
    if (state.loaded) return
    const data = await resourceContextApi.get()
    state.tenants = data?.tenants ?? []
    state.loaded = true

    // 校验持久化选择：任一级失效则逐级清空
    if (!currentTenant.value) {
      state.tenantId = null
      state.clusterId = null
      state.namespace = null
    } else if (!currentCluster.value) {
      state.clusterId = null
      state.namespace = null
    } else if (!namespacesOfCluster.value.includes(state.namespace ?? '')) {
      state.namespace = null
    }
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
    setTenant,
    setCluster,
    setNamespace,
  }
}
