<script setup lang="ts">
/**
 * 资源与命令表单项（服务配置页「资源与命令」页签与灰度对话框共用）：CPU / 内存请求与上限、启动命令与参数、拉取策略。
 * 直接改 form 的字段（调用方的响应式表单对象）；只渲染 el-form-item，外层 el-form 由调用方给。
 * 空值的清理（空串、新加的空命令项）由调用方组装提交时做，见 desiredFormUtil.cleanCommandLists。
 */
import { computed } from 'vue'
import { parseQuantity } from '@/utils/quantity'
import AppQuantityInput from './AppQuantityInput.vue'
import StringListEditor from './StringListEditor.vue'
import type { AppServiceDesired } from '@/types'

const props = defineProps<{
  form: AppServiceDesired
  disabled?: boolean
}>()

/** 软校验：请求不能大于上限（K8s 会拒绝，发布时才报就晚了） */
const problem = computed(() => {
  const over = (req?: string | null, lim?: string | null): boolean => {
    const r = parseQuantity(req); const l = parseQuantity(lim)
    return Number.isFinite(r) && Number.isFinite(l) && r > l
  }
  const out: string[] = []
  if (over(props.form.cpuRequest, props.form.cpuLimit)) out.push('CPU 请求大于上限')
  if (over(props.form.memoryRequest, props.form.memoryLimit)) out.push('内存请求大于上限')
  return out.join('；')
})
</script>

<template>
  <el-form-item label="CPU 请求"><AppQuantityInput v-model="form.cpuRequest" kind="cpu" :disabled="disabled" placeholder="如 0.1，留空不设" /></el-form-item>
  <el-form-item label="CPU 上限"><AppQuantityInput v-model="form.cpuLimit" kind="cpu" :disabled="disabled" placeholder="如 1，留空不设" /></el-form-item>
  <el-form-item label="内存请求"><AppQuantityInput v-model="form.memoryRequest" kind="memory" :disabled="disabled" placeholder="如 128，留空不设" /></el-form-item>
  <el-form-item label="内存上限">
    <AppQuantityInput v-model="form.memoryLimit" kind="memory" :disabled="disabled" placeholder="如 512，留空不设" />
    <div v-if="problem" class="app-resf__err">{{ problem }}</div>
  </el-form-item>
  <el-form-item label="启动命令"><StringListEditor v-model="form.command" :disabled="disabled" placeholder="一个参数，如 sh；留空则用镜像默认" /></el-form-item>
  <el-form-item label="启动参数"><StringListEditor v-model="form.args" :disabled="disabled" placeholder="一个参数，如 -c" /></el-form-item>
  <el-form-item label="拉取策略">
    <el-select v-model="form.imagePullPolicy" clearable placeholder="跟随默认" style="width: 200px" :disabled="disabled">
      <el-option label="Always" value="Always" />
      <el-option label="IfNotPresent" value="IfNotPresent" />
      <el-option label="Never" value="Never" />
    </el-select>
  </el-form-item>
</template>

<style>
.app-resf__err { width: 100%; margin-top: 6px; color: var(--el-color-danger); font-size: 12px; }
</style>
