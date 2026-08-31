<script setup lang="ts">
import { QuestionFilled } from '@element-plus/icons-vue'

defineProps<{ tip: string }>()
</script>

<template>
  <el-tooltip :content="tip" placement="top" :show-after="100" popper-class="field-help-popper">
    <span class="field-help"><el-icon><QuestionFilled /></el-icon></span>
  </el-tooltip>
</template>

<style scoped>
/* inline-flex + vertical-align:middle → 与左侧文字同高、垂直居中（不再漂到右上角） */
.field-help {
  display: inline-flex;
  align-items: center;
  /* el-form 的 label 是 display:flex + align-items:flex-start → .field-help 是 flex item，
     vertical-align 不生效；用 align-self:center 让 icon 在 32px 行内垂直居中（与文字齐平） */
  align-self: center;
  line-height: 1;
  /* 非 flex 的普通内联上下文兜底 */
  vertical-align: middle;
  margin-left: 4px;
  color: var(--text-3);
  cursor: help;
}
.field-help :deep(.el-icon) {
  font-size: 12px;
}
.field-help:hover {
  color: var(--el-color-primary);
}
</style>

<style>
/* popper 渲染在 body 下，scoped 不生效 → 用全局样式限制宽度，避免长说明一行铺满 */
.field-help-popper.el-popper {
  max-width: 320px;
  white-space: normal;
}
</style>
