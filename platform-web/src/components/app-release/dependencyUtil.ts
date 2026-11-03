/**
 * 依赖相关的纯函数与常量：key 校验、可引用字段、状态文案、使用方反查、「从依赖导入」的默认变量名。
 * 规则与后端一致（AppServiceConfigService / AppDependencyService 的 KEY、RESERVED_SUFFIXES、HOST、INDEX_PATH；
 * AppEnvResolver 的字段集合）。前端只做软校验，权威校验在后端。
 */
import type { AppDependencyRequest, AppDependencyRow, AppServiceDesired, AppServiceRow } from '@/types'
import type { Tone } from './releaseUtil'

export const KEY_PATTERN = /^[a-z]([a-z0-9-]{0,18}[a-z0-9])?$/
export const RESERVED_SUFFIXES = ['-env', '-secret', '-headless', '-files']
export const HOST_PATTERN = /^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$/
export const INDEX_PATH_PATTERN = /^\/[A-Za-z0-9._~:,*+@=%/-]*$/
export const ENV_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/

/** 依赖 / 服务共用的名称规则；返回空串 = 合法 */
export function keyProblem(key: string): string {
  if (!key) return ''
  if (!KEY_PATTERN.test(key)) return '至多 20 位，小写字母、数字、连字符，字母开头，不以连字符结尾'
  const bad = RESERVED_SUFFIXES.find((s) => key.endsWith(s))
  return bad ? `不能以 ${bad} 结尾（平台保留）` : ''
}

export const EXTERNAL_PROTOCOLS = [
  'mysql', 'postgresql', 'redis', 'kafka', 'rabbitmq', 'elasticsearch', 'nacos', 'milvus',
]

/** 外部连接表单（AppExternalForm）的值；空串 = 未填 */
export interface ExternalForm {
  protocol: string
  host: string
  port: number | undefined
  secretName: string
  usernameKey: string
  passwordKey: string
}

export function emptyExternal(): ExternalForm {
  return { protocol: 'mysql', host: '', port: undefined, secretName: '', usernameKey: '', passwordKey: '' }
}

/** 已登记的外部连接 → 表单初值（修改时回填） */
export function externalForm(e?: AppDependencyRow['external']): ExternalForm {
  const s = (v: unknown): string => (typeof v === 'string' ? v : '')
  return {
    protocol: s(e?.protocol) || 'mysql',
    host: s(e?.host),
    port: typeof e?.port === 'number' ? e.port : undefined,
    secretName: s(e?.secretName),
    usernameKey: s(e?.usernameKey),
    passwordKey: s(e?.passwordKey),
  }
}

/** 表单 → 接口的 external；空字段不带（后端白名单校验，多余字段会被拒） */
export function externalPayload(e: ExternalForm): NonNullable<AppDependencyRequest['external']> {
  const out: NonNullable<AppDependencyRequest['external']> = { protocol: e.protocol, host: e.host.trim() }
  if (e.port) out.port = e.port
  if (e.secretName.trim()) out.secretName = e.secretName.trim()
  if (e.usernameKey.trim()) out.usernameKey = e.usernameKey.trim()
  if (e.passwordKey.trim()) out.passwordKey = e.passwordKey.trim()
  return out
}

/** 支持初始化脚本的中间件（与后端 AppInitSupport 同一份） */
export const INIT_CODES = ['mysql', 'postgresql', 'redis', 'elasticsearch', 'kafka']

/** 创建依赖时不开放的中间件：它们的创建需要后端依赖请求里没有的参数（Milvus 的对象存储来源） */
export const CREATE_EXCLUDED = ['milvus']

export const DEP_FIELD_LABEL: Record<string, string> = {
  host: '主机',
  port: '端口',
  address: '地址（主机:端口）',
  url: 'URL',
  username: '用户名（Secret 引用）',
  password: '密码（Secret 引用）',
}
export const SVC_FIELD_LABEL: Record<string, string> = {
  host: '主机',
  port: '端口',
  url: 'URL',
}
export const SVC_FIELDS = Object.keys(SVC_FIELD_LABEL)

/**
 * 各中间件有哪些凭据字段，镜像后端 `MiddlewareService.connInfoOf`（platform-api，约 526-561 行）的
 * `.secret(name, usernameKey, passwordKey)`：键为 null 的字段，`AppEnvResolver` 会判为「没有该字段」。
 * connInfoOf 改了这里要一起改。host / port / address / url 对所有中间件都有。
 * redis、elasticsearch：只有密码；kafka、nacos：没有凭据；其余：用户名 + 密码。
 */
export const MIDDLEWARE_CREDENTIALS: Record<string, { username: boolean; password: boolean }> = {
  mysql: { username: true, password: true },
  postgresql: { username: true, password: true },
  rabbitmq: { username: true, password: true },
  milvus: { username: true, password: true },
  redis: { username: false, password: true },
  elasticsearch: { username: false, password: true },
  kafka: { username: false, password: false },
  nacos: { username: false, password: false },
}

/**
 * 依赖可引用的字段。EXTERNAL 看登记信息有没有端口 / 用户名 / 密码键；
 * 中间件看实例类型（`dep.code`）；类型未知时只给无凭据的四个字段，宁缺毋滥。
 */
export function depFields(dep: AppDependencyRow): string[] {
  const all = Object.keys(DEP_FIELD_LABEL)
  if (dep.type !== 'EXTERNAL') {
    const cred = MIDDLEWARE_CREDENTIALS[dep.code ?? '']
    return all.filter((f) => (f === 'username' ? !!cred?.username : f === 'password' ? !!cred?.password : true))
  }
  const e = dep.external
  return all.filter((f) => {
    if (f === 'port') return e?.port != null
    if (f === 'username') return !!e?.secretName && !!e?.usernameKey
    if (f === 'password') return !!e?.secretName && !!e?.passwordKey
    return true
  })
}

export function refToken(key: string, field: string): string {
  return `\${${key}.${field}}`
}

export interface RefGroup { label: string; items: { token: string; field: string; hint: string }[] }

/** 可引用字段，按「依赖 / 服务」分组（「插入引用」菜单与值输入框的下拉共用） */
export function refGroups(dependencies?: AppDependencyRow[], serviceKeys?: string[]): RefGroup[] {
  const out: RefGroup[] = []
  for (const d of dependencies ?? []) {
    out.push({
      label: `依赖 ${d.key}`,
      items: depFields(d).map((f) => ({ token: refToken(d.key, f), field: f, hint: DEP_FIELD_LABEL[f] ?? f })),
    })
  }
  for (const k of serviceKeys ?? []) {
    out.push({
      label: `服务 ${k}`,
      items: SVC_FIELDS.map((f) => ({ token: refToken(k, f), field: f, hint: SVC_FIELD_LABEL[f] ?? f })),
    })
  }
  return out
}

export const DEP_STATUS: Record<string, { text: string; type: Tone }> = {
  READY: { text: '运行正常', type: 'success' },
  CREATING: { text: '创建中', type: 'warning' },
  DEGRADED: { text: '降级', type: 'warning' },
  FAILED: { text: '异常', type: 'danger' },
  DELETING: { text: '删除中', type: 'info' },
  EXTERNAL: { text: '外部连接', type: 'neutral' },
  NO_INSTANCE: { text: '无实例', type: 'danger' },
  UNKNOWN: { text: '状态未知', type: 'neutral' },
}
export function depStatus(d: AppDependencyRow): { text: string; type: Tone } {
  return DEP_STATUS[d.status ?? ''] ?? { text: d.status || '—', type: 'neutral' }
}

export const INIT_STATUS: Record<string, { text: string; type: Tone }> = {
  RUNNING: { text: '执行中', type: 'warning' },
  SUCCESS: { text: '已成功', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
}

export function ownershipText(d: { type: string; ownership?: string | null }): string {
  if (d.type === 'EXTERNAL') return '外部连接'
  if (d.ownership === 'CREATED') return '应用创建'
  if (d.ownership === 'BOUND') return '绑定已有实例'
  return '—'
}

/** 与后端 AppEnvResolver.REF 同一写法：${key.field} */
export const REF = /\$\{([^.${}]+)\.([^${}]+)\}/g

/** 期望配置引用到的依赖 / 服务 key：环境变量（SECRET 行除外）+ 配置文件内容。与后端 AppServiceDesired.references 一致 */
export function refsOf(desired?: AppServiceDesired | null): string[] {
  const out = new Set<string>()
  const scan = (text?: string | null) => {
    for (const m of (text ?? '').matchAll(REF)) out.add(m[1] as string)
  }
  for (const e of desired?.env ?? []) if (e.kind !== 'SECRET') scan(e.value)
  for (const f of desired?.configFiles ?? []) scan(f.content)
  return [...out]
}

/** 引用了这个依赖的服务（来自期望配置；待删除的服务不算，与后端移除校验一致） */
export function usersOf(services: AppServiceRow[], depKey: string): string[] {
  return services
    .filter((s) => s.pending !== 'DELETED' && refsOf(s.desired).includes(depKey))
    .map((s) => s.key)
}

/** 配置文件内容可引用的字段（会明文进 ConfigMap，凭据不行）。与后端 AppServiceDesired.FILE_REF_FIELDS 一致 */
export const FILE_REF_FIELDS = ['host', 'port', 'address', 'url']

/** 依赖 key → 环境变量名前缀：order-db → ORDER_DB */
export function envPrefix(key: string): string {
  return key.toUpperCase().replace(/-/g, '_')
}
