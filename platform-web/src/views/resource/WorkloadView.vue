<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workloadApi } from '@/api'
import type { K8sWorkload } from '@/types'
import type { WorkloadKind } from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()

const loading = ref(false)
const list = ref<K8sWorkload[]>([])
/**客户端类型过滤：all / deployment / statefulset / daemonset */
const kindFilter = ref('all')

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await workloadApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

const KINDS = [
  { value: 'all', label: '全部' },
  { value: 'deployment', label: 'Deployment' },
  { value: 'statefulset', label: 'StatefulSet' },
  { value: 'daemonset', label: 'DaemonSet' },
]

const filtered = computed(() =>
  kindFilter.value === 'all' ? list.value : list.value.filter((w) => w.kind === kindFilter.value),
)

function kindLabel(kind?: string | null): string {
  switch (kind) {
    case 'deployment': return 'Deployment'
    case 'statefulset': return 'StatefulSet'
    case 'daemonset': return 'DaemonSet'
    default: return kind ?? '—'
  }
}

function replicasText(row: K8sWorkload): string {
  if (row.kind === 'daemonset') {
    return row.readyReplicas != null ? `${row.readyReplicas} 就绪` : '—'
  }
  const total = row.replicas ?? 0
  const readyCount = row.readyReplicas ?? 0
  return `${readyCount}/${total}`
}

// ---------- 跳转编辑器（新建 / 编辑） ----------
function goEditor(name: string | null): void {
  router.push(name ? `/resources/workloads/editor?name=${encodeURIComponent(name)}` : '/resources/workloads/editor')
}

// ---------- 伸缩：基础表单仅伸缩副本数（DaemonSet 无副本概念） ----------
const scaleVisible = ref(false)
const scaling = ref(false)
const scaleForm = reactive({ row: null as K8sWorkload | null, replicas: 1 })

function openScale(row: K8sWorkload): void {
  if (row.kind === 'daemonset') {
    ElMessage.info('DaemonSet 无副本数概念，每个节点一个 Pod')
    return
  }
  scaleForm.row = row
  scaleForm.replicas = row.replicas ?? 1
  scaleVisible.value = true
}

async function submitScale(): Promise<void> {
  const row = scaleForm.row
  if (!row) return
  scaling.value = true
  try {
    await workloadApi.update(row.name, { tenantId: state.tenantId!, clusterId: state.clusterId! }, {
      kind: row.kind as WorkloadKind,
      name: row.name,
      namespace: state.namespace!,
      replicas: scaleForm.replicas,
    })
    ElMessage.success(`已伸缩到 ${scaleForm.replicas} 副本`)
    scaleVisible.value = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    scaling.value = false
  }
}

// ---------- 删除（跨 kind 查找） ----------
async function onDelete(row: K8sWorkload): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 ${kindLabel(row.kind)}「${row.name}」？其 Pod 会被一并回收。`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await workloadApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sWorkload | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sWorkload): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await workloadApi.getYaml(detail.value.name, ctxParams.value)
    } catch {
      /* 拦截器已提示 */
    }
  }
})

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
    <PageHeader title="工作负载" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="goEditor(null)">创建工作负载</el-button>
    </PageHeader>

    <div v-if="ready" class="panel table-panel">
      <div class="kind-filter">
        <el-radio-group v-model="kindFilter" size="small">
          <el-radio-button v-for="k in KINDS" :key="k.value" :value="k.value">{{ k.label }}</el-radio-button>
        </el-radio-group>
      </div>

      <EmptyState
        v-if="filtered.length === 0 && !loading"
        title="该命名空间下暂无工作负载"
        description="点击右上「创建工作负载」新建 Deployment / StatefulSet / DaemonSet。"
      />
      <el-table v-else v-loading="loading || !ready" :data="filtered" stripe>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ kindLabel(row.kind) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="副本（就绪/总数）" width="150">
          <template #default="{ row }">{{ replicasText(row) }}</template>
        </el-table-column>
        <el-table-column label="镜像" min-width="220">
          <template #default="{ row }">
            <code v-for="(img, i) in row.images ?? []" :key="i" class="res-name img-cell">{{ img }}</code>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" @click="goEditor(row.name)">编辑</el-button>
            <el-button link type="primary" @click="openScale(row)">伸缩</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 伸缩（基础表单仅支持副本数） -->
    <el-dialog v-model="scaleVisible" :title="`伸缩 · ${scaleForm.row?.name ?? ''}`" width="420px">
      <el-form label-width="80px">
        <el-form-item label="副本数">
          <el-input-number v-model="scaleForm.replicas" :min="0" :max="64" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scaleVisible = false">取消</el-button>
        <el-button type="primary" :loading="scaling" @click="submitScale">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`${kindLabel(detail?.kind)} · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="类型">{{ kindLabel(detail?.kind) }}</el-descriptions-item>
            <el-descriptions-item label="副本">{{ replicasText(detail!) }}</el-descriptions-item>
            <el-descriptions-item label="镜像">
              <code v-for="(img, i) in detail?.images ?? []" :key="i" class="res-name img-cell">{{ img }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="端口">
              <span class="muted">{{ (detail?.ports ?? []).map((p) => p.containerPort).filter(Boolean).join('，') || '—' }}</span>
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
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.kind-filter {
  margin-bottom: 10px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.img-cell {
  display: inline-block;
  margin-right: 8px;
  font-size: 12.5px;
}
.muted {
  color: var(--text-3);
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
</style>
