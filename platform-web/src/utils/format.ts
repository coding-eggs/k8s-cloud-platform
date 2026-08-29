/** 时间格式化：兼容后端 epoch millis（number）与 ISO 字符串 */
export function fmtDate(v?: number | string | null): string {
  if (v === null || v === undefined || v === '') return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
