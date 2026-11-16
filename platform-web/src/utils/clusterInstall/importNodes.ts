/**
 * 批量导入节点：把从 Excel / WPS / 表格里粘出来的文本解析成节点行。
 *
 * <p>纯函数：不读写 DOM、不发请求。格式：
 * `hostname  IP  [SSH端口]  [SSH用户]  [操作系统]  [角色]`，
 * 分隔符自动认制表符（Excel 粘贴）/ 逗号（CSV）/ 空白；首行如果是表头（含 hostname 与 ip）自动跳过。
 * 角色用 `/ | 、 ;` 分隔；OS 认下拉里的值与常见写法（`Rocky 9`、`rocky9`、`Rocky Linux 9`）。
 */
import { DEFAULT_OS, OS_OPTIONS } from './kubespray.ts'
import type { InstallNode, NodeRole } from './types'

export interface NodeImportOptions {
  sshPort: number
  sshUser: string
  os: string
  roles: NodeRole[]
}

export interface NodeImportIssue {
  /** 1-based 物理行号（表头行也算一行，方便对着原文找） */
  line: number
  message: string
}

export interface NodeImportResult {
  /** 只含解析通过的行；有问题的行进 issues，不静默产出半成品 */
  nodes: InstallNode[]
  issues: NodeImportIssue[]
}

const DEFAULT_OPTIONS: NodeImportOptions = {
  sshPort: 22,
  sshUser: 'root',
  os: DEFAULT_OS,
  roles: ['worker'],
}

const ROLE_ALIASES: Record<string, NodeRole> = {
  'control-plane': 'control-plane',
  controlplane: 'control-plane',
  cp: 'control-plane',
  master: 'control-plane',
  控制面: 'control-plane',
  管理节点: 'control-plane',
  worker: 'worker',
  w: 'worker',
  node: 'worker',
  工作节点: 'worker',
  etcd: 'etcd',
}

/** 归一化：大小写、空白、`.` `_` `-` 都不算区别 */
function norm(s: string): string {
  return s.toLowerCase().replace(/[\s._-]+/g, '')
}

/** OS 值或常见写法 → OS_OPTIONS 的 value；认不出返回 null */
function matchOs(raw: string): string | null {
  const key = norm(raw)
  const bare = key.replace(/linux|stream/g, '')
  for (const o of OS_OPTIONS) {
    if (norm(o.value) === key) return o.value
    if (norm(o.label).replace(/linux|stream/g, '') === bare) return o.value
  }
  return null
}

function parseRoles(raw: string): { roles: NodeRole[]; unknown: string[] } {
  const roles: NodeRole[] = []
  const unknown: string[] = []
  for (const part of raw.split(/[/|、;+]/)) {
    const token = part.trim().toLowerCase()
    if (!token) continue
    const role = ROLE_ALIASES[token]
    if (role) {
      if (!roles.includes(role)) roles.push(role)
    } else {
      unknown.push(part.trim())
    }
  }
  return { roles, unknown }
}

function isIpv4(ip: string): boolean {
  const parts = ip.split('.')
  if (parts.length !== 4) return false
  return parts.every((p) => /^\d{1,3}$/.test(p) && Number(p) <= 255 && String(Number(p)) === p)
}

const HOSTNAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]*$/

function splitLine(line: string, delimiter: string): string[] {
  if (delimiter === 'ws') return line.split(/\s+/)
  return line.split(delimiter).map((s) => s.trim())
}

export function parseNodeRows(text: string, options: Partial<NodeImportOptions> = {}): NodeImportResult {
  const opts: NodeImportOptions = { ...DEFAULT_OPTIONS, ...options }
  const lines = text.split(/\r?\n/)

  // 分隔符按第一条有内容的行判定：Excel 粘贴是制表符，CSV 是逗号
  const firstData = lines.find((l) => l.trim() && !l.trim().startsWith('#'))
  const delimiter = !firstData ? 'ws' : firstData.includes('\t') ? '\t' : firstData.includes(',') ? ',' : 'ws'

  const nodes: InstallNode[] = []
  const issues: NodeImportIssue[] = []
  const seenHost = new Set<string>()
  const seenIp = new Set<string>()
  let headerChecked = false

  lines.forEach((rawLine, index) => {
    const lineNo = index + 1
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) return

    const cells = splitLine(line, delimiter)
    // 表头只可能出现在第一条内容行
    if (!headerChecked) {
      headerChecked = true
      const lower = cells.map((c) => c.toLowerCase())
      if (lower.includes('hostname') && lower.includes('ip')) return
    }

    if (cells.length > 6) {
      issues.push({ line: lineNo, message: `列数过多（最多 6 列）：${cells.length} 列` })
      return
    }

    const [hostnameRaw = '', ipRaw = '', portRaw = '', userRaw = '', osRaw = '', rolesRaw = ''] = cells

    if (!hostnameRaw) {
      issues.push({ line: lineNo, message: 'hostname 不能为空' })
      return
    }
    if (!HOSTNAME_RE.test(hostnameRaw)) {
      issues.push({ line: lineNo, message: `hostname 格式不正确：${hostnameRaw}` })
      return
    }
    if (!isIpv4(ipRaw)) {
      issues.push({ line: lineNo, message: `IP 格式不正确：${ipRaw || '(空)'}` })
      return
    }
    const port = portRaw ? Number(portRaw) : opts.sshPort
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      issues.push({ line: lineNo, message: `SSH 端口不合法：${portRaw}` })
      return
    }
    let os = opts.os
    if (osRaw) {
      const matched = matchOs(osRaw)
      if (!matched) {
        issues.push({ line: lineNo, message: `未知操作系统：${osRaw}` })
        return
      }
      os = matched
    }
    let roles = opts.roles
    if (rolesRaw) {
      const parsed = parseRoles(rolesRaw)
      if (parsed.unknown.length > 0) {
        issues.push({ line: lineNo, message: `未知角色：${parsed.unknown.join('、')}` })
        return
      }
      if (parsed.roles.length > 0) roles = parsed.roles
    }

    if (seenHost.has(hostnameRaw)) {
      issues.push({ line: lineNo, message: `hostname 重复：${hostnameRaw}` })
      return
    }
    if (seenIp.has(ipRaw)) {
      issues.push({ line: lineNo, message: `IP 重复：${ipRaw}` })
      return
    }
    seenHost.add(hostnameRaw)
    seenIp.add(ipRaw)

    nodes.push({
      hostname: hostnameRaw,
      ip: ipRaw,
      sshPort: port,
      sshUser: userRaw || opts.sshUser,
      os,
      roles: [...roles],
    })
  })

  return { nodes, issues }
}
