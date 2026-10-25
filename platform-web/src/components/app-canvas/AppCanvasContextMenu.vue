<script setup lang="ts">
import { computed } from 'vue'

/**
 * 画布上的右键菜单：节点和连线共用一套外壳（定位、遮罩、样式），只是条目不同。
 *
 * <p>坐标是视口坐标（`contextmenu` 事件的 clientX/clientY），所以用 fixed 定位；
 * 靠边缘时往回收一点，避免菜单被窗口截掉。
 */
const props = defineProps<{
  open: boolean
  x: number
  y: number
  /** 右键的是节点还是连线 */
  targetKind?: 'node' | 'edge'
  /** 节点：当前挂了几条线；0 时「断开全部连线」置灰 */
  edgeCount?: number
  /** 连线：两端的节点 key，显示成「api → db-1」 */
  edgeLabel?: string
}>()

const emit = defineEmits<{
  close: []
  duplicate: []
  disconnect: []
  remove: []
  removeEdge: []
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
    <div class="ctx-menu__backdrop" @click="emit('close')" @contextmenu.prevent="emit('close')" />
    <div class="ctx-menu" :style="style">
      <template v-if="targetKind === 'edge'">
        <div class="ctx-menu__caption" :title="edgeLabel">{{ edgeLabel }}</div>
        <button type="button" class="ctx-menu__item ctx-menu__item--danger" @click="emit('removeEdge')">
          删除连线
        </button>
      </template>

      <template v-else>
        <button type="button" class="ctx-menu__item" @click="emit('duplicate')">复制节点</button>
        <button
          type="button"
          class="ctx-menu__item"
          :disabled="!edgeCount"
          @click="emit('disconnect')"
        >
          断开全部连线
          <span v-if="edgeCount" class="ctx-menu__count">{{ edgeCount }}</span>
        </button>
        <div class="ctx-menu__sep" />
        <button type="button" class="ctx-menu__item ctx-menu__item--danger" @click="emit('remove')">
          移除节点
        </button>
      </template>
    </div>
  </template>
</template>

<style scoped>
.ctx-menu__backdrop {
  position: fixed;
  inset: 0;
  z-index: 2999;
}
.ctx-menu {
  position: fixed;
  z-index: 3000;
  width: 168px;
  padding: 4px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color-overlay);
  box-shadow: var(--el-box-shadow-light);
}
.ctx-menu__caption {
  overflow: hidden;
  padding: 6px 10px 7px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 4px;
  color: var(--el-text-color-secondary);
  font-family: var(--el-font-family-mono, monospace);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ctx-menu__item {
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
.ctx-menu__item:hover:not(:disabled) {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
.ctx-menu__item:disabled {
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
}
.ctx-menu__item--danger:hover {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.ctx-menu__count {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.ctx-menu__sep {
  height: 1px;
  margin: 4px 6px;
  background: var(--el-border-color-lighter);
}
</style>
