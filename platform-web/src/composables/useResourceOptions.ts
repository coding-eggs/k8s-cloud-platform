import { reactive, watch } from 'vue'
import { configMapApi, namespaceApi, pvcApi, secretApi, storageClassApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'

/**
 * 工作负载编辑器的「资源下拉选项」共享单例：按当前上下文（租户/集群/命名空间）拉取
 * ConfigMap / Secret / PVC / StorageClass / 命名空间 的名称列表，供各子编辑器 el-select 复用。
 * <p>
 * 模块级单例 + 单一 watch：所有编辑器共享同一份数据、只加载一次；上下文变化自动重载。
 * 各下拉一律 filterable + allow-create → 选项缺失 / 自定义值仍可手填，不破坏已有配置的回显。
 *
 * <p><b>每类候选按权限位短路</b>（见 apiCodes / docs/development/frontend-permission-conventions.md）：
 * 这些是"锦上添花"的候选值，租户角色不一定持有对应 list 权限（例如租户成员没有
 * platform:allocation:list，拿不到命名空间清单；V2026_10_07_3 起存储类更是平台管理员专属）。
 * 不短路的话，租户用户一进工作负载编辑器就会连吃几个 403 弹窗——而每个下拉本身都能手填，
 * 弹窗纯属噪音。短路 = 不发注定被拒的请求。
 */
const { state, ready } = useResourceContext()
const perm = usePermission()

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

/** 无权限时的占位：不发请求，直接给空候选 */
const NONE = Promise.resolve([] as string[])

async function reload(): Promise<void> {
  if (!ready.value) return
  const key = `${state.tenantId}|${state.clusterId}|${state.namespace}`
  if (key === loadedKey) return
  resourceOptions.loading = true
  try {
    const ctx3 = { tenantId: state.tenantId!, clusterId: state.clusterId!, namespace: state.namespace! }
    const ctx2 = { tenantId: state.tenantId!, clusterId: state.clusterId! }
    const [cms, secs, pvcs, scs, nss] = await Promise.all([
      perm.has(apiCodes.configmapList) ? names(configMapApi.list(ctx3)) : NONE,
      perm.has(apiCodes.secretList) ? names(secretApi.list(ctx3)) : NONE,
      perm.has(apiCodes.pvcList) ? names(pvcApi.list(ctx3)) : NONE,
      // 存储类：集群级、无命名空间维度，仅平台管理员可读（V2026_10_07_3）
      perm.has(apiCodes.clusterManage) ? names(storageClassApi.list(ctx2)) : NONE,
      // 命名空间清单属平台分配面；租户上下文里工作负载只能落在当前 ns，本就不需要这份候选
      perm.has(apiCodes.allocationList) ? names(namespaceApi.list(state.clusterId!)) : NONE,
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
