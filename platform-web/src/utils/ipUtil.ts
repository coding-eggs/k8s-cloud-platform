/**
 * 轻量 IPv4/IPv6 计算（BigInt）——供「保留 IP」picker 预填 / 越界校验用。
 * <p>只覆盖本平台需要的最小集：IP→BigInt、CIDR→[start,end]、某 IP 是否落在 CIDR 内。
 * 不做子网运算/格式美化（保留段起止 IP 的字符串由后端 blockIps 返回，无需 BigInt→IP 反格式化）。
 */

export interface IpCidrRange { start: bigint; end: bigint }

/** IPv4 点分 / IPv6（支持 :: 压缩）→ BigInt；无法解析返回 null。不支持 zone index(%eth0)。 */
export function ipToBigInt(ip: string): bigint | null {
  const s = (ip ?? '').trim()
  if (!s) return null
  if (s.includes('.') && !s.includes(':')) return ipv4ToBigInt(s)
  if (s.includes(':')) return ipv6ToBigInt(s.toLowerCase())
  return null
}

/** 是否为合法 IP（v4/v6）。 */
export function isIp(s: string): boolean {
  return ipToBigInt(s) != null
}

function ipv4ToBigInt(s: string): bigint | null {
  const parts = s.split('.')
  if (parts.length !== 4) return null
  let v = 0n
  for (const p of parts) {
    if (!/^\d{1,3}$/.test(p)) return null
    const n = Number(p)
    if (n > 255) return null
    v = (v << 8n) | BigInt(n)
  }
  return v
}

function ipv6ToBigInt(s: string): bigint | null {
  if (s.includes('%')) return null // zone index 不支持
  const halves = s.split('::')
  if (halves.length > 2) return null
  let groups: string[]
  if (halves.length === 2) {
    const left = halves[0] ? halves[0].split(':') : []
    const right = halves[1] ? halves[1].split(':') : []
    if (left.length + right.length > 7) return null
    groups = [...left, ...Array(8 - left.length - right.length).fill('0'), ...right]
  } else {
    groups = s.split(':')
    if (groups.length !== 8) return null
  }
  let v = 0n
  for (const g of groups) {
    if (!/^[0-9a-f]{1,4}$/.test(g)) return null
    v = (v << 16n) | BigInt('0x' + g)
  }
  return v
}

/** CIDR（或裸 IP → /32、/128）→ [start,end]；无法解析返回 null。 */
export function cidrToRange(cidr: string): IpCidrRange | null {
  const s = (cidr ?? '').trim()
  if (!s) return null
  const slash = s.indexOf('/')
  if (slash < 0) {
    const v = ipToBigInt(s)
    if (v == null) return null
    return { start: v, end: v }
  }
  const baseStr = s.slice(0, slash)
  const prefixStr = s.slice(slash + 1)
  if (!/^\d{1,3}$/.test(prefixStr)) return null
  const base = ipToBigInt(baseStr)
  if (base == null) return null
  const totalBits = baseStr.includes('.') ? 32 : 128
  const prefix = Number(prefixStr)
  if (prefix > totalBits) return null
  const hostBits = BigInt(totalBits - prefix)
  const allOnes = (1n << BigInt(totalBits)) - 1n
  const mask = allOnes ^ ((1n << hostBits) - 1n)
  const network = base & mask
  const size = 1n << hostBits
  return { start: network, end: network + size - 1n }
}

/** 某 IP 是否落在 CIDR 范围内（含端点）。任一无法解析 → false。 */
export function containsInCidr(cidr: string, ip: string): boolean {
  const r = cidrToRange(cidr)
  const v = ipToBigInt(ip)
  if (!r || v == null) return false
  return v >= r.start && v <= r.end
}

/** BigInt → IP 字符串（v4 点分 / v6 带 :: 压缩） */
export function bigIntToIp(v: bigint, isV6: boolean): string {
  if (!isV6) {
    return [(v >> 24n) & 0xffn, (v >> 16n) & 0xffn, (v >> 8n) & 0xffn, v & 0xffn]
      .map((b) => b.toString()).join('.')
  }
  const groups: number[] = []
  for (let i = 7; i >= 0; i--) groups.push(Number((v >> BigInt(i * 16)) & 0xffffn))
  // 最长连续零段压缩为 ::（取最先出现的最长段；无 ≥2 的零段则不压缩）
  let bestStart = -1, bestLen = 1, curStart = -1, curLen = 0
  for (let i = 0; i < 8; i++) {
    if (groups[i] === 0) {
      if (curLen === 0) curStart = i
      curLen++
      if (curLen > bestLen) { bestStart = curStart; bestLen = curLen }
    } else {
      curLen = 0
    }
  }
  if (bestStart < 0) return groups.map((g) => g.toString(16)).join(':')
  const left = groups.slice(0, bestStart).map((g) => g.toString(16))
  const right = groups.slice(bestStart + bestLen).map((g) => g.toString(16))
  return `${left.join(':')}::${right.join(':')}`
}

/**
 * CIDR → IP 列表（含端点）。超过 cap 截断并标记 truncated（下拉候选用，防大段爆炸）。
 * 无法解析返回 null。
 */
export function expandCidrToIps(cidr: string, cap: number): { ips: string[]; truncated: boolean } | null {
  const r = cidrToRange(cidr)
  if (!r) return null
  const isV6 = (cidr ?? '').trim().split('/')[0]?.includes(':') ?? false
  const size = r.end - r.start + 1n
  const n = size > BigInt(cap) ? cap : Number(size)
  const ips: string[] = []
  for (let i = 0; i < n; i++) ips.push(bigIntToIp(r.start + BigInt(i), isV6))
  return { ips, truncated: size > BigInt(cap) }
}
