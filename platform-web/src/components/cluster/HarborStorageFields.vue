<script setup lang="ts">
/**
 * Harbor 的一块 PVC 存储设置（存储类 + 容量）。
 *
 * <p>单独成组件是为了让「内置的数据库/Redis / 镜像数据 / 作业日志 / 扫描器」在表单里
 * 各自紧挨着自己的开关，而不是散在通用「存储」分组里。
 */
defineProps<{
  /** 这一行是谁的存储（如「内置 PostgreSQL」） */
  label: string
  storageClass: string
  size: string
  options: string[]
  /** 编辑态：StatefulSet 的 volumeClaimTemplates 不可变，只读展示 */
  disabled: boolean
  sizePlaceholder?: string
  hint?: string
}>()

const emit = defineEmits<{
  (e: 'update:storageClass', v: string): void
  (e: 'update:size', v: string): void
}>()
</script>

<template>
  <div class="hs-row">
    <span class="hs-label">{{ label }}</span>
    <div class="hs-controls">
      <el-select :model-value="storageClass" filterable allow-create clearable
                 :disabled="disabled" placeholder="跟随集群默认存储类"
                 @update:model-value="(v: string) => emit('update:storageClass', v ?? '')">
        <el-option v-for="sc in options" :key="sc" :label="sc" :value="sc" />
      </el-select>
      <el-input :model-value="size" :disabled="disabled" spellcheck="false"
                :placeholder="sizePlaceholder ?? '如 5Gi'"
                @update:model-value="(v: string) => emit('update:size', v)" />
    </div>
    <div v-if="hint" class="hs-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.hs-row { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
.hs-label { font-size: 13px; color: var(--text-2); }
.hs-controls { display: grid; grid-template-columns: minmax(0, 1fr) 160px; gap: 8px; }
.hs-hint { font-size: 12px; color: var(--text-3); }
</style>
