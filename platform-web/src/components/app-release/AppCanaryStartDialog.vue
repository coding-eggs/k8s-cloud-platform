<script setup lang="ts">
/**
 * 开始灰度 / 修改灰度版本配置（spec §9）。
 *
 * 默认复制线上 stable，只把镜像 tag 置顶让用户改；端口、暴露方式只读（后端同样要求与 stable 一致）。
 * 可改：镜像、环境变量、配置文件、资源与命令；开始时另有副本数、初始权重与 header 直达（仅 HTTP）。
 * 分流范围（内部灰度设计）：L7 网格应用集群内调用也分流；L7 且网关暴露时可选「外部流量参与灰度」。
 * 其余配置（探针、HPA、可观测、持久化等）原样沿用 stable。
 * 弹窗样式非 scoped + 前缀 app-cny-（Element Plus 弹窗 scoped CSS 坑）。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { appCanaryApi } from '@/api'
import AppTagInput from './AppTagInput.vue'
import AppEnvEditor from './AppEnvEditor.vue'
import AppConfigFilesEditor from './AppConfigFilesEditor.vue'
import AppResourcesForm from './AppResourcesForm.vue'
import AppSchedulingForm from './AppSchedulingForm.vue'
import AppVolumesForm from './AppVolumesForm.vue'
import { usePermission } from '@/stores/permission'
import { useResourceContext } from '@/stores/context'
import {
  cleanCommandLists, cleanConfigFiles, cleanScheduling, configFileProblems, dataMountOf,
  mountProblems, nodeSelectorProblems, tolerationProblems, volumeProblems,
} from './desiredFormUtil'
import type { AppDependencyRow, AppServiceDesired } from '@/types'

const props = defineProps<{
  modelValue: boolean
  mode: 'start' | 'edit'
  appId: string
  tenantId?: string
  serviceKey: string
  /** 线上 stable 的期望配置（不含 canary 块） */
  stable: AppServiceDesired
  /** 修改时：线上灰度版本的期望配置 */
  canaryDesired?: AppServiceDesired | null
  protocol: 'HTTP' | 'TCP'
  /** 应用线上设置为 L7 网格 */
  l7: boolean
  /** stable 有网关暴露 */
  exposed: boolean
  dependencies: AppDependencyRow[]
  serviceKeys: string[]
  ctx: { tenantId: string; clusterId: string; namespace: string }
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submitted', executionId: number): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T
}

const form = ref<AppServiceDesired>({ imageRepo: '', imageTag: '' })
/** 打开时的起点（命令 / 参数里原有的空串按它保留） */
let base: AppServiceDesired | null = null
const tab = ref('basic')
const replicas = ref(1)
const weight = ref(10)
const headerOn = ref(true)
const headerName = ref('x-canary')
const headerValue = ref('true')
const external = ref(true)
const submitting = ref(false)
const error = ref('')

watch(visible, (v) => {
  if (!v) return
  base = clone(props.mode === 'edit' && props.canaryDesired ? props.canaryDesired : props.stable)
  const c = clone(base)
  delete (c as Record<string, unknown>).canary
  c.configFiles = (c.configFiles ?? []).map((f) => ({ ...f }))
  tab.value = 'basic'
  c.env = (c.env ?? []).map((e) => ({ ...e }))
  form.value = c
  replicas.value = 1
  weight.value = 10
  headerOn.value = props.protocol === 'HTTP'
  headerName.value = 'x-canary'
  headerValue.value = 'true'
  external.value = true
  error.value = ''
}, { immediate: true })

const sameImage = computed(() =>
  form.value.imageRepo === props.stable.imageRepo && form.value.imageTag === props.stable.imageTag)
const dataMount = computed(() => dataMountOf(form.value))
const filesInvalid = computed(() => configFileProblems(form.value.configFiles, dataMount.value).some(Boolean))
/** 调度 / 挂载的软校验（与配置页同一套；权威校验在后端 AppCanaryService → AppServiceDesired） */
const perm = usePermission()
const schedulingInvalid = computed(() => nodeSelectorProblems(form.value).some(Boolean)
  || tolerationProblems(form.value).some(Boolean))
const volumesInvalid = computed(() => volumeProblems(form.value, !!perm.isAdmin).some(Boolean)
  || mountProblems(form.value).some(Boolean))
/** 挂载页签的候选下拉（ConfigMap / Secret / PVC）来自资源域上下文：切过去先把命名空间对齐到应用 */
const { setNamespace } = useResourceContext()
watch(tab, (v) => { if (v === 'volumes') setNamespace(props.ctx.namespace) })
const probeOff = computed(() => form.value.probeEnabled === false)
const exposeText = computed(() => (!props.exposed ? `不对外（${props.protocol}）` : props.protocol === 'HTTP' ? '网关 HTTP' : '网关 TCP'))
/** 分流范围说明 */
const scopeText = computed(() => {
  if (!props.l7) return '应用未开启 L7 网格：只分网关进来的流量，集群内其他服务调用的仍是线上版本。'
  if (!props.exposed) return '集群内其他服务调用本服务的流量按权重分流（应用为 L7 网格，经 waypoint）。'
  return external.value
    ? '集群内调用与网关进来的流量都按同一权重分流。'
    : '只分集群内调用；网关进来的流量固定进线上版本。'
})

function buildDesired(): AppServiceDesired {
  const d = clone(form.value)
  d.imageRepo = (d.imageRepo ?? '').trim()
  d.imageTag = (d.imageTag ?? '').trim()
  cleanCommandLists(d, base)
  // 调度 / 挂载：空行不提交，否则灰度版本会带上半填的配置（后端同样拒绝）
  cleanScheduling(d)
  for (const k of Object.keys(d)) {
    const v = d[k]
    if (v === '' || v == null || (Array.isArray(v) && v.length === 0 && k !== 'env')) delete d[k]
  }
  d.env = (d.env ?? []).filter((e) => e.name || e.value || e.secretName).map((e) => {
    const o = { ...e }
    if (o.kind !== 'SECRET') { delete o.secretName; delete o.secretKey } else { delete o.value }
    return o
  })
  const files = cleanConfigFiles(form.value.configFiles)
  if (files.length) d.configFiles = files
  else delete d.configFiles
  return d
}

async function submit(): Promise<void> {
  if (!form.value.imageRepo?.trim() || !form.value.imageTag?.trim()) {
    error.value = '请填写镜像仓库和 tag'
    tab.value = 'basic'
    return
  }
  if (filesInvalid.value) {
    error.value = '配置文件有问题，见「配置文件」页签'
    tab.value = 'files'
    return
  }
  if (schedulingInvalid.value) {
    error.value = '调度有问题，见「调度」页签'
    tab.value = 'scheduling'
    return
  }
  if (volumesInvalid.value) {
    error.value = '挂载有问题，见「挂载」页签'
    tab.value = 'volumes'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const req = { appId: props.appId, serviceKey: props.serviceKey, tenantId: props.tenantId, desired: buildDesired() }
    const id = props.mode === 'start'
      ? await appCanaryApi.start({
        ...req,
        replicas: replicas.value,
        weight: weight.value,
        headerEnabled: props.protocol === 'HTTP' && headerOn.value,
        headerName: headerName.value.trim() || undefined,
        headerValue: headerValue.value,
        external: props.l7 && props.exposed ? external.value : undefined,
      })
      : await appCanaryApi.update(req)
    ElMessage.success(props.mode === 'start' ? '已开始灰度，正在执行' : '已提交灰度版本配置，正在执行')
    visible.value = false
    emit('submitted', id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="mode === 'start' ? `开始灰度 · ${serviceKey}` : `修改灰度版本配置 · ${serviceKey}`"
             width="720px" class="app-cny-dlg" destroy-on-close>
    <div class="app-cny-dlg__lead">
      灰度版本默认复制线上配置，通常只需改镜像 tag；环境变量、配置文件、资源与命令、调度与挂载也可以改，其余沿用线上版本。
    </div>
    <el-alert v-if="mode === 'start'" :type="l7 ? 'info' : 'warning'" :closable="false" show-icon :title="scopeText" class="app-cny-dlg__scope" />
    <el-tabs v-model="tab" class="app-cny-dlg__tabs">
      <el-tab-pane label="基本" name="basic">
        <el-form label-width="110px" @submit.prevent>
          <el-form-item label="镜像 tag" required>
            <AppTagInput v-model="form.imageTag" :repo="form.imageRepo" />
            <div v-if="sameImage" class="app-cny-dlg__warn">镜像与线上版本相同（不拦截，确认是有意为之）</div>
          </el-form-item>
          <el-form-item label="镜像仓库" required><el-input v-model="form.imageRepo" placeholder="不含 tag" /></el-form-item>
          <el-form-item label="端口 / 暴露">
            <span><code>{{ form.port }}</code> · {{ exposeText }}</span>
            <span class="app-cny-dlg__tip">与线上一致，不可改</span>
          </el-form-item>
          <template v-if="mode === 'start'">
            <el-form-item label="灰度副本数">
              <el-input-number v-model="replicas" :min="1" :max="100" />
              <span class="app-cny-dlg__tip">灰度版本不自动扩缩容</span>
            </el-form-item>
            <el-form-item label="初始权重">
              <el-input-number v-model="weight" :min="0" :max="100" />
              <span class="app-cny-dlg__tip">% 的流量进灰度版本，之后可随时调整</span>
            </el-form-item>
            <el-form-item v-if="l7 && exposed" label="外部流量参与">
              <el-switch v-model="external" />
              <div class="app-cny-dlg__tip-block">关掉后网关进来的请求固定进线上版本，权重只作用于集群内调用</div>
            </el-form-item>
            <el-form-item v-if="protocol === 'HTTP'" label="header 直达">
              <el-switch v-model="headerOn" />
              <template v-if="headerOn">
                <el-input v-model="headerName" class="app-cny-dlg__hdr" placeholder="x-canary" />
                <span>:</span>
                <el-input v-model="headerValue" class="app-cny-dlg__hdr" placeholder="true" />
              </template>
              <div class="app-cny-dlg__tip-block">带这个请求头的请求总是进灰度版本（优先于权重），方便自测{{ l7 ? '；集群内调用需调用方把这个请求头往下传' : '' }}</div>
            </el-form-item>
          </template>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="环境变量" name="env">
        <AppEnvEditor v-model="form.env" :dependencies="dependencies" :service-keys="serviceKeys" :ctx="ctx" />
      </el-tab-pane>
      <el-tab-pane label="配置文件" name="files">
        <AppConfigFilesEditor v-model="form.configFiles" :data-mount="dataMount" :dependencies="dependencies" :service-keys="serviceKeys" />
      </el-tab-pane>
      <el-tab-pane label="资源与命令" name="resources">
        <el-form label-width="110px" @submit.prevent>
          <AppResourcesForm :form="form" />
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="调度" name="scheduling">
        <AppSchedulingForm :form="form" :cluster-id="ctx.clusterId" />
      </el-tab-pane>
      <el-tab-pane label="挂载" name="volumes">
        <AppVolumesForm :form="form" />
      </el-tab-pane>
    </el-tabs>
    <el-alert v-if="probeOff" type="warning" :closable="false" show-icon
              title="探针已关闭：就绪只代表进程已启动，灰度版本可能在没准备好时就接到流量" />
    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="app-cny-dlg__err" />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="filesInvalid || schedulingInvalid || volumesInvalid" @click="submit">{{ mode === 'start' ? '开始灰度' : '提交' }}</el-button>
    </template>
  </el-dialog>
</template>

<style>
.app-cny-dlg__lead { margin-bottom: 14px; color: var(--text-3); font-size: 12.5px; line-height: 1.6; }
.app-cny-dlg__tip { margin-left: 10px; color: var(--text-3); font-size: 12px; }
.app-cny-dlg__tip-block { width: 100%; color: var(--text-3); font-size: 12px; }
.app-cny-dlg__warn { width: 100%; margin-top: 4px; color: var(--el-color-warning); font-size: 12px; }
.app-cny-dlg__hdr { width: 140px; margin: 0 6px; }
.app-cny-dlg__err { margin-top: 10px; }
.app-cny-dlg__scope { margin-bottom: 10px; }
.app-cny-dlg__tabs .el-tabs__content { max-height: 60vh; overflow-y: auto; }
</style>
