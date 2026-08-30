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
