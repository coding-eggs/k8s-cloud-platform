<script setup lang="ts">
/**
 * 字段说明的小问号：鼠标悬停/聚焦给出这个字段**到底在做什么**。
 *
 * 用在表单标题后面（`<template #label>`）而不是塞一段 `form-tip`：
 * 说明长、且大部分时候用户不需要，铺在页面上会把表单撑得很长。
 */
defineProps<{
  /** 悬浮说明文本（纯文本，会自动转义） */
  text: string
  placement?: 'top' | 'bottom' | 'left' | 'right'
}>()
</script>

<template>
  <el-tooltip
    :content="text"
    :placement="placement ?? 'top'"
    effect="dark"
    :show-after="150"
    popper-class="field-helper-tip"
  >
    <!-- 图标是全局注册的（main.ts 注册了整套 @element-plus/icons-vue），这里不用 import -->
    <el-icon class="field-helper"><InfoFilled /></el-icon>
  </el-tooltip>
</template>

<style scoped>
.field-helper {
  margin-left: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  cursor: help;
  vertical-align: -2px;
}
.field-helper:hover {
  color: var(--el-color-primary);
}
</style>

<!-- 非 scoped：popper 被 teleport 到 body，scoped 的 data-v-* 匹配不到（同 el-dialog 的坑） -->
<style>
.field-helper-tip.el-popper {
  max-width: 340px;
  line-height: 1.6;
}
</style>
