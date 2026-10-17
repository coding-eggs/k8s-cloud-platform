<script setup lang="ts">
/**
 * 集群组件清单（S1.2）。数据源 `POST /cluster/components`（平台侧全量），由 `ClusterDetailView`
 * 用 `useClusterComponents` 取好后经 props 传入 —— 一个页面只探测一次，面板不是第二个数据源
 * （同 `MeshStatusBanner` 的约定）。
 *
 * ⚠️ 四态必须分开渲染 —— {@code UNKNOWN}（探测不到）与 {@code NOT_INSTALLED}（没装）是相反结论，
 *    都渲染成灰 tag 就等于把"不知道"说成了"没有"（本仓修过的坑）。
 * ⚠️ 安装/升级（S3.6）：能不能装由后端下发的 `availableVersions` 决定（空 = 没有 recipe），
 *    前端不再硬编码组件清单。
 *    已装但**不是平台装的**（无平台操作记录）只读 —— 与「已装的一律只探测不接管」一致。
 *    升级只在**有别的版本可选**时给按钮，点开先选版本（只有一个版本时显示「已是最新」）。
 *    UNKNOWN 不给装：「不知道」不是「没有」。
 */
import { computed, ref } from 'vue'
import type { K8sComponentOperation, K8sComponentState, K8sComponentStatus } from '@/types'

const props = defineProps<{
  statuses: K8sComponentStatus[]
  loading: boolean
  /** 「重新探测」需要 platform:cluster:manage（写操作） */
  canRefresh: boolean
  /** 各组件最近一次操作（由页面统一加载） */
  operations: Record<string, K8sComponentOperation>
  /** 正在执行的组件 code（按钮 loading） */
  applying?: string | null
}>()
const emit = defineEmits<{
  (e: 'refresh'): void
  (e: 'apply', payload: { code: string; action: 'INSTALL' | 'UPGRADE'; targetVersion?: string }): void
}>()

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
  'ci-install-source': 'CI（安装来源）',
  network: '网络',
  ipam: 'IPAM',
  'gateway-tls': '网关 TLS 证书',
  'otel-collection': 'OTel 采集',
}
function featureLabel(code: string): string {
  return FEATURE_LABELS[code] ?? code
}

/** 只滤掉与「健康」列重复的 `n/m 就绪`，其余 detail 一律显示 */
function showDetail(detail?: string | null): boolean {
  return !!detail && !/^\d+\/\d+ 就绪$/.test(detail)
}

/** 平台能不能装它：由后端下发的可安装 chart 版本决定（空 = 没有 recipe） */
function installable(row: K8sComponentStatus): boolean {
  return (row.availableVersions?.length ?? 0) > 0
}

/**
 * 线上 chart 版本 —— 只有「平台装成功的最后一次操作」知道。
 * 最近一次操作失败时无法确定，按未知处理（此时允许升级重试）。
 */
function installedChartVersion(code: string): string | null {
  const op = props.operations[code]
  return op && op.status === 'SUCCESS' ? (op.toVersion ?? null) : null
}

/** 可升级到的版本：recipe 里除当前版本之外的 —— 只有一个版本时不出现升级按钮 */
function upgradeTargets(row: K8sComponentStatus): string[] {
  const current = installedChartVersion(row.code)
  return (row.availableVersions ?? []).filter((v) => v !== current)
}

const upgradeDialog = ref<{ visible: boolean; code: string; version: string; versions: string[] }>({
  visible: false, code: '', version: '', versions: [],
})

function openUpgrade(row: K8sComponentStatus): void {
  const versions = upgradeTargets(row)
  if (!versions.length) return
  // 候选按后端（recipe）顺序展示，默认选第一个 —— recipe 的版本列表约定为「新 → 旧」
  upgradeDialog.value = { visible: true, code: row.code, version: versions[0] ?? '', versions }
}

function confirmUpgrade(): void {
  const d = upgradeDialog.value
  if (!d.version) return
  d.visible = false
  emit('apply', { code: d.code, action: 'UPGRADE', targetVersion: d.version })
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
          <!-- 缺组件给指引；安装按钮在右侧「操作」列 -->
          <div v-if="row.state === 'NOT_INSTALLED' && row.features.length" class="cc-guide">
            缺它 → {{ row.features.map(featureLabel).join(' / ') }} 不可用（安装需 cluster-admin）
          </div>
          <!-- ⚠️ detail 必须显示：Review Focus #4（"在平台已知的位置没有找到"）与 #5（"命中 2 个，取 X"）
               都只活在这个字段里。藏掉它们 = UI 变回「断言没装」/「静默挑一个」。 -->
          <div v-if="showDetail(row.detail)" class="cc-guide cc-detail">{{ row.detail }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <template v-if="row.state === 'NOT_INSTALLED' && installable(row)">
            <el-button link type="primary" :loading="props.applying === row.code"
                       @click="emit('apply', { code: row.code, action: 'INSTALL' })">
              安装
            </el-button>
          </template>
          <template v-else-if="row.state === 'INSTALLED_HEALTHY' || row.state === 'INSTALLED_UNHEALTHY'">
            <el-tooltip v-if="!operations[row.code]" content="该组件由外部安装，平台不接管" placement="top">
              <span class="cc-readonly">只读</span>
            </el-tooltip>
            <!-- 有可升级版本才给按钮；只有一个版本时不是"能升级"，说清楚 -->
            <el-button v-else-if="upgradeTargets(row).length" link type="primary"
                       :loading="props.applying === row.code" @click="openUpgrade(row)">
              升级
            </el-button>
            <el-tooltip v-else content="平台内置的就是当前版本，暂无可升级版本" placement="top">
              <span class="cc-readonly">已是最新</span>
            </el-tooltip>
          </template>
          <!-- UNKNOWN / 无 recipe：不给装 —— "不知道"不是"没有"；没有 recipe 就明确说"暂不支持" -->
          <span v-else-if="row.state === 'UNKNOWN'" class="cc-readonly">—</span>
          <span v-else class="cc-readonly">暂不支持</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="upgradeDialog.visible" title="选择升级版本" width="360px" append-to-body>
      <div class="cc-upgrade-tip">
        当前 chart 版本：<code>{{ installedChartVersion(upgradeDialog.code) ?? '未知' }}</code>
      </div>
      <el-radio-group v-model="upgradeDialog.version">
        <el-radio v-for="v in upgradeDialog.versions" :key="v" :value="v" class="cc-upgrade-option">
          {{ v }}
        </el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="upgradeDialog.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!upgradeDialog.version" @click="confirmUpgrade">升级</el-button>
      </template>
    </el-dialog>
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
.cc-detail { font-style: italic; }
.cc-readonly { font-size: 12px; color: var(--text-3); }
</style>

<style>
/* ⚠️ el-dialog 内部节点没有 data-v-*，scoped 匹配不到 —— 非 scoped + 独有前缀（本仓既有约定） */
.cc-upgrade-tip { font-size: 13px; color: var(--text-2); margin-bottom: 10px; }
.cc-upgrade-option { display: block; margin: 6px 0; }
</style>
