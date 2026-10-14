import { registryApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import type { RegistrySuggestItem } from '@/types'
import { usePermission } from '@/stores/permission'

/**
 * 镜像输入框的候选补全（S3.1 / Docker Hub 增补）。两处用到：`WorkloadView` 的「更新镜像」弹窗、
 * `ContainerEditor` 的容器镜像框。
 *
 * <p>四条规矩，缺一不可：
 * <ol>
 *   <li><b>无权限时不发请求</b> —— 与 frontend-permission-conventions §4 的「可选拉取短路」同一条；</li>
 *   <li><b>失败静默</b> —— 返回空候选，不弹错。补全是每按键一次的高频动作，弹错会掩盖真正的问题，
 *       也会让用户以为「填不了镜像」（spec §5.3）；</li>
 *   <li><b>选中即整段替换</b> —— 后端给的候选已经是完整镜像串，前端不做任何拼接
 *       （拼接规则是方言相关的，见 `RegistryDialect` 的 javadoc）；</li>
 *   <li><b>候选带来源连接名</b> —— 无 host 的输入会同时问多条连接，同一行右侧要标出它来自哪条。
 *       后端已按连接名排好序，前端**不再排**。</li>
 * </ol>
 */
export function useImageSuggest() {
  const perm = usePermission()

  function fetchImageSuggestions(
    query: string,
    cb: (items: RegistrySuggestItem[]) => void,
  ): void {
    const q = (query ?? '').trim()
    if (!q || !perm.has(apiCodes.registryCatalog)) {
      cb([])
      return
    }
    registryApi
      .suggest(q)
      .then((r) => cb(r.items ?? []))
      .catch(() => cb([])) // 静默降级：候选为空不是错误态
  }

  return { fetchImageSuggestions }
}
