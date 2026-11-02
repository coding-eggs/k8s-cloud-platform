<script setup lang="ts">
/**
 * 依赖详情抽屉：基本信息、使用方反查、初始化、移除。
 *
 * - 使用方：由服务期望配置里的 `${依赖.字段}` 反查（与后端移除校验同一口径之一；线上或进行中的发布里的引用
 *   只有后端知道，移除时以后端的拒绝原文为准）。
 * - 初始化：只在实例 READY 后可用，且只对支持初始化的中间件。脚本只是文本，不会被前端解析。
 *   成功过的依赖再次执行需要确认（后端要 confirm=true）；ES 必须给目标路径。
 *   状态为 RUNNING 时由页面级轮询刷新概览（只在 RUNNING / 实例创建中轮询）。
 *   日志是初始化 Job 的原始输出，页面上明确提示。
 * - 移除：后端在仍被引用时拒绝并点名服务，原文原样显示。CREATED 依赖让用户选保留实例（默认）或删除实例。
 * 口令在这里的任何位置都不会出现。
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { appDependencyApi, middlewareApi } from '@/api'
import StatusBadge from '@/components/StatusBadge.vue'
import type { AppDependencyRow, AppServiceRow, MiddlewareInstance } from '@/types'
import { INDEX_PATH_PATTERN, INIT_CODES, INIT_STATUS, depStatus, ownershipText, refToken, usersOf } from './dependencyUtil'
import { errMsg } from './releaseUtil'

const props = defineProps<{
  modelValue: boolean
  dep: AppDependencyRow | null
  services: AppServiceRow[]
  appId: string
  tenantId?: string
  canWrite: boolean
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 需要父组件重新拉概览（初始化状态变化 / 轮询） */
  (e: 'refresh'): void
  (e: 'removed', key: string): void
}>()

const router = useRouter()
const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const users = computed(() => (props.dep ? usersOf(props.services, props.dep.key) : []))
const refExample = computed(() => (props.dep ? refToken(props.dep.key, '字段') : ''))

// ---------------- 实例 ----------------
const instance = ref<MiddlewareInstance | null>(null)
async function loadInstance(): Promise<void> {
  instance.value = null
  const id = props.dep?.instanceId
  if (!id) return
  try {
    const got = await middlewareApi.instanceGet(id, props.tenantId)
    if (props.dep?.instanceId === id) instance.value = got
  } catch {
    // 实例读不到不影响依赖本身的展示；拦截器已提示
  }
}
function openInstance(): void {
  if (props.dep?.instanceId) void router.push(`/apps/${props.dep.instanceId}`)
}
function openService(key: string): void {
  visible.value = false
  void router.push(`/applications/${props.appId}/services/${encodeURIComponent(key)}`)
}

// ---------------- 初始化 ----------------
const code = computed(() => props.dep?.code ?? '')
const initSupported = computed(() => props.dep?.type === 'MIDDLEWARE' && INIT_CODES.includes(code.value))
const initPayload = ref('')
const initPath = ref('')
const initConfirm = ref(false)
const needConfirm = ref(false)
const initBusy = ref(false)
const initError = ref('')

const PAYLOAD_HINT: Record<string, string> = {
  mysql: 'SQL 脚本，用 root 账号执行。建议写成可重复执行的（IF NOT EXISTS）。',
  postgresql: 'SQL 脚本，用实例的管理账号执行。建议写成可重复执行的。',
  redis: '每行一条 Redis 命令，如 SET app:version 1。',
  elasticsearch: 'JSON 请求体，以 PUT 方式提交到下面的目标路径。',
  kafka: '每行一个 topic，格式 名称:分区数:副本数，如 orders:3:1。已存在的 topic 会跳过。',
}
const needsPath = computed(() => code.value === 'elasticsearch')
const pathProblem = computed(() => {
  if (!needsPath.value) return ''
  const p = initPath.value.trim()
  if (!p) return ''
  return INDEX_PATH_PATTERN.test(p) ? '' : '路径需以 / 开头，只能含字母、数字和 . _ ~ : , * + @ = % / -'
})
/** 成功过的依赖，再次执行要显式确认 */
const confirmRequired = computed(() => props.dep?.initStatus === 'SUCCESS' || needConfirm.value)
const instanceReady = computed(() => props.dep?.status === 'READY')
const running = computed(() => props.dep?.initStatus === 'RUNNING')
const canInit = computed(() => {
  if (!props.canWrite || !instanceReady.value || running.value || initBusy.value) return false
  if (!initPayload.value.trim()) return false
  if (needsPath.value && (!initPath.value.trim() || !!pathProblem.value)) return false
  return !confirmRequired.value || initConfirm.value
})

async function runInit(): Promise<void> {
  if (!props.dep || !canInit.value) return
  initBusy.value = true
  initError.value = ''
  try {
    await appDependencyApi.init({
      appId: props.appId,
      tenantId: props.tenantId,
      key: props.dep.key,
      initPayload: initPayload.value,
      ...(needsPath.value ? { initPath: initPath.value.trim() } : {}),
      ...(confirmRequired.value ? { confirm: true } : {}),
    })
    ElMessage.success('初始化已开始，完成后这里会显示结果')
    initConfirm.value = false
    needConfirm.value = false
    emit('refresh')
  } catch (e) {
    const msg = errMsg(e, '初始化失败')
    initError.value = msg
    // 后端认为需要确认（比如本页没看到的历史成功记录）：露出确认框，不替用户勾选
    if (msg.includes('confirm') || msg.includes('确认')) needConfirm.value = true
  } finally {
    initBusy.value = false
  }
}

// 初始化进行中的轮询在页面级（ApplicationDetailView）：抽屉关了表格也照样更新

// ---------------- 移除 ----------------
const removeVisible = ref(false)
const removing = ref(false)
const removeError = ref('')
const deleteInstance = ref(false)
const deleteAck = ref(false)

function openRemove(): void {
  deleteInstance.value = false
  deleteAck.value = false
  removeError.value = ''
  removeVisible.value = true
}
const canRemove = computed(() => !removing.value && (!deleteInstance.value || deleteAck.value))

async function submitRemove(): Promise<void> {
  if (!props.dep || !canRemove.value) return
  removing.value = true
  removeError.value = ''
  const key = props.dep.key
  try {
    await appDependencyApi.remove({
      appId: props.appId,
      tenantId: props.tenantId,
      key,
      ...(props.dep.ownership === 'CREATED' ? { deleteInstance: deleteInstance.value } : {}),
    })
    removeVisible.value = false
    visible.value = false
    ElMessage.success(deleteInstance.value ? '依赖已移除，实例正在删除' : '依赖已移除')
    emit('removed', key)
  } catch (e) {
    // 仍被引用时后端会点名服务：原文原样显示
    removeError.value = errMsg(e, '移除失败')
  } finally {
    removing.value = false
  }
}

watch(
  () => [visible.value, props.dep?.key] as const,
  ([open]) => {
    if (!open) return
    initPayload.value = ''
    initPath.value = ''
    initConfirm.value = false
    needConfirm.value = false
    initError.value = ''
    void loadInstance()
  },
  { immediate: true },
)
</script>

<template>
  <el-drawer v-model="visible" :title="dep ? `依赖 ${dep.key}` : '依赖'" size="560px" class="app-dep-drawer" destroy-on-close>
    <template v-if="dep">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="来源">{{ ownershipText(dep) }}</el-descriptions-item>
        <el-descriptions-item label="状态"><StatusBadge :label="depStatus(dep).text" :type="depStatus(dep).type" /></el-descriptions-item>
        <el-descriptions-item v-if="dep.type === 'MIDDLEWARE'" label="中间件实例">
          <el-link v-if="dep.instanceId" type="primary" @click="openInstance">
            {{ instance ? `${instance.displayName || instance.name}（${instance.code}）` : dep.instanceId }}
          </el-link>
          <span v-else class="app-dep-drawer__muted">还没有关联的实例</span>
        </el-descriptions-item>
        <template v-if="dep.type === 'EXTERNAL' && dep.external">
          <el-descriptions-item label="协议">{{ dep.external.protocol || '—' }}</el-descriptions-item>
          <el-descriptions-item label="地址">{{ dep.external.host }}{{ dep.external.port ? `:${dep.external.port}` : '' }}</el-descriptions-item>
          <el-descriptions-item label="Secret">{{ dep.external.secretName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="用户名键">{{ dep.external.usernameKey || '—' }}</el-descriptions-item>
          <el-descriptions-item label="密码键">{{ dep.external.passwordKey || '—' }}</el-descriptions-item>
        </template>
      </el-descriptions>

      <h4 class="app-dep-drawer__h">使用方</h4>
      <div v-if="users.length" class="app-dep-drawer__users">
        <el-link v-for="u in users" :key="u" type="primary" @click="openService(u)">{{ u }}</el-link>
      </div>
      <div v-else class="app-dep-drawer__muted">暂无服务引用它。</div>
      <div class="app-dep-drawer__tip">
        使用方来自服务期望配置里的 <code>{{ refExample }}</code> 引用；线上或进行中发布里的引用，移除时由后端校验。
      </div>

      <template v-if="initSupported">
        <h4 class="app-dep-drawer__h">初始化脚本</h4>
        <div class="app-dep-drawer__tip">不随发布执行，手动触发；表结构变更仍由业务自己的迁移工具负责。</div>
        <div v-if="dep.initStatus" class="app-dep-drawer__status">
          最近一次：
          <StatusBadge :label="INIT_STATUS[dep.initStatus]?.text ?? dep.initStatus" :type="INIT_STATUS[dep.initStatus]?.type ?? 'neutral'" />
        </div>
        <template v-if="dep.initLog">
          <el-alert type="warning" :closable="false" show-icon class="app-dep-drawer__warn"
            title="下面是初始化 Job 的原始输出，可能包含执行的语句或数据库返回的内容；脚本里不要写明文口令。" />
          <pre class="app-dep-drawer__log">{{ dep.initLog }}</pre>
        </template>

        <el-alert v-if="!instanceReady" type="info" :closable="false" show-icon class="app-dep-drawer__warn"
          title="实例就绪（运行正常）后才能初始化" />
        <el-form label-position="top" class="app-dep-drawer__form" :disabled="!canWrite || !instanceReady || running" @submit.prevent>
          <el-form-item v-if="needsPath" label="目标路径" required>
            <el-input v-model="initPath" spellcheck="false" placeholder="如 /my-index 或 /_index_template/my-tpl" />
            <div v-if="pathProblem" class="app-dep-drawer__err">{{ pathProblem }}</div>
          </el-form-item>
          <el-form-item label="脚本内容" required>
            <el-input v-model="initPayload" type="textarea" :rows="8" spellcheck="false" placeholder="脚本内容" />
            <div class="app-dep-drawer__tip">{{ PAYLOAD_HINT[code] }}</div>
          </el-form-item>
          <el-form-item v-if="confirmRequired">
            <el-checkbox v-model="initConfirm">这个依赖已经成功初始化过，我确认要再次执行</el-checkbox>
          </el-form-item>
        </el-form>
        <el-alert v-if="initError" type="error" :closable="false" show-icon :title="initError" class="app-dep-drawer__warn" />
        <el-button type="primary" :loading="initBusy || running" :disabled="!canInit" :title="canWrite ? '' : '没有应用写权限'" @click="runInit">
          {{ running ? '执行中…' : dep.initStatus ? '再次执行' : '执行初始化' }}
        </el-button>
      </template>
    </template>

    <template #footer>
      <el-button type="danger" plain :disabled="!canWrite || running || !dep" :title="!canWrite ? '没有应用写权限' : running ? '初始化进行中，结束后才能移除' : ''" @click="openRemove">移除依赖</el-button>
    </template>

  </el-drawer>

  <el-dialog v-model="removeVisible" :title="`移除依赖 ${dep?.key ?? ''}`" width="520px" class="app-dep-rm" :close-on-click-modal="false">
    <template v-if="dep?.ownership === 'CREATED'">
      <p class="app-dep-rm__p">这个依赖的实例是随应用创建的，移除时请选择实例的去向：</p>
      <el-radio-group v-model="deleteInstance" class="app-dep-rm__radios">
        <el-radio :value="false">保留实例（默认）：转交中间件模块继续管理，数据不受影响</el-radio>
        <el-radio :value="true">同时删除实例：实例及其中的数据会被销毁，无法恢复</el-radio>
      </el-radio-group>
      <el-alert v-if="deleteInstance" type="error" :closable="false" show-icon title="删除实例会销毁其中的全部数据，此操作不可恢复。" class="app-dep-rm__warn" />
      <el-checkbox v-if="deleteInstance" v-model="deleteAck" class="app-dep-rm__ack">我确认删除实例及其中的数据</el-checkbox>
    </template>
    <p v-else-if="dep?.type === 'MIDDLEWARE'" class="app-dep-rm__p">只解除应用与该实例的引用，实例本身不受影响。</p>
    <p v-else class="app-dep-rm__p">只删除这条外部连接的登记信息，不影响外部服务。</p>
    <el-alert v-if="users.length" type="warning" :closable="false" show-icon class="app-dep-rm__warn"
      :title="`服务 ${users.join('、')} 的配置里还引用着它，需要先去掉引用并发布。`" />
    <el-alert v-if="removeError" type="error" :closable="false" show-icon :title="removeError" class="app-dep-rm__warn" />
    <template #footer>
      <el-button @click="removeVisible = false">取消</el-button>
      <el-button type="danger" :loading="removing" :disabled="!canRemove" @click="submitRemove">移除</el-button>
    </template>
  </el-dialog>
</template>

<style>
/* el-drawer / el-dialog 内部节点没有 data-v-*，scoped 打不到；唯一前缀 app-dep-drawer / app-dep-rm */
.app-dep-drawer .app-dep-drawer__h { margin: 20px 0 8px; font-size: 14px; }
.app-dep-drawer .app-dep-drawer__muted { color: var(--el-text-color-secondary); font-size: 13px; }
.app-dep-drawer .app-dep-drawer__tip { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; margin-top: 4px; }
.app-dep-drawer .app-dep-drawer__err { color: var(--el-color-danger); font-size: 12px; }
.app-dep-drawer .app-dep-drawer__users { display: flex; flex-wrap: wrap; gap: 12px; }
.app-dep-drawer .app-dep-drawer__status { margin: 8px 0; font-size: 13px; }
.app-dep-drawer .app-dep-drawer__warn { margin: 8px 0; }
.app-dep-drawer .app-dep-drawer__form { margin-top: 8px; }
.app-dep-drawer .app-dep-drawer__log {
  margin: 0 0 8px;
  padding: 10px 12px;
  max-height: 260px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.app-dep-rm .app-dep-rm__p { margin: 0 0 12px; font-size: 13px; line-height: 1.6; }
.app-dep-rm .app-dep-rm__radios { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.app-dep-rm .app-dep-rm__radios .el-radio { height: auto; white-space: normal; line-height: 1.5; }
.app-dep-rm .app-dep-rm__warn { margin-top: 12px; }
.app-dep-rm .app-dep-rm__ack { margin-top: 8px; }
</style>
