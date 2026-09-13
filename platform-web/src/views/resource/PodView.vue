<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, Search } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { podApi } from '@/api'
import type { K8sPod } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import PodTerminal from '@/components/PodTerminal.vue'
import PodLogDialog from '@/components/PodLogDialog.vue'
import { fmtAge, fmtDate, podPhaseType } from '@/utils/format'

const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sPod[]>([])
/** 名称模糊搜索（前端过滤） */
const keyword = ref('')
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter((r) => (r.name ?? '').toLowerCase().includes(kw))
})

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

/** 就绪（容器）：非 init 容器的 ready/total */
function readyCount(pod: K8sPod): string {
  const cs = (pod.containerDetails ?? []).filter((c) => !c.init)
  return `${cs.filter((c) => c.ready).length}/${cs.length}`
}

/** 点名称 → Pod 详情页（与工作负载详情里的 pod 列表一致） */
function goPod(name: string): void {
  router.push(`/resources/pods/detail?name=${encodeURIComponent(name)}`)
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

// ---------- Yaml（只读） ----------
const yamlVisible = ref(false)
const yamlPod = ref<K8sPod | null>(null)
const yamlText = ref('')
async function openYaml(row: K8sPod): Promise<void> {
  yamlPod.value = row
  yamlText.value = ''
  yamlVisible.value = true
  try {
    yamlText.value = await podApi.getYaml(row.name, ctxParams.value)
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 日志（PodLogDialog：恒跟随 + 底部自动滚动 + 全屏） ----------
const logVisible = ref(false)
const logPod = ref<K8sPod | null>(null)

function openLogs(row: K8sPod): void {
  logPod.value = row
  logVisible.value = true
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

// ---------- Pod 操作下拉：日志 / Exec / Yaml / 删除（与工作负载详情里的 pod 列表一致） ----------
function onPodCommand(cmd: string, row: K8sPod): void {
  switch (cmd) {
    case 'logs': openLogs(row); break
    case 'exec': openExec(row); break
    case 'yaml': openYaml(row); break
    case 'delete': onDelete(row); break
  }
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
    <PageHeader title="Pod" >
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 Pod"
      description="Pod 由工作负载控制器管理（本页只读）；创建 Deployment/StatefulSet/DaemonSet 后 Pod 会自动出现。"
    />

    <div v-else class="panel table-panel">
      <div class="search-bar">
        <el-input v-model="keyword" placeholder="按名称搜索…" clearable :prefix-icon="Search" class="search-input" />
      </div>
      <el-table v-loading="loading || !ready" :data="filtered" stripe>
        <el-table-column label="名称" width="350">
          <template #default="{ row }"><code class="res-name name-link" @click="goPod(row.name)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="镜像" min-width="500">
          <template #default="{ row }">
            <code v-for="(c, i) in row.containerDetails ?? []" :key="i" class="res-name img-cell">
              <span class="muted">{{c.name}}:</span> {{ c.image }}</code>
          </template>
        </el-table-column>
        <el-table-column label="容器" min-width="60">
          <template #default="{ row }">{{ readyCount(row) }}</template>
        </el-table-column>
        <el-table-column label="IP" min-width="130">
          <template #default="{ row }"><span class="muted">{{ row.podIp ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="调度节点" min-width="80">
          <template #default="{ row }"><span class="muted">{{ row.nodeName ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="重启" min-width="80">
          <template #default="{ row }">{{ row.restarts ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }"><StatusBadgeTip :label="row.phase ?? 'Unknown'" :type="podPhaseType(row.phase)" :reason="row.statusReason" /></template>
        </el-table-column>
        <el-table-column label="创建时间（存活时长）" width="170">
          <template #default="{ row }">
            <div class="age-cell"><div>{{ fmtDate(row.creationTime) }}</div><div class="muted">{{ fmtAge(row.creationTime) }}</div></div>
          </template>
        </el-table-column>
        <el-table-column width="64" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: string) => onPodCommand(cmd, row)">
              <el-button link type="primary" :icon="MoreFilled" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="logs">日志</el-dropdown-item>
                  <el-dropdown-item command="exec" :disabled="row.phase !== 'Running'">Exec</el-dropdown-item>
                  <el-dropdown-item command="yaml">Yaml</el-dropdown-item>
                  <el-dropdown-item divided style="color: var(--el-color-danger)" command="delete">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 日志（流式：恒跟随 + 底部自动滚动 + 全屏） -->
    <PodLogDialog
      v-model="logVisible"
      :pod="logPod"
      :tenant-id="ctxParams.tenantId"
      :cluster-id="ctxParams.clusterId"
      :namespace="ctxParams.namespace"
    />

    <!-- Exec 终端 -->
    <el-dialog v-model="execVisible" :title="`Exec · ${execPod?.name ?? ''}`" width="960px" destroy-on-close>
      <div class="log-toolbar">
        <el-select v-model="execContainer" size="small" style="width: 200px">
          <el-option v-for="c in execPod?.containers ?? []" :key="c" :label="c" :value="c" />
        </el-select>
        <span class="muted">切换容器会断开当前会话并重连；关闭弹窗即断开</span>
      </div>
      <PodTerminal
        v-if="execVisible && execPod"
        :key="execContainer"
        :tenant-id="ctxParams.tenantId"
        :cluster-id="ctxParams.clusterId"
        :namespace="ctxParams.namespace"
        :name="execPod.name"
        :container="execContainer || undefined"
      />
    </el-dialog>

    <!-- Yaml（只读） -->
    <el-dialog v-model="yamlVisible" :title="`Yaml · ${yamlPod?.name ?? ''}`" width="760px">
      <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
      <div v-else class="muted">加载中…</div>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.search-bar {
  margin-bottom: 10px;
}
.search-input {
  width: 260px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
.muted {
  color: var(--text-3);
}
.age-cell {
  line-height: 1.5;
}
.img-cell {
  display: inline-block;
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
</style>
