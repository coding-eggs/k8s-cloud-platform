<script setup lang="ts">
/**
 * 服务详情（设计 §8）：概览 / 配置 / Pod 与日志 / 监控 四个页签。
 *
 * 配置页签改的是「期望配置」，不碰线上；保存带读到的 version（乐观锁）。
 * 冲突时不丢用户输入：提示条给两条路 —— 载入最新（丢弃我的修改）/ 保留我的修改并取最新版本。
 * Pod / 日志 / 监控复用资源域的 PodLogDialog、WorkloadLogsDialog、MetricsPanel，
 * 工作负载名按台账约定 `<slug>-<key>`。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { appServiceApi, applicationApi, podApi, workloadApi } from '@/api'
import { useApplicationContext } from '@/composables/useApplicationContext'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { podLabelSelector } from '@/utils/podSelector'
import { fmtAge, fmtDate, podPhaseType } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import EmptyState from '@/components/EmptyState.vue'
import PodLogDialog from '@/components/PodLogDialog.vue'
import WorkloadLogsDialog from '@/components/WorkloadLogsDialog.vue'
import MetricsPanel from '@/components/workload/MetricsPanel.vue'
import AppEnvEditor from '@/components/app-release/AppEnvEditor.vue'
import StringListEditor from '@/components/app-release/StringListEditor.vue'
import { useResourceContext } from '@/stores/context'
import type { AppServiceDesired, AppServiceRow, AppServiceState, ApplicationView, K8sPod } from '@/types'
import type { WorkloadDetail } from '@/types/workload'

const route = useRoute()
const router = useRouter()
const { tenantId, ensure } = useApplicationContext()
const { setNamespace } = useResourceContext()
const perm = usePermission()
const canWrite = computed(() => perm.has(apiCodes.applicationWrite))

const appId = computed(() => String(route.params.id ?? ''))
const key = computed(() => String(route.params.key ?? ''))

const app = ref<ApplicationView | null>(null)
const row = ref<AppServiceRow | null>(null)
const pendingKeys = ref<string[]>([])
const allRows = ref<AppServiceRow[]>([])
const depKeys = ref<string[]>([])
const loading = ref(false)
const notFound = ref(false)

const STATE_META: Record<AppServiceState, { text: string; type: 'success' | 'warning' | 'danger' | 'info' | 'neutral' }> = {
  READY: { text: '运行正常', type: 'success' },
  PENDING: { text: '启动中', type: 'warning' },
  FAILED: { text: '异常', type: 'danger' },
  MISSING: { text: '线上缺失', type: 'danger' },
  UNKNOWN: { text: '状态未知', type: 'neutral' },
  NOT_RELEASED: { text: '未发布', type: 'info' },
}
const PENDING_TEXT: Record<string, string> = { ADDED: '新增，待发布', CHANGED: '有待发布改动', DELETED: '待删除' }

/** 孤儿服务（线上有、台账无行）：version 为 null，配置只读 */
const orphan = computed(() => !!row.value && row.value.version == null)
/** 待删除的服务后端拒绝修改；没有写权限同样只读 */
const pendingDelete = computed(() => row.value?.pending === 'DELETED')
const readonly = computed(() => orphan.value || pendingDelete.value || !canWrite.value)
const workloadName = computed(() => (app.value ? `${app.value.slug}-${key.value}` : ''))

// ---------- 表单 ----------
const form = ref<AppServiceDesired>({ imageRepo: '', imageTag: '' })
const baseline = ref('')
const version = ref<number | null>(null)

function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T
}
function snapshotOf(): string {
  return JSON.stringify(buildDesired())
}
function fillForm(d: AppServiceDesired): void {
  const c = clone(d)
  c.env = (c.env ?? []).map((e) => ({ ...e }))
  form.value = c
  baseline.value = snapshotOf()
}
const dirty = computed(() => !!row.value && snapshotOf() !== baseline.value)

/** 组装要提交的 desired：未识别的字段原样保留（后端白名单，不认的会拒绝，所以只带读到的）；空串不发 */
function buildDesired(): AppServiceDesired {
  const d: AppServiceDesired = clone(form.value)
  d.imageRepo = (d.imageRepo ?? '').trim()
  d.imageTag = (d.imageTag ?? '').trim()
  for (const k of Object.keys(d)) {
    const v = d[k]
    if (v === '' || v == null || (Array.isArray(v) && v.length === 0 && k !== 'env')) delete d[k]
  }
  d.env = (d.env ?? []).filter((e) => e.name || e.value || e.secretName).map((e) => {
    const o = { ...e }
    if (o.kind !== 'SECRET') { delete o.secretName; delete o.secretKey } else { delete o.value }
    return o
  })
  return d
}

async function load(opts: { keepForm?: boolean } = {}): Promise<void> {
  if (!appId.value || !key.value) return
  loading.value = true
  notFound.value = false
  const target = key.value
  try {
    const [a, o] = await Promise.all([
      applicationApi.get(appId.value, tenantId.value),
      appServiceApi.overview(appId.value, tenantId.value),
    ])
    if (target !== key.value) return
    app.value = a
    allRows.value = o.services
    pendingKeys.value = o.pendingKeys
    depKeys.value = o.dependencies.map((d) => d.key)
    const r = o.services.find((s) => s.key === target) ?? null
    row.value = r
    if (!r) { notFound.value = true; return }
    if (opts.keepForm) {
      version.value = r.version
    } else {
      version.value = r.version
      fillForm(r.desired)
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

// ---------- 保存 / 冲突 ----------
const saving = ref(false)
const conflict = ref(false)
const saveError = ref('')

async function save(): Promise<void> {
  if (version.value == null || !row.value) return
  saving.value = true
  saveError.value = ''
  try {
    const out = await appServiceApi.update({
      appId: appId.value,
      serviceKey: key.value,
      desired: buildDesired(),
      version: version.value,
      tenantId: tenantId.value,
    })
    conflict.value = false
    ElMessage.success('已保存，发布后生效')
    fillForm(out.desired)
    version.value = out.version
    await load({ keepForm: true })
    baseline.value = snapshotOf()
  } catch (e) {
    const msg = e instanceof Error ? e.message : '保存失败'
    saveError.value = msg
    // 用户输入原样保留在表单里；冲突另给明确的两条路
    conflict.value = msg.includes('冲突')
  } finally {
    saving.value = false
  }
}
/** 他人改过：丢弃我的修改，载入最新 */
async function discardAndReload(): Promise<void> {
  conflict.value = false
  saveError.value = ''
  await load()
}
/** 他人改过：保留我的修改，只取最新版本号，再点保存即覆盖对方 */
async function keepMineWithLatest(): Promise<void> {
  conflict.value = false
  saveError.value = ''
  await load({ keepForm: true })
  ElMessage.info('已取得最新版本，你的修改仍在表单中；再次保存将覆盖对方的改动')
}
function resetForm(): void {
  if (row.value) fillForm(row.value.desired)
  conflict.value = false
  saveError.value = ''
}

// ---------- 导航 / 移除 ----------
function back(): void {
  router.push(`/applications/${appId.value}`)
}
async function remove(): Promise<void> {
  const r = row.value
  if (!r || r.version == null) return
  const published = r.state !== 'NOT_RELEASED'
  try {
    await ElMessageBox.confirm(
      published ? `移除服务「${r.key}」？标记待删除，下次发布时勾选后才从集群移除。` : `移除服务「${r.key}」？尚未发布，直接删除配置。`,
      '移除服务', { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await appServiceApi.remove({ appId: appId.value, serviceKey: r.key, version: r.version, tenantId: tenantId.value })
    // 接口不返回走了哪条路径（直接删行 / 标记待删除），文案对两种都成立
    ElMessage.success('已移除；若该服务已发布过，需在下次发布时勾选才会从集群删除')
    back()
  } catch {
    // 失败（多半是冲突）不能清掉表单里没保存的输入
    await load({ keepForm: true })
  }
}

// ---------- Pod 与日志 / 监控 ----------
const tab = ref('overview')
const cfgTab = ref('basic')
const wl = ref<WorkloadDetail | null>(null)
const pods = ref<K8sPod[]>([])
const podLoading = ref(false)
const podError = ref(false)
const ctx = computed(() => ({
  tenantId: tenantId.value ?? '',
  clusterId: app.value?.clusterId ?? '',
  namespace: app.value?.namespace ?? '',
}))
const runtimeReady = computed(() => !!row.value && row.value.state !== 'NOT_RELEASED' && !!app.value)

async function loadPods(): Promise<void> {
  if (!runtimeReady.value) return
  podLoading.value = true
  podError.value = false
  try {
    const d = await workloadApi.get(workloadName.value, ctx.value)
    wl.value = d
    pods.value = (await podApi.list({ ...ctx.value, labelSelector: podLabelSelector(d.selector, workloadName.value) })) ?? []
  } catch {
    podError.value = true
  } finally {
    podLoading.value = false
  }
}
watch([tab, runtimeReady], ([t, ok]) => { if ((t === 'pods' || t === 'monitor') && ok && !wl.value) void loadPods() })

const logVisible = ref(false)
const logPod = ref<K8sPod | null>(null)
const allLogsVisible = ref(false)
function openLogs(p: K8sPod): void { logPod.value = p; logVisible.value = true }
function readyCount(p: K8sPod): string {
  const cs = (p.containerDetails ?? []).filter((c) => !c.init)
  return `${cs.filter((c) => c.ready).length}/${cs.length}`
}
function openWorkloadPage(): void {
  if (!app.value) return
  // 资源页按顶栏命名空间取数：先把上下文对到应用所在的命名空间
  setNamespace(app.value.namespace)
  router.push(`/resources/workloads/detail?name=${encodeURIComponent(workloadName.value)}`)
}

const selfRefs = computed(() => allRows.value.filter((s) => s.kind === 'SERVICE' && s.key !== key.value).map((s) => s.key))

onMounted(async () => {
  await ensure()
  await load()
})
watch(key, () => { wl.value = null; pods.value = []; tab.value = 'overview'; void load() })
</script>

<template>
  <div class="app-svc" v-loading="loading && !row">
    <PageHeader :title="`服务 ${key}`" :description="app ? `${app.displayName || app.name} · ${app.namespace}` : undefined">
      <el-button @click="back">返回应用</el-button>
      <el-button type="danger" plain :disabled="!row || readonly" @click="remove">移除服务</el-button>
    </PageHeader>

    <EmptyState v-if="notFound" title="服务不存在" description="它可能已被移除，或应用中没有这个名称。">
      <el-button @click="back">返回应用</el-button>
    </EmptyState>

    <el-tabs v-else-if="row && app" v-model="tab" class="app-svc__tabs">
      <!-- 概览 -->
      <el-tab-pane label="概览" name="overview">
        <section class="panel app-svc__card">
          <div class="app-svc__kv"><span class="k">状态</span>
            <StatusBadge :label="STATE_META[row.state]?.text ?? row.state" :type="STATE_META[row.state]?.type ?? 'neutral'" />
          </div>
          <div class="app-svc__kv"><span class="k">类型</span>{{ row.kind === 'SERVICE' ? '服务（有端口）' : '后台进程（无端口）' }}</div>
          <div class="app-svc__kv"><span class="k">期望镜像</span><code>{{ row.image }}</code></div>
          <div class="app-svc__kv"><span class="k">线上镜像</span><code v-if="row.liveImage">{{ row.liveImage }}</code><span v-else class="muted">未发布</span></div>
          <div class="app-svc__kv"><span class="k">副本数</span>{{ row.replicas ?? '—' }}</div>
          <div class="app-svc__kv"><span class="k">待发布</span>
            <el-tag v-if="row.pending" type="warning" effect="light" size="small">{{ PENDING_TEXT[row.pending] }}</el-tag>
            <span v-else class="muted">无</span>
          </div>
          <div v-if="row.kind === 'SERVICE'" class="app-svc__kv"><span class="k">集群内地址</span>
            <code>{{ workloadName }}:{{ row.desired.port }}</code>
          </div>
          <div class="app-svc__kv"><span class="k">工作负载</span>
            <code>{{ workloadName }}</code>
            <el-button v-if="runtimeReady" link type="primary" @click="openWorkloadPage">查看工作负载详情</el-button>
          </div>
          <el-alert v-if="orphan" class="app-svc__alert" type="warning" :closable="false" show-icon
                    title="线上有这个服务，但应用里已没有它的配置记录。配置不可编辑，可在发布时勾选删除。" />
        </section>
      </el-tab-pane>

      <!-- 配置 -->
      <el-tab-pane label="配置" name="config">
        <el-alert v-if="orphan" class="app-svc__alert" type="warning" :closable="false" show-icon
                  title="孤儿服务：配置来自线上快照，只读，不能保存。" />
        <el-alert v-else-if="pendingDelete" class="app-svc__alert" type="warning" :closable="false" show-icon
                  title="该服务已标记待删除，配置只读；下次发布勾选后会从集群移除。" />
        <el-alert v-else-if="!canWrite" class="app-svc__alert" type="info" :closable="false" show-icon
                  title="你没有应用写权限，配置只读。" />
        <el-alert v-if="conflict" class="app-svc__alert" type="error" :closable="false" show-icon
                  title="配置冲突：服务已被他人修改">
          <div>你的修改还在表单里，没有丢。可以载入最新版本（放弃你的修改），或保留你的修改并取得最新版本号后再次保存（会覆盖对方的改动）。</div>
          <div class="app-svc__conflict-actions">
            <el-button size="small" @click="discardAndReload">载入最新，放弃我的修改</el-button>
            <el-button size="small" type="primary" @click="keepMineWithLatest">保留我的修改，取最新版本</el-button>
          </div>
        </el-alert>
        <el-alert v-else-if="saveError" class="app-svc__alert" type="error" :closable="false" show-icon :title="saveError" />

        <el-tabs v-model="cfgTab" type="card" class="app-svc__cfg">
          <el-tab-pane label="基本" name="basic">
            <el-form label-width="110px" :disabled="readonly" @submit.prevent>
              <el-form-item label="镜像仓库" required><el-input v-model="form.imageRepo" placeholder="不含 tag" /></el-form-item>
              <el-form-item label="镜像 tag" required><el-input v-model="form.imageTag" /></el-form-item>
              <el-form-item label="端口">
                <el-input-number v-model="form.port" :min="1" :max="65535" :controls="false" placeholder="留空" />
                <span class="app-svc__tip">填了端口就是服务，清空则变为后台进程</span>
              </el-form-item>
              <el-form-item label="副本数"><el-input-number v-model="form.replicas" :min="0" :max="100" /></el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="环境变量" name="env">
            <AppEnvEditor v-model="form.env" :dependency-keys="depKeys" :service-keys="selfRefs" :class="{ 'is-disabled': readonly }" />
          </el-tab-pane>

          <el-tab-pane label="资源与命令" name="resources">
            <el-form label-width="110px" :disabled="readonly" @submit.prevent>
              <el-form-item label="CPU 请求"><el-input v-model="form.cpuRequest" placeholder="如 100m" /></el-form-item>
              <el-form-item label="CPU 上限"><el-input v-model="form.cpuLimit" placeholder="如 1" /></el-form-item>
              <el-form-item label="内存请求"><el-input v-model="form.memoryRequest" placeholder="如 128Mi" /></el-form-item>
              <el-form-item label="内存上限"><el-input v-model="form.memoryLimit" placeholder="如 512Mi" /></el-form-item>
              <el-form-item label="启动命令"><StringListEditor v-model="form.command" :disabled="readonly" placeholder="一个参数，如 sh；留空则用镜像默认" /></el-form-item>
              <el-form-item label="启动参数"><StringListEditor v-model="form.args" :disabled="readonly" placeholder="一个参数，如 -c" /></el-form-item>
              <el-form-item label="拉取策略">
                <el-select v-model="form.imagePullPolicy" clearable placeholder="跟随默认" style="width: 200px">
                  <el-option label="Always" value="Always" />
                  <el-option label="IfNotPresent" value="IfNotPresent" />
                  <el-option label="Never" value="Never" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="高级" name="advanced">
            <el-form label-width="110px" :disabled="readonly" @submit.prevent>
              <el-form-item v-if="form.port" label="持久化"><el-switch v-model="form.persistenceEnabled" /></el-form-item>
              <template v-if="form.port && form.persistenceEnabled">
                <el-form-item label="存储大小"><el-input v-model="form.storageSize" placeholder="如 10Gi" /></el-form-item>
                <el-form-item label="存储类"><el-input v-model="form.storageClass" placeholder="留空使用默认" /></el-form-item>
                <el-form-item label="挂载路径"><el-input v-model="form.mountPath" placeholder="如 /data" /></el-form-item>
              </template>
              <el-form-item label="自动扩缩容"><el-switch v-model="form.autoscalingEnabled" /></el-form-item>
              <template v-if="form.autoscalingEnabled">
                <el-form-item label="最小副本"><el-input-number v-model="form.minReplicas" :min="1" /></el-form-item>
                <el-form-item label="最大副本"><el-input-number v-model="form.maxReplicas" :min="1" /></el-form-item>
                <el-form-item label="目标 CPU %"><el-input-number v-model="form.targetCpu" :min="1" :max="100" /></el-form-item>
              </template>
              <!-- 后端缺省=开启（!FALSE.equals）：没写 probeEnabled 就是开；切换时一律写显式 true/false -->
              <el-form-item label="健康探针">
                <el-switch :model-value="form.probeEnabled !== false" @update:model-value="(v: string | number | boolean) => (form.probeEnabled = v === true)" />
              </el-form-item>
              <el-form-item v-if="form.probeEnabled !== false" label="探针路径"><el-input v-model="form.probePath" placeholder="默认 /，如 /healthz" /></el-form-item>
              <el-form-item label="暴露指标"><el-switch v-model="form.metricsEnabled" /></el-form-item>
              <el-form-item v-if="form.metricsEnabled" label="指标路径"><el-input v-model="form.metricsPath" placeholder="如 /metrics" /></el-form-item>
            </el-form>
          </el-tab-pane>
        </el-tabs>

        <div class="app-svc__save">
          <el-button type="primary" :loading="saving" :disabled="readonly || !dirty" @click="save">保存</el-button>
          <el-button :disabled="readonly || !dirty" @click="resetForm">还原</el-button>
          <span class="muted">保存只改期望配置，发布后才会生效到集群。</span>
        </div>
      </el-tab-pane>

      <!-- Pod 与日志 -->
      <el-tab-pane label="Pod 与日志" name="pods" lazy>
        <EmptyState v-if="!runtimeReady" title="尚未发布" description="服务发布后，这里显示它的 Pod 和日志。" />
        <EmptyState v-else-if="podError" title="读取失败" description="工作负载读取失败，可能线上还没有创建完成。">
          <el-button @click="loadPods">重试</el-button>
        </EmptyState>
        <template v-else>
          <div class="app-svc__bar">
            <el-button :loading="podLoading" @click="loadPods">刷新</el-button>
            <el-button :disabled="!pods.length" @click="allLogsVisible = true">聚合日志</el-button>
          </div>
          <el-table v-loading="podLoading" :data="pods" size="small" empty-text="当前没有 Pod">
            <el-table-column label="名称" min-width="260"><template #default="{ row: p }"><code>{{ p.name }}</code></template></el-table-column>
            <el-table-column label="就绪" width="80"><template #default="{ row: p }">{{ readyCount(p) }}</template></el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row: p }"><StatusBadgeTip :label="p.phase ?? 'Unknown'" :type="podPhaseType(p.phase)" :reason="p.statusReason" /></template>
            </el-table-column>
            <el-table-column label="重启" width="70"><template #default="{ row: p }">{{ p.restarts ?? 0 }}</template></el-table-column>
            <el-table-column label="节点" min-width="120"><template #default="{ row: p }">{{ p.nodeName ?? '—' }}</template></el-table-column>
            <el-table-column label="存活" width="160">
              <template #default="{ row: p }">{{ fmtDate(p.creationTime) }}（{{ fmtAge(p.creationTime) }}）</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row: p }"><el-button link type="primary" @click="openLogs(p)">日志</el-button></template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <!-- 监控 -->
      <el-tab-pane label="监控" name="monitor" lazy>
        <EmptyState v-if="!runtimeReady" title="尚未发布" description="服务发布后才有监控数据。" />
        <MetricsPanel
          v-else-if="wl"
          dimension="workload"
          :name="wl.name"
          :kind="wl.kind"
          :tenant-id="ctx.tenantId"
          :cluster-id="ctx.clusterId"
          :namespace="ctx.namespace"
        />
        <EmptyState v-else-if="podError" title="读取失败" description="工作负载读取失败，无法取得监控。" />
        <div v-else class="muted">加载中…</div>
      </el-tab-pane>
    </el-tabs>

    <PodLogDialog v-if="app" v-model="logVisible" :pod="logPod" :tenant-id="ctx.tenantId" :cluster-id="ctx.clusterId" :namespace="ctx.namespace" />
    <WorkloadLogsDialog v-if="app" v-model="allLogsVisible" :pods="pods" :tenant-id="ctx.tenantId" :cluster-id="ctx.clusterId" :namespace="ctx.namespace" />
  </div>
</template>

<style scoped>
.muted { color: var(--text-3); font-size: 12.5px; }
.app-svc__tabs { margin: 0 10px; }
.app-svc__card { padding: 16px 18px; }
.app-svc__kv { display: flex; align-items: center; gap: 12px; padding: 6px 0; font-size: 13.5px; }
.app-svc__kv .k { width: 90px; flex-shrink: 0; color: var(--text-3); }
.app-svc__alert { margin-bottom: 12px; }
.app-svc__conflict-actions { margin-top: 8px; }
.app-svc__cfg { margin-top: 4px; }
.app-svc__tip { margin-left: 10px; color: var(--text-3); font-size: 12px; }
.app-svc__save { display: flex; align-items: center; gap: 10px; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.app-svc__bar { display: flex; gap: 8px; margin-bottom: 10px; }
.is-disabled { pointer-events: none; opacity: 0.6; }
</style>
