/**
 * 租户列表排序：**default 租户置顶**，其余保持原有相对顺序。
 *
 * <h2>为什么用 id/name 判「default 租户」</h2>
 * 平台没有 isDefault 之类的标记 —— `platform_tenant` 只有 id / name / status / 三个时间戳，
 * 迁移里也没有 seed 任何租户。所以约定：**id 或 name 等于 `default`（大小写不敏感、忽略首尾空白）
 * 即视为 default 租户**。两者任一命中都算，避免"id 是 default 但显示名不同"（或反之）被漏掉。
 *
 * <h2>在哪几处生效</h2>
 * 三处都是「用户要挑一个租户」的场景，default 排第一才有意义的是这些：
 * <ul>
 *   <li>顶栏上下文级联（{@code stores/context.ts} 的租户树）—— 顺带决定 {@code ensureDefaults()}
 *       的默认落点（它取树里的第一个），即"进资源页默认就用 default 租户"</li>
 *   <li>租户切换器（{@code components/TenantSwitcher.vue}）</li>
 * </ul>
 * **租户管理页（TenantView）有意不套这个顺序**：那是一张管理表格、按后端返回序展示，
 * 管理员可以用它上面的筛选/排序；在那里把 default 提到最前只会让表格顺序变得不直观。
 */

/** 判定用的标记值 */
const DEFAULT_TENANT_MARKER = 'default'

/** 两种租户来源的公共形状：`/context` 给 tenantId+name，`/tenant/list` 与 `/user/my-tenants` 给 id+name */
export interface TenantLike {
  /** ResourceContextTenant 的字段 */
  tenantId?: string | null
  /** PlatformTenant 的字段 */
  id?: string | null
  name?: string | null
}

/** 是否 default 租户（id 或 name 命中；null/undefined 一律 false） */
export function isDefaultTenant(t: TenantLike | null | undefined): boolean {
  if (!t) return false
  return [t.tenantId, t.id, t.name].some(
    (v) => typeof v === 'string' && v.trim().toLowerCase() === DEFAULT_TENANT_MARKER,
  )
}

/**
 * default 置顶的稳定排序；**不改原数组**。
 * <p>其余元素保持原有相对顺序 —— `Array.prototype.sort` 自 ES2019 起在规范层面保证稳定，
 * 故此处的比较器对非 default 之间一律返回 0，等价于"原序不动"。
 */
export function sortTenantsDefaultFirst<T extends TenantLike>(list: readonly T[] | null | undefined): T[] {
  const arr = [...(list ?? [])]
  return arr.sort((a, b) => Number(isDefaultTenant(b)) - Number(isDefaultTenant(a)))
}
