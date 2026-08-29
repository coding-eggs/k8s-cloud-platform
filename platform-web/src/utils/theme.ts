const STORAGE_KEY = 'platform_theme'

export type ThemeMode = 'dark' | 'light'

/** 当前主题（默认深色） */
export function getTheme(): ThemeMode {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'light' ? 'light' : 'dark'
}

export function applyTheme(mode: ThemeMode): void {
  document.documentElement.classList.toggle('dark', mode === 'dark')
  localStorage.setItem(STORAGE_KEY, mode)
}

/** 应用启动时调用：按持久化偏好设置主题（默认深色） */
export function initTheme(): void {
  applyTheme(getTheme())
}

/** 切换主题，返回切换后的值 */
export function toggleTheme(): ThemeMode {
  const next: ThemeMode = getTheme() === 'dark' ? 'light' : 'dark'
  applyTheme(next)
  return next
}
