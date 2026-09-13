<script setup lang="ts">
/** 指标面板：4 图（CPU/内存/网络/磁盘），每图独立时间范围（5m/10m/20m）。
 * 按 dimension 选 workload/pod 接口；每图独立 loading。刷新由父级「全局刷新」驱动（defineExpose.refresh）。 */
import { computed, onMounted, reactive, watch } from 'vue'
import { podMetrics, workloadMetrics, type MetricsRangeReq } from '@/api/metrics'
import type { MetricSeriesResponse } from '@/types/metrics'
import MetricChart from './MetricChart.vue'

const props = defineProps<{
  dimension: 'workload' | 'pod'
  name: string
  /** 租户 ID：admin token 代操作必须显式传（k8s-server 列 pod 做命名空间域边界校验） */
  tenantId: string
  clusterId: string
  namespace: string
  /** 工作负载类型（deployment/statefulset/daemonset）；仅 workload 维度传，pod 维度留空 */
  kind?: string
  /** CPU 配额（核）；>0 时 CPU 图可切 % */
  cpuLimit?: number | null
  /** 内存配额（字节）；>0 时内存图可切 % */
  memoryLimit?: number | null
}>()

const api = computed(() => (props.dimension === 'workload' ? workloadMetrics : podMetrics))

type MetricKey = 'cpu' | 'memory' | 'network' | 'disk'
const KEYS: MetricKey[] = ['cpu', 'memory', 'network', 'disk']
interface Cell { data: MetricSeriesResponse | null; loading: boolean }
const state = reactive<Record<MetricKey, Cell>>({
  cpu: { data: null, loading: false },
  memory: { data: null, loading: false },
  network: { data: null, loading: false },
  disk: { data: null, loading: false },
})

const RANGES = [15, 30, 60]
/** 每图独立的时间范围（分钟） */
const ranges = reactive<Record<MetricKey, number>>({ cpu: 30, memory: 30, network: 30, disk: 30 })

async function fetchOne(key: MetricKey): Promise<void> {
  if (!props.name || !props.tenantId || !props.clusterId) return
  state[key].loading = true
  try {
    const end = Math.floor(Date.now() / 1000)
    const req: MetricsRangeReq = { tenantId: props.tenantId, clusterId: props.clusterId, namespace: props.namespace, kind: props.kind, start: end - ranges[key] * 60, end }
    state[key].data = await api.value[key](props.name, req)
  } catch {
    state[key].data = null // 失败：该图显示「无数据」（全局拦截器已弹错误提示）
  } finally {
    state[key].loading = false
  }
}

async function fetchAll(): Promise<void> {
  if (!props.name || !props.tenantId || !props.clusterId) return
  await Promise.all(KEYS.map((k) => fetchOne(k)))
}

// 每图时间范围变化 → 只重取该图
watch(() => ranges.cpu, () => void fetchOne('cpu'))
watch(() => ranges.memory, () => void fetchOne('memory'))
watch(() => ranges.network, () => void fetchOne('network'))
watch(() => ranges.disk, () => void fetchOne('disk'))
// 上下文 / 目标变化 → 全部重取
watch([() => props.name, () => props.tenantId, () => props.clusterId, () => props.namespace], fetchAll)
onMounted(fetchAll)

/** 供父级「全局刷新」调用：立即重取全部 4 图 */
defineExpose({ refresh: fetchAll })
</script>

<template>
  <section class="metrics-panel">
    <div class="mp-head">
      <h3 class="mp-title">监控指标</h3>
    </div>

    <div class="metrics-grid">
      <MetricChart title="CPU" :unit="state.cpu.data?.unit ?? '核'" :series="state.cpu.data?.series ?? []" :loading="state.cpu.loading" percentable :limit="cpuLimit" :ranges="RANGES" :range="ranges.cpu" @update:range="(v) => (ranges.cpu = v)" />
      <MetricChart title="内存" :unit="state.memory.data?.unit ?? '字节'" :series="state.memory.data?.series ?? []" :loading="state.memory.loading" percentable :limit="memoryLimit" :ranges="RANGES" :range="ranges.memory" @update:range="(v) => (ranges.memory = v)" />
      <MetricChart title="网络 IO" :unit="state.network.data?.unit ?? '字节/秒'" :series="state.network.data?.series ?? []" :loading="state.network.loading" :ranges="RANGES" :range="ranges.network" @update:range="(v) => (ranges.network = v)" />
      <MetricChart title="磁盘 IO" :unit="state.disk.data?.unit ?? '字节/秒'" :series="state.disk.data?.series ?? []" :loading="state.disk.loading" :ranges="RANGES" :range="ranges.disk" @update:range="(v) => (ranges.disk = v)" />
    </div>
  </section>
</template>

<style scoped>
.metrics-panel {  }
.mp-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.mp-title { margin: 0; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.metrics-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);   /* 单列：4 图顺序往下排；minmax(0,…) 保证能缩到比内容更窄 */
  gap: 14px;
}
</style>
