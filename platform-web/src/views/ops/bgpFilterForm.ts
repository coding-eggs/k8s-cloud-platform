import type { BgpFilterOperation, BgpFilterRule } from '@/types'

/** 编辑器内的操作行（判别联合 → kind + 单一 value；提交时转回 BgpFilterOperation） */
export interface OpRow {
  kind: 'addCommunity' | 'prependAsPath' | 'setPriority'
  value: string
}

/** 编辑器内的规则行：字符串字段归一为非空（'' = 不限），chip 类给数组，另挂本地操作行 */
export interface RuleRow {
  cidr: string
  prefixLengthMin: number | null
  prefixLengthMax: number | null
  source: string
  iface: string
  matchOperator: string
  peerType: string
  communityValues: string[]
  asPathPrefix: string[]
  priority: number | null
  action: string
  opsRows: OpRow[]
}

export function opsToRows(ops?: BgpFilterOperation[] | null): OpRow[] {
  return (ops ?? []).map<OpRow>((op) => {
    if (op.addCommunity != null) return { kind: 'addCommunity', value: op.addCommunity }
    if (op.prependAsPath?.length) return { kind: 'prependAsPath', value: op.prependAsPath.join(',') }
    if (op.setPriority != null) return { kind: 'setPriority', value: String(op.setPriority) }
    return { kind: 'addCommunity', value: '' }
  })
}

export function rowsToOps(rows: OpRow[]): BgpFilterOperation[] | null {
  const out: BgpFilterOperation[] = []
  for (const r of rows) {
    const v = r.value.trim()
    if (!v) continue
    if (r.kind === 'addCommunity') {
      out.push({ addCommunity: v })
    } else if (r.kind === 'prependAsPath') {
      const prefix = v.split(',').map((s) => s.trim()).filter(Boolean)
      if (prefix.length) out.push({ prependAsPath: prefix })
    } else {
      const n = Number(v)
      if (Number.isFinite(n)) out.push({ setPriority: n })
    }
  }
  return out.length ? out : null
}

export function ruleToDto(row: RuleRow): BgpFilterRule {
  return {
    cidr: row.cidr.trim() || null,
    prefixLengthMin: row.prefixLengthMin ?? null,
    prefixLengthMax: row.prefixLengthMax ?? null,
    source: row.source || null,
    iface: row.iface.trim() || null,
    matchOperator: row.matchOperator || null,
    peerType: row.peerType || null,
    communityValues: row.communityValues.length ? row.communityValues : null,
    asPathPrefix: row.asPathPrefix.length ? row.asPathPrefix : null,
    priority: row.priority ?? null,
    action: row.action || null,
    operations: rowsToOps(row.opsRows),
  }
}

export function dtoToRule(d: BgpFilterRule): RuleRow {
  return {
    cidr: d.cidr ?? '',
    prefixLengthMin: d.prefixLengthMin ?? null,
    prefixLengthMax: d.prefixLengthMax ?? null,
    source: d.source ?? '',
    iface: d.iface ?? '',
    matchOperator: d.matchOperator ?? '',
    peerType: d.peerType ?? '',
    communityValues: d.communityValues ?? [],
    asPathPrefix: d.asPathPrefix ?? [],
    priority: d.priority ?? null,
    action: d.action ?? 'Accept',
    opsRows: opsToRows(d.operations),
  }
}

/** 新建空规则（action 默认 Accept；chip 类字段给空数组便于 el-select 绑定） */
export function emptyRule(): RuleRow {
  return {
    cidr: '', prefixLengthMin: null, prefixLengthMax: null, source: '', iface: '',
    matchOperator: '', peerType: '', communityValues: [], asPathPrefix: [],
    priority: null, action: 'Accept', opsRows: [],
  }
}
