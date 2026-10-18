<script setup lang="ts">
/**
 * 工作负载「可观测接入」（OTel 批次一）。
 *
 * 只改 PodTemplate 的注入注解与主容器 OTEL_SERVICE_NAME —— 与 mesh-toggle 同一套读-改-写，
 * 后端负责保留其余字段。切换会触发滚动更新；回滚到旧 revision 可能把接入配置一起回滚。
 *
 * Node 在第一批置灰：operator 不会自动识别语言，写错注解不会报错但永远没有数据，
 * 所以不在界面上给出一个看起来可用的选项。
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
}>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'saved'): void
}>()

const INJECT_JAVA = 'instrumentation.opentelemetry.io/inject-java'
const INJECT_PYTHON = 'instrumentation.opentelemetry.io/inject-python'

const language = ref<'java' | 'python'>('java')
const tracing = ref(false)
const logs = ref(false)
const serviceName = ref('')
const containerName = ref('')
const saving = ref(false)

watch(() => props.visible, (v) => {
  if (v) init()
})

function init(): void {
  const ann = props.annotations ?? {}
  const java = ann[INJECT_JAVA] as string | undefined
  const python = ann[INJECT_PYTHON] as string | undefined
  const value = String(java ?? python ?? '')
  language.value = python && !java ? 'python' : 'java'
  if (!java && !python) {
    tracing.value = false
    logs.value = false
  } else {
    tracing.value = value.endsWith('platform-instrumentation') || value.endsWith('-all')
    logs.value = value.endsWith('-logs') || value.endsWith('-all')
  }
  serviceName.value = props.workloadName
  containerName.value = props.containers[0] ?? ''
}

async function submit(): Promise<void> {
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
  <el-dialog :model-value="visible" title="可观测接入" width="560px" append-to-body
             @update:model-value="emit('update:visible', $event)">
    <div class="wl-otel-tip">
      开启后平台给 Pod 注入 OpenTelemetry agent，只影响新建的 Pod（会触发滚动更新）。
      两个开关都关闭时，保存会移除平台写入的注入注解。
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
      <el-form-item label="应用日志">
        <el-switch v-model="logs" />
        <span class="wl-otel-hint">仅 Java / Python 日志框架；容器 stdout 不采</span>
      </el-form-item>
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
      两个开关都关：保存后会移除注入注解，回到未接入状态。
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
</style>
