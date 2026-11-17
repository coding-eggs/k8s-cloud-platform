/** 极简 YAML 序列化：只覆盖 group_vars 需要的标量 / 数组 / 嵌套对象。 */

export type YamlScalar = string | number | boolean
export type YamlValue = YamlScalar | YamlValue[] | { [key: string]: YamlValue }
export type YamlDoc = Partial<Record<string, YamlValue>>

function scalar(v: YamlScalar): string {
  return typeof v === 'string' ? JSON.stringify(v) : String(v)
}

/** 列表项：标量写成 `- x`，对象写成 `- key: v` + 其后各键缩进对齐（kubespray 的 mirrors 就是这种） */
function renderItem(item: YamlValue, indent: number): string[] {
  const pad = ' '.repeat(indent)
  if (item !== null && typeof item === 'object' && !Array.isArray(item)) {
    const inner = Object.entries(item).flatMap(([k, v]) => renderEntry(k, v as YamlValue, indent + 4))
    const first = inner[0]
    if (!first) return [`${pad}  - {}`]
    // 第一行去掉自己那层缩进，接到 "- " 后面
    return [`${pad}  - ${first.slice(indent + 4)}`, ...inner.slice(1)]
  }
  return [`${pad}  - ${scalar(item as YamlScalar)}`]
}

function renderEntry(key: string, value: YamlValue, indent: number): string[] {
  const pad = ' '.repeat(indent)
  if (Array.isArray(value)) {
    const lines = [`${pad}${key}:`]
    for (const item of value) lines.push(...renderItem(item, indent))
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

export function yamlDoc<T extends object>(doc: T): string {
  const lines: string[] = []
  for (const [k, v] of Object.entries(doc)) {
    if (v === undefined) continue
    lines.push(...renderEntry(k, v as YamlValue, 0))
  }
  return lines.join('\n') + '\n'
}
