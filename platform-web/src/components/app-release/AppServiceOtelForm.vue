<script setup lang="ts">
/**
 * 应用服务「可观测」子页签：OTel 接入（desired.otel），随发布下发。
 * 语义与工作负载页「可观测接入」相同（后端共用 OtelInjection）；服务名固定为工作负载名。
 *
 * <p>组件门禁：开关从关到开需要组件旗标可用（追踪 / 应用日志 → otel-collection，日志 → otel-logs）；
 * 保存的配置里已开启的始终可关（不追溯）。
 */
import { computed } from 'vue'
import type { AppServiceOtel } from '@/types'

const props = defineProps<{
  modelValue?: AppServiceOtel
  /** 已保存配置里的 otel（判断「已开启」用） */
  original?: AppServiceOtel
  workloadName: string
  readonly: boolean
  available: Record<string, boolean>
  reasons: Record<string, string>
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: AppServiceOtel | undefined): void }>()

const cur = computed<AppServiceOtel>(() => props.modelValue ?? { tracing: false, logs: false, logMode: 'app' })

function patch(p: Partial<AppServiceOtel>): void {
  const next: AppServiceOtel = { ...cur.value, ...p }
  if (!next.tracing) delete next.sampling
  if (next.logMode !== 'file' || !next.logs) delete next.logFile
  if (!next.language) delete next.language
  if (next.sampling == null) delete next.sampling
  emit('update:modelValue', next.tracing || next.logs ? next : undefined)
}

const fileLogs = computed(() => cur.value.logs && cur.value.logMode === 'file')
const needsLanguage = computed(() => cur.value.tracing || (cur.value.logs && !fileLogs.value))

/** 关着、且已保存配置里也是关的，并且旗标不可用 → 不能打开 */
const tracingLocked = computed(() =>
  !cur.value.tracing && !props.original?.tracing && !props.available['otel-collection'])
const logsLocked = computed(() =>
  !cur.value.logs && !props.original?.logs && !props.available['otel-logs'])
/** 「日志框架」方式额外需要 otel-collection（探针注入）；已保存为该方式的保留可选 */
const appModeLocked = computed(() =>
  !props.available['otel-collection']
  && !(props.original?.logs && (props.original.logMode ?? 'app') === 'app'))

function toggleLogs(on: boolean): void {
  // 探针不可用时打开日志 → 直接落到「读日志文件」方式（唯一可用的方式）
  patch(on && appModeLocked.value && (cur.value.logMode ?? 'app') === 'app' ? { logs: true, logMode: 'file' } : { logs: on })
}
const tracingReason = computed(() => (tracingLocked.value ? props.reasons['otel-collection'] : ''))
const logsReason = computed(() => (logsLocked.value ? props.reasons['otel-logs'] : ''))
</script>

<template>
  <el-form label-width="140px" :disabled="readonly">
    <el-form-item label="链路追踪">
      <el-tooltip :disabled="!tracingLocked" :content="tracingReason" placement="top">
        <span><el-switch :model-value="cur.tracing" :disabled="tracingLocked" @update:model-value="(v: string | number | boolean) => patch({ tracing: v === true })" /></span>
      </el-tooltip>
      <span v-if="tracingLocked" class="form-tip warn">{{ tracingReason }}</span>
    </el-form-item>
    <el-form-item label="日志采集">
      <el-tooltip :disabled="!logsLocked" :content="logsReason" placement="top">
        <span><el-switch :model-value="cur.logs" :disabled="logsLocked" @update:model-value="(v: string | number | boolean) => toggleLogs(v === true)" /></span>
      </el-tooltip>
      <span v-if="logsLocked" class="form-tip warn">{{ logsReason }}</span>
    </el-form-item>
    <el-form-item v-if="cur.logs" label="采集方式">
      <el-radio-group :model-value="cur.logMode ?? 'app'" @update:model-value="(v: string | number | boolean | undefined) => patch({ logMode: v === 'file' ? 'file' : 'app' })">
        <el-radio value="app" :disabled="appModeLocked">日志框架（Java / Python）</el-radio>
        <el-radio value="file">读日志文件（Nginx 等）</el-radio>
      </el-radio-group>
      <div v-if="appModeLocked" class="form-tip warn">「日志框架」方式需要探针注入：{{ reasons['otel-collection'] }}</div>
    </el-form-item>
    <el-form-item v-if="fileLogs" label="日志文件">
      <el-input :model-value="cur.logFile ?? ''" placeholder="容器内具体文件，如 /var/log/nginx/access.log" style="width: 420px"
                @update:model-value="(v: string) => patch({ logFile: v.trim() })" />
    </el-form-item>
    <el-form-item v-if="needsLanguage" label="应用语言">
      <el-select :model-value="cur.language ?? ''" placeholder="请选择" style="width: 200px"
                 @update:model-value="(v: string) => patch({ language: v })">
        <el-option label="Java" value="java" />
        <el-option label="Python" value="python" />
      </el-select>
    </el-form-item>
    <el-form-item v-if="cur.tracing" label="采样率 %">
      <el-input-number :model-value="cur.sampling" :min="1" :max="100" placeholder="跟随组件默认"
                       @update:model-value="(v: number | undefined | null) => patch({ sampling: v ?? undefined })" />
    </el-form-item>
    <el-form-item v-if="cur.tracing || cur.logs" label="服务名">
      <code>{{ workloadName }}</code>
      <span class="form-tip">OTEL_SERVICE_NAME 固定为工作负载名，日志 / 链路按它过滤。</span>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.form-tip { color: var(--text-3); font-size: 12px; line-height: 1.6; }
div.form-tip { width: 100%; }
span.form-tip { margin-left: 10px; }
.form-tip.warn { color: var(--el-color-warning); }
</style>
