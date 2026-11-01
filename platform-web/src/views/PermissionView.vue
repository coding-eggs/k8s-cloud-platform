<script setup lang="ts">
/**
 * 权限点目录（platform_permission）。
 *
 * <p>展示单位是**权限码（code）**：一行 = 一个 code 覆盖的全部规则，默认折叠；
 * 展开后才渲染行级规则（编辑/删除仍按行）。这样 ~268 条规则变成 ~177 组，首屏 DOM 大幅下降。
 *
 * <p>排障反查：顶部「按 URL 反查」输入实际请求的方法 + 路径，调 `/permission/match`
 * （判据与运行时鉴权 `PermissionRegistry.requiredCodes` 同源），列出覆盖它的码与规则。
 * 纯子串搜索做不到这一点 —— `/workloads/{name}` 这类模式也要能命中 `/workloads/list`。
 *
 * <p>读 `/permission/catalog|match` = platform:role:read；写 create/update/delete/reload = platform:role:manage。
 * 写操作后失效共享缓存并重拉目录；后端提交后已热加载运行时规则。
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { permissionApi } from '@/api'
import type { PermissionGroup, PermissionMatchResult, PermissionRuleView } from '@/types'
import { invalidatePermissionCaches, usePermissionCatalog } from '@/composables/usePermissionCatalog'
import { usePermissionSearch } from '@/composables/usePermissionSearch'
import PermissionGroupList from '@/components/permission/PermissionGroupList.vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

const { groups, loading, ensure, refresh } = usePermissionCatalog()

/** tab 顺序即此数组顺序；与创建对话框的域下拉同源（K8S 为预留域，不展示） */
const DOMAINS = ['API', 'Page']
const activeDomain = ref('API')

const keyword = ref('')
const { active: searchActive, groupMatches } = usePermissionSearch(keyword)

/** 每域的组数（tab 角标）；domain 为空的脏数据按 API 计 */
const countByDomain = computed(() => {
  const m: Record<string, number> = {}
  for (const g of groups.value ?? []) {
    const d = g.domain ?? 'API'
    m[d] = (m[d] ?? 0) + 1
  }
  return m
})

const domainGroups = computed(() => (groups.value ?? []).filter((g) => (g.domain ?? 'API') === activeDomain.value))
const filteredGroups = computed(() => domainGroups.value.filter(groupMatches))
const totalRules = computed(() => (groups.value ?? []).reduce((n, g) => n + g.rules.length, 0))
const totalCodes = computed(() => groups.value?.length ?? 0)

const DOMAIN_HINTS: Record<string, string> = {
  API: 'resource 是 platform-api 的 URL 模式，action 是 HTTP 方法 —— 这是接口鉴权的唯一来源。',
  Page: 'resource 是前端路由 path，action 固定 VIEW；只决定菜单与路由能否进入，不参与接口鉴权。',
}
const domainHint = computed(() => DOMAIN_HINTS[activeDomain.value] ?? '')

// ---------------- 按 URL 反查 ----------------

const lookupOpen = ref(false)
const lookupMethod = ref('GET')
const lookupPath = ref('')
const matchLoading = ref(false)
const match = ref<PermissionMatchResult | null>(null)

const LOOKUP_METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']

function toggleLookup(): void {
  lookupOpen.value = !lookupOpen.value
  if (!lookupOpen.value) {
    match.value = null
    lookupPath.value = ''
  }
}

/** 允许粘贴完整 URL：取 pathname，去掉 query/hash，补前导 /。 */
function normalizePath(raw: string): string {
  let s = raw.trim()
  if (!s) return ''
  if (/^https?:\/\//i.test(s)) {
    try {
      s = new URL(s).pathname
    } catch {
      /* 保留原文，交给后端校验 */
    }
  }
  s = s.split('#')[0]!.split('?')[0]!
  // 平台前端的接口都走 /api 前缀（nginx/vite 转发时剥掉）；权限行里存的是剥掉后的
  // platform-api 路径。粘贴浏览器地址栏 / DevTools 里的完整 URL 时，把这一层也去掉。
  if (s === '/api') s = '/'
  else if (s.startsWith('/api/')) s = s.slice(4)
  if (!s.startsWith('/')) s = `/${s}`
  return s
}

async function runMatch(): Promise<void> {
  const path = normalizePath(lookupPath.value)
  if (!path) {
    ElMessage.warning('请输入请求路径')
    return
  }
  if (path.length > 2048) {
    ElMessage.warning('路径过长（上限 2048）')
    return
  }
  matchLoading.value = true
  try {
    match.value = await permissionApi.match({ method: lookupMethod.value, path })
  } catch {
    /* 拦截器提示 */
  } finally {
    matchLoading.value = false
  }
}

// ---------------- 创建 / 编辑对话框 ----------------

const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const form = reactive({ id: '', domain: 'API', resource: '', action: 'POST', code: '', description: '' })

const API_ACTIONS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', '*']
const PAGE_ACTIONS = ['VIEW']
const actionsForDomain = computed(() => (form.domain === 'Page' ? PAGE_ACTIONS : API_ACTIONS))

function openCreate(): void {
  isEdit.value = false
  form.id = ''
  form.domain = activeDomain.value
  form.resource = ''
  form.action = actionsForDomain.value[0] === 'VIEW' ? 'VIEW' : 'POST'
  form.code = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(group: PermissionGroup, rule: PermissionRuleView): void {
  isEdit.value = true
  form.id = rule.id
  form.domain = group.domain ?? 'API'
  form.resource = rule.resource ?? ''
  form.action = rule.action ?? 'POST'
  form.code = group.code
  form.description = rule.description ?? ''
  dialogVisible.value = true
}

function onDomainChange(d: string): void {
  if (d === 'Page') form.action = 'VIEW'
  else if (!API_ACTIONS.includes(form.action)) form.action = 'POST'
}

async function submit(): Promise<void> {
  const code = form.code.trim()
  if (!code || !(code.startsWith('platform:') || code.startsWith('tenant:'))) {
    ElMessage.warning('权限码必填，且须以 platform: 或 tenant: 开头')
    return
  }
  const resource = form.resource.trim()
  if (!resource) {
    ElMessage.warning('请输入资源（API 域为 URL 模式；Page 域为前端路由 path）')
    return
  }
  if (!resource.startsWith('/')) {
    ElMessage.warning(`${form.domain} 域的 resource 须以 / 开头（API 如 /xxx/**；Page 如 /resources/workloads）`)
    return
  }
  saving.value = true
  try {
    const payload = {
      domain: form.domain,
      resource,
      action: form.action,
      code,
      description: form.description.trim() || undefined,
    }
    if (isEdit.value) {
      await permissionApi.update({ id: form.id, ...payload })
    } else {
      await permissionApi.create(payload)
    }
    ElMessage.success('已保存，授权规则已实时生效')
    dialogVisible.value = false
    activeDomain.value = form.domain
    keyword.value = '' // 清掉搜索，确保刚保存的组可见
    invalidatePermissionCaches()
    await refresh()
  } catch {
    /* 拦截器提示（含裸端点保护拒绝的端点清单） */
  } finally {
    saving.value = false
  }
}

// ---------------- 行操作 / 刷新 ----------------

async function onDelete(group: PermissionGroup, rule: PermissionRuleView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除权限行「${rule.resource ?? '-'}（${rule.action ?? '-'}）」？若它是某端点的唯一覆盖行，后端会拒绝并列出受影响端点。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await permissionApi.delete(rule.id)
    ElMessage.success('已删除，授权规则已实时生效')
    invalidatePermissionCaches()
    await refresh()
  } catch {
    /* 拦截器提示 */
  }
}

const reloading = ref(false)
async function onReload(): Promise<void> {
  reloading.value = true
  try {
    await permissionApi.reload()
    ElMessage.success('运行时规则已与数据库对齐')
    invalidatePermissionCaches()
    await refresh()
  } catch {
    /* 拦截器提示 */
  } finally {
    reloading.value = false
  }
}

async function onRefresh(): Promise<void> {
  invalidatePermissionCaches()
  await refresh()
}

// ---------------- 键盘 ----------------

const searchInput = ref<{ focus: () => void } | null>(null)

function onKeydown(e: KeyboardEvent): void {
  const t = e.target as HTMLElement | null
  const typing =
    !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)
  if (e.key === '/' && !typing) {
    e.preventDefault()
    searchInput.value?.focus()
    return
  }
  if (e.key === 'Escape') {
    if (match.value) {
      match.value = null
      return
    }
    if (keyword.value) {
      keyword.value = ''
      return
    }
    if (lookupOpen.value) lookupOpen.value = false
  }
}

onMounted(() => {
  void ensure()
  window.addEventListener('keydown', onKeydown)
})
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div>
    <PageHeader title="权限点" description="按权限码折叠；排障时可输入实际请求路径反查覆盖关系">
      <el-button type="primary" @click="openCreate">创建权限点</el-button>
      <el-button @click="onRefresh">刷新</el-button>
      <el-button :loading="reloading" @click="onReload">重载规则</el-button>
    </PageHeader>

    <div class="toolbar">
      <el-input
        ref="searchInput"
        v-model="keyword"
        clearable
        class="search"
        placeholder="搜索权限码 / URL 模式 / 说明（空格分隔=同时满足）"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-tabs v-model="activeDomain" class="domain-tabs">
        <el-tab-pane v-for="d in DOMAINS" :key="d" :name="d">
          <template #label>
            <span class="tab-label">
              {{ d }}
              <span class="tab-count" :class="{ 'is-zero': !(countByDomain[d] ?? 0) }">
                {{ countByDomain[d] ?? 0 }} 组
              </span>
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>
      <el-button plain :type="lookupOpen ? 'primary' : undefined" @click="toggleLookup">按 URL 反查</el-button>
    </div>

    <div v-if="lookupOpen" class="lookup">
      <el-select v-model="lookupMethod" class="lookup-method">
        <el-option v-for="m in LOOKUP_METHODS" :key="m" :label="m" :value="m" />
      </el-select>
      <el-input
        v-model="lookupPath"
        class="lookup-path"
        placeholder="/workloads/list（也可粘贴完整 URL）"
        @keyup.enter="runMatch"
      />
      <el-button type="primary" :loading="matchLoading" @click="runMatch">反查</el-button>
      <span class="lookup-hint">按 Ant 模式匹配，与运行时判权同口径</span>
    </div>

    <div class="domain-hint">{{ domainHint }}</div>

    <div v-if="match" class="match-banner">
      <span>
        <b class="mono">{{ match.method }} {{ match.path }}</b>
        · 命中 {{ match.totalRules }} 条规则 / {{ match.matched.length }} 个权限码
      </span>
      <el-button link type="primary" @click="match = null">返回目录</el-button>
    </div>

    <div v-else class="summary">
      <span>共 {{ totalRules }} 条规则 · {{ totalCodes }} 个权限码</span>
      <span v-if="searchActive">命中 {{ filteredGroups.length }} 组 · 已自动展开</span>
      <el-button v-if="keyword" link type="primary" @click="keyword = ''">清空搜索</el-button>
    </div>

    <div v-loading="loading" class="list-area">
      <template v-if="match">
        <EmptyState
          v-if="!match.totalRules"
          title="没有权限行匹配该请求"
          description="可能端点尚未注册、方法不匹配，或该请求走的是豁免路径。"
        />
        <PermissionGroupList v-else :groups="match.matched" expand-all :match-path="match.path" />
      </template>

      <template v-else>
        <EmptyState
          v-if="!loading && !filteredGroups.length"
          :title="keyword ? '没有匹配的权限点' : '该域暂无权限点'"
          :description="keyword ? '试试 URL 片段（workloads）、权限码（platform:role）或说明关键词。' : undefined"
        >
          <el-button v-if="keyword" @click="keyword = ''">清空搜索</el-button>
          <el-button type="primary" @click="openCreate">创建权限点</el-button>
        </EmptyState>
        <PermissionGroupList
          v-else
          :groups="filteredGroups"
          :keyword="keyword"
          rule-actions
          @edit-rule="openEdit"
          @delete-rule="onDelete"
        />
      </template>
    </div>

    <div v-if="!match && !loading && filteredGroups.length" class="foot">
      <span>已显示 {{ filteredGroups.length }} 组 / 共 {{ domainGroups.length }} 组</span>
      <span class="foot-hint">默认折叠；搜索命中时自动展开</span>
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑权限点' : '创建权限点'" width="520px">
      <el-form label-width="130px">
        <el-form-item label="权限域" required>
          <el-select v-model="form.domain" @change="onDomainChange">
            <el-option v-for="d in DOMAINS" :key="d" :label="d" :value="d" />
          </el-select>
          <div class="form-tip">
            API = 后端接口授权（resource 是 URL 模式，唯一的鉴权来源）；Page = 前端页面可见性（resource 是路由 path，不进 URL 规则表）。
          </div>
        </el-form-item>
        <el-form-item label="资源 (resource)" required>
          <el-input
            v-model="form.resource"
            :placeholder="form.domain === 'Page' ? '前端路由 path，例如 /resources/workloads' : 'URL 模式，例如 /tenant/**'"
          />
        </el-form-item>
        <el-form-item label="方法 (action)" required>
          <el-select v-model="form.action">
            <el-option v-for="a in actionsForDomain" :key="a" :label="a" :value="a" />
          </el-select>
          <div v-if="form.domain === 'Page'" class="form-tip">Page 域动作固定为 VIEW（页面可见性，与 HTTP 无关）。</div>
        </el-form-item>
        <el-form-item label="权限码 (code)" required>
          <el-input v-model="form.code" placeholder="例如 platform:role:manage" />
          <div class="form-tip">
            须以 platform:（平台族）或 tenant:（租户族）开头；同 code 可覆盖多条 URL 规则（ANY-of），角色勾选按 code 展开全部行。
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="例如 新建权限点" />
          <div class="form-tip">说明属于这一行（该 URL 规则 / 页面码），与 code 无关：同 code 的多行各写各的。</div>
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
  display: flex;
  align-items: center;
  gap: 10px 16px;
  flex-wrap: wrap;
}
.search {
  flex: 1 1 280px;
  min-width: 220px;
  max-width: 520px;
}
.domain-tabs {
  flex: 1 1 220px;
  min-width: 0;
}
.domain-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.domain-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.tab-count {
  font-size: 11px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 9px;
  background: var(--panel-hover);
  color: var(--text-3);
  font-weight: 400;
}
.tab-count.is-zero {
  opacity: 0.45;
}
.lookup {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px dashed var(--border-strong);
  border-radius: 8px;
  background: var(--panel);
}
.lookup-method {
  width: 108px;
  flex-shrink: 0;
}
.lookup-path {
  flex: 1 1 260px;
  min-width: 180px;
}
.lookup-hint {
  color: var(--text-3);
  font-size: 11.5px;
}
.domain-hint {
  margin: 10px 0 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-3);
}
.summary {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--text-3);
}
.match-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  padding: 8px 12px;
  border-left: 3px solid var(--accent);
  border-radius: 0 6px 6px 0;
  background: var(--accent-soft);
  font-size: 12.5px;
  color: var(--text-2);
}
.list-area {
  min-height: 120px;
}
.foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--text-3);
}
.foot-hint {
  margin-left: auto;
}
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.4;
}
.mono {
  font-family: ui-monospace, monospace;
}
</style>
