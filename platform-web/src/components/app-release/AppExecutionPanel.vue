<script setup lang="ts">
/**
 * 发布进度面板：阶段 + 每个服务的健康 + 失败 / 中断时的「重新执行 / 继续 / 回滚」。
 *
 * 轮询：只在执行处于 PENDING / RUNNING 时才对 `/application/execution/active` 轮询，
 * 一旦返回空（执行结束）或进入 FAILED / INTERRUPTED 就停；卸载时清掉定时器。
 * 每次轮询都向父页发 `tick`，父页据此刷新 overview（服务现读状态来自那里）。
 *
 * 动作与后端规则对齐：
 * - 继续 / 重新执行 = 同一个 `execution/resume`；INTERRUPTED 叫「继续」，FAILED 叫「重新执行」（从失败阶段接着跑，发布号不变）。
 *   后端只允许最新发布的那条执行继续，旧的会被拒绝，原文提示。
 * - 回滚：只在有线上版本可回时给出，回到线上版本（失败发布本身比线上新，所以合法）。
 * - 失败不会自动回滚，这里不暗示它做过什么。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { appReleaseApi } from '@/api'
import StatusBadge from '@/components/StatusBadge.vue'
import AppExecutionLog from './AppExecutionLog.vue'
import type { AppExecution, AppServiceRow, AppServiceState } from '@/types'
import { errMsg, EXEC_STAGE, EXEC_STATUS, isRunning, OFFLINE_STAGE_ORDER, STAGE_ORDER, stageStep } from './releaseUtil'

const props = defineProps<{
  appId: string
  tenantId?: string
  /** overview 的 activeExecution，没有则用失败的 lastExecution */
  execution: AppExecution | null
  services: AppServiceRow[]
  liveNo: number
  canWrite: boolean
}>()
const emit = defineEmits<{
  (e: 'tick'): void
  (e: 'changed'): void
  (e: 'rollback'): void
}>()

const STATE_META: Record<AppServiceState, { text: string; type: 'success' | 'warning' | 'danger' | 'info' | 'neutral' }> = {
  READY: { text: '运行正常', type: 'success' },
  PENDING: { text: '启动中', type: 'warning' },
  FAILED: { text: '异常', type: 'danger' },
  MISSING: { text: '线上缺失', type: 'danger' },
  UNKNOWN: { text: '状态未知', type: 'neutral' },
  NOT_RELEASED: { text: '未发布', type: 'info' },
}

const current = ref<AppExecution | null>(props.execution)
const acting = ref(false)
const error = ref('')
/** 每次轮询 +1，过程日志跟着重读 */
const logTick = ref(0)
/** 失败 / 中断 / 进行中默认展开日志；用户手动收起后不再自动展开 */
const showLog = ref(true)

let timer: number | null = null
function stopPoll(): void {
  if (timer != null) { window.clearInterval(timer); timer = null }
}
async function poll(): Promise<void> {
  try {
    const e = await appReleaseApi.activeExecution(props.appId, props.tenantId)
    if (e) current.value = e
    logTick.value++
    emit('tick')
    if (!e || !isRunning(e.status)) stopPoll()
  } catch {
    // 拦截器已提示；下一轮再试
  }
}
function syncPoll(): void {
  if (isRunning(current.value?.status)) {
    if (timer == null) timer = window.setInterval(() => { void poll() }, 3000)
  } else {
    stopPoll()
  }
}
watch(() => props.execution, (e) => { current.value = e; syncPoll() }, { immediate: true })
onBeforeUnmount(stopPoll)

const exec = computed(() => current.value)
const running = computed(() => isRunning(exec.value?.status))
const interrupted = computed(() => exec.value?.status === 'INTERRUPTED')
const failed = computed(() => exec.value?.status === 'FAILED')
const statusMeta = computed(() => EXEC_STATUS[exec.value?.status ?? ''] ?? { text: exec.value?.status ?? '', type: 'neutral' as const })
/** 下线只有「预检 → 删除对象」两步，其余阶段执行器直接跳过 */
const offline = computed(() => exec.value?.releaseType === 'OFFLINE')
const stages = computed(() => (offline.value ? OFFLINE_STAGE_ORDER : STAGE_ORDER))
const stageLabel = (s?: string | null): string | undefined =>
  s == null ? undefined : (offline.value && s === 'PRUNE' ? '删除对象' : EXEC_STAGE[s])
const verb = computed(() => (offline.value ? '下线' : '发布'))
const stageIndex = computed(() => stageStep(exec.value?.stage, stages.value))
const stepStatus = computed(() => (failed.value || interrupted.value ? 'error' : 'process'))
/** 只列有线上对象或本次相关的服务；NOT_RELEASED 的服务没有健康可言 */
const healthRows = computed(() => props.services.filter((s) => s.state !== 'NOT_RELEASED'))

async function resume(): Promise<void> {
  if (!exec.value) return
  acting.value = true
  error.value = ''
  try {
    await appReleaseApi.resume(exec.value.id, props.tenantId)
    ElMessage.success(interrupted.value ? '已继续' : '已重新执行')
    logTick.value++
    emit('changed')
  } catch (e) {
    error.value = errMsg(e, '操作失败')
    emit('changed')
  } finally {
    acting.value = false
  }
}
</script>

<template>
  <section v-if="exec" class="panel app-exec">
    <div class="app-exec__head">
      <h3>{{ verb }} #{{ exec.releaseNo }}</h3>
      <StatusBadge :label="statusMeta.text" :type="statusMeta.type" />
      <span v-if="exec.startedBy" class="app-exec__muted">发起人 {{ exec.startedBy }}</span>
      <span class="app-exec__spacer" />
      <template v-if="canWrite && (failed || interrupted)">
        <el-button type="primary" :loading="acting" @click="resume">{{ interrupted ? '继续' : '重新执行' }}</el-button>
        <el-button v-if="liveNo" :disabled="acting" @click="emit('rollback')">回滚到线上版本 #{{ liveNo }}</el-button>
      </template>
    </div>

    <el-steps :active="stageIndex" :process-status="stepStatus" finish-status="success" align-center class="app-exec__steps">
      <el-step v-for="s in stages" :key="s" :title="stageLabel(s)" />
    </el-steps>

    <el-alert
      v-if="failed"
      type="error"
      :closable="false"
      show-icon
      :title="`${verb}失败，停在「${stageLabel(exec.stage) ?? exec.stage ?? '未知阶段'}」`"
      :description="exec.message || undefined"
    >
      <div class="app-exec__hint">平台不会自动回滚。可以修复后重新执行（发布号不变，从失败阶段接着跑）、发布一个修复版本，或回滚。</div>
    </el-alert>
    <el-alert
      v-else-if="interrupted"
      type="warning"
      :closable="false"
      show-icon
      :title="`${verb}已中断`"
      :description="exec.message || undefined"
    >
      <div class="app-exec__hint">
        常见原因是平台服务重启或登录凭据失效。点「继续」会用你当前的登录身份接着跑；
        也可以直接发起新的发布，它会取代这次中断的执行。
      </div>
    </el-alert>
    <el-alert
      v-else-if="running"
      type="info"
      :closable="false"
      show-icon
      :title="`进行中：${stageLabel(exec.stage) ?? exec.stage ?? statusMeta.text}`"
      :description="exec.message || (offline ? '正在删除应用的集群对象（数据卷保留），期间不能发布。' : '发布进行中，期间不能再次发布。')"
    />
    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="app-exec__err" />

    <div class="app-exec__log-head">
      <el-button link type="primary" @click="showLog = !showLog">{{ showLog ? '收起过程日志' : '展开过程日志' }}</el-button>
      <span v-if="running" class="app-exec__muted">每 3 秒刷新；健康检查只在状态变化时追加一条</span>
    </div>
    <AppExecutionLog
      v-if="showLog"
      :app-id="appId"
      :release-no="exec.releaseNo"
      :tenant-id="tenantId"
      :refresh-key="logTick"
      :offline="offline"
    />

    <div v-if="healthRows.length" class="app-exec__health">
      <span v-for="s in healthRows" :key="s.key" class="app-exec__svc">
        <b>{{ s.key }}</b>
        <StatusBadge :label="STATE_META[s.state]?.text ?? s.state" :type="STATE_META[s.state]?.type ?? 'neutral'" />
      </span>
    </div>
  </section>
</template>

<style scoped>
.app-exec { padding: 14px 16px; margin-bottom: 16px; display: flex; flex-direction: column; gap: 12px; }
.app-exec__head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.app-exec__head h3 { margin: 0; font-size: 15px; }
.app-exec__spacer { flex: 1; }
.app-exec__muted { color: var(--text-3); font-size: 12px; }
.app-exec__hint { font-size: 12px; margin-top: 4px; }
.app-exec__log-head { display: flex; align-items: center; gap: 12px; margin-bottom: -6px; }
.app-exec__health { display: flex; flex-wrap: wrap; gap: 8px 20px; font-size: 13px; }
.app-exec__svc { display: inline-flex; align-items: center; gap: 8px; }
</style>
