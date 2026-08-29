<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, templateApi, tenantApi } from '@/api'
import type { K8sCluster, NamespaceAllocation, PlatformTenant, RbacTemplate } from '@/types'
import { fmtDate } from '@/utils/format'
import AllocateNamespaceDialog from '@/components/AllocateNamespaceDialog.vue'

const loading = ref(false)
const list = ref<PlatformTenant[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await tenantApi.list()
  } finally {
    loading.value = false
  }
}

// ---- 创建 / 编辑对话框 ----
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const form = reactive({ id: '', name: '', serviceAccount: '', status: 1 })

function openCreate(): void {
  isEdit.value = false
  form.id = ''
  form.name = ''
  form.serviceAccount = ''
  form.status = 1
  dialogVisible.value = true
}

function openEdit(row: PlatformTenant): void {
  isEdit.value = true
  form.id = row.id
  form.name = row.name
  form.serviceAccount = row.serviceAccount
  form.status = row.status
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请输入租户名称')
    return
  }
  if (!isEdit.value) {
    if (!form.serviceAccount.trim()) {
      ElMessage.warning('请输入租户标识（serviceAccount）')
      return
    }
    // 前端预校验（后端仍会校验）：小写字母/数字/-，首尾字母数字，≤60
    const sa = form.serviceAccount.trim()
    if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(sa) || sa.length > 60) {
      ElMessage.warning('serviceAccount 需符合 K8s 命名规范：小写字母/数字/-，首尾为字母或数字，最长 60 字符')
      return
    }
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await tenantApi.update({ id: form.id, name: form.name.trim(), status: form.status })
      ElMessage.success('已更新')
    } else {
      await tenantApi.create({ name: form.name.trim(), serviceAccount: form.serviceAccount.trim(), status: form.status })
      ElMessage.success('创建成功，已在各启用集群创建 SA')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}

// ---- 行操作 ----
async function onToggle(row: PlatformTenant, enabled: boolean): Promise<void> {
  const value = enabled ? 1 : 0
  try {
    await tenantApi.update({ id: row.id, status: value })
    row.status = value
    ElMessage.success(enabled ? '已启用（补建各集群 SA）' : '已禁用')
  } catch {
    /* 拦截器提示 */
  }
}

async function onProvision(row: PlatformTenant): Promise<void> {
  try {
    await tenantApi.provision(row.id)
    ElMessage.success('租户 SA 重新开通完成')
  } catch {
    /* 拦截器提示 */
  }
}

async function onDelete(row: PlatformTenant): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除租户「${row.name}」？将软删除并清理各集群的 SA / RoleBinding。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await tenantApi.delete(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器提示 */
  }
}

// ---- 命名空间抽屉（该租户的分配列表 + 分配 / 取消分配） ----
const drawerVisible = ref(false)
const currentTenant = ref<PlatformTenant | null>(null)
const allocLoading = ref(false)
const allocations = ref<NamespaceAllocation[]>([])
const clusters = ref<K8sCluster[]>([])
const templates = ref<RbacTemplate[]>([])
const allocDialogVisible = ref(false)

const clusterNameMap = computed(() => new Map(clusters.value.map((c) => [c.clusterId, c.clusterName])))
const templateNameMap = computed(() => new Map(templates.value.map((t) => [t.id, t.name])))

async function openNamespaces(row: PlatformTenant): Promise<void> {
  currentTenant.value = row
  drawerVisible.value = true
  ;[clusters.value, templates.value] = await Promise.all([clusterApi.list(), templateApi.list()])
  await loadAllocations()
}

async function loadAllocations(): Promise<void> {
  if (!currentTenant.value) return
  allocLoading.value = true
  try {
    allocations.value = await tenantApi.namespaceList({ tenantId: currentTenant.value.id })
  } finally {
    allocLoading.value = false
  }
}

async function onDeallocate(row: NamespaceAllocation): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消租户「${currentTenant.value?.name ?? ''}」在集群「${clusterNameMap.value.get(row.clusterId) ?? row.clusterId}」的命名空间 ${row.namespace} 分配？（删 RoleBinding，不删命名空间）`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await tenantApi.namespaceDeallocate({ tenantId: row.tenantId, clusterId: row.clusterId, namespace: row.namespace })
    ElMessage.success('已取消分配')
    await loadAllocations()
  } catch {
    /* 拦截器提示 */
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">创建租户</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="name" label="租户名称" min-width="140" />
      <el-table-column label="租户标识 (serviceAccount)" min-width="200">
        <template #default="{ row }">
          <span>{{ row.serviceAccount }}</span>
          <el-tag size="small" type="info" class="sa-tag">K8s SA: tn-{{ row.serviceAccount }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.status === 1" @change="(v: boolean) => onToggle(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmtDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openNamespaces(row)">命名空间</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" @click="onProvision(row)">重新开通</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑租户' : '创建租户'" width="520px">
      <el-form label-width="130px">
        <el-form-item label="租户名称" required>
          <el-input v-model="form.name" placeholder="例如 测试租户A" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="租户标识" required>
          <el-input v-model="form.serviceAccount" placeholder="小写字母/数字/-，例如 coding-test" />
          <div class="form-tip">K8s 中 ServiceAccount 名 = tn- + 该值（platform-system 命名空间下）</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 租户命名空间抽屉：该租户的分配列表 + 分配 / 取消分配 -->
    <el-drawer v-model="drawerVisible" :title="`命名空间 · ${currentTenant?.name ?? ''}`" size="680px">
      <div class="toolbar">
        <el-button type="primary" @click="allocDialogVisible = true">分配命名空间</el-button>
        <el-button @click="loadAllocations">刷新</el-button>
      </div>

      <el-table v-loading="allocLoading" :data="allocations" stripe>
        <el-table-column label="集群" min-width="140">
          <template #default="{ row }">{{ clusterNameMap.get(row.clusterId) ?? row.clusterId }}</template>
        </el-table-column>
        <el-table-column prop="namespace" label="命名空间" min-width="140" />
        <el-table-column label="RBAC 模板" min-width="160">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ templateNameMap.get(row.roleTemplateId ?? '') ?? row.roleTemplateId ?? '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分配时间" width="160">
          <template #default="{ row }">{{ fmtDate(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="onDeallocate(row)">取消分配</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <AllocateNamespaceDialog v-model="allocDialogVisible" :tenant-id="currentTenant?.id" @success="loadAllocations" />
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}
.sa-tag {
  margin-left: 8px;
}
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.4;
}
</style>
