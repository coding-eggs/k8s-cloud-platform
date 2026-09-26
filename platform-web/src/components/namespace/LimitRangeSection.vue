<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { namespaceApi } from '@/api'
import type { K8sLimitRange, K8sLimitRangeItem, ResourcePair } from '@/types'
import { bytesToMi, coresToMilli, milliToCores, miToBytes } from '@/utils/quantityUnits'
import FieldHelp from '@/components/workload/FieldHelp.vue'

/**
 * 限制范围编辑区块（core/v1 LimitRange，平台单份：对象名固定 default）。
 *
 * 三类型（Container / Pod / PersistentVolumeClaim）× 五字段（max/min/default/defaultRequest/
 * maxLimitRequestRatio）× 两维度（cpu / memory）。模型值 = 基础单位（cpu=核、memory=字节；
 * ratio 无量纲），显示经 utils/quantityUnits 换算为固定单位（cpu→m、memory→Mi），
 * 单位一律 `el-input` + `<template #append>`（el-input-number 无 #append 插槽）。
 *
 * 线路语义（overlay，见 CoreV1LimitRangeConverter）：
 *  - 关掉的类型不进 payload → 后端「建模类型未提及即删除」；
 *  - 类型内清空的字段（pair 两维度皆 null）省略 → 删除该字段；只填一个维度 → 只写该维度键。
 * 跨字段校验同 platform-api NamespaceService.validateLimitRange（max≥min、default≥defaultRequest、
 * ratio≥1，未填项跳过）：违规单元格红框 + 行内提示，isValid() 为 false 时父级阻断提交。
 */
const props = defineProps<{ clusterId: string; namespace: string }>()

const SINGLE_NAME = 'default'

type LrType = 'Container' | 'Pod' | 'PersistentVolumeClaim'
type LrField = 'max' | 'min' | 'defaultValue' | 'defaultRequest' | 'maxLimitRequestRatio'
type LrDim = 'cpu' | 'memory'

/** 三类型的展示文案：scope = 数值作用对象（写进各字段 tip），hint = 类型级说明 */
const LR_TYPES: { type: LrType; label: string; scope: string; hint: string }[] = [
  {
    type: 'Container', label: '容器（Container）', scope: '单个容器',
    hint: '约束 Pod 内的单个容器：K8s 为未写 limit/request 的容器注入 default/defaultRequest，并拒绝超出 max、低于 min 的取值。',
  },
  {
    type: 'Pod', label: 'Pod（Pod）', scope: '单个 Pod',
    hint: '约束整个 Pod：其值按 Pod 内所有容器之和计算（max/min 为该总和的上下限）。default/defaultRequest 对 Pod 级不生效（K8s 只在容器级注入）。',
  },
  {
    type: 'PersistentVolumeClaim', label: '存储声明（PersistentVolumeClaim）', scope: '单个 PVC',
    hint: '约束 PVC。注意 K8s 此处作用于存储容量（storage 维度），平台契约仅建模 cpu/memory 两项，故该组一般留空；PVC 的存储总量请用资源配额。',
  },
]

const LR_FIELDS: { key: LrField; label: string; kind: 'bound' | 'injected' | 'ratio'; tip: string }[] = [
  {
    key: 'max', label: 'max（上限）', kind: 'bound',
    tip: '{scope}可设置的最大值（spec.limits[].max）。低于该值的取值被拒绝。留空 = 不设。',
  },
  {
    key: 'min', label: 'min（下限）', kind: 'bound',
    tip: '{scope}可设置的最小值（spec.limits[].min）。高于该值的取值被拒绝。留空 = 不设。',
  },
  {
    key: 'defaultValue', label: 'default（缺省 limit）', kind: 'injected',
    tip: '{scope}未显式写 limits 时，K8s 自动注入的 limit 默认值（spec.limits[].default）。须 ≥ defaultRequest。',
  },
  {
    key: 'defaultRequest', label: 'defaultRequest（缺省 request）', kind: 'injected',
    tip: '{scope}未显式写 requests 时，K8s 自动注入的 request 默认值（spec.limits[].defaultRequest）。不得大于 default。',
  },
  {
    key: 'maxLimitRequestRatio', label: 'maxLimitRequestRatio', kind: 'ratio',
    tip: '{scope}的 limits ÷ requests 允许的最大倍数（spec.limits[].maxLimitRequestRatio），需 ≥ 1，无量纲（填 2 = limit 最多为 request 的 2 倍）。',
  },
]

const LR_DIMS: { key: LrDim; label: string; unit: string }[] = [
  { key: 'cpu', label: 'cpu', unit: 'm' },
  { key: 'memory', label: 'memory', unit: 'Mi' },
]

/** 单元格输入框的占位示例（按 字段 × 维度） */
const PLACEHOLDER: Record<string, string> = {
  'max|cpu': '2000', 'max|memory': '4096',
  'min|cpu': '100', 'min|memory': '128',
  'defaultValue|cpu': '1000', 'defaultValue|memory': '2048',
  'defaultRequest|cpu': '200', 'defaultRequest|memory': '512',
  'maxLimitRequestRatio|cpu': '2', 'maxLimitRequestRatio|memory': '2',
}

interface TypeState { enabled: boolean; cells: Record<string, number | null> }

function emptyType(): TypeState {
  const cells: Record<string, number | null> = {}
  for (const f of LR_FIELDS) for (const d of LR_DIMS) cells[`${f.key}|${d.key}`] = null
  return { enabled: false, cells }
}

const enabled = ref(false)
/**
 * load() 是否失败（状态未知）。失败后 UI 与「未配置」无法区分，父级 saveConstraints
 * 必须整段跳过本区块（既不 upsert 也不 delete）——任何写都是盲写：
 * delete 分支会误删仍存在的对象；开回开关再空保存会删光所有建模类型限制项。
 */
const loadFailed = ref(false)
const state = reactive<Record<LrType, TypeState>>({
  Container: emptyType(),
  Pod: emptyType(),
  PersistentVolumeClaim: emptyType(),
})

function cellKey(field: LrField, dim: LrDim): string {
  return `${field}|${dim}`
}

/** ratio 无量纲（可含小数，如 1.5）；其余维度值经固定单位换算 */
function unitOf(field: LrField, dim: LrDim): 'm' | 'Mi' | null {
  if (field === 'maxLimitRequestRatio') return null
  return dim === 'cpu' ? 'm' : 'Mi'
}

/** 无量纲数值：剥非数字字符（保留一个小数点）→ number；空/非法 → null */
function decimalOrNull(raw: number | string | null | undefined): number | null {
  const s = String(raw ?? '').replace(/[^\d.]/g, '')
  if (s === '') return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

function displayOf(type: LrType, field: LrField, dim: LrDim): number | null {
  const v = state[type].cells[cellKey(field, dim)]
  if (v == null) return null
  const unit = unitOf(field, dim)
  if (unit === 'm') return coresToMilli(v)
  if (unit === 'Mi') return bytesToMi(v)
  return v
}

function onCell(type: LrType, field: LrField, dim: LrDim, raw: number | string | null | undefined): void {
  const unit = unitOf(field, dim)
  state[type].cells[cellKey(field, dim)] =
    unit === 'm' ? milliToCores(raw) : unit === 'Mi' ? miToBytes(raw) : decimalOrNull(raw)
}

function fieldTip(f: (typeof LR_FIELDS)[number], scope: string): string {
  return f.tip.replace('{scope}', scope)
}

/** 类型内的数值序违规（未填项跳过）。keys 带类型前缀（type|field|dim）→ 只标红违规类型自己的单元格 */
interface Issue { keys: string[]; msg: string }

function issueKey(type: LrType, field: LrField, dim: LrDim): string {
  return `${type}|${field}|${dim}`
}

function typeIssues(type: LrType): Issue[] {
  const st = state[type]
  const out: Issue[] = []
  const label = LR_TYPES.find((t) => t.type === type)?.label ?? type
  for (const d of LR_DIMS) {
    const max = st.cells[cellKey('max', d.key)]
    const min = st.cells[cellKey('min', d.key)]
    if (max != null && min != null && max < min) {
      out.push({
        keys: [issueKey(type, 'max', d.key), issueKey(type, 'min', d.key)],
        msg: `${label} ${d.label}：max 不得小于 min`,
      })
    }
    const def = st.cells[cellKey('defaultValue', d.key)]
    const req = st.cells[cellKey('defaultRequest', d.key)]
    if (def != null && req != null && def < req) {
      out.push({
        keys: [issueKey(type, 'defaultValue', d.key), issueKey(type, 'defaultRequest', d.key)],
        msg: `${label} ${d.label}：defaultRequest 不得大于 default`,
      })
    }
    const ratio = st.cells[cellKey('maxLimitRequestRatio', d.key)]
    if (ratio != null && ratio < 1) {
      out.push({ keys: [issueKey(type, 'maxLimitRequestRatio', d.key)], msg: `${label} ${d.label}：maxLimitRequestRatio 不得小于 1` })
    }
  }
  return out
}

/** 仅已启用类型参与校验（关掉的类型不进 payload，无须校验） */
const activeIssues = computed<Issue[]>(() =>
  enabled.value ? LR_TYPES.filter((t) => state[t.type].enabled).flatMap((t) => typeIssues(t.type)) : [],
)

const invalidCells = computed<Set<string>>(() => new Set(activeIssues.value.flatMap((i) => i.keys)))

/** 每个类型的行内提示：命中该类型单元格的违规拼成一句（'' = 无违规） */
function typeHint(type: LrType): string {
  return activeIssues.value
    .filter((i) => i.keys.some((k) => k.startsWith(`${type}|`)))
    .map((i) => i.msg)
    .join('；')
}

function isValid(): boolean {
  return activeIssues.value.length === 0
}

function reset(): void {
  enabled.value = false
  for (const t of LR_TYPES) state[t.type] = emptyType()
}

function onToggleSection(val: boolean | string | number): void {
  if (val) return
  reset()
  ElMessage.warning('已关闭限制范围：保存将删除该命名空间的 LimitRange（含平台未建模的类型，如 ContainerFixed）')
}

/** 关掉类型：清空该类型数值（payload 省略该类型 → 后端 overlay 删除，保留旧值只会误导） */
function onToggleType(type: LrType, val: boolean | string | number): void {
  if (val) return
  state[type] = emptyType()
}

function pairOf(st: TypeState, field: LrField): ResourcePair | null {
  const cpu = st.cells[cellKey(field, 'cpu')]
  const memory = st.cells[cellKey(field, 'memory')]
  if (cpu == null && memory == null) return null
  const pair: ResourcePair = {}
  if (cpu != null) pair.cpu = cpu
  if (memory != null) pair.memory = memory
  return pair
}

/** 提交体（基础单位）：只含已启用类型；pair 两维度皆空 → 省略该字段（后端删除） */
function toPayload(): K8sLimitRange {
  const limits: K8sLimitRangeItem[] = []
  for (const t of LR_TYPES) {
    const st = state[t.type]
    if (!st.enabled) continue
    const item: K8sLimitRangeItem = { type: t.type }
    for (const f of LR_FIELDS) {
      const pair = pairOf(st, f.key)
      if (pair) item[f.key] = pair
    }
    limits.push(item)
  }
  return { name: SINGLE_NAME, namespace: props.namespace, limits }
}

function hasAnyValue(): boolean {
  return LR_TYPES.some(
    (t) => state[t.type].enabled || LR_FIELDS.some((f) => LR_DIMS.some((d) => state[t.type].cells[cellKey(f.key, d.key)] != null)),
  )
}

/**
 * 回填：null（未配置）→ 关闭开关；取不到（拦截器已提示）回落「未配置」，不锁死编辑，
 * 但置 loadFailed=true —— 父级据此跳过本区块的保存写操作（盲写防护，见 loadFailed 注释）。
 */
async function load(): Promise<void> {
  loadFailed.value = false
  if (!props.namespace) { reset(); return }
  try {
    const cur = await namespaceApi.limitrangeGet(props.clusterId, props.namespace)
    reset()
    if (!cur) return
    enabled.value = true
    for (const item of cur.limits ?? []) {
      const matched = LR_TYPES.find((t) => t.type === item.type)
      if (!matched) continue   // 未建模类型（ContainerFixed 等）由后端 overlay 原样保留，不在编辑器内呈现
      for (const f of LR_FIELDS) {
        for (const d of LR_DIMS) {
          state[matched.type].cells[cellKey(f.key, d.key)] = item[f.key]?.[d.key] ?? null
        }
      }
      state[matched.type].enabled = true
    }
  } catch {
    reset()
    loadFailed.value = true
  }
}

defineExpose({ load, toPayload, hasAnyValue, isValid, enabled, loadFailed })
</script>

<template>
  <el-card shadow="never" class="lr-sec">
    <template #header>
      <div class="ls-head">
        <span class="ls-title">限制范围（LimitRange）</span>
        <span class="ls-head-right">
          <el-switch v-model="enabled" @change="onToggleSection" />
          <FieldHelp tip="关闭并保存 = 删除该命名空间的 LimitRange 对象（含平台未建模的类型，如 ContainerFixed）。开启才会创建/更新名为 default 的限制范围对象。" />
        </span>
      </div>
    </template>

    <el-alert
      v-if="loadFailed"
      type="warning"
      :closable="false"
      show-icon
      class="ls-loadfail"
      title="未能读取该命名空间的现有限制范围——保存将跳过本区块，不会修改或删除集群中已存在的 LimitRange 对象。请刷新页面（或重新选择集群）后再修改。"
    />

    <div v-if="!enabled" class="ls-off">
      未启用 —— 保存时不会创建限制范围；若该命名空间已有 LimitRange，将被整体删除。
    </div>

    <div v-else class="ls-body">
      <div v-for="t in LR_TYPES" :key="t.type" class="ls-type" :class="{ 'is-on': state[t.type].enabled }">
        <div class="ls-type-head">
          <span class="ls-type-label">{{ t.label }} <FieldHelp :tip="t.hint" /></span>
          <span class="ls-type-switch">
            <span class="ls-switch-text">启用该类型</span>
            <el-switch v-model="state[t.type].enabled" @change="(v: boolean | string | number) => onToggleType(t.type, v)" />
            <FieldHelp tip="关闭该类型并保存 = 从 LimitRange 中删除该类型的限制项（平台仅建模 Container / Pod / PersistentVolumeClaim 三类，其余类型原样保留）。" />
          </span>
        </div>

        <div v-if="state[t.type].enabled" class="ls-grid">
          <div class="ls-row ls-headrow">
            <span class="ls-cell-label">约束项</span>
            <span v-for="d in LR_DIMS" :key="d.key" class="ls-cell">{{ d.label }}（单位 {{ d.unit }}）</span>
          </div>
          <div
            v-for="f in LR_FIELDS"
            :key="f.key"
            class="ls-row"
            :class="{ 'ls-row-ratio': f.kind === 'ratio' }"
          >
            <span class="ls-cell-label">
              {{ f.label }}
              <FieldHelp :tip="fieldTip(f, t.scope)" />
            </span>
            <span v-for="d in LR_DIMS" :key="d.key" class="ls-cell" :class="{ 'cell-invalid': invalidCells.has(issueKey(t.type, f.key, d.key)) }">
              <el-input
                :model-value="displayOf(t.type, f.key, d.key)"
                :placeholder="PLACEHOLDER[`${f.key}|${d.key}`] ?? ''"
                @update:model-value="(v: number | string | null | undefined) => onCell(t.type, f.key, d.key, v)"
              >
                <template v-if="unitOf(f.key, d.key)" #append>{{ unitOf(f.key, d.key) }}</template>
              </el-input>
            </span>
          </div>
          <div v-if="typeHint(t.type)" class="ls-hint">{{ typeHint(t.type) }}</div>
        </div>
        <div v-else class="ls-type-off">已关闭 —— 保存后该类型的限制项将被删除。</div>
      </div>
      <div class="ls-foot">留空 = 该项不设约束；清空的项保存后会从该类型中删除。数值序约束：max ≥ min、default ≥ defaultRequest、ratio ≥ 1。</div>
    </div>
  </el-card>
</template>

<style scoped>
.ls-loadfail {
  margin-bottom: 10px;
}
.ls-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ls-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.ls-head-right {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.ls-off {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}
.ls-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ls-type {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
}
.ls-type.is-on {
  border-color: var(--el-color-primary-light-7);
}
.ls-type-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ls-type-label {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}
.ls-type-switch {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.ls-switch-text {
  font-size: 12px;
  color: var(--text-3);
}
.ls-type-off {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-3);
}
.ls-grid {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ls-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.ls-headrow {
  font-size: 12px;
  color: var(--text-3);
}
.ls-cell-label {
  width: 210px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: var(--text-2);
  line-height: var(--el-component-size, 32px);
}
.ls-cell {
  flex: 1;
  min-width: 0;
}
.ls-hint {
  font-size: 12px;
  line-height: 16px;
  color: var(--el-color-danger);
}
.ls-foot {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}
/* 违规格红框（ResourcesEditor 的 .cell.is-error 范式） */
.cell-invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
</style>
