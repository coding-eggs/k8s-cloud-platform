<script setup lang="ts">
/**
 * 应用服务「可观测」子页签：应用语言 + 日志采集（desired.otel），随发布下发。
 * 语义与工作负载页「可观测接入」相同（后端共用 OtelInjection）；服务名固定为工作负载名。
 *
 * <p>链路追踪是应用级设置（应用详情「应用设置」）：应用开启时，选了语言的服务才被追踪，这里只说明本服务是否会被追踪。
 *
 * <p>组件门禁：日志开关从关到开需要 otel-logs；「日志框架」方式需要 otel-collection（探针注入）；
 * 应用开着追踪时，首次给服务选语言 = 本服务开始被追踪，同样需要 otel-collection。已保存的取值始终保留（不追溯）。
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
  /** 应用设置（期望值）里链路追踪是否开启 */
  appTracing: boolean
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: AppServiceOtel | undefined): void }>()

const cur = computed<AppServiceOtel>(() => props.modelValue ?? { logs: false })

function patch(p: Partial<AppServiceOtel>): void {
  const next: AppServiceOtel = { ...cur.value, ...p }
  if (!next.logs) { delete next.logMode; delete next.logFile }
  if (next.logMode !== 'file') delete next.logFile
  if (!next.language) delete next.language
  emit('update:modelValue', next.logs || next.language ? next : undefined)
}

const fileLogs = computed(() => cur.value.logs && cur.value.logMode === 'file')
const languageRequired = computed(() => cur.value.logs && !fileLogs.value)

const collectionOk = computed(() => !!props.available['otel-collection'])
/** 关着、且已保存配置里也是关的，并且旗标不可用 → 不能打开 */
const logsLocked = computed(() =>
  !cur.value.logs && !props.original?.logs && !props.available['otel-logs'])
/** 「日志框架」方式额外需要 otel-collection（探针注入）；已保存为该方式的保留可选 */
const appModeLocked = computed(() =>
  !collectionOk.value && !(props.original?.logs && (props.original.logMode ?? 'app') === 'app'))
/** 应用开着追踪、组件不可用、原来没选语言 → 选语言会让本服务开始被追踪，保存会被拒 */
const languageLocked = computed(() =>
  props.appTracing && !collectionOk.value && !props.original?.language && !cur.value.language)

function toggleLogs(on: boolean): void {
  // 探针不可用时打开日志 → 直接落到「读日志文件」方式（唯一可用的方式）
  patch(on && appModeLocked.value && (cur.value.logMode ?? 'app') === 'app' ? { logs: true, logMode: 'file' } : { logs: on })
}
const logsReason = computed(() => (logsLocked.value ? props.reasons['otel-logs'] : ''))
const tracedText = computed(() => {
  if (!props.appTracing) return '应用未开启链路追踪（在应用详情「应用设置」里开关，作用于全部服务）。'
  return cur.value.language
    ? '应用已开启链路追踪：本服务会被追踪（发布后生效）。'
    : '应用已开启链路追踪：本服务没选语言，不会被追踪（Nginx、Go 等无法自动注入探针的服务保持留空）。'
})
</script>

<template>
  <el-form label-width="140px" :disabled="readonly">
    <el-form-item label="应用语言">
      <el-select :model-value="cur.language ?? ''" placeholder="不注入探针" clearable style="width: 200px"
                 :disabled="languageLocked"
                 @update:model-value="(v: string) => patch({ language: v || undefined })">
        <el-option label="Java" value="java" />
        <el-option label="Python" value="python" />
      </el-select>
      <span v-if="languageRequired && !cur.language" class="form-tip warn">「日志框架」方式需要选择语言</span>
      <div class="form-tip">{{ tracedText }}</div>
      <div v-if="languageLocked" class="form-tip warn">链路追踪组件不可用：{{ reasons['otel-collection'] }}</div>
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
    <el-form-item v-if="cur.logs || cur.language" label="服务名">
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
