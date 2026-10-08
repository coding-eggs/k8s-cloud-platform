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
 * - 内置角色（builtIn=1）：不可删、code/scope 不可改（后端无改名端点）；权限集允许 manage 重存 → 仍可勾选保存。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { roleApi } from '@/api'
import type { PlatformPermission, PlatformRole } from '@/types'

const rolesLoading = ref(false)
const roles = ref<PlatformRole[]>([])
/** 当前角色**可分配**的权限点行，来自 POST /role/permission/assignable（随所选角色变化）。
 *  ⚠️ 不在前端按 scope 前缀过滤：可分配范围是授权策略，判据在后端（RoleService.assignablePermissions）。 */
const assignablePerms = ref<PlatformPermission[]>([])
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

/** 权限目录按 code 聚合：**一个勾选项 = 一个 code**（保存时 codes → 后端展开该 code 的全部行）。 */
interface PermRule {
  /** API/K8S 域是 URL 模式，Page 域是前端路由 path */
  resource: string
  action: string
  /** 行级说明（platform_permission.description），可能为空 —— 说明本就属于行，不属于 code */
  description: string
}
interface PermNode {
  code: string
  /** 该 code 覆盖的**全部**行（ANY-of：一个 code 可对应多条 URL）。长度 1 时行内直接显示那条 URL
   *  （含它的说明）；多条时行内只显示条数、点击弹层列出全部 —— 显示首条会误导（哪条在前取决于服务端返回顺序）。
   *  ⚠️ 节点上**不存 code 级说明**：description 是行级字段，多条 URL 的 code 取首条会把"其中一条的说明"
   *  冒充成"这个码的说明"（例：platform:cluster:manage 有 60 条，首条可能是"建集群"，看着像"这个码就是建集群"）。 */
  rules: PermRule[]
}

/** tab 顺序即此数组顺序；与 PermissionView、创建对话框的域下拉同源 */
const DOMAINS = [
  'API'
  // , 'K8S'
  , 'Page']
/** 当前域 tab */
const activeDomain = ref('API')

/** 域 → 该域下的勾选项（**扁平，按 code**）。数据源 = 后端给的可分配集合，前端不再过滤。
 *
 *  <p>为什么不按 resource（URL 模式）分组：勾选单元是 **code**，而一个 code 可以覆盖多条 URL
 *  （ANY-of，最粗的 `platform:cluster:manage` 占 60 条）。按 URL 分组等于把一个 code 随机挂在它
 *  "排序第一条 URL"的标题下 —— 用户在 `/nodes/list` 那个分组里既找不到也想不到它，
 *  看到的只是一堆"只含一条"的分组。URL 明细改为**点条数就地展开**（见模板的 el-popover），
 *  完整核账仍去「权限点」页（那里是行级的）。 */
const nodesByDomain = computed(() => {
  const byDomain = new Map<string, PermNode[]>()
  const seen = new Map<string, PermNode>()
  for (const p of assignablePerms.value) {
    let node = seen.get(p.code)
    if (!node) {
      node = { code: p.code, rules: [] }
      seen.set(p.code, node)
      const domain = p.domain ?? 'API'
      const arr = byDomain.get(domain) ?? []
      byDomain.set(domain, arr)
      arr.push(node)
    }
    node.rules.push({
      resource: p.resource ?? '-',
      action: p.action ?? '-',
      description: p.description ?? '',
    })
  }
  // 服务端只 order by domain, code —— 同 code 内的行序不定，故此处按 (URL, 方法) 定序，
  // 否则每次加载条数对了但顺序会跳（弹层里尤其明显）
  for (const arr of byDomain.values()) {
    for (const n of arr) {
      n.rules.sort((a, b) => a.resource.localeCompare(b.resource) || a.action.localeCompare(b.action))
    }
  }
  return byDomain
})

/** 当前 tab 的勾选项 */
const activeNodes = computed(() => nodesByDomain.value.get(activeDomain.value) ?? [])

/** tab 角标：该域下「已勾/可选」。已勾是跨 tab 的全局状态（checkedCodes 不随 tab 变化），
 *  所以切 tab 不会丢勾选，保存时提交的仍是全部域的并集。 */
const domainTabs = computed(() =>
  DOMAINS.map((d) => {
    const nodes = nodesByDomain.value.get(d) ?? []
    return { domain: d, total: nodes.length, checked: nodes.filter((n) => isChecked(n.code)).length }
  }),
)

/** 换角色后若当前 tab 空（例如从只有 Page 码的角色切到只有 API 码的角色），自动落到第一个有内容的域，
 *  否则会看到一片空白、误以为角色没有权限。 */
function ensureTabHasContent(): void {
  if (activeNodes.value.length) return
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

/** 拉一个角色的两份数据：已勾选的 code（回显）+ 可分配的权限点行（勾选列表）。
 *  可分配集合**随角色变化**（TENANT 只有 tenant: 族），所以不能像以前那样登录后拉一次复用。 */
async function loadForRole(roleId: string): Promise<void> {
  permsLoading.value = true
  checkedCodes.value = []
  assignablePerms.value = []
  try {
    const [codes, perms] = await Promise.all([
      roleApi.permissionList(roleId),
      roleApi.permissionAssignable(roleId),
    ])
    checkedCodes.value = codes
    assignablePerms.value = perms
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
          勾选=权限点 code（同 code 覆盖多个规则，保存时后端按 code 展开全部行）。
          可勾范围由后端下发（POST /role/permission/assignable），前端不自行过滤 ——
          {{ selectedRole.scope === 'TENANT'
            ? '租户角色只能勾 tenant: 权限族（可向上够到平台族是不变量禁止的）。'
            : '平台角色两族都可勾。注意：给平台角色勾租户码 = 授权它对「任何」租户做那件事（无租户上下文的 token 走代管路径，边界只剩命名空间分配表），不是"只在某个租户里"。' }}
          <br />
          一行=一个 code（按 code 平铺，不按 URL 分组 —— 一个 code 可覆盖多条 URL，按 URL 分组会把它挂到其中一条下面）。
          行内灰色小字：<b>只覆盖一条 URL 的码</b>直接显示那条 URL 和它的说明；<b>覆盖多条</b>的只显示条数，
          <b>点条数可展开看全部</b>（含每行的方法与说明；最粗的 platform:cluster:manage 占 60 条）。
          多条的码行内不给说明 —— 说明是行级的，挂首条会被读成"这个码就是干那件事"。
          按 URL 反查哪个码覆盖它，去「权限点」页。
          <br />
          上方 tab 按<b>域</b>切换，角标是「本域已勾 / 本域可选」；<b>勾选跨 tab 保留</b>，保存提交的是所有域的并集。
          <b>域：Page</b> 是<b>页面可见性</b>（resource = 前端路由 path），与 <b>域：API</b> 的「接口授权」相互独立：
          取消 Page 只会让侧边栏入口消失，取消 API 会让页面能进但操作被拒。页面清单与命名规则见 src/pageCodes.ts。
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
          <!-- 全部域统一：**扁平列表，一行一个 code**（勾选单元就是 code）。
               不按 resource/route 分组的理由见 script 里 nodesByDomain 的注释。
               行内次要信息：单条 URL 的码显示那条 URL + 它的说明；多条只显示条数（点开看明细）。 -->
          <div v-for="n in activeNodes" :key="n.code" class="perm-leaf">
            <el-checkbox :model-value="isChecked(n.code)" @update:model-value="(v: unknown) => toggleCode(n.code, !!v)">
              <span class="perm-leaf-code">{{ n.code }}</span>
              <!-- 说明只在「该 code 就对应这一条 URL」时行内显示：说明是**行级**字段，
                   多条 URL 的 code 挂上首条说明会被读成"这个码就是干那件事"（分配时误导）。
                   多条的说明在弹层里逐行显示，那里每条 URL 配自己的说明，不会张冠李戴。 -->
              <span v-if="n.rules.length === 1 && n.rules[0]?.description" class="perm-leaf-desc">
                {{ n.rules[0].description }}
              </span>
            </el-checkbox>
            <!-- URL 明细放在 checkbox 的 label 之外：label 内的任何点击都会连带勾选/取消，交互会打架。
                 ⚠️ 弹层内容是本组件的 slot 节点，带 data-v 作用域，所以 scoped 样式能命中（与 el-dialog 内部节点不同）。 -->
            <el-popover
              v-if="n.rules.length > 1"
              placement="right-start"
              trigger="click"
              :width="860"
            >
              <template #reference>
                <span class="perm-leaf-count">{{ n.rules.length }} 条 URL 规则</span>
              </template>
              <div class="perm-rules-head">
                <span class="perm-rules-code">{{ n.code }}</span>
                <span class="perm-rules-count">{{ n.rules.length }} 条规则</span>
              </div>
              <div class="perm-rules-list">
                <div v-for="r in n.rules" :key="`${r.action} ${r.resource}`" class="perm-rules-row">
                  <span class="perm-rules-method">{{ r.action }}</span>
                  <span class="perm-rules-path">{{ r.resource }}</span>
                  <span v-if="r.description" class="perm-rules-desc">{{ r.description }}</span>
                </div>
              </div>
            </el-popover>
            <span v-else-if="n.rules.length" class="perm-leaf-route">{{ n.rules[0]?.resource }}</span>
          </div>
          <div v-if="!activeNodes.length && !permsLoading" class="perm-empty">该域下暂无可勾权限点</div>
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
.perm-leaf {
  padding: 2px 0 2px 12px;
  /* 行内次要信息（URL / 条数）与 checkbox 同排 */
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}
/* EP 的 el-checkbox 自带 margin-right: 30px，flex 下会把后面的小字推远 */
.perm-leaf :deep(.el-checkbox) {
  margin-right: 8px;
}
.perm-leaf-code {
  font-size: 13px;
}
.perm-leaf-desc {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-3);
}
/* 行内次要信息：单条 URL 的码显示那条 URL（API 域是 URL 模式，Page 域是 route path） */
.perm-leaf-route {
  margin-left: 8px;
  font-size: 11.5px;
  font-family: ui-monospace, monospace;
  color: var(--text-3);
  opacity: .8;
}
/* 多 URL 的码：条数是个开关，点开列全部规则。虚线下划线是"可点"的提示
   （没有它这行字看起来和左边的说明一样只是文本） */
.perm-leaf-count {
  margin-left: 8px;
  font-size: 11.5px;
  color: var(--el-color-warning);
  cursor: pointer;
  border-bottom: 1px dashed currentColor;
}
.perm-leaf-count:hover {
  color: var(--el-color-primary);
}
/* ---- 「N 条 URL 规则」弹层 ----
   最粗的码有 60 条，要能扫读而不是"一片小字"：字号提到 13px、行间给足、斑马纹 + 悬停高亮，
   方法做成固定宽的小胶囊，让 URL 列左边界对齐。 */
.perm-rules-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
}
.perm-rules-code {
  font-family: ui-monospace, monospace;
  font-size: 12.5px;
  color: var(--text-2);
  word-break: break-all;
}
.perm-rules-count {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--text-3);
}
/* 60 条必须有滚动上限（380px ≈ 11 行，超出的滚） */
.perm-rules-list {
  margin-top: 6px;
  max-height: 380px;
  overflow-y: auto;
}
.perm-rules-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 7px 8px;
  border-radius: 6px;
  line-height: 1.55;
}
.perm-rules-row:nth-child(even) {
  background: var(--panel-hover);
}
.perm-rules-row:hover {
  background: var(--accent-soft);
}
.perm-rules-method {
  flex: 0 0 62px;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--bg-elevated);
  text-align: center;
  font-family: ui-monospace, monospace;
  font-size: 11.5px;
  color: var(--text-2);
}
.perm-rules-path {
  flex: 1 1 auto;
  min-width: 0;
  font-family: ui-monospace, monospace;
  font-size: 13px;
  color: var(--text-1);
  word-break: break-all;
}
/* 行级说明：右对齐挂在 URL 后面，长说明最多占 42% 宽，不把 URL 挤没 */
.perm-rules-desc {
  flex: 0 1 auto;
  max-width: 42%;
  text-align: right;
  font-size: 12px;
  color: var(--text-3);
  word-break: break-word;
}
.perm-empty {
  color: var(--text-3);
  font-size: 13px;
  padding: 20px;
}
</style>
