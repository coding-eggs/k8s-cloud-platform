<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hpaApi } from '@/api'
import type { K8sHpa, K8sHpaBehavior, K8sHpaBehaviorRule, K8sHpaMetric, K8sHpaMetricTarget } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sHpa[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await hpaApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

// ---------- 表单模型（指标 / behavior 行） ----------
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

const TARGET_KINDS = ['Deployment', 'StatefulSet']
const form = reactive({
  name: '',
  targetKind: 'Deployment',
  targetName: '',
  minReplicas: null as number | null,
  maxReplicas: null as number | null,
  metrics: [] as MetricRow[],
  scaleUp: newBehaviorRule() as BehaviorRule,
  scaleDown: newBehaviorRule() as BehaviorRule,
})

function openCreate(): void {
  isEdit.value = false
  form.name = ''
  form.targetKind = 'Deployment'
  form.targetName = ''
  form.minReplicas = null
  form.maxReplicas = null
  form.metrics = [newMetricRow()]
  form.scaleUp = newBehaviorRule()
  form.scaleDown = newBehaviorRule()
  dialogVisible.value = true
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
    row.targetValue = t.value ?? ''
    row.targetAverageValue = t.averageValue ?? ''
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

function openEdit(row: K8sHpa): void {
  isEdit.value = true
  form.name = row.name
  form.targetKind = row.scaleTargetRef?.kind ?? 'Deployment'
  form.targetName = row.scaleTargetRef?.name ?? ''
  form.minReplicas = row.minReplicas ?? null
  form.maxReplicas = row.maxReplicas ?? null
  form.metrics = (row.metrics ?? []).map(k8sToRow)
  if (form.metrics.length === 0) form.metrics = [newMetricRow()]
  form.scaleUp = k8sRuleToForm(row.behavior?.scaleUp)
  form.scaleDown = k8sRuleToForm(row.behavior?.scaleDown)
  dialogVisible.value = true
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
    target.value = row.targetValue.trim() || undefined
  } else {
    target.type = 'Value'
    target.value = row.targetValue.trim() || undefined
    target.averageValue = row.targetAverageValue.trim() || undefined
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

// ---------- 创建 / 编辑对话框 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)

async function submit(): Promise<void> {
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入名称'); return }
  if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }
  if (!form.targetName.trim()) { ElMessage.warning('请输入目标工作负载名称'); return }
  if (form.maxReplicas == null || form.maxReplicas < 1) { ElMessage.warning('最大副本数需 ≥ 1'); return }
  if (form.minReplicas != null && (form.minReplicas < 1 || form.minReplicas > form.maxReplicas)) { ElMessage.warning('最小副本数需在 1 ~ 最大副本数之间'); return }
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

  const payload: K8sHpa = {
    name,
    namespace: state.namespace!,
    minReplicas: form.minReplicas ?? 1,
    maxReplicas: form.maxReplicas!,
    scaleTargetRef: { kind: form.targetKind, name: form.targetName.trim() },
    metrics,
    behavior,
  }

  saving.value = true
  try {
    if (isEdit.value) {
      await hpaApi.update(name, { tenantId: state.tenantId!, clusterId: state.clusterId! }, payload)
      ElMessage.success('已更新')
    } else {
      await hpaApi.create({ tenantId: state.tenantId!, clusterId: state.clusterId! }, payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

// ---------- 删除 ----------
async function onDelete(row: K8sHpa): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 HPA「${row.name}」？`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await hpaApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sHpa | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sHpa): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try { yamlText.value = await hpaApi.getYaml(detail.value.name, ctxParams.value) } catch { /* 拦截器已提示 */ }
  }
})

function targetText(t?: K8sHpaMetricTarget | null): string {
  if (!t) return ''
  if (t.type === 'Utilization') return `${t.averageUtilization}%`
  return t.value ?? t.averageValue ?? ''
}

function metricSummary(row: K8sHpa): string {
  const parts: string[] = []
  for (const m of row.metrics ?? []) {
    if (m.resource) parts.push(`${m.resource.name} ${targetText(m.resource.target)}`.trim())
    else if (m.containerResource) parts.push(`${m.containerResource.container}/${m.containerResource.name} ${targetText(m.containerResource.target)}`.trim())
    else if (m.pods) parts.push(`Pods:${m.pods.metricName}`)
    else if (m.object) parts.push(`Object:${m.object.metricName}`)
    else if (m.external) parts.push(`External:${m.external.metricName}`)
  }
  return parts.join('，') || '—'
}

// ---------- 上下文联动：顶栏 chip 变化时刷新 ----------
onMounted(() => { void load(); void refresh() })
watch(
  () => [state.tenantId, state.clusterId, state.namespace],
  () => { if (ready.value) void refresh() },
)

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader title="HPA" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="openCreate">创建 HPA</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 HPA"
      description="点击右上「创建 HPA」为工作负载配置自动扩缩容。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="目标工作负载" min-width="180">
          <template #default="{ row }">
            <span class="muted">{{ row.scaleTargetRef?.kind ?? '—' }} / </span><code class="res-name">{{ row.scaleTargetRef?.name ?? '—' }}</code>
          </template>
        </el-table-column>
        <el-table-column label="副本" width="150">
          <template #default="{ row }">
            {{ row.minReplicas ?? 1 }}–{{ row.maxReplicas ?? '—' }}
            <span v-if="row.currentReplicas != null" class="muted">（当前 {{ row.currentReplicas }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="指标" min-width="200">
          <template #default="{ row }"><span class="port-text">{{ metricSummary(row) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 创建 / 编辑 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? `编辑 HPA · ${form.name}` : '创建 HPA'" width="900px" top="6vh">
      <el-form label-width="110px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="form.name" :disabled="isEdit" placeholder="小写字母/数字/-，例如 app-hpa" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="命名空间">
              <el-input :model-value="state.namespace ?? ''" disabled />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="目标类型">
              <el-select v-model="form.targetKind" style="width: 100%">
                <el-option v-for="k in TARGET_KINDS" :key="k" :label="k" :value="k" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="目标名称" required>
              <el-input v-model="form.targetName" placeholder="工作负载名，例如 app" />
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label="最小副本">
              <el-input-number v-model="form.minReplicas" :min="1" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="4">
            <el-form-item label="最大副本" required>
              <el-input-number v-model="form.maxReplicas" :min="1" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 指标 -->
        <div class="section-title">指标（metrics）</div>
        <div v-for="(m, idx) in form.metrics" :key="idx" class="metric-block">
          <div class="metric-head">
            <el-select v-model="m.type" style="width: 190px" @change="onMetricTypeChange(m)">
              <el-option v-for="t in METRIC_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
            <el-button link type="danger" :disabled="form.metrics.length <= 1" @click="removeMetric(idx)">删除</el-button>
          </div>

          <!-- Resource / ContainerResource -->
          <template v-if="m.type === 'Resource' || m.type === 'ContainerResource'">
            <div class="metric-fields">
              <el-input v-if="m.type === 'ContainerResource'" v-model="m.container" placeholder="容器名" class="f-2" />
              <el-input v-model="m.name" placeholder="资源名（cpu/memory）" class="f-2" />
            </div>
          </template>
          <!-- Pods / Object / External -->
          <div v-else-if="m.type === 'Pods' || m.type === 'Object' || m.type === 'External'" class="metric-fields">
            <el-input v-model="m.metricName" placeholder="指标名，例如 requests-per-second" class="f-2" />
          </div>
          <!-- Object.describedObject -->
          <div v-if="m.type === 'Object'" class="metric-fields obj-ref">
            <el-input v-model="m.objApiVersion" placeholder="apiVersion（可空）" class="f-2" />
            <el-input v-model="m.objKind" placeholder="kind，例如 Ingress" class="f-2" />
            <el-input v-model="m.objName" placeholder="name" class="f-2" />
            <el-input v-model="m.objNamespace" placeholder="namespace（可空）" class="f-2" />
          </div>

          <!-- target -->
          <div class="metric-fields">
            <el-select v-model="m.targetUtilizationType" style="width: 150px">
              <el-option v-for="t in TARGET_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
            <el-input-number
              v-if="m.targetUtilizationType === 'Utilization'"
              v-model="m.targetAverageUtilization"
              :min="1" :max="100" controls-position="right" placeholder="目标利用率 %" style="width: 170px"
            />
            <template v-else>
              <el-input v-model="m.targetValue" placeholder="目标值（Quantity，如 500m / 1Gi）" class="f-2" />
              <el-input
                v-if="m.type === 'External'"
                v-model="m.targetAverageValue"
                placeholder="平均值（可空，Quantity）" class="f-2"
              />
            </template>
          </div>
        </div>
        <el-button class="add-row-btn" plain @click="addMetric">+ 添加指标</el-button>

        <!-- behavior -->
        <div class="section-title">扩缩容行为（behavior，可选 · 仅 v2）</div>
        <el-alert type="info" :closable="false" class="behavior-tip" title="留空则不设置 behavior。v1 集群不支持 behavior，填写会在保存时报错。" />
        <div class="behavior-grid">
          <div v-for="(side, key) in [{ k: 'scaleUp' as const, label: '扩容 scaleUp' }, { k: 'scaleDown' as const, label: '缩容 scaleDown' }]" :key="side.k">
            <div class="behavior-title">{{ side.label }}</div>
            <div class="metric-fields">
              <el-input-number v-model="form[side.k].stabilizationWindowSeconds" :min="0" controls-position="right" placeholder="稳定期(秒)" style="width: 150px" />
              <el-select v-model="form[side.k].selectPolicy" style="width: 130px">
                <el-option v-for="p in SELECT_POLICIES" :key="p" :label="p" :value="p" />
              </el-select>
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
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`HPA · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="目标工作负载">{{ detail?.scaleTargetRef?.kind ?? '—' }} / {{ detail?.scaleTargetRef?.name ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="副本范围">{{ detail?.minReplicas ?? 1 }} – {{ detail?.maxReplicas ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="当前副本">{{ detail?.currentReplicas ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
          <div class="section-title">指标</div>
          <div class="muted">{{ metricSummary(detail!) }}</div>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-panel { padding: 8px; }
.res-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 13px; color: var(--text-1); }
.port-text { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; color: var(--text-2); }
.muted { color: var(--text-3); }
.section-title { font-size: 13px; font-weight: 600; color: var(--text-2); margin: 14px 0 8px; }
.metric-block { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; background: var(--panel-hover); }
.metric-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.metric-fields { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 8px; }
.f-2 { width: 200px; }
.obj-ref { padding-left: 12px; border-left: 2px solid var(--border); }
.add-row-btn { width: 100%; }
.behavior-tip { margin-bottom: 10px; }
.behavior-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.behavior-title { font-size: 12.5px; font-weight: 600; color: var(--text-2); margin-bottom: 8px; }
.add-policy-btn { margin-top: 4px; }
.yaml-block {
  margin: 0; padding: 14px; border-radius: 8px; background: var(--panel-hover);
  border: 1px solid var(--border); font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px; line-height: 1.6; color: var(--text-2); max-height: 60vh; overflow: auto; white-space: pre-wrap;
}
</style>
