<script setup lang="ts">
/**
 * 部署包文件面板：文件 chip + 单文件下载 + 打包下载 + **全屏查看**。
 *
 * <p>右栏只有几百像素宽，YAML / README 挤在里面很难读，所以预览不再内嵌在右栏：
 * 选中文件后点「全屏查看」，用 `el-dialog fullscreen` 铺满窗口，里面还能直接切文件。
 *
 * <p>注意：Element Plus 的 dialog 内容挂在 body 下、拿不到 scoped 的 data-v 属性，
 * 所以查看器里的一切样式写在**非 scoped** 的 `<style>` 里，并统一用 `.artifact-viewer` 前缀隔离。
 */
import { computed, ref, watch } from 'vue'
import { Download, FullScreen } from '@element-plus/icons-vue'
import YamlBlock from '@/components/YamlBlock.vue'
import { renderMarkdown } from '@/utils/clusterInstall/markdown'
import { buildZip } from '@/utils/clusterInstall/zip'
import type { InstallFile } from '@/utils/clusterInstall/types'

const props = defineProps<{ files: InstallFile[] }>()

const index = ref(0)
const viewerOpen = ref(false)
const selected = computed(() => props.files[index.value])
const isYaml = computed(() => {
  const p = selected.value?.path ?? ''
  return p.endsWith('.yaml') || p.endsWith('.yml')
})
const isMarkdown = computed(() => (selected.value?.path ?? '').endsWith('.md'))

watch(
  () => props.files.length,
  (len) => {
    if (index.value >= len) index.value = 0
  },
)

function baseName(path: string): string {
  return path.split('/').pop() ?? path
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 立刻 revoke 会让部分浏览器拿不到内容，挪到下一轮事件循环
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

function download(file: InstallFile): void {
  saveBlob(new Blob([file.content], { type: 'text/plain;charset=utf-8' }), baseName(file.path))
}

/** 打包下载：一个 zip 里保留 inventory/<集群名>/ 的完整结构，解压即可拷进 kubespray */
function downloadAll(): void {
  if (props.files.length === 0) return
  const first = props.files[0]
  const cluster = first?.path.split('/')[1] || 'inventory'
  saveBlob(buildZip(props.files), `${cluster}.zip`)
}
</script>

<template>
  <div class="artifact">
    <div v-if="!files.length" class="artifact__empty">填写表单后生成产物</div>

    <template v-else>
      <div class="artifact__toolbar">
        <el-button size="small" type="primary" :icon="FullScreen" @click="viewerOpen = true">
          全屏查看
        </el-button>
        <el-button size="small" :icon="Download" @click="downloadAll">打包下载 .zip</el-button>
        <span class="artifact__toolbar-hint">{{ files.length }} 个文件，保留 inventory/ 目录结构</span>
      </div>

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

      <div class="artifact__bar">
        <code class="artifact__full-path">{{ selected?.path }}</code>
        <el-button size="small" @click="selected && download(selected)">下载</el-button>
      </div>
    </template>

    <el-dialog
      v-model="viewerOpen"
      fullscreen
      append-to-body
      destroy-on-close
      class="artifact-viewer"
    >
      <template #header>
        <div class="artifact-viewer__head">
          <code class="artifact-viewer__path">{{ selected?.path }}</code>
          <el-button size="small" :icon="Download" @click="selected && download(selected)">
            下载
          </el-button>
        </div>
      </template>

      <div class="artifact-viewer__chips">
        <button
          v-for="(f, i) in files"
          :key="f.path"
          type="button"
          :class="['artifact-viewer__chip', { 'artifact-viewer__chip--active': i === index }]"
          :title="f.path"
          @click="index = i"
        >
          {{ baseName(f.path) }}
        </button>
      </div>

      <div class="artifact-viewer__body">
        <YamlBlock v-if="selected && isYaml" :yaml="selected.content" max-height="calc(100vh - 190px)" />
        <!-- eslint-disable-next-line vue/no-v-html -- 内容由本地 renderMarkdown 转义后生成 -->
        <div
          v-else-if="selected && isMarkdown"
          class="artifact-viewer__md"
          v-html="renderMarkdown(selected.content)"
        />
        <pre v-else-if="selected" class="artifact-viewer__text">{{ selected.content }}</pre>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.artifact {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 120px;
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
.artifact__toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.artifact__toolbar-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 文件 chip：横排、可换行，宽度不够也不会把预览挤掉 */
.artifact__list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.artifact__item {
  padding: 4px 11px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 13px;
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
.artifact__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.artifact__full-path {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

<!-- dialog 内容挂在 body 下，拿不到 scoped 的 data-v，样式必须写在这里 -->
<style>
.artifact-viewer .el-dialog__header {
  margin-right: 0;
  padding: 14px 24px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.artifact-viewer .el-dialog__body {
  padding: 16px 24px 24px;
  height: calc(100vh - 65px);
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
.artifact-viewer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-right: 32px;
}
.artifact-viewer__path {
  min-width: 0;
  font-size: 14px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact-viewer__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex: none;
}
.artifact-viewer__chip {
  padding: 5px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.6;
  cursor: pointer;
  white-space: nowrap;
}
.artifact-viewer__chip:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.artifact-viewer__chip--active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.artifact-viewer__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}
.artifact-viewer__body .yaml-view {
  font-size: 14px;
  line-height: 1.7;
}
.artifact-viewer__md {
  padding: 20px 32px 32px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-size: 15px;
  line-height: 1.9;
  max-width: 1080px;
  width: 100%;
  margin: 0 auto;
}
.artifact-viewer__md h1,
.artifact-viewer__md h2,
.artifact-viewer__md h3,
.artifact-viewer__md h4 {
  margin: 22px 0 10px;
  font-size: 17px;
  font-weight: 600;
}
.artifact-viewer__md h1:first-child {
  margin-top: 0;
}
.artifact-viewer__md p {
  margin: 10px 0;
}
.artifact-viewer__md ul,
.artifact-viewer__md ol {
  margin: 10px 0;
  padding-left: 24px;
}
.artifact-viewer__md li {
  margin: 4px 0;
}
.artifact-viewer__md code {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-size: 13.5px;
}
.artifact-viewer__md pre {
  margin: 12px 0;
  padding: 12px 14px;
  border-radius: 6px;
  background: var(--el-fill-color);
  font-size: 13.5px;
  line-height: 1.7;
  overflow: auto;
}
.artifact-viewer__md pre code {
  padding: 0;
  background: transparent;
}
.artifact-viewer__md blockquote {
  margin: 12px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-radius: 4px;
}
.artifact-viewer__text {
  margin: 0;
  padding: 20px 24px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
