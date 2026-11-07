/**
 * 应用服务里「依赖集群组件」的功能 → 组件功能旗标码。与后端 `AppServiceFeatures.required` 同口径：
 * 只有让功能生效的取值才算开启；网格的「跟随」与 none 任何时候都能设。
 *
 * <p>链路追踪与服务网格是应用级设置（后端 `AppSettings`）：判定前先用 {@link effectiveDesired}
 * 把应用设置合并进服务配置，与后端渲染 / 门禁同一套合并规则。
 */
import type { AppServiceDesired, AppSettings } from '@/types'

/** 发布勾选与 pendingKeys 里代表「应用设置」的伪 key（服务 key 是 DNS 标签，不会冲突） */
export const APP_SETTINGS_KEY = '@app'

export const FEATURE_LABELS: Record<string, string> = {
  'hpa-resource-metrics': '自动扩缩容',
  servicemonitor: '指标采集',
  'otel-collection': '链路追踪 / 应用日志接入',
  'otel-logs': '日志采集',
  'ambient-mesh': '服务网格',
}

export function waypointNameOf(s?: AppSettings | null): string | null {
  const v = (s?.mesh?.useWaypoint ?? '').trim()
  return v && v !== 'none' ? v : null
}

/** 应用设置本身需要的旗标（保存应用设置时的门禁） */
export function settingsRequiredFlags(s?: AppSettings | null): string[] {
  const out: string[] = []
  if (s?.tracing) out.push('otel-collection')
  if (s?.mesh?.dataplaneMode === 'ambient' || waypointNameOf(s)) out.push('ambient-mesh')
  return out
}

/** 该服务在给定应用设置下是否被链路追踪：应用开了追踪且服务选了语言 */
export function isTraced(d?: AppServiceDesired | null, s?: AppSettings | null): boolean {
  return !!(s?.tracing && d?.otel?.language)
}

/** 服务在给定应用设置下需要的旗标（与后端 AppSettings.effective + AppServiceFeatures.required 同口径） */
export function requiredFlags(d?: AppServiceDesired | null, s?: AppSettings | null): string[] {
  if (!d) return []
  const out: string[] = []
  if (d.autoscalingEnabled) out.push('hpa-resource-metrics')
  if (d.port && d.metricsEnabled) out.push('servicemonitor')
  const o = d.otel
  const appLogs = !!(o?.logs && o.logMode !== 'file')
  if (isTraced(d, s) || appLogs) out.push('otel-collection')
  if (o?.logs) out.push('otel-logs')
  const mesh = s?.mesh
  if (mesh?.dataplaneMode === 'ambient' || (d.port && waypointNameOf(s))) out.push('ambient-mesh')
  return out
}
