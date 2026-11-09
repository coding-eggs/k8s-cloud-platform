<script setup lang="ts">
/**
 * 「调度」页签：直接复用工作负载编辑器的三个组件（节点选择 / 亲和 / 容忍），
 * 形状与工作负载页完全一致；发布渲染时 platform-api 再翻成 K8s 原生字段（AppScheduling）。
 *
 * 只读态用 fieldset + pointer-events 屏蔽交互：共用组件不必为此各加一个 disabled 参数。
 * 节点候选来自集群真实节点的标签（nodeCatalog），无权限时退化为自由输入。
 */
import { computed, onMounted, watch } from 'vue'
import NodeLabelEditor from '@/components/workload/NodeLabelEditor.vue'
import AffinityEditor from '@/components/workload/AffinityEditor.vue'
import TolerationEditor from '@/components/workload/TolerationEditor.vue'
import { useNodeCatalog } from '@/stores/nodeCatalog'
import type { AppServiceDesired } from '@/types'
import type { Affinity } from '@/types/workload'

const props = defineProps<{
  form: AppServiceDesired
  disabled?: boolean
  clusterId?: string
}>()

const cat = useNodeCatalog()
function loadNodes(): void {
  void cat.load(props.clusterId ?? null)
}
onMounted(loadNodes)
watch(() => props.clusterId, loadNodes)

/** 表单里 affinity 允许为空（null），编辑器的 model 只认 undefined */
const affinity = computed<Affinity | undefined>({
  get: () => props.form.affinity ?? undefined,
  set: (v) => { props.form.affinity = v ?? null },
})
</script>

<template>
  <fieldset class="app-sch" :disabled="disabled" :class="{ 'is-readonly': disabled }">
    <div class="app-sch__hint">
      这些字段随发布写进 Pod 模板，改动会让 Pod 滚动重启。节点候选来自集群里真实节点的标签，也可以直接输入。
    </div>
    <div class="app-sch__block">
      <div class="app-sch__title">节点选择（nodeSelector）</div>
      <NodeLabelEditor v-model="form.nodeSelector" />
    </div>
    <div class="app-sch__block">
      <div class="app-sch__title">亲和与反亲和（affinity）</div>
      <AffinityEditor v-model="affinity" />
    </div>
    <div class="app-sch__block">
      <div class="app-sch__title">容忍（tolerations）</div>
      <TolerationEditor v-model="form.tolerations" />
    </div>
  </fieldset>
</template>

<style>
.app-sch { width: 100%; padding: 0; margin: 0; border: 0; }
.app-sch.is-readonly { pointer-events: none; opacity: .65; }
.app-sch__hint { margin-bottom: 16px; color: var(--text-3); font-size: 12.5px; line-height: 1.6; }
.app-sch__block { margin-bottom: 22px; }
.app-sch__title { margin-bottom: 8px; font-weight: 600; }
</style>
