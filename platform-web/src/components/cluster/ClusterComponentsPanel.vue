<script setup lang="ts">
/**
 * 集群组件清单（S1.2）。数据源 `POST /cluster/components`（平台侧全量），由 `ClusterDetailView`
 * 用 `useClusterComponents` 取好后经 props 传入 —— 一个页面只探测一次，面板不是第二个数据源
 * （同 `MeshStatusBanner` 的约定）。
 *
 * ⚠️ 四态必须分开渲染 —— {@code UNKNOWN}（探测不到）与 {@code NOT_INSTALLED}（没装）是相反结论，
 *    都渲染成灰 tag 就等于把"不知道"说成了"没有"（本仓修过的坑）。
 * ⚠️ 本批**不给安装按钮**：安装/升级留给 S3.3 / S3.4（spec 决策 9），这里只给指引。
 */
import { computed } from 'vue'
import type { K8sComponentState, K8sComponentStatus } from '@/types'

const props = defineProps<{
  statuses: K8sComponentStatus[]
  loading: boolean
  /** 「重新探测」需要 platform:cluster:manage（写操作） */
  canRefresh: boolean
}>()
const emit = defineEmits<{ (e: 'refresh'): void }>()

/** 四态 → 徽标。⚠️ UNKNOWN 用虚线边（effect=plain）与 NOT_INSTALLED（实心灰）区分开 */
const STATE: Record<K8sComponentState, {
  label: string
  type: 'success' | 'warning' | 'info'
  effect: 'light' | 'plain'
}> = {
  INSTALLED_HEALTHY: { label: '已装', type: 'success', effect: 'light' },
  INSTALLED_UNHEALTHY: { label: '已装 · 不健康', type: 'warning', effect: 'light' },
  NOT_INSTALLED: { label: '未安装', type: 'info', effect: 'light' },
  UNKNOWN: { label: '未探测', type: 'info', effect: 'plain' },
}

/** 功能 code → 展示文案。code 是稳定 id（门控按它查），文案改这里不影响门控 */
const FEATURE_LABELS: Record<string, string> = {
  'hpa-resource-metrics': 'HPA 资源指标',
  'metrics-charts': '监控曲线',
  servicemonitor: 'ServiceMonitor / PodMonitor',
  'service-mesh': '服务网格',
  routes: '路由',
  'ambient-mesh': 'ambient 流量接管',
  ci: 'CI 流水线',
  network: '网络',
  ipam: 'IPAM',
  'gateway-tls': '网关 TLS 证书',
  'otel-collection': 'OTel 采集',
}
function featureLabel(code: string): string {
  return FEATURE_LABELS[code] ?? code
}

const rows = computed(() => props.statuses)

const healthyCount = computed(() =>
  props.statuses.filter((s) => s.state === 'INSTALLED_HEALTHY' || s.state === 'INSTALLED_UNHEALTHY').length)
const missingCount = computed(() => props.statuses.filter((s) => s.state === 'NOT_INSTALLED').length)
const unknownCount = computed(() => props.statuses.filter((s) => s.state === 'UNKNOWN').length)
/** 缺组件波及的功能数（去重） —— 这就是台账说的"新的那一层" */
const affectedFeatures = computed(() => {
  const codes = new Set<string>()
  for (const s of props.statuses) {
    if (s.state === 'NOT_INSTALLED') s.features.forEach((f) => codes.add(f))
  }
  return codes.size
})

/** 摘要一行：`11 个组件中 10 个已装，缺 1 个会影响 1 项平台功能` */
const summary = computed(() => {
  if (!props.statuses.length) return props.loading ? '正在探测…' : '—'
  const total = props.statuses.length
  const parts = [`${total} 个组件中 ${healthyCount.value} 个已装`]
  if (missingCount.value) parts.push(`缺 ${missingCount.value} 个会影响 ${affectedFeatures.value} 项平台功能`)
  if (unknownCount.value) parts.push(`${unknownCount.value} 个未探测`)
  return parts.join('，')
})

const anyUnknown = computed(() => unknownCount.value > 0)
</script>

<template>
  <div class="cc-panel">
    <div class="cc-head">
      <span class="cc-summary">{{ summary }}</span>
      <el-button size="small" :loading="loading" :disabled="!canRefresh" @click="emit('refresh')">
        重新探测
      </el-button>
    </div>

    <div v-if="anyUnknown" class="cc-warn">
      集群当前探测不到，以下标记「未探测」的组件状态可能不准确。
    </div>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column label="组件" min-width="190">
        <template #default="{ row }">
          <span class="cc-name">{{ row.name }}</span>
          <code class="cc-code">{{ row.code }}</code>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <!-- 四态各一态：UNKNOWN 用虚线边与 NOT_INSTALLED 区分开 -->
          <el-tag size="small" :type="STATE[row.state as K8sComponentState].type"
                  :effect="STATE[row.state as K8sComponentState].effect">
            {{ STATE[row.state as K8sComponentState].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="150">
        <template #default="{ row }"><code class="mono">{{ row.version ?? '—' }}</code></template>
      </el-table-column>
      <el-table-column label="命名空间" width="180">
        <template #default="{ row }">{{ row.namespace ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="健康" width="100">
        <template #default="{ row }">
          {{ row.ready == null ? '—' : `${row.ready}/${row.desired}` }}
        </template>
      </el-table-column>
      <el-table-column label="影响的功能" min-width="260">
        <template #default="{ row }">
          <span v-for="f in row.features" :key="f" class="cc-feature">{{ featureLabel(f) }}</span>
          <!-- 缺组件给指引，不给安装按钮（安装/升级留给 S3.3 / S3.4） -->
          <div v-if="row.state === 'NOT_INSTALLED' && row.features.length" class="cc-guide">
            缺它 → {{ row.features.map(featureLabel).join(' / ') }} 不可用（安装需 cluster-admin）
          </div>
          <div v-else-if="row.state === 'UNKNOWN'" class="cc-guide">{{ row.detail }}</div>
          <div v-else-if="row.state === 'INSTALLED_UNHEALTHY'" class="cc-guide">{{ row.detail }}</div>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.cc-panel { display: flex; flex-direction: column; gap: 10px; }
.cc-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.cc-summary { font-size: 13px; color: var(--text-2); }
.cc-warn {
  font-size: 12px;
  color: var(--warning);
  padding: 6px 10px;
  border: 1px dashed var(--warning);
  border-radius: 6px;
}
.cc-name { margin-right: 8px; }
.cc-code { font-size: 12px; color: var(--text-3); }
.cc-feature { margin-right: 8px; font-size: 12px; color: var(--text-2); }
.cc-guide { margin-top: 4px; font-size: 12px; color: var(--text-3); }
</style>
