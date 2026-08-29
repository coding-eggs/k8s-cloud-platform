<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { serviceMonitorApi } from '@/api'
import type { K8sServiceMonitor, K8sSmEndpoint } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sServiceMonitor[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await serviceMonitorApi.list(ctxParams.value)
  } catch (e) {
    //集群未装 Prometheus Operator 时 CRD 404：拦截器已提示，这里保留空列表
    list.value = []
  } finally {
    loading.value = false
  }
}

// ---------- 创建 / 编辑对话框（matchLabels kv + endpoint 行） ----------
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const form = reactive({
  name: '',
  matchRows: [] as { key: string; value: string }[],
  endpoints: [] as { port: string; path: string; interval: string }[],
})

function newForm(): void {
  form.name = ''
  form.matchRows = [{ key: 'app', value: '' }]
  form.endpoints = [{ port: '', path: '/metrics', interval: '30s' }]
}

function openCreate(): void {
  isEdit.value = false
  newForm()
  dialogVisible.value = true
}

function openEdit(row: K8sServiceMonitor): void {
  isEdit.value = true
  form.name = row.name
  const entries = Object.entries(row.matchLabels ?? {})
  form.matchRows = entries.length ? entries.map(([key, value]) => ({ key, value })) : [{ key: '', value: '' }]
  const eps = (row.endpoints ?? []).map((e) => ({ port: e.port ?? '', path: e.path ?? '', interval: e.interval ?? '' }))
  form.endpoints = eps.length ? eps : [{ port: '', path: '/metrics', interval: '30s' }]
  dialogVisible.value = true
}

function addMatchRow(): void {
  form.matchRows.push({ key: '', value: '' })
}

function removeMatchRow(index: number): void {
  form.matchRows.splice(index, 1)
}

function addEndpoint(): void {
  form.endpoints.push({ port: '', path: '/metrics', interval: '30s' })
}

function removeEndpoint(index: number): void {
  form.endpoints.splice(index, 1)
}

async function submit(): Promise<void> {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('请输入名称')
    return
  }
  if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(name)) {
    ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾')
    return
  }
  const matchLabels: Record<string, string> = {}
  for (const row of form.matchRows) {
    const key = row.key.trim()
    if (!key || !row.value.trim()) continue
    matchLabels[key] = row.value.trim()
  }
  if (!Object.keys(matchLabels).length) {
    ElMessage.warning('至少需要一条有效的 matchLabels（用于选中目标 Service）')
    return
  }
  const endpoints: K8sSmEndpoint[] = form.endpoints
    .filter((e) => e.port.trim())
    .map((e) => ({ port: e.port.trim(), path: e.path.trim() || null, interval: e.interval.trim() || null }))
  if (!endpoints.length) {
    ElMessage.warning('至少需要一个端点（port 必填）')
    return
  }

  saving.value = true
  try {
    const payload = { name, namespace: state.namespace!, matchLabels, endpoints }
    if (isEdit.value) {
      await serviceMonitorApi.update(name, { tenantId: state.tenantId!, clusterId: state.clusterId! }, payload)
      ElMessage.success('已更新')
    } else {
      await serviceMonitorApi.create({ tenantId: state.tenantId!, clusterId: state.clusterId! }, payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

// ---------- 删除 ----------
async function onDelete(row: K8sServiceMonitor): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 ServiceMonitor「${row.name}」？Prometheus 将停止抓取对应目标。`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await serviceMonitorApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sServiceMonitor | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sServiceMonitor): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await serviceMonitorApi.getYaml(detail.value.name, ctxParams.value)
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
    <PageHeader title="ServiceMonitor" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="openCreate">创建 ServiceMonitor</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 ServiceMonitor"
      description="ServiceMonitor 由 Prometheus Operator 消费（monitoring.coreos.com/v1 CRD）；集群未安装时操作会提示错误。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="选择标签（matchLabels）" min-width="240">
          <template #default="{ row }">
            <template v-if="row.matchLabels && Object.keys(row.matchLabels).length">
              <el-tag
                v-for="(v, k) in row.matchLabels"
                :key="k"
                size="small"
                effect="plain"
                class="label-tag"
              >{{ k }}={{ v }}</el-tag>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="端点" width="90">
          <template #default="{ row }">{{ (row.endpoints ?? []).length }} 个</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 创建 / 编辑 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? `编辑 ServiceMonitor · ${form.name}` : '创建 ServiceMonitor'" width="720px" top="8vh">
      <el-form label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="isEdit" placeholder="小写字母/数字/-，例如 app-monitor" />
          <div class="form-tip">K8s 资源名创建后不可修改；命名空间 = 当前上下文</div>
        </el-form-item>
        <el-form-item label="选择标签">
          <div class="kv-editor">
            <div v-for="(row, idx) in form.matchRows" :key="idx" class="kv-row">
              <el-input v-model="row.key" placeholder="Key（如 app）" class="kv-key" />
              <el-input v-model="row.value" placeholder="Value（需匹配目标 Service 的标签）" />
              <el-button link type="danger" :disabled="form.matchRows.length <= 1" @click="removeMatchRow(idx)">删除</el-button>
            </div>
            <el-button class="add-row-btn" plain @click="addMatchRow">+ 添加标签</el-button>
          </div>
        </el-form-item>
        <el-form-item label="抓取端点">
          <div class="kv-editor">
            <div v-for="(ep, idx) in form.endpoints" :key="idx" class="kv-row">
              <el-input v-model="ep.port" placeholder="Port（必填）" style="width: 30%" />
              <el-input v-model="ep.path" placeholder="Path（如 /metrics）" style="width: 34%" />
              <el-input v-model="ep.interval" placeholder="Interval（如 30s）" style="width: 26%" />
              <el-button link type="danger" :disabled="form.endpoints.length <= 1" @click="removeEndpoint(idx)">删除</el-button>
            </div>
            <el-button class="add-row-btn" plain @click="addEndpoint">+ 添加端点</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`ServiceMonitor · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="选择标签">
              <el-tag
                v-for="(v, k) in detail?.matchLabels ?? {}"
                :key="k"
                size="small"
                effect="plain"
                class="label-tag"
              >{{ k }}={{ v }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
          <div class="section-title">抓取端点</div>
          <el-table :data="detail?.endpoints ?? []" stripe size="small">
            <el-table-column prop="port" label="Port" width="100" />
            <el-table-column prop="path" label="Path" min-width="160">
              <template #default="{ row }">{{ row.path ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="interval" label="Interval" width="100">
              <template #default="{ row }">{{ row.interval ?? '—' }}</template>
            </el-table-column>
          </el-table>
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
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.label-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.muted {
  color: var(--text-3);
}
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.kv-editor {
  width: 100%;
}
.kv-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.kv-key {
  width: 40%;
  flex-shrink: 0;
}
.add-row-btn {
  width: 100%;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  margin: 14px 0 8px;
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
