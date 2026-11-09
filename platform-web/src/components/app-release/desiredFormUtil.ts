/**
 * 服务期望配置表单的共用小工具（服务配置页与灰度对话框共用）。
 *
 * 配置文件软校验，与后端 AppServiceDesired.normalizeConfigFiles 同一套规则（权威校验在后端）：
 * 路径形状与字符集、长度、重复 / 嵌套、与持久化挂载路径冲突、引用只能是 FILE_REF_FIELDS。
 * 服务配置页与灰度对话框共用（AppConfigFilesEditor 显示、调用方据此禁用提交）。
 */
import { FILE_REF_FIELDS } from './dependencyUtil'
import type { AppConfigFile as ConfigFile, AppServiceDesired } from '@/types'
import type { VolumeDef } from '@/types/workload'

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

// ---------------------------------------------------------------------------
// 调度与挂载（nodeSelector / tolerations / affinity / volumes + volumeMounts）
// 形状与工作负载页一致（types/workload.ts）；软校验，权威校验在后端 AppServiceDesired
// ---------------------------------------------------------------------------

const DNS_LABEL = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const DNS_SUBDOMAIN = /^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$/
export const TOLERATION_EFFECTS = ['', 'NoSchedule', 'PreferNoSchedule', 'NoExecute']
export const HOST_PATH_TYPES = ['', 'Directory', 'DirectoryOrCreate', 'File', 'FileOrCreate', 'Socket', 'CharDevice', 'BlockDevice']
const VOLUME_TYPES = ['emptyDir', 'configMap', 'secret', 'persistentVolumeClaim', 'hostPath', 'projected', 'downwardAPI', 'csi', 'nfs']

function overlaps(a: string, b: string): boolean {
  return a === b || a.startsWith(`${b}/`) || b.startsWith(`${a}/`)
}

/** 节点选择的每行问题（空串 = 没问题） */
export function nodeSelectorProblems(d: AppServiceDesired): string[] {
  return Object.keys(d.nodeSelector ?? {}).map((k) => {
    if (!k.trim()) return '标签名不能为空'
    if (/\s/.test(k)) return `标签名不能含空白：${k}`
    return ''
  })
}

/** 容忍的每行问题（空串 = 没问题），下标与列表对齐 */
export function tolerationProblems(d: AppServiceDesired): string[] {
  return (d.tolerations ?? []).map((t) => {
    const operator = t.operator ?? 'Equal'
    if (operator === 'Equal' && !(t.key ?? '').trim()) return 'operator=Equal 时必须填 key'
    if (operator === 'Exists' && (t.value ?? '').trim()) return 'operator=Exists 时不能填 value'
    if (!TOLERATION_EFFECTS.includes(t.effect ?? '')) return 'effect 只能是 NoSchedule / PreferNoSchedule / NoExecute'
    if (t.tolerationSeconds != null && (t.effect ?? '') !== 'NoExecute') return '容忍时间只能用于 NoExecute'
    return ''
  })
}

function itemsProblem(items?: { key?: string | null; path?: string | null }[] | null): string {
  for (const it of items ?? []) {
    if (!(it.key ?? '').trim() || !(it.path ?? '').trim()) return 'items 需要同时填 key 与 path'
  }
  return ''
}

function volumeProblem(v: VolumeDef, i: number, list: VolumeDef[], isAdmin: boolean): string {
  const name = (v.name ?? '').trim()
  if (!name) return '请填写卷名'
  if (!DNS_LABEL.test(name) || name.length > 63) return '卷名需为小写字母 / 数字 / 连字符（至多 63 位）'
  if (list.some((o, j) => j !== i && (o.name ?? '').trim() === name)) return '卷名重复'
  if (!VOLUME_TYPES.includes(v.type)) return '请选择卷类型'
  if (v.type === 'hostPath' && !isAdmin) return 'hostPath 只允许平台管理员使用'
  switch (v.type) {
    case 'emptyDir': {
      const size = v.emptyDir?.sizeLimit
      return size != null && size < 0 ? '大小上限不能为负' : ''
    }
    case 'configMap':
    case 'secret': {
      const n = ((v.type === 'configMap' ? v.configMap?.name : v.secret?.secretName) ?? '').trim()
      if (!n) return `请填写 ${v.type === 'configMap' ? 'ConfigMap' : 'Secret'} 名称`
      if (!DNS_SUBDOMAIN.test(n)) return '名称不合法（小写字母、数字、. -）'
      return itemsProblem(v.type === 'configMap' ? v.configMap?.items : v.secret?.items)
    }
    case 'persistentVolumeClaim': {
      const c = (v.persistentVolumeClaim?.claimName ?? '').trim()
      if (!c) return '请填写 PVC 名称'
      return DNS_SUBDOMAIN.test(c) ? '' : 'PVC 名称不合法（小写字母、数字、. -）'
    }
    case 'hostPath': {
      const p = (v.hostPath?.path ?? '').trim()
      if (!p.startsWith('/')) return '节点路径必须是绝对路径'
      return HOST_PATH_TYPES.includes(v.hostPath?.type ?? '') ? '' : 'hostPath 类型不支持'
    }
    case 'projected':
      return (v.projected?.sources ?? []).length ? '' : 'projected 至少需要一个投影来源'
    case 'downwardAPI':
      return (v.downwardAPI?.items ?? []).length ? '' : 'downwardAPI 至少需要一个文件项'
    case 'csi':
      return (v.csi?.driver ?? '').trim() ? '' : '请填写 CSI 驱动名'
    case 'nfs':
      return (v.nfs?.server ?? '').trim() && (v.nfs?.path ?? '').trim() ? '' : 'NFS 服务器与共享路径都不能为空'
    default:
      return ''
  }
}

/** 卷定义的每行问题（空串 = 没问题），下标与列表对齐 */
export function volumeProblems(d: AppServiceDesired, isAdmin: boolean): string[] {
  const list = d.volumes ?? []
  return list.map((v, i) => volumeProblem(v, i, list, isAdmin))
}

/** 挂载点的每行问题（空串 = 没问题）：引用的卷必须存在，路径不能互相覆盖或遮住持久化卷 / 配置文件 */
export function mountProblems(d: AppServiceDesired): string[] {
  const mounts = d.volumeMounts ?? []
  const names = (d.volumes ?? []).map((v) => (v.name ?? '').trim())
  const mount = dataMountOf(d)
  const files = (d.configFiles ?? []).map((f) => (f.path ?? '').trim()).filter(Boolean)
  const paths: string[] = []
  return mounts.map((m) => {
    const name = (m.name ?? '').trim()
    if (!name || !names.includes(name)) return '挂载点引用了不存在的卷'
    const path = (m.mountPath ?? '').trim()
    if (!path) return '请填写挂载路径'
    if (!path.startsWith('/') || path.length > 255) return '挂载路径必须是绝对路径'
    if (path.split('/').slice(1).some((seg) => seg === '' || seg === '.' || seg === '..' || !PATH_SEGMENT.test(seg))) {
      return '挂载路径只能包含字母、数字和 . _ - @ + = ,'
    }
    if (paths.some((o) => overlaps(path, o))) return '挂载路径互相覆盖'
    paths.push(path)
    if (mount && overlaps(path, mount)) return `与持久化挂载路径 ${mount} 冲突`
    const file = files.find((f) => overlaps(path, f))
    if (file) return `与配置文件 ${file} 冲突`
    const subPath = (m.subPath ?? '').trim()
    if (subPath && (subPath.startsWith('/') || subPath.split('/').some((s) => s === '' || s === '.' || s === '..'))) {
      return 'subPath 必须是相对卷根的路径'
    }
    return ''
  })
}

/** 提交用：去掉空行与空白；空对象 / 空列表不提交（否则会变成待发布改动） */
export function cleanScheduling(d: AppServiceDesired): void {
  const nodeSelector: Record<string, string> = {}
  for (const [k, v] of Object.entries(d.nodeSelector ?? {})) {
    if (k.trim()) nodeSelector[k.trim()] = String(v ?? '').trim()
  }
  if (Object.keys(nodeSelector).length) d.nodeSelector = nodeSelector
  else delete d.nodeSelector

  // 空 key + operator=Exists 是合法且有意义的一行（容忍所有污点），不能当空行丢掉 ——
  // 丢了会让「把 Equal 改成 Exists」这种改动既不脏也发不出去（保存按钮一直灰）。
  const tolerations = (d.tolerations ?? []).filter((t) => (t.key ?? '').trim() || (t.value ?? '').trim()
    || (t.effect ?? '') || t.tolerationSeconds != null || (t.operator ?? 'Equal') === 'Exists')
  if (tolerations.length) d.tolerations = tolerations
  else delete d.tolerations

  const volumes = (d.volumes ?? []).filter((v) => (v.name ?? '').trim())
  if (volumes.length) d.volumes = volumes
  else delete d.volumes

  const mounts = (d.volumeMounts ?? []).filter((m) => (m.name ?? '').trim() || (m.mountPath ?? '').trim())
  if (mounts.length) d.volumeMounts = mounts
  else delete d.volumeMounts

  const affinity = d.affinity
  if (!affinity || (!affinity.nodeAffinity && !affinity.podAffinity && !affinity.podAntiAffinity)) delete d.affinity
}
