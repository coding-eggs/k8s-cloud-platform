<script setup lang="ts">
// BGP* 编辑器页表单上方的说明块（硬要求：是什么 / 怎么用 / 注意事项）
defineProps<{
  /** 这个对象是做什么的 */
  what: string
  /** 如何使用 */
  how: string
  /** 注意事项（逐条列出） */
  warnings?: string[]
}>()
</script>

<template>
  <div class="bgp-doc">
    <div class="doc-sec">
      <span class="doc-k">是什么</span>
      <p>{{ what }}</p>
    </div>
    <div class="doc-sec">
      <span class="doc-k">怎么用</span>
      <p>{{ how }}</p>
    </div>
    <div v-if="warnings?.length" class="doc-sec warn">
      <span class="doc-k doc-k-warn">注意</span>
      <ul>
        <li v-for="(w, i) in warnings" :key="i">{{ w }}</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.bgp-doc {
  display: flex; flex-direction: column; gap: 10px; margin-bottom: 18px;
  padding: 14px 16px; border: 1px solid var(--border); border-radius: 8px; background: var(--panel-hover);
}
.doc-sec { display: flex; gap: 10px; font-size: 13px; line-height: 1.7; color: var(--text-2); }
.doc-k {
  flex-shrink: 0; width: 56px; text-align: center; padding: 1px 0; border-radius: 5px;
  background: var(--panel); border: 1px solid var(--border); color: var(--text-2); font-size: 12px; height: fit-content; margin-top: 3px;
}
.doc-k-warn { color: var(--el-color-warning); border-color: var(--el-color-warning-light-5); }
.warn { color: var(--text-2); }
p { margin: 0; }
ul { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 3px; }
</style>
