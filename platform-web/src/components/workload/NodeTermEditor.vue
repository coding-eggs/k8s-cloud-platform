<script setup lang="ts">
import type { NodeSelectorRequirement, NodeSelectorTerm } from '@/types/workload'
import FieldHelp from './FieldHelp.vue'

const model = defineModel<NodeSelectorTerm>()

const OPERATORS = ['In', 'NotIn', 'Exists', 'DoesNotExist', 'Gt', 'Lt']

/** 各字段作用说明（FieldHelp） */
const T_MEXP = '按节点标签(label)匹配的条件表达式。同一 Term 内的多条表达式是 AND（须全部满足）。'
const T_MFIELD = '按节点内置字段(field)匹配的条件。同一 Term 内的多条条件是 AND。'
const T_KEY_LABEL = '节点标签的键，如 zone、disktype。'
const T_KEY_FIELD = '节点字段的键，如 kubernetes.io/arch、metadata.name。'
const T_OPERATOR = '运算符：In/NotIn=取值在/不在 values；Exists/DoesNotExist=存在/不存在；Gt/Lt=数值大于/小于。'
const T_VALUES = '标签取值列表。Exists/DoesNotExist 不用填；Gt/Lt 只填一个。'

type ListKey = 'matchExpressions' | 'matchFields'

function blankReq(): NodeSelectorRequirement { return { key: '', operator: 'In', values: [] } }
function addExpr(kind: ListKey): void {
  const t = model.value ?? (model.value = { matchExpressions: null, matchFields: null })
  const list = t[kind] ?? (t[kind] = [])
  list.push(blankReq())
}
function removeExpr(kind: ListKey, i: number): void { model.value?.[kind]?.splice(i, 1) }
/** 切到 Exists/DoesNotExist 时清空 values（k8s 校验要求这两种 operator 不带 values） */
function setOperator(r: NodeSelectorRequirement, op: string): void {
  r.operator = op
  if (op === 'Exists' || op === 'DoesNotExist') r.values = []
}
</script>

<template>
  <div class="nt-editor">
    <!-- matchExpressions -->
    <div class="nt-sec">
      <div class="nt-head">
        <span class="nt-label">matchExpression<FieldHelp :tip="T_MEXP" /></span>
        <el-button link type="primary" @click="addExpr('matchExpressions')">+ 添加</el-button>
      </div>
      <div v-for="(req, j) in model?.matchExpressions ?? []" :key="j" class="nt-row">
        <span class="nt-fl">key<FieldHelp :tip="T_KEY_LABEL" /></span>
        <el-input v-model="req.key" placeholder="如 zone" style="width: 130px" />
        <span class="nt-fl">operator<FieldHelp :tip="T_OPERATOR" /></span>
        <el-select :model-value="req.operator" @update:model-value="(op: string) => setOperator(req, op)" style="width: 120px">
          <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
        </el-select>
        <template v-if="req.operator !== 'Exists' && req.operator !== 'DoesNotExist'">
          <span class="nt-fl">values<FieldHelp :tip="T_VALUES" /></span>
          <el-select
            v-model="req.values"
            multiple filterable allow-create default-first-option :reserve-keyword="false"
            :placeholder="req.operator === 'Gt' || req.operator === 'Lt' ? 'value（单个，如 1）' : '输入后回车'"
            style="flex: 1; min-width: 160px"
          />
        </template>
        <el-button link type="danger" @click="removeExpr('matchExpressions', j)">删除</el-button>
      </div>
    </div>

    <!-- matchFields -->
    <div class="nt-sec">
      <div class="nt-head">
        <span class="nt-label">matchField<FieldHelp :tip="T_MFIELD" /></span>
        <el-button link type="primary" @click="addExpr('matchFields')">+ 添加</el-button>
      </div>
      <div v-for="(req, j) in model?.matchFields ?? []" :key="j" class="nt-row">
        <span class="nt-fl">key<FieldHelp :tip="T_KEY_FIELD" /></span>
        <el-input v-model="req.key" placeholder="如 kubernetes.io/arch" style="width: 170px" />
        <span class="nt-fl">operator<FieldHelp :tip="T_OPERATOR" /></span>
        <el-select :model-value="req.operator" @update:model-value="(op: string) => setOperator(req, op)" style="width: 120px">
          <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
        </el-select>
        <template v-if="req.operator !== 'Exists' && req.operator !== 'DoesNotExist'">
          <span class="nt-fl">values<FieldHelp :tip="T_VALUES" /></span>
          <el-select
            v-model="req.values"
            multiple filterable allow-create default-first-option :reserve-keyword="false"
            :placeholder="req.operator === 'Gt' || req.operator === 'Lt' ? 'value（单个，如 1）' : '输入后回车'"
            style="flex: 1; min-width: 160px"
          />
        </template>
        <el-button link type="danger" @click="removeExpr('matchFields', j)">删除</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.nt-editor { width: 100% }
.nt-sec { margin-bottom: 8px }
.nt-head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px }
.nt-label { font-size: 12px; color: var(--el-text-color-secondary); font-weight: 600 }
.nt-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 6px }
.nt-fl { font-size: 12px; color: var(--el-text-color-secondary); white-space: nowrap }
</style>
