/**
 * 应用所在命名空间的 Secret 候选（名 + 键），给环境变量的 Secret 行和外部依赖的 Secret 登记用。
 *
 * - 只留名、类型、键名：列表接口会带回明文值，取完立刻丢掉，不放进任何响应式状态。
 * - 没有 tenant:secret:list 就不发请求（候选是锦上添花，下拉都允许手填）。
 * - 失败只置空，不阻塞编辑。
 */
import { ref, type Ref } from 'vue'
import { secretApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'

export interface SecretCtx { tenantId?: string; clusterId: string; namespace: string }
export interface SecretOption { name: string; type: string; keys: string[] }

export function useNamespaceSecrets(ctx: Ref<SecretCtx>) {
  const perm = usePermission()
  const options = ref<SecretOption[]>([])
  const loading = ref(false)
  const canList = (): boolean => perm.has(apiCodes.secretList)

  async function reload(): Promise<void> {
    const c = ctx.value
    if (!c.clusterId || !c.namespace || !canList()) {
      options.value = []
      return
    }
    const key = `${c.tenantId}|${c.clusterId}|${c.namespace}`
    loading.value = true
    try {
      const list = await secretApi.list({ tenantId: c.tenantId ?? '', clusterId: c.clusterId, namespace: c.namespace })
      const cur = ctx.value
      if (key !== `${cur.tenantId}|${cur.clusterId}|${cur.namespace}`) return
      options.value = (list ?? [])
        // 镜像拉取凭据、SA token 不是给应用读的
        .filter((s) => s.type !== 'kubernetes.io/dockerconfigjson' && s.type !== 'kubernetes.io/dockercfg'
          && s.type !== 'kubernetes.io/service-account-token')
        .map((s) => ({ name: s.name, type: s.type, keys: Object.keys(s.data ?? {}).sort() }))
        .sort((a, b) => a.name.localeCompare(b.name))
    } catch {
      options.value = []
    } finally {
      loading.value = false
    }
  }

  function keysOf(name?: string | null): string[] {
    return options.value.find((o) => o.name === name)?.keys ?? []
  }

  return { options, loading, reload, keysOf, canList }
}
