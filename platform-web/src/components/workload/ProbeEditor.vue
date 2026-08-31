<script setup lang="ts">
import { computed, watch } from 'vue'
import type { Probe } from '@/types/workload'

type HandlerKind = 'none' | 'httpGet' | 'tcpSocket' | 'exec'

const props = defineProps<{ kind: string }>()
const model = defineModel<Probe | null>()

/** A2：以「哪个子对象存在」判定当前 handler（null/空 Probe 无 handler = 未配置） */
function handlerOf(p?: Probe | null): HandlerKind {
  if (p?.httpGet) return 'httpGet'
  if (p?.tcpSocket) return 'tcpSocket'
  if (p?.exec) return 'exec'
  return 'none'
}

/** 所有写操作统一走这里：合并进 model；liveness/startup 强制 successThreshold=1 */
function write(partial: Partial<Probe>): void {
  const next: Probe = { ...(model.value ?? {}), ...partial }
  if (props.kind !== 'readiness') next.successThreshold = 1
  model.value = next
}

/** 切换 handler：清空其它子对象，只保留选中项（A2 互斥）；选「无」= 整个探测未配置 → model=null */
const handler = computed<HandlerKind>({
  get: () => handlerOf(model.value),
  set: (k) => {
    if (k === 'none') { model.value = null; return }
    const partial: Partial<Probe> = { httpGet: null, tcpSocket: null, exec: null }
    if (k === 'httpGet') partial.httpGet = { port: '', path: '', scheme: 'HTTP' }
    else if (k === 'tcpSocket') partial.tcpSocket = { port: '' }
    else if (k === 'exec') partial.exec = { command: [] }
    write(partial)
  },
})

/** 外部加载了非 1 的 successThreshold 时纠正（liveness/startup） */
watch(
  () => model.value?.successThreshold,
  (t) => {
    if (props.kind !== 'readiness' && t != null && t !== 1) write({ successThreshold: 1 })
  },
)

/** exec command：textarea 按行/逗号切分 → string[] */
const execCommandText = computed<string>({
  get: () => model.value?.exec?.command.join('\n') ?? '',
  set: (text) => {
    const command = text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean)
    write({ exec: { command } })
  },
})
</script>

<template>
  <div class="probe-editor">
    <div class="pe-row">
      <span class="pe-label">Handler</span>
      <el-radio-group :model-value="handler" @update:model-value="(k: HandlerKind) => (handler = k)">
        <el-radio value="none">无</el-radio>
        <el-radio value="httpGet">httpGet</el-radio>
        <el-radio value="tcpSocket">tcpSocket</el-radio>
        <el-radio value="exec">exec</el-radio>
      </el-radio-group>
    </div>

    <div v-if="handler === 'httpGet' && model?.httpGet" class="pe-row">
      <span class="pe-label">httpGet</span>
      <el-input v-model="model.httpGet.port" placeholder="端口（数字或命名端口）" style="width: 150px" />
      <el-input v-model="model.httpGet.path" placeholder="路径（如 /healthz）" style="width: 180px" />
      <el-select v-model="model.httpGet.scheme" style="width: 110px">
        <el-option label="HTTP" value="HTTP" />
        <el-option label="HTTPS" value="HTTPS" />
      </el-select>
    </div>

    <div v-else-if="handler === 'tcpSocket' && model?.tcpSocket" class="pe-row">
      <span class="pe-label">tcpSocket</span>
      <el-input v-model="model.tcpSocket.port" placeholder="端口（数字或命名端口）" style="width: 150px" />
    </div>

    <div v-else-if="handler === 'exec' && model?.exec" class="pe-row">
      <span class="pe-label">exec</span>
      <el-input v-model="execCommandText" type="textarea" :rows="2" placeholder="命令，每行一个（或逗号分隔）" style="flex: 1; min-width: 240px" />
    </div>

    <div v-if="model && handler !== 'none'" class="pe-numbers">
      <div class="num-item">
        <span class="num-label">initialDelaySeconds</span>
        <el-input-number v-model="model.initialDelaySeconds" :min="0" controls-position="right" />
      </div>
      <div class="num-item">
        <span class="num-label">periodSeconds</span>
        <el-input-number v-model="model.periodSeconds" :min="1" controls-position="right" />
      </div>
      <div class="num-item">
        <span class="num-label">timeoutSeconds</span>
        <el-input-number v-model="model.timeoutSeconds" :min="1" controls-position="right" />
      </div>
      <div class="num-item">
        <span class="num-label">successThreshold</span>
        <el-input-number v-model="model.successThreshold" :min="1" :disabled="kind !== 'readiness'" controls-position="right" />
      </div>
      <div class="num-item">
        <span class="num-label">failureThreshold</span>
        <el-input-number v-model="model.failureThreshold" :min="1" controls-position="right" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.probe-editor { width: 100% }
.pe-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.pe-label { width: 90px; flex-shrink: 0; font-size: 12px; color: var(--el-text-color-secondary) }
.pe-numbers { display: flex; gap: 8px; flex-wrap: wrap; padding-left: 98px }
.num-item { display: flex; align-items: center; gap: 4px }
.num-label { font-size: 12px; color: var(--el-text-color-secondary) }
</style>
