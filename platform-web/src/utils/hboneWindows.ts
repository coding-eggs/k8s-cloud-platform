/**
 * 网格 HBONE 隧道的缓冲窗口（Istio 组件「编辑」表单里的两组字段）。
 *
 * values 里存的是**字节数字符串**（chart 把 env 写成 `value: "{{ $val }}"`，数字会被 Helm 渲染成
 * 科学计数法），表单按 MiB 展示。约束与后端 HboneWindows.java 同一套，这里是即时反馈，后端还会再拒一次：
 * - Pod 之间（ztunnel）：请求数 × 单个请求的缓冲上限 < 整条连接的缓冲上限
 * - 入口网关与 waypoint（Envoy）：整条连接的缓冲上限 ≥ 单个请求的 16 倍
 *
 * 背景（2026-11-12 实测）：暂停或被中断的视频请求会占着额度不放，占满后同一条连接上的
 * 其它请求全部卡住 —— movie 看视频一会儿全站不可访问。
 */

export const MIB = 1024 * 1024

export const ISTIOD_STREAM_KEY = 'pilot.env.PILOT_HBONE_INITIAL_STREAM_WINDOW_SIZE'
export const ISTIOD_CONNECTION_KEY = 'pilot.env.PILOT_HBONE_INITIAL_CONNECTION_WINDOW_SIZE'
export const ZTUNNEL_STREAMS_KEY = 'env.POOL_MAX_STREAMS_PER_CONNECTION'
export const ZTUNNEL_STREAM_KEY = 'env.HTTP2_STREAM_WINDOW_SIZE'
export const ZTUNNEL_CONNECTION_KEY = 'env.HTTP2_CONNECTION_WINDOW_SIZE'

/** 窗口上限：HTTP/2 协议规定 2^31-1 字节，按整 MiB 取 2047 */
export const MAX_WINDOW_MIB = 2047
export const MAX_STREAMS = 1000
const ENVOY_MIN_RATIO = 16

/** values 里的字节数（字符串或数字）→ 表单里的 MiB；不是数字 → undefined（输入框显示为空） */
export function bytesToMiB(raw: unknown): number | undefined {
  const n = Number(raw)
  if (raw == null || raw === '' || !Number.isFinite(n)) return undefined
  return Math.round((n / MIB) * 100) / 100
}

/** 表单里的 MiB → values 里的字节数字符串 */
export function miBToBytes(mib: number): string {
  return String(Math.round(mib * MIB))
}

/** 每个字段自身的取值范围；不合法返回原因，否则 null */
export function fieldProblem(type: 'windowMiB' | 'intString', label: string, value: unknown): string | null {
  const n = typeof value === 'number' ? value : Number(value)
  if (value == null || value === '' || !Number.isFinite(n)) return `请填写「${label}」`
  if (type === 'intString') {
    if (!Number.isInteger(n) || n < 1 || n > MAX_STREAMS) return `「${label}」要在 1 到 ${MAX_STREAMS} 之间`
    return null
  }
  if (!Number.isInteger(n) || n < 1 || n > MAX_WINDOW_MIB) return `「${label}」要在 1 到 ${MAX_WINDOW_MIB} MiB 之间的整数`
  return null
}

/**
 * 两组字段之间的约束；values 是表单当前值（窗口为 MiB 数字、请求数为整数），没出现的字段不检查。
 */
export function crossFieldProblem(values: Record<string, unknown>): string | null {
  const num = (key: string): number | null => {
    const v = values[key]
    const n = typeof v === 'number' ? v : Number(v)
    return v == null || v === '' || !Number.isFinite(n) ? null : n
  }
  const streams = num(ZTUNNEL_STREAMS_KEY)
  const zStream = num(ZTUNNEL_STREAM_KEY)
  const zConnection = num(ZTUNNEL_CONNECTION_KEY)
  if (streams != null && zStream != null && zConnection != null && streams * zStream >= zConnection) {
    return `Pod 之间（ztunnel）：请求数 × 单个请求的缓冲上限（${streams} × ${zStream} = ${streams * zStream} MiB）`
      + `必须小于整条连接的缓冲上限（${zConnection} MiB），否则这些请求同时暂停时，同一条连接上的其它请求会全部卡住`
  }
  const eStream = num(ISTIOD_STREAM_KEY)
  const eConnection = num(ISTIOD_CONNECTION_KEY)
  if (eStream != null && eConnection != null && eConnection < eStream * ENVOY_MIN_RATIO) {
    return `入口网关与 waypoint：整条连接的缓冲上限至少要是单个请求的 ${ENVOY_MIN_RATIO} 倍`
      + `（现在 ${eConnection} / ${eStream} MiB）。被中断的下载会占着额度 1 小时不放，倍数太小几次中断就会让服务无法访问`
  }
  return null
}

/** 分组说明（表单小节标题下的一行提示）；不是这两组 → null */
export function groupHint(group: string): string | null {
  if (group.includes('ztunnel')) {
    return '一条连接上同时跑多个请求；暂停的请求（如视频缓冲够了）会占着额度。'
      + '要求「请求数 × 单个请求的缓冲上限」小于连接上限，暂停再多也不会卡住别的请求。调大只在数据真积压时多占内存'
  }
  if (group.includes('waypoint')) {
    return '浏览器中途取消的下载会占着额度 1 小时不放（Istio 已知问题）。'
      + '单个请求的上限越小、连接上限越大，越不容易把服务卡死；连接上限至少是单个请求的 16 倍'
  }
  return null
}
