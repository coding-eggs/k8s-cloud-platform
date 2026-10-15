<script setup lang="ts">
/**
 * 画布上的一个节点卡片。
 *
 * 四件事：
 * 1. **长相由后端 schema 决定**（图标 / 配色语义名 / 副标题取哪几个字段）——
 *    加一个后端节点，它自带图标与配色，前端一行不改。
 * 2. **副标题是一句配置摘要**（git 节点显示仓库与分支、构建节点显示镜像名）——
 *    不点开也知道这个节点在干嘛。
 * 3. **标题是用户起的名字**（属性面板里填的），留空就显示节点 id。
 *    id 是 Tekton 的 task 名、也是日志与运行状态的键，**悬停卡片可见**（改名字不动它）。
 * 4. **配色只用语义名**（success / accent / warning…），落到 `var(--success)` 这类主题变量上 ——
 *    写死 hex 会在浅色/暗色另一半里翻车（这一版最初就是栽在这上面：用了不存在的变量名，
 *    暗色主题下节点变成白底黑字浮在深色画布上）。
 */
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { usePipelineNodeTypes } from '@/composables/usePipelineNodeTypes'

const props = defineProps<{
  id: string
  /** 编辑态才显示「从这里接下一个节点」的 + 按钮（运行视图是只读的） */
  showAdd?: boolean
  data: {
    nodeType: string
    /** 用户起的显示名（可空）—— 空就退回显示 id */
    name?: string
    config?: Record<string, string>
    /** 运行视图里才有 */
    status?: string
  }
}>()

const emit = defineEmits<{ (e: 'add', type: string): void }>()

const { types, byType, labelOf, iconOf, accentOf, summaryOf } = usePipelineNodeTypes()

const icon = computed(() => iconOf(props.data.nodeType))
const accent = computed(() => accentOf(props.data.nodeType))
/** 没起名就显示 id —— 总不能一片空白（id 也是 Tekton 的 task 名，悬停能看到） */
const title = computed(() => (props.data.name ?? '').trim() || props.id)
/** 类型名单独显示一行小字 —— 节点名是用户起的（clone），类型才是它是什么（git 克隆） */
const typeLabel = computed(() => {
  const n = byType(props.data.nodeType)?.label
  return n ? labelOf(props.data.nodeType) : props.data.nodeType
})
const summary = computed(() => summaryOf(props.data.nodeType, props.data.config))

const STATUS_CLASS: Record<string, string> = {
  SUCCEEDED: 'ok',
  FAILED: 'bad',
  CANCELLED: 'warn',
  TIMEOUT: 'warn',
  RUNNING: 'running',
}
const STATUS_TEXT: Record<string, string> = {
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMEOUT: '超时',
  RUNNING: '正在跑',
}
const statusClass = computed(() => (props.data.status ? STATUS_CLASS[props.data.status] ?? '' : ''))
const statusText = computed(() => (props.data.status ? STATUS_TEXT[props.data.status] ?? props.data.status : ''))
</script>

<template>
  <div class="pnode" :class="[`accent-${accent}`, statusClass]" :title="id">
    <Handle type="target" :position="Position.Left" class="pnode-port" />

    <div class="pnode-icon">
      <el-icon><component :is="icon" /></el-icon>
    </div>

    <div class="pnode-body">
      <div class="pnode-title">{{ title }}</div>
      <div class="pnode-type">{{ typeLabel }}</div>
      <div v-if="summary" class="pnode-summary">{{ summary }}</div>
    </div>

    <div v-if="statusText" class="pnode-status">{{ statusText }}</div>

    <Handle type="source" :position="Position.Right" class="pnode-port" />

    <!-- 编辑态：悬停时在右侧露出一个 +，从这里接下一个节点（不用回左边面板再拖一次） -->
    <el-dropdown
      v-if="showAdd"
      trigger="click"
      placement="right-start"
      @command="(t: string) => emit('add', t)"
      @click.stop
    >
      <button class="pnode-add" title="接一个下游节点" @click.stop>+</button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="t in types" :key="t.type" :command="t.type">
            {{ labelOf(t.type) }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.pnode {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  min-width: 190px;
  max-width: 240px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  box-shadow: var(--shadow-panel);
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.15s;
}
.pnode:hover {
  border-color: var(--border-strong);
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.1);
}
.pnode.selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}

.pnode-icon {
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  font-size: 15px;
  color: var(--accent);
  background: var(--accent-soft);
}
/* 配色只用语义名 → 落到主题变量，浅色与暗色各自取自己的色 */
.accent-success .pnode-icon {
  color: var(--success);
  background: color-mix(in srgb, var(--success) 12%, transparent);
}
.accent-warning .pnode-icon {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 12%, transparent);
}
.accent-danger .pnode-icon {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 12%, transparent);
}
.accent-info .pnode-icon {
  color: var(--info);
  background: color-mix(in srgb, var(--info) 12%, transparent);
}

.pnode-body {
  min-width: 0;
  flex: 1;
}
.pnode-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pnode-type {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 1px;
}
.pnode-summary {
  margin-top: 5px;
  font-size: 11px;
  color: var(--text-2);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 运行视图的着色：与运行历史里的状态口径一致 */
.pnode-status {
  position: absolute;
  top: -9px;
  right: 8px;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10px;
  background: var(--panel);
  border: 1px solid var(--border);
  color: var(--text-2);
}
.pnode.ok {
  border-color: var(--success);
}
.pnode.ok .pnode-status {
  color: var(--success);
  border-color: var(--success);
}
.pnode.bad {
  border-color: var(--danger);
}
.pnode.bad .pnode-status {
  color: var(--danger);
  border-color: var(--danger);
}
.pnode.warn {
  border-color: var(--warning);
}
.pnode.warn .pnode-status {
  color: var(--warning);
  border-color: var(--warning);
}
.pnode.running {
  border-color: var(--info);
}
.pnode.running .pnode-status {
  color: var(--info);
  border-color: var(--info);
}

.pnode-add {
  position: absolute;
  right: -11px;
  top: 50%;
  transform: translateY(-50%);
  width: 22px;
  height: 22px;
  padding: 0;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-2);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s, border-color 0.15s, color 0.15s;
}
.pnode:hover .pnode-add {
  opacity: 1;
}
.pnode-add:hover {
  border-color: var(--accent);
  color: var(--accent);
}

/* 连接点：小圆点，悬停时放大 */
:deep(.pnode-port) {
  width: 9px;
  height: 9px;
  border: 2px solid var(--panel);
  background: var(--border-strong);
  transition: background 0.15s, transform 0.15s;
}
.pnode:hover :deep(.pnode-port) {
  background: var(--accent);
}
</style>
