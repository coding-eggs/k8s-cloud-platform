<script setup lang="ts">
import { computed } from 'vue'

/**
 * 键值集合展示（标签 / 注解 / nodeSelector 通用）：
 * - 胶囊左对齐、自动换行（多个标签之间折行）；单个过长标签限宽 + 右侧省略号，不撑破侧栏；
 * - 鼠标悬浮整组 → 弹出 popover，完整列出 key=value（长值换行、过多可滚动）。
 */
const props = withDefaults(defineProps<{
  title: string
  data?: Record<string, string> | null
  /** popover 弹出方向：左栏用 right，右栏用 left */
  placement?: 'right' | 'left'
}>(), {
  data: () => ({}),
  placement: 'right',
})

const entries = computed<[string, string][]>(() => Object.entries(props.data ?? {}))
const count = computed(() => entries.value.length)
</script>

<template>
  <span class="kv-tags">
    <el-popover v-if="count" :placement="placement" :width="640" trigger="hover" popper-class="kv-popper">
      <template #reference>
        <span class="tag-wrap">
          <span v-for="[key, val] in entries" :key="key" class="kv-tag">{{ key }}={{ val }}</span>
        </span>
      </template>
      <div class="kv-pop">
        <div class="kv-pop-title">{{ title }}（{{ count }}）</div>
        <div v-for="[key, val] in entries" :key="key" class="kv-pop-line">
          <code class="mono"><el-tag  type="warning" effect="plain">{{key}}</el-tag>: {{ val }}</code>
        </div>
      </div>
    </el-popover>
    <span v-else class="muted">—</span>
  </span>
</template>

<style scoped>
.kv-tags { display: block; width: 100%; }
/* 左对齐 + 每个标签独占一行（标签之间换行） */
.tag-wrap { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; width: 100%; }
/* 单个胶囊限宽（不超过列宽）+ 右侧省略号，长内容不撑破侧栏（完整值看悬浮 popover） */
.kv-tag {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
</style>

<!-- popover 内容 teleport 到 body，scoped 匹配不到 → 用非 scoped 块 + 独有前缀 -->
<style>
.kv-popper .kv-pop { display: flex; flex-direction: column; gap: 6px; max-height: 340px; overflow: auto; }
.kv-popper .kv-pop-title { font-size: 12px; font-weight: 700; color: var(--text-2); margin-bottom: 2px; }
.kv-popper .kv-pop-line { display: flex; align-items: baseline; gap: 6px; font-size: 12.5px; line-height: 1.5; }
.kv-popper .kv-pop-line .eq { color: var(--text-3); flex-shrink: 0; }
.kv-popper .kv-pop-line code {
  font-family: Consolas, 'JetBrains Mono', monospace;
  word-break: break-all;
  min-width: 0;
}
</style>
