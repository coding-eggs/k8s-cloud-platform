<script setup lang="ts">
import StatusBadge from './StatusBadge.vue'

type BadgeType = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

defineProps<{
  label: string
  type?: BadgeType
  /** 状态原因（非成功/失败时的说明）；为空时不显示悬浮提示 */
  reason?: string | null
}>()
</script>

<template>
  <el-tooltip :disabled="!reason" placement="top" :show-after="100" popper-class="status-reason-popper">
    <template #content v-if="reason">
      <div class="status-reason-text">{{ reason }}</div>
    </template>
    <StatusBadge :label="label" :type="type" />
  </el-tooltip>
</template>

<style scoped>
/* 让 tooltip 的触发元素（状态徽章）保持内联，不撑开表格单元格 */
:deep(.status-badge) { cursor: help }
</style>

<style>
/* popper 渲染在 body 下，scoped 不生效 → 全局样式：多行换行 + 限宽（reason 可能含 \n） */
.status-reason-popper.el-popper {
  max-width: 420px;
}
.status-reason-text {
  white-space: pre-line;
  line-height: 1.6;
  font-size: 12px;
}
</style>
