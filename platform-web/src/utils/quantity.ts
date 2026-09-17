const SI: Record<string, number> = { m: -3, k: 3, M: 6, G: 9, T: 12, P: 15, E: 18 }
const BIN: Record<string, number> = { Ki: 10, Mi: 20, Gi: 30, Ti: 40, Pi: 50, Ei: 60 }
/**K8s quantity → 基础单位数值；无法解析返回 NaN */
export function parseQuantity(raw?: string | null): number {
  if (raw == null) return NaN
  const s = raw.trim()
  if (!s) return NaN
  let i = 0; let neg = false
  if (s[0] === '-') { neg = true; i++ } else if (s[0] === '+') i++
  const start = i
  while (i < s.length && (/[0-9.]/.test(s.charAt(i)))) i++
  if (i === start) return NaN
  const base = Number(s.slice(start, i))
  if (Number.isNaN(base)) return NaN
  const suffix = s.slice(i).trim()
  let factor = 1
  if (suffix) {
    if (SI[suffix] != null) factor = 10 ** SI[suffix]
    else if (BIN[suffix] != null) factor = 2 ** BIN[suffix]
    else return NaN
  }
  return neg ? -base * factor : base * factor
}

/** 去浮点噪声并去尾零：7.920 → "7.92"，16106127360 → "16106127360" */
function trimNum(n: number): string {
  if (!Number.isFinite(n)) return ''
  return String(Number(n.toPrecision(12)))
}

const BIN_UNITS = ['B', 'Ki', 'Mi', 'Gi', 'Ti', 'Pi']
/** 字节 → 二进制单位字符串（16Gi、512Mi…） */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || Number.isNaN(bytes)) return ''
  let v = Math.abs(bytes); let i = 0
  while (v >= 1024 && i < BIN_UNITS.length - 1) { v /= 1024; i++ }
  const sign = bytes < 0 ? '-' : ''
  return `${sign}${trimNum(v)} ${BIN_UNITS[i]}`
}

/**
 * 基础单位数值 → 展示字符串。
 * kind：'memory'=字节(二进制 Gi/Mi…) / 'cpu'=核数 / 'count'=整数计数（默认）。
 */
export function formatQuantity(value: number | null | undefined, kind: 'cpu' | 'memory' | 'count' = 'count'): string {
  if (value == null || Number.isNaN(value)) return ''
  if (kind === 'memory') return formatBytes(value)
  return trimNum(value)
}
