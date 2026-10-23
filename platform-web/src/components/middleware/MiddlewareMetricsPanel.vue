<script setup lang="ts">
/**
 * 中间件实例的监控曲线（CPU / 内存 / 网络 / 磁盘），跨该实例**全部 Pod** 聚合。
 *
 * <p>与工作负载详情同款 `MetricChart`；数据来自 `/middleware/instance/metrics`（一次请求返回四张图，
 * 后端只解析一次 Pod 集合）。四图共用一个时间范围，切换时整组重取。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { middlewareApi } from '@/api'
import MetricChart from '@/components/workload/MetricChart.vue'
import type { MiddlewareMetrics } from '@/types'

const props = defineProps<{
  instanceId: string
  tenantId?: string | null
  /** CPU 上限（核）；>0 时 CPU 图可切百分比 */
  cpuLimit?: number | null
  /** 内存上限（字节）；>0 时内存图可切百分比 */
  memoryLimit?: number | null
  /** 创建时是否开了「采集业务指标」；关着就只显示资源用量，并给一行说明 */
  metricsEnabled?: boolean
}>()

const RANGES = [15, 30, 60]
const range = ref(30)
const loading = ref(false)
const data = ref<MiddlewareMetrics | null>(null)

/** 该中间件自己的指标（Redis 连接数 / RabbitMQ 积压 / PG 复制延迟…），由后端按类型声明 */
const business = computed(() => data.value?.business ?? [])

async function load(): Promise<void> {
  if (!props.instanceId) return
  loading.value = true
  try {
    const end = Math.floor(Date.now() / 1000)
    data.value = await middlewareApi.instanceMetrics(
      props.instanceId, end - range.value * 60, end, props.tenantId ?? undefined)
  } catch {
    data.value = null // 失败：四图都显示「无数据」（拦截器已统一提示）
  } finally {
    loading.value = false
  }
}

function setRange(v: string | number | boolean | undefined): void {
  const n = Number(v)
  if (!Number.isFinite(n) || n === range.value) return
  range.value = n
  void load()
}

watch(() => props.instanceId, load)
onMounted(load)

/** 供详情页「刷新」联动 */
defineExpose({ refresh: load })
</script>

<template>
  <section class="mw-metrics panel">
    <div class="mm-head">
      <span class="mm-title">监控指标</span>
      <el-radio-group :model-value="range" size="small" @change="setRange">
        <el-radio-button v-for="r in RANGES" :key="r" :value="r">{{ r }} 分钟</el-radio-button>
      </el-radio-group>
    </div>
    <div class="mm-grid">
      <MetricChart title="CPU" :unit="data?.cpu.unit ?? '核'" :series="data?.cpu.series ?? []"
                   :loading="loading" percentable :limit="cpuLimit ?? null" />
      <MetricChart title="内存" :unit="data?.memory.unit ?? '字节'" :series="data?.memory.series ?? []"
                   :loading="loading" percentable :limit="memoryLimit ?? null" />
      <MetricChart title="网络" :unit="data?.network.unit ?? '字节/秒'" :series="data?.network.series ?? []"
                   :loading="loading" />
      <MetricChart title="磁盘" :unit="data?.disk.unit ?? '字节/秒'" :series="data?.disk.series ?? []"
                   :loading="loading" />
    </div>

    <!-- 该中间件自己的指标（创建时勾了「采集业务指标」才存在） -->
    <template v-if="metricsEnabled && business.length">
      <div class="mm-subhead">业务指标</div>
      <div class="mm-grid">
        <MetricChart v-for="m in business" :key="m.key" :title="m.label"
                     :unit="m.data.unit" :series="m.data.series ?? []" :loading="loading" />
      </div>
    </template>
    <p v-else-if="!metricsEnabled" class="mm-hint">
      该实例创建时未开启「采集业务指标」；需要业务指标请删除后按新规格重建（勾选该开关）。
    </p>

    <p class="mm-help">
      资源用量口径：该实例在当前集群里的全部 Pod 聚合（含 Operator 侧车，不含网络命名空间影子容器）。
    </p>
  </section>
</template>

<style scoped>
.mw-metrics { padding: 12px 14px; }
.mm-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.mm-title { font-weight: 600; }
.mm-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.mm-subhead { margin: 16px 0 8px; font-weight: 600; font-size: 13px; color: var(--text-2); }
.mm-help { margin: 8px 0 0; font-size: 12px; color: var(--text-3); }
.mm-hint { margin: 10px 0 0; font-size: 12px; color: var(--text-3); }
</style>
