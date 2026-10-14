<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import type { K8sL4RouteRule, K8sRouteBackendRef } from '@/types'

/**
 * TLSRoute 的**规则表单**（只有后端列表；SNI 主机名在外壳，与其它 kind 的 hostnames 同一件事）。
 * 整体搬迁自原 `TlsRouteEditorView.vue` 的规则部分。
 *
 * <p>契约与其它 *RouteForm 同：只有 `setRules(raw)` / `toDto()` / `validate()` 三个方法。
 */
const MAX_RULES = 16

interface BackendRow {
  name: string
  namespace: string
  port: number | null
  weight: number | null
  group?: string | null
  kind?: string | null
}
interface RuleRow {
  name: string
  backendRefs: BackendRow[]
}


function newBackend(): BackendRow { return { name: '', namespace: '', port: null, weight: null } }
function newRule(): RuleRow { return { name: '', backendRefs: [newBackend()] } }

const rules = ref<RuleRow[]>([newRule()])

function addRule(): void {
  if (rules.value.length >= MAX_RULES) { ElMessage.warning(`最多 ${MAX_RULES} 条规则`); return }
  rules.value.push(newRule())
}
function removeRule(i: number): void { rules.value.splice(i, 1) }
function addBackend(r: RuleRow): void { r.backendRefs.push(newBackend()) }
function removeBackend(r: RuleRow, i: number): void { r.backendRefs.splice(i, 1) }

function fromBackend(b: K8sRouteBackendRef): BackendRow {
  return { name: b.name ?? '', namespace: b.namespace ?? '', port: b.port ?? null, weight: b.weight ?? null, group: b.group, kind: b.kind }
}
function fromRule(r: K8sL4RouteRule): RuleRow {
  return { name: r.name ?? '', backendRefs: (r.backendRefs ?? []).map(fromBackend) }
}

// ---------- 外壳回填：DTO → 模型 ----------
function setRules(raw: unknown): void {
  const list = (raw as K8sL4RouteRule[] | null) ?? []
  rules.value = list.map(fromRule)
  if (!rules.value.length) rules.value = [newRule()]
}

// ---------- 外壳提交：模型 → DTO ----------
function toDto(): K8sL4RouteRule[] {
  return rules.value.map((r) => ({
    name: r.name.trim() || null,
    backendRefs: r.backendRefs
      .filter((b) => b.name.trim())
      .map((b) => ({
        name: b.name.trim(),
        namespace: b.namespace.trim() || null,
        port: b.port,
        weight: b.weight,
        group: b.group ?? null,
        kind: b.kind ?? null,
      })),
  }))
}

// ---------- 校验（只校验规则；名称 / 父资源 / SNI 在外壳） ----------
function validate(): string | null {
  if (!rules.value.length) return '至少需要一条规则'
  for (const [ri, r] of rules.value.entries()) {
    const no = `规则 #${ri + 1}`
    const backends = r.backendRefs.filter((b) => b.name.trim())
    if (!backends.length) return `${no}：至少需要一个后端`
    for (const b of backends) {
      if (b.port == null || b.port < 1 || b.port > 65535) return `${no}：后端「${b.name}」的端口须为 1–65535`
      if (b.weight != null && (b.weight < 0 || b.weight > 1000000)) return `${no}：后端「${b.name}」的权重须为 0–1000000`
    }
  }
  return null
}

defineExpose({ validate, setRules, toDto })
</script>

<template>
        <el-divider content-position="left">规则（rules；最多 16 条）</el-divider>

        <div v-for="(r, ri) in rules" :key="ri" class="sub-block rule-block">
          <div class="sub-head">
            <span class="sub-title">规则 #{{ ri + 1 }}</span>
            <el-button v-if="rules.length > 1" link type="danger" @click="removeRule(ri)">删除规则</el-button>
          </div>

          <el-form-item label="规则名">
            <el-input v-model="r.name" placeholder="可选（本路由内唯一）" style="max-width: 300px" />
            <FieldHelp tip="规则的名称，仅用于可读性。留空即可。" />
          </el-form-item>

          <el-form-item label="后端（backendRefs）" required>
            <div class="list-rows">
              <div v-for="(b, bi) in r.backendRefs" :key="bi" class="list-row">
                <el-input v-model="b.name" placeholder="Service 名" style="flex: 1" />
                <el-input v-model="b.namespace" placeholder="命名空间（留空=本 ns）" style="width: 180px" />
                <el-input-number v-model="b.port" :min="1" :max="65535" controls-position="right" placeholder="端口" style="width: 130px" />
                <el-input-number v-model="b.weight" :min="0" :max="1000000" controls-position="right" placeholder="权重" style="width: 130px" />
                <el-button v-if="r.backendRefs.length > 1" link type="danger" @click="removeBackend(r, bi)">删除</el-button>
              </div>
              <el-button size="small" @click="addBackend(r)">添加后端</el-button>
            </div>
            <FieldHelp tip="目标 Service 名 + 端口（必填）+ 权重（相对值不是百分比；0 = 不转发）。TLS 的分配维度只有权重。" />
          </el-form-item>
        </div>
        <el-button size="small" @click="addRule">添加规则</el-button>

</template>

<style scoped>
/* 规则块用到的样式；与外壳 scoped 样式各自一份（scoped 不共享，是刻意的） */
.sub-block {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px 14px 2px;
  margin-bottom: 12px;
  background: var(--panel-hover);
}
.rule-block { background: var(--panel); }
.sub-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.backend-row { max-width: 780px; margin-bottom: 8px; }
</style>
