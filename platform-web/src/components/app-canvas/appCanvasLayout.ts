import type { AppNodeKind } from '@/types'

/** 画布布局计算（纯函数）：把「谁使用谁」画成从左往右的依赖分层。 */

export interface CanvasNode {
  key: string
  kind: AppNodeKind
}

export interface CanvasEdge {
  from: string
  to: string
}

export interface Point {
  x: number
  y: number
}

/**
 * 卡片固定尺寸。AppNodeCard 的 CSS 必须与这里一致，
 * 否则同一列的左边缘会对不齐（这正是上一版不齐的原因：卡片是 min-width）。
 */
export const NODE_WIDTH = 280
export const NODE_HEIGHT = 72

/** 列间距、行间距；行间距取 24 让行距 96 能被 16 的吸附网格整除 */
const COL_GAP = 96
const ROW_GAP = 24
const ORIGIN_X = 48
const ORIGIN_Y = 48

/**
 * 分层：边的方向是「使用者 → 被使用者」（api → db），所以**被依赖的一方在左**。
 * 节点的层级 = 它依赖的最长链条长度，没有出边的（中间件 / 外部服务）为 0。
 */
export function layeredLayout(nodes: CanvasNode[], edges: CanvasEdge[]): Record<string, Point> {
  if (!nodes.length) return {}

  const present = new Set(nodes.map((n) => n.key))
  const outEdges = new Map<string, string[]>()
  for (const e of edges) {
    if (e.from === e.to || !present.has(e.from) || !present.has(e.to)) continue
    const list = outEdges.get(e.from) ?? []
    if (!list.includes(e.to)) list.push(e.to)
    outEdges.set(e.from, list)
  }

  const level = levels(nodes.map((n) => n.key), outEdges)

  const columns = new Map<number, CanvasNode[]>()
  for (const n of nodes) {
    const col = level.get(n.key) ?? 0
    const list = columns.get(col) ?? []
    list.push(n)
    columns.set(col, list)
  }

  const tallest = Math.max(1, ...Array.from(columns.values(), (list) => list.length))
  const tallestBox = tallest * NODE_HEIGHT + (tallest - 1) * ROW_GAP

  const out: Record<string, Point> = {}
  for (const [col, list] of columns) {
    // 列内顺序沿用节点在 spec 里的顺序：加节点只往后排，不会把已有节点重新洗一遍
    const box = list.length * NODE_HEIGHT + (list.length - 1) * ROW_GAP
    // 每列在自己的高度里垂直居中，短列不会顶在最上面
    const top = ORIGIN_Y + (tallestBox - box) / 2
    list.forEach((n, i) => {
      out[n.key] = { x: ORIGIN_X + col * (NODE_WIDTH + COL_GAP), y: top + i * (ROW_GAP + NODE_HEIGHT) }
    })
  }
  return out
}

function levels(keys: string[], outEdges: Map<string, string[]>): Map<string, number> {
  const memo = new Map<string, number>()
  const onPath = new Set<string>()
  const levelOf = (key: string): number => {
    const cached = memo.get(key)
    if (cached !== undefined) return cached
    // 环在保存时就被 AppSpecValidator 拒了；这里只是防止图画坏了把页面递归炸掉
    if (onPath.has(key)) return 0
    onPath.add(key)
    let level = 0
    for (const next of outEdges.get(key) ?? []) {
      level = Math.max(level, levelOf(next) + 1)
    }
    onPath.delete(key)
    memo.set(key, level)
    return level
  }
  for (const key of keys) levelOf(key)
  return memo
}
