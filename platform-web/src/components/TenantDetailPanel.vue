<script setup lang="ts">
/**
 * 租户详情面板（Task 20）：命名空间分配 + 成员与角色 两个 tab。
 * 复用于两处（组件复用，靠 props + 权限位区分能力面）：
 *   1. TenantView（/tenants，代管轨）：admin 从列表选租户，drawer 内使用，delegate=true；
 *   2. /tenants/detail（自管轨，Task 18 裁定）：tenantId 强制取当前租户上下文，delegate=false。
 *
 * 后端边界（无需前端重复判定）：
 * - /tenant/member/*：TenantContextResolver 裁决 —— 自管 token 锁定本租户（tenantId 必与 hat 一致，
 *   否则 TENANT_MISMATCH），代管必传 tenantId；
 * - /tenant/namespace/list：服务层按 token 租户强制覆盖入参（自管只见本租户分配）。
 *
 * ⚠️ 自管轨能力缺口（后端 seed/端点未覆盖，前端如实降级，不假装可用）：
 * - 角色目录 /role/list 仅 platform:role:read → 租户管理员拿不到 TENANT 角色列表 ⇒ 授予/回收禁用；
 * - 用户搜索 /user/list 仅 platform:user:manage ⇒ 「添加成员」在自管轨禁用。
 *   roadmap：/role/list、/user/list 补 tenant:member:manage ANY-of 行，或自管专用端点。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, platformUserApi, roleApi, templateApi, tenantApi } from '@/api'
import type { K8sCluster, NamespaceAllocation, PlatformRole, PlatformUser, RbacTemplate, TenantMemberView } from '@/types'
import { fmtDate } from '@/utils/format'
import AllocateNamespaceDialog from '@/components/AllocateNamespaceDialog.vue'
import { usePermission } from '@/stores/permission'

const props = defineProps<{
  tenantId: string
  tenantName: string
  /** 代管轨（admin）：可分配命名空间/添加成员；自管轨靠权限位自然收敛 */
  delegate?: boolean
}>()

const perm = usePermission()
const activeTab = ref('ns')

const can = (c: string) => perm.has(c)
const canManageMembers = computed(() => perm.hasAny(['tenant:member:manage', 'platform:member:manage']))

// ==================== 命名空间分配 tab ====================
const allocLoading = ref(false)
const allocations = ref<NamespaceAllocation[]>([])
const clusters = ref<K8sCluster[]>([])
const templates = ref<RbacTemplate[]>([])
const allocDialogVisible = ref(false)
const clusterNameMap = computed(() => new Map(clusters.value.map((c) => [c.clusterId, c.clusterName])))
const templateNameMap = computed(() => new Map(templates.value.map((t) => [t.id, t.name])))

async function loadAllocations(): Promise<void> {
  if (!props.tenantId) return
  allocLoading.value = true
  try {
    allocations.value = await tenantApi.namespaceList({ tenantId: props.tenantId })
    // 集群名/模板名映射尽力而为：自管轨无 platform:cluster:manage / platform:template:manage
    // → 对应端点 403，降级显示原始 id（拦截器提示已由 http.ts 统一弹，两拉取失败均吞）
    if (can('platform:cluster:manage') && !clusters.value.length) {
      try {
        clusters.value = await clusterApi.list()
      } catch {
        /* 降级 clusterId */
      }
    }
    if (can('platform:template:manage') && !templates.value.length) {
      try {
        templates.value = await templateApi.list()
      } catch {
        /* 降级 roleTemplateId */
      }
    }
  } finally {
    allocLoading.value = false
  }
}

async function onDeallocate(row: NamespaceAllocation): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消租户「${props.tenantName}」在集群「${clusterNameMap.value.get(row.clusterId) ?? row.clusterId}」的命名空间 ${row.namespace} 分配？（删 RoleBinding，不删命名空间）`,
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

// ==================== 成员与角色 tab ====================
const memberLoading = ref(false)
const members = ref<TenantMemberView[]>([])
/** TENANT 族角色目录：代管轨可读（admin 有 platform:role:read）；自管轨 403 → null=不可用 */
const tenantRoles = ref<PlatformRole[] | null>([])
const roleCatalogFailed = computed(() => tenantRoles.value === null)
const roleNameMap = computed(() => new Map((tenantRoles.value ?? []).map((r) => [r.id, r.name])))

const addDialogVisible = ref(false)
const userLoading = ref(false)
const userKeyword = ref('')
const userCandidates = ref<PlatformUser[]>([])
const memberBusy = ref('')

async function loadMembers(): Promise<void> {
  if (!props.tenantId) return
  memberLoading.value = true
  try {
    members.value = await tenantApi.memberList(props.tenantId)
  } catch {
    members.value = []
  } finally {
    memberLoading.value = false
  }
}

async function loadRoleCatalog(): Promise<void> {
  try {
    const all = await roleApi.list()
    tenantRoles.value = all.filter((r) => r.scope === 'TENANT' && r.status !== 0)
  } catch {
    tenantRoles.value = null // 无权/失败 → 降级：授予角色禁用（见文件头缺口说明）
  }
}

/** 搜索平台用户（后端 /user/list 全量返回，客户端按 用户名/邮箱/显示名 过滤） */
async function searchUsers(): Promise<void> {
  userLoading.value = true
  try {
    const k = userKeyword.value.trim().toLowerCase()
    const all = await platformUserApi.list()
    userCandidates.value = (k
      ? all.filter(
          (u) =>
            u.username.toLowerCase().includes(k) ||
            (u.email ?? '').toLowerCase().includes(k) ||
            (u.displayName ?? '').toLowerCase().includes(k),
        )
      : all
    )
      .filter((u) => !members.value.some((m) => m.userId === u.id))
      .slice(0, 50)
  } catch {
    userCandidates.value = []
  } finally {
    userLoading.value = false
  }
}

function openAddDialog(): void {
  addDialogVisible.value = true
  void searchUsers()
}

async function onAddMember(u: PlatformUser): Promise<void> {
  memberBusy.value = u.id
  try {
    await tenantApi.memberAdd({ tenantId: props.tenantId, userId: u.id })
    ElMessage.success(`已加入成员 ${u.username}`)
    // 角色目录可用时顺手不落默认角色 —— 由操作者显式授予（避免替用户决定权限面）
    addDialogVisible.value = false
    await loadMembers()
  } catch {
    /* 拦截器提示 */
  } finally {
    memberBusy.value = ''
  }
}

const grantPicker = reactive<Record<string, string>>({})

async function onGrantRole(row: TenantMemberView): Promise<void> {
  const roleId = grantPicker[row.userId]
  if (!roleId) {
    ElMessage.warning('请先选择角色')
    return
  }
  memberBusy.value = row.userId
  try {
    await tenantApi.memberRoleGrant({ tenantId: props.tenantId, userId: row.userId, roleId })
    ElMessage.success('已授予角色（重复授予幂等）')
    grantPicker[row.userId] = ''
    await loadMembers()
  } catch {
    /* 拦截器提示 */
  } finally {
    memberBusy.value = ''
  }
}

async function onRevokeRole(row: TenantMemberView, roleId: string): Promise<void> {
  memberBusy.value = row.userId
  try {
    await tenantApi.memberRoleRevoke({ tenantId: props.tenantId, userId: row.userId, roleId })
    ElMessage.success('已回收角色')
    await loadMembers()
  } catch {
    /* 拦截器提示 */
  } finally {
    memberBusy.value = ''
  }
}

async function onRemoveMember(row: TenantMemberView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认将成员「${row.username ?? row.userId}」移出租户「${props.tenantName}」？级联删除其在本租户的角色。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  memberBusy.value = row.userId
  try {
    await tenantApi.memberRemove({ tenantId: props.tenantId, userId: row.userId })
    ElMessage.success('已移除成员')
    await loadMembers()
  } catch {
    /* 拦截器提示 */
  } finally {
    memberBusy.value = ''
  }
}

function refreshAll(): void {
  void loadAllocations()
  void loadMembers()
}

// tenantId 变化（切租户 / 列表换行）→ 重拉两个 tab 数据
watch(() => props.tenantId, refreshAll, { immediate: true })
watch(activeTab, (t) => {
  if (t === 'members') void loadRoleCatalog()
})
</script>

<template>
  <div class="panel">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="命名空间分配" name="ns">
        <div class="toolbar">
          <el-button v-if="can('platform:allocation:manage')" type="primary" @click="allocDialogVisible = true">
            分配命名空间
          </el-button>
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
          <el-table-column v-if="can('platform:allocation:manage')" label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="onDeallocate(row)">取消分配</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="成员与角色" name="members">
        <div class="toolbar">
          <el-tooltip
            :disabled="canManageMembers && can('platform:user:manage')"
            content="自管轨暂无法读取平台用户目录（/user/list 需 platform:user:manage），无法搜索并添加成员"
            placement="top"
          >
            <span>
              <el-button
                type="primary"
                :disabled="!canManageMembers || !can('platform:user:manage')"
                @click="openAddDialog"
                >添加成员</el-button
              >
            </span>
          </el-tooltip>
          <el-button @click="loadMembers">刷新</el-button>
          <el-alert
            v-if="canManageMembers && roleCatalogFailed && activeTab === 'members'"
            class="inline-alert"
            type="warning"
            :closable="false"
            show-icon
            title="无法读取角色目录（/role/list 需 platform:role:read）—— 授予/回收角色暂不可用"
          />
        </div>
        <el-table v-loading="memberLoading" :data="members" stripe>
          <el-table-column prop="username" label="用户名" min-width="120" />
          <el-table-column label="显示名" min-width="110">
            <template #default="{ row }">{{ row.displayName || '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" min-width="220">
            <template #default="{ row }">
              <el-tag v-if="row.owner" size="small" type="danger" class="role-tag">租户管理员</el-tag>
              <el-tag
                v-for="rid in row.roleIds ?? []"
                :key="rid"
                size="small"
                type="info"
                class="role-tag"
              >
                {{ roleNameMap.get(rid) ?? rid }}
                <el-button
                  v-if="canManageMembers && !roleCatalogFailed"
                  link
                  type="danger"
                  size="small"
                  class="revoke-btn"
                  :loading="memberBusy === row.userId"
                  @click="onRevokeRole(row, rid)"
                  >回收</el-button
                >
              </el-tag>
              <span v-if="!(row.roleIds ?? []).length && !row.owner" class="no-role">未授予角色</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canManageMembers" label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-select
                v-model="grantPicker[row.userId]"
                size="small"
                placeholder="选角色"
                class="grant-select"
                :disabled="roleCatalogFailed"
              >
                <el-option v-for="r in tenantRoles" :key="r.id" :label="r.name" :value="r.id" />
              </el-select>
              <el-button
                link
                type="primary"
                size="small"
                :disabled="roleCatalogFailed || !grantPicker[row.userId]"
                :loading="memberBusy === row.userId"
                @click="onGrantRole(row)"
                >授予</el-button
              >
              <el-button
                link
                type="danger"
                size="small"
                :disabled="row.owner"
                :loading="memberBusy === row.userId"
                @click="onRemoveMember(row)"
                >移出</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <AllocateNamespaceDialog v-model="allocDialogVisible" :tenant-id="tenantId" @success="loadAllocations" />

    <!-- 添加成员：搜索平台用户（用户名/邮箱/显示名 客户端过滤，排除已在成员） -->
    <el-dialog v-model="addDialogVisible" title="添加成员" width="560px">
      <div class="toolbar">
        <el-input
          v-model="userKeyword"
          placeholder="搜索用户名 / 显示名 / 邮箱"
          clearable
          class="search"
          @keyup.enter="searchUsers"
        />
        <el-button @click="searchUsers">搜索</el-button>
      </div>
      <el-table v-loading="userLoading" :data="userCandidates" stripe max-height="360">
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column label="显示名" min-width="100">
          <template #default="{ row }">{{ row.displayName || '-' }}</template>
        </el-table-column>
        <el-table-column label="邮箱" min-width="140">
          <template #default="{ row }">{{ row.email || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="memberBusy === row.id" @click="onAddMember(row)">添加</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="addDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 10px;
  align-items: center;
}
.search {
  width: 260px;
}
.inline-alert {
  margin-left: auto;
  max-width: 460px;
}
.role-tag {
  margin-right: 6px;
}
.revoke-btn {
  margin-left: 4px;
  font-size: 11px;
}
.no-role {
  color: var(--text-3);
  font-size: 12px;
}
.grant-select {
  width: 120px;
  margin-right: 6px;
}
</style>
