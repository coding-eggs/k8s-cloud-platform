/**
 * 服务期望配置表单的共用小工具（服务配置页与灰度对话框共用）。
 *
 * 配置文件软校验，与后端 AppServiceDesired.normalizeConfigFiles 同一套规则（权威校验在后端）：
 * 路径形状与字符集、长度、重复 / 嵌套、与持久化挂载路径冲突、引用只能是 FILE_REF_FIELDS。
 * 服务配置页与灰度对话框共用（AppConfigFilesEditor 显示、调用方据此禁用提交）。
 */
import { FILE_REF_FIELDS } from './dependencyUtil'
import type { AppConfigFile as ConfigFile, AppServiceDesired } from '@/types'

const PATH_SEGMENT = /^[A-Za-z0-9._@+=,-]+$/
const FILE_REF = /\$\{([^.${}]+)\.([^${}]+)\}/g

/** 持久化数据卷挂载点：只有「有端口 + 开持久化」才有，默认 /data（同后端 dataMountPath） */
export function dataMountOf(d: AppServiceDesired): string | null {
  if (!d.port || !d.persistenceEnabled) return null
  const p = (d.mountPath ?? '').trim() || '/data'
  return p.length > 1 && p.endsWith('/') ? p.slice(0, -1) : p
}

function fileProblem(list: ConfigFile[], i: number, mount: string | null): string {
  const f = list[i]
  if (!f) return ''
  const path = (f.path ?? '').trim()
  if (!path) return f.content ? '请填写路径' : ''
  if (!path.startsWith('/')) return '路径必须以 / 开头'
  if (path.length > 255) return '路径过长（最多 255 个字符）'
  if (path.endsWith('/')) return '路径要指向文件，不能以 / 结尾'
  if (path.split('/').slice(1).some((seg) => seg === '' || seg === '.' || seg === '..' || !PATH_SEGMENT.test(seg))) {
    return '路径只允许字母、数字和 . _ - @ + = ,，不能有空段、. 或 ..'
  }
  const others = list.filter((_, j) => j !== i).map((o) => (o.path ?? '').trim()).filter(Boolean)
  if (others.includes(path)) return '路径重复'
  const nested = others.find((o) => path.startsWith(`${o}/`) || o.startsWith(`${path}/`))
  if (nested) return `与 ${nested} 互相嵌套`
  if (mount && (path === mount || path.startsWith(`${mount}/`) || mount.startsWith(`${path}/`))) {
    return `与持久化挂载路径 ${mount} 冲突`
  }
  for (const m of (f.content ?? '').matchAll(FILE_REF)) {
    if (!FILE_REF_FIELDS.includes(m[2] ?? '')) {
      return `${m[0]} 不能用在配置文件里：内容会明文写进 ConfigMap，只能引用 ${FILE_REF_FIELDS.join(' / ')}；凭据请用环境变量`
    }
  }
  return ''
}

/** 每个文件的问题（空串 = 没问题），下标与 list 对齐 */
export function configFileProblems(list: ConfigFile[] | null | undefined, mount: string | null): string[] {
  const l = list ?? []
  return l.map((_, i) => fileProblem(l, i, mount))
}

/** 提交用：路径去首尾空白，路径和内容都空的新行丢掉 */
export function cleanConfigFiles(list: ConfigFile[] | null | undefined): ConfigFile[] {
  return (list ?? [])
    .map((f) => ({ path: (f.path ?? '').trim(), content: f.content ?? '' }))
    .filter((f) => f.path || f.content)
}

/**
 * 命令 / 参数：用户新加了没填的空项在提交时丢掉；原本（base 里）就有的空串按个数原样保留。就地改 d。
 */
export function cleanCommandLists(d: AppServiceDesired, base: AppServiceDesired | null | undefined): void {
  for (const k of ['command', 'args'] as const) {
    const list = d[k]
    if (!Array.isArray(list)) continue
    const orig = base?.[k]
    let allowed = Array.isArray(orig) ? orig.filter((x) => x === '').length : 0
    d[k] = list.filter((x) => x !== '' || allowed-- > 0)
  }
}
