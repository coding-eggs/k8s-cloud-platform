<script setup lang="ts">
/** 节点指标面板：4 图（CPU/内存/网络/磁盘），每图独立时间范围（15m/30m/60m）。
 * 数据源 node_exporter，按 instance=<internalIp>:9100 过滤；刷新由父级「全局刷新」驱动（defineExpose.refresh）。 */
import { onMounted, reactive, watch } from 'vue'
import { nodeMetrics, type NodeMetricsReq } from '@/api/metrics'
import type { MetricSeriesResponse } from '@/types/metrics'
import MetricChart from '../workload/MetricChart.vue'

const props = defineProps<{
  name: string
  clusterId: string
  /** node_exporter 实例（<internalIp>:9100）；为空时不查询 */
  instance: string
  /** CPU 配额（核，allocatable）；>0 时 CPU 图可切 % */
  cpuLimit?: number | null
  /** 内存配额（字节，allocatable）；>0 时内存图可切 % */
  memoryLimit?: number | null
}>()

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
  if (!props.name || !props.clusterId || !props.instance) return
  state[key].loading = true
  try {
    const end = Math.floor(Date.now() / 1000)
    const req: NodeMetricsReq = { clusterId: props.clusterId, instance: props.instance, start: end - ranges[key] * 60, end }
    state[key].data = await nodeMetrics[key](props.name, req)
  } catch {
    state[key].data = null // 失败：该图显示「无数据」（全局拦截器已弹错误提示）
  } finally {
    state[key].loading = false
  }
}

async function fetchAll(): Promise<void> {
  if (!props.name || !props.clusterId || !props.instance) return
  await Promise.all(KEYS.map((k) => fetchOne(k)))
}

// 每图时间范围变化 → 只重取该图
watch(() => ranges.cpu, () => void fetchOne('cpu'))
watch(() => ranges.memory, () => void fetchOne('memory'))
watch(() => ranges.network, () => void fetchOne('network'))
watch(() => ranges.disk, () => void fetchOne('disk'))
// 上下文 / 目标变化 → 全部重取
watch([() => props.name, () => props.clusterId, () => props.instance], fetchAll)
onMounted(fetchAll)

/** 供父级「全局刷新」调用：立即重取全部 4 图 */
defineExpose({ refresh: fetchAll })
</script>

<template>
  <section class="metrics-panel">
    <div class="mp-head">
      <h3 class="mp-title">监控指标（node_exporter）</h3>
      <span v-if="!instance" class="muted">未获取到节点内网 IP，无法查询监控数据</span>
    </div>

    <div v-if="instance" class="metrics-grid">
      <MetricChart title="CPU 使用率" :unit="state.cpu.data?.unit ?? '%'" :series="state.cpu.data?.series ?? []" :loading="state.cpu.loading" :ranges="RANGES" :range="ranges.cpu" @update:range="(v) => (ranges.cpu = v)" />
      <MetricChart title="内存" :unit="state.memory.data?.unit ?? '字节'" :series="state.memory.data?.series ?? []" :loading="state.memory.loading" percentable :limit="memoryLimit" :ranges="RANGES" :range="ranges.memory" @update:range="(v) => (ranges.memory = v)" />
      <MetricChart title="网络 IO（聚合）" :unit="state.network.data?.unit ?? '字节/秒'" :series="state.network.data?.series ?? []" :loading="state.network.loading" :ranges="RANGES" :range="ranges.network" @update:range="(v) => (ranges.network = v)" />
      <MetricChart title="磁盘 IO（按设备）" :unit="state.disk.data?.unit ?? '字节/秒'" :series="state.disk.data?.series ?? []" :loading="state.disk.loading" :ranges="RANGES" :range="ranges.disk" @update:range="(v) => (ranges.disk = v)" />
    </div>
  </section>
</template>

<style scoped>
.metrics-panel { }
.mp-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.mp-title { margin: 0; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.muted { color: var(--text-3); font-size: 12.5px; }
.metrics-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
}
</style>
