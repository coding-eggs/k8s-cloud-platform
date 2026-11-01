import type { PermissionGroup, PlatformPermission } from '@/types'

/** 与服务端一致的字符串序（码点序，不用 localeCompare —— 后者对符号/大小写的排序与 Java 不同）。 */
function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

/**
 * 把平铺权限行按 (domain, code) 聚成组。
 *
 * <p>顺序与服务端 `/permission/catalog` 相同：组按 domain → code 升序，组内按 resource → action 升序。
 * 角色管理页的可分配集合来自 `/role/permission/assignable`（后端按角色 scope 过滤后仍是平铺行），
 * 用它补齐同一套分组口径；权限点页直接消费服务端已分好的 catalog。
 *
 * <p>domain 为空的脏数据按 API 计（与既有页面的 `p.domain ?? 'API'` 约定一致）。
 */
export function groupPermissions(rows: PlatformPermission[]): PermissionGroup[] {
  const byKey = new Map<string, PermissionGroup>()
  for (const p of rows) {
    const domain = p.domain ?? 'API'
    const key = `${domain}\u0000${p.code}`
    let g = byKey.get(key)
    if (!g) {
      g = { code: p.code, domain, rules: [] }
      byKey.set(key, g)
    }
    g.rules.push({
      id: p.id,
      resource: p.resource ?? null,
      action: p.action ?? null,
      description: p.description ?? null,
    })
  }

  const groups = [...byKey.values()]
  for (const g of groups) {
    g.rules.sort((a, b) => cmp(a.resource ?? '', b.resource ?? '') || cmp(a.action ?? '', b.action ?? ''))
  }
  groups.sort((a, b) => cmp(a.domain ?? '', b.domain ?? '') || cmp(a.code, b.code))
  return groups
}
