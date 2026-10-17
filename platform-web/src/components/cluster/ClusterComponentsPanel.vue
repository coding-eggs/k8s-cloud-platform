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
import { ElMessage } from 'element-plus'
import type {
  K8sCalicoInstall, K8sComponentOperation, K8sComponentState, K8sComponentStatus, K8sEditableField,
  K8sThanosInstall, SecretRefOption,
} from '@/types'

const props = defineProps<{
  statuses: K8sComponentStatus[]
  loading: boolean
  /** 「重新探测」需要 platform:cluster:manage（写操作） */
  canRefresh: boolean
  /** 各组件最近一次操作（由页面统一加载） */
  operations: Record<string, K8sComponentOperation>
  /** 正在执行的组件 code（按钮 loading） */
  applying?: string | null
  /** 命名空间候选（Thanos 安装表单用；由页面提供，面板不发请求） */
  loadNamespaces?: () => Promise<string[]>
  /** 某命名空间下的 Secret 候选（name + keys；凭证数据不会下发） */
  loadSecretOptions?: (namespace: string) => Promise<SecretRefOption[]>
}>()
const emit = defineEmits<{
  (e: 'refresh'): void
  (e: 'apply', payload: {
    code: string
    action: 'INSTALL' | 'UPGRADE'
    targetVersion?: string
    editableValues?: Record<string, unknown>
    /** 平台告警回调地址（仅 platformAlerting 的组件） */
    alertWebhookUrl?: string
    /** Thanos Receive 地址（仅 thanosRemoteWrite 的组件） */
    thanosReceiveUrl?: string
    /** Thanos 安装参数（仅 thanosInstall 的组件，管理集群） */
    thanosInstall?: K8sThanosInstall
    /** Calico 网络参数（仅 calicoNetwork 的组件） */
    calicoInstall?: K8sCalicoInstall
  }): void
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

interface ApplyDialog {
  visible: boolean
  code: string
  name: string
  action: 'INSTALL' | 'UPGRADE'
  version: string
  versions: string[]
  fields: K8sEditableField[]
  /** 非 JSON 字段的当前值（动态类型，模板按 type 渲染不同控件） */
  values: Record<string, any>
  /** JSON 字段的文本（textarea 里编辑，提交时解析） */
  jsonText: Record<string, string>
  /**
   * 打开时的初始值快照。提交时**只发改动过的字段** —— 未改的沿用后端合并规则里的
   * 「上次快照」基线，否则每次升级都会把用户上次改的值重置成 recipe 默认值。
   */
  initial: Record<string, string>
  /** 该组件装完是否要下发告警联动（Prometheus）：显示回调地址输入框 */
  showWebhook: boolean
  webhookUrl: string
  /** 打开时的预填值：没改就不提交，后端会沿用上次成功操作里记的地址 */
  initialWebhook: string
  /** 该组件是否需要 Thanos Receive 地址（Prometheus 的 remoteWrite 目标） */
  showThanos: boolean
  thanosUrl: string
  initialThanos: string
  /** 该组件是否同时安装 Thanos（管理集群的 Prometheus） */
  showThanosInstall: boolean
  thanos: K8sThanosInstall
  initialThanosInstall: string
  namespaceOptions: string[]
  secretOptions: SecretRefOption[]
  /** 该组件是否要填 Calico 网络参数（CIDR / 封装方式） */
  showCalico: boolean
  calico: K8sCalicoInstall
  initialCalico: string
}

/** Calico 支持的封装方式（Installation CR 的取值） */
const ENCAPSULATIONS = ['IPIP', 'VXLAN', 'IPIPCrossSubnet', 'VXLANCrossSubnet', 'None']

const EMPTY_THANOS: K8sThanosInstall = {
  namespace: 'monitor', secretName: '', endpoint: '', bucket: '', insecureSkipVerify: false,
}

const applyDialog = ref<ApplyDialog>({
  visible: false, code: '', name: '', action: 'INSTALL',
  version: '', versions: [], fields: [], values: {}, jsonText: {}, initial: {},
  showWebhook: false, webhookUrl: '', initialWebhook: '',
  showThanos: false, thanosUrl: '', initialThanos: '',
  showThanosInstall: false, thanos: { ...EMPTY_THANOS },
  initialThanosInstall: JSON.stringify(EMPTY_THANOS),
  namespaceOptions: [], secretOptions: [],
  showCalico: false, calico: { podCidr: '', encapsulation: 'IPIP' },
  initialCalico: '',
})

function openInstall(row: K8sComponentStatus): void {
  openApply(row, 'INSTALL')
}

function openUpgrade(row: K8sComponentStatus): void {
  openApply(row, 'UPGRADE')
}

function openApply(row: K8sComponentStatus, action: 'INSTALL' | 'UPGRADE'): void {
  const versions = action === 'UPGRADE' ? upgradeTargets(row) : []
  if (action === 'UPGRADE' && !versions.length) return
  const fields = row.editableFields ?? []
  const values: Record<string, any> = {}
  const jsonText: Record<string, string> = {}
  const initial: Record<string, string> = {}
  for (const f of fields) {
    if (f.type === 'json') {
      const text = JSON.stringify(f.defaultValue ?? {}, null, 2)
      jsonText[f.key] = text
      initial[f.key] = text
    } else {
      values[f.key] = f.defaultValue
      initial[f.key] = JSON.stringify(f.defaultValue)
    }
  }
  const thanos: K8sThanosInstall = { ...EMPTY_THANOS }
  const calico: K8sCalicoInstall = {
    podCidr: row.defaultPodCidr ?? '',
    encapsulation: row.defaultEncapsulation || 'IPIP',
  }
  applyDialog.value = {
    visible: true, code: row.code, name: row.name, action,
    version: versions[0] ?? '', versions, fields, values, jsonText, initial,
    showWebhook: !!row.platformAlerting,
    webhookUrl: row.defaultAlertWebhookUrl ?? '',
    initialWebhook: (row.defaultAlertWebhookUrl ?? '').trim(),
    showThanos: !!row.thanosRemoteWrite,
    thanosUrl: row.defaultThanosReceiveUrl ?? '',
    initialThanos: (row.defaultThanosReceiveUrl ?? '').trim(),
    showThanosInstall: !!row.thanosInstall,
    thanos,
    initialThanosInstall: JSON.stringify(thanos),
    namespaceOptions: [], secretOptions: [],
    showCalico: !!row.calicoNetwork,
    calico,
    initialCalico: JSON.stringify(calico),
  }
  if (row.thanosInstall) {
    void loadThanosOptions()
  }
}

/** Thanos 表单的命名空间/Secret 候选：懒加载，失败就留空（用户可以手输） */
async function loadThanosOptions(): Promise<void> {
  const d = applyDialog.value
  if (!d.showThanosInstall) return
  try {
    d.namespaceOptions = (await props.loadNamespaces?.()) ?? []
  } catch {
    d.namespaceOptions = []
  }
  await refreshSecretOptions()
}

async function refreshSecretOptions(): Promise<void> {
  const d = applyDialog.value
  if (!d.showThanosInstall || !d.thanos.namespace) {
    d.secretOptions = []
    return
  }
  try {
    d.secretOptions = (await props.loadSecretOptions?.(d.thanos.namespace)) ?? []
  } catch {
    d.secretOptions = []
  }
}

function confirmApply(): void {
  const d = applyDialog.value
  const editable: Record<string, unknown> = {}
  for (const f of d.fields) {
    if (f.type === 'json') {
      const text = d.jsonText[f.key] ?? ''
      if (text === d.initial[f.key]) continue
      try {
        editable[f.key] = JSON.parse(text)
      } catch {
        ElMessage.error(`「${f.label}」不是合法 JSON`)
        return
      }
    } else {
      const value = d.values[f.key]
      if (JSON.stringify(value) === d.initial[f.key]) continue
      editable[f.key] = value
    }
  }
  d.visible = false
  emit('apply', {
    code: d.code,
    action: d.action,
    targetVersion: d.action === 'UPGRADE' ? d.version : undefined,
    editableValues: Object.keys(editable).length ? editable : undefined,
    // 两个地址：只发改动过的 —— 没改的由后端沿用上次成功操作里记的值（否则升级会被平台默认值覆盖）
    alertWebhookUrl: d.showWebhook && d.webhookUrl.trim() && d.webhookUrl.trim() !== d.initialWebhook
      ? d.webhookUrl.trim() : undefined,
    thanosReceiveUrl: d.showThanos && d.thanosUrl.trim() && d.thanosUrl.trim() !== d.initialThanos
      ? d.thanosUrl.trim() : undefined,
    // Thanos 安装参数：整组没改动就不发，后端沿用上次成功操作里记的
    thanosInstall: d.showThanosInstall && JSON.stringify(d.thanos) !== d.initialThanosInstall
      ? { ...d.thanos } : undefined,
    calicoInstall: d.showCalico && JSON.stringify(d.calico) !== d.initialCalico
      ? { ...d.calico } : undefined,
  })
}

/** 操作状态 → 色标（列与详情弹窗共用） */
const OP_STATUS: Record<K8sComponentOperation['status'], {
  label: string
  type: 'success' | 'info' | 'warning' | 'danger'
}> = {
  PENDING: { label: '排队中', type: 'info' },
  RUNNING: { label: '进行中', type: 'warning' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  UNKNOWN: { label: '中断', type: 'info' },
}

/** 上次操作色标（模板里 Safe 访问，避免 Record 索引的 undefined 类型） */
function opTag(code: string): {
  present: boolean
  label: string
  type: 'success' | 'info' | 'warning' | 'danger'
  tooltip: string
} {
  const op = props.operations[code]
  if (!op) {
    return { present: false, label: '', type: 'info', tooltip: '' }
  }
  const meta = OP_STATUS[op.status]
  return { present: true, label: meta.label, type: meta.type,
    tooltip: `${op.finishedAt ?? '进行中'} · ${op.operator ?? ''}` }
}

const operationDialog = ref<{
  visible: boolean
  name: string
  op: K8sComponentOperation | null
}>({ visible: false, name: '', op: null })

function openOperation(row: K8sComponentStatus): void {
  const op = props.operations[row.code]
  if (!op) return
  operationDialog.value = { visible: true, name: row.name, op }
}

/** 复制 helm 输出：http 内网下 navigator.clipboard 不存在，必须带 execCommand 兜底 */
async function copyLog(): Promise<void> {
  const text = operationDialog.value.op?.logTail ?? ''
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      document.body.removeChild(area)
    }
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
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
      <el-table-column label="上次操作" width="100">
        <template #default="{ row }">
          <el-tooltip v-if="opTag(row.code).present" :content="opTag(row.code).tooltip" placement="top">
            <el-tag size="small" effect="plain" class="cc-op-tag"
                    :type="opTag(row.code).type"
                    @click="openOperation(row)">
              {{ opTag(row.code).label }}
            </el-tag>
          </el-tooltip>
          <span v-else class="cc-readonly">—</span>
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
                       @click="openInstall(row)">
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

    <el-dialog v-model="applyDialog.visible" width="480px" append-to-body
               :title="`${applyDialog.action === 'INSTALL' ? '安装' : '升级'} ${applyDialog.name}`">
      <div v-if="applyDialog.action === 'UPGRADE'" class="cc-form-row">
        <span class="cc-form-label">目标版本</span>
        <div class="cc-form-control">
          <div class="cc-upgrade-tip">
            当前 chart 版本：<code>{{ installedChartVersion(applyDialog.code) ?? '未知' }}</code>
          </div>
          <el-radio-group v-model="applyDialog.version">
            <el-radio v-for="v in applyDialog.versions" :key="v" :value="v" class="cc-upgrade-option">
              {{ v }}
            </el-radio>
          </el-radio-group>
        </div>
      </div>
      <div v-if="applyDialog.showCalico" class="cc-form-row">
        <span class="cc-form-label">Pod CIDR</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.calico.podCidr"
                    placeholder="192.168.0.0/16" spellcheck="false" />
          <div class="cc-form-hint">必须与集群现有 Pod CIDR 一致；写错会立即中断集群网络</div>
        </div>
      </div>
      <div v-if="applyDialog.showCalico" class="cc-form-row">
        <span class="cc-form-label">封装方式</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.calico.encapsulation" style="width: 100%">
            <el-option v-for="e in ENCAPSULATIONS" :key="e" :label="e" :value="e" />
          </el-select>
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">凭证 Secret 所在命名空间</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.thanos.namespace" filterable allow-create style="width: 100%"
                     @change="() => refreshSecretOptions()">
            <el-option v-for="ns in applyDialog.namespaceOptions" :key="ns" :label="ns" :value="ns" />
          </el-select>
          <div class="cc-form-hint">凭证可以放在任意命名空间；Thanos 本身装在 monitor</div>
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">凭证 Secret</span>
        <div class="cc-form-control">
          <el-select v-model="applyDialog.thanos.secretName" filterable allow-create style="width: 100%"
                     placeholder="含 access key / secret key 的 Secret">
            <el-option v-for="s in applyDialog.secretOptions" :key="s.name" :label="s.name" :value="s.name" />
          </el-select>
          <div class="cc-form-hint">
            凭证不出集群：平台读出后拼成 objstore.yml。
            支持 access-key+secret-key / MINIO_ACCESS_KEY+MINIO_SECRET_KEY 等组合
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">MinIO 地址</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.thanos.endpoint"
                    placeholder="minio.default.svc:9000" spellcheck="false" />
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">MinIO Bucket</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.thanos.bucket" placeholder="thanos" spellcheck="false" />
        </div>
      </div>
      <div v-if="applyDialog.showThanosInstall" class="cc-form-row">
        <span class="cc-form-label">跳过 HTTPS 校验</span>
        <div class="cc-form-control">
          <el-switch v-model="applyDialog.thanos.insecureSkipVerify" />
        </div>
      </div>
      <div v-if="applyDialog.showThanos" class="cc-form-row">
        <span class="cc-form-label">Thanos Receive 地址</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.thanosUrl"
                    placeholder="http://thanos-receive:19291/api/v1/receive" spellcheck="false" />
          <div class="cc-form-hint">
            Prometheus 的 remoteWrite 目标（管理集群 Thanos）；集群网络需可达
          </div>
        </div>
      </div>
      <div v-if="applyDialog.showWebhook" class="cc-form-row">
        <span class="cc-form-label">平台告警回调地址</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.webhookUrl"
                    placeholder="http://<平台地址>:8081/alert/webhook" spellcheck="false" />
          <div class="cc-form-hint">
            集群里的 Alertmanager 需要能访问到；后端会自动追加 ?clusterId=…
          </div>
        </div>
      </div>
      <div v-for="f in applyDialog.fields" :key="f.key" class="cc-form-row">
        <span class="cc-form-label">{{ f.label }}</span>
        <div class="cc-form-control">
          <el-switch v-if="f.type === 'boolean'" v-model="applyDialog.values[f.key]" />
          <el-input-number v-else-if="f.type === 'number'" v-model="applyDialog.values[f.key]" :min="0" />
          <el-input v-else-if="f.type === 'json'" v-model="applyDialog.jsonText[f.key]"
                    type="textarea" :rows="5" spellcheck="false" />
          <el-input v-else v-model="applyDialog.values[f.key]" />
        </div>
      </div>
      <div v-if="!applyDialog.fields.length && applyDialog.action === 'INSTALL'" class="cc-upgrade-tip">
        该组件没有可编辑配置，将使用平台默认值安装。
      </div>
      <template #footer>
        <el-button @click="applyDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmApply">确定</el-button>
      </template>
    </el-dialog>

    <!-- 上次操作详情：失败原因 + helm 输出（排查日志的唯一入口） -->
    <el-dialog v-model="operationDialog.visible" :title="`操作详情 · ${operationDialog.name}`"
               width="760px" append-to-body>
      <template v-if="operationDialog.op">
        <div class="cc-op-meta">
          <span>动作：{{ operationDialog.op.action === 'INSTALL' ? '安装' : '升级' }}</span>
          <span>chart 版本：{{ operationDialog.op.fromVersion || '—' }} → {{ operationDialog.op.toVersion || '—' }}</span>
          <span>结果：
            <el-tag size="small" :type="OP_STATUS[operationDialog.op.status].type">
              {{ OP_STATUS[operationDialog.op.status].label }}
            </el-tag>
          </span>
          <span>操作人：{{ operationDialog.op.operator || '—' }}</span>
          <span>时间：{{ operationDialog.op.startedAt || '—' }} ~ {{ operationDialog.op.finishedAt || '未结束' }}</span>
        </div>
        <div v-if="operationDialog.op.message" class="cc-op-message">{{ operationDialog.op.message }}</div>
        <div class="cc-op-log-head">
          <span>helm 输出</span>
          <el-button size="small" link type="primary" :disabled="!operationDialog.op.logTail"
                     @click="copyLog">复制</el-button>
        </div>
        <pre class="cc-op-log">{{ operationDialog.op.logTail || '（无输出）' }}</pre>
      </template>
      <template #footer>
        <el-button @click="operationDialog.visible = false">关闭</el-button>
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
.cc-form-row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 14px; }
.cc-form-label { flex: 0 0 150px; font-size: 13px; color: var(--text-2); padding-top: 6px; }
.cc-form-control { flex: 1 1 auto; min-width: 0; }
.cc-form-hint { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.cc-op-tag { cursor: pointer; }
.cc-op-meta { display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 13px; color: var(--text-2); margin-bottom: 10px; }
.cc-op-message { font-size: 13px; color: var(--danger); margin-bottom: 10px; white-space: pre-wrap; }
.cc-op-log-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 13px; color: var(--text-2); margin-bottom: 6px;
}
.cc-op-log {
  max-height: 380px; overflow: auto; margin: 0; padding: 10px; border-radius: 6px;
  background: var(--bg); font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
</style>
