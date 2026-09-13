import { reactive, watch } from 'vue'
import { configMapApi, namespaceApi, pvcApi, secretApi, storageClassApi } from '@/api'
import { useResourceContext } from '@/stores/context'

/**
 * 工作负载编辑器的「资源下拉选项」共享单例：按当前上下文（租户/集群/命名空间）拉取
 * ConfigMap / Secret / PVC / StorageClass / 命名空间 的名称列表，供各子编辑器 el-select 复用。
 * <p>
 * 模块级单例 + 单一 watch：所有编辑器共享同一份数据、只加载一次；上下文变化自动重载。
 * 各下拉一律 filterable + allow-create → 选项缺失 / 自定义值仍可手填，不破坏已有配置的回显。
 */
const { state, ready } = useResourceContext()

export const resourceOptions = reactive({
  loading: false,
  configMaps: [] as string[],
  secrets: [] as string[],
  pvcs: [] as string[],
  storageClasses: [] as string[],
  namespaces: [] as string[],
})

let loadedKey = ''

/** 取名称列表；单个资源拉取失败不阻塞其它（下拉仍 allow-create 手填） */
function names<T extends { name?: string | null }>(p: Promise<T[]>): Promise<string[]> {
  return p.then((arr) => arr.map((x) => x.name ?? '').filter(Boolean)).catch(() => [] as string[])
}

async function reload(): Promise<void> {
  if (!ready.value) return
  const key = `${state.tenantId}|${state.clusterId}|${state.namespace}`
  if (key === loadedKey) return
  resourceOptions.loading = true
  try {
    const ctx3 = { tenantId: state.tenantId!, clusterId: state.clusterId!, namespace: state.namespace! }
    const ctx2 = { tenantId: state.tenantId!, clusterId: state.clusterId! }
    const [cms, secs, pvcs, scs, nss] = await Promise.all([
      names(configMapApi.list(ctx3)),
      names(secretApi.list(ctx3)),
      names(pvcApi.list(ctx3)),
      names(storageClassApi.list(ctx2)),
      names(namespaceApi.list(state.clusterId!)),
    ])
    // 上下文在加载期间已切换 → 丢弃过期结果，等下一次 watch 触发
    if (key !== `${state.tenantId}|${state.clusterId}|${state.namespace}`) return
    resourceOptions.configMaps = cms
    resourceOptions.secrets = secs
    resourceOptions.pvcs = pvcs
    resourceOptions.storageClasses = scs
    resourceOptions.namespaces = nss
    loadedKey = key
  } finally {
    resourceOptions.loading = false
  }
}

let started = false
export function useResourceOptions() {
  if (!started) {
    started = true
    watch(
      [() => ready.value, () => state.tenantId, () => state.clusterId, () => state.namespace],
      () => { void reload() },
      { immediate: true },
    )
  }
  return { options: resourceOptions, reload }
}
