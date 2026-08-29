<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { podApi } from '@/api'
import type { K8sPod } from '@/types'
import { useResourceContext } from '@/stores/context'
import { getAccessToken } from '@/auth/oauth'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import PodTerminal from '@/components/PodTerminal.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sPod[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await podApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

function phaseType(phase?: string | null): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (phase) {
    case 'Running': return 'success'
    case 'Pending': return 'warning'
    case 'Succeeded': return 'info'
    case 'Failed': return 'danger'
    case 'Unknown': return 'neutral'
    default: return 'warning'
  }
}

// ---------- 删除（由控制器重建） ----------
async function onDelete(row: K8sPod): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 Pod「${row.name}」？若属于工作负载，控制器会自动重建新 Pod。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await podApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sPod | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sPod): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await podApi.getYaml(detail.value.name, ctxParams.value)
    } catch {
      /* 拦截器已提示 */
    }
  }
})

// ---------- 日志（HTTP 流式透传，follow=true 时持续追加） ----------
const logVisible = ref(false)
const logPod = ref<K8sPod | null>(null)
const logContainer = ref('')
const logTailLines = ref(500)
const logFollow = ref(false)
const logText = ref('')
const logLoading = ref(false)
let logAbort: AbortController | null = null

async function startLog(): Promise<void> {
  if (!logPod.value) return
  logAbort?.abort()
  logAbort = new AbortController()
  logLoading.value = true
  logText.value = ''
  const q = new URLSearchParams({
    tenantId: ctxParams.value.tenantId,
    clusterId: ctxParams.value.clusterId,
    namespace: ctxParams.value.namespace,
    tailLines: String(logTailLines.value),
    follow: String(logFollow.value),
  })
  if (logContainer.value) q.set('container', logContainer.value)
  try {
    const resp = await fetch(
      `/api/resource/pods/${encodeURIComponent(logPod.value.name)}/logs?${q.toString()}`,
      { headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` }, signal: logAbort.signal },
    )
    const ctype = resp.headers.get('content-type') ?? ''
    if (!resp.ok || ctype.includes('application/json')) {
      //失败体 = ResponseData JSON，解析出 msg 展示
      let msg = `HTTP ${resp.status}`
      try {
        const body = (await resp.json()) as { code?: number; msg?: string }
        if (body.msg) msg = body.msg
      } catch {
        /* 保留 HTTP 状态 */
      }
      logText.value = `[错误] ${msg}`
      return
    }
    const reader = resp.body!.getReader()
    const decoder = new TextDecoder('utf-8')
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      logText.value += decoder.decode(value, { stream: true })
    }
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      logText.value = `[错误] ${(e as Error).message}`
    }
  } finally {
    logLoading.value = false
  }
}

function openLogs(row: K8sPod): void {
  logPod.value = row
  logContainer.value = (row.containers ?? [])[0] ?? ''
  logVisible.value = true
  void startLog()
}

function closeLogs(): void {
  logAbort?.abort()
  logVisible.value = false
}

// ---------- Exec（xterm 完整终端，WS 经 platform-api 中继） ----------
const execVisible = ref(false)
const execPod = ref<K8sPod | null>(null)
const execContainer = ref('')

function openExec(row: K8sPod): void {
  execPod.value = row
  execContainer.value = (row.containers ?? [])[0] ?? ''
  execVisible.value = true
}

// ---------- 上下文联动：顶栏 chip 变化时刷新 ----------
onMounted(() => {
  void load()
  void refresh()
})
watch(
  () => [state.tenantId, state.clusterId, state.namespace],
  () => {
    if (ready.value) void refresh()
  },
)

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader title="Pod" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 Pod"
      description="Pod 由工作负载控制器管理（本页只读）；创建 Deployment/StatefulSet/DaemonSet 后 Pod 会自动出现。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="240">
          <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge :label="row.phase ?? 'Unknown'" :type="phaseType(row.phase)" />
          </template>
        </el-table-column>
        <el-table-column label="节点" min-width="160">
          <template #default="{ row }"><span class="muted">{{ row.nodeName ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="IP" width="140">
          <template #default="{ row }"><span class="muted">{{ row.podIp ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="重启" width="70">
          <template #default="{ row }">{{ row.restarts ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="容器" min-width="140">
          <template #default="{ row }"><span class="muted">{{ (row.containers ?? []).join('，') || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" @click="openLogs(row)">日志</el-button>
            <el-button link type="primary" :disabled="row.phase !== 'Running'" @click="openExec(row)">Exec</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`Pod · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="状态">
              <StatusBadge :label="detail?.phase ?? 'Unknown'" :type="phaseType(detail?.phase)" />
            </el-descriptions-item>
            <el-descriptions-item label="节点">{{ detail?.nodeName ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="IP">{{ detail?.podIp ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="重启次数">{{ detail?.restarts ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="容器">
              <el-tag v-for="c in detail?.containers ?? []" :key="c" size="small" effect="plain" class="label-tag">{{ c }}</el-tag>
              <span v-if="!(detail?.containers ?? []).length" class="muted">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
    <!-- 日志（流式） -->
    <el-dialog v-model="logVisible" :title="`日志 · ${logPod?.name ?? ''}`" width="760px" @closed="closeLogs">
      <div class="log-toolbar">
        <el-select v-model="logContainer" size="small" style="width: 200px" @change="() => void startLog()">
          <el-option v-for="c in logPod?.containers ?? []" :key="c" :label="c" :value="c" />
        </el-select>
        <el-input-number v-model="logTailLines" :min="10" :max="5000" :step="100" size="small" style="width: 130px" />
        <el-switch v-model="logFollow" active-text="跟随 (follow)" @change="() => void startLog()" />
        <el-button size="small" @click="() => void startLog()">重新加载</el-button>
      </div>
      <pre class="log-block" :class="{ 'is-loading': logLoading }">{{ logText || (logLoading ? '加载中…' : '（无输出）') }}</pre>
    </el-dialog>

    <!-- Exec 终端 -->
    <el-dialog v-model="execVisible" :title="`Exec · ${execPod?.name ?? ''}`" width="960px" destroy-on-close>
      <div class="log-toolbar">
        <el-select v-model="execContainer" size="small" style="width: 200px" disabled>
          <el-option v-for="c in execPod?.containers ?? []" :key="c" :label="c" :value="c" />
        </el-select>
        <span class="muted">多容器 Pod 请在详情中确认目标容器；关闭弹窗即断开会话</span>
      </div>
      <PodTerminal
        v-if="execVisible && execPod"
        :tenant-id="ctxParams.tenantId"
        :cluster-id="ctxParams.clusterId"
        :namespace="ctxParams.namespace"
        :name="execPod.name"
        :container="execContainer || undefined"
      />
    </el-dialog>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.muted {
  color: var(--text-3);
}
.label-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.yaml-block {
  margin: 0;
  padding: 14px;
  border-radius: 8px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
}
.log-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.log-block {
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: #0d1117;
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: #e6edf3;
  height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
