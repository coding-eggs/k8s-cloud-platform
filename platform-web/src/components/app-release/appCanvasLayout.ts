import dagre from '@dagrejs/dagre'
import type { AppNodeKind } from '@/types'

/**
 * 拓扑布局（纯函数，dagre 分层布局）：把「谁使用谁」画成从右往左的依赖图。
 *
 * 为什么不再自己按「最长依赖链」分列：每列常常只有一个服务，全落在同一行，
 * 跨列的边（api → mysql）就从中间卡片背后穿过，看上去像一条串。dagre 会给跨列的边
 * 在中间各列放占位点、按交叉最少排每列的上下顺序，并给出每条边的拐点。
 */

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

export interface TopologyLayout {
  /** 节点左上角（Vue Flow 的 position 口径；dagre 给的是中心点，这里已换算） */
  nodes: Record<string, Point>
  /** 边的拐点，键 = `${from}->${to}`；首尾点落在两端卡片的边框上 */
  edges: Record<string, Point[]>
}

/** 卡片固定尺寸。AppNodeCard 的 CSS 必须与这里一致，否则线头对不上卡片边框。 */
export const NODE_WIDTH = 280
export const NODE_HEIGHT = 72

export function edgeId(from: string, to: string): string {
  return `${from}->${to}`
}

/**
 * 方向 RL：边是「使用者 → 被使用者」，被使用的一方在左。
 * ranker 用 longest-path：没有出边的节点（中间件 / 外部服务）全部落在同一列、即最左列，
 * 与旧版「依赖在最左」的读法一致；network-simplex 会把只被一个服务用的中间件拉到它旁边。
 */
export function layeredLayout(nodes: CanvasNode[], edges: CanvasEdge[]): TopologyLayout {
  if (!nodes.length) return { nodes: {}, edges: {} }

  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'RL', ranker: 'longest-path', nodesep: 32, ranksep: 96, edgesep: 16, marginx: 48, marginy: 48 })
  g.setDefaultEdgeLabel(() => ({}))

  // 插入顺序影响同列的初始顺序：沿用传入顺序，结果稳定
  for (const n of nodes) g.setNode(n.key, { width: NODE_WIDTH, height: NODE_HEIGHT })
  const present = new Set(nodes.map((n) => n.key))
  for (const e of edges) {
    if (e.from === e.to || !present.has(e.from) || !present.has(e.to)) continue
    g.setEdge(e.from, e.to)
  }

  dagre.layout(g)

  const out: TopologyLayout = { nodes: {}, edges: {} }
  for (const key of g.nodes()) {
    const n = g.node(key)
    out.nodes[key] = { x: n.x - NODE_WIDTH / 2, y: n.y - NODE_HEIGHT / 2 }
  }
  // dagre 的首尾点落在卡片边框的任意位置（常在顶边 / 底边），且同一张卡的多条线挤在一个点上。
  // 改成：从使用方卡片的左侧出、到被使用方卡片的右侧入，同侧多条线按走向上下错开。
  const raw = g.edges().map((e) => ({ from: e.v, to: e.w, mid: (g.edge(e).points ?? []).slice(1, -1) as Point[] }))
  const centre = (key: string): Point => g.node(key) as Point
  // 线在这一侧的「来去方向」：取离卡片最近的中间点，没有就取对端卡片中心
  const outY = new Map<string, number>()
  const inY = new Map<string, number>()
  for (const e of raw) {
    outY.set(edgeId(e.from, e.to), (e.mid[0] ?? centre(e.to)).y)
    inY.set(edgeId(e.from, e.to), (e.mid[e.mid.length - 1] ?? centre(e.from)).y)
  }
  const outPort = spreadPorts(raw, (e) => e.from, outY)
  const inPort = spreadPorts(raw, (e) => e.to, inY)

  for (const e of raw) {
    const id = edgeId(e.from, e.to)
    const s = centre(e.from)
    const t = centre(e.to)
    const start = { x: s.x - NODE_WIDTH / 2, y: s.y + (outPort.get(id) ?? 0) }
    const end = { x: t.x + NODE_WIDTH / 2, y: t.y + (inPort.get(id) ?? 0) }
    // 两端各加一小段水平引线：线头水平出入，箭头朝向不歪
    out.edges[id] = [start, { x: start.x - LEAD, y: start.y }, ...e.mid, { x: end.x + LEAD, y: end.y }, end]
  }
  return out
}

/** 出入卡片时水平引线的长度 */
const LEAD = 24
/** 同侧相邻两条线的垂直间距上限 */
const PORT_GAP = 10

/**
 * 同一张卡同一侧的多条线：按各自走向（来去方向的 y）排序后，以卡片中线为中心等距错开，
 * 往上走的在上、往下走的在下，避免在卡片边缘交叉。
 */
function spreadPorts<E extends { from: string; to: string }>(
  edges: E[], side: (e: E) => string, towardsY: Map<string, number>,
): Map<string, number> {
  const groups = new Map<string, E[]>()
  for (const e of edges) {
    const list = groups.get(side(e)) ?? []
    list.push(e)
    groups.set(side(e), list)
  }
  const out = new Map<string, number>()
  for (const list of groups.values()) {
    list.sort((a, b) => (towardsY.get(edgeId(a.from, a.to)) ?? 0) - (towardsY.get(edgeId(b.from, b.to)) ?? 0))
    const gap = Math.min(PORT_GAP, (NODE_HEIGHT - 16) / Math.max(1, list.length - 1))
    list.forEach((e, i) => out.set(edgeId(e.from, e.to), (i - (list.length - 1) / 2) * gap))
  }
  return out
}
