<script setup lang="ts">
/**
 * 应用页（设计 §8）：服务与依赖一览，待发布改动，发布入口。
 *
 * 数据只有一个来源：`/application/overview`（服务现读状态 + 期望配置 + 乐观锁版本）。
 * 待发布改动由后端比较「期望配置 vs 线上快照」得出，这里不另存草稿。
 * 发布对话框 / 发布记录属于后续任务，入口先留位（见 openRelease）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { appServiceApi, applicationApi } from '@/api'
import { useApplicationContext } from '@/composables/useApplicationContext'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { AppOverview, AppServiceRow, AppServiceState, ApplicationView } from '@/types'

const route = useRoute()
const router = useRouter()
const { tenantId, ensure } = useApplicationContext()

const appId = computed(() => String(route.params.id ?? ''))
const app = ref<ApplicationView | null>(null)
const overview = ref<AppOverview | null>(null)
const loading = ref(false)
const loadFailed = ref(false)

const STATE_META: Record<AppServiceState, { text: string; type: 'success' | 'warning' | 'danger' | 'info' | 'neutral' }> = {
  READY: { text: '运行正常', type: 'success' },
  PENDING: { text: '启动中', type: 'warning' },
  FAILED: { text: '异常', type: 'danger' },
  MISSING: { text: '线上缺失', type: 'danger' },
  UNKNOWN: { text: '状态未知', type: 'neutral' },
  NOT_RELEASED: { text: '未发布', type: 'info' },
}
const PENDING_META: Record<string, { text: string; type: 'success' | 'warning' | 'danger' }> = {
  ADDED: { text: '新增，待发布', type: 'success' },
  CHANGED: { text: '有待发布改动', type: 'warning' },
  DELETED: { text: '待删除', type: 'danger' },
}
const APP_STATUS: Record<string, { text: string; type: 'success' | 'warning' | 'danger' | 'info' | 'neutral' }> = {
  DRAFT: { text: '未发布', type: 'info' },
  DEPLOYING: { text: '发布中', type: 'warning' },
  READY: { text: '运行正常', type: 'success' },
  FAILED: { text: '发布失败', type: 'danger' },
  INTERRUPTED: { text: '已中断', type: 'danger' },
}
const EXEC_STAGE: Record<string, string> = {
  PRECHECK: '预检', RENDER: '渲染', CONFLICT: '冲突检查', APPLY: '下发', HEALTH: '健康检查', PRUNE: '清理旧对象',
}

const pendingCount = computed(() => overview.value?.pendingKeys.length ?? 0)
const live = computed(() => overview.value?.liveReleaseNo ?? app.value?.liveReleaseNo ?? 0)
const statusMeta = computed(() => APP_STATUS[overview.value?.status ?? app.value?.status ?? ''] ?? { text: '未知', type: 'neutral' as const })
const active = computed(() => overview.value?.activeExecution ?? null)
const lastFailed = computed(() => {
  const e = overview.value?.lastExecution
  return e && !overview.value?.activeExecution && (e.status === 'FAILED' || e.status === 'INTERRUPTED') ? e : null
})

async function load(silent = false): Promise<void> {
  if (!appId.value) return
  if (!silent) loading.value = true
  loadFailed.value = false
  const target = appId.value
  try {
    const [a, o] = await Promise.all([
      applicationApi.get(target, tenantId.value),
      appServiceApi.overview(target, tenantId.value),
    ])
    if (target !== appId.value) return
    app.value = a
    overview.value = o
  } catch {
    // 拦截器已提示；静默轮询失败不覆盖已有数据
    if (!silent && target === appId.value) loadFailed.value = true
  } finally {
    loading.value = false
  }
}

// 有活动执行时每 5s 刷新一次进度，结束后停
let timer: number | null = null
function stopPoll(): void {
  if (timer != null) { window.clearInterval(timer); timer = null }
}
watch(active, (e) => {
  stopPoll()
  if (e) timer = window.setInterval(() => { void load(true) }, 5000)
}, { immediate: true })
onBeforeUnmount(stopPoll)

function goList(): void {
  router.push('/applications')
}
function openService(row: AppServiceRow): void {
  router.push(`/applications/${appId.value}/services/${encodeURIComponent(row.key)}`)
}

/** 发布对话框由后续任务提供：此处只保留入口，避免页面空转 */
function openRelease(): void {
  ElMessage.info('发布对话框将在后续版本提供')
}

// ---------- 添加服务 ----------
const addVisible = ref(false)
const adding = ref(false)
const addError = ref('')
const addForm = ref({ key: '', imageRepo: '', imageTag: '', port: undefined as number | undefined, replicas: 1 })

const keyHint = computed(() => {
  const k = addForm.value.key
  if (!k) return ''
  return /^[a-z]([a-z0-9-]{0,18}[a-z0-9])?$/.test(k) ? '' : '2-20 位小写字母、数字、连字符，字母开头，不以连字符结尾'
})
const canAdd = computed(() => !!addForm.value.key && !keyHint.value && !!addForm.value.imageRepo.trim() && !!addForm.value.imageTag.trim())

function openAdd(): void {
  addForm.value = { key: '', imageRepo: '', imageTag: '', port: undefined, replicas: 1 }
  addError.value = ''
  addVisible.value = true
}
async function submitAdd(): Promise<void> {
  if (!canAdd.value) return
  adding.value = true
  addError.value = ''
  const f = addForm.value
  try {
    await appServiceApi.create({
      appId: appId.value,
      serviceKey: f.key,
      desired: {
        imageRepo: f.imageRepo.trim(),
        imageTag: f.imageTag.trim(),
        ...(f.port ? { port: f.port } : {}),
        replicas: f.replicas,
        env: [],
      },
      tenantId: tenantId.value,
    })
    addVisible.value = false
    ElMessage.success('服务已添加，发布后生效')
    await load(true)
  } catch (e) {
    // 拦截器已弹 toast；对话框内再留一份，避免提示一闪而过
    addError.value = e instanceof Error ? e.message : '添加失败'
  } finally {
    adding.value = false
  }
}

async function removeService(row: AppServiceRow): Promise<void> {
  if (row.version == null) {
    ElMessage.warning('该服务线上存在但配置记录已缺失，请在发布时勾选删除')
    return
  }
  const published = row.state !== 'NOT_RELEASED'
  try {
    await ElMessageBox.confirm(
      published
        ? `移除服务「${row.key}」？它会被标记为待删除，下次发布时勾选后才真正从集群移除。`
        : `移除服务「${row.key}」？它还没有发布过，会直接删除配置。`,
      '移除服务',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await appServiceApi.remove({ appId: appId.value, serviceKey: row.key, version: row.version, tenantId: tenantId.value })
    ElMessage.success(published ? '已标记待删除' : '已移除')
  } catch {
    // 冲突等错误拦截器已提示；刷新让用户看到最新版本
  }
  await load(true)
}

function imageTag(image?: string | null): string {
  if (!image) return ''
  const i = image.lastIndexOf(':')
  return i > image.lastIndexOf('/') ? image.slice(i + 1) : image
}
function tagCell(row: AppServiceRow): string {
  const want = imageTag(row.image)
  const cur = imageTag(row.liveImage)
  return cur && cur !== want ? `${cur} → ${want}` : want
}
function usedBy(depKey: string): string[] {
  return (overview.value?.services ?? [])
    .filter((s) => (s.desired.env ?? []).some((e) => (e.value ?? '').includes('${' + depKey + '.')))
    .map((s) => s.key)
}

const bottomTab = ref('releases')

onMounted(async () => {
  await ensure()
  await load()
})
watch(appId, () => { overview.value = null; app.value = null; void load() })
</script>

<template>
  <div class="app-detail" v-loading="loading && !overview">
    <PageHeader :title="app?.displayName || app?.name || '应用'">
      <el-button @click="goList">返回列表</el-button>
      <el-button :loading="loading" @click="load()">刷新</el-button>
      <el-button type="primary" :disabled="!overview" @click="openRelease">
        发布{{ pendingCount ? ` · ${pendingCount} 项改动` : '' }}
      </el-button>
    </PageHeader>

    <EmptyState v-if="loadFailed" title="加载失败" description="应用不存在，或没有访问权限。">
      <el-button @click="load()">重试</el-button>
    </EmptyState>

    <template v-else-if="overview && app">
      <div class="app-detail__meta">
        <StatusBadge :label="statusMeta.text" :type="statusMeta.type" />
        <span>线上 <b>{{ live ? `#${live}` : '未发布' }}</b></span>
        <span class="muted">命名空间 {{ app.namespace }}</span>
        <span v-if="app.description" class="muted">{{ app.description }}</span>
      </div>

      <el-alert
        v-if="active"
        class="app-detail__banner"
        type="info"
        :closable="false"
        show-icon
        :title="`发布 #${active.releaseNo} 进行中：${EXEC_STAGE[active.stage ?? ''] ?? active.stage ?? active.status}`"
        :description="active.message || undefined"
      />
      <el-alert
        v-else-if="lastFailed"
        class="app-detail__banner"
        type="error"
        :closable="false"
        show-icon
        :title="`发布 #${lastFailed.releaseNo} ${lastFailed.status === 'INTERRUPTED' ? '已中断' : '失败'}`"
        :description="lastFailed.message || '重新执行、继续与回滚入口将在发布记录中提供。'"
      />

      <section class="panel app-detail__sec">
        <div class="app-detail__sec-head">
          <h3>服务</h3>
          <el-button type="primary" plain @click="openAdd">+ 添加服务</el-button>
        </div>
        <EmptyState v-if="!overview.services.length" title="还没有服务" description="添加一个服务：填镜像和端口，填了端口就是服务，不填就是后台进程。" />
        <el-table v-else :data="overview.services" size="default">
          <el-table-column label="名称" min-width="150">
            <template #default="{ row }">
              <el-link type="primary" @click="openService(row)">{{ row.key }}</el-link>
              <el-tag size="small" effect="plain" class="app-detail__kind">{{ row.kind === 'SERVICE' ? '服务' : '后台进程' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="镜像 tag" min-width="170">
            <template #default="{ row }"><code>{{ tagCell(row) }}</code></template>
          </el-table-column>
          <el-table-column label="副本" width="80">
            <template #default="{ row }">{{ row.replicas ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <StatusBadge :label="STATE_META[row.state as AppServiceState]?.text ?? row.state" :type="STATE_META[row.state as AppServiceState]?.type ?? 'neutral'" />
            </template>
          </el-table-column>
          <el-table-column label="待发布" min-width="140">
            <template #default="{ row }">
              <el-tag v-if="row.pending" :type="PENDING_META[row.pending]?.type" effect="light" size="small">
                {{ PENDING_META[row.pending]?.text }}
              </el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openService(row)">配置</el-button>
              <el-button link type="danger" :disabled="row.pending === 'DELETED'" @click="removeService(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel app-detail__sec">
        <div class="app-detail__sec-head">
          <h3>依赖</h3>
          <!-- 依赖的新建 / 绑定 / 外部连接由后续任务提供 -->
          <el-button plain disabled title="依赖管理将在后续版本提供">+ 添加依赖</el-button>
        </div>
        <EmptyState v-if="!overview.dependencies.length" title="暂无依赖" description="数据库、缓存等依赖在这里统一管理，服务通过 ${依赖名.host} 引用。" />
        <el-table v-else :data="overview.dependencies">
          <el-table-column prop="key" label="名称" min-width="140" />
          <el-table-column prop="type" label="类型" width="120" />
          <el-table-column label="来源" width="100">
            <template #default="{ row }">{{ row.ownership === 'CREATED' ? '应用创建' : '绑定' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">{{ row.status || '—' }}</template>
          </el-table-column>
          <el-table-column label="使用方" min-width="160">
            <template #default="{ row }">
              <template v-if="usedBy(row.key).length">被 {{ usedBy(row.key).join('、') }} 使用</template>
              <span v-else class="muted">暂无引用</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <el-tabs v-model="bottomTab" class="app-detail__tabs">
        <el-tab-pane label="发布记录" name="releases" lazy>
          <EmptyState title="发布记录" description="发布记录与回滚将在后续版本提供。" />
        </el-tab-pane>
        <el-tab-pane label="拓扑" name="topology" lazy>
          <EmptyState title="拓扑" description="由变量引用生成的只读依赖图，将在后续版本提供。" />
        </el-tab-pane>
        <el-tab-pane label="事件" name="events" lazy>
          <EmptyState title="事件" description="应用事件将在后续版本提供。" />
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-dialog v-model="addVisible" title="添加服务" width="520px" class="app-svc-dlg" destroy-on-close>
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="addForm.key" placeholder="如 api（创建后不可改，决定资源名）" maxlength="20" />
          <div v-if="keyHint" class="app-svc-dlg__err">{{ keyHint }}</div>
        </el-form-item>
        <el-form-item label="镜像仓库" required>
          <el-input v-model="addForm.imageRepo" placeholder="不含 tag，如 harbor.example.com/t-demo/api" />
        </el-form-item>
        <el-form-item label="镜像 tag" required>
          <el-input v-model="addForm.imageTag" placeholder="如 1.4.2" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="addForm.port" :min="1" :max="65535" :controls="false" placeholder="留空" />
          <span class="app-svc-dlg__tip">填了端口就是服务，不填就是后台进程</span>
        </el-form-item>
        <el-form-item label="副本数">
          <el-input-number v-model="addForm.replicas" :min="0" :max="100" />
        </el-form-item>
        <el-alert v-if="addError" type="error" :closable="false" show-icon :title="addError" />
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="adding" :disabled="!canAdd" @click="submitAdd">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.muted { color: var(--text-3); }
.app-detail__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin: 0 10px 12px;
  font-size: 13px;
}
.app-detail__banner { margin-bottom: 12px; }
.app-detail__sec { padding: 14px 16px; margin-bottom: 16px; }
.app-detail__sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.app-detail__sec-head h3 { margin: 0; font-size: 15px; }
.app-detail__kind { margin-left: 8px; }
.app-detail__tabs { margin: 0 4px; }
</style>

<style>
/* el-dialog 内部节点没有 data-v-*，scoped 打不到；唯一前缀 app-svc-dlg */
.app-svc-dlg .app-svc-dlg__err { color: var(--el-color-danger); font-size: 12px; }
.app-svc-dlg .app-svc-dlg__tip { margin-left: 10px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
