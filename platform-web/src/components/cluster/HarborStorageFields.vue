<script setup lang="ts">
/**
 * Harbor 的一块 PVC 存储设置（存储类 + 容量）。
 *
 * <p>单独成组件是为了让「内置的数据库/Redis / 镜像数据 / 作业日志 / 扫描器」在表单里
 * 各自紧挨着自己的开关，而不是散在通用「存储」分组里。
 *
 * <p>编辑态**不是所有控件都锁**，所以锁的原因要写在控件旁边：
 * StorageClass 对任何已存在的 PVC 都不可变；容量只有在 PVC 是独立对象（registry /
 * jobservice）时才能扩容，StatefulSet 的 volumeClaimTemplates 连模板都改不了。
 */
defineProps<{
  /** 这一行是谁的存储（如「内置 PostgreSQL」） */
  label: string
  storageClass: string
  size: string
  options: string[]
  /** 编辑态：PVC 的 storageClassName 不可变，只读展示 */
  storageClassDisabled: boolean
  /**
   * 编辑态是否禁止改容量。
   * StatefulSet 的 volumeClaimTemplates 不可变 → true；
   * registry / jobservice 是独立 PVC，升级时可调大（需 SC 支持扩容）→ false。
   */
  sizeDisabled: boolean
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
    <div class="hs-head">
      <span class="hs-label">{{ label }}</span>
      <!-- 存储类锁 = 编辑态（安装时 storageClassDisabled 为 false，不显示任何标记） -->
      <template v-if="storageClassDisabled">
        <span class="hs-tag hs-tag-lock">存储类：创建后不可改</span>
        <span v-if="sizeDisabled" class="hs-tag hs-tag-lock">容量：模板不可变</span>
        <span v-else class="hs-tag hs-tag-ok">容量：可扩容（只能调大）</span>
      </template>
    </div>
    <div class="hs-controls">
      <el-select :model-value="storageClass" filterable allow-create clearable
                 :disabled="storageClassDisabled" placeholder="跟随集群默认存储类"
                 @update:model-value="(v: string) => emit('update:storageClass', v ?? '')">
        <el-option v-for="sc in options" :key="sc" :label="sc" :value="sc" />
      </el-select>
      <el-input :model-value="size" :disabled="sizeDisabled" spellcheck="false"
                :placeholder="sizePlaceholder ?? '如 5Gi'"
                @update:model-value="(v: string) => emit('update:size', v)" />
    </div>
    <div v-if="hint" class="hs-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.hs-row { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
.hs-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.hs-label { font-size: 13px; color: var(--text-2); }
.hs-tag { font-size: 12px; line-height: 18px; padding: 0 6px; border-radius: 4px;
          border: 1px solid var(--border-strong); color: var(--text-3); }
.hs-tag-ok { color: var(--success); border-color: var(--success); }
.hs-controls { display: grid; grid-template-columns: minmax(0, 1fr) 160px; gap: 8px; }
.hs-hint { font-size: 12px; color: var(--text-3); }
</style>
