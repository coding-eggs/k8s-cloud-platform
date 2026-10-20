import { reactive } from 'vue'

/**
 * 路由后端（backendRefs）的两个下拉：Service 名 + 命名空间。
 *
 * <p>规则（2026-10-20 用户要求）：
 * <ul>
 *   <li><b>命名空间只能选</b>：候选 = 当前租户在该集群分配到的命名空间（父页面从资源上下文传入），
 *       不允许手输 —— 手输一个不属于本租户的 ns 只会写出一个永远不生效/越权的引用；</li>
 *   <li><b>Service 名支持下拉</b>：按行内命名空间懒加载（首次展开下拉时查一次，结果按 ns 缓存）；
 *       加载失败不阻断填写，仍可手输（filterable + allow-create）。</li>
 * </ul>
 *
 * <p>行内 {@code namespace} 为空表示「本命名空间」—— 候选从 {@code currentNamespace} 解析。
 */
export interface BackendServiceProps {
  /** 当前租户在本集群的命名空间（只能从这里选） */
  namespaceOptions?: string[]
  /** 当前上下文命名空间（行内留空时用） */
  currentNamespace?: string
  /** 按命名空间列 Service 名；失败返回空数组即可 */
  loadServices?: (namespace: string) => Promise<string[]>
}

export function useBackendServiceOptions(props: BackendServiceProps) {
  /** 每个命名空间查过一次就缓存（失败也缓存空，避免每次展开都打一次集群） */
  const services = reactive<Record<string, string[]>>({})
  const loading = reactive<Record<string, boolean>>({})

  /** 行内生效的命名空间：空 = 当前上下文命名空间 */
  function effectiveNamespace(namespace: string): string {
    return (namespace || '').trim() || (props.currentNamespace ?? '')
  }

  function servicesOf(namespace: string): string[] {
    return services[effectiveNamespace(namespace)] ?? []
  }

  function isLoading(namespace: string): boolean {
    return !!loading[effectiveNamespace(namespace)]
  }

  async function ensureServices(namespace: string): Promise<void> {
    const ns = effectiveNamespace(namespace)
    if (!ns || services[ns] !== undefined || loading[ns] || !props.loadServices) return
    loading[ns] = true
    try {
      services[ns] = (await props.loadServices(ns)) ?? []
    } catch {
      services[ns] = []
    } finally {
      loading[ns] = false
    }
  }

  /** 换命名空间时清掉已选 Service：同名 Service 不一定存在于新 ns */
  function onNamespaceChange(row: { name: string }): void {
    row.name = ''
  }

  return { services, loading, effectiveNamespace, servicesOf, isLoading, ensureServices, onNamespaceChange }
}
