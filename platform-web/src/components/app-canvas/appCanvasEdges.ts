import type { AppNodeKind } from '@/types'

/** 锚点在卡片的哪一侧 */
export type Side = 'l' | 'r'

export interface EdgeSides {
  /** source（使用者）那端 */
  s: Side
  /** target（被使用的资源）那端 */
  t: Side
}

/** 没有记录时的默认锚点：使用者从左出、资源从右入 —— 正好是自动分层的方向 */
export const DEFAULT_SIDES: EdgeSides = { s: 'l', t: 'r' }

/** 中间件和外部服务只能被使用，不能发起连线 */
export function isProvider(kind: AppNodeKind | undefined): boolean {
  return kind === 'MIDDLEWARE' || kind === 'EXTERNAL'
}

export function sideOf(handle: string | null | undefined, fallback: Side): Side {
  return handle === 'l' || handle === 'r' ? handle : fallback
}

export interface ConnectionInput {
  source: string
  target: string
  sourceHandle?: string | null
  targetHandle?: string | null
}

export type NormalizedConnection =
  | { ok: true; from: string; to: string; sides: EdgeSides }
  | { ok: false; reason: 'missing' | 'self' | 'both-providers' }

/**
 * 把一次拖拽归一成「计算节点 → 资源」的有向边。
 *
 * <p>两端锚点随便拖（左侧/右侧的出口和入口都能起、都能落），线就画在你选的两个锚点上；
 * 从资源那端往服务拖也接受 —— 两端翻过来即可，箭头永远落在被使用的一方，
 * 不会因为拖拽方向不同而变味。
 */
export function normalizeConnection(
  input: ConnectionInput,
  kindOf: (key: string) => AppNodeKind | undefined,
): NormalizedConnection {
  const { source, target } = input
  if (!source || !target) return { ok: false, reason: 'missing' }
  if (source === target) return { ok: false, reason: 'self' }

  const fromProvider = isProvider(kindOf(source))
  const toProvider = isProvider(kindOf(target))
  if (fromProvider && toProvider) return { ok: false, reason: 'both-providers' }

  return {
    ok: true,
    from: fromProvider ? target : source,
    to: fromProvider ? source : target,
    sides: fromProvider
      ? {
        s: sideOf(input.targetHandle, DEFAULT_SIDES.s),
        t: sideOf(input.sourceHandle, DEFAULT_SIDES.t),
      }
      : {
        s: sideOf(input.sourceHandle, DEFAULT_SIDES.s),
        t: sideOf(input.targetHandle, DEFAULT_SIDES.t),
      },
  }
}
