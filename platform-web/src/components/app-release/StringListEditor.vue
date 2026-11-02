<script setup lang="ts">
/**
 * 字符串列表编辑器（启动命令 / 参数）：一项一个输入框，值原样保留 ——
 * 不 trim、不丢空项、不按换行拆分（`["sh","-c","a\nb"]` 里的 "a\nb" 是同一个参数）。
 * 只有用户改动某一项时才会产生新数组，没碰过的值保持逐字节不变。
 */
defineProps<{
  placeholder?: string
  disabled?: boolean
}>()

const model = defineModel<string[]>({ default: () => [] })

function add(): void {
  model.value = [...model.value, '']
}
function remove(i: number): void {
  model.value = model.value.filter((_, idx) => idx !== i)
}
function patch(i: number, v: string): void {
  model.value = model.value.map((x, idx) => (idx === i ? v : x))
}
</script>

<template>
  <div class="str-list">
    <div v-for="(item, i) in model" :key="i" class="str-list__row">
      <span class="str-list__idx">{{ i + 1 }}</span>
      <el-input
        :model-value="item"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 6 }"
        :placeholder="placeholder"
        :disabled="disabled"
        @update:model-value="(v: string) => patch(i, v)"
      />
      <el-button link type="danger" :disabled="disabled" @click="remove(i)">删除</el-button>
    </div>
    <el-button :disabled="disabled" @click="add">+ 添加一项</el-button>
    <span class="str-list__hint">每项是一个独立的参数，空格与换行会原样保留。</span>
  </div>
</template>

<style scoped>
.str-list__row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 6px;
}
.str-list__idx {
  width: 18px;
  flex-shrink: 0;
  line-height: 32px;
  text-align: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.str-list__row :deep(.el-textarea) {
  flex: 1;
  min-width: 0;
}
.str-list__hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
