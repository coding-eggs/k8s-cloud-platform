<script setup lang="ts">
import { reactive } from 'vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import type { K8sL4RouteRule, K8sRouteBackendRef } from '@/types'

/**
 * UDPRoute 的**规则表单**（只有后端列表 —— UDPRoute 只允许一条规则）。整体搬迁自原 `UdpRouteEditorView.vue` 的规则部分。
 *
 * <p>契约与其它 *RouteForm 同：只有 `setRules(raw)` / `toDto()` / `validate()` 三个方法，外壳不持有规则状态。
 * 模型就是那一条规则（与旧编辑器的内部模型一致），`toDto()` 把它包成 DTO 的 rules 数组。
 */
interface BackendRow {
  name: string
  namespace: string
  port: number | null
  weight: number | null
  /** UI 不编辑，仅回填透传 */
  group?: string | null
  kind?: string | null
}


function newBackend(): BackendRow { return { name: '', namespace: '', port: null, weight: null } }

/** 本 kind 只有一条规则 */
const rule = reactive({ name: '', backendRefs: [newBackend()] as BackendRow[] })

function addBackend(): void { rule.backendRefs.push(newBackend()) }
function removeBackend(i: number): void { rule.backendRefs.splice(i, 1) }

function fromBackend(b: K8sRouteBackendRef): BackendRow {
  return { name: b.name ?? '', namespace: b.namespace ?? '', port: b.port ?? null, weight: b.weight ?? null, group: b.group, kind: b.kind }
}

// ---------- 外壳回填：DTO → 模型 ----------
function setRules(raw: unknown): void {
  const list = (raw as K8sL4RouteRule[] | null) ?? []
  const first = list[0]
  rule.name = first?.name ?? ''
  rule.backendRefs = (first?.backendRefs ?? []).map(fromBackend)
  if (!rule.backendRefs.length) rule.backendRefs = [newBackend()]
}

// ---------- 外壳提交：模型 → DTO ----------
function toDto(): K8sL4RouteRule[] {
  return [
    {
      name: rule.name.trim() || null,
      backendRefs: rule.backendRefs
        .filter((b) => b.name.trim())
        .map((b) => ({
          name: b.name.trim(),
          namespace: b.namespace.trim() || null,
          port: b.port,
          weight: b.weight,
          group: b.group ?? null,
          kind: b.kind ?? null,
        })),
    },
  ]
}

// ---------- 校验（只校验后端；名称 / 父资源在外壳） ----------
function validate(): string | null {
  const backends = rule.backendRefs.filter((b) => b.name.trim())
  if (!backends.length) return '至少需要一个后端（backendRefs）'
  for (const b of backends) {
    if (b.port == null || b.port < 1 || b.port > 65535) return `后端「${b.name}」的端口须为 1–65535`
    if (b.weight != null && (b.weight < 0 || b.weight > 1000000)) return `后端「${b.name}」的权重须为 0–1000000`
  }
  return null
}

defineExpose({ validate, setRules, toDto })
</script>

<template>
        <el-divider content-position="left">规则（rules；UDPRoute 只允许一条）</el-divider>

        <el-form-item label="规则名">
          <el-input v-model="rule.name" placeholder="可选" style="max-width: 300px" />
          <FieldHelp tip="规则的名称，仅用于可读性。留空即可。" />
        </el-form-item>

        <el-form-item label="后端（backendRefs）" required>
          <div class="list-rows">
            <div v-for="(b, bi) in rule.backendRefs" :key="bi" class="list-row">
              <el-input v-model="b.name" placeholder="Service 名" style="flex: 1" />
              <el-input v-model="b.namespace" placeholder="命名空间（留空=本 ns）" style="width: 180px" />
              <el-input-number v-model="b.port" :min="1" :max="65535" controls-position="right" placeholder="端口" style="width: 130px" />
              <el-input-number v-model="b.weight" :min="0" :max="1000000" controls-position="right" placeholder="权重" style="width: 130px" />
              <el-button v-if="rule.backendRefs.length > 1" link type="danger" @click="removeBackend(bi)">删除</el-button>
            </div>
            <el-button size="small" @click="addBackend">添加后端</el-button>
          </div>
          <FieldHelp tip="目标 Service 名 + 端口（必填）+ 权重。权重是相对值不是百分比：两个后端各 50 与各 1 效果相同；权重 0 表示不向它转发。默认 kind=Service。L4 没有按路径/头的分流，只有权重。" />
        </el-form-item>

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
