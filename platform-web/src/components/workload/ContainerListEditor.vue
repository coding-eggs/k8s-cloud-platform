<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ContainerDef } from '@/types/workload'
import ContainerEditor from './ContainerEditor.vue'
import { ensureModelList } from './modelList'

const props = defineProps<{ isInit: boolean; volumeNames: string[]; externalNames?: string[] }>()

/** 单列表 model：主容器或 init 容器（父页面用两个实例分别放「Pod 容器」「初始化容器」模块） */
const model = defineModel<ContainerDef[]>()

/** 当前激活 tab（字符串索引）。panes 不 lazy、隐藏不销毁 → ref 数组始终覆盖全部容器，B4 校验不受切 tab 影响 */
const activeTab = ref('0')

function blankContainer(): ContainerDef {
  return { name: '', image: '', command: [], args: [], workingDir: '', imagePullPolicy: 'IfNotPresent', envs: [], envFrom: [], ports: [], volumeMounts: [] }
}

/** 添加容器 = 末尾加一个 tab，并切到新 tab */
function add(): void {
  const arr = ensureModelList(model)
  arr.push(blankContainer())
  activeTab.value = String(arr.length - 1)
}
/** C1：主容器至少留一个（closable 在 length<=1 时关 X）；init 可全删 */
function remove(i: number): void {
  const arr = model.value
  if (!arr) return
  if (!props.isInit && arr.length <= 1) return
  arr.splice(i, 1)
}
function onRemove(name: string): void {
  const i = Number(name)
  remove(i)
  const len = (model.value ?? []).length
  activeTab.value = String(Math.max(0, Math.min(i, len - 1)))
}

/** closable：主容器剩最后一个时关掉 X（C1）；init ≥1 即可关 */
const closable = computed(() => {
  const n = (model.value ?? []).length
  return props.isInit ? n > 0 : n > 1
})

/** C2：DNS_LABEL——小写字母/数字/连字符，不以连字符开头结尾，长度 ≤ 63（前端提示，后端校验为准） */
const DNS_LABEL_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
function isDnsLabel(n: string): boolean {
  return n.length > 0 && n.length <= 63 && DNS_LABEL_RE.test(n)
}

/** C2：本列表名 ∪ 另一侧容器名（externalNames）→ 跨 main/init 也能实时提示重名 */
const allNames = computed<string[]>(() => [...(model.value ?? []).map((c) => c.name), ...(props.externalNames ?? [])])
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

/** tab 标签：填了 name 用 name，否则默认序号；非法/重名追加 ⚠ 便于在未激活的 tab 上也能察觉 */
function tabLabel(c: ContainerDef, i: number): string {
  const base = c.name || (props.isInit ? `Init ${i + 1}` : `容器 ${i + 1}`)
  return nameInvalid(c) ? `${base} ⚠` : base
}

const emptyText = computed(() => (props.isInit ? '暂无 init 容器（可选，可添加）' : '暂无容器，请至少添加一个'))

/** B4：持有本列表每个已渲染 ContainerEditor 的 ref（v-for 内字符串 ref 自动收集为数组），isValid() 全部通过才 true */
type EditorRef = InstanceType<typeof ContainerEditor> | null
const editorRefs = ref<EditorRef[]>([])
function isValid(): boolean {
  return editorRefs.value
    .filter((r): r is NonNullable<EditorRef> => r != null)
    .every((r) => r.isValid())
}
defineExpose({ isValid, add })
</script>

<template>
  <div class="container-list-editor">
    <el-tabs v-if="(model ?? []).length > 0" v-model="activeTab" type="card" :closable="closable" @tab-remove="onRemove">
      <el-tab-pane v-for="(c, i) in model ?? []" :key="i" :name="String(i)" :label="tabLabel(c, i)">
        <div class="cl-pane" :class="{ 'is-invalid': nameInvalid(c) }">
          <div v-if="nameInvalid(c)" class="c2-hint">{{ nameHint(c) }}</div>
          <ContainerEditor ref="editorRefs" v-model="(model!)[i]" :is-init="props.isInit" :volume-names="props.volumeNames" />
        </div>
      </el-tab-pane>
    </el-tabs>
    <el-empty v-if="(model ?? []).length === 0" :description="emptyText" :image-size="64" />
  </div>
</template>

<style scoped>
.container-list-editor { width: 100% }
.cl-pane { padding: 4px 2px }
.c2-hint { font-size: 12px; color: var(--el-color-danger); margin-bottom: 8px }
</style>
