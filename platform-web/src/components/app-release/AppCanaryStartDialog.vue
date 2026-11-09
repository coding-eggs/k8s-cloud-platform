<script setup lang="ts">
/**
 * 开始灰度 / 修改灰度版本配置（spec §9）。
 *
 * 默认复制线上 stable，只把镜像 tag 置顶让用户改；端口、暴露方式只读（后端同样要求与 stable 一致）。
 * 可改：镜像、环境变量、配置文件、资源与命令；开始时另有副本数、初始权重与 header 直达（仅 HTTP）。
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
import { cleanCommandLists, cleanConfigFiles, configFileProblems, dataMountOf } from './desiredFormUtil'
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
  error.value = ''
}, { immediate: true })

const sameImage = computed(() =>
  form.value.imageRepo === props.stable.imageRepo && form.value.imageTag === props.stable.imageTag)
const dataMount = computed(() => dataMountOf(form.value))
const filesInvalid = computed(() => configFileProblems(form.value.configFiles, dataMount.value).some(Boolean))
const probeOff = computed(() => form.value.probeEnabled === false)
const exposeText = computed(() => (props.protocol === 'HTTP' ? '网关 HTTP' : '网关 TCP'))

function buildDesired(): AppServiceDesired {
  const d = clone(form.value)
  d.imageRepo = (d.imageRepo ?? '').trim()
  d.imageTag = (d.imageTag ?? '').trim()
  cleanCommandLists(d, base)
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
      灰度版本默认复制线上配置，通常只需改镜像 tag；环境变量、配置文件、资源与命令也可以改，其余沿用线上版本。它只接网关进来的流量；集群内其他服务调用的仍是线上版本。
    </div>
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
              <span class="app-cny-dlg__tip">% 的网关流量进灰度版本，之后可随时调整</span>
            </el-form-item>
            <el-form-item v-if="protocol === 'HTTP'" label="header 直达">
              <el-switch v-model="headerOn" />
              <template v-if="headerOn">
                <el-input v-model="headerName" class="app-cny-dlg__hdr" placeholder="x-canary" />
                <span>:</span>
                <el-input v-model="headerValue" class="app-cny-dlg__hdr" placeholder="true" />
              </template>
              <div class="app-cny-dlg__tip-block">带这个请求头的请求总是进灰度版本（优先于权重），方便自测</div>
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
    </el-tabs>
    <el-alert v-if="probeOff" type="warning" :closable="false" show-icon
              title="探针已关闭：就绪只代表进程已启动，灰度版本可能在没准备好时就接到流量" />
    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="app-cny-dlg__err" />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="filesInvalid" @click="submit">{{ mode === 'start' ? '开始灰度' : '提交' }}</el-button>
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
.app-cny-dlg__tabs .el-tabs__content { max-height: 60vh; overflow-y: auto; }
</style>
