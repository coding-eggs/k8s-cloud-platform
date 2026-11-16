<script setup lang="ts">
/** 产物面板：文件列表 + 只读预览 + 单文件下载。YAML 走 YamlBlock（带行号与复制）。 */
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
  URL.revokeObjectURL(url)
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
          @click="index = i"
        >
          <span class="artifact__name">{{ baseName(f.path) }}</span>
          <span class="artifact__path">{{ dirName(f.path) }}</span>
        </button>
      </div>

      <div class="artifact__body">
        <div class="artifact__bar">
          <code class="artifact__full-path">{{ selected?.path }}</code>
          <el-button size="small" @click="selected && download(selected)">下载</el-button>
        </div>
        <YamlBlock v-if="selected && isYaml" :yaml="selected.content" max-height="52vh" />
        <pre v-else-if="selected" class="artifact__text">{{ selected.content }}</pre>
      </div>
    </template>
  </div>
</template>

<style scoped>
.artifact {
  display: flex;
  gap: 12px;
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
.artifact__list {
  width: 200px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 56vh;
  overflow: auto;
}
.artifact__item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.artifact__item:hover {
  background: var(--el-fill-color-light);
}
.artifact__item--active {
  background: var(--el-fill-color);
}
.artifact__name {
  font-size: 12.5px;
  color: var(--el-text-color-primary);
  font-weight: 600;
}
.artifact__path {
  font-size: 10.5px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
.artifact__body {
  flex: 1;
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
  font-size: 11px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact__text {
  margin: 0;
  max-height: 52vh;
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
