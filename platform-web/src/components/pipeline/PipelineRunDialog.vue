<script setup lang="ts">
/**
 * 触发运行前的节点屏蔽：**在画布上图选**，不是列表。
 *
 * <p>点一个节点 = 屏蔽它和它的全部下游；再点一次 = 恢复它和它的上游。
 * 规则与后端 `PipelineCanvasMask.maskedClosure` 同一条 —— 这里只做交互便利，
 * 服务端会自己再展开一次，不信任提交的集合。
 *
 * <p>图就是编辑器里那张图（同一份画布、同一套节点卡片、同样的坐标），
 * 所以「哪个节点接哪个」不用再看文字猜。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type { Edge, Node } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import type { PipelineCanvasEdge, PipelineCanvasNode, PipelineFilePatch, PipelineSourceRef } from '@/types'
import { usePipelineNodeTypes } from '@/composables/usePipelineNodeTypes'
import PipelineNodeVue from '@/components/pipeline/PipelineNodeVue.vue'
import PipelineSourceFiles from '@/components/pipeline/PipelineSourceFiles.vue'
import { baseText, refText, repoShort } from '@/components/pipeline/sourceRefText'

const props = defineProps<{
  modelValue: boolean
  nodes: PipelineCanvasNode[]
  edges: PipelineCanvasEdge[]
  title?: string
  /** 触发前预览到的源码信息（父组件在打开时拉；失败不影响运行） */
  sources?: PipelineSourceRef[]
  previewLoading?: boolean
  previewError?: string | null
  /** 「查看变更」那一次请求在飞（它要下载整棵快照，比打开时那次慢得多） */
  diffLoading?: boolean
  /** 取某个文件的补丁（点开清单里一行时才调；来源由父组件决定：预览走 pipelineId） */
  patchLoader?: (source: PipelineSourceRef, path: string) => Promise<PipelineFilePatch>
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 第二个参数 = 本次要**钉住**的 commit（节点 id → 完整 sha），只含仍在跑且解析成功的 git 节点 */
  (e: 'confirm', maskedNodeIds: string[], pinnedShas: Record<string, string>): void
  /** 用户点了「查看变更」：父组件带 withDiff 再拉一次预览 */
  (e: 'request-diff'): void
}>()

/** 节点清单（图标 / 类型名 / 配色）—— 图上的卡片与编辑器里是同一套 */
const { types: nodeTypes, load: loadNodeTypes } = usePipelineNodeTypes()
/**
 * 一个弹窗一个 flow 实例。
 * ⚠️ id 不能省：编辑器页面上已经有一个 `<VueFlow>`，不显式给 id 时
 * `useVueFlow()` 会注入**外面那个** store —— 两张图会共用节点，`fitView` 也会去 fit 外面的画布。
 */
const flowId = `pipeline-mask-${Math.random().toString(36).slice(2, 9)}`
/** 弹窗在打开动画期间量到的视口偏小 —— `fit-view-on-init` 会把右侧节点算到框外，打开后再 fit 一次 */
const { fitView } = useVueFlow(flowId)

/** 选中的 = 本次要跑的；没选中的 = 被屏蔽 */
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

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    // 每次打开都回到「全部运行」—— 上一次的屏蔽不该悄悄带进这一次
    selected.value = new Set((props.nodes ?? []).map((n) => n.id))
    // 编辑器里通常已经拉过了（同一份缓存）—— 有就别再发一次请求
    if (!nodeTypes.value.length) void loadNodeTypes()
    // 等打开动画结束（尺寸稳定）再 fit。320ms 取的是 el-dialog 的过渡时长，
    // 早于它调用会按动画中的小视口算，右侧节点被算到框外
    void nextTick(() => setTimeout(() => void fitView({ padding: 0.15 }), 320))
  },
  { immediate: true },
)

/** 上游被屏蔽了 → 自己也必须被屏蔽（图上的卡片会说一句「上游已屏蔽」） */
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
    // 重新恢复时必须把上游也恢复（否则依赖不成立）
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

function onNodeClick(e: { node: { id: string } }) {
  const n = (props.nodes ?? []).find((x) => x.id === e.node.id)
  if (n) toggle(n, !selected.value.has(n.id))
}

function resetAll() {
  selected.value = new Set((props.nodes ?? []).map((n) => n.id))
}

/** 图上的节点：坐标、卡片与编辑器一致；未选中的显示为「已屏蔽」 */
const flowNodes = computed<Node[]>(() =>
  (props.nodes ?? []).map((n, i) => ({
    id: n.id,
    type: 'pipeline',
    position: n.position
      ? { x: n.position.x, y: n.position.y }
      : { x: 60 + (i % 3) * 220, y: 40 + Math.floor(i / 3) * 120 },
    data: {
      nodeType: n.type,
      name: n.name ?? '',
      config: n.config,
      finallyTask: n.finallyTask === true,
      // MASKED 是运行详情里也用的那个语义（虚线 + 灰）—— 这里借它表示「这次不跑」
      status: selected.value.has(n.id) ? undefined : 'MASKED',
      retryHint: blockedByUpstream(n.id) ? '上游已屏蔽' : undefined,
    },
  })),
)

/** 连线：一端被屏蔽就画成虚线 —— 切口在哪一眼看得见 */
const flowEdges = computed<Edge[]>(() =>
  (props.edges ?? []).map((e) => {
    const cut = !selected.value.has(e.from) || !selected.value.has(e.to)
    return {
      id: `e-${e.from}-${e.to}`,
      source: e.from,
      target: e.to,
      type: 'smoothstep',
      markerEnd: MarkerType.ArrowClosed,
      style: {
        stroke: cut ? 'var(--border)' : 'var(--border-strong)',
        strokeWidth: 1.5,
        strokeDasharray: cut ? '4 4' : undefined,
      },
    }
  }),
)

const runnableCount = computed(
  () => (props.nodes ?? []).filter((n) => selected.value.has(n.id) && n.finallyTask !== true).length,
)
const masked = computed(() => (props.nodes ?? []).filter((n) => !selected.value.has(n.id)))

// ---- 源码预览 ----

/** 本次要钉住的提交：**仍在跑**且解析出完整 sha 的 git 节点（被屏蔽的节点不传）。 */
const pinnedShas = computed(() => {
  const out: Record<string, string> = {}
  for (const s of props.sources ?? []) {
    if (s.sha && selected.value.has(s.nodeId)) out[s.nodeId] = s.sha
  }
  return out
})

/** 值不值得算变更：有基线、且与基线不是同一个提交（相同 = 这次没有新代码） */
function canDiff(s: PipelineSourceRef): boolean {
  return Boolean(s.sha && s.baseSha && s.sha !== s.baseSha)
}

function confirm() {
  emit('confirm', masked.value.map((n) => n.id), pinnedShas.value)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title ?? '触发运行'"
    width="min(1040px, 94vw)"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="hint">
      点击节点 = <b>屏蔽它和它的全部下游</b>（只影响这一次运行，流水线定义不变）；再点一次 = 恢复它和它的上游。
    </div>

    <!-- 源码：这次到底会构建哪个提交（「先看后跑」——运行按这里显示的 sha 钉住） -->
    <div v-if="previewLoading || previewError || (sources ?? []).length" class="sources">
      <div class="sources-title">源码</div>
      <div v-if="previewLoading" class="src-note">正在解析仓库…（要连远端仓库，最多约 30 秒）</div>
      <div v-else-if="previewError" class="src-note src-bad">源码预览不可用：{{ previewError }}</div>
      <template v-else>
        <div
          v-for="s in sources"
          :key="s.nodeId"
          class="src-item"
          :class="{ 'is-off': !selected.has(s.nodeId) }"
        >
          <div class="src-row">
            <span class="src-repo">{{ repoShort(s.repo) }}</span>
            <span class="src-ref">{{ refText(s) }}</span>
            <template v-if="s.sha">
              <code class="src-sha" :title="s.sha">{{ s.shortSha }}</code>
              <!-- 提交标题要浅取一个 commit 才有（与「查看变更」那一次一起取）—— 没有就不占位 -->
              <span v-if="s.subject" class="src-subject" :title="s.subject">{{ s.subject }}</span>
              <span class="src-base">{{ baseText(s) }}</span>
              <!-- 已经算过就不再显示按钮（清单已经摊在下面了） -->
              <el-button
                v-if="canDiff(s) && !(s.files && s.files.length)"
                link
                type="primary"
                class="src-diff"
                :loading="diffLoading"
                @click="emit('request-diff')"
              >
                查看变更
              </el-button>
            </template>
            <span v-else class="src-bad">{{ s.error || '未解析出提交' }}</span>
          </div>
          <!-- 变更清单：点过「查看变更」才有（要下载整棵快照，不能自动来） -->
          <PipelineSourceFiles
            v-if="s.files && s.files.length"
            :source="s"
            :max-height="220"
            :patch-loader="patchLoader"
            class="src-files"
          />
          <!-- 拿不到变更/提交信息的原因 —— 不影响运行，所以只是说明 -->
          <div v-if="s.warning" class="src-note src-warn">{{ s.warning }}</div>
        </div>
      </template>
    </div>

    <div class="graph">
      <VueFlow
        :id="flowId"
        :nodes="flowNodes"
        :edges="flowEdges"
        :nodes-draggable="false"
        :nodes-connectable="false"
        :edges-updatable="false"
        :zoom-on-double-click="false"
        :min-zoom="0.2"
        :max-zoom="1.6"
        fit-view-on-init
        @node-click="onNodeClick"
      >
        <template #node-pipeline="nodeProps">
          <PipelineNodeVue :id="nodeProps.id" :data="nodeProps.data" />
        </template>
        <Background :gap="18" :size="1.4" pattern-color="var(--border-strong)" />
        <Controls position="bottom-right" />
      </VueFlow>
      <div v-if="!(nodes ?? []).length" class="empty">这条流水线还没有节点</div>
    </div>

    <div class="summary">
      本次运行 <b>{{ runnableCount }}</b> 个节点，屏蔽 <b>{{ masked.length }}</b> 个
      <el-button v-if="masked.length" link type="primary" class="reset" @click="resetAll">
        全部恢复
      </el-button>
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
.graph {
  position: relative;
  height: 52vh;
  min-height: 360px;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--bg);
}
/* 图上的节点是**可点的**（点它 = 屏蔽/恢复）—— 光标本身就是提示 */
.graph :deep(.vue-flow__node) {
  cursor: pointer;
}
.empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: 13px;
  color: var(--text-3);
  pointer-events: none;
}
.summary {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-2);
}
.reset {
  margin-left: 8px;
  font-size: 12px;
}

/* ---- 源码预览 ---- */
.sources {
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
}
.sources-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
  margin-bottom: 6px;
}
.src-note {
  font-size: 12px;
  color: var(--text-3);
}
.src-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 12px;
  line-height: 20px;
}
.src-item {
  padding: 1px 0;
}
/* 被屏蔽的 git 节点：灰掉，表示这次不跑（它的 pin 也不会生效） */
.src-item.is-off {
  opacity: .45;
}
.src-files {
  margin: 2px 0 6px 0;
}
.src-diff {
  flex-shrink: 0;
  font-size: 12px;
}
.src-repo {
  color: var(--text-1);
  font-weight: 500;
  flex-shrink: 0;
}
.src-ref {
  color: var(--text-3);
  flex-shrink: 0;
}
.src-sha {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--panel-hover);
  color: var(--accent);
  flex-shrink: 0;
}
.src-subject {
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.src-base {
  margin-left: auto;
  flex-shrink: 0;
  color: var(--text-3);
}
.src-bad {
  color: var(--danger);
}
.src-warn {
  color: var(--warning);
}
</style>
