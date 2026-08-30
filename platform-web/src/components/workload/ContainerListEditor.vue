<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ContainerDef } from '@/types/workload'
import ContainerEditor from './ContainerEditor.vue'

const props = defineProps<{ volumeNames: string[] }>()

/** 两个命名 model（Vue 3.4+ 多 model）：主容器 + init 容器；父页面未传时为 undefined，读取一律 ?? [] */
const main = defineModel<ContainerDef[]>('main')
const init = defineModel<ContainerDef[]>('init')

/** 全初始化的空容器：子表单立即可用；resources/lifecycle/probes 留空（ContainerEditor 的空安全 computed 处理 undefined） */
function blankContainer(): ContainerDef {
  return { name: '', image: '', command: [], args: [], workingDir: '', imagePullPolicy: null, envs: [], envFrom: [], ports: [], volumeMounts: [] }
}

function ensureMain(): ContainerDef[] {
  if (main.value === undefined) main.value = []
  return main.value
}
function ensureInit(): ContainerDef[] {
  if (init.value === undefined) init.value = []
  return init.value
}

function addMain(): void {
  ensureMain().push(blankContainer())
}
/** C1：至少保留一个主容器——按钮 disabled 与函数内双重守卫 */
function removeMain(i: number): void {
  const arr = main.value
  if (arr && arr.length > 1) arr.splice(i, 1)
}
function addInit(): void {
  ensureInit().push(blankContainer())
}
/** init 容器可全删 */
function removeInit(i: number): void {
  init.value?.splice(i, 1)
}

/** C2：DNS_LABEL——小写字母/数字/连字符，不以连字符开头结尾，长度 ≤ 63（前端提示，后端校验为准） */
const DNS_LABEL_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
function isDnsLabel(n: string): boolean {
  return n.length > 0 && n.length <= 63 && DNS_LABEL_RE.test(n)
}

/** C2：收集 main + init 全部容器名；重复或非空但非法标红；空名 = 未填写，不提示 */
const allNames = computed<string[]>(() => [...(main.value ?? []), ...(init.value ?? [])].map((c) => c.name))
function isDuplicate(c: ContainerDef): boolean {
  return !!c.name && allNames.value.filter((n) => n === c.name).length > 1
}
function nameInvalid(c: ContainerDef): boolean {
  if (!c.name) return false
  return isDuplicate(c) || !isDnsLabel(c.name)
}
function nameHint(c: ContainerDef): string {
  return isDuplicate(c) ? '容器名与其他容器重复' : '容器名须为 DNS_LABEL（小写字母/数字/连字符，≤63 位）'
}

/** B4：持有每个已渲染 ContainerEditor 的 ref（v-for 内字符串 ref 自动收集为数组，卸载项留 null 洞），isValid() 全部通过才 true（空列表 = true） */
type EditorRef = InstanceType<typeof ContainerEditor> | null
const mainRefs = ref<EditorRef[]>([])
const initRefs = ref<EditorRef[]>([])
function isValid(): boolean {
  return [...mainRefs.value, ...initRefs.value]
    .filter((r): r is NonNullable<EditorRef> => r != null)
    .every((r) => r.isValid())
}
defineExpose({ isValid })
</script>

<template>
  <div class="container-list-editor">
    <!-- 主容器（C1：至少一个） -->
    <div class="cl-section">
      <div class="cl-section-header">
        <span class="cl-section-title">容器 containers</span>
        <el-button plain size="small" @click="addMain">+ 添加容器</el-button>
      </div>
      <div v-for="(c, i) in main ?? []" :key="i" class="cl-item" :class="{ 'is-invalid': nameInvalid(c) }">
        <div class="cl-item-header">
          <span class="cl-item-title">容器 {{ i + 1 }}<template v-if="c.name">（{{ c.name }}）</template></span>
          <span v-if="nameInvalid(c)" class="c2-hint">{{ nameHint(c) }}</span>
          <el-button link type="danger" :disabled="(main ?? []).length <= 1" @click="removeMain(i)">删除</el-button>
        </div>
        <ContainerEditor ref="mainRefs" v-model="c" :is-init="false" :volume-names="props.volumeNames" />
      </div>
      <el-empty v-if="(main ?? []).length === 0" description="暂无容器，请至少添加一个" :image-size="64" />
    </div>

    <!-- init 容器（可选，可全删） -->
    <div class="cl-section">
      <div class="cl-section-header">
        <span class="cl-section-title">Init 容器 initContainers（可选）</span>
        <el-button plain size="small" @click="addInit">+ 添加 init 容器</el-button>
      </div>
      <div v-for="(c, i) in init ?? []" :key="i" class="cl-item" :class="{ 'is-invalid': nameInvalid(c) }">
        <div class="cl-item-header">
          <span class="cl-item-title">Init 容器 {{ i + 1 }}<template v-if="c.name">（{{ c.name }}）</template></span>
          <span v-if="nameInvalid(c)" class="c2-hint">{{ nameHint(c) }}</span>
          <el-button link type="danger" @click="removeInit(i)">删除</el-button>
        </div>
        <ContainerEditor ref="initRefs" v-model="c" :is-init="true" :volume-names="props.volumeNames" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.container-list-editor { width: 100%; display: flex; flex-direction: column; gap: 16px }
.cl-section { border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 8px 12px 12px }
.cl-section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px }
.cl-section-title { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary) }
.cl-item { border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 8px; margin-bottom: 8px }
.cl-item:last-of-type { margin-bottom: 0 }
.cl-item.is-invalid { border-color: var(--el-color-danger) }
.cl-item-header { display: flex; gap: 8px; align-items: center; margin-bottom: 4px }
.cl-item-title { flex: 1; font-size: 12px; color: var(--el-text-color-secondary) }
.c2-hint { font-size: 12px; color: var(--el-color-danger) }
</style>
