<script setup lang="ts">
/**
 * 角色与权限（Task 20）：左列角色（PLATFORM / TENANT 分组），右列权限勾选树。
 * 权限点：读 /role/list|/permission/list|/role/permission/list = platform:role:read；
 *         写 /role/create|delete|permission/save = platform:role:manage（菜单入口用 manage，
 *         实践中 manage 持有者（admin）同闭包内也有 read）。
 *
 * 关键建模（与后端 RoleService 对齐）：
 * - /permission/list 返回 platform_permission 行，⚠️ code 不唯一（ANY-of：同 code 覆盖多个 URL 行）
 *   → 按 code 聚合为单个勾选项；保存提交 codes（非 ids），后端把 code 的全部行落关联。
 * - scope 不变量：TENANT 角色只能勾 tenant: code，PLATFORM 角色只能勾 platform: code
 *   → 勾选树按所选角色的 scope 过滤顶层 code 族（后端 assertScopeMatches 兜底）；
 *   豁免内置超管 admin——V2026_09_29_2 起它持有资源域 tenant:* code，需可见可重存。
 * - 内置角色（builtIn=1）：不可删、code/scope 不可改（后端无改名端点）；权限集允许 manage 重存 → 仍可勾选保存。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { permissionApi, roleApi } from '@/api'
import type { PlatformPermission, PlatformRole } from '@/types'

const rolesLoading = ref(false)
const roles = ref<PlatformRole[]>([])
const allPerms = ref<PlatformPermission[]>([])
const selectedRoleId = ref('')
const checkedCodes = ref<string[]>([])
const permsLoading = ref(false)
const saving = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const createForm = reactive({ name: '', code: '', description: '', scope: 'TENANT' })

const selectedRole = computed(() => roles.value.find((r) => r.id === selectedRoleId.value) ?? null)
const platformRoles = computed(() => roles.value.filter((r) => r.scope === 'PLATFORM'))
const tenantRoles = computed(() => roles.value.filter((r) => r.scope === 'TENANT'))

/** 权限目录按 code 聚合：一行 = 一个 URL 规则；勾选项 = code（保存时 codes → 后端展开全部行）。
 *  族过滤 = 所选角色 scope 不变量（PLATFORM 角色只见 platform: code，反之亦然）。 */
interface PermNode {
  code: string
  description: string
  ruleCount: number
  sample: string
}
const grouped = computed(() => {
  const scope = selectedRole.value?.scope
  // scope 不变量：TENANT 角色只见 tenant: code，PLATFORM 角色只见 platform: code；
  // 豁免内置超管 admin（V2026_09_29_2 起同时持有资源域 tenant:* code，后端 assertScopeMatches 同步豁免）
  const isAdmin = selectedRole.value?.code === 'admin'
  const prefix = isAdmin ? null : scope === 'TENANT' ? 'tenant:' : scope === 'PLATFORM' ? 'platform:' : null
  // domain → resource（API 域 resource=URL 模式）→ codes
  const byDomain = new Map<string, Map<string, PermNode[]>>()
  const seen = new Map<string, PermNode>()
  for (const p of allPerms.value) {
    if (prefix && !p.code.startsWith(prefix)) continue
    let node = seen.get(p.code)
    if (!node) {
      node = { code: p.code, description: p.description ?? '', ruleCount: 0, sample: p.resource ?? '' }
      seen.set(p.code, node)
      const res = byDomain.get(p.domain ?? 'API') ?? new Map<string, PermNode[]>()
      byDomain.set(p.domain ?? 'API', res)
      ;(res.get(p.resource ?? '-') ?? push(res, p.resource ?? '-')).push(node)
    }
    node.ruleCount += 1
  }
  return [...byDomain.entries()].map(([domain, resMap]) => ({
    domain,
    resources: [...resMap.entries()].map(([resource, nodes]) => ({ resource, nodes })),
  }))
  function push(m: Map<string, PermNode[]>, k: string): PermNode[] {
    const arr: PermNode[] = []
    m.set(k, arr)
    return arr
  }
})

const totalChecked = computed(() => checkedCodes.value.length)

async function loadRoles(): Promise<void> {
  rolesLoading.value = true
  try {
    roles.value = await roleApi.list()
  } finally {
    rolesLoading.value = false
  }
}

async function loadPerms(): Promise<void> {
  try {
    allPerms.value = await permissionApi.list()
  } catch {
    allPerms.value = []
  }
}

async function selectRole(role: PlatformRole): Promise<void> {
  if (selectedRoleId.value === role.id) return
  selectedRoleId.value = role.id
  await loadCheckedFor(role.id)
}

async function loadCheckedFor(roleId: string): Promise<void> {
  permsLoading.value = true
  checkedCodes.value = []
  try {
    checkedCodes.value = await roleApi.permissionList(roleId)
  } catch {
    /* 拦截器提示 */
  } finally {
    permsLoading.value = false
  }
}

function isChecked(code: string): boolean {
  return checkedCodes.value.includes(code)
}

function toggleCode(code: string, checked: boolean): void {
  const set = new Set(checkedCodes.value)
  if (checked) set.add(code)
  else set.delete(code)
  checkedCodes.value = [...set]
}

async function save(): Promise<void> {
  if (!selectedRole.value) return
  saving.value = true
  try {
    await roleApi.permissionSave({ roleId: selectedRole.value.id, permissionCodes: checkedCodes.value })
    ElMessage.success('权限已保存（全量重存）')
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}

async function onDelete(role: PlatformRole): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除角色「${role.name}（${role.code}）」？将级联清理其权限关联。`, '提示', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await roleApi.delete(role.id)
    ElMessage.success('已删除')
    if (selectedRoleId.value === role.id) selectedRoleId.value = ''
    await loadRoles()
  } catch {
    /* 拦截器提示 */
  }
}

async function submitCreate(): Promise<void> {
  if (!createForm.name.trim() || !createForm.code.trim()) {
    ElMessage.warning('角色名称与编码必填')
    return
  }
  creating.value = true
  try {
    const r = await roleApi.create({
      name: createForm.name.trim(),
      code: createForm.code.trim(),
      description: createForm.description.trim() || undefined,
      scope: createForm.scope,
    })
    ElMessage.success('角色已创建')
    createVisible.value = false
    createForm.name = ''
    createForm.code = ''
    createForm.description = ''
    await loadRoles()
    await selectRole(r)
    selectedRoleId.value = r.id
    await loadCheckedFor(r.id)
  } catch {
    /* 拦截器提示 */
  } finally {
    creating.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadPerms()])
})
</script>

<template>
  <div class="role-layout">
    <!-- 左列：角色（PLATFORM / TENANT 分组） -->
    <div class="role-col" v-loading="rolesLoading">
      <div class="role-col-head">
        <span class="role-col-title">角色</span>
        <el-button size="small" type="primary" @click="createVisible = true">新建</el-button>
      </div>
      <div class="role-group">
        <div class="role-group-title">平台角色（PLATFORM）</div>
        <div
          v-for="r in platformRoles"
          :key="r.id"
          class="role-item"
          :class="{ active: r.id === selectedRoleId }"
          @click="selectRole(r)"
        >
          <el-icon v-if="r.builtIn === 1" class="lock"><Lock /></el-icon>
          <span class="role-name">{{ r.name }}</span>
          <el-tag size="small" type="warning" class="scope-badge">平台</el-tag>
          <el-button
            v-if="r.builtIn !== 1"
            link
            type="danger"
            size="small"
            class="role-del"
            @click.stop="onDelete(r)"
            >删除</el-button
          >
        </div>
        <div v-if="!platformRoles.length" class="role-empty">无</div>
      </div>
      <div class="role-group">
        <div class="role-group-title">租户角色（TENANT）</div>
        <div
          v-for="r in tenantRoles"
          :key="r.id"
          class="role-item"
          :class="{ active: r.id === selectedRoleId }"
          @click="selectRole(r)"
        >
          <el-icon v-if="r.builtIn === 1" class="lock"><Lock /></el-icon>
          <span class="role-name">{{ r.name }}</span>
          <el-tag size="small" class="scope-badge">租户</el-tag>
          <el-button
            v-if="r.builtIn !== 1"
            link
            type="danger"
            size="small"
            class="role-del"
            @click.stop="onDelete(r)"
            >删除</el-button
          >
        </div>
        <div v-if="!tenantRoles.length" class="role-empty">无</div>
      </div>
    </div>

    <!-- 右列：权限勾选树（按 domain/resource 分组、code 聚合） -->
    <div class="perm-col">
      <template v-if="selectedRole">
        <div class="perm-head">
          <div class="perm-title-line">
            <span class="perm-title">{{ selectedRole.name }}</span>
            <span class="perm-code">{{ selectedRole.code }}</span>
            <el-tag v-if="selectedRole.builtIn === 1" size="small" type="info">内置（不可删/不可改码；权限可重存）</el-tag>
            <el-tag v-else size="small" type="info">{{ selectedRole.scope === 'TENANT' ? '租户角色' : '平台角色' }}</el-tag>
          </div>
          <div class="perm-actions">
            <span class="perm-count">已选 {{ totalChecked }} 项</span>
            <el-button type="primary" :loading="saving" @click="save">保存权限</el-button>
          </div>
        </div>
        <div class="perm-hint">
          勾选=权限点 code（同 code 覆盖多个 URL 规则，保存时后端按 code 展开全部行）；
          {{ selectedRole.code === 'admin'
            ? '平台管理员可见全部权限族（platform: + tenant:）'
            : selectedRole.scope === 'TENANT' ? '租户角色仅可选 tenant: 权限族' : '平台角色仅可选 platform: 权限族' }}（后端不变量校验）。
        </div>
        <div v-loading="permsLoading" class="perm-body">
          <div v-for="g in grouped" :key="g.domain" class="perm-domain">
            <div class="perm-domain-title">域：{{ g.domain }}</div>
            <div v-for="rs in g.resources" :key="rs.resource" class="perm-resource">
              <div class="perm-resource-title">{{ rs.resource }}</div>
              <div v-for="n in rs.nodes" :key="n.code" class="perm-leaf">
                <el-checkbox :model-value="isChecked(n.code)" @update:model-value="(v: unknown) => toggleCode(n.code, !!v)">
                  <span class="perm-leaf-code">{{ n.code }}</span>
                  <span v-if="n.description" class="perm-leaf-desc">{{ n.description }}</span>
                  <span v-if="n.ruleCount > 1" class="perm-leaf-count">{{ n.ruleCount }} 条 URL 规则</span>
                </el-checkbox>
              </div>
            </div>
          </div>
          <div v-if="!grouped.length && !permsLoading" class="perm-empty">该角色族下暂无可勾权限点</div>
        </div>
      </template>
      <el-empty v-else description="选择左侧角色以编辑权限" />
    </div>

    <el-dialog v-model="createVisible" title="新建角色" width="520px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="例如 只读审计员" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="createForm.code" placeholder="角色 code，全局唯一，例如 audit-reader" />
        </el-form-item>
        <el-form-item label="角色族" required>
          <el-radio-group v-model="createForm.scope">
            <el-radio value="TENANT">TENANT（租户内）</el-radio>
            <el-radio value="PLATFORM">PLATFORM（平台）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.role-layout {
  display: flex;
  gap: 16px;
  align-items: stretch;
  min-height: calc(100vh - 140px);
}
.role-col {
  width: 300px;
  flex-shrink: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-elevated);
  padding: 10px;
  overflow-y: auto;
}
.role-col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 6px 10px;
}
.role-col-title {
  font-weight: 700;
  color: var(--text-1);
}
.role-group-title {
  font-size: 11px;
  letter-spacing: .1em;
  color: var(--text-3);
  padding: 10px 6px 4px;
}
.role-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 8px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-2);
}
.role-item:hover {
  background: var(--panel-hover);
}
.role-item.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.role-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lock {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-3);
}
.scope-badge {
  flex-shrink: 0;
}
.role-del {
  margin-left: auto;
  flex-shrink: 0;
}
.role-empty {
  padding: 6px 8px;
  color: var(--text-3);
  font-size: 12px;
}
.perm-col {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-elevated);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
}
.perm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.perm-title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.perm-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-1);
}
.perm-code {
  font-size: 12px;
  color: var(--text-3);
}
.perm-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.perm-count {
  font-size: 12px;
  color: var(--text-3);
}
.perm-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.5;
}
.perm-body {
  margin-top: 12px;
  flex: 1;
  overflow-y: auto;
}
.perm-domain {
  margin-bottom: 14px;
}
.perm-domain-title {
  font-size: 12px;
  letter-spacing: .1em;
  color: var(--text-3);
  border-bottom: 1px solid var(--border);
  padding-bottom: 4px;
  margin-bottom: 6px;
}
.perm-resource-title {
  font-size: 12px;
  color: var(--text-2);
  font-family: ui-monospace, monospace;
  padding: 6px 0 2px 4px;
}
.perm-leaf {
  padding: 2px 0 2px 12px;
}
.perm-leaf-code {
  font-size: 13px;
}
.perm-leaf-desc {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-3);
}
.perm-leaf-count {
  margin-left: 8px;
  font-size: 11px;
  color: var(--el-color-warning);
}
.perm-empty {
  color: var(--text-3);
  font-size: 13px;
  padding: 20px;
}
</style>
