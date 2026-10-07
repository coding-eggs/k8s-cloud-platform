<script setup lang="ts">
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { emptyRule, type OpRow, type RuleRow } from './bgpFilterForm'

// 规则卡片列表：直接改父级传入的响应式数组（push/splice/字段绑定），无需 v-model
const props = defineProps<{ rules: RuleRow[] }>()

function addRule(): void { props.rules.push(emptyRule()) }
function removeRule(i: number): void { props.rules.splice(i, 1) }

function addOp(row: RuleRow): void { row.opsRows.push({ kind: 'addCommunity', value: '' }) }
function removeOp(row: RuleRow, i: number): void { row.opsRows.splice(i, 1) }

const OP_KIND_LABEL: Record<OpRow['kind'], string> = {
  addCommunity: '追加 community',
  prependAsPath: 'AS-Path Prepend',
  setPriority: '设置 priority',
}
</script>

<template>
  <div class="rule-list">
    <el-empty v-if="!rules.length" description="暂无规则（空列表 = 该方向不过滤）" :image-size="48" />

    <div v-for="(row, i) in rules" :key="i" class="rule-card">
      <div class="rule-head">
        <span class="rule-no">规则 {{ i + 1 }}</span>
        <el-button link type="danger" @click="removeRule(i)">删除规则</el-button>
      </div>

      <div class="rule-grid">
        <div class="cell">
          <label>CIDR <FieldHelp tip="要匹配的路由前缀（如 10.48.0.0/16）。留空 = 任意前缀。" /></label>
          <el-input v-model="row.cidr" placeholder="如 10.48.0.0/16（留空 = 任意前缀）" />
        </div>
        <div class="cell">
          <label>前缀长度 min / max <FieldHelp tip="限定匹配路由的前缀长度范围（0-128），与 CIDR 配合做更精确的匹配。留空 = 不限。" /></label>
          <div class="pair">
            <el-input-number v-model="row.prefixLengthMin" :min="0" :max="128" controls-position="right" placeholder="min" style="flex: 1" />
            <el-input-number v-model="row.prefixLengthMax" :min="0" :max="128" controls-position="right" placeholder="max" style="flex: 1" />
          </div>
        </div>
        <div class="cell">
          <label>匹配方式 <FieldHelp tip="对下方 communities / AS-Path 前缀值的匹配方式：Equal=等于、NotEqual=不等于、In=包含任一、NotIn=不包含。留空 = 默认（填了值即按存在性匹配）。" /></label>
          <el-select v-model="row.matchOperator" clearable placeholder="不限（默认）" style="width: 100%">
            <el-option label="Equal（等于）" value="Equal" />
            <el-option label="NotEqual（不等于）" value="NotEqual" />
            <el-option label="In（包含于）" value="In" />
            <el-option label="NotIn（不包含于）" value="NotIn" />
          </el-select>
        </div>
        <div class="cell">
          <label>对端类型 <FieldHelp tip="按 peering 类型过滤：eBGP（外部 AS）/ iBGP（内部 AS）。留空 = 不限。" /></label>
          <el-select v-model="row.peerType" clearable placeholder="不限（默认）" style="width: 100%">
            <el-option label="eBGP" value="eBGP" />
            <el-option label="iBGP" value="iBGP" />
          </el-select>
        </div>
        <div class="cell">
          <label>接口（interface）<FieldHelp tip="按出接口名匹配路由（高级用法，一般留空）。" /></label>
          <el-input v-model="row.iface" placeholder="按出接口匹配（高级，一般留空）" />
        </div>
        <div class="cell">
          <label>来源 <FieldHelp tip="RemotePeers = 只匹配从远端对端学到的路由。留空 = 不限。" /></label>
          <el-select v-model="row.source" clearable placeholder="不限（默认）" style="width: 100%">
            <el-option label="RemotePeers（来自远端对端）" value="RemotePeers" />
          </el-select>
        </div>
        <div class="cell">
          <label>Communities <FieldHelp tip="只匹配携带指定 community 值的路由（回车添加，如 64512:600）。与「匹配方式」配合使用。" /></label>
          <el-select
            v-model="row.communityValues" multiple filterable allow-create default-first-option :reserve-keyword="false"
            placeholder="按 community 值匹配（回车添加，如 64512:600）" style="width: 100%"
          />
        </div>
        <div class="cell">
          <label>AS-Path 前缀 <FieldHelp tip="只匹配 AS 路径以指定值开头的路由（回车添加，如 65001）。与「匹配方式」配合使用。" /></label>
          <el-select
            v-model="row.asPathPrefix" multiple filterable allow-create default-first-option :reserve-keyword="false"
            placeholder="按 AS 路径前缀匹配（回车添加，如 65001）" style="width: 100%"
          />
        </div>
        <div class="cell">
          <label>Priority <FieldHelp tip="规则优先级（新版字段，多条规则评估时的排序参考）。一般留空 = 默认。" /></label>
          <el-input-number v-model="row.priority" :min="0" controls-position="right" placeholder="默认" style="width: 100%" />
        </div>
        <div class="cell">
          <label>动作（action）<FieldHelp tip="命中本规则后的动作：Accept=放行；Reject=拒绝（该方向路由不再宣告/学习，用错会造成路由黑洞）。" /></label>
          <el-select v-model="row.action" style="width: 100%">
            <el-option label="Accept（放行）" value="Accept" />
            <el-option label="Reject（拒绝）" value="Reject" />
          </el-select>
        </div>

        <div class="cell span2">
          <label>附加操作（operations，命中后执行）<FieldHelp tip="规则命中（Accept 时）额外执行的动作，可多条：追加 community / AS-Path Prepend（增加路径开销降低优选度）/ 设置 priority。留空 = 仅放行或拒绝。" /></label>
          <div class="ops">
            <div v-for="(op, j) in row.opsRows" :key="j" class="op-line">
              <el-select v-model="op.kind" style="width: 180px">
                <el-option v-for="(label, k) in OP_KIND_LABEL" :key="k" :label="label" :value="k" />
              </el-select>
              <el-input
                v-model="op.value" class="op-value"
                :placeholder="op.kind === 'addCommunity' ? '如 64512:600' : op.kind === 'prependAsPath' ? 'AS 号，逗号分隔（如 65001,65001）' : '整数，如 100'"
              />
              <el-button link type="danger" @click="removeOp(row, j)">删除</el-button>
            </div>
            <el-button size="small" @click="addOp(row)">添加操作</el-button>
          </div>
        </div>
      </div>
    </div>

    <el-button class="add-rule" @click="addRule">添加规则</el-button>
  </div>
</template>

<style scoped>
.rule-list { display: flex; flex-direction: column; gap: 12px; }
.rule-card { border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; background: var(--panel); }
.rule-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.rule-no { font-size: 13px; font-weight: 600; color: var(--text-1); }
.rule-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 14px; }
.cell { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.cell.span2 { grid-column: span 2; }
.cell label { font-size: 12px; color: var(--text-2); }
.pair { display: flex; gap: 8px; }
.ops { display: flex; flex-direction: column; gap: 6px; }
.op-line { display: flex; align-items: center; gap: 8px; }
.op-value { flex: 1; }
.add-rule { align-self: flex-start; }
</style>
