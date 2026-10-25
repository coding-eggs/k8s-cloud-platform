<script setup lang="ts">
import { computed } from 'vue'

/**
 * 节点右键菜单。
 *
 * <p>坐标是视口坐标（`contextmenu` 事件的 clientX/clientY），所以用 fixed 定位；
 * 靠边缘时往回收一点，避免菜单被窗口截掉。
 */
const props = defineProps<{
  open: boolean
  x: number
  y: number
  /** 这个节点当前挂了几条线；0 时「断开全部连线」置灰 */
  edgeCount: number
}>()

const emit = defineEmits<{
  close: []
  duplicate: []
  disconnect: []
  remove: []
}>()

const WIDTH = 168
const HEIGHT = 132

const style = computed(() => ({
  left: `${Math.max(8, Math.min(props.x, window.innerWidth - WIDTH - 8))}px`,
  top: `${Math.max(8, Math.min(props.y, window.innerHeight - HEIGHT - 8))}px`,
}))
</script>

<template>
  <template v-if="open">
    <!-- 透明遮罩：点画布任意处、或再右键，都算关菜单 -->
    <div class="node-menu__backdrop" @click="emit('close')" @contextmenu.prevent="emit('close')" />
    <div class="node-menu" :style="style">
      <button type="button" class="node-menu__item" @click="emit('duplicate')">复制节点</button>
      <button
        type="button"
        class="node-menu__item"
        :disabled="!edgeCount"
        @click="emit('disconnect')"
      >
        断开全部连线
        <span v-if="edgeCount" class="node-menu__count">{{ edgeCount }}</span>
      </button>
      <div class="node-menu__sep" />
      <button type="button" class="node-menu__item node-menu__item--danger" @click="emit('remove')">
        移除节点
      </button>
    </div>
  </template>
</template>

<style scoped>
.node-menu__backdrop {
  position: fixed;
  inset: 0;
  z-index: 2999;
}
.node-menu {
  position: fixed;
  z-index: 3000;
  width: 168px;
  padding: 4px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color-overlay);
  box-shadow: var(--el-box-shadow-light);
}
.node-menu__item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 7px 10px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--el-text-color-regular);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.node-menu__item:hover:not(:disabled) {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
.node-menu__item:disabled {
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
}
.node-menu__item--danger:hover {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.node-menu__count {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.node-menu__sep {
  height: 1px;
  margin: 4px 6px;
  background: var(--el-border-color-lighter);
}
</style>
