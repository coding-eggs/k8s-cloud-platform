/**
 * 配额/限制范围表单的单位换算：显示单位 ⇄ 基础单位（cpu=核、memory=字节）。
 * 线路与后端一律基础单位（BigDecimal→JSON number），换算只发生在 UI 边界，与
 * components/workload/ResourcesEditor.vue 的固定单位输入范式同构（cpu→m、memory→Mi、计数→原值）。
 * 展示格式化用 utils/quantity.ts 的 formatQuantity/formatBytes —— 本文件不做格式化、不解析后缀字符串。
 * 所有函数对 null / '' / NaN 一律返回 null（= 该项不约束）。
 */

/** 核 → 毫核（输入框显示值） */
export function coresToMilli(v: number | null | undefined): number | null {
  return v == null || Number.isNaN(v) ? null : Math.round(v * 1000)
}

/** 毫核 → 核（提交值）；先剥非数字字符再判空（剥后为空 = 未填/垃圾 → null，不可落 0 造出「上限 0」配额） */
export function milliToCores(v: number | string | null | undefined): number | null {
  const s = String(v ?? '').replace(/\D/g, '')
  return s === '' ? null : Number(s) / 1000
}

/** 字节 → MiB（显示值，取整） */
export function bytesToMi(v: number | null | undefined): number | null {
  return v == null || Number.isNaN(v) ? null : Math.round(v / 2 ** 20)
}

/** MiB → 字节（提交值）；同 milliToCores：剥后判空 */
export function miToBytes(v: number | string | null | undefined): number | null {
  const s = String(v ?? '').replace(/\D/g, '')
  return s === '' ? null : Number(s) * 2 ** 20
}

/** 计数：整数或 null（''/null/NaN/非数字 → null；合法 0 保留） */
export function intOrNull(v: number | string | null | undefined): number | null {
  const s = String(v ?? '').trim()
  if (s === '') return null
  const n = Number(s)
  return Number.isFinite(n) ? Math.trunc(n) : null
}
