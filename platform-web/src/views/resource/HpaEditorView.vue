<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hpaApi, workloadApi } from '@/api'
import type { K8sHpa, K8sHpaBehavior, K8sHpaBehaviorRule, K8sHpaMetric, K8sHpaMetricTarget } from '@/types'
import type { WorkloadDetail } from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import { useClusterCapability } from '@/composables/useClusterCapability'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>(route.query.name as string | null)
/** ?targetKind&targetName → 预选目标（WorkloadView 传来的是小写原值；可切换，用户裁决） */
const preTargetKind = ((route.query.targetKind as string | null) ?? '').trim().toLowerCase()
const preTargetName = ((route.query.targetName as string | null) ?? '').trim()

const ctx3 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))
const ctx2 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
}))

// ---------- 集群 API 能力（指标类型 / behavior 门禁） ----------
// useClusterCapability 返回普通对象：模板里也必须显式 .value（漏掉会让门禁静默恒真/恒假）
const cap = useClusterCapability(computed(() => state.clusterId))

/** canonical 键：小写 kind/name —— 全局不变量（Task 9 的 badge / 行操作按同一约定匹配） */
function canonicalKey(kind: string | null | undefined, name: string | null | undefined): string {
  return `${(kind ?? '').toLowerCase()}/${name ?? ''}`
}
/** 提交时小写 kind → K8s 大写 kind（仅组装 body 时转换，canonical 键恒为小写） */
const KIND_CAP: Record<string, string> = { deployment: 'Deployment', statefulset: 'StatefulSet' }
function kindLabel(kind?: string | null): string {
  switch (kind) {
    case 'deployment': return 'Deployment'
    case 'statefulset': return 'StatefulSet'
    case 'daemonset': return 'DaemonSet'
    default: return kind ?? '—'
  }
}

// ---------- 表单模型（指标 / behavior 行；自 HpaView 原样迁移） ----------
type MetricType = 'Resource' | 'ContainerResource' | 'Pods' | 'Object' | 'External'
interface MetricRow {
  type: MetricType
  // Resource / ContainerResource
  name: string
  container: string
  // target
  targetUtilizationType: 'Utilization' | 'AverageValue' | 'Value'
  targetAverageUtilization: number | null
  targetValue: string
  targetAverageValue: string
  // Pods / Object / External
  metricName: string
  // Object.describedObject
  objApiVersion: string
  objKind: string
  objName: string
  objNamespace: string
}

interface PolicyRow { type: 'Pods' | 'Percent'; value: number | null; periodSeconds: number | null }
interface BehaviorRule { stabilizationWindowSeconds: number | null; selectPolicy: string; policies: PolicyRow[] }

const METRIC_TYPES: MetricType[] = ['Resource', 'ContainerResource', 'Pods', 'Object', 'External']
const TARGET_TYPES = ['Utilization', 'AverageValue', 'Value']
const SELECT_POLICIES = ['Max', 'Min', 'Disabled']
const POLICY_TYPES = ['Percent', 'Pods']

function newMetricRow(): MetricRow {
  return {
    type: 'Resource', name: 'cpu', container: '',
    targetUtilizationType: 'Utilization', targetAverageUtilization: 70, targetValue: '', targetAverageValue: '',
    metricName: '', objApiVersion: '', objKind: '', objName: '', objNamespace: '',
  }
}
function newPolicyRow(): PolicyRow { return { type: 'Percent', value: 100, periodSeconds: 60 } }
function newBehaviorRule(): BehaviorRule { return { stabilizationWindowSeconds: null, selectPolicy: 'Max', policies: [] } }

const form = reactive({
  name: '',
  targetKey: '',
  minReplicas: null as number | null,
  maxReplicas: null as number | null,
  metrics: [] as MetricRow[],
  scaleUp: newBehaviorRule() as BehaviorRule,
  scaleDown: newBehaviorRule() as BehaviorRule,
})

/** 表单字符串 → 基础单位数字（后端 value/averageValue 现为 number）；空/非法 → undefined */
function toNum(s: string): number | undefined {
  const t = s.trim()
  if (t === '') return undefined
  const n = Number(t)
  return Number.isNaN(n) ? undefined : n
}

function k8sToRow(m: K8sHpaMetric): MetricRow {
  const row = newMetricRow()
  row.type = m.type
  let t: K8sHpaMetricTarget | null = null
  if (m.resource) t = m.resource.target ?? null
  else if (m.containerResource) t = m.containerResource.target ?? null
  else if (m.pods) t = m.pods.target ?? null
  else if (m.object) t = m.object.target ?? null
  else if (m.external) t = m.external.target ?? null
  if (t) {
    row.targetUtilizationType = (t.type as MetricRow['targetUtilizationType']) ?? 'Utilization'
    row.targetAverageUtilization = t.averageUtilization ?? null
    row.targetValue = t.value != null ? String(t.value) : ''
    row.targetAverageValue = t.averageValue != null ? String(t.averageValue) : ''
  }
  if (m.resource) row.name = m.resource.name ?? ''
  if (m.containerResource) { row.container = m.containerResource.container ?? ''; row.name = m.containerResource.name ?? '' }
  if (m.pods) row.metricName = m.pods.metricName ?? ''
  if (m.object) {
    row.metricName = m.object.metricName ?? ''
    row.objApiVersion = m.object.describedObject?.apiVersion ?? ''
    row.objKind = m.object.describedObject?.kind ?? ''
    row.objName = m.object.describedObject?.name ?? ''
    row.objNamespace = m.object.describedObject?.namespace ?? ''
  }
  if (m.external) row.metricName = m.external.metricName ?? ''
  return row
}

function k8sRuleToForm(r?: K8sHpaBehaviorRule | null): BehaviorRule {
  if (!r) return newBehaviorRule()
  return {
    stabilizationWindowSeconds: r.stabilizationWindowSeconds ?? null,
    selectPolicy: r.selectPolicy ?? 'Max',
    policies: (r.policies ?? []).map((p) => ({ type: p.type ?? 'Percent', value: p.value ?? null, periodSeconds: p.periodSeconds ?? null })),
  }
}

function addMetric(): void { form.metrics.push(newMetricRow()) }
function removeMetric(index: number): void { form.metrics.splice(index, 1) }
function onMetricTypeChange(row: MetricRow): void {
  // 切到 Resource/ContainerResource 时给个默认资源名，避免空
  if ((row.type === 'Resource' || row.type === 'ContainerResource') && !row.name) row.name = 'cpu'
}
function addPolicy(rule: BehaviorRule, type: 'scaleUp' | 'scaleDown'): void { rule.policies.push(newPolicyRow()) }
function removePolicy(rule: BehaviorRule, index: number): void { rule.policies.splice(index, 1) }

// ---------- 行 → payload ----------
function rowToK8s(row: MetricRow): K8sHpaMetric {
  const target: K8sHpaMetricTarget = {}
  if (row.targetUtilizationType === 'Utilization') {
    target.type = 'Utilization'
    target.averageUtilization = row.targetAverageUtilization ?? undefined
  } else if (row.targetUtilizationType === 'AverageValue') {
    target.type = 'AverageValue'
    target.value = toNum(row.targetValue)
  } else {
    target.type = 'Value'
    target.value = toNum(row.targetValue)
    target.averageValue = toNum(row.targetAverageValue)
  }
  switch (row.type) {
    case 'Resource':
      return { type: 'Resource', resource: { name: row.name.trim(), target } }
    case 'ContainerResource':
      return { type: 'ContainerResource', containerResource: { container: row.container.trim(), name: row.name.trim(), target } }
    case 'Pods':
      return { type: 'Pods', pods: { metricName: row.metricName.trim(), target } }
    case 'Object':
      return {
        type: 'Object',
        object: {
          describedObject: { apiVersion: row.objApiVersion.trim() || undefined, kind: row.objKind.trim(), name: row.objName.trim(), namespace: row.objNamespace.trim() || undefined },
          metricName: row.metricName.trim(), target,
        },
      }
    case 'External':
      return { type: 'External', external: { metricName: row.metricName.trim(), target } }
  }
}

function hasBehavior(): boolean {
  const any = (r: BehaviorRule) => r.stabilizationWindowSeconds != null || (r.policies?.length ?? 0) > 0
  return any(form.scaleUp) || any(form.scaleDown)
}

function ruleToK8s(r: BehaviorRule): K8sHpaBehaviorRule {
  const out: K8sHpaBehaviorRule = {}
  if (r.stabilizationWindowSeconds != null) out.stabilizationWindowSeconds = r.stabilizationWindowSeconds
  if (r.selectPolicy) out.selectPolicy = r.selectPolicy as K8sHpaBehaviorRule['selectPolicy']
  if (r.policies?.length) {
    out.policies = r.policies.map((p) => ({ type: p.type, value: p.value ?? undefined, periodSeconds: p.periodSeconds ?? undefined }))
  }
  return out
}

// ---------- 目标工作负载下拉（替代原 targetKind select + targetName 自由文本） ----------
const workloadOptions = ref<WorkloadDetail[]>([])
/** 本命名空间内已被其它 HPA 绑定的 canonical 键集合 */
const boundKeys = ref<Set<string>>(new Set())
const optsLoading = ref(false)
/** 自身键（预选 / 编辑态回填的原始目标）：从禁用集排除，否则编辑自己的负载时被误判「已绑定」 */
const ownKey = ref('')
/** 编辑态回填的 scaleTargetRef.kind 原始大小写（遗留绑定可能是 ReplicaSet 等下拉外 kind）；
 *  目标未变时提交按原样发送，避免 canonical 键小写化丢失 kind（数据保真，防控制器找不到目标不伸缩） */
const ownKind = ref('')

/** 一个工作负载只能绑定一个 HPA：已绑别人的项禁选 */
function isBound(w: WorkloadDetail): boolean {
  const k = canonicalKey(w.kind, w.name)
  return boundKeys.value.has(k) && k !== ownKey.value
}

/** 当前选择的 canonical 键拆出的 kind / name（编辑或预填时该负载可能不在下拉里 → 补一个回显项） */
const selectedKind = computed(() => form.targetKey.split('/')[0] ?? '')
const selectedName = computed(() => {
  const i = form.targetKey.indexOf('/')
  return i < 0 ? '' : form.targetKey.slice(i + 1)
})

interface TargetOption { value: string; label: string; bound: boolean }
/** 下拉项 = canonical 键；已绑别人的标 bound（模板里转 :disabled + 「已绑定」提示） */
const targetOptions = computed<TargetOption[]>(() => {
  const opts: TargetOption[] = workloadOptions.value.map((w) => ({
    value: canonicalKey(w.kind, w.name),
    label: `${w.name}（${kindLabel(w.kind)}）`,
    bound: isBound(w),
  }))
  const key = form.targetKey
  if (key && !opts.some((o) => o.value === key)) {
    opts.unshift({ value: key, label: `${selectedName.value}（${kindLabel(selectedKind.value)}）`, bound: false })
  }
  return opts
})

async function loadOptions(): Promise<void> {
  if (!ready.value) return
  optsLoading.value = true
  try {
    const [ws, hs] = await Promise.all([workloadApi.list(ctx3.value), hpaApi.list(ctx3.value)])
    workloadOptions.value = ws.filter((w) => w.kind === 'deployment' || w.kind === 'statefulset')
    boundKeys.value = new Set(hs.map((h) => canonicalKey(h.scaleTargetRef?.kind, h.scaleTargetRef?.name)))
  } catch {
    /* 拦截器已提示 */
  } finally {
    optsLoading.value = false
  }
}

// ---------- 编辑回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    // 列表行深度不足（不含完整 metrics/behavior）→ 必须走 get
    const d = await hpaApi.get(editing.value, ctx3.value)
    form.name = d.name
    ownKey.value = canonicalKey(d.scaleTargetRef?.kind, d.scaleTargetRef?.name)
    ownKind.value = d.scaleTargetRef?.kind ?? ''
    form.targetKey = ownKey.value
    form.minReplicas = d.minReplicas ?? null
    form.maxReplicas = d.maxReplicas ?? null
    form.metrics = (d.metrics ?? []).map(k8sToRow)
    if (form.metrics.length === 0) form.metrics = [newMetricRow()]
    form.scaleUp = k8sRuleToForm(d.behavior?.scaleUp)
    form.scaleDown = k8sRuleToForm(d.behavior?.scaleDown)
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

function initForm(): void {
  form.name = ''
  form.minReplicas = null
  form.maxReplicas = null
  form.metrics = [newMetricRow()]
  form.scaleUp = newBehaviorRule()
  form.scaleDown = newBehaviorRule()
  // 预选目标（小写原值 → canonical 键）；仍可切换，切换时其余选项继续受「已绑定」禁用约束
  const preKey = preTargetKind && preTargetName ? canonicalKey(preTargetKind, preTargetName) : ''
  form.targetKey = preKey
  ownKey.value = preKey
  ownKind.value = '' // 创建/预选路径无「原始大写 kind」可保真（query 传入即小写，D/S 经 KIND_CAP 还原）
}

onMounted(() => {
  void load()
  initForm()
  if (ready.value) { void loadOptions(); if (editing.value) void loadDetail() }
})
// 上下文晚于挂载才选齐时补拉（选项 + 自身占用键随之刷新）
watch(ready, (r) => {
  if (!r) return
  void loadOptions()
  if (editing.value && detailState.value === 'idle') void loadDetail()
})
// 新建态换集群/命名空间 → 目标与已绑定集失效，清空重选（编辑态目标恒等于自身，不受影响）
watch([() => state.clusterId, () => state.namespace], () => {
  if (!editing.value) { form.targetKey = ''; ownKey.value = '' }
})

const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

/** 未探测到任何 metrics API group：软提示（不硬阻断，五类仍全可选） */
const notProbedTip = computed(() => ready.value && !cap.loading.value && !cap.probed.value)

// ---------- 指标类型门禁：按真实依赖的 API group 精确禁用 ----------
const TYPE_GROUP: Record<MetricType, 'metrics' | 'custom' | 'external'> = {
  Resource: 'metrics', ContainerResource: 'metrics', Pods: 'custom', Object: 'custom', External: 'external',
}
const TYPE_HINT: Record<'metrics' | 'custom' | 'external', string> = {
  metrics: '需 metrics-server', custom: '需 prometheus-adapter（custom）', external: '需 prometheus-adapter（external）',
}
const typeAvailable = (t: MetricType) =>
  !cap.probed.value || (TYPE_GROUP[t] === 'metrics' ? cap.hasMetricsServer.value : TYPE_GROUP[t] === 'custom' ? cap.hasCustomMetrics.value : cap.hasExternalMetrics.value)
const typeHint = (t: MetricType) => (typeAvailable(t) ? '' : TYPE_HINT[TYPE_GROUP[t]])

const METRIC_TYPE_DESC: Record<MetricType, string> = {
  Resource: 'Resource=按 Pod 整体资源（cpu/memory）利用率',
  ContainerResource: 'ContainerResource=按指定容器的资源',
  Pods: 'Pods=按命名空间内一组 Pod 的自定义指标聚合',
  Object: 'Object=按单个 K8s 对象（如 Ingress）的自定义指标',
  External: 'External=按集群外部指标源',
}

// ---------- 提交：本地校验 → 组装 body（update 为整对象替换） ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const saving = ref(false)

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }
  if (!form.targetKey) { ElMessage.warning('请选择目标工作负载'); return }
  if (form.maxReplicas == null || form.maxReplicas < 1) { ElMessage.warning('最大副本数需 ≥ 1'); return }
  if (form.minReplicas != null && (form.minReplicas < 1 || form.minReplicas > form.maxReplicas)) { ElMessage.warning('最小副本数需在 1 ~ 最大副本数之间'); return }
  if (!form.metrics.length) { ElMessage.warning('至少需要一个指标'); return }
  for (const m of form.metrics) {
    const needsName = m.type === 'Resource' || m.type === 'ContainerResource'
    if (needsName && !m.name.trim()) { ElMessage.warning(`存在未填写资源名的 ${m.type} 指标`); return }
    if (m.type === 'ContainerResource' && !m.container.trim()) { ElMessage.warning('ContainerResource 指标需填写容器名'); return }
    if ((m.type === 'Pods' || m.type === 'Object' || m.type === 'External') && !m.metricName.trim()) { ElMessage.warning(`${m.type} 指标需填写指标名`); return }
    if (m.type === 'Object' && (!m.objKind.trim() || !m.objName.trim())) { ElMessage.warning('Object 指标需填写被描述对象的 kind 和 name'); return }
    if (m.targetUtilizationType !== 'Utilization' && !m.targetValue.trim()) { ElMessage.warning(`${m.type} 指标（${m.targetUtilizationType}）需填写目标值`); return }
  }

  const metrics: K8sHpaMetric[] = form.metrics.map(rowToK8s)
  let behavior: K8sHpaBehavior | null = null
  if (hasBehavior()) {
    behavior = { scaleUp: ruleToK8s(form.scaleUp), scaleDown: ruleToK8s(form.scaleDown) }
  }

  // canonical 键（小写）拆回大写 kind。目标未改（仍等于回填的自身键）→ 原样发送回填时记录的 kind，
  // 保住遗留非 D/S 绑定（如 ReplicaSet）的大小写；新选目标必是 deployment/statefulset → KIND_CAP 还原大写。
  const [kKind = '', kName = ''] = form.targetKey.split('/')
  const targetKindOut =
    editing.value && ownKind.value && form.targetKey === ownKey.value ? ownKind.value : KIND_CAP[kKind] ?? kKind

  const payload: K8sHpa = {
    name,
    namespace: state.namespace!,
    minReplicas: form.minReplicas ?? 1,
    maxReplicas: form.maxReplicas!,
    scaleTargetRef: { kind: targetKindOut, name: kName },
    metrics,
    behavior,
  }

  saving.value = true
  try {
    if (editing.value) {
      await hpaApi.update(editing.value, ctx2.value, payload)
      ElMessage.success('保存成功')
    } else {
      await hpaApi.create(ctx2.value, payload)
      ElMessage.success('创建成功')
    }
    router.push('/resources/hpas')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/resources/hpas') }

const pageTitle = computed(() => (editing.value ? '编辑 HPA' : '创建 HPA'))
const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div class="res-editor">
    <PageHeader :title="pageTitle" :description="contextDesc">
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑 HPA。" />

    <template v-else>
      <div v-if="formVisible" class="editor-body">
        <!-- 基础信息 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">{{ editing ? `HPA · ${form.name}` : '基础信息' }}</span></template>
          <el-form label-width="200px" label-position="left">
            <el-form-item label="名称" required>
              <template #label>名称 <FieldHelp tip="HPA 对象名，需符合 RFC1123（小写字母/数字/-，字母或数字开头结尾），命名空间内唯一；K8s 资源名创建后不可修改。" /></template>
              <el-input v-model="form.name" :disabled="!!editing" placeholder="小写字母/数字/-，例如 app-hpa" style="width: 360px" />
            </el-form-item>
            <el-form-item label="命名空间">
              <template #label>命名空间 <FieldHelp tip="取当前上下文（顶栏）的命名空间，只读；HPA 只能扩缩同命名空间内的工作负载。" /></template>
              <el-input :model-value="state.namespace ?? ''" disabled style="width: 360px" />
            </el-form-item>
            <el-form-item label="目标工作负载" required>
              <template #label>目标工作负载 <FieldHelp tip="选择要自动扩缩容的 Deployment / StatefulSet。一个工作负载只能绑定一个 HPA，已绑定的不可选（已绑定的自己除外）。" /></template>
              <el-select v-model="form.targetKey" filterable clearable :loading="optsLoading" placeholder="选择目标工作负载（必填）" style="width: 420px">
                <el-option v-for="o in targetOptions" :key="o.value" :label="o.label" :value="o.value" :disabled="o.bound">
                  <div class="opt-row">
                    <span>{{ o.label }}</span>
                    <span v-if="o.bound" class="opt-hint">已绑定 HPA</span>
                  </div>
                </el-option>
              </el-select>
              <div v-if="!workloadOptions.length && !optsLoading" class="form-tip">该命名空间下暂无可绑定 HPA 的 Deployment / StatefulSet。</div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 副本范围 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">副本范围</span></template>
          <el-form label-width="200px" label-position="left">
            <el-form-item label="最小副本">
              <template #label>最小副本 <FieldHelp tip="缩容下限（1 ~ 最大副本数），留空默认 1。" /></template>
              <el-input-number v-model="form.minReplicas" :min="1" controls-position="right" placeholder="默认 1" class="num-input" />
            </el-form-item>
            <el-form-item label="最大副本" required>
              <template #label>最大副本 <FieldHelp tip="扩容上限，需 ≥ 1。" /></template>
              <el-input-number v-model="form.maxReplicas" :min="1" controls-position="right" placeholder="必填" class="num-input" />
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 指标 metrics -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">指标（metrics）</span></template>
          <el-alert
            v-if="notProbedTip"
            type="info"
            :closable="false"
            class="cap-tip"
            title="集群 API 能力尚未探测，指标可用性未知；可点列表页「刷新能力」。当前五类指标默认可选。"
          />
          <div v-for="(m, idx) in form.metrics" :key="idx" class="metric-block">
            <div class="metric-head">
              <span class="metric-idx">指标 #{{ idx + 1 }}</span>
              <el-button link type="danger" :disabled="form.metrics.length <= 1" @click="removeMetric(idx)">删除</el-button>
            </div>

            <!-- 类型：按真实依赖的 API group 门禁（不可用项 disabled，已选值照常回显） -->
            <div class="mfield">
              <label>指标类型 <FieldHelp :tip="`五类互斥。${METRIC_TYPE_DESC.Resource}；${METRIC_TYPE_DESC.ContainerResource}；${METRIC_TYPE_DESC.Pods}；${METRIC_TYPE_DESC.Object}；${METRIC_TYPE_DESC.External}。可用类型取决于集群是否安装 metrics-server / prometheus-adapter。`" /></label>
              <el-select v-model="m.type" style="width: 260px" @change="onMetricTypeChange(m)">
                <el-option v-for="t in METRIC_TYPES" :key="t" :label="t" :value="t" :disabled="!typeAvailable(t)">
                  <div class="opt-row">
                    <span>{{ t }}</span>
                    <span class="opt-hint">{{ typeAvailable(t) ? METRIC_TYPE_DESC[t] : typeHint(t) }}</span>
                  </div>
                </el-option>
              </el-select>
            </div>

            <!-- Resource / ContainerResource 专属字段 -->
            <template v-if="m.type === 'Resource' || m.type === 'ContainerResource'">
              <div v-if="m.type === 'ContainerResource'" class="mfield">
                <label>容器名 <FieldHelp tip="containerResource.container：目标容器名，须是该 Pod 内已存在的容器名；同一 Pod 多容器可分别配置多个 ContainerResource 指标。" /></label>
                <el-input v-model="m.container" placeholder="容器名，例如 app" class="f-2" />
              </div>
              <div class="mfield">
                <label>资源名 <FieldHelp tip="resource/containerResource.name：内置资源名，目前只支持 cpu 与 memory（其他值 K8s 不接受）。留 cpu 即按 CPU 利用率扩缩容。" /></label>
                <el-input v-model="m.name" placeholder="资源名（cpu/memory）" class="f-2" />
              </div>
            </template>
            <!-- Pods / Object / External：自定义 / 外部指标名 -->
            <div v-else class="mfield">
              <label>指标名 <FieldHelp tip="metric.name：由 prometheus-adapter 暴露的指标名（custom.metrics.k8s.io / external.metrics.k8s.io）。需与 adapter 的 rules 里 configured 的名字一致，拼错会导致 HPA 取不到值、不伸缩。" /></label>
              <el-input v-model="m.metricName" placeholder="指标名，例如 requests-per-second" class="f-2" />
            </div>
            <!-- Object.describedObject -->
            <template v-if="m.type === 'Object'">
              <div class="mfield">
                <label>被描述对象 apiVersion <FieldHelp tip="object.describedObject.apiVersion：被度量对象版本（可空，K8s 按 kind 推断），例如 networking.k8s.io/v1。" /></label>
                <el-input v-model="m.objApiVersion" placeholder="apiVersion（可空）" class="f-2" />
              </div>
              <div class="mfield">
                <label>被描述对象 kind <FieldHelp tip="object.describedObject.kind：被度量的单个对象类型，例如 Ingress；与下方 name 共同定位唯一对象。" /></label>
                <el-input v-model="m.objKind" placeholder="kind，例如 Ingress" class="f-2" />
              </div>
              <div class="mfield">
                <label>被描述对象 name <FieldHelp tip="object.describedObject.name：被度量对象的名字，必填。" /></label>
                <el-input v-model="m.objName" placeholder="name" class="f-2" />
              </div>
              <div class="mfield">
                <label>被描述对象 namespace <FieldHelp tip="object.describedObject.namespace：留空 = 与本 HPA 同命名空间；跨命名空间对象需显式填写。" /></label>
                <el-input v-model="m.objNamespace" placeholder="namespace（可空）" class="f-2" />
              </div>
            </template>

            <!-- target -->
            <div class="mfield">
              <label>目标类型 <FieldHelp tip="target.type：Utilization=平均值占请求量的百分比（Resource / ContainerResource 常用）；AverageValue=所有相关 Pod 的平均绝对值；Value=聚合总量（Object / External 常用）。" /></label>
              <el-select v-model="m.targetUtilizationType" style="width: 190px">
                <el-option v-for="t in TARGET_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
            </div>
            <div v-if="m.targetUtilizationType === 'Utilization'" class="mfield">
              <label>目标利用率 <FieldHelp tip="target.averageUtilization：1 ~ 100 的整数百分比，指相对容器 requests 的平均利用率（不是 limits）。如 70 = 平均用到请求量的 70% 即触发扩容。" /></label>
              <el-input-number v-model="m.targetAverageUtilization" :min="1" :max="100" controls-position="right" placeholder="目标利用率 %" style="width: 190px" />
            </div>
            <template v-else>
              <div class="mfield">
                <label>目标值 <FieldHelp tip="target.value：Quantity 语义，如 500m / 1Gi；但后端 DTO 统一存基础单位（CPU=核数、内存=字节），故此处填纯数字：500m 填 0.5、1Gi 填 1073741824。非数字输入会被丢弃。" /></label>
                <el-input v-model="m.targetValue" placeholder="目标值（Quantity，如 500m / 1Gi）" class="f-2" />
              </div>
              <div v-if="m.type === 'External'" class="mfield">
                <label>平均值 <FieldHelp tip="target.averageValue（仅 External，可空）：外部指标按实例分摊后的期望均值，Quantity 基础单位。与目标值二选一或同时给出，由 adapter 侧语义决定。" /></label>
                <el-input v-model="m.targetAverageValue" placeholder="平均值（可空，基础单位）" class="f-2" />
              </div>
            </template>
          </div>
          <el-button class="add-row-btn" plain @click="addMetric">+ 添加指标</el-button>
        </el-card>

        <!-- 扩缩容行为 behavior -->
        <el-card shadow="never" class="sec-card">
          <template #header>
            <span class="sec-title">
              扩缩容行为（behavior，可选）<FieldHelp tip="控制扩缩容速率与稳定性，避免抖动。留空则不设置 behavior、使用集群默认。仅 autoscaling/v2 支持。" />
            </span>
          </template>
          <el-alert
            v-if="!cap.hpaSupportsBehavior.value"
            type="warning"
            :closable="false"
            class="cap-tip"
            title="该集群 autoscaling 无 v2（capability 探测），v1 HPA 不支持 behavior，仅支持 CPU 利用率"
          />
          <div :class="{ 'behavior-disabled': !cap.hpaSupportsBehavior.value }">
            <div class="behavior-grid">
              <div v-for="(side, key) in [{ k: 'scaleUp' as const, label: '扩容 scaleUp' }, { k: 'scaleDown' as const, label: '缩容 scaleDown' }]" :key="side.k">
                <div class="behavior-title">{{ side.label }}</div>
                <div class="sub-title">
                  稳定期<FieldHelp tip="stabilizationWindowSeconds：该方向扩/缩容前的观察窗口（秒），窗口内取最不利于变更的读数，留空 = 用集群默认。" />
                </div>
                <div class="metric-fields">
                  <el-input-number v-model="form[side.k].stabilizationWindowSeconds" :min="0" controls-position="right" placeholder="稳定期(秒)" style="width: 150px" />
                </div>
                <div class="sub-title">
                  策略<FieldHelp tip="selectPolicy：同一周期内有多条速率策略时如何取值——Max=最宽松（允许最多）、Min=最保守、Disabled=该方向完全禁用扩/缩容。" />
                </div>
                <div class="metric-fields">
                  <el-select v-model="form[side.k].selectPolicy" style="width: 130px">
                    <el-option v-for="p in SELECT_POLICIES" :key="p" :label="p" :value="p" />
                  </el-select>
                </div>
                <div class="sub-title">
                  速率策略（policies）<FieldHelp tip="policies：限定单位时间内的变化量；type=Percent（相对当前副本数百分比）或 Pods（绝对副本数），value=上限，periodSeconds=窗口（秒）。" />
                </div>
                <div v-for="(p, pi) in form[side.k].policies" :key="pi" class="metric-fields">
                  <el-select v-model="p.type" style="width: 120px">
                    <el-option v-for="pt in POLICY_TYPES" :key="pt" :label="pt" :value="pt" />
                  </el-select>
                  <el-input-number v-model="p.value" :min="1" controls-position="right" placeholder="值" style="width: 120px" />
                  <el-input-number v-model="p.periodSeconds" :min="1" controls-position="right" placeholder="窗口(秒)" style="width: 130px" />
                  <el-button link type="danger" @click="removePolicy(form[side.k], pi)">删除</el-button>
                </div>
                <el-button link type="primary" class="add-policy-btn" @click="addPolicy(form[side.k], side.k)">+ 速率策略</el-button>
              </div>
            </div>
          </div>
        </el-card>

        <div class="form-actions">
          <el-button @click="goBack">取消 / 返回</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
      </div>

      <EmptyState v-else-if="detailState === 'error'" title="加载 HPA 失败" description="请返回列表重试；若该资源已被删除，刷新列表即可。">
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </EmptyState>

      <div v-else class="loading-tip">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
.editor-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sec-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.sec-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.form-tip {
  width: 100%;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.num-input {
  width: 200px;
}
.cap-tip {
  margin-bottom: 10px;
}
/* 下拉选项：名称 + 说明 / 已绑定提示两列 */
.opt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  line-height: 1.4;
  padding: 2px 0;
}
.opt-hint {
  font-size: 12px;
  color: var(--text-3);
}
/* 指标块（自 HpaView 迁移） */
.metric-block { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; background: var(--panel-hover); }
.metric-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.metric-idx { font-size: 13px; font-weight: 600; color: var(--text-2); }
/* 指标块内的字段行：label（含 FieldHelp）在上、控件在下 */
.mfield { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; margin-top: 8px; }
.mfield > label { font-size: 12px; color: var(--text-3); }
.sub-title { font-size: 12px; color: var(--text-3); margin-top: 10px; }
.metric-fields { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 8px; }
.f-2 { width: 200px; }
.add-row-btn { width: 100%; }
/* behavior 网格 + v1 禁用态 */
.behavior-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.behavior-title { font-size: 12.5px; font-weight: 600; color: var(--text-2); margin-bottom: 8px; }
.add-policy-btn { margin-top: 4px; }
.behavior-disabled { pointer-events: none; opacity: .55; }
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 4px 0 16px;
}
.loading-tip {
  padding: 48px;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}
</style>
