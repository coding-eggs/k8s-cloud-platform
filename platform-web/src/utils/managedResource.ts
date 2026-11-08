/**
 * 受管资源（平台组件 / 中间件实例创建的资源）的展示与提示。
 *
 * <p>判定在 k8s-server（`ManagedResourceGuard`）完成，接口返回 `managedBy`：
 * `COMPONENT` / `MIDDLEWARE` / null。前端只负责置灰与提示 —— 真正的拒绝在后端。
 */
export type ManagedBy = 'COMPONENT' | 'MIDDLEWARE' | null | undefined

export function managedByOf(row: unknown): ManagedBy {
  if (row && typeof row === 'object' && 'managedBy' in row) {
    return (row as { managedBy?: ManagedBy }).managedBy ?? null
  }
  return null
}

export function isManaged(row: unknown): boolean {
  return managedByOf(row) !== null
}

/** 行内提示文案（菜单里的禁用项 / tooltip） */
export function managedReason(row: unknown): string {
  if (managedByOf(row) !== 'MIDDLEWARE') return '由平台组件管理，请到「集群组件」页操作'
  return isMiddlewareExpose(row)
    ? '由中间件的「对外访问」生成，只能在中间件实例详情里取消'
    : '由中间件实例管理，请到「中间件」页操作'
}

/** 中间件对外暴露生成的路由 / NodePort Service（k8s-server 打的标签） */
export function isMiddlewareExpose(row: unknown): boolean {
  const labels = (row as { labels?: Record<string, string> | null } | null)?.labels
  return labels?.['platform.coding/managed-by'] === 'middleware-expose'
}
