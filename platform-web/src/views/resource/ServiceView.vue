<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { serviceApi } from '@/api'
import type { K8sService, K8sServicePort } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sService[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await serviceApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

const SERVICE_TYPES = ['ClusterIP', 'NodePort', 'LoadBalancer']
const PROTOCOLS = ['TCP', 'UDP', 'SCTP']

// ---------- 创建 / 编辑对话框（类型 + 端口行） ----------
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const form = reactive({
  name: '',
  type: 'ClusterIP',
  ports: [] as { name: string; port: number | null; targetPort: string; nodePort: number | null; protocol: string }[],
})

function newPortRow(): { name: string; port: number | null; targetPort: string; nodePort: number | null; protocol: string } {
  return { name: '', port: null, targetPort: '', nodePort: null, protocol: 'TCP' }
}

function openCreate(): void {
  isEdit.value = false
  form.name = ''
  form.type = 'ClusterIP'
  form.ports = [newPortRow()]
  dialogVisible.value = true
}

function openEdit(row: K8sService): void {
  isEdit.value = true
  form.name = row.name
  form.type = row.type || 'ClusterIP'
  const ports = (row.ports ?? []).map((p) => ({
    name: p.name ?? '',
    port: p.port ?? null,
    targetPort: p.targetPort ?? '',
    nodePort: p.nodePort ?? null,
    protocol: p.protocol ?? 'TCP',
  }))
  form.ports = ports.length ? ports : [newPortRow()]
  dialogVisible.value = true
}

function addPort(): void {
  form.ports.push(newPortRow())
}

function removePort(index: number): void {
  form.ports.splice(index, 1)
}

const showNodePort = computed(() => form.type === 'NodePort')

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
  if (form.ports.length === 0) {
    ElMessage.warning('至少需要一个端口')
    return
  }
  const ports: K8sServicePort[] = []
  for (const p of form.ports) {
    if (!p.port || p.port < 1 || p.port > 65535) {
      ElMessage.warning('存在非法的 Service 端口（1-65535）')
      return
    }
    if (!p.targetPort.trim()) {
      ElMessage.warning('每个端口需填写 targetPort（数字或命名端口）')
      return
    }
    ports.push({
      name: p.name.trim() || null,
      port: p.port,
      targetPort: p.targetPort.trim(),
      nodePort: showNodePort.value ? p.nodePort : null,
      protocol: p.protocol,
    })
  }

  saving.value = true
  try {
    const payload = { name, namespace: state.namespace!, type: form.type, ports }
    if (isEdit.value) {
      await serviceApi.update(name, { tenantId: state.tenantId!, clusterId: state.clusterId! }, payload)
      ElMessage.success('已更新')
    } else {
      await serviceApi.create({ tenantId: state.tenantId!, clusterId: state.clusterId! }, payload)
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
async function onDelete(row: K8sService): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 Service「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await serviceApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sService | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sService): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await serviceApi.getYaml(detail.value.name, ctxParams.value)
    } catch {
      /* 拦截器已提示 */
    }
  }
})

function portSummary(row: K8sService): string {
  return (row.ports ?? [])
    .map((p) => `${p.port}→${p.targetPort}${p.nodePort ? `:${p.nodePort}` : ''} ${p.protocol ?? 'TCP'}`)
    .join('，') || '—'
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
    <PageHeader title="Service" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="openCreate">创建 Service</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 Service"
      description="点击右上「创建 Service」新建，或到顶栏切换上下文查看其他命名空间。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="180">
          <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.type || 'ClusterIP' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Cluster IP" width="140">
          <template #default="{ row }"><span class="muted">{{ row.clusterIp ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="端口" min-width="200">
          <template #default="{ row }"><span class="port-text">{{ portSummary(row) }}</span></template>
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
    <el-dialog v-model="dialogVisible" :title="isEdit ? `编辑 Service · ${form.name}` : '创建 Service'" width="720px" top="8vh">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="isEdit" placeholder="小写字母/数字/-，例如 app-svc" />
          <div class="form-tip">K8s 资源名创建后不可修改；命名空间 = 当前上下文</div>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 200px">
            <el-option v-for="t in SERVICE_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="端口">
          <div class="port-editor">
            <div v-for="(p, idx) in form.ports" :key="idx" class="port-row">
              <el-input v-model="p.name" placeholder="名称（可空）" class="port-name" />
              <el-input-number v-model="p.port" :min="1" :max="65535" controls-position="right" placeholder="Port" class="port-num" />
              <el-input v-model="p.targetPort" placeholder="TargetPort（数字/命名）" class="port-target" />
              <el-input-number
                v-if="showNodePort"
                v-model="p.nodePort"
                :min="30000"
                :max="32767"
                controls-position="right"
                placeholder="NodePort（可空）"
                class="port-num"
              />
              <el-select v-model="p.protocol" class="port-proto">
                <el-option v-for="pr in PROTOCOLS" :key="pr" :label="pr" :value="pr" />
              </el-select>
              <el-button link type="danger" :disabled="form.ports.length <= 1" @click="removePort(idx)">删除</el-button>
            </div>
            <el-button class="add-row-btn" plain @click="addPort">+ 添加端口</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`Service · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="类型">{{ detail?.type || 'ClusterIP' }}</el-descriptions-item>
            <el-descriptions-item label="Cluster IP">{{ detail?.clusterIp ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
          <div class="section-title">端口</div>
          <el-table :data="detail?.ports ?? []" stripe size="small">
            <el-table-column prop="name" label="名称" width="100">
              <template #default="{ row }">{{ row.name ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="port" label="Port" width="80" />
            <el-table-column prop="targetPort" label="TargetPort" width="110" />
            <el-table-column prop="nodePort" label="NodePort" width="90">
              <template #default="{ row }">{{ row.nodePort ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="protocol" label="协议" width="70">
              <template #default="{ row }">{{ row.protocol ?? 'TCP' }}</template>
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
.port-text {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  color: var(--text-2);
}
.muted {
  color: var(--text-3);
}
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.port-editor {
  width: 100%;
}
.port-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.port-name {
  width: 24%;
  flex-shrink: 0;
}
.port-num {
  width: 130px;
  flex-shrink: 0;
}
.port-target {
  width: 26%;
  flex-shrink: 0;
}
.port-proto {
  width: 90px;
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
