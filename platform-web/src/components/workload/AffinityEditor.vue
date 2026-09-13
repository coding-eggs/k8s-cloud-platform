<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Affinity, NodeSelectorTerm, PodAffinityTerm } from '@/types/workload'
import NodeTermEditor from './NodeTermEditor.vue'
import PodTermEditor from './PodTermEditor.vue'
import FieldHelp from './FieldHelp.vue'

const model = defineModel<Affinity>()

type Kind = 'node' | 'pod' | 'podAnti'
type Mode = 'required' | 'preferred'

/** 一条亲和规则。content 四选一，按 (kind, mode) 决定用哪个字段：
 *  node+required → nodeRequired；node+preferred → nodePreferred；
 *  pod/podAnti+required → podRequired；pod/podAnti+preferred → podPreferred */
interface Rule {
  id: number
  kind: Kind
  mode: Mode
  nodeRequired?: NodeSelectorTerm[]
  nodePreferred?: { weight: number; preference: NodeSelectorTerm }[]
  podRequired?: PodAffinityTerm[]
  podPreferred?: { weight: number; podAffinityTerm: PodAffinityTerm }[]
}

const rules = ref<Rule[]>([])
let nextId = 0
/** 防回环：记住上一次 emit 出去的 Affinity 的 JSON 快照，watch 里比对跳过自己触发的回声 */
let lastEmitted: string | null = null

const KINDS: Kind[] = ['node', 'pod', 'podAnti']
const MODES: Mode[] = ['required', 'preferred']

const KIND_LABEL: Record<Kind, string> = { node: '节点亲和', pod: 'Pod 亲和', podAnti: 'Pod 反亲和' }
function kindLabel(k: Kind): string { return KIND_LABEL[k] }

/** 各亲和类型的作用说明（随选中类型变化） */
const TYPE_TIP: Record<Kind, string> = {
  node: '约束 Pod 调度到哪些节点：按节点的标签(label)或字段(field)匹配，支持 In/NotIn/Gt/Lt 等运算符、多组 OR，比 nodeSelector 更灵活。',
  pod: '让本 Pod 尽量靠近运行了匹配 Pod 的节点：按拓扑域(topologyKey，如 kubernetes.io/hostname)划分，用于把相关 Pod（如同一服务实例、需低延迟互访的组件）调度到同一拓扑域。',
  podAnti: '让本 Pod 避开运行了匹配 Pod 的节点：按拓扑域(topologyKey，如 kubernetes.io/hostname)划分，用于把同类 Pod 打散到不同节点做高可用。',
}
function typeTip(k: Kind): string { return TYPE_TIP[k] }
const MODE_TIP = '必需(required)：硬性约束，调度器必须满足，否则 Pod 无法调度（Pending）。偏好(preferred)：软性约束，尽量满足并按 weight(1-100) 加权择优，不满足也能调度。'
const T_WEIGHT = '偏好权重（1-100），越大越优先；调度时综合各条偏好的加权得分择优放置。'

// ---------- 空值工厂 ----------
function blankNodeTerm(): NodeSelectorTerm {
  return { matchExpressions: null, matchFields: null }
}
function blankPodTerm(): PodAffinityTerm {
  return { namespaces: [], topologyKey: '', matchLabels: {} }
}

/** 按 (kind, mode) 确保该规则当前槽有内容（新增 / 换类型 / 翻策略时调用）。
 *  只初始化目标槽、不清空其它槽 —— 切换后再切回可恢复原数据；toAffinity 只读取当前 (kind,mode) 槽，故不会误发其它槽 */
function applyBlankContent(r: Rule): void {
  if (r.kind === 'node' && r.mode === 'required') { if (!r.nodeRequired?.length) r.nodeRequired = [blankNodeTerm()] }
  else if (r.kind === 'node' && r.mode === 'preferred') { if (!r.nodePreferred?.length) r.nodePreferred = [{ weight: 1, preference: blankNodeTerm() }] }
  else if (r.mode === 'required') { if (!r.podRequired?.length) r.podRequired = [blankPodTerm()] }          // pod & podAnti 共用形状
  else { if (!r.podPreferred?.length) r.podPreferred = [{ weight: 1, podAffinityTerm: blankPodTerm() }] }    // pod & podAnti 共用形状
}

// ---------- model ↔ rules 双向同步（JSON 快照防回环） ----------
/** 外部 → 本地：model 变化（loadDetail 回填 / 重置）时重建 rules */
watch(model, (newAff) => {
  const snap = JSON.stringify(newAff ?? null)
  if (snap === lastEmitted) return            // 自己 emit 的回声 → 跳过，打断循环
  rules.value = fromAffinity(newAff)
}, { immediate: true, deep: true })

/** 本地 → 外部：rules 变化时派生 Affinity 并写回 model（空项在此剪枝） */
watch(rules, () => {
  const derived = toAffinity(rules.value)
  const snap = JSON.stringify(derived ?? null)
  if (snap === lastEmitted) return            // 无有效变化 → 不写，避免无谓触发
  lastEmitted = snap
  model.value = derived
}, { deep: true })

/** 从 Affinity 派生规则列表：每个有内容的 (kind,mode) 槽一条 */
function fromAffinity(a?: Affinity | null): Rule[] {
  const out: Rule[] = []
  if (a?.nodeAffinity?.required?.nodeSelectorTerms?.length) out.push({ id: nextId++, kind: 'node', mode: 'required', nodeRequired: a.nodeAffinity.required.nodeSelectorTerms })
  if (a?.nodeAffinity?.preferred?.length) out.push({ id: nextId++, kind: 'node', mode: 'preferred', nodePreferred: a.nodeAffinity.preferred })
  if (a?.podAffinity?.required?.length) out.push({ id: nextId++, kind: 'pod', mode: 'required', podRequired: a.podAffinity.required })
  if (a?.podAffinity?.preferred?.length) out.push({ id: nextId++, kind: 'pod', mode: 'preferred', podPreferred: a.podAffinity.preferred })
  if (a?.podAntiAffinity?.required?.length) out.push({ id: nextId++, kind: 'podAnti', mode: 'required', podRequired: a.podAntiAffinity.required })
  if (a?.podAntiAffinity?.preferred?.length) out.push({ id: nextId++, kind: 'podAnti', mode: 'preferred', podPreferred: a.podAntiAffinity.preferred })
  return out
}

/** NodeSelectorTerm 是否有实际内容（空 term 在 k8s OR 语义下匹配所有节点，会令整个 required 失效，必须剔除） */
function nodeTermHasContent(t: NodeSelectorTerm): boolean {
  return (t.matchExpressions?.length ?? 0) > 0 || (t.matchFields?.length ?? 0) > 0
}

/** 从规则列表派生 Affinity（含剪枝）。node 分支过滤空 term；pod/podAnti 仅整数组为空才丢弃。全空返回 undefined */
function toAffinity(rs: Rule[]): Affinity | undefined {
  const aff: Affinity = {}
  let any = false
  for (const r of rs) {
    if (r.kind === 'node' && r.mode === 'required') {
      const terms = (r.nodeRequired ?? []).filter(nodeTermHasContent)
      if (terms.length) { aff.nodeAffinity = { ...aff.nodeAffinity, required: { nodeSelectorTerms: terms } }; any = true }
    } else if (r.kind === 'node' && r.mode === 'preferred') {
      const pref = (r.nodePreferred ?? []).filter((p) => nodeTermHasContent(p.preference))
      if (pref.length) { aff.nodeAffinity = { ...aff.nodeAffinity, preferred: pref }; any = true }
    } else if (r.kind === 'pod' && r.mode === 'required') {
      const terms = r.podRequired ?? []
      if (terms.length) { aff.podAffinity = { ...aff.podAffinity, required: terms }; any = true }
    } else if (r.kind === 'pod' && r.mode === 'preferred') {
      const pref = r.podPreferred ?? []
      if (pref.length) { aff.podAffinity = { ...aff.podAffinity, preferred: pref }; any = true }
    } else if (r.kind === 'podAnti' && r.mode === 'required') {
      const terms = r.podRequired ?? []
      if (terms.length) { aff.podAntiAffinity = { ...aff.podAntiAffinity, required: terms }; any = true }
    } else if (r.kind === 'podAnti' && r.mode === 'preferred') {
      const pref = r.podPreferred ?? []
      if (pref.length) { aff.podAntiAffinity = { ...aff.podAntiAffinity, preferred: pref }; any = true }
    }
  }
  return any ? aff : undefined
}

// ---------- 增删 + 换类型 / 换策略 ----------
/** 其它行占用的 (kind|mode) 组合集合 */
function combosUsedExcept(excludeId: number): Set<string> {
  const s = new Set<string>()
  for (const x of rules.value) if (x.id !== excludeId) s.add(x.kind + '|' + x.mode)
  return s
}

/** 添加规则：取第一个未被占用的 (kind,mode)，上限 6（3 类型 × 2 策略） */
function addRule(): void {
  if (rules.value.length >= 6) return
  const used = new Set(rules.value.map((r) => r.kind + '|' + r.mode))
  for (const k of KINDS) for (const m of MODES) {
    if (!used.has(k + '|' + m)) {
      const r: Rule = { id: nextId++, kind: k, mode: m }
      applyBlankContent(r)
      rules.value.push(r)
      return
    }
  }
}

function removeRule(id: number): void {
  const i = rules.value.findIndex((r) => r.id === id)
  if (i >= 0) rules.value.splice(i, 1)   // rules watch 自动重派生 + 剪枝
}

/** 切换亲和类型：重置 content；若新类型的当前策略已被其它行占用，则自动翻到另一策略 */
function onKindChange(r: Rule, newKind: Kind): void {
  r.kind = newKind
  applyBlankContent(r)
  if (combosUsedExcept(r.id).has(newKind + '|' + r.mode)) {
    r.mode = r.mode === 'required' ? 'preferred' : 'required'
    applyBlankContent(r)   // content 形状可能随 mode 改变（node required↔preferred）
  }
}

/** 切换策略：必须重置 content——node 的 required(terms[]) 与 preferred({weight,preference}[]) 形状不同，
 *  不重置则切到偏好后 nodePreferred 仍为 undefined，点「+ 添加项」会在 undefined.push 上静默失败 */
function onModeChange(r: Rule, m: Mode): void {
  r.mode = m
  applyBlankContent(r)
}

/** 类型下拉：某 kind 的两种策略都被其它行占用 → 禁用 */
function typeDisabled(r: Rule, kind: Kind): boolean {
  const used = combosUsedExcept(r.id)
  return used.has(kind + '|required') && used.has(kind + '|preferred')
}

/** 策略下拉：当前 kind 的该 mode 已被其它行占用 → 禁用 */
function modeDisabled(r: Rule, mode: Mode): boolean {
  return combosUsedExcept(r.id).has(r.kind + '|' + mode)
}

// ---------- term 增删（按各 backing store） ----------
function addNodeReqTerm(r: Rule): void { r.nodeRequired!.push(blankNodeTerm()) }
function removeNodeReqTerm(r: Rule, i: number): void { r.nodeRequired!.splice(i, 1) }
function addNodePrefItem(r: Rule): void { r.nodePreferred!.push({ weight: 1, preference: blankNodeTerm() }) }
function removeNodePrefItem(r: Rule, i: number): void { r.nodePreferred!.splice(i, 1) }
function addPodReqTerm(r: Rule): void { r.podRequired!.push(blankPodTerm()) }
function removePodReqTerm(r: Rule, i: number): void { r.podRequired!.splice(i, 1) }
function addPodPrefItem(r: Rule): void { r.podPreferred!.push({ weight: 1, podAffinityTerm: blankPodTerm() }) }
function removePodPrefItem(r: Rule, i: number): void { r.podPreferred!.splice(i, 1) }
</script>

<template>
  <div class="affinity-editor">
    <el-button v-if="rules.length < 6" link type="primary" @click="addRule">+ 添加亲和规则</el-button>

    <div v-for="(r, ri) in rules" :key="r.id" class="af-rule">
      <!-- 行头：类型 + 策略 + 删除 -->
      <div class="af-row af-head">
        <span class="af-sublabel">规则 {{ ri + 1 }}</span>
        <span class="af-label">亲和类型</span>
        <el-select :model-value="r.kind" @update:model-value="(k: string) => onKindChange(r, k as Kind)" style="width: 150px">
          <el-option v-for="k in KINDS" :key="k" :label="kindLabel(k)" :value="k" :disabled="typeDisabled(r, k)" />
        </el-select>
        <FieldHelp :tip="typeTip(r.kind)" />
        <span class="af-label">策略</span>
        <el-select :model-value="r.mode" @update:model-value="(m: string) => onModeChange(r, m as Mode)" style="width: 120px">
          <el-option label="必需" value="required" :disabled="modeDisabled(r, 'required')" />
          <el-option label="偏好" value="preferred" :disabled="modeDisabled(r, 'preferred')" />
        </el-select>
        <FieldHelp :tip="MODE_TIP" />
        <el-button class="af-del" link type="danger" @click="removeRule(r.id)">删除</el-button>
      </div>

      <!-- node + required -->
      <template v-if="r.kind === 'node' && r.mode === 'required'">
        <el-button link type="primary" @click="addNodeReqTerm(r)">+ 添加 Term</el-button>
        <div v-for="(term, i) in r.nodeRequired ?? []" :key="i" class="af-block">
          <div class="af-row"><span class="af-sublabel">Term {{ i + 1 }}</span><el-button class="af-del" link type="danger" @click="removeNodeReqTerm(r, i)">删除</el-button></div>
          <NodeTermEditor :model-value="term" />
        </div>
      </template>

      <!-- node + preferred -->
      <template v-else-if="r.kind === 'node' && r.mode === 'preferred'">
        <el-button link type="primary" @click="addNodePrefItem(r)">+ 添加项</el-button>
        <div v-for="(item, i) in r.nodePreferred ?? []" :key="i" class="af-block">
          <div class="af-row">
            <span class="af-sublabel">第 {{ i + 1 }} 项</span>
            <span class="af-fl">weight<FieldHelp :tip="T_WEIGHT" /></span>
            <el-input-number v-model="item.weight" :min="1" :max="100" controls-position="right" style="width: 110px" />
            <el-button class="af-del" link type="danger" @click="removeNodePrefItem(r, i)">删除</el-button>
          </div>
          <NodeTermEditor :model-value="item.preference" />
        </div>
      </template>

      <!-- pod / podAnti + required -->
      <template v-else-if="r.mode === 'required'">
        <el-button link type="primary" @click="addPodReqTerm(r)">+ 添加 Term</el-button>
        <div v-for="(term, i) in r.podRequired ?? []" :key="i" class="af-block">
          <div class="af-row"><span class="af-sublabel">Term {{ i + 1 }}</span><el-button class="af-del" link type="danger" @click="removePodReqTerm(r, i)">删除</el-button></div>
          <PodTermEditor :model-value="term" />
        </div>
      </template>

      <!-- pod / podAnti + preferred -->
      <template v-else>
        <el-button link type="primary" @click="addPodPrefItem(r)">+ 添加项</el-button>
        <div v-for="(item, i) in r.podPreferred ?? []" :key="i" class="af-block">
          <div class="af-row">
            <span class="af-sublabel">第 {{ i + 1 }} 项</span>
            <span class="af-fl">weight<FieldHelp :tip="T_WEIGHT" /></span>
            <el-input-number v-model="item.weight" :min="1" :max="100" controls-position="right" style="width: 110px" />
            <el-button class="af-del" link type="danger" @click="removePodPrefItem(r, i)">删除</el-button>
          </div>
          <PodTermEditor :model-value="item.podAffinityTerm" />
        </div>
      </template>
    </div>

    <div v-if="rules.length === 0" class="af-empty">暂无亲和规则，点击「添加亲和规则」配置。</div>
  </div>
</template>

<style scoped>
.affinity-editor { width: 100% }
.af-rule { margin-bottom: 12px; padding: 8px; border: 1px solid var(--el-border-color); border-radius: 6px }
.af-head { flex-wrap: wrap; row-gap: 8px }
.af-label { font-size: 12px; color: var(--el-text-color-secondary); word-break: break-all }
.af-block { margin-top: 8px; padding: 8px; border: 1px dashed var(--el-border-color); border-radius: 4px }
.af-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px }
.af-fl { font-size: 12px; color: var(--el-text-color-secondary); white-space: nowrap }
.af-del { margin-left: auto }
.af-sublabel { font-size: 12px; color: var(--el-text-color-secondary) }
.af-empty { color: var(--text-3); font-size: 13px; padding: 8px 0 }
</style>
