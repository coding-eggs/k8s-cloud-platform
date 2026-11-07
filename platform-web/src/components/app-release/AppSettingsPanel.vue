<script setup lang="ts">
/**
 * 应用设置：链路追踪与服务网格（作用于全部服务）。
 *
 * <p>为什么在应用级：一条链路跨多个服务，只开一部分就断，采样由入口决定；网格的 mTLS / L7 策略也要调用双方都在网格里。
 * 服务只保留「应用语言」（决定能否注入探针）和日志采集。
 *
 * <p>保存只改期望值；发布时勾选「应用设置」才下发，并让全部服务滚动。
 * 组件门禁与服务级同口径：没装不让开，已开的可关（后端 AppFeatureGate.assertSettings 同样只拦从关到开）。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { appServiceApi } from '@/api'
import type { AppServiceMesh, AppSettings, AppSettingsView } from '@/types'
import { useClusterComponents } from '@/composables/useClusterComponents'
import AppMeshForm from './AppMeshForm.vue'

const props = defineProps<{
  appId: string
  tenantId?: string
  clusterId?: string
  namespace?: string
  view: AppSettingsView
  editable: boolean
}>()
const emit = defineEmits<{ (e: 'saved'): void }>()

const comps = useClusterComponents(computed(() => props.clusterId))

const form = ref<AppSettings>({})
function reset(): void {
  form.value = JSON.parse(JSON.stringify(props.view.settings ?? {}))
}
watch(() => props.view, reset, { immediate: true, deep: true })

/** 键排序后比较，去掉空值：「没有」与「空」不算改动 */
function canon(s?: AppSettings | null): string {
  const out: AppSettings = {}
  if (s?.tracing) {
    out.tracing = true
    if (s.sampling != null) out.sampling = s.sampling
  }
  const m: AppServiceMesh = {}
  if (s?.mesh?.dataplaneMode) m.dataplaneMode = s.mesh.dataplaneMode
  if (s?.mesh?.useWaypoint) m.useWaypoint = s.mesh.useWaypoint
  if (m.dataplaneMode || m.useWaypoint) out.mesh = m
  return JSON.stringify(out)
}
const dirty = computed(() => canon(form.value) !== canon(props.view.settings))
/** 期望值与线上快照不同 = 有待发布改动 */
const pending = computed(() => canon(props.view.settings) !== canon(props.view.live))

const tracingLocked = computed(() =>
  !form.value.tracing && !props.view.settings?.tracing && !comps.available('otel-collection'))
const meshAvailable = computed(() => comps.available('ambient-mesh'))

function setTracing(on: boolean): void {
  const next: AppSettings = { ...form.value, tracing: on || undefined }
  if (!on) delete next.sampling
  form.value = next
}
function setSampling(v: number | null | undefined): void {
  form.value = { ...form.value, sampling: v ?? undefined }
}
const mesh = computed<AppServiceMesh | undefined>({
  get: () => form.value.mesh,
  set: (v) => { form.value = { ...form.value, mesh: v } },
})

const saving = ref(false)
async function save(): Promise<void> {
  saving.value = true
  try {
    await appServiceApi.updateSettings({
      appId: props.appId,
      settings: JSON.parse(canon(form.value)),
      version: props.view.version,
      tenantId: props.tenantId,
    })
    ElMessage.success('已保存，发布时勾选「应用设置」后生效')
    emit('saved')
  } catch {
    // 拦截器已提示（含「配置冲突」与组件门禁原文）
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="app-settings">
    <el-form label-width="140px" :disabled="!editable">
      <el-form-item label="链路追踪">
        <el-tooltip :disabled="!tracingLocked" :content="comps.unavailableReason('otel-collection')" placement="top">
          <span><el-switch :model-value="!!form.tracing" :disabled="tracingLocked"
                           @update:model-value="(v: string | number | boolean) => setTracing(v === true)" /></span>
        </el-tooltip>
        <span v-if="tracingLocked" class="form-tip warn">{{ comps.unavailableReason('otel-collection') }}</span>
        <div class="form-tip">开启后，在服务的「可观测」里选了应用语言（Java / Python）的服务会注入探针；没选语言的服务（Nginx、Go 等）不注入。</div>
      </el-form-item>
      <el-form-item v-if="form.tracing" label="采样率 %">
        <el-input-number :model-value="form.sampling" :min="1" :max="100" placeholder="跟随组件默认"
                         @update:model-value="setSampling" />
        <span class="form-tip">由链路入口决定是否采样，下游服务跟随，所以只在应用级设一次。</span>
      </el-form-item>
    </el-form>
    <AppMeshForm v-model="mesh" :original="view.settings?.mesh"
                 :tenant-id="tenantId" :cluster-id="clusterId" :namespace="namespace"
                 :readonly="!editable" :mesh-available="meshAvailable"
                 :mesh-reason="comps.unavailableReason('ambient-mesh')" />
    <div class="app-settings__save">
      <el-button type="primary" :loading="saving" :disabled="!editable || !dirty" @click="save">保存</el-button>
      <el-button :disabled="!editable || !dirty" @click="reset">还原</el-button>
      <el-tag v-if="pending && !dirty" type="warning" effect="light" size="small">有待发布改动</el-tag>
      <span class="muted">保存只改期望值；发布时勾选「应用设置」后作用于全部服务（会让所有服务滚动重启）。</span>
    </div>
  </div>
</template>

<style scoped>
.form-tip { color: var(--text-3); font-size: 12px; line-height: 1.6; }
div.form-tip { width: 100%; }
span.form-tip { margin-left: 10px; }
.form-tip.warn { color: var(--el-color-warning); }
.app-settings__save { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 4px; }
.app-settings__save .muted { color: var(--text-3); font-size: 12.5px; }
</style>
