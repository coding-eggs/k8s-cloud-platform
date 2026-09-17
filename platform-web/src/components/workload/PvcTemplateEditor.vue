<script setup lang="ts">
import type { PvcTemplate } from '@/types/workload'
import { ensureModelList } from './modelList'
import { useResourceOptions } from '@/composables/useResourceOptions'

const { options } = useResourceOptions()

// disabled：编辑页传入。StatefulSet 的 volumeClaimTemplates 创建后不可修改（k8s 限制），整块只读。
const props = withDefaults(defineProps<{ disabled?: boolean }>(), { disabled: false })

const model = defineModel<PvcTemplate[]>()

const ACCESS_MODES = ['ReadWriteOnce', 'ReadOnlyMany', 'ReadWriteMany', 'ReadWriteOncePod'] as const
const VOLUME_MODES = ['Filesystem', 'Block'] as const

const GIB = 2 ** 30

function add(): void {
  ensureModelList(model).push({ name: '', accessModes: ['ReadWriteOnce'], storage: GIB, storageClassName: null, volumeMode: 'Filesystem' })
}

function remove(i: number): void {
  if (props.disabled) return
  model.value?.splice(i, 1)
}

/** 容量：model 存字节（number|null）；显示换算成 Gi，输入换算回字节。 */
function storageNum(p: PvcTemplate): string {
  if (p.storage == null || Number.isNaN(p.storage)) return ''
  return String(Number((p.storage / GIB).toPrecision(12)))
}
function onStorageInput(p: PvcTemplate, raw: string): void {
  const num = raw.replace(/[^0-9.]/g, '') // 只保留数字与小数点
  p.storage = (num === '' || Number.isNaN(Number(num))) ? null : Number(num) * GIB
}
</script>

<template>
  <div class="pvc-editor">
    <div v-for="(p, i) in model" :key="i" class="pvc-item">
      <div class="pvc-item-header">
        <span class="pvc-item-title">模板 {{ i + 1 }}</span>
        <el-button v-if="!disabled" link type="danger" @click="remove(i)">删除</el-button>
      </div>
      <el-form label-width="84px" label-position="right">
        <el-form-item label="名称">
          <el-input v-model="p.name" :disabled="disabled" placeholder="与 volumeMount 同名" style="width: 260px" />
        </el-form-item>
        <el-form-item label="访问模式">
          <el-select v-model="p.accessModes" :disabled="disabled" multiple collapse-tags placeholder="accessModes" style="width: 300px">
            <el-option v-for="m in ACCESS_MODES" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="容量">
          <el-input
            :model-value="storageNum(p)"
            :disabled="disabled"
            placeholder="仅数字，如 1、500"
            style="width: 200px"
            @input="(v: string) => onStorageInput(p, v)"
          >
            <template #append>Gi</template>
          </el-input>
        </el-form-item>
        <el-form-item label="存储类">
          <el-select v-model="p.storageClassName" :disabled="disabled" filterable allow-create default-first-option clearable placeholder="storageClassName（可选）" style="width: 260px">
            <el-option v-for="n in options.storageClasses" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="卷模式">
          <el-select v-model="p.volumeMode" :disabled="disabled" placeholder="volumeMode" style="width: 200px">
            <el-option v-for="m in VOLUME_MODES" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
      </el-form>
    </div>

    <el-button v-if="!disabled" class="add-row-btn" plain @click="add">+ 添加 volumeClaimTemplate</el-button>
    <!-- C4：前端提示——后端严格校验每个模板名须被某容器以同名 volumeMount 引用 -->
    <div v-if="model?.length && !disabled" class="c4-hint">请确保有容器以同名 volumeMount 引用</div>
    <div v-if="disabled" class="c4-hint warn">StatefulSet 的存储卷模板创建后不可修改，此处仅供查看。</div>
  </div>
</template>

<style scoped>
.pvc-editor { width: 100% }
.pvc-item { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 12px 16px 4px; margin-bottom: 12px; background: var(--el-fill-color-blank) }
.pvc-item-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px }
.pvc-item-title { font-weight: 600; font-size: 13px }
.add-row-btn { width: 100% }
.c4-hint { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px }
.c4-hint.warn { color: var(--el-color-warning) }
</style>
