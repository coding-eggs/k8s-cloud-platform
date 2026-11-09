<script setup lang="ts">
/**
 * 应用页服务列表的灰度入口：
 * - mode=start：不在灰度 → 直接弹「开始灰度」对话框（默认复制线上 stable）
 * - mode=manage：灰度中 → 抽屉里放 AppCanaryPanel（权重 / header、指标、Pod、全量 / 回退）
 * 两种都先读线上快照拿本服务的期望配置（灰度中带 canary 块）。
 * 抽屉样式非 scoped + 前缀 app-cny-drw-（Element Plus 弹窗 scoped CSS 坑）。
 */
import { computed, ref, watch } from 'vue'
import { appReleaseApi } from '@/api'
import AppCanaryPanel from './AppCanaryPanel.vue'
import AppCanaryStartDialog from './AppCanaryStartDialog.vue'
import type { AppDependencyRow, AppServiceDesired, AppServiceRow, ApplicationView } from '@/types'

const props = defineProps<{
  modelValue: boolean
  mode: 'start' | 'manage'
  app: ApplicationView
  tenantId?: string
  row: AppServiceRow | null
  services: AppServiceRow[]
  dependencies: AppDependencyRow[]
  liveNo: number
  canWrite: boolean
  busy: boolean
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'changed'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const liveDesired = ref<AppServiceDesired | null>(null)
const loaded = ref(false)
/** 打开时读一次；线上发布号变了（调整权重 / 改灰度配置成功）再读，修改对话框要最新的灰度版本配置 */
watch([visible, () => props.liveNo], async ([v], old) => {
  const reopen = !old || old[0] !== v
  if (reopen) { loaded.value = false; liveDesired.value = null }
  if (!v || !props.row || !props.liveNo) { loaded.value = v; return }
  try {
    const r = await appReleaseApi.get(props.app.id, props.liveNo, props.tenantId)
    liveDesired.value = r?.snapshot?.services?.[props.row.key] ?? null
  } catch {
    liveDesired.value = null
  } finally {
    loaded.value = true
  }
}, { immediate: true })

const ctx = computed(() => ({
  tenantId: props.tenantId ?? '',
  clusterId: props.app.clusterId ?? '',
  namespace: props.app.namespace ?? '',
}))
const serviceKeys = computed(() => props.services
  .filter((s) => s.kind === 'SERVICE' && s.key !== props.row?.key).map((s) => s.key))
const stable = computed<AppServiceDesired>(() => {
  const d = { ...(liveDesired.value ?? props.row?.desired ?? {}) } as Record<string, unknown>
  delete d.canary
  return d as unknown as AppServiceDesired
})
const protocol = computed<'HTTP' | 'TCP'>(() => (stable.value.expose === 'gateway-tcp' ? 'TCP' : 'HTTP'))

/** start 模式：快照读完才弹对话框；对话框关掉 = 整个入口关掉 */
const startVisible = computed({
  get: () => visible.value && props.mode === 'start' && loaded.value,
  set: (v: boolean) => { if (!v) visible.value = false },
})

/** 管理中灰度结束（全量 / 回退成功）：抽屉没东西可管了，关掉 */
watch(() => props.row?.canary, (c, prev) => {
  if (props.mode === 'manage' && prev && !c) visible.value = false
})

function onChanged(): void {
  emit('changed')
}
function onStarted(): void {
  visible.value = false
  emit('changed')
}
</script>

<template>
  <AppCanaryStartDialog
    v-if="row && mode === 'start'"
    v-model="startVisible"
    mode="start"
    :app-id="app.id"
    :tenant-id="tenantId"
    :service-key="row.key"
    :stable="stable"
    :protocol="protocol"
    :dependencies="dependencies"
    :service-keys="serviceKeys"
    :ctx="ctx"
    @submitted="onStarted"
  />
  <el-drawer
    v-if="mode === 'manage'"
    v-model="visible"
    :title="row ? `灰度 · ${row.key}` : '灰度'"
    size="760px"
    class="app-cny-drw"
    destroy-on-close
  >
    <div v-if="!loaded" v-loading="true" class="app-cny-drw__loading" />
    <AppCanaryPanel
      v-else-if="row"
      :app-id="app.id"
      :tenant-id="tenantId"
      :slug="app.slug"
      :row="row"
      :live-desired="liveDesired"
      :dependencies="dependencies"
      :service-keys="serviceKeys"
      :ctx="ctx"
      :can-write="canWrite"
      :busy="busy"
      @changed="onChanged"
    />
  </el-drawer>
</template>

<style>
.app-cny-drw .el-drawer__body { padding-top: 4px; }
.app-cny-drw__loading { height: 200px; }
</style>
