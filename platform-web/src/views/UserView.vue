<script setup lang="ts">
/**
 * 用户管理（Task 20）：平台用户列表 + 新建 + 平台角色授予/回收。
 * 权限点 platform:user:manage（/user/list|create|get|platformRole/grant|platformRole/revoke 同码，见 seed）。
 *
 * ⚠️ 已知限制（后端无「查某用户已有平台角色」端点，/user/me 只含自己）：
 *   平台角色对话框是「盲操作」——列出 PLATFORM 角色供授予/回收，但不显示该用户当前已持有哪些
 *   （不假装有当前态）。重复授予后端幂等；回收未持有的角色后端静默无操作。
 *   roadmap：补 /user/platformRole/list 端点后回显。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { platformUserApi, roleApi } from '@/api'
import type { PlatformRole, PlatformUser } from '@/types'
import { fmtDate } from '@/utils/format'

const loading = ref(false)
const list = ref<PlatformUser[]>([])
const keyword = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await platformUserApi.list()
  } finally {
    loading.value = false
  }
}

/** 客户端过滤：用户名/显示名/邮箱（后端 /user/list 全量返回） */
const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return list.value
  return list.value.filter(
    (u) =>
      u.username.toLowerCase().includes(k) ||
      (u.displayName ?? '').toLowerCase().includes(k) ||
      (u.email ?? '').toLowerCase().includes(k),
  )
})

// ---- 新建对话框 ----
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ username: '', password: '', displayName: '', email: '', status: 1 })

function openCreate(): void {
  form.username = ''
  form.password = ''
  form.displayName = ''
  form.email = ''
  form.status = 1
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('用户名与密码必填')
    return
  }
  saving.value = true
  try {
    await platformUserApi.create({
      username: form.username.trim(),
      password: form.password,
      displayName: form.displayName.trim() || undefined,
      email: form.email.trim() || undefined,
      status: form.status,
    })
    ElMessage.success('创建成功')
    dialogVisible.value = false
    await load()
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}

// ---- 平台角色授予/回收（盲操作，见文件头限制说明） ----
const roleDialogVisible = ref(false)
const roleTarget = ref<PlatformUser | null>(null)
const platformRoles = ref<PlatformRole[]>([])
const rolesLoading = ref(false)
const grantRoleId = ref('')
const revokeRoleId = ref('')
const granting = ref(false)

async function openPlatformRoles(row: PlatformUser): Promise<void> {
  roleTarget.value = row
  grantRoleId.value = ''
  revokeRoleId.value = ''
  roleDialogVisible.value = true
  rolesLoading.value = true
  try {
    // /role/list 归 platform:role:read（admin 闭包含之）；失败仅提示，不阻塞对话框
    platformRoles.value = (await roleApi.list()).filter((r) => r.scope === 'PLATFORM')
  } catch {
    platformRoles.value = []
  } finally {
    rolesLoading.value = false
  }
}

async function submitRoleAction(kind: 'grant' | 'revoke'): Promise<void> {
  const roleId = kind === 'grant' ? grantRoleId.value : revokeRoleId.value
  if (!roleTarget.value || !roleId) {
    ElMessage.warning('请先选择角色')
    return
  }
  granting.value = true
  try {
    const payload = { userId: roleTarget.value.id, roleId }
    if (kind === 'grant') await platformUserApi.platformRoleGrant(payload)
    else await platformUserApi.platformRoleRevoke(payload)
    ElMessage.success(kind === 'grant' ? '已授予（重复授予幂等）' : '已回收')
    if (kind === 'grant') grantRoleId.value = ''
    else revokeRoleId.value = ''
  } catch {
    /* 拦截器提示 */
  } finally {
    granting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建用户</el-button>
      <el-button @click="load">刷新</el-button>
      <el-input v-model="keyword" placeholder="搜索用户名 / 显示名 / 邮箱" clearable class="search" />
    </div>

    <el-table v-loading="loading" :data="filtered" stripe>
      <el-table-column prop="username" label="用户名" min-width="130" />
      <el-table-column label="显示名" min-width="120">
        <template #default="{ row }">{{ row.displayName || '-' }}</template>
      </el-table-column>
      <el-table-column label="邮箱" min-width="170">
        <template #default="{ row }">{{ row.email || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="160">
        <template #default="{ row }">{{ fmtDate(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmtDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openPlatformRoles(row)">平台角色</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建用户" width="520px">
      <el-form label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" placeholder="登录名，全局唯一" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="BCrypt 加密存储" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 平台角色：无回显端点 → 授予/回收两个独立选择器，盲操作（见 script 头部说明） -->
    <el-dialog v-model="roleDialogVisible" :title="`平台角色 · ${roleTarget?.username ?? ''}`" width="560px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="后端暂无「用户已持平台角色」查询端点，此处不显示当前持有情况；授予幂等、回收未持有不报错。"
        class="role-alert"
      />
      <div v-loading="rolesLoading" class="role-rows">
        <div class="role-row">
          <span class="role-row-label">授予</span>
          <el-select v-model="grantRoleId" placeholder="选择 PLATFORM 角色" filterable class="role-row-select">
            <el-option v-for="r in platformRoles" :key="r.id" :label="`${r.name}（${r.code}）`" :value="r.id" />
          </el-select>
          <el-button type="primary" :loading="granting" :disabled="!grantRoleId" @click="submitRoleAction('grant')">
            授予
          </el-button>
        </div>
        <div class="role-row">
          <span class="role-row-label">回收</span>
          <el-select v-model="revokeRoleId" placeholder="选择 PLATFORM 角色" filterable class="role-row-select">
            <el-option v-for="r in platformRoles" :key="r.id" :label="`${r.name}（${r.code}）`" :value="r.id" />
          </el-select>
          <el-button type="warning" :loading="granting" :disabled="!revokeRoleId" @click="submitRoleAction('revoke')">
            回收
          </el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="roleDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
  display: flex;
  gap: 12px;
  align-items: center;
}
.search {
  width: 260px;
  margin-left: auto;
}
.role-alert {
  margin-bottom: 14px;
}
.role-rows {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.role-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.role-row-label {
  width: 40px;
  flex-shrink: 0;
  color: var(--text-2);
  font-size: 13px;
}
.role-row-select {
  flex: 1;
}
</style>
