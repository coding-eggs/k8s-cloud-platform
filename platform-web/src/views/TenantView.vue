<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { platformUserApi, tenantApi } from '@/api'
import type { PlatformTenant, PlatformUser } from '@/types'
import { fmtDate } from '@/utils/format'
import TenantDetailPanel from '@/components/TenantDetailPanel.vue'

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
const form = reactive({ id: '', name: '', serviceAccount: '', status: 1, ownerUserId: '' })

// owner 选择器（数据源 /user/list，同权限 platform:user:manage；admin 代管轨可用）
const ownerCandidates = ref<PlatformUser[]>([])
const ownerLoading = ref(false)
async function loadOwnerCandidates(): Promise<void> {
  ownerLoading.value = true
  try {
    ownerCandidates.value = await platformUserApi.list()
  } catch {
    ownerCandidates.value = []
  } finally {
    ownerLoading.value = false
  }
}

function openCreate(): void {
  isEdit.value = false
  form.id = ''
  form.name = ''
  form.serviceAccount = ''
  form.status = 1
  form.ownerUserId = ''
  dialogVisible.value = true
  void loadOwnerCandidates()
}

function openEdit(row: PlatformTenant): void {
  isEdit.value = true
  form.id = row.id
  form.name = row.name
  form.serviceAccount = row.serviceAccount
  form.status = row.status
  form.ownerUserId = ''
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
      await tenantApi.create({
        name: form.name.trim(),
        serviceAccount: form.serviceAccount.trim(),
        status: form.status,
        ownerUserId: form.ownerUserId || undefined, // 非空：建租即加成员并授内置 tenant-admin（后端 TenantService）
      })
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

// ---- 详情抽屉（Task 20：el-tabs 命名空间分配 / 成员与角色，委托 TenantDetailPanel，admin 代管轨） ----
const drawerVisible = ref(false)
const currentTenant = ref<PlatformTenant | null>(null)

function openDetail(row: PlatformTenant): void {
  currentTenant.value = row
  drawerVisible.value = true
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
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
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
        <el-form-item v-if="!isEdit" label="租户管理员(owner)">
          <el-select
            v-model="form.ownerUserId"
            filterable
            clearable
            :loading="ownerLoading"
            placeholder="可不选；选择后建租户即加成员并授 tenant-admin"
            class="owner-select"
          >
            <el-option
              v-for="u in ownerCandidates"
              :key="u.id"
              :label="`${u.username}${u.displayName ? ' · ' + u.displayName : ''}`"
              :value="u.id"
            />
          </el-select>
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

    <!-- 租户详情抽屉：命名空间分配 / 成员与角色（TenantDetailPanel 复用，代管轨传 delegate） -->
    <el-drawer v-model="drawerVisible" :title="`租户详情 · ${currentTenant?.name ?? ''}`" size="760px">
      <TenantDetailPanel
        v-if="currentTenant"
        :tenant-id="currentTenant.id"
        :tenant-name="currentTenant.name"
        delegate
      />
    </el-drawer>
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
.owner-select {
  width: 100%;
}
</style>
