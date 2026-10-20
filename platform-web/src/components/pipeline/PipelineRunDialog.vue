<script setup lang="ts">
/**
 * 触发运行前的节点勾选：**取消勾选 = 本次屏蔽它（连同全部下游）**。
 *
 * ⚠️ 级联规则与后端 `PipelineCanvasMask.maskedClosure` 同一条：
 * 屏蔽必须连同下游。这里做它只是交互便利 —— 服务端会自己再展开一次，不信任提交的集合。
 */
import { computed, ref, watch } from 'vue'
import type { PipelineCanvasEdge, PipelineCanvasNode } from '@/types'

const props = defineProps<{
  modelValue: boolean
  nodes: PipelineCanvasNode[]
  edges: PipelineCanvasEdge[]
  title?: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm', maskedNodeIds: string[]): void
}>()

const selected = ref<Set<string>>(new Set())

const parentsOf = computed(() => {
  const m = new Map<string, string[]>()
  for (const e of props.edges ?? []) {
    const list = m.get(e.to)
    if (list) list.push(e.from)
    else m.set(e.to, [e.from])
  }
  return m
})
const childrenOf = computed(() => {
  const m = new Map<string, string[]>()
  for (const e of props.edges ?? []) {
    const list = m.get(e.from)
    if (list) list.push(e.to)
    else m.set(e.from, [e.to])
  }
  return m
})

/** 拓扑序（Kahn）；收尾节点不参与连线，统一排在最后 */
const ordered = computed<PipelineCanvasNode[]>(() => {
  const nodes = props.nodes ?? []
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const indeg = new Map<string, number>()
  for (const n of nodes) indeg.set(n.id, (parentsOf.value.get(n.id) ?? []).length)
  const queue = nodes.filter((n) => (indeg.get(n.id) ?? 0) === 0).map((n) => n.id)
  const out: string[] = []
  while (queue.length) {
    const id = queue.shift()!
    out.push(id)
    for (const next of childrenOf.value.get(id) ?? []) {
      const d = (indeg.get(next) ?? 0) - 1
      indeg.set(next, d)
      if (d === 0) queue.push(next)
    }
  }
  for (const n of nodes) if (!out.includes(n.id)) out.push(n.id)
  const normal = out.filter((id) => byId.get(id)?.finallyTask !== true)
  const fin = out.filter((id) => byId.get(id)?.finallyTask === true)
  return [...normal, ...fin].map((id) => byId.get(id)!)
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) selected.value = new Set((props.nodes ?? []).map((n) => n.id))
  },
)

/** 上游被屏蔽了 → 自己也必须被屏蔽（勾选置灰且不可单独打开） */
function blockedByUpstream(id: string): boolean {
  return (parentsOf.value.get(id) ?? []).some((p) => !selected.value.has(p))
}

function uncheckWithDescendants(id: string) {
  const next = new Set(selected.value)
  const stack = [id]
  while (stack.length) {
    const cur = stack.pop()!
    next.delete(cur)
    stack.push(...(childrenOf.value.get(cur) ?? []))
  }
  selected.value = next
}

function toggle(node: PipelineCanvasNode, on: boolean) {
  if (on) {
    const next = new Set(selected.value)
    // 重新勾上时必须把上游也勾上（否则依赖不成立）
    const stack = [node.id]
    while (stack.length) {
      const cur = stack.pop()!
      next.add(cur)
      stack.push(...(parentsOf.value.get(cur) ?? []))
    }
    selected.value = next
  } else {
    uncheckWithDescendants(node.id)
  }
}

const runnableCount = computed(
  () => (props.nodes ?? []).filter((n) => selected.value.has(n.id) && n.finallyTask !== true).length,
)
const masked = computed(() => (props.nodes ?? []).filter((n) => !selected.value.has(n.id)))
const labelOf = (n: PipelineCanvasNode) => (n.name ?? '').trim() || n.id

function confirm() {
  emit('confirm', masked.value.map((n) => n.id))
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title ?? '触发运行'"
    width="560px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="hint">
      取消勾选 = <b>本次屏蔽它及它的全部下游</b>（定义不变）。默认全部运行。
    </div>
    <div class="list">
      <label v-for="n in ordered" :key="n.id" class="row" :class="{ blocked: blockedByUpstream(n.id) }">
        <el-checkbox
          :model-value="selected.has(n.id)"
          :disabled="blockedByUpstream(n.id)"
          @update:model-value="(v: boolean | string | number) => toggle(n, v === true)"
        />
        <span class="name">{{ labelOf(n) }}</span>
        <span v-if="n.finallyTask" class="tag">收尾</span>
        <span v-if="!n.finallyTask && blockedByUpstream(n.id)" class="why">上游已屏蔽</span>
      </label>
    </div>
    <div class="summary">
      本次运行 <b>{{ runnableCount }}</b> 个节点，屏蔽 <b>{{ masked.length }}</b> 个
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="runnableCount === 0" @click="confirm">触发</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 10px;
}
.list {
  max-height: 46vh;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  color: var(--text-1);
  cursor: pointer;
}
.row.blocked {
  cursor: not-allowed;
}
.row.blocked .name {
  color: var(--text-3);
}
.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag {
  font-size: 10px;
  color: var(--warning);
  border: 1px solid var(--warning);
  border-radius: 999px;
  padding: 0 6px;
}
.why {
  font-size: 11px;
  color: var(--text-3);
}
.summary {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-2);
}
</style>
