<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi } from '@/api'
import type { K8sCluster } from '@/types'
import { fmtDate } from '@/utils/format'

const loading = ref(false)
const list = ref<K8sCluster[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await clusterApi.list()
  } finally {
    loading.value = false
  }
}

// ---- 创建 / 编辑对话框 ----
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const IP_STACKS: K8sCluster['ipStack'][] = ['IPV4', 'IPV6', 'IPV4_AND_IPV6']
const form = reactive({
  clusterId: '',
  clusterName: '',
  description: '',
  kubeconfig: '',
  version: '',
  istioVersion: '',
  calicoVersion: '',
  containerRuntime: '',
  ipStack: 'IPV4' as K8sCluster['ipStack'],
  prometheusUrl: '',
  grafanaUrl: '',
})

function resetForm(): void {
  form.clusterId = ''
  form.clusterName = ''
  form.description = ''
  form.kubeconfig = ''
  form.version = ''
  form.istioVersion = ''
  form.calicoVersion = ''
  form.containerRuntime = ''
  form.ipStack = 'IPV4'
  form.prometheusUrl = ''
  form.grafanaUrl = ''
}

function openCreate(): void {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: K8sCluster): void {
  isEdit.value = true
  resetForm()
  form.clusterId = row.clusterId
  form.clusterName = row.clusterName
  form.description = row.description ?? ''
  form.version = row.version ?? ''
  form.istioVersion = row.istioVersion ?? ''
  form.calicoVersion = row.calicoVersion ?? ''
  form.containerRuntime = row.containerRuntime ?? ''
  form.ipStack = row.ipStack ?? 'IPV4'
  form.prometheusUrl = row.prometheusUrl ?? ''
  form.grafanaUrl = row.grafanaUrl ?? ''
  // kubeconfig 留空 = 保留原值
  dialogVisible.value = true
}

function commonPayload() {
  return {
    clusterName: form.clusterName.trim(),
    description: form.description || undefined,
    version: form.version || undefined,
    istioVersion: form.istioVersion || undefined,
    calicoVersion: form.calicoVersion || undefined,
    containerRuntime: form.containerRuntime || undefined,
    ipStack: form.ipStack,
    prometheusUrl: form.prometheusUrl || undefined,
    grafanaUrl: form.grafanaUrl || undefined,
  }
}

async function submit(): Promise<void> {
  if (!form.clusterName.trim()) {
    ElMessage.warning('请输入集群名称')
    return
  }
  if (!isEdit.value && !form.kubeconfig.trim()) {
    ElMessage.warning('请粘贴 kubeconfig 内容')
    return
  }
  // 编辑模式下填写了 kubeconfig → 重新探测 + 加密，需确认
  if (isEdit.value && form.kubeconfig.trim()) {
    try {
      await ElMessageBox.confirm(
        '已填写新的 kubeconfig，将重新探测连通性并覆盖加密存储，继续？',
        '修改 kubeconfig',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await clusterApi.update({
        clusterId: form.clusterId,
        ...commonPayload(),
        kubeconfig: form.kubeconfig.trim() || undefined, // 留空 = 保留原值
      })
      ElMessage.success('已更新')
    } else {
      await clusterApi.create({
        ...commonPayload(),
        kubeconfig: form.kubeconfig,
      })
      ElMessage.success('创建成功，K8s 侧开通完成')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 错误已由拦截器提示 */
  } finally {
    saving.value = false
  }
}

// ---- 行操作 ----
async function onToggle(row: K8sCluster, enabled: boolean): Promise<void> {
  const value = enabled ? 1 : 0
  try {
    await clusterApi.toggleEnabled(row.clusterId, value)
    row.enabled = value
    ElMessage.success(enabled ? '已启用' : '已禁用')
  } catch {
    /* 拦截器提示 */
  }
}

async function onProvision(row: K8sCluster): Promise<void> {
  try {
    await clusterApi.provision(row.clusterId)
    ElMessage.success('重新开通完成')
    await load()
  } catch {
    /* 拦截器提示 */
  }
}

const refreshingId = ref('')
async function onRefreshCapability(row: K8sCluster): Promise<void> {
  refreshingId.value = row.clusterId
  try {
    await clusterApi.refreshCapability(row.clusterId)
    ElMessage.success('集群 API 能力已刷新')
  } catch {
    /* 拦截器提示 */
  } finally {
    refreshingId.value = ''
  }
}

async function onDelete(row: K8sCluster): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除集群「${row.clusterName}」？（软删除，K8s 侧对象保留）`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await clusterApi.delete(row.clusterId)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器提示 */
  }
}

function statusTag(status?: K8sCluster['status']): { type: 'success' | 'danger' | 'info' | 'warning'; text: string } {
  switch (status) {
    case 'CONNECTED': return { type: 'success', text: '已连接' }
    case 'ERROR': return { type: 'danger', text: '错误' }
    case 'CONNECTING': return { type: 'warning', text: '连接中' }
    default: return { type: 'info', text: status ?? '未知' }
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增集群</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="clusterName" label="集群名称" min-width="140" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status).type">{{ statusTag(row.status).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled === 1" @change="(v: boolean) => onToggle(row, v)" />
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="120" />
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmtDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" @click="onProvision(row)">重新开通</el-button>
          <el-button link type="success" :loading="refreshingId === row.clusterId" @click="onRefreshCapability(row)">刷新能力</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑集群' : '新增集群'" width="680px">
      <el-form label-width="100px">
        <el-divider content-position="left">基本信息</el-divider>
        <el-form-item label="集群名称" required>
          <el-input v-model="form.clusterName" placeholder="例如 prod-cluster-1" />
        </el-form-item>
        <el-form-item label="kubeconfig" :required="!isEdit">
          <el-input
            v-model="form.kubeconfig"
            type="textarea"
            :rows="6"
            :placeholder="isEdit ? '留空 = 保留原值；填写新内容将重新探测并覆盖加密存储' : '粘贴 .kube/config 文件全部内容（将加密存储）'"
            class="kubeconfig-input"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>

        <el-divider content-position="left">组件版本</el-divider>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="集群版本">
              <el-input v-model="form.version" placeholder="例如 v1.29.0" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="容器运行时">
              <el-input v-model="form.containerRuntime" placeholder="例如 containerd" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Istio 版本">
              <el-input v-model="form.istioVersion" placeholder="例如 1.21.0" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Calico 版本">
              <el-input v-model="form.calicoVersion" placeholder="例如 3.27.0" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">网络</el-divider>
        <el-form-item label="IP 栈">
          <el-select v-model="form.ipStack" style="width: 200px">
            <el-option v-for="s in IP_STACKS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">监控接入（仅记录，本监控功能不用）</el-divider>
        <el-form-item label="Prometheus">
          <el-input v-model="form.prometheusUrl" placeholder="例如 http://prometheus.monitor:9090" />
        </el-form-item>
        <el-form-item label="Grafana">
          <el-input v-model="form.grafanaUrl" placeholder="例如 http://grafana.monitor:3000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}
.kubeconfig-input :deep(textarea) {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
}
</style>
