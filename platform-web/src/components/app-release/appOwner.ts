/** 应用图表给其创建的对象打的标签；值 = 应用 id（即 `/applications/:id` 的参数） */
export const APP_LABEL = 'platform.coding/app'

export function appIdOf(labels?: Record<string, string> | null): string {
  return labels?.[APP_LABEL] ?? ''
}
