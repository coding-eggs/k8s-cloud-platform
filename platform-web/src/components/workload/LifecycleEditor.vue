<script setup lang="ts">
import { watch } from 'vue'
import type { ExecAction, HttpGetAction, Lifecycle, LifecycleHandler, SleepAction } from '@/types/workload'
import FieldHelp from './FieldHelp.vue'

type LifeAction = 'none' | 'exec' | 'httpGet' | 'sleep'
type Slot = 'postStart' | 'preStop'

/** 动作类型 / 子字段说明（FieldHelp） */
const T_EXEC = '在容器内执行命令，每行一个或用逗号分隔；退出码为 0 视为执行成功。'
const T_HTTPGET = '向容器发起 HTTP GET 请求，返回 2xx/3xx 状态码视为成功。'
const T_SLEEP = '休眠指定秒数后继续（常用于 preStop 优雅下线前等待）。'
const T_SCHEME = '请求使用的协议：HTTP（明文，默认）或 HTTPS（加密）。'
const T_PORT = '要访问的容器端口（数字，1-65535）。'
const T_PATH = '请求的 URL 路径，如 /healthz；留空为根路径 /。'
const T_SECONDS = '休眠的秒数。'

const model = defineModel<Lifecycle>()

/** A2：以「哪个子对象存在」判定当前动作（空 handler 无动作 = 未配置） */
function actionOf(h?: LifecycleHandler | null): LifeAction {
  if (h?.exec) return 'exec'
  if (h?.httpGet) return 'httpGet'
  if (h?.sleep) return 'sleep'
  return 'none'
}

/** 本地暂存：按槽位缓存各动作已填数据，切走再切回可恢复。model 始终只保留当前一个动作子对象（A2 不变式） */
type SlotStash = { exec?: ExecAction | null; httpGet?: HttpGetAction | null; sleep?: SleepAction | null }
const stash: Record<Slot, SlotStash> = { postStart: {}, preStop: {} }
/** 防回环：外部加载（loadDetail/重置）时据此重建 stash，避免上一个工作负载的残留串数据 */
let lastEmitted: string | null = null

watch(
  () => model.value,
  (p) => {
    const snap = JSON.stringify(p ?? null)
    if (snap === lastEmitted) return   // 自己 commit 的回声 → 跳过
    stash.postStart = { exec: p?.postStart?.exec ?? null, httpGet: p?.postStart?.httpGet ?? null, sleep: p?.postStart?.sleep ?? null }
    stash.preStop = { exec: p?.preStop?.exec ?? null, httpGet: p?.preStop?.httpGet ?? null, sleep: p?.preStop?.sleep ?? null }
  },
  { immediate: true },
)

function commit(next: Lifecycle): void {
  lastEmitted = JSON.stringify(next)
  model.value = next
}

/** 切换动作：先暂存当前子对象，再从缓存恢复目标（或默认），清空其它（A2 互斥） */
function setAction(slot: Slot, a: LifeAction): void {
  const cur = actionOf(model.value?.[slot])
  if (cur !== 'none') {
    const m = stash[slot]
    const h = model.value?.[slot]
    // 只暂存当前真实存在的动作，避免把其它动作已缓存的数据覆盖成 null（切走再切回丢值）
    if (h?.exec) m.exec = h.exec
    if (h?.httpGet) m.httpGet = h.httpGet
    if (h?.sleep) m.sleep = h.sleep
  }
  let nextHandler: LifecycleHandler | null
  if (a === 'none') nextHandler = null
  else if (a === 'exec') nextHandler = { exec: stash[slot].exec ?? { command: [] }, httpGet: null, sleep: null }
  else if (a === 'httpGet') nextHandler = { exec: null, httpGet: stash[slot].httpGet ?? { path: '', scheme: 'HTTP' }, sleep: null }
  else nextHandler = { exec: null, httpGet: null, sleep: stash[slot].sleep ?? { seconds: null } }
  const next: Lifecycle = { ...(model.value ?? {}) }
  next[slot] = nextHandler
  commit(next)
}

/** exec command：textarea 按行/逗号切分 → string[] */
function cmdText(slot: Slot): string {
  return model.value?.[slot]?.exec?.command.join('\n') ?? ''
}

function setCmd(slot: Slot, text: string): void {
  const command = text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean)
  const next: Lifecycle = { ...(model.value ?? {}) }
  next[slot] = { exec: { command }, httpGet: null, sleep: null }
  commit(next)
}

/** httpGet 端口：仅数字（本平台限制探针端口为数字，不支持命名端口） */
function httpPort(slot: Slot): number | undefined {
  return model.value?.[slot]?.httpGet?.port
}
function setHttpPort(slot: Slot, v: number | null | undefined): void {
  const h = model.value?.[slot]
  if (h?.httpGet) h.httpGet.port = v == null ? undefined : v
}
</script>

<template>
  <div class="life-editor">
    <div class="life-block">
      <div class="life-head">
        <span class="life-title">postStart <FieldHelp tip="容器启动后立即执行的钩子（与容器入口并行运行）。执行失败会导致容器重启。" /></span>
        <el-radio-group :model-value="actionOf(model?.postStart)" @update:model-value="(a: LifeAction) => setAction('postStart', a)">
          <el-radio value="none">无</el-radio>
          <el-radio value="exec">exec<FieldHelp :tip="T_EXEC" /></el-radio>
          <el-radio value="httpGet">httpGet<FieldHelp :tip="T_HTTPGET" /></el-radio>
          <el-radio value="sleep">sleep<FieldHelp :tip="T_SLEEP" /></el-radio>
        </el-radio-group>
      </div>
      <div v-if="model?.postStart?.exec" class="life-fields">
        <div class="sub-field">
          <span class="sub-label">命令 <FieldHelp :tip="T_EXEC" /></span>
          <el-input :model-value="cmdText('postStart')" type="textarea" :rows="2" placeholder="命令，每行一个（或逗号分隔）" style="width: 360px; max-width: 100%" @update:model-value="(t: string) => setCmd('postStart', t)" />
        </div>
      </div>
      <div v-else-if="model?.postStart?.httpGet" class="life-fields">
        <div class="sub-field">
          <span class="sub-label">协议 <FieldHelp :tip="T_SCHEME" /></span>
          <el-select v-model="model.postStart.httpGet.scheme" style="width: 140px">
            <el-option label="HTTP" value="HTTP" />
            <el-option label="HTTPS" value="HTTPS" />
          </el-select>
        </div>
        <div class="sub-field">
          <span class="sub-label">端口 <FieldHelp :tip="T_PORT" /></span>
          <el-input-number :model-value="httpPort('postStart')" :min="1" :max="65535" controls-position="right" style="width: 200px" @update:model-value="(v: number | undefined) => setHttpPort('postStart', v)" />
        </div>
        <div class="sub-field">
          <span class="sub-label">路径 <FieldHelp :tip="T_PATH" /></span>
          <el-input v-model="model.postStart.httpGet.path" placeholder="如 /healthz，留空为 /" style="width: 240px" />
        </div>
      </div>
      <div v-else-if="model?.postStart?.sleep" class="life-fields">
        <div class="sub-field">
          <span class="sub-label">seconds <FieldHelp :tip="T_SECONDS" /></span>
          <el-input-number v-model="model.postStart.sleep.seconds" :min="0" controls-position="right" />
        </div>
      </div>
    </div>

    <div class="life-block">
      <div class="life-head">
        <span class="life-title">preStop <FieldHelp tip="容器停止前执行的钩子（发送 SIGTERM 后运行）。可用于优雅下线，最长等待 terminationGracePeriodSeconds。" /></span>
        <el-radio-group :model-value="actionOf(model?.preStop)" @update:model-value="(a: LifeAction) => setAction('preStop', a)">
          <el-radio value="none">无</el-radio>
          <el-radio value="exec">exec<FieldHelp :tip="T_EXEC" /></el-radio>
          <el-radio value="httpGet">httpGet<FieldHelp :tip="T_HTTPGET" /></el-radio>
          <el-radio value="sleep">sleep<FieldHelp :tip="T_SLEEP" /></el-radio>
        </el-radio-group>
      </div>
      <div v-if="model?.preStop?.exec" class="life-fields">
        <div class="sub-field">
          <span class="sub-label">命令 <FieldHelp :tip="T_EXEC" /></span>
          <el-input :model-value="cmdText('preStop')" type="textarea" :rows="2" placeholder="命令，每行一个（或逗号分隔）" style="width: 360px; max-width: 100%" @update:model-value="(t: string) => setCmd('preStop', t)" />
        </div>
      </div>
      <div v-else-if="model?.preStop?.httpGet" class="life-fields">
        <div class="sub-field">
          <span class="sub-label">协议 <FieldHelp :tip="T_SCHEME" /></span>
          <el-select v-model="model.preStop.httpGet.scheme" style="width: 140px">
            <el-option label="HTTP" value="HTTP" />
            <el-option label="HTTPS" value="HTTPS" />
          </el-select>
        </div>
        <div class="sub-field">
          <span class="sub-label">端口 <FieldHelp :tip="T_PORT" /></span>
          <el-input-number :model-value="httpPort('preStop')" :min="1" :max="65535" controls-position="right" style="width: 200px" @update:model-value="(v: number | undefined) => setHttpPort('preStop', v)" />
        </div>
        <div class="sub-field">
          <span class="sub-label">路径 <FieldHelp :tip="T_PATH" /></span>
          <el-input v-model="model.preStop.httpGet.path" placeholder="如 /healthz，留空为 /" style="width: 240px" />
        </div>
      </div>
      <div v-else-if="model?.preStop?.sleep" class="life-fields">
        <div class="sub-field">
          <span class="sub-label">seconds <FieldHelp :tip="T_SECONDS" /></span>
          <el-input-number v-model="model.preStop.sleep.seconds" :min="0" controls-position="right" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.life-editor { width: 100% }
.life-block { margin-bottom: 8px }
.life-head { display: flex; gap: 8px; align-items: center; margin-bottom: 8px }
.life-title { width: 96px; flex-shrink: 0; font-size: 12px; color: var(--el-text-color-secondary); display: inline-flex; align-items: center; gap: 4px; white-space: nowrap }
/* 子字段：竖排，每行 = label(+FieldHelp) + 控件 */
.life-fields { display: flex; flex-direction: column; gap: 8px; padding-left: 104px }
.sub-field { display: flex; align-items: center; gap: 12px }
.sub-label { width: 90px; flex-shrink: 0; font-size: 13px; color: var(--el-text-color-secondary); display: inline-flex; align-items: center; gap: 4px; white-space: nowrap }
</style>
