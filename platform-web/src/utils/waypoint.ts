/**
 * Istio ambient / Gateway API 相关的**共享口径**（B3 §11 × B6）。
 *
 * <p>这里放三类东西，共同点是「同一事实被多处用到，散落就会漂移」：
 * 1. **waypoint 判定** —— 后端 `GatewayService.isWaypointGateway` 的同口径前端副本；
 * 2. **保留 label 常量** —— 命名空间 / pod template / Gateway 上的 istio 标签 key；
 * 3. **保留 key 清单** —— 通用 LabelEditor 必须排除它们，否则用户能绕过专用区块
 *    （把 ambient 标签当普通标签改，两个输入源互相覆盖）。
 */

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
