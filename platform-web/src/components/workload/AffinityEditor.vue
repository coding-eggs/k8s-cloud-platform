<script setup lang="ts">
import type { Affinity, NodeSelectorRequirement, NodeSelectorTerm, PodAffinityTerm } from '@/types/workload'
import LabelEditor from './LabelEditor.vue'

const model = defineModel<Affinity>()

const OPERATORS = ['In', 'NotIn', 'Exists', 'DoesNotExist', 'Gt', 'Lt']

type NodeListKey = 'matchExpressions' | 'matchFields'

function blankRequirement(): NodeSelectorRequirement {
  return { key: '', operator: 'In', values: [] }
}

function blankNodeTerm(): NodeSelectorTerm {
  return { matchExpressions: null, matchFields: null }
}

function blankPodTerm(): PodAffinityTerm {
  return { namespaces: [], topologyKey: '', matchLabels: {} }
}

/** nodeAffinity.required.nodeSelectorTerms 加 term（逐级惰性初始化） */
function addNodeRequiredTerm(): void {
  const m = model.value ?? (model.value = {})
  const na = m.nodeAffinity ?? (m.nodeAffinity = {})
  const req = na.required ?? (na.required = { nodeSelectorTerms: [] })
  req.nodeSelectorTerms.push(blankNodeTerm())
}

function removeNodeRequiredTerm(i: number): void {
  model.value?.nodeAffinity?.required?.nodeSelectorTerms.splice(i, 1)
  prune()
}

/** nodeAffinity.preferred 加项（weight + preference） */
function addNodePreferred(): void {
  const m = model.value ?? (model.value = {})
  const na = m.nodeAffinity ?? (m.nodeAffinity = {})
  const pref = na.preferred ?? (na.preferred = [])
  pref.push({ weight: 1, preference: blankNodeTerm() })
}

function removeNodePreferred(i: number): void {
  model.value?.nodeAffinity?.preferred?.splice(i, 1)
  prune()
}

/** podAntiAffinity.required 加 term */
function addPodRequired(): void {
  const m = model.value ?? (model.value = {})
  const pa = m.podAntiAffinity ?? (m.podAntiAffinity = {})
  const req = pa.required ?? (pa.required = [])
  req.push(blankPodTerm())
}

function removePodRequired(i: number): void {
  model.value?.podAntiAffinity?.required?.splice(i, 1)
  prune()
}

/** podAntiAffinity.preferred 加项（weight + podAffinityTerm） */
function addPodPreferred(): void {
  const m = model.value ?? (model.value = {})
  const pa = m.podAntiAffinity ?? (m.podAntiAffinity = {})
  const pref = pa.preferred ?? (pa.preferred = [])
  pref.push({ weight: 1, podAffinityTerm: blankPodTerm() })
}

function removePodPreferred(i: number): void {
  model.value?.podAntiAffinity?.preferred?.splice(i, 1)
  prune()
}

/** NodeSelectorTerm.matchExpressions / matchFields 多行增删（同 VolumeEditor items 习惯） */
function addExpr(term: NodeSelectorTerm, kind: NodeListKey): void {
  const list = term[kind] ?? (term[kind] = [])
  list.push(blankRequirement())
}

function removeExpr(term: NodeSelectorTerm, kind: NodeListKey, i: number): void {
  term[kind]?.splice(i, 1)
  prune()
}

/** 切到 Exists/DoesNotExist 时清空 values（k8s 校验要求这两种 operator 不带 values） */
function setOperator(r: NodeSelectorRequirement, op: string): void {
  r.operator = op
  if (op === 'Exists' || op === 'DoesNotExist') r.values = []
}

/** NodeSelectorTerm 是否有实际内容（空 term 在 k8s OR 语义下匹配所有节点，会令整个 required 失效，必须剔除） */
function nodeTermHasContent(t: NodeSelectorTerm): boolean {
  return (t.matchExpressions?.length ?? 0) > 0 || (t.matchFields?.length ?? 0) > 0
}

/** 删除后逐层清掉空壳：空结构 = 未配置（同 ProbeEditor「子对象存在才算配置」） */
function prune(): void {
  const m = model.value
  if (!m) return
  const na = m.nodeAffinity
  if (na) {
    if (na.required) {
      // k8s 对 nodeSelectorTerms 做 OR：先剔掉无内容 term，再判空壳
      const kept = na.required.nodeSelectorTerms.filter(nodeTermHasContent)
      if (kept.length === 0) na.required = null
      else if (kept.length !== na.required.nodeSelectorTerms.length) na.required.nodeSelectorTerms = kept
    }
    if (na.preferred) {
      const keptPref = na.preferred.filter((p) => nodeTermHasContent(p.preference))
      if (keptPref.length === 0) na.preferred = null
      else if (keptPref.length !== na.preferred.length) na.preferred = keptPref
    }
    if (!na.required && !na.preferred) m.nodeAffinity = null
  }
  const pa = m.podAntiAffinity
  if (pa) {
    if (pa.required && pa.required.length === 0) pa.required = null
    if (pa.preferred && pa.preferred.length === 0) pa.preferred = null
    if (!pa.required && !pa.preferred) m.podAntiAffinity = null
  }
  if (!m.nodeAffinity && !m.podAntiAffinity) model.value = undefined
}
</script>

<template>
  <div class="affinity-editor">
    <el-collapse>
      <!-- 节点亲和 -->
      <el-collapse-item title="节点亲和 nodeAffinity" name="node">
        <!-- required -->
        <div class="af-section">
          <div class="af-head">
            <span class="af-label">requiredDuringSchedulingIgnoredDuringExecution</span>
            <el-button link type="primary" @click="addNodeRequiredTerm">+ 添加 Term</el-button>
          </div>
          <div v-for="(term, i) in model?.nodeAffinity?.required?.nodeSelectorTerms ?? []" :key="i" class="af-block">
            <div class="af-row">
              <span class="af-sublabel">Term {{ i + 1 }}</span>
              <el-button class="af-del" link type="danger" @click="removeNodeRequiredTerm(i)">删除</el-button>
            </div>
            <div class="af-sub">
              <div v-for="(r, j) in term.matchExpressions ?? []" :key="j" class="af-row">
                <el-input v-model="r.key" placeholder="key（节点标签）" style="width: 150px" />
                <el-select :model-value="r.operator" @update:model-value="(op: string) => setOperator(r, op)" style="width: 130px">
                  <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
                </el-select>
                <el-select
                  v-if="r.operator !== 'Exists' && r.operator !== 'DoesNotExist'"
                  v-model="r.values"
                  multiple filterable allow-create default-first-option :reserve-keyword="false"
                  :placeholder="r.operator === 'Gt' || r.operator === 'Lt' ? 'value（单个，如 1）' : 'values（输入后回车）'"
                  style="flex: 1; min-width: 180px"
                />
                <el-button link type="danger" @click="removeExpr(term, 'matchExpressions', j)">删除</el-button>
              </div>
              <el-button link type="primary" @click="addExpr(term, 'matchExpressions')">+ 添加 matchExpression</el-button>
            </div>
            <div class="af-sub">
              <div v-for="(r, j) in term.matchFields ?? []" :key="j" class="af-row">
                <el-input v-model="r.key" placeholder="key（节点字段）" style="width: 150px" />
                <el-select :model-value="r.operator" @update:model-value="(op: string) => setOperator(r, op)" style="width: 130px">
                  <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
                </el-select>
                <el-select
                  v-if="r.operator !== 'Exists' && r.operator !== 'DoesNotExist'"
                  v-model="r.values"
                  multiple filterable allow-create default-first-option :reserve-keyword="false"
                  :placeholder="r.operator === 'Gt' || r.operator === 'Lt' ? 'value（单个，如 1）' : 'values（输入后回车）'"
                  style="flex: 1; min-width: 180px"
                />
                <el-button link type="danger" @click="removeExpr(term, 'matchFields', j)">删除</el-button>
              </div>
              <el-button link type="primary" @click="addExpr(term, 'matchFields')">+ 添加 matchField</el-button>
            </div>
          </div>
        </div>

        <!-- preferred -->
        <div class="af-section">
          <div class="af-head">
            <span class="af-label">preferredDuringSchedulingIgnoredDuringExecution</span>
            <el-button link type="primary" @click="addNodePreferred">+ 添加项</el-button>
          </div>
          <div v-for="(item, i) in model?.nodeAffinity?.preferred ?? []" :key="i" class="af-block">
            <div class="af-row">
              <span class="af-sublabel">第 {{ i + 1 }} 项</span>
              <span class="af-sublabel">weight</span>
              <el-input-number v-model="item.weight" :min="1" :max="100" controls-position="right" style="width: 110px" />
              <el-button class="af-del" link type="danger" @click="removeNodePreferred(i)">删除</el-button>
            </div>
            <div class="af-sub">
              <div v-for="(r, j) in item.preference.matchExpressions ?? []" :key="j" class="af-row">
                <el-input v-model="r.key" placeholder="key（节点标签）" style="width: 150px" />
                <el-select :model-value="r.operator" @update:model-value="(op: string) => setOperator(r, op)" style="width: 130px">
                  <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
                </el-select>
                <el-select
                  v-if="r.operator !== 'Exists' && r.operator !== 'DoesNotExist'"
                  v-model="r.values"
                  multiple filterable allow-create default-first-option :reserve-keyword="false"
                  :placeholder="r.operator === 'Gt' || r.operator === 'Lt' ? 'value（单个，如 1）' : 'values（输入后回车）'"
                  style="flex: 1; min-width: 180px"
                />
                <el-button link type="danger" @click="removeExpr(item.preference, 'matchExpressions', j)">删除</el-button>
              </div>
              <el-button link type="primary" @click="addExpr(item.preference, 'matchExpressions')">+ 添加 matchExpression</el-button>
            </div>
            <div class="af-sub">
              <div v-for="(r, j) in item.preference.matchFields ?? []" :key="j" class="af-row">
                <el-input v-model="r.key" placeholder="key（节点字段）" style="width: 150px" />
                <el-select :model-value="r.operator" @update:model-value="(op: string) => setOperator(r, op)" style="width: 130px">
                  <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
                </el-select>
                <el-select
                  v-if="r.operator !== 'Exists' && r.operator !== 'DoesNotExist'"
                  v-model="r.values"
                  multiple filterable allow-create default-first-option :reserve-keyword="false"
                  :placeholder="r.operator === 'Gt' || r.operator === 'Lt' ? 'value（单个，如 1）' : 'values（输入后回车）'"
                  style="flex: 1; min-width: 180px"
                />
                <el-button link type="danger" @click="removeExpr(item.preference, 'matchFields', j)">删除</el-button>
              </div>
              <el-button link type="primary" @click="addExpr(item.preference, 'matchFields')">+ 添加 matchField</el-button>
            </div>
          </div>
        </div>
      </el-collapse-item>

      <!-- Pod 反亲和 -->
      <el-collapse-item title="Pod 反亲和 podAntiAffinity" name="pod">
        <!-- required -->
        <div class="af-section">
          <div class="af-head">
            <span class="af-label">requiredDuringSchedulingIgnoredDuringExecution</span>
            <el-button link type="primary" @click="addPodRequired">+ 添加 Term</el-button>
          </div>
          <div v-for="(term, i) in model?.podAntiAffinity?.required ?? []" :key="i" class="af-block">
            <div class="af-row">
              <span class="af-sublabel">Term {{ i + 1 }}</span>
              <el-button class="af-del" link type="danger" @click="removePodRequired(i)">删除</el-button>
            </div>
            <div class="af-row">
              <el-select
                v-model="term.namespaces"
                multiple filterable allow-create default-first-option :reserve-keyword="false"
                placeholder="namespaces（空 = 当前命名空间）" style="width: 240px"
              />
              <el-input v-model="term.topologyKey" placeholder="topologyKey（如 kubernetes.io/hostname）" style="width: 260px" />
            </div>
            <div class="af-sub">
              <span class="af-sublabel">matchLabels</span>
              <LabelEditor v-if="term.matchLabels" v-model="term.matchLabels" />
              <el-button v-else link type="primary" @click="term.matchLabels = {}">+ 添加 matchLabels</el-button>
            </div>
          </div>
        </div>

        <!-- preferred -->
        <div class="af-section">
          <div class="af-head">
            <span class="af-label">preferredDuringSchedulingIgnoredDuringExecution</span>
            <el-button link type="primary" @click="addPodPreferred">+ 添加项</el-button>
          </div>
          <div v-for="(item, i) in model?.podAntiAffinity?.preferred ?? []" :key="i" class="af-block">
            <div class="af-row">
              <span class="af-sublabel">第 {{ i + 1 }} 项</span>
              <span class="af-sublabel">weight</span>
              <el-input-number v-model="item.weight" :min="1" :max="100" controls-position="right" style="width: 110px" />
              <el-button class="af-del" link type="danger" @click="removePodPreferred(i)">删除</el-button>
            </div>
            <div class="af-row">
              <el-select
                v-model="item.podAffinityTerm.namespaces"
                multiple filterable allow-create default-first-option :reserve-keyword="false"
                placeholder="namespaces（空 = 当前命名空间）" style="width: 240px"
              />
              <el-input v-model="item.podAffinityTerm.topologyKey" placeholder="topologyKey（如 kubernetes.io/hostname）" style="width: 260px" />
            </div>
            <div class="af-sub">
              <span class="af-sublabel">matchLabels</span>
              <LabelEditor v-if="item.podAffinityTerm.matchLabels" v-model="item.podAffinityTerm.matchLabels" />
              <el-button v-else link type="primary" @click="item.podAffinityTerm.matchLabels = {}">+ 添加 matchLabels</el-button>
            </div>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.affinity-editor { width: 100% }
.af-section { margin-bottom: 12px }
.af-head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px }
.af-label { font-size: 12px; color: var(--el-text-color-secondary); word-break: break-all }
.af-block { margin-bottom: 8px; padding: 8px; border: 1px dashed var(--el-border-color); border-radius: 4px }
.af-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px }
.af-del { margin-left: auto }
.af-sub { padding-left: 12px; margin-bottom: 8px }
.af-sublabel { font-size: 12px; color: var(--el-text-color-secondary) }
</style>
