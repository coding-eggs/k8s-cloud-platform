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
 * - 文件日志（file）：Nginx 等写文件的应用，由 per-Pod sidecar collector 读**指定文件**
 *   （父目录挂成 emptyDir；sidecar 读不到 stdout，没写文件就没数据）。
 *
 * Node 在第一批置灰：operator 不会自动识别语言，写错注解不会报错但永远没有数据。
 */
import { computed, ref, watch } from 'vue'
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
  /** 已接入文件日志时，应用容器上平台卷的挂载目录（日志文件的父目录，从 volumeMount 反推） */
  fileLogDirectory?: string
  /**
   * 集群是否启用了「工作负载可观测」（OTel 采集栈健康）。false 时整个表单禁用：
   * 没有 otel-operator 时写进去的注入注解不会有人消费 —— 填了也没有数据。
   * undefined = 还没探测到（读不到 ≠ 不可用，不拦）。
   */
  otelAvailable?: boolean
  /** 上面为 false 时的原因（来自组件旗标，如「缺 OTel 采集栈」） */
  otelUnavailableReason?: string
  /** 集群的 OTel 采集栈是否开了「应用日志」；false 时日志开关不能打开（gateway 没有 logs 管道） */
  logsAvailable?: boolean
  logsUnavailableReason?: string
  /** 主容器当前的 OTEL_TRACES_SAMPLER_ARG（如 "0.1"）；有值说明设过工作负载级采样率 */
  samplerArg?: string
}>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'saved'): void
}>()

const INJECT_JAVA = 'instrumentation.opentelemetry.io/inject-java'
const INJECT_PYTHON = 'instrumentation.opentelemetry.io/inject-python'
const SIDECAR_ANNOTATION = 'sidecar.opentelemetry.io/inject'
const SIDECAR_VALUE = 'monitor/platform-otel-sidecar'
/** 平台写入的具体日志文件（sidecar 视角，如 /var/log/app/access.log） */
const LOG_FILE_ANNOTATION = 'platform.coding/otel-log-file'

const language = ref<'java' | 'python'>('java')
const tracing = ref(false)
const logs = ref(false)
const logMode = ref<'app' | 'file'>('app')
const logFile = ref('/var/log/nginx/access.log')
const serviceName = ref('')
const containerName = ref('')
/** 工作负载级采样率（百分比）；null = 跟随组件默认（会移除这两个 env） */
const sampling = ref<number | null>(null)
const saving = ref(false)

/**
 * 集群没开「工作负载可观测」时整个表单不可填：
 * 注入注解由 otel-operator 的 Instrumentation 消费，operator 不在写进去也没人管。
 * 只有明确 false 才禁（undefined = 还没探测到，不拦）。
 */
const stackUnavailable = computed(() => props.otelAvailable === false)
const stackReason = computed(() =>
  props.otelUnavailableReason || '集群未安装或未就绪 OTel 采集栈（otel-operator）')

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
    // 回填：父目录（卷挂载）+ 文件名（平台注解里 sidecar 视角的 /var/log/app/<文件名>）
    const mount = props.fileLogDirectory ?? '/var/log/nginx'
    const logged = typeof ann[LOG_FILE_ANNOTATION] === 'string' ? ann[LOG_FILE_ANNOTATION] as string : ''
    const fileName = logged.split('/').filter(Boolean).pop()
    logFile.value = fileName ? `${mount}/${fileName}` : `${mount}/access.log`
  } else {
    logs.value = value.endsWith('-logs') || value.endsWith('-all')
    logMode.value = 'app'
  }
  tracing.value = value.endsWith('platform-instrumentation') || value.endsWith('-all')
  serviceName.value = props.workloadName
  containerName.value = props.containers[0] ?? ''
  // 工作负载级采样率回填：env 里存的是比例（0.1），表单用百分比
  const ratio = Number.parseFloat(props.samplerArg ?? '')
  sampling.value = Number.isFinite(ratio) ? Math.round(ratio * 100) : null
}

async function submit(): Promise<void> {
  if (stackUnavailable.value) {
    ElMessage.warning('集群未启用工作负载可观测（OTel 采集栈不可用），现在填写不会生效')
    return
  }
  if (logs.value && logMode.value === 'file') {
    const file = logFile.value.trim()
    if (!file.startsWith('/') || file.endsWith('/')) {
      ElMessage.warning('请填到具体日志文件（容器内绝对路径，如 /var/log/nginx/access.log）')
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
      // 只追踪时才有意义；null/空 = 交给后端移除 env、回到组件默认采样率
      sampling: tracing.value && sampling.value != null ? sampling.value : undefined,
      logMode: logs.value ? logMode.value : undefined,
      logFile: logs.value && logMode.value === 'file' ? logFile.value.trim() : undefined,
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
    <el-alert v-if="stackUnavailable" type="warning" :closable="false" show-icon class="wl-otel-alert"
              :title="'集群未启用工作负载可观测：' + stackReason"
              description="这个表单写的是 OTel 注入注解（日志 / 追踪都靠采集栈消费），没有采集栈时填了也不会生效。请先在集群组件页安装 otel-operator 并等它就绪。" />
    <div class="wl-otel-tip">
      开启后平台给 Pod 注入 OpenTelemetry agent，只影响新建的 Pod（会触发滚动更新）。
      两个开关都关闭时，保存会移除平台写入的注入注解与文件日志卷。
    </div>
    <el-form label-width="110px" :disabled="stackUnavailable">
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
      <el-form-item v-if="tracing" label="采样率">
        <el-input-number v-model="sampling" :min="1" :max="100" :step="10"
                         :value-on-clear="null" placeholder="跟随组件" style="width: 160px" />
        <span class="wl-otel-hint">%，留空 = 跟随组件的全局采样率</span>
        <div class="wl-otel-hint wl-otel-block">
          写在本工作负载上（OTEL_TRACES_SAMPLER / OTEL_TRACES_SAMPLER_ARG），<b>优先级高于</b>组件侧采样率。
          这是按整条 trace 的决定（错误不会被单独保留）；留空并保存会移除这两个变量，回到组件默认
        </div>
      </el-form-item>
      <el-form-item label="日志采集">
        <el-switch v-model="logs" :disabled="logsAvailable === false && !logs" />
        <span class="wl-otel-hint">容器 stdout 不采；只采应用日志框架或指定文件</span>
        <div v-if="logsAvailable === false" class="wl-otel-hint wl-otel-block">
          {{ logsUnavailableReason || '集群的 OTel 采集栈未开启「应用日志」' }}。
          现在打开也不会有数据，需先在集群组件页打开「应用日志」并重新提交
        </div>
      </el-form-item>
      <el-form-item v-if="logs" label="采集方式">
        <el-radio-group v-model="logMode">
          <el-radio value="app">应用日志（Java / Python 日志框架）</el-radio>
          <el-radio value="file">文件日志（Nginx 等写文件）</el-radio>
        </el-radio-group>
      </el-form-item>
      <template v-if="logs && logMode === 'file'">
        <el-form-item label="日志文件">
          <el-input v-model="logFile" placeholder="/var/log/nginx/access.log" spellcheck="false" />
          <div class="wl-otel-hint wl-otel-block">
            容器内的<b>具体文件</b>（不是目录）。平台把它的父目录挂成 emptyDir，sidecar 只采这一个文件；
            支持 glob（如 <code>access.log*</code>，可连带轮转文件）
          </div>
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
      <el-button type="primary" :loading="saving" :disabled="stackUnavailable" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wl-otel-alert { margin-bottom: 12px; }
.wl-otel-tip { font-size: 13px; color: var(--text-2); margin-bottom: 12px; line-height: 1.6; }
.wl-otel-hint { margin-left: 10px; font-size: 12px; color: var(--text-3); }
.wl-otel-block { margin-left: 0; margin-top: 4px; }
</style>
