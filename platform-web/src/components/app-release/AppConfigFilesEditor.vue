<script setup lang="ts">
/**
 * 配置文件编辑（服务配置页「配置文件」页签与灰度对话框共用）。
 * 校验规则在 desiredFormUtil；是否可提交由调用方用 configFileProblems 自己判断。
 * 样式非 scoped + 前缀 app-cfgf-（会被放进 Element Plus 对话框）。
 */
import { computed } from 'vue'
import EmptyState from '@/components/EmptyState.vue'
import { FILE_REF_FIELDS, refToken } from './dependencyUtil'
import { configFileProblems } from './desiredFormUtil'
import type { AppConfigFile, AppDependencyRow } from '@/types'

const props = defineProps<{
  modelValue: AppConfigFile[] | undefined
  /** 持久化挂载点（无则 null），路径不能与它冲突 */
  dataMount: string | null
  dependencies: AppDependencyRow[]
  serviceKeys: string[]
  disabled?: boolean
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: AppConfigFile[]): void }>()

const list = computed(() => props.modelValue ?? [])
const problems = computed(() => configFileProblems(props.modelValue, props.dataMount))
const refHint = computed(() => {
  const keys = [...props.dependencies.map((d) => d.key), ...props.serviceKeys]
  const sample = keys[0] ? refToken(keys[0], 'host') : '${order-db.host}'
  return `内容可引用 ${FILE_REF_FIELDS.map((f) => `.${f}`).join(' / ')}，如 ${sample}；发布时替换成实际值。`
    + '不能引用 username / password（文件明文进 ConfigMap），凭据请用环境变量。'
})
function add(): void {
  emit('update:modelValue', [...list.value, { path: '', content: '' }])
}
function remove(i: number): void {
  emit('update:modelValue', list.value.filter((_, j) => j !== i))
}
</script>

<template>
  <div class="app-cfgf">
    <div class="app-cfgf__hint">
      每个文件以只读方式挂载到容器里的指定路径（ConfigMap 卷，按文件挂，不覆盖所在目录的其他文件）；内容改了发布时会滚动 Pod。
      {{ refHint }}
    </div>
    <div v-for="(f, i) in list" :key="i" class="app-cfgf__file">
      <div class="app-cfgf__head">
        <el-input v-model="f.path" :disabled="disabled" placeholder="容器内绝对路径，如 /etc/app/application.yaml" />
        <el-button link type="danger" :disabled="disabled" @click="remove(i)">删除</el-button>
      </div>
      <el-input
        v-model="f.content"
        type="textarea"
        :disabled="disabled"
        :autosize="{ minRows: 4, maxRows: 16 }"
        class="app-cfgf__body"
        placeholder="文件内容"
      />
      <div v-if="problems[i]" class="app-cfgf__err">{{ problems[i] }}</div>
    </div>
    <EmptyState v-if="!list.length" title="没有配置文件" description="需要把配置文件放进容器时在这里添加，例如 nginx.conf、application.yaml。" />
    <el-button v-if="!disabled" plain class="app-cfgf__add" @click="add">+ 添加配置文件</el-button>
  </div>
</template>

<style>
.app-cfgf { width: 100%; }
.app-cfgf__hint { margin-bottom: 12px; color: var(--text-3); font-size: 12.5px; line-height: 1.6; }
.app-cfgf__file { margin-bottom: 14px; padding: 10px 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.app-cfgf__head { display: flex; align-items: center; gap: 10px; }
.app-cfgf__body { margin-top: 8px; font-family: var(--el-font-family-mono, monospace); }
.app-cfgf__err { margin-top: 6px; color: var(--el-color-danger); font-size: 12px; }
.app-cfgf__add { margin-top: 4px; }
</style>
