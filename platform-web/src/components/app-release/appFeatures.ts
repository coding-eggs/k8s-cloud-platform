/**
 * 应用服务里「依赖集群组件」的功能 → 组件功能旗标码。与后端 `AppServiceFeatures.required` 同口径：
 * 只有让功能生效的取值才算开启；网格的「跟随」与 none 任何时候都能设。
 */
import type { AppServiceDesired } from '@/types'

export const FEATURE_LABELS: Record<string, string> = {
  'hpa-resource-metrics': '自动扩缩容',
  servicemonitor: '指标采集',
  'otel-collection': '链路追踪 / 应用日志接入',
  'otel-logs': '日志采集',
  'ambient-mesh': '服务网格',
}

export function waypointNameOf(d?: AppServiceDesired | null): string | null {
  const v = (d?.mesh?.useWaypoint ?? '').trim()
  return v && v !== 'none' ? v : null
}

export function requiredFlags(d?: AppServiceDesired | null): string[] {
  if (!d) return []
  const out: string[] = []
  if (d.autoscalingEnabled) out.push('hpa-resource-metrics')
  if (d.port && d.metricsEnabled) out.push('servicemonitor')
  const o = d.otel
  if (o) {
    const fileLogs = o.logs && o.logMode === 'file'
    if (o.tracing || (o.logs && !fileLogs)) out.push('otel-collection')
    if (o.logs) out.push('otel-logs')
  }
  if (d.mesh?.dataplaneMode === 'ambient' || waypointNameOf(d)) out.push('ambient-mesh')
  return out
}
