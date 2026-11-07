<script setup lang="ts">
/**
 * 应用服务概览里的「自动扩缩容」「可观测」「服务网格」三行（spec 2026-11-07 §6）。
 *
 * <p>只展示<b>线上快照</b>（当前线上发布的 snapshot.services[key]）里开启的项 —— 期望配置里改了没发布的不算。
 * 细节不在这里重做，一律跳到已有页面（HPA / 日志查询 / 链路追踪 / 网格拓扑）；目标页无权限则不显示按钮。
 * HPA 读失败或无权限：整行不显示（不报错）。
 */
import { computed, ref, watch } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { hpaApi } from '@/api'
import type { AppServiceDesired, K8sHpa, K8sHpaMetric } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'

const props = defineProps<{
  /** 线上快照里该服务的期望配置；null = 未发布 / 读不到 */
  live: AppServiceDesired | null
  workloadName: string
  ctx: { tenantId: string; clusterId: string; namespace: string }
}>()

const router = useRouter()
const perm = usePermission()

/** 目标路由的 requiresPerm（ANY-of）判定；没声明的视为可进 */
function canOpen(loc: RouteLocationRaw): boolean {
  try {
    const codes = router.resolve(loc).meta?.requiresPerm as string[] | undefined
    return !codes?.length || perm.hasAny(codes)
  } catch {
    return false
  }
}
function go(loc: RouteLocationRaw): void {
  void router.push(loc)
}

// ---------- HPA ----------
const hpa = ref<K8sHpa | null>(null)
async function loadHpa(): Promise<void> {
  hpa.value = null
  const { tenantId, clusterId, namespace } = props.ctx
  if (!props.live?.autoscalingEnabled || !props.workloadName || !tenantId || !clusterId || !namespace) return
  if (!perm.has(apiCodes.hpaGet)) return
  try {
    hpa.value = await hpaApi.get(props.workloadName, props.ctx)
  } catch {
    hpa.value = null // 读失败：整行不显示
  }
}
watch(() => [props.live?.autoscalingEnabled, props.workloadName, props.ctx.tenantId, props.ctx.clusterId, props.ctx.namespace],
  () => { void loadHpa() }, { immediate: true })

const RESOURCE_TEXT: Record<string, string> = { cpu: 'CPU', memory: '内存' }
function metricText(m: K8sHpaMetric): string {
  const r = m.resource ?? m.containerResource
  if (!r) return m.type
  const t = r.target
  const name = RESOURCE_TEXT[r.name ?? ''] ?? r.name ?? m.type
  if (t?.averageUtilization != null) return `${name} 目标 ${t.averageUtilization}%`
  if (t?.averageValue != null) return `${name} 目标均值 ${t.averageValue}`
  return name
}
const hpaMetrics = computed(() => (hpa.value?.metrics ?? []).map(metricText))
const hpaLink: RouteLocationRaw = { name: 'hpas' }

// ---------- 可观测 ----------
const otel = computed(() => props.live?.otel ?? null)
const LANG_TEXT: Record<string, string> = { java: 'Java', python: 'Python' }
const otelParts = computed<string[]>(() => {
  const o = otel.value
  if (!o) return []
  const out: string[] = []
  if (o.language && (o.tracing || (o.logs && o.logMode !== 'file'))) out.push(LANG_TEXT[o.language] ?? o.language)
  if (o.tracing) out.push(o.sampling != null ? `链路追踪（采样 ${o.sampling}%）` : '链路追踪')
  if (o.logs) out.push(o.logMode === 'file' ? `日志采集（读文件 ${o.logFile ?? ''}）` : '日志采集（日志框架）')
  return out
})
const logsLink = computed<RouteLocationRaw>(() => ({ name: 'observability-logs', query: { workload: props.workloadName } }))
const tracesLink = computed<RouteLocationRaw>(() => ({ name: 'mesh-traces', query: { service: props.workloadName } }))

// ---------- 服务网格 ----------
const mesh = computed(() => props.live?.mesh ?? null)
const meshParts = computed<string[]>(() => {
  const m = mesh.value
  if (!m) return []
  const out: string[] = []
  if (m.dataplaneMode) out.push(m.dataplaneMode === 'ambient' ? '数据面 ambient' : '数据面 none（不加入网格）')
  if (m.useWaypoint) out.push(m.useWaypoint === 'none' ? 'waypoint 显式不使用' : `waypoint ${m.useWaypoint}`)
  return out
})
const topologyLink = computed<RouteLocationRaw>(() => ({
  name: 'mesh-topology', query: { focus: `${props.ctx.namespace}/${props.workloadName}` },
}))
</script>

<template>
  <div v-if="live?.autoscalingEnabled && hpa" class="app-rt__kv">
    <span class="k">自动扩缩容</span>
    <span>当前 {{ hpa.currentReplicas ?? '—' }} 副本（{{ hpa.minReplicas ?? 1 }}–{{ hpa.maxReplicas ?? '?' }}）</span>
    <span v-if="hpaMetrics.length" class="muted">{{ hpaMetrics.join('；') }}</span>
    <el-button v-if="canOpen(hpaLink)" link type="primary" @click="go(hpaLink)">查看 HPA</el-button>
  </div>
  <div v-if="otelParts.length" class="app-rt__kv">
    <span class="k">可观测</span>
    <span>{{ otelParts.join('、') }}</span>
    <el-button v-if="otel?.logs && canOpen(logsLink)" link type="primary" @click="go(logsLink)">历史日志</el-button>
    <el-button v-if="otel?.tracing && canOpen(tracesLink)" link type="primary" @click="go(tracesLink)">链路</el-button>
  </div>
  <div v-if="meshParts.length" class="app-rt__kv">
    <span class="k">服务网格</span>
    <span>{{ meshParts.join('、') }}</span>
    <el-button v-if="canOpen(topologyLink)" link type="primary" @click="go(topologyLink)">在网格拓扑里看</el-button>
  </div>
</template>

<style scoped>
.app-rt__kv { display: flex; align-items: center; gap: 12px; padding: 6px 0; font-size: 13.5px; flex-wrap: wrap; }
.app-rt__kv .k { width: 90px; flex-shrink: 0; color: var(--text-3); }
.app-rt__kv .muted { color: var(--text-3); font-size: 12.5px; }
</style>
