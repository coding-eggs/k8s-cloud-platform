<script setup lang="ts">
/**
 * 变量选择器：列出可引用的依赖与服务及其字段，点一项把 `${key.field}` 交给调用方插入。
 *
 * 依赖按登记信息列字段（外部连接没有端口 / 用户名 / 密码键的就不列），中间件依赖列全部字段，
 * 实例实际有没有某个字段由发布预览校验。username / password 引用的是 Secret，永远不会变成明文。
 * 服务只列有端口的（调用方传进来的 serviceKeys 已经过滤）。
 */
import { computed } from 'vue'
import type { AppDependencyRow } from '@/types'
import { DEP_FIELD_LABEL, SVC_FIELD_LABEL, SVC_FIELDS, depFields, refToken } from './dependencyUtil'

const props = defineProps<{
  dependencies?: AppDependencyRow[]
  serviceKeys?: string[]
  disabled?: boolean
  /** 按钮文案 */
  label?: string
}>()
const emit = defineEmits<{ (e: 'pick', token: string): void }>()

interface Group { label: string; items: { token: string; field: string; hint: string }[] }

const groups = computed<Group[]>(() => {
  const out: Group[] = []
  for (const d of props.dependencies ?? []) {
    out.push({
      label: `依赖 ${d.key}`,
      items: depFields(d).map((f) => ({ token: refToken(d.key, f), field: f, hint: DEP_FIELD_LABEL[f] ?? f })),
    })
  }
  for (const k of props.serviceKeys ?? []) {
    out.push({
      label: `服务 ${k}`,
      items: SVC_FIELDS.map((f) => ({ token: refToken(k, f), field: f, hint: SVC_FIELD_LABEL[f] ?? f })),
    })
  }
  return out
})
</script>

<template>
  <el-dropdown trigger="click" :disabled="disabled || !groups.length" @command="(t: string) => emit('pick', t)">
    <el-button :disabled="disabled || !groups.length" :title="groups.length ? '' : '暂无可引用的依赖或服务'">{{ label || '插入引用' }}</el-button>
    <template #dropdown>
      <el-dropdown-menu class="app-ref-picker__menu">
        <template v-for="g in groups" :key="g.label">
          <el-dropdown-item disabled class="app-ref-picker__group">{{ g.label }}</el-dropdown-item>
          <el-dropdown-item v-for="it in g.items" :key="it.token" :command="it.token">
            <code class="app-ref-picker__token">{{ it.token }}</code>
            <span class="app-ref-picker__hint">{{ it.hint }}</span>
          </el-dropdown-item>
        </template>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style>
/* el-dropdown 菜单挂在 body 下，scoped 打不到；唯一前缀 app-ref-picker__ */
.app-ref-picker__menu { max-height: 320px; overflow-y: auto; }
.el-dropdown-menu__item.app-ref-picker__group {
  font-size: 12px;
  font-weight: 600;
  opacity: 1;
}
.app-ref-picker__hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
