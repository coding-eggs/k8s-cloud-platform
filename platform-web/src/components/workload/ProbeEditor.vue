<script setup lang="ts">
import { computed, watch } from 'vue'
import type { ExecAction, HttpGetAction, Probe, TCPSocketAction } from '@/types/workload'
import FieldHelp from './FieldHelp.vue'

type HandlerKind = 'none' | 'httpGet' | 'tcpSocket' | 'exec'
type HKey = 'httpGet' | 'tcpSocket' | 'exec'

const props = defineProps<{ kind: string }>()
const model = defineModel<Probe | null>()

/** A2：以「哪个子对象存在」判定当前 handler（null/空 Probe 无 handler = 未配置） */
function handlerOf(p?: Probe | null): HandlerKind {
  if (p?.httpGet) return 'httpGet'
  if (p?.tcpSocket) return 'tcpSocket'
  if (p?.exec) return 'exec'
  return 'none'
}

/** 本地暂存：切换 handler 前保存各 handler 已填数据，切回时恢复。
 *  model 始终只保留当前一个 handler 子对象（A2 不变式，提交/转换器不变） */
const stash: { httpGet?: HttpGetAction | null; tcpSocket?: TCPSocketAction | null; exec?: ExecAction | null } = {}
/** 防回环：记住自己 write 出去的 JSON 快照；外部加载（loadDetail/重置）时据此重建 stash，避免上一个工作负载的残留串数据 */
let lastEmitted: string | null = null

watch(
  () => model.value,
  (p) => {
    const snap = JSON.stringify(p ?? null)
    if (snap === lastEmitted) return   // 自己 write 的回声 → 跳过
    stash.httpGet = p?.httpGet ?? null
    stash.tcpSocket = p?.tcpSocket ?? null
    stash.exec = p?.exec ?? null
  },
  { immediate: true },
)

/** 所有写操作统一走这里：合并进 model；liveness/startup 强制 successThreshold=1 */
function write(partial: Partial<Probe>): void {
  const next: Probe = { ...(model.value ?? {}), ...partial }
  if (props.kind !== 'readiness') next.successThreshold = 1
  lastEmitted = JSON.stringify(next)
  model.value = next
}

/** 切换 handler：先暂存当前子对象，再从缓存恢复目标（或默认），清空其它（A2 互斥）；选「无」= 整个探测未配置 → model=null */
const handler = computed<HandlerKind>({
  get: () => handlerOf(model.value),
  set: (k) => {
    const cur = handlerOf(model.value)
    if (cur === 'httpGet') stash.httpGet = model.value?.httpGet ?? null
    else if (cur === 'tcpSocket') stash.tcpSocket = model.value?.tcpSocket ?? null
    else if (cur === 'exec') stash.exec = model.value?.exec ?? null
    if (k === 'none') { lastEmitted = 'null'; model.value = null; return }
    const partial: Partial<Probe> = { httpGet: null, tcpSocket: null, exec: null }
    if (k === 'httpGet') partial.httpGet = stash.httpGet ?? { port: '', path: '', scheme: 'HTTP' }
    else if (k === 'tcpSocket') partial.tcpSocket = stash.tcpSocket ?? { port: '' }
    else partial.exec = stash.exec ?? { command: [] }
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
        <el-radio value="httpGet">httpGet<FieldHelp tip="向容器发起 HTTP GET 请求，返回 2xx/3xx 状态码视为成功。" /></el-radio>
        <el-radio value="tcpSocket">tcpSocket<FieldHelp tip="尝试对指定端口建立 TCP 连接，能连上即视为成功。" /></el-radio>
        <el-radio value="exec">exec<FieldHelp tip="在容器内执行命令，退出码为 0 视为成功。" /></el-radio>
      </el-radio-group>
    </div>

    <div v-if="handler === 'httpGet' && model?.httpGet" class="pe-fields">
      <div class="sub-field">
        <span class="sub-label">协议 <FieldHelp tip="请求使用的协议：HTTP（明文，默认）或 HTTPS（加密）。" /></span>
        <el-select v-model="model.httpGet.scheme" style="width: 140px">
          <el-option label="HTTP" value="HTTP" />
          <el-option label="HTTPS" value="HTTPS" />
        </el-select>
      </div>
      <div class="sub-field">
        <span class="sub-label">端口 <FieldHelp tip="要访问的容器端口，可填数字或命名端口（须与容器声明的 ports 对应）。" /></span>
        <el-input v-model="model.httpGet.port" placeholder="数字或命名端口" style="width: 200px" />
      </div>
      <div class="sub-field">
        <span class="sub-label">路径 <FieldHelp tip="请求的 URL 路径，如 /healthz；留空为根路径 /。返回 2xx/3xx 视为探测成功。" /></span>
        <el-input v-model="model.httpGet.path" placeholder="如 /healthz，留空为 /" style="width: 240px" />
      </div>
    </div>

    <div v-else-if="handler === 'tcpSocket' && model?.tcpSocket" class="pe-fields">
      <div class="sub-field">
        <span class="sub-label">端口 <FieldHelp tip="要尝试建立 TCP 连接的容器端口，可填数字或命名端口；能连上即视为成功。" /></span>
        <el-input v-model="model.tcpSocket.port" placeholder="数字或命名端口" style="width: 200px" />
      </div>
    </div>

    <div v-else-if="handler === 'exec' && model?.exec" class="pe-fields">
      <div class="sub-field">
        <span class="sub-label">命令 <FieldHelp tip="在容器内执行的命令，每行一个或用逗号分隔；退出码为 0 视为探测成功。" /></span>
        <el-input v-model="execCommandText" type="textarea" :rows="2" placeholder="命令，每行一个（或逗号分隔）" style="width: 360px; max-width: 100%" />
      </div>
    </div>

    <div v-if="model && handler !== 'none'" class="pe-numbers">
      <div class="num-row">
        <span class="num-label">初始延迟 <span class="en">initialDelaySeconds</span> <FieldHelp tip="首次探测前等待的秒数（容器启动后延迟多久才开始探测）。默认 0；启动慢的应用可留足时间。" /></span>
        <el-input-number v-model="model.initialDelaySeconds" :min="0" controls-position="right" />
      </div>
      <div class="num-row">
        <span class="num-label">探测间隔 <span class="en">periodSeconds</span> <FieldHelp tip="两次探测之间的间隔秒数。默认 10，最小 1。" /></span>
        <el-input-number v-model="model.periodSeconds" :min="1" controls-position="right" />
      </div>
      <div class="num-row">
        <span class="num-label">探测超时 <span class="en">timeoutSeconds</span> <FieldHelp tip="单次探测允许的最长耗时（秒），超过即判定本次失败。默认 1，最小 1。" /></span>
        <el-input-number v-model="model.timeoutSeconds" :min="1" controls-position="right" />
      </div>
      <div class="num-row">
        <span class="num-label">成功阈值 <span class="en">successThreshold</span> <FieldHelp tip="从失败转为成功所需的最小连续成功次数。仅就绪探针可 > 1；存活/启动探针固定为 1。默认 1。" /></span>
        <el-input-number v-model="model.successThreshold" :min="1" :disabled="kind !== 'readiness'" controls-position="right" />
      </div>
      <div class="num-row">
        <span class="num-label">失败阈值 <span class="en">failureThreshold</span> <FieldHelp tip="判定失败前允许的最大连续失败次数，超过即触发动作（如存活探针重启容器）。默认 3，最小 1。" /></span>
        <el-input-number v-model="model.failureThreshold" :min="1" controls-position="right" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.probe-editor { width: 100% }
.pe-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.pe-label { width: 90px; flex-shrink: 0; font-size: 12px; color: var(--el-text-color-secondary) }
/* handler 子字段：竖排，每行 = label(+FieldHelp) + 控件 */
.pe-fields { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px }
.sub-field { display: flex; align-items: center; gap: 12px }
.sub-label { width: 90px; flex-shrink: 0; font-size: 13px; color: var(--el-text-color-secondary); display: inline-flex; align-items: center; gap: 4px; white-space: nowrap }
.pe-numbers {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.num-row { display: flex; align-items: center; gap: 12px }
.num-label {
  width: 230px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}
.num-label .en { font-size: 12px; color: var(--text-3) }
</style>
