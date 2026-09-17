/** 监控指标前端本地计算：K8s 数量解析（CPU/内存 limit）+ 数值人性化。
 * 百分比由前端控制（D9）：接口只回原始序列，limit 来自 pod spec，% = value / limit。 */

/** CPU limit → 核。后端已回基础单位数字（核）；兼容旧的带单位字符串（"500m"/"2"）。无法解析返回 null。 */
export function parseCpuCores(v?: number | string | null): number | null {
  if (v == null) return null
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  const t = v.trim()
  if (!t) return null
  if (t.endsWith('m')) {
    const n = Number(t.slice(0, -1))
    return Number.isFinite(n) ? n / 1000 : null
  }
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

/** 内存 limit → 字节。后端已回基础单位数字（字节）；兼容旧的带单位字符串（"16Gi"/"512Mi"/纯数字）。 */
export function parseMemBytes(v?: number | string | null): number | null {
  if (v == null) return null
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  const t = v.trim()
  if (!t) return null
  const m = t.match(/^([0-9]*\.?[0-9]+)\s*([A-Za-z]{0,2})$/)
  if (!m) return null
  const n = Number(m[1])
  if (!Number.isFinite(n)) return null
  switch (m[2]) {
    case '': return n
    case 'Ki': return n * 1024
    case 'Mi': return n * 1024 ** 2
    case 'Gi': return n * 1024 ** 3
    case 'Ti': return n * 1024 ** 4
    case 'K': return n * 1000
    case 'M': return n * 1000 ** 2
    case 'G': return n * 1000 ** 3
    case 'T': return n * 1000 ** 4
    default: return null
  }
}

/** 字节 → 人性化（B / KiB / MiB / GiB / TiB），最多 2 位小数。 */
export function humanizeBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '—'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${i === 0 ? Math.round(v) : v.toFixed(2)} ${units[i]}`
}

/** 数值 → 展示文本。percent=true 时忽略 unit 直接给百分比；否则按 unit 人性化。 */
export function formatMetricValue(value: number, unit: string, percent: boolean): string {
  if (percent) return `${value.toFixed(1)}%`
  if (!Number.isFinite(value)) return '—'
  switch (unit) {
    case '核': return `${round2(value)} 核`
    case '字节': return humanizeBytes(value)
    case '字节/秒': return `${humanizeBytes(value)}/s`
    default: return String(round2(value))
  }
}

/** Y 轴刻度紧凑文本（不带单位后缀，tooltip 才带完整单位）。 */
export function formatMetricAxis(value: number, unit: string, percent: boolean): string {
  if (percent) return `${Math.round(value)}%`
  if (!Number.isFinite(value)) return ''
  switch (unit) {
    case '核': return String(round2(value))
    case '字节': return humanizeBytes(value)
    case '字节/秒': return humanizeBytes(value)
    default: return String(value)
  }
}

/** CPU 动态显示单位：整图最大值 <1 核 → 毫核(m, ×1000)；否则 core(×1)。贴合 k8s/Prometheus 习惯。 */
export function cpuDisplayUnit(maxCores: number): { scale: number; suffix: string } {
  return maxCores < 1 ? { scale: 1000, suffix: 'm' } : { scale: 1, suffix: 'core' }
}

/** CPU tooltip 文本。入参是**已按 scale 缩放后的显示值**（core=核数、mcore=毫核数）。 */
export function formatCpuDisplay(v: number, suffix: string): string {
  if (!Number.isFinite(v)) return '—'
  const num = suffix === 'core' ? v.toFixed(2) : trimZeros(v, 1)
  return suffix === 'core' ? `${num} core` : `${num}m`
}

/** CPU Y 轴刻度（带单位词）。入参同 formatCpuDisplay：已缩放的显示值。 */
export function formatCpuAxisDisplay(v: number, suffix: string): string {
  if (!Number.isFinite(v)) return ''
  if (v === 0) return '0'
  const num = suffix === 'core' ? String(round2(v)) : trimZeros(v, 1)
  return suffix === 'core' ? `${num} core` : `${num}m`
}

/** "5.0"→"5"、"4.80"→"4.8"、"300.0"→"300"（仅在有小数点时去尾零）。 */
function trimZeros(n: number, maxDecimals: number): string {
  const s = n.toFixed(maxDecimals)
  return s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '') : s
}

function round2(n: number): number {
  return Math.round(n * 100) / 100
}

/** 容器结构里带 resources.limits 的最小形状（workload ContainerDef / pod PodContainerDetail 均满足）；limits 值为基础单位数字 */
type HasLimits = { resources?: { limits?: Record<string, number | string> } | null }

/** 汇总一组容器的 CPU limit（核）；无任何 limit 返回 null（不显示 % 切换）。 */
export function sumCpuCores(list: HasLimits[]): number | null {
  let total = 0
  let found = false
  for (const c of list) {
    const v = parseCpuCores(c.resources?.limits?.cpu)
    if (v != null) { total += v; found = true }
  }
  return found ? total : null
}

/** 汇总一组容器的内存 limit（字节）；无任何 limit 返回 null。 */
export function sumMemBytes(list: HasLimits[]): number | null {
  let total = 0
  let found = false
  for (const c of list) {
    const v = parseMemBytes(c.resources?.limits?.memory)
    if (v != null) { total += v; found = true }
  }
  return found ? total : null
}
