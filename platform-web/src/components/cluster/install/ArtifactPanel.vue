<script setup lang="ts">
/**
 * 产物面板：文件列表 + 只读预览 + 单文件下载。YAML 走 YamlBlock（带行号与复制）。
 *
 * <p>列表放在预览**上方**（横向 chip）而不是左侧竖排：右侧汇总列只有 400~560px 宽，
 * 竖排 200px 的列表会把预览挤到 200px 以内，YAML 基本没法看。
 */
import { computed, ref, watch } from 'vue'
import YamlBlock from '@/components/YamlBlock.vue'
import type { InstallFile } from '@/utils/clusterInstall/types'

const props = defineProps<{ files: InstallFile[] }>()

const index = ref(0)
const selected = computed(() => props.files[index.value])
const isYaml = computed(() => {
  const p = selected.value?.path ?? ''
  return p.endsWith('.yaml') || p.endsWith('.yml')
})

watch(
  () => props.files.length,
  (len) => {
    if (index.value >= len) index.value = 0
  },
)

function baseName(path: string): string {
  return path.split('/').pop() ?? path
}

function dirName(path: string): string {
  const parts = path.split('/')
  parts.pop()
  return parts.join('/')
}

function download(file: InstallFile): void {
  const blob = new Blob([file.content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = baseName(file.path)
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 立刻 revoke 会让部分浏览器拿不到内容，挪到下一轮事件循环
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
</script>

<template>
  <div class="artifact">
    <div v-if="!files.length" class="artifact__empty">填写表单后生成产物</div>

    <template v-else>
      <div class="artifact__list">
        <button
          v-for="(f, i) in files"
          :key="f.path"
          type="button"
          :class="['artifact__item', { 'artifact__item--active': i === index }]"
          :title="f.path"
          @click="index = i"
        >
          {{ baseName(f.path) }}
        </button>
      </div>

      <div class="artifact__body">
        <div class="artifact__bar">
          <code class="artifact__full-path">{{ selected?.path }}</code>
          <el-button size="small" @click="selected && download(selected)">下载</el-button>
        </div>
        <YamlBlock v-if="selected && isYaml" :yaml="selected.content" max-height="46vh" />
        <pre v-else-if="selected" class="artifact__text">{{ selected.content }}</pre>
      </div>
    </template>
  </div>
</template>

<style scoped>
.artifact {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 180px;
}
.artifact__empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}
/* 文件 chip：横排、可换行，宽度不够也不会把预览挤掉 */
.artifact__list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.artifact__item {
  padding: 3px 9px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.6;
  cursor: pointer;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact__item:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.artifact__item--active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.artifact__body {
  min-width: 0;
}
.artifact__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.artifact__full-path {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact__text {
  margin: 0;
  max-height: 46vh;
  overflow: auto;
  padding: 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
