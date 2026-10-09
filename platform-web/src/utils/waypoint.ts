/**
 * Istio ambient / Gateway API 相关的**共享口径**（B3 §11 × B6）。
 *
 * <p>这里放三类东西，共同点是「同一事实被多处用到，散落就会漂移」：
 * 1. **waypoint 判定** —— 后端 `GatewayService.isWaypointGateway` 的同口径前端副本；
 * 2. **保留 label 常量 + 类型能力判定** —— 命名空间 / pod template / Gateway 上的 istio 标签 key，
 *    以及「这个 waypoint 能不能承接某类流量」；
 * 3. **保留 key 清单** —— 通用 LabelEditor 必须排除它们，否则用户能绕过专用区块
 *    （把 ambient 标签当普通标签改，两个输入源互相覆盖）。
 */
import type { WaypointRef } from '@/types'

/** waypoint 类别名里的标记段（Istio：istio-waypoint / istio-agentgateway-waypoint）。用「含」而非「以…结尾」，容忍派生命名。 */
export const WAYPOINT_CLASS_MARKER = '-waypoint'

/** GatewayClass 名是否 waypoint 类别（判定口径与后端一致：只看 gatewayClassName） */
export function isWaypointClassName(className?: string | null): boolean {
  return typeof className === 'string' && className.trim().includes(WAYPOINT_CLASS_MARKER)
}

/** Gateway 是否 waypoint 类别 */
export function isWaypointGateway(gw: { gatewayClassName?: string | null } | null | undefined): boolean {
  return isWaypointClassName(gw?.gatewayClassName)
}

/** 命名空间 / Pod 模板：istio ambient 数据面模式（ambient / none） */
export const LABEL_DATAPLANE_MODE = 'istio.io/dataplane-mode'
/** 命名空间 / Service / Pod：指向一个 waypoint Gateway（值 = Gateway 名，或 none 显式不使用） */
export const LABEL_USE_WAYPOINT = 'istio.io/use-waypoint'
/** Gateway：这个 waypoint 处理哪类流量（service / workload / all / none） */
export const LABEL_WAYPOINT_FOR = 'istio.io/waypoint-for'

/** waypoint-for 取值（官方：可选的 label，**缺省即 service**） */
export const WAYPOINT_FOR_VALUES = ['service', 'workload', 'all', 'none'] as const
/** 下拉/说明里的取值文案 */
export const WAYPOINT_FOR_LABELS: Record<string, string> = {
  service: 'service（发往服务的流量，默认）',
  workload: 'workload（Pod / VM IP 直连流量）',
  all: 'all（服务 + 工作负载）',
  none: 'none（不处理任何流量，测试用）',
}
/** label 缺省时的取值（Istio 官方默认值） */
export const WAYPOINT_FOR_DEFAULT = 'service'

/**
 * 读 Gateway 上的 {@code istio.io/waypoint-for}；**缺省 / 空 = service**（官方默认值）。
 * 不认识的值原样返回 —— 能不能用由下面两个白名单判定（不认识一律"不能"）。
 */
export function waypointForOf(
  gw: { labels?: Record<string, string> | null } | null | undefined,
): string {
  const v = gw?.labels?.[LABEL_WAYPOINT_FOR]
  return typeof v === 'string' && v.trim() ? v.trim() : WAYPOINT_FOR_DEFAULT
}

/** 能否承接东西向服务流量 —— 命名空间级 use-waypoint 的可选集（官方：waypoint 默认只处理 service） */
export function canHandleService(waypointFor?: string | null): boolean {
  return waypointFor === 'service' || waypointFor === 'all'
}

/**
 * 能否承接 Pod/VM 直连流量 —— Pod 级（pod template）use-waypoint 的可选集。
 * 官方原文："when you label a pod to use a specific waypoint … the waypoint should be labeled
 * istio.io/waypoint-for with the value workload or all."
 */
export function canHandleWorkload(waypointFor?: string | null): boolean {
  return waypointFor === 'workload' || waypointFor === 'all'
}

/** Gateway（或其窄投影）→ waypoint 引用（名字 + 类型） */
export function toWaypointRef(gw: { name?: string | null; labels?: Record<string, string> | null }): WaypointRef {
  return { name: gw.name ?? '', waypointFor: waypointForOf(gw) }
}

/** waypoint 引用 → 下拉里的展示文案 */
export function waypointOptionLabel(ref: WaypointRef): string {
  return `${ref.name}（${ref.waypointFor ?? WAYPOINT_FOR_DEFAULT}）`
}

/**
 * 由专用 UI 区块独占管理的保留 key（通用标签/注解编辑器必须排除）。
 * 命名空间编辑器的「服务网格」区块、工作负载编辑器的「服务网格」模块是它们唯一的编辑入口。
 */
export const RESERVED_MESH_LABELS = [LABEL_DATAPLANE_MODE, LABEL_USE_WAYPOINT] as const

/** dataplane-mode 取值（与 ztunnel 一致） */
export const DATAPLANE_MODES = [
  { value: 'ambient', label: '纳入 ambient' },
  { value: 'none', label: '排除（none）' },
] as const

/** use-waypoint 的「显式不使用」取值：显式关掉从命名空间继承来的 waypoint */
export const USE_WAYPOINT_NONE = 'none'
