<script setup lang="ts">
/**
 * 工作负载「可观测接入」（OTel 批次一 + 批次二）。
 *
 * 只改 PodTemplate 的注入注解、主容器 OTEL_SERVICE_NAME 与（文件日志时）平台 emptyDir 卷 ——
 * 与 mesh-toggle 同一套读-改-写，后端负责保留其余字段。切换会触发滚动更新；
 * 回滚到旧 revision 可能把接入配置一起回滚。
 *
 * 日志两种模式：
 * - 应用日志（app）：Java/Python 的日志框架，由 Instrumentation 注入的 agent 上报；
 * - 文件日志（file）：Nginx 等写文件的应用，由 per-Pod sidecar collector 读目录。
 *   sidecar 读不到 stdout，所以必须用户提供日志目录并确认应用确实写文件。
 *
 * Node 在第一批置灰：operator 不会自动识别语言，写错注解不会报错但永远没有数据。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { workloadApi } from '@/api'

const props = defineProps<{
  visible: boolean
  workloadName: string
  kind?: string
  tenantId: string
  clusterId: string
  namespace: string
  annotations?: Record<string, unknown> | null
  containers: string[]
  /** 已接入文件日志时，应用容器上的日志目录（从平台卷的挂载反推） */
  fileLogDirectory?: string
}>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'saved'): void
}>()

const INJECT_JAVA = 'instrumentation.opentelemetry.io/inject-java'
const INJECT_PYTHON = 'instrumentation.opentelemetry.io/inject-python'
const SIDECAR_ANNOTATION = 'sidecar.opentelemetry.io/inject'
const SIDECAR_VALUE = 'monitor/platform-otel-sidecar'

const language = ref<'java' | 'python'>('java')
const tracing = ref(false)
const logs = ref(false)
const logMode = ref<'app' | 'file'>('app')
const logDirectory = ref('/var/log/nginx')
const confirmFileLogs = ref(false)
const serviceName = ref('')
const containerName = ref('')
const saving = ref(false)

// immediate：列表行入口是「先取详情、再挂载并直接置 visible=true」，
// 此时 watcher 不会自己触发，必须在挂载时就初始化一次
watch(() => props.visible, (v) => {
  if (v) init()
}, { immediate: true })

function init(): void {
  const ann = props.annotations ?? {}
  const java = ann[INJECT_JAVA] as string | undefined
  const python = ann[INJECT_PYTHON] as string | undefined
  const value = String(java ?? python ?? '')
  const sidecar = ann[SIDECAR_ANNOTATION] === SIDECAR_VALUE
  language.value = python && !java ? 'python' : 'java'
  if (sidecar) {
    logs.value = true
    logMode.value = 'file'
    logDirectory.value = props.fileLogDirectory ?? '/var/log/nginx'
    confirmFileLogs.value = true
  } else {
    logs.value = value.endsWith('-logs') || value.endsWith('-all')
    logMode.value = 'app'
    confirmFileLogs.value = false
  }
  tracing.value = value.endsWith('platform-instrumentation') || value.endsWith('-all')
  serviceName.value = props.workloadName
  containerName.value = props.containers[0] ?? ''
}

async function submit(): Promise<void> {
  if (logs.value && logMode.value === 'file') {
    if (!confirmFileLogs.value) {
      ElMessage.warning('请勾选“确认应用把日志写入该目录”（sidecar 读不到容器 stdout）')
      return
    }
    if (!logDirectory.value.trim().startsWith('/')) {
      ElMessage.warning('日志目录必须是容器内的绝对路径（以 / 开头）')
      return
    }
  }
  saving.value = true
  try {
    await workloadApi.observability(props.workloadName, {
      tenantId: props.tenantId,
      clusterId: props.clusterId,
      namespace: props.namespace,
      kind: props.kind,
      language: language.value,
      tracing: tracing.value,
      logs: logs.value,
      serviceName: serviceName.value.trim() || undefined,
      containerName: containerName.value || undefined,
      logMode: logs.value ? logMode.value : undefined,
      logDirectory: logs.value && logMode.value === 'file' ? logDirectory.value.trim() : undefined,
      confirmFileLogs: logs.value && logMode.value === 'file' ? confirmFileLogs.value : false,
    })
    ElMessage.success('已提交，Pod 滚动更新后生效')
    emit('update:visible', false)
    emit('saved')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="visible" title="可观测接入" width="600px" append-to-body
             @update:model-value="emit('update:visible', $event)">
    <div class="wl-otel-tip">
      开启后平台给 Pod 注入 OpenTelemetry agent，只影响新建的 Pod（会触发滚动更新）。
      两个开关都关闭时，保存会移除平台写入的注入注解与文件日志卷。
    </div>
    <el-form label-width="110px">
      <el-form-item label="应用类型">
        <el-select v-model="language" style="width: 100%">
          <el-option label="Java（Logback / Log4j2）" value="java" />
          <el-option label="Python（logging / structlog）" value="python" />
          <el-option label="Node.js（第一批不支持）" value="nodejs" disabled />
        </el-select>
      </el-form-item>
      <el-form-item label="追踪">
        <el-switch v-model="tracing" />
        <span class="wl-otel-hint">traces 写入 Tempo</span>
      </el-form-item>
      <el-form-item label="日志采集">
        <el-switch v-model="logs" />
        <span class="wl-otel-hint">容器 stdout 不采；只采应用日志框架或指定目录的文件</span>
      </el-form-item>
      <el-form-item v-if="logs" label="采集方式">
        <el-radio-group v-model="logMode">
          <el-radio value="app">应用日志（Java / Python 日志框架）</el-radio>
          <el-radio value="file">文件日志（Nginx 等写文件）</el-radio>
        </el-radio-group>
      </el-form-item>
      <template v-if="logs && logMode === 'file'">
        <el-form-item label="日志目录">
          <el-input v-model="logDirectory" placeholder="/var/log/nginx" spellcheck="false" />
          <div class="wl-otel-hint wl-otel-block">
            容器内的绝对路径。平台把该目录挂成 emptyDir，sidecar 只读采集其中的 *.log 文件；
            目录下所有日志会一起被采集。需要 OTel 采集栈已重放到批次二（sidecar CR 就绪）
          </div>
        </el-form-item>
        <el-form-item label="确认写文件">
          <el-checkbox v-model="confirmFileLogs">
            确认应用把日志写入该目录（不是只打 stdout）
          </el-checkbox>
        </el-form-item>
      </template>
      <el-form-item label="服务名">
        <el-input v-model="serviceName" placeholder="默认工作负载名" />
      </el-form-item>
      <el-form-item label="主容器">
        <el-select v-model="containerName" style="width: 100%">
          <el-option v-for="c in containers" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
    </el-form>
    <div v-if="!tracing && !logs" class="wl-otel-tip">
      两个开关都关：保存后会移除注入注解与文件日志卷，回到未接入状态。
    </div>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wl-otel-tip { font-size: 13px; color: var(--text-2); margin-bottom: 12px; line-height: 1.6; }
.wl-otel-hint { margin-left: 10px; font-size: 12px; color: var(--text-3); }
.wl-otel-block { margin-left: 0; margin-top: 4px; }
</style>
