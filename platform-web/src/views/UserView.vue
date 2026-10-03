<script setup lang="ts">
/**
 * 用户管理（Task 20）：平台用户列表 + 新建 + 平台角色授予/回收（带回显）。
 * 权限点 platform:user:manage（/user/list|create|get|platformRole/grant|revoke|list 同码，见 seed）。
 * 回显走 /user/platformRole/list（V2026_10_01_1）：对话框展示当前持有的 PLATFORM 角色、
 * 授予下拉只列尚未持有的角色；授予/回收成功后重新拉取持有列表。
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

// ---- 平台角色授予/回收（回显：当前持有列表 + 授予下拉只列未持有） ----
const roleDialogVisible = ref(false)
const roleTarget = ref<PlatformUser | null>(null)
const platformRoles = ref<PlatformRole[]>([])
const heldRoles = ref<PlatformRole[]>([])
const heldLoaded = ref(false)
const rolesLoading = ref(false)
const grantRoleId = ref('')
const acting = ref<{ kind: 'grant' | 'revoke'; roleId: string } | null>(null)

/** 授予下拉候选：尚未持有的 PLATFORM 角色（重复授予虽幂等但无意义，不列） */
const grantableRoles = computed(() =>
  platformRoles.value.filter((r) => !heldRoles.value.some((h) => h.id === r.id)),
)

async function reloadHeld(): Promise<void> {
  if (!roleTarget.value) return
  try {
    heldRoles.value = await platformUserApi.platformRoleList(roleTarget.value.id)
    heldLoaded.value = true
  } catch {
    /* 拦截器提示 */
  }
}

async function openPlatformRoles(row: PlatformUser): Promise<void> {
  roleTarget.value = row
  grantRoleId.value = ''
  heldRoles.value = []
  heldLoaded.value = false
  roleDialogVisible.value = true
  rolesLoading.value = true
  try {
    // /role/list 归 platform:role:read（admin 闭包含之）；/user/platformRole/list 与本页面同码
    const [all, held] = await Promise.all([roleApi.list(), platformUserApi.platformRoleList(row.id)])
    platformRoles.value = all.filter((r) => r.scope === 'PLATFORM')
    heldRoles.value = held
    heldLoaded.value = true
  } catch {
    /* 拦截器提示 */
  } finally {
    rolesLoading.value = false
  }
}

async function submitRoleAction(kind: 'grant' | 'revoke', roleId: string): Promise<void> {
  if (!roleTarget.value || !roleId) return
  acting.value = { kind, roleId }
  try {
    const payload = { userId: roleTarget.value.id, roleId }
    if (kind === 'grant') await platformUserApi.platformRoleGrant(payload)
    else await platformUserApi.platformRoleRevoke(payload)
    ElMessage.success(kind === 'grant' ? '已授予' : '已回收')
    if (kind === 'grant') grantRoleId.value = ''
    await reloadHeld()
  } catch {
    /* 拦截器提示 */
  } finally {
    acting.value = null
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

    <!-- 平台角色：回显当前持有（/user/platformRole/list）+ 授予下拉只列未持有 -->
    <el-dialog v-model="roleDialogVisible" :title="`平台角色 · ${roleTarget?.username ?? ''}`" width="560px">
      <div v-loading="rolesLoading">
        <div class="role-section-title">当前持有（{{ heldLoaded ? heldRoles.length : '-' }}）</div>
        <template v-if="heldLoaded">
          <div v-if="heldRoles.length" class="role-held-list">
            <div v-for="r in heldRoles" :key="r.id" class="role-held-item">
              <el-tag size="small">{{ r.name }}（{{ r.code }}）</el-tag>
              <el-tag v-if="r.builtIn === 1" size="small" type="info" class="builtin-badge">内置</el-tag>
              <el-button
                link
                type="danger"
                size="small"
                :loading="acting?.kind === 'revoke' && acting.roleId === r.id"
                @click="submitRoleAction('revoke', r.id)"
              >回收</el-button>
            </div>
          </div>
          <div v-else class="role-held-empty">未持有平台角色</div>
        </template>
        <div v-else-if="!rolesLoading" class="role-held-empty">当前持有加载失败，请关闭后重试</div>

        <div class="role-grant">
          <span class="role-row-label">授予</span>
          <el-select v-model="grantRoleId" placeholder="选择尚未持有的 PLATFORM 角色" filterable class="role-row-select">
            <el-option v-for="r in grantableRoles" :key="r.id" :label="`${r.name}（${r.code}）`" :value="r.id" />
          </el-select>
          <el-button
            type="primary"
            :loading="acting?.kind === 'grant' && acting.roleId === grantRoleId"
            :disabled="!grantRoleId"
            @click="submitRoleAction('grant', grantRoleId)"
          >授予</el-button>
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
.role-section-title {
  font-size: 12px;
  letter-spacing: .08em;
  color: var(--text-3);
  margin-bottom: 8px;
}
.role-held-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 260px;
  overflow-y: auto;
}
.role-held-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.builtin-badge {
  margin-left: 4px;
}
.role-held-empty {
  color: var(--text-3);
  font-size: 13px;
  padding: 6px 0 14px;
}
.role-grant {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
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
