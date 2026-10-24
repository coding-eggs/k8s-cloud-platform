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
import type { MiddlewareBusinessMetric, MiddlewareMetrics } from '@/types'

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
/** 适合"当前值/状态"的（连接数、节点数、分片数、存活…）：画成时序图只会是一条平线 */
const stats = computed(() => business.value.filter((m) => (m.kind ?? 'CHART') !== 'CHART'))
/** 只有速率/比率这类看趋势才有意义的才画图 */
const charts = computed(() => business.value.filter((m) => (m.kind ?? 'CHART') === 'CHART'))

/** 序列最后一个采样点（当前值）；没有数据返回 null */
function latest(m: MiddlewareBusinessMetric): number | null {
  const points = m.data?.series?.[0]?.points ?? []
  const v = points.length ? points[points.length - 1]?.value : null
  return v == null || Number.isNaN(v) ? null : v
}

/** 字节 → 卡片用的紧凑写法（formatBytes 保留 12 位有效数字，卡片上太长） */
function statBytes(v: number): string {
  const abs = Math.abs(v)
  if (abs >= 1024 ** 4) return `${(v / 1024 ** 4).toFixed(2)} Ti`
  if (abs >= 1024 ** 3) return `${(v / 1024 ** 3).toFixed(1)} Gi`
  if (abs >= 1024 ** 2) return `${(v / 1024 ** 2).toFixed(1)} Mi`
  if (abs >= 1024) return `${(v / 1024).toFixed(1)} Ki`
  return `${Math.round(v)} B`
}

/** 当前值按单位格式化（字节走人类可读，百分比/秒保留合适精度） */
function statText(m: MiddlewareBusinessMetric): string {
  const v = latest(m)
  if (v == null) return '无数据'
  const unit = m.data?.unit ?? ''
  if (unit === '字节') return statBytes(v)
  if (unit === '%') return v.toFixed(1)
  if (unit === '秒') return v >= 10 ? v.toFixed(0) : v.toFixed(2)
  if (Math.abs(v) >= 10000) return Math.round(v).toLocaleString()
  return Number.isInteger(v) || Math.abs(v) >= 100 ? String(Math.round(v)) : v.toFixed(2)
}

/** 状态标签：goodWhenNonZero=true → value>0 正常；否则 value==0 正常（告警数/流控） */
function stateOf(m: MiddlewareBusinessMetric): { text: string; type: 'success' | 'danger' | 'info' } {
  const v = latest(m)
  if (v == null) return { text: '无数据', type: 'info' }
  const good = m.goodWhenNonZero ? v > 0 : v === 0
  return good
    ? { text: m.goodLabel || '正常', type: 'success' }
    : { text: m.badLabel || '异常', type: 'danger' }
}

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

    <!-- 该中间件自己的指标（创建时勾选或编辑里开启后才存在） -->
    <template v-if="metricsEnabled">
      <!-- 当前值 / 状态：画成趋势图只会是平线，用卡片更直观 -->
      <template v-if="stats.length">
        <div class="mm-subhead">当前状态</div>
        <div class="mm-stats">
          <div v-for="m in stats" :key="m.key" class="mm-stat">
            <div class="mm-stat-label">{{ m.label }}</div>
            <div class="mm-stat-value">
              <el-tag v-if="m.kind === 'STATE'" size="small" :type="stateOf(m).type" effect="light">
                {{ stateOf(m).text }}
              </el-tag>
              <template v-else>
                <span class="mm-stat-num">{{ statText(m) }}</span>
                <!-- 字节已经带了 B/Ki/Mi/Gi 单位，不再重复 -->
                <span v-if="m.data.unit !== '字节'" class="mm-stat-unit">{{ m.data.unit }}</span>
              </template>
            </div>
          </div>
        </div>
      </template>
      <!-- 速率 / 比率：看趋势 -->
      <template v-if="charts.length">
        <div class="mm-subhead">业务指标</div>
        <div class="mm-grid">
          <MetricChart v-for="m in charts" :key="m.key" :title="m.label"
                       :unit="m.data.unit" :series="m.data.series ?? []" :loading="loading" />
        </div>
      </template>
      <p v-if="!stats.length && !charts.length" class="mm-hint">
        已开启指标采集，但还没有数据（新实例需等一轮采集）。
      </p>
    </template>
    <p v-else class="mm-hint">
      该实例未开启「采集业务指标」；可在「编辑」里开启（单向，不支持关闭）。
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
.mm-stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
.mm-stat {
  border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px;
  background: var(--panel, #fff);
}
.mm-stat-label { font-size: 12px; color: var(--text-3); margin-bottom: 6px; }
.mm-stat-value { display: flex; align-items: baseline; gap: 4px; }
.mm-stat-num { font-size: 20px; font-weight: 600; }
.mm-stat-unit { font-size: 12px; color: var(--text-3); }
.mm-help { margin: 8px 0 0; font-size: 12px; color: var(--text-3); }
.mm-hint { margin: 10px 0 0; font-size: 12px; color: var(--text-3); }
</style>
