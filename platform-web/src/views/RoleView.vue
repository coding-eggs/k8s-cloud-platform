<script setup lang="ts">
/**
 * 角色与权限（Task 20）：左列角色（PLATFORM / TENANT 分组），右列权限勾选列表（按域分 tab，域内按 code 平铺）。
 * 权限点：读 /role/list|/permission/list|/role/permission/list = platform:role:read；
 *         写 /role/create|delete|permission/save = platform:role:manage（菜单入口用 manage，
 *         实践中 manage 持有者（admin）同闭包内也有 read）。
 *
 * 关键建模（与后端 RoleService 对齐）：
 * - /permission/list 返回 platform_permission 行，⚠️ code 不唯一（ANY-of：同 code 覆盖多个 URL 行）
 *   → 按 code 聚合为单个勾选项（**一行一个 code，不按 resource/URL 分组**）；
 *   保存提交 codes（非 ids），后端把 code 的全部行落关联。
 * - **可勾范围由后端给**：POST /role/permission/assignable 返回该角色可分配的权限点行，
 *   本页只渲染它、不按 code 前缀自行过滤（可分配范围是授权策略，前端再判一份必然与后端漂移）。
 *   后端规则：TENANT 角色只能持 tenant: 族；PLATFORM 角色两族都可（2026-10-08 起，详见 RoleService）。
 * - 内置角色（builtIn=1）：不可删、code/scope 不可改（名称 / 描述可改，/role/update）；权限集允许 manage 重存 → 仍可勾选保存。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { usePaletteAction } from '@/composables/usePaletteAction'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { roleApi } from '@/api'
import type { PermissionGroup, PlatformRole } from '@/types'
import { useAssignablePermissions } from '@/composables/usePermissionCatalog'
import { usePermissionSearch } from '@/composables/usePermissionSearch'
import PermissionGroupList from '@/components/permission/PermissionGroupList.vue'

const rolesLoading = ref(false)
const roles = ref<PlatformRole[]>([])
/** 当前角色**可分配**的权限点，按 code 分组后来自 POST /role/permission/assignable。
 *  ⚠️ 不在前端按 scope 前缀过滤：可分配范围是授权策略，判据在后端（RoleService.assignablePermissions）。
 *  同一 scope 的集合相同，缓存按 scope 复用（见 useAssignablePermissions），切角色不重复拉全量。 */
const assignableGroups = ref<PermissionGroup[]>([])
const { ensure: ensureAssignable } = useAssignablePermissions()
/** 勾选列表的本地搜索（code / URL / 说明） */
const permKeyword = ref('')
const { active: permSearchActive, groupMatches } = usePermissionSearch(permKeyword)
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

/** tab 顺序即此数组顺序；与 PermissionView、创建对话框的域下拉同源 */
const DOMAINS = [
  'API'
  // , 'K8S'
  , 'Page']
/** 当前域 tab */
const activeDomain = ref('API')

/** 域 → 该域下可勾选的 code 组（组内是规则明细，展开看）。
 *  勾选单元仍是 **code**；分组只改变展示，保存仍提交 codes，后端按 code 展开全部行。 */
const groupsByDomain = computed(() => {
  const byDomain = new Map<string, PermissionGroup[]>()
  for (const g of assignableGroups.value) {
    const domain = g.domain ?? 'API'
    const arr = byDomain.get(domain) ?? []
    byDomain.set(domain, arr)
    arr.push(g)
  }
  return byDomain
})

/** 当前 tab 的勾选项（已应用本地搜索） */
const activeGroups = computed(() => (groupsByDomain.value.get(activeDomain.value) ?? []).filter(groupMatches))

/** tab 角标：该域下「已勾/可选」组数。已勾是跨 tab 的全局状态（checkedCodes 不随 tab 变化），
 *  所以切 tab 不会丢勾选，保存时提交的仍是全部域的并集。 */
const domainTabs = computed(() =>
  DOMAINS.map((d) => {
    const groups = groupsByDomain.value.get(d) ?? []
    return { domain: d, total: groups.length, checked: groups.filter((g) => isChecked(g.code)).length }
  }),
)

/** 换角色后若当前 tab 空（例如从只有 Page 码的角色切到只有 API 码的角色），自动落到第一个有内容的域，
 *  否则会看到一片空白、误以为角色没有权限。搜索中不自动切 tab（空结果是搜索造成的，不是角色没有权限）。 */
function ensureTabHasContent(): void {
  if (permSearchActive.value) return
  if (activeGroups.value.length) return
  const first = domainTabs.value.find((t) => t.total > 0)
  if (first) activeDomain.value = first.domain
}

const totalChecked = computed(() => checkedCodes.value.length)

async function loadRoles(): Promise<void> {
  rolesLoading.value = true
  try {
    roles.value = await roleApi.list()
  } finally {
    rolesLoading.value = false
  }
}

async function selectRole(role: PlatformRole): Promise<void> {
  if (selectedRoleId.value === role.id) return
  selectedRoleId.value = role.id
  await loadForRole(role.id)
}

/** 拉一个角色的两份数据：已勾选的 code（回显）+ 可分配的权限点（勾选列表）。
 *  可分配集合按角色 scope 决定（TENANT 只有 tenant: 族），同一 scope 的内容相同 → 走模块级缓存，
 *  切角色/切页不重复拉全量。 */
async function loadForRole(roleId: string): Promise<void> {
  permsLoading.value = true
  checkedCodes.value = []
  assignableGroups.value = []
  try {
    const scope = roles.value.find((r) => r.id === roleId)?.scope ?? 'PLATFORM'
    const [codes, groups] = await Promise.all([
      roleApi.permissionList(roleId),
      ensureAssignable(scope, roleId),
    ])
    checkedCodes.value = codes
    assignableGroups.value = groups
  } catch {
    /* 拦截器提示；失败即两侧都空，页面显示"该域下暂无可勾权限点" */
  } finally {
    permsLoading.value = false
    ensureTabHasContent()
  }
}

function isChecked(code: string): boolean {
  return checkedCodes.value.includes(code)
}

function onToggleCode(code: string): void {
  const set = new Set(checkedCodes.value)
  if (set.has(code)) set.delete(code)
  else set.add(code)
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

// ---- 编辑名称 / 描述（编码、角色族不可改；内置角色也可改名） ----
const editVisible = ref(false)
const editing = ref(false)
const editForm = reactive({ id: '', name: '', description: '' })

function openEdit(role: PlatformRole): void {
  editForm.id = role.id
  editForm.name = role.name
  editForm.description = role.description ?? ''
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  if (!editForm.name.trim()) {
    ElMessage.warning('角色名称必填')
    return
  }
  editing.value = true
  try {
    await roleApi.update({ id: editForm.id, name: editForm.name.trim(), description: editForm.description.trim() })
    ElMessage.success('已保存')
    editVisible.value = false
    await loadRoles()   // 右侧标题跟着 selectedRole（computed 自 roles）刷新，已勾选的权限不受影响
  } catch {
    /* 拦截器提示 */
  } finally {
    editing.value = false
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
    await selectRole(r) // 选中新角色并拉取它的「已勾选 + 可分配」两份数据
  } catch {
    /* 拦截器提示 */
  } finally {
    creating.value = false
  }
}

// 权限目录不再在挂载时预取：可分配集合随角色变化（loadForRole）
onMounted(async () => {
  await loadRoles()
})

// 命令面板（Ctrl+K）页内功能：路由 meta.actions 的落点，条件与页面上对应按钮的禁用条件一致
usePaletteAction({
  create: { run: () => { createVisible.value = true } },
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

    <!-- 右列：权限勾选列表（按域分 tab，域内按 code 平铺） -->
    <div class="perm-col">
      <template v-if="selectedRole">
        <div class="perm-head">
          <div>
          <div class="perm-title-line">
            <span class="perm-title">{{ selectedRole.name }}</span>
            <span class="perm-code">{{ selectedRole.code }}</span>
            <el-tag v-if="selectedRole.builtIn === 1" size="small" type="info">内置（不可删/不可改码；权限可重存）</el-tag>
            <el-tag v-else size="small" type="info">{{ selectedRole.scope === 'TENANT' ? '租户角色' : '平台角色' }}</el-tag>
            <el-button link type="primary" size="small" @click="openEdit(selectedRole)">编辑名称</el-button>
          </div>
          <div v-if="selectedRole.description" class="perm-desc">{{ selectedRole.description }}</div>
          </div>
          <div class="perm-actions">
            <span class="perm-count">已选 {{ totalChecked }} 项</span>
            <el-button type="primary" :loading="saving" @click="save">保存权限</el-button>
          </div>
        </div>
        <div class="perm-hint">
          勾选=权限点 code（同 code 覆盖多个规则，保存时后端按 code 展开全部行）。
          可勾范围由后端下发（POST /role/permission/assignable），前端不自行过滤 ——
          {{ selectedRole.scope === 'TENANT'
            ? '租户角色只能勾 tenant: 权限族（可向上够到平台族是不变量禁止的）。'
            : '平台角色两族都可勾。注意：给平台角色勾租户码 = 授权它对「任何」租户做那件事（无租户上下文的 token 走代管路径，边界只剩命名空间分配表），不是"只在某个租户里"。' }}
          <br />
          一行=一个 code，默认折叠；展开后是该码覆盖的规则明细（含每行的方法与说明）。
          要按 URL 反查哪个码覆盖它，去「权限点」页。
          <br />
          上方 tab 按<b>域</b>切换，角标是「本域已勾 / 本域可选」；<b>勾选跨 tab 保留</b>，保存提交的是所有域的并集。
          <b>域：Page</b> 是<b>页面可见性</b>（resource = 前端路由 path），与 <b>域：API</b> 的「接口授权」相互独立：
          取消 Page 只会让侧边栏入口消失，取消 API 会让页面能进但操作被拒。页面清单与命名规则见 src/pageCodes.ts。
        </div>
        <div class="perm-toolbar">
          <el-input v-model="permKeyword" clearable size="small" class="perm-search" placeholder="搜索 code / URL / 说明">
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <span v-if="permSearchActive" class="perm-search-hint">命中 {{ activeGroups.length }} 组</span>
        </div>
        <el-tabs v-model="activeDomain" class="perm-tabs">
          <el-tab-pane v-for="t in domainTabs" :key="t.domain" :name="t.domain">
            <template #label>
              <span class="perm-tab-label">
                {{ t.domain }}
                <span class="perm-tab-count" :class="{ 'is-zero': !t.total }">{{ t.checked }}/{{ t.total }}</span>
              </span>
            </template>
          </el-tab-pane>
        </el-tabs>
        <div v-loading="permsLoading" class="perm-body">
          <!-- 勾选单元是 code，所以 checkbox 在组头；展开只影响规则明细的渲染。
               默认折叠，首屏只渲染组头（约 177 组），搜索命中时自动展开。 -->
          <PermissionGroupList
            v-if="activeGroups.length"
            :groups="activeGroups"
            :keyword="permKeyword"
            selectable
            :checked-codes="checkedCodes"
            @toggle-code="onToggleCode"
          />
          <div v-else-if="!permsLoading" class="perm-empty">
            {{ permKeyword ? '没有匹配的权限点' : '该域下暂无可勾权限点' }}
          </div>
        </div>
      </template>
      <el-empty v-else description="选择左侧角色以编辑权限" />
    </div>

    <el-dialog v-model="editVisible" title="编辑角色" width="520px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" />
        </el-form-item>
        <div class="form-hint">编码与角色族创建后不可改（编码被按码引用；改角色族会让已勾的权限违反约束）。</div>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

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
  margin-top: 4px;
  flex: 1;
  overflow-y: auto;
}
/* 域 tab：紧贴勾选区上方，与 .perm-body 共享剩余高度 */
.perm-tabs {
  margin-top: 10px;
}
.perm-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.perm-tabs :deep(.el-tabs__item) {
  height: 34px;
  line-height: 34px;
  font-size: 13px;
}
.perm-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.perm-tab-count {
  font-size: 11px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 9px;
  background: var(--panel-hover);
  color: var(--text-3);
}
.perm-tab-count.is-zero {
  opacity: .45;
}
.perm-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}
.perm-search {
  max-width: 320px;
}
.perm-search-hint {
  font-size: 11.5px;
  color: var(--text-3);
}
.perm-empty {
  color: var(--text-3);
  font-size: 13px;
  padding: 20px;
}
.perm-desc {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 4px;
}
.form-hint {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-3);
  padding-left: 80px;
}
</style>
