/** 极简 YAML 序列化：只覆盖 group_vars 需要的标量 / 数组 / 嵌套对象。 */

export type YamlScalar = string | number | boolean
export type YamlValue = YamlScalar | YamlScalar[] | { [key: string]: YamlValue }
export type YamlDoc = Partial<Record<string, YamlValue>>

function scalar(v: YamlScalar): string {
  return typeof v === 'string' ? JSON.stringify(v) : String(v)
}

function renderEntry(key: string, value: YamlValue, indent: number): string[] {
  const pad = ' '.repeat(indent)
  if (Array.isArray(value)) {
    const lines = [`${pad}${key}:`]
    for (const item of value) lines.push(`${pad}  - ${scalar(item)}`)
    return lines
  }
  if (typeof value === 'object') {
    const lines = [`${pad}${key}:`]
    for (const [k, v] of Object.entries(value)) {
      if (v === undefined) continue
      lines.push(...renderEntry(k, v as YamlValue, indent + 2))
    }
    return lines
  }
  return [`${pad}${key}: ${scalar(value)}`]
}

export function yamlDoc(doc: YamlDoc): string {
  const lines: string[] = []
  for (const [k, v] of Object.entries(doc)) {
    if (v === undefined) continue
    lines.push(...renderEntry(k, v as YamlValue, 0))
  }
  return lines.join('\n') + '\n'
}
