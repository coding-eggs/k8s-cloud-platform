<script setup lang="ts">
/**
 * 灰度管理（应用页服务列表「管理灰度」抽屉，spec §9）：未灰度时说明 + 开始；灰度中时权重 / header、指标对比、两个版本的 Pod、全量 / 回退。
 * 平台只给指标，好不好由租户自己判断：canary 错误率比 stable 高 1 个百分点以上时数字标橙，不拦操作。
 * 内部灰度（设计 2026-11-09-app-internal-canary-design.md）：应用为 L7 网格时集群内调用也按同一权重分流；
 * 有网关暴露时「外部流量参与灰度」开关决定网关流量是否分流；指标分「内部调用」「网关进来」两组。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { appCanaryApi, podApi, workloadApi } from '@/api'
import { podLabelSelector } from '@/utils/podSelector'
import { podPhaseType } from '@/utils/format'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import AppCanaryStartDialog from './AppCanaryStartDialog.vue'
import { serviceProtocol } from './releaseUtil'
import type { AppCanaryMetrics, AppCanaryMetricsGroup, AppDependencyRow, AppServiceDesired, AppServiceRow, K8sPod } from '@/types'

const props = defineProps<{
  appId: string
  tenantId?: string
  slug: string
  row: AppServiceRow
  /** 线上快照里本服务的期望配置（灰度中带 canary 块） */
  liveDesired: AppServiceDesired | null
  dependencies: AppDependencyRow[]
  serviceKeys: string[]
  ctx: { tenantId: string; clusterId: string; namespace: string }
  canWrite: boolean
  /** 应用有进行中的执行：按钮全部禁用 */
  busy: boolean
  /** 应用线上设置为 L7 网格（集群内调用分流） */
  l7: boolean
}>()
const emit = defineEmits<{ (e: 'changed'): void }>()

interface CanaryBlock { desired: AppServiceDesired; replicas: number; weight: number; header: { name: string; value: string } | null }
const block = computed<CanaryBlock | null>(() => {
  const c = (props.liveDesired as Record<string, unknown> | null)?.canary
  return c && typeof c === 'object' ? c as CanaryBlock : null
})
const inCanary = computed(() => !!props.row.canary)
const stable = computed<AppServiceDesired>(() => {
  const d = { ...(props.liveDesired ?? props.row.desired) } as Record<string, unknown>
  delete d.canary
  return d as AppServiceDesired
})
const protocol = computed<'HTTP' | 'TCP'>(() => serviceProtocol(stable.value))
/** 有网关暴露：网关流量可参与灰度 */
const exposed = computed(() => stable.value.expose === 'gateway-http' || stable.value.expose === 'gateway-tcp')
/** 「外部流量参与」开关：只在 L7 且有网关暴露时可调（非 L7 时网关流量是唯一分流的部分，固定参与） */
const externalSwitchable = computed(() => props.l7 && exposed.value)
/** 当前分流范围说明 */
const scopeText = computed(() => {
  const ext = exposed.value && (props.row.canary?.external ?? true)
  if (!props.l7) return '仅网关进来的流量分流（应用未开启 L7 网格，集群内调用不分流、全部进 stable）'
  return ext ? '集群内调用与网关进来的流量都按权重分流' : '仅集群内调用分流（网关进来的固定进 stable）'
})

/** 开始灰度不可用的原因；空串 = 可以开始 */
const startBlocked = computed(() => {
  if (!props.canWrite) return '没有应用写权限'
  if (props.busy) return '有进行中的执行，结束后再操作'
  if (props.row.state === 'NOT_RELEASED' || !props.liveDesired) return '服务尚未上线'
  if (!props.l7 && !exposed.value) return '应用未开启 L7 网格（在应用设置里开启并选择 waypoint），且服务没有网关暴露'
  if (props.liveDesired.persistenceEnabled) return '有状态服务（开启了持久化）不能灰度'
  return ''
})
const actBlocked = computed(() => (!props.canWrite ? '没有应用写权限' : props.busy ? '有进行中的执行，结束后再操作' : ''))

// ---------- 开始 / 修改配置 ----------
const dlgVisible = ref(false)
const dlgMode = ref<'start' | 'edit'>('start')
function openStart(): void { dlgMode.value = 'start'; dlgVisible.value = true }
function openEdit(): void { dlgMode.value = 'edit'; dlgVisible.value = true }

// ---------- 权重 / header ----------
const weight = ref(10)
const headerOn = ref(false)
const headerName = ref('x-canary')
const headerValue = ref('true')
const external = ref(true)
function syncFromLive(): void {
  external.value = props.row.canary?.external ?? true
  weight.value = props.row.canary?.weight ?? 10
  headerOn.value = !!props.row.canary?.headerName
  headerName.value = props.row.canary?.headerName ?? 'x-canary'
  headerValue.value = props.row.canary?.headerValue ?? 'true'
}
watch(() => props.row.canary, syncFromLive, { immediate: true })
const routeDirty = computed(() => {
  const c = props.row.canary
  if (!c) return false
  if (weight.value !== c.weight) return true
  if (externalSwitchable.value && external.value !== c.external) return true
  if (headerOn.value !== !!c.headerName) return true
  return headerOn.value && (headerName.value !== c.headerName || headerValue.value !== c.headerValue)
})
const applying = ref(false)
async function applyRoute(): Promise<void> {
  applying.value = true
  try {
    await appCanaryApi.update({
      appId: props.appId, serviceKey: props.row.key, tenantId: props.tenantId,
      weight: weight.value,
      headerEnabled: protocol.value === 'HTTP' && headerOn.value,
      headerName: headerOn.value ? headerName.value.trim() || undefined : undefined,
      headerValue: headerOn.value ? headerValue.value : undefined,
      external: externalSwitchable.value ? external.value : undefined,
    })
    ElMessage.success('已提交，正在执行')
    emit('changed')
  } catch {
    /* 拦截器已提示 */
  } finally {
    applying.value = false
  }
}

// ---------- 全量 / 回退 ----------
async function promote(): Promise<void> {
  try {
    await ElMessageBox.confirm(`全量：灰度版本（${props.row.canary?.image}）成为线上版本，删除灰度版本。`, '全量',
      { type: 'warning', confirmButtonText: '全量', cancelButtonText: '取消' })
  } catch { return }
  try {
    await appCanaryApi.promote({ appId: props.appId, serviceKey: props.row.key, tenantId: props.tenantId })
    ElMessage.success('已提交全量，正在执行')
    emit('changed')
  } catch { /* 拦截器已提示 */ }
}
async function abort(): Promise<void> {
  try {
    await ElMessageBox.confirm('回到灰度开始前：stable 恢复为灰度前的配置，删除灰度版本。', '回退',
      { type: 'warning', confirmButtonText: '回退', cancelButtonText: '取消' })
  } catch { return }
  try {
    await appCanaryApi.abort({ appId: props.appId, serviceKey: props.row.key, tenantId: props.tenantId })
    ElMessage.success('已提交回退，正在执行')
    emit('changed')
  } catch { /* 拦截器已提示 */ }
}

// ---------- 指标 ----------
const win = ref('15m')
const metrics = ref<AppCanaryMetrics | null>(null)
const metricsLoading = ref(false)
let timer: number | null = null
async function loadMetrics(): Promise<void> {
  if (!inCanary.value) return
  metricsLoading.value = true
  try {
    metrics.value = await appCanaryApi.metrics({
      appId: props.appId, serviceKey: props.row.key, window: win.value, tenantId: props.tenantId,
    })
  } catch {
    metrics.value = null
  } finally {
    metricsLoading.value = false
  }
}
function restartTimer(): void {
  if (timer != null) { window.clearInterval(timer); timer = null }
  if (inCanary.value) timer = window.setInterval(() => { void loadMetrics() }, 30_000)
}
watch([inCanary, win], () => { void loadMetrics(); restartTimer() }, { immediate: true })
onBeforeUnmount(() => { if (timer != null) window.clearInterval(timer) })

interface MetricRow { label: string; key: string; fmt: (v: number) => string }
const pct = (v: number): string => `${(v * 100).toFixed(2)}%`
const num = (v: number): string => (v >= 100 ? v.toFixed(0) : v.toFixed(2))
const metricRows = computed<MetricRow[]>(() => (protocol.value === 'TCP'
  ? [
      { label: '新建连接/秒', key: 'cps', fmt: num },
      { label: '收发字节/秒', key: 'bytes', fmt: (v) => (v >= 1024 * 1024 ? `${(v / 1024 / 1024).toFixed(2)} MiB` : v >= 1024 ? `${(v / 1024).toFixed(1)} KiB` : `${v.toFixed(0)} B`) },
    ]
  : [
      { label: '请求/秒', key: 'rps', fmt: num },
      { label: '错误率（5xx）', key: 'errorRate', fmt: pct },
      { label: 'P50 延迟', key: 'p50', fmt: (v) => `${num(v)} ms` },
      { label: 'P99 延迟', key: 'p99', fmt: (v) => `${num(v)} ms` },
    ]))
/** 两组来源：内部调用（L7 才有）、网关进来（有网关暴露才有） */
const groups = computed<{ key: string; title: string; data: AppCanaryMetricsGroup }[]>(() => {
  const out: { key: string; title: string; data: AppCanaryMetricsGroup }[] = []
  if (metrics.value?.internal) out.push({ key: 'internal', title: '内部调用', data: metrics.value.internal })
  if (metrics.value?.external) out.push({ key: 'external', title: '网关进来', data: metrics.value.external })
  return out
})
function cell(g: AppCanaryMetricsGroup, side: 'stable' | 'canary', r: MetricRow): string {
  const v = g[side]?.[r.key]
  return v == null ? '暂无数据' : r.fmt(v)
}
/** canary 错误率比 stable 高 1 个百分点以上：标橙（只提示） */
function errorWorse(g: AppCanaryMetricsGroup): boolean {
  const s = g.stable?.errorRate
  const c = g.canary?.errorRate
  return s != null && c != null && c - s > 0.01
}

// ---------- 两个版本的 Pod ----------
const pods = ref<{ stable: K8sPod[]; canary: K8sPod[] }>({ stable: [], canary: [] })
const podLoading = ref(false)
async function podsOf(name: string): Promise<K8sPod[]> {
  try {
    const d = await workloadApi.get(name, props.ctx)
    return (await podApi.list({ ...props.ctx, labelSelector: podLabelSelector(d.selector, name) })) ?? []
  } catch {
    return []
  }
}
async function loadPods(): Promise<void> {
  if (!inCanary.value) return
  podLoading.value = true
  const base = `${props.slug}-${props.row.key}`
  const [s, c] = await Promise.all([podsOf(base), podsOf(`${base}-canary`)])
  pods.value = { stable: s, canary: c }
  podLoading.value = false
}
watch(() => [inCanary.value, props.row.canary?.state, props.row.canary?.image], () => { void loadPods() }, { immediate: true })
const podRows = computed(() => [
  ...pods.value.stable.map((p) => ({ ...p, version: 'stable' })),
  ...pods.value.canary.map((p) => ({ ...p, version: 'canary' })),
])
function readyCount(p: K8sPod): string {
  const cs = (p.containerDetails ?? []).filter((c) => !c.init)
  return `${cs.filter((c) => c.ready).length}/${cs.length}`
}
</script>

<template>
  <div class="app-cny">
    <template v-if="!inCanary">
      <section class="panel app-cny__card">
        <p>入口灰度：再起一个灰度版本，按权重（HTTP 还可按请求头）把<strong>网关进来</strong>的一部分流量分给它；
          看指标没问题就「全量」，有问题就「回退」到灰度开始前。灰度期间这个服务的配置锁定。</p>
        <el-tooltip :disabled="!startBlocked" :content="startBlocked" placement="top">
          <span><el-button type="primary" :disabled="!!startBlocked" @click="openStart">开始灰度</el-button></span>
        </el-tooltip>
      </section>
    </template>

    <template v-else-if="row.canary">
      <el-alert type="info" :closable="false" show-icon class="app-cny__alert" :title="scopeText" />

      <section class="panel app-cny__card">
        <h4>流量</h4>
        <div class="app-cny__kv"><span class="k">灰度版本</span><code>{{ row.canary.image }}</code>
          <span class="muted">{{ row.canary.replicas }} 副本 · 状态 {{ row.canary.state }}</span></div>
        <div class="app-cny__kv"><span class="k">权重</span>
          <el-slider v-model="weight" :min="0" :max="100" :disabled="!!actBlocked" class="app-cny__slider" />
          <span><b>{{ weight }}%</b> 进灰度</span>
        </div>
        <div class="app-cny__kv"><span class="k"></span>
          <el-button v-for="w in [0, 10, 25, 50, 100]" :key="w" size="small" :disabled="!!actBlocked" @click="weight = w">{{ w }}%</el-button>
        </div>
        <div v-if="externalSwitchable" class="app-cny__kv"><span class="k">外部流量</span>
          <el-switch v-model="external" :disabled="!!actBlocked" />
          <span class="muted">{{ external ? '网关进来的请求也按权重分流' : '网关进来的请求固定进 stable，权重只作用于集群内调用' }}</span>
        </div>
        <div v-if="protocol === 'HTTP'" class="app-cny__kv"><span class="k">header 直达</span>
          <el-switch v-model="headerOn" :disabled="!!actBlocked" />
          <template v-if="headerOn">
            <el-input v-model="headerName" size="small" class="app-cny__hdr" :disabled="!!actBlocked" />
            <span>:</span>
            <el-input v-model="headerValue" size="small" class="app-cny__hdr" :disabled="!!actBlocked" />
          </template>
          <span class="muted">带此请求头的请求总是进灰度（优先于权重）{{ l7 ? '；集群内调用需调用方把这个请求头往下传' : '' }}</span>
        </div>
        <div class="app-cny__kv"><span class="k"></span>
          <el-tooltip :disabled="!actBlocked" :content="actBlocked" placement="top">
            <span><el-button type="primary" :loading="applying" :disabled="!!actBlocked || !routeDirty" @click="applyRoute">应用</el-button></span>
          </el-tooltip>
          <el-button :disabled="!routeDirty" @click="syncFromLive">还原</el-button>
        </div>
      </section>

      <section class="panel app-cny__card">
        <div class="app-cny__head">
          <h4>指标对比</h4>
          <el-radio-group v-model="win" size="small">
            <el-radio-button value="5m">5 分钟</el-radio-button>
            <el-radio-button value="15m">15 分钟</el-radio-button>
            <el-radio-button value="1h">1 小时</el-radio-button>
          </el-radio-group>
          <el-button size="small" :loading="metricsLoading" @click="loadMetrics">刷新</el-button>
          <span class="muted">每 30 秒自动刷新；内部调用来自 waypoint，网关进来来自入口网关</span>
        </div>
        <div v-if="!groups.length" class="muted">暂无数据</div>
        <div v-for="g in groups" :key="g.key" class="app-cny__group">
          <div class="app-cny__group-title">{{ g.title }}</div>
          <el-table :data="metricRows" size="small">
            <el-table-column label="指标" prop="label" width="160" />
            <el-table-column label="stable">
              <template #default="{ row: r }">{{ cell(g.data, 'stable', r) }}</template>
            </el-table-column>
            <el-table-column label="canary">
              <template #default="{ row: r }">
                <span :class="{ 'app-cny__worse': r.key === 'errorRate' && errorWorse(g.data) }">{{ cell(g.data, 'canary', r) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <section class="panel app-cny__card">
        <div class="app-cny__head">
          <h4>两个版本的 Pod</h4>
          <el-button size="small" :loading="podLoading" @click="loadPods">刷新</el-button>
        </div>
        <el-table v-loading="podLoading" :data="podRows" size="small" empty-text="当前没有 Pod">
          <el-table-column label="版本" width="90">
            <template #default="{ row: p }"><el-tag size="small" :type="p.version === 'canary' ? 'primary' : 'info'" effect="light">{{ p.version }}</el-tag></template>
          </el-table-column>
          <el-table-column label="名称" min-width="260"><template #default="{ row: p }"><code>{{ p.name }}</code></template></el-table-column>
          <el-table-column label="就绪" width="80"><template #default="{ row: p }">{{ readyCount(p) }}</template></el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row: p }"><StatusBadgeTip :label="p.phase ?? 'Unknown'" :type="podPhaseType(p.phase)" :reason="p.statusReason" /></template>
          </el-table-column>
          <el-table-column label="重启" width="70"><template #default="{ row: p }">{{ p.restarts ?? 0 }}</template></el-table-column>
        </el-table>
        <div class="muted app-cny__tip">日志请到服务页「Pod 与日志」页签或资源页查看。</div>
      </section>

      <section class="panel app-cny__card app-cny__ops">
        <el-tooltip :disabled="!actBlocked" :content="actBlocked" placement="top">
          <span class="app-cny__btns">
            <el-button :disabled="!!actBlocked" @click="openEdit">修改灰度版本配置</el-button>
            <el-button type="success" :disabled="!!actBlocked" @click="promote">全量</el-button>
            <el-button type="danger" plain :disabled="!!actBlocked" @click="abort">回退</el-button>
          </span>
        </el-tooltip>
      </section>
    </template>

    <AppCanaryStartDialog
      v-model="dlgVisible"
      :mode="dlgMode"
      :app-id="appId"
      :tenant-id="tenantId"
      :service-key="row.key"
      :stable="stable"
      :canary-desired="block?.desired ?? null"
      :protocol="protocol"
      :l7="l7"
      :exposed="exposed"
      :dependencies="dependencies"
      :service-keys="serviceKeys"
      :ctx="ctx"
      @submitted="emit('changed')"
    />
  </div>
</template>

<style scoped>
.muted { color: var(--text-3); font-size: 12.5px; }
.app-cny__alert { margin-bottom: 12px; }
.app-cny__card { padding: 14px 18px; margin-bottom: 12px; }
.app-cny__card h4 { margin: 0 0 10px; }
.app-cny__head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.app-cny__head h4 { margin: 0; }
.app-cny__kv { display: flex; align-items: center; gap: 10px; padding: 6px 0; font-size: 13.5px; }
.app-cny__kv .k { width: 90px; flex-shrink: 0; color: var(--text-3); }
.app-cny__slider { width: 320px; }
.app-cny__hdr { width: 140px; }
.app-cny__worse { color: var(--el-color-warning); font-weight: 600; }
.app-cny__tip { margin-top: 6px; }
.app-cny__group + .app-cny__group { margin-top: 12px; }
.app-cny__group-title { margin-bottom: 6px; font-size: 13px; font-weight: 600; }
.app-cny__btns { display: inline-flex; gap: 8px; }
</style>
