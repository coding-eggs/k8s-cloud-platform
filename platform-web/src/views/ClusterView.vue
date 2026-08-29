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
const form = reactive({ clusterId: '', clusterName: '', description: '', kubeconfig: '' })

function openCreate(): void {
  isEdit.value = false
  form.clusterId = ''
  form.clusterName = ''
  form.description = ''
  form.kubeconfig = ''
  dialogVisible.value = true
}

function openEdit(row: K8sCluster): void {
  isEdit.value = true
  form.clusterId = row.clusterId
  form.clusterName = row.clusterName
  form.description = row.description ?? ''
  form.kubeconfig = ''
  dialogVisible.value = true
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
  saving.value = true
  try {
    if (isEdit.value) {
      await clusterApi.update({
        clusterId: form.clusterId,
        clusterName: form.clusterName.trim(),
        description: form.description || undefined,
      })
      ElMessage.success('已更新')
    } else {
      await clusterApi.create({
        clusterName: form.clusterName.trim(),
        kubeconfig: form.kubeconfig,
        description: form.description || undefined,
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
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" @click="onProvision(row)">重新开通</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑集群' : '新增集群'" width="640px">
      <el-form label-width="90px">
        <el-form-item label="集群名称" required>
          <el-input v-model="form.clusterName" placeholder="例如 prod-cluster-1" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="kubeconfig" required>
          <el-input
            v-model="form.kubeconfig"
            type="textarea"
            :rows="8"
            placeholder="粘贴 .kube/config 文件全部内容（将加密存储）"
            class="kubeconfig-input"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
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
