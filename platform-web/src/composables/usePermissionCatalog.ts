import { permissionApi, roleApi } from '@/api'
import type { PermissionGroup } from '@/types'
import { groupPermissions } from '@/utils/permissionGroups'
import { ref } from 'vue'

/**
 * 权限数据的**跨页面缓存**。
 *
 * <p>迁移原因：权限点页与角色管理页各自全量拉一次（约 268 行），每次切换页面/角色都重来。
 * 目录内容只在权限行增删改时变化，因此做成模块级缓存 + 显式失效：
 * <ul>
 *   <li>catalog：权限点页的分组目录，全局一份；</li>
 *   <li>assignable：角色页的可分配集合，按 **scope** 缓存两份（PLATFORM / TENANT）——
 *       后端按角色 scope 决定可分配范围，同 scope 的不同角色拿到的是同一集合。</li>
 * </ul>
 * 任一页写入后调用 {@link invalidatePermissionCaches}，下一次读取重新拉取。
 */

const catalog = ref<PermissionGroup[] | null>(null)
const catalogLoading = ref(false)
let catalogInflight: Promise<PermissionGroup[]> | null = null

const assignableByScope = new Map<string, PermissionGroup[]>()
const assignableLoading = ref(false)
const assignableInflight = new Map<string, Promise<PermissionGroup[]>>()

export function usePermissionCatalog() {
  async function ensure(): Promise<PermissionGroup[]> {
    if (catalog.value) return catalog.value
    if (!catalogInflight) {
      catalogLoading.value = true
      catalogInflight = permissionApi
        .catalog()
        .then((groups) => {
          catalog.value = groups
          return groups
        })
        .finally(() => {
          catalogLoading.value = false
          catalogInflight = null
        })
    }
    return catalogInflight
  }

  /** 强制重拉（刷新按钮 / 写入之后）。 */
  async function refresh(): Promise<PermissionGroup[]> {
    catalog.value = null
    catalogInflight = null
    return ensure()
  }

  return { groups: catalog, loading: catalogLoading, ensure, refresh }
}

export function useAssignablePermissions() {
  /**
   * @param scope 角色的 scope（PLATFORM / TENANT）；缓存键用它而不是 roleId —— 同 scope 集合相同
   * @param roleId 后端仍按角色判定可分配范围，首次拉取必须带上
   */
  async function ensure(scope: string, roleId: string): Promise<PermissionGroup[]> {
    const key = scope || 'PLATFORM'
    const cached = assignableByScope.get(key)
    if (cached) return cached
    let inflight = assignableInflight.get(key)
    if (!inflight) {
      assignableLoading.value = true
      inflight = roleApi
        .permissionAssignable(roleId)
        .then((rows) => {
          const groups = groupPermissions(rows)
          assignableByScope.set(key, groups)
          return groups
        })
        .finally(() => {
          assignableLoading.value = false
          assignableInflight.delete(key)
        })
      assignableInflight.set(key, inflight)
    }
    return inflight
  }

  return { loading: assignableLoading, ensure }
}

/** 权限行增删改后调用（两个页面共享）：下次读取重新拉取。 */
export function invalidatePermissionCaches(): void {
  catalog.value = null
  catalogInflight = null
  assignableByScope.clear()
  assignableInflight.clear()
}
