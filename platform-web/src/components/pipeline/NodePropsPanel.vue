<script setup lang="ts">
/**
 * 右侧属性面板。**完全由后端给的 schema 渲染** —— 加字段不用改这里一行。
 *
 * 三类受控行为：
 * 1. `IMAGE` 字段挂**镜像候选补全**（S3.1 那套 `useImageSuggest`）——
 *    「不用背镜像名」正是 S3.1 存在的理由，在这里兑现。
 * 2. `SELECT` 用 `options` 渲染。
 * 3. **选预设只换默认值**：`preset` 变化时把 `image` / `command` 两个框填上该预设的值，
 *    **不锁死任何字段**（用户明确要求：预设只是预设，都能改）。
 */
import { computed } from 'vue'
import { usePipelineNodeTypes } from '@/composables/usePipelineNodeTypes'
import { useImageSuggest } from '@/composables/useImageSuggest'
import type { PipelineCanvasNode, PipelineConnection, RegistrySuggestItem } from '@/types'

const props = defineProps<{
  node: PipelineCanvasNode
  /** 可引用的 git 连接（窄投影，只有被授权的才在里面） */
  gitConnections: PipelineConnection[]
  /** 可引用的镜像仓库连接（同上；镜像节点的目标地址由它决定） */
  registryConnections: PipelineConnection[]
}>()
const emit = defineEmits<{ (e: 'change'): void }>()

const { byType, optionsOf, presetOf } = usePipelineNodeTypes()
const { fetchImageSuggestions } = useImageSuggest()

const schema = computed(() => byType(props.node.type))
const fields = computed(() => schema.value?.fields ?? [])
const isShell = computed(() => props.node.type === 'shell')
const isGit = computed(() => props.node.type === 'git')

function valueOf(key: string): string {
  return props.node.config[key] ?? ''
}

function setValue(key: string, v: string) {
  props.node.config[key] = v
  emit('change')
}

/**
 * 选预设 = 把它的镜像与命令填进去。两个框之后照样能改。
 *
 * ⚠️ **必须挂在"用户改了这个控件"上，不能挂 watch**：面板在切换选中节点时不卸载，
 * 而 watch 的 getter 依赖 `props.node` —— 换节点时它会拿**新节点的** preset 执行一次，
 * 把用户在旧节点上手改过的 image/command 静默覆盖掉（而那正是规格最强调的"预设只是默认值"）。
 */
function onPresetChange(key: string) {
  setValue('preset', key)
  const p = presetOf(key)
  if (!p) return
  if (p.image) setValue('image', p.image)
  if (p.command) setValue('command', p.command)
}

/** IMAGE 字段的候选：**整段替换**（后端给的已经是完整镜像串），候选右侧标来源连接名 */
function onImageFetch(query: string, cb: (items: { value: string; connectionName?: string }[]) => void) {
  fetchImageSuggestions(query, (items: RegistrySuggestItem[]) =>
    cb(items.map((i) => ({ value: i.value, connectionName: i.connectionName }))),
  )
}
</script>

<template>
  <div class="props">
    <div class="props-title">{{ node.id }}</div>
    <el-empty v-if="!schema" description="未知节点类型" :image-size="60" />

    <template v-for="f in fields" :key="f.key">
      <div class="props-field">
        <label>{{ f.label }}<span v-if="f.required" class="req">*</span></label>

        <!-- git 连接：候选来自**被授权**的窄投影；没被授权的不出现在这里（Review Focus #4） -->
        <el-select
          v-if="isGit && f.key === 'connectionId'"
          :model-value="valueOf(f.key)"
          clearable
          placeholder="不选 = 匿名拉取公开仓库"
          style="width: 100%"
          @update:model-value="(v: string) => setValue(f.key, v ?? '')"
        >
          <el-option v-for="c in gitConnections" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>

        <el-select
          v-else-if="f.key === 'preset'"
          :model-value="valueOf(f.key)"
          style="width: 100%"
          @update:model-value="(v: string) => onPresetChange(v ?? '')"
        >
          <el-option v-for="o in optionsOf(f)" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <!-- 镜像节点的目标仓库：**必须下拉**，候选只含被授权可用的（窄投影，没有凭据内容） -->
        <el-select
          v-else-if="f.key === 'registryConnectionId'"
          :model-value="valueOf(f.key)"
          placeholder="选一条被授权的镜像仓库连接"
          style="width: 100%"
          @update:model-value="(v: string) => setValue(f.key, v ?? '')"
        >
          <el-option v-for="c in registryConnections" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>

        <el-select
          v-else-if="f.type === 'SELECT'"
          :model-value="valueOf(f.key)"
          style="width: 100%"
          @update:model-value="(v: string) => setValue(f.key, v ?? '')"
        >
          <el-option
            v-for="o in optionsOf(f)"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <!-- IMAGE：挂候选补全。失败静默、选中整段替换 —— 与 S3.1 的两处完全一致 -->
        <el-autocomplete
          v-else-if="f.type === 'IMAGE'"
          :model-value="valueOf(f.key)"
          :fetch-suggestions="onImageFetch"
          :placeholder="f.placeholder ?? ''"
          style="width: 100%"
          @update:model-value="(v: string) => setValue(f.key, v)"
        >
          <template #default="{ item }">
            <div class="suggest-row">
              <span class="suggest-value">{{ item.value }}</span>
              <span v-if="item.connectionName" class="suggest-source">{{ item.connectionName }}</span>
            </div>
          </template>
        </el-autocomplete>

        <el-input
          v-else-if="f.type === 'TEXTAREA'"
          type="textarea"
          :rows="4"
          :model-value="valueOf(f.key)"
          :placeholder="f.placeholder ?? ''"
          @update:model-value="(v: string) => setValue(f.key, v)"
        />

        <el-input
          v-else
          :model-value="valueOf(f.key)"
          :placeholder="f.placeholder ?? ''"
          @update:model-value="(v: string) => setValue(f.key, v)"
        />

        <div v-if="f.help" class="props-help">{{ f.help }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.props {
  width: 300px;
  flex: 0 0 300px;
  padding: 10px 12px;
  border-left: 1px solid var(--border, #ebeef5);
  overflow: auto;
}
.props-title {
  font-weight: 600;
  margin-bottom: 10px;
  word-break: break-all;
}
.props-field {
  margin-bottom: 12px;
}
.props-field label {
  display: block;
  font-size: 12px;
  margin-bottom: 4px;
  color: var(--text-dim, #606266);
}
.req {
  color: var(--danger, #f56c6c);
  margin-left: 2px;
}
.suggest-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.suggest-source {
  color: var(--text-dim, #909399);
  font-size: 12px;
}
.props-help {
  font-size: 12px;
  color: var(--text-dim, #909399);
  margin-top: 4px;
  line-height: 1.5;
}
</style>
