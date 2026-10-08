<script setup lang="ts">
/**
 * 权限点目录（platform_permission）：一行 = 一条 URL 规则/一条页面码；code 不唯一（ANY-of）。
 * 读 /permission/list = platform:role:read；写 create/update/delete/reload = platform:role:manage。
 *
 * <p><b>按域分 tab 展示</b>：API / K8S / Page 三个域的语义完全不同（资源列分别是 URL 模式 / 资源名 /
 * 前端路由 path），混在一张表里既读不出重点、也让「域」列占了宽度却零信息量。tab 一次只看一个域，
 * 表格里就不再需要「域」列，URL 模式那一列得以放宽。
 *
 * <p>表格把相邻同 code 行的「权限点/说明」两列纵向合并 —— 一眼看出"哪个权限码覆盖哪些 URL"。
 * 后端写前裸端点校验（会删空活端点覆盖即拒绝，错误经 http 拦截器统一弹错）+ 提交后热加载即时生效。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import { permissionApi } from '@/api'
import type { PlatformPermission } from '@/types'

const loading = ref(false)
const list = ref<PlatformPermission[]>([])

/** tab 顺序即此数组顺序；与创建对话框的域下拉同源 */
const DOMAINS = [
    'API'
  // , 'K8S'
  , 'Page']
/** 当前 tab。默认 API —— 它是唯一的鉴权来源，行数也最多 */
const activeDomain = ref('API')

/** 每域行数（tab 上的角标）。缺省域（domain 为 null 的脏数据）按 API 计，与下方分组口径一致 */
const countByDomain = computed(() => {
  const m: Record<string, number> = {}
  for (const p of list.value) {
    const d = p.domain ?? 'API'
    m[d] = (m[d] ?? 0) + 1
  }
  return m
})

/** 当前 tab 的行。⚠️ span-merge 的行索引必须基于它而非 list —— 否则合并区间会错位 */
const shownRows = computed(() => list.value.filter((p) => (p.domain ?? 'API') === activeDomain.value))

/** 每域的读法提示：resource 列在不同域下含义不同，别让用户自己猜。
 *  ⚠️ 走 {{ }} 插值，不支持 markdown —— 要加粗请用 &lt;b&gt; 或换行，别写 **。 */
const DOMAIN_HINTS: Record<string, string> = {
  API: 'resource 是 k8s-server / platform-api 的 URL 模式，action 是 HTTP 方法。这是唯一的接口鉴权来源 —— 表驱动授权按（方法, 路径）命中这里的行。',
  K8S: '预留域，当前无运行时消费者（不进 URL 规则表）。',
  Page: 'resource 是前端路由 path，action 固定 VIEW。决定侧边栏入口与路由能否进入，与接口授权相互独立；这些行不进 URL 规则表。页面清单与命名规则见 src/pageCodes.ts。',
}
const domainHint = computed(() => DOMAIN_HINTS[activeDomain.value] ?? '')

/** resource 列的标题随域变化（Page 下它不是 URL 模式） */
const resourceColumnLabel = computed(() =>
  activeDomain.value === 'Page' ? '路由 path (resource)' : 'URL 模式 (resource)',
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const rows = await permissionApi.list()
    // 服务端 order by domain, code；此处按 domain（tab 顺序）→ code → resource 稳定重排：
    // 同域内同 code 行相邻，才能让 span-merge 正确合并
    const order = new Map(DOMAINS.map((d, i) => [d, i]))
    list.value = [...rows].sort(
      (a, b) =>
        (order.get(a.domain ?? 'API') ?? 99) - (order.get(b.domain ?? 'API') ?? 99) ||
        a.code.localeCompare(b.code) ||
        (a.resource ?? '').localeCompare(b.resource ?? ''),
    )
  } finally {
    loading.value = false
  }
}

/** 每个 code 组的起始行号与行数（span-method 合并依据），基于当前 tab 的行 */
const codeGroups = computed(() => {
  const groups = new Map<string, { index: number; size: number }>()
  shownRows.value.forEach((p, i) => {
    const g = groups.get(p.code)
    if (!g) groups.set(p.code, { index: i, size: 1 })
    else g.size += 1
  })
  return groups
})

/** 仅合并第 0/1 列（权限点、说明）：组首行 rowspan=组大小，其余行隐藏 */
function spanMethod({ row, rowIndex, columnIndex }: { row: PlatformPermission; rowIndex: number; columnIndex: number }): [number, number] {
  if (columnIndex !== 0 && columnIndex !== 1) return [1, 1]
  const g = codeGroups.value.get(row.code)
  if (!g) return [1, 1]
  return rowIndex === g.index ? [g.size, 1] : [0, 1]
}

// ---- 创建 / 编辑对话框（共用，isEdit 切换） ----
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const form = reactive({ id: '', domain: 'API', resource: '', action: 'POST', code: '', description: '' })

/** API/K8S 域动作 = HTTP 方法（表驱动授权把 action 直接当 method 匹配） */
const API_ACTIONS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', '*']
/** Page 域动作 = 前端路由可见性动词，与 HTTP 无关（后端 PermissionService.PAGE_ACTIONS 同口径） */
const PAGE_ACTIONS = ['VIEW']
/** 当前域可用的动作列表：切换域名时收敛，避免造出后端会拒的组合 */
const actionsForDomain = computed(() => (form.domain === 'Page' ? PAGE_ACTIONS : API_ACTIONS))

function openCreate(): void {
  isEdit.value = false
  form.id = ''
  // 新建时预填当前 tab 的域 —— 在 Page tab 点「创建权限点」多半是要加一个页面码
  form.domain = activeDomain.value
  form.resource = ''
  form.action = actionsForDomain.value[0] === 'VIEW' ? 'VIEW' : 'POST'
  form.code = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row: PlatformPermission): void {
  isEdit.value = true
  form.id = row.id
  form.domain = row.domain ?? 'API'
  form.resource = row.resource ?? ''
  form.action = row.action ?? 'POST'
  form.code = row.code
  form.description = row.description ?? ''
  dialogVisible.value = true
}

/** 切换权限域：动作取值随之收敛（Page 只有一个合法动作 view） */
function onDomainChange(d: string): void {
  if (d === 'Page') form.action = 'VIEW'
  else if (!API_ACTIONS.includes(form.action)) form.action = 'POST'
}

async function submit(): Promise<void> {
  // 手动校验（与后端 PermissionService.validateFields 同口径，后端仍会兜底）
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
  if ((form.domain === 'API' || form.domain === 'Page') && !resource.startsWith('/')) {
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
    // 切到该行所属域的 tab 再刷新：在 Page tab 里新建/改域后，不切 tab 会看不到刚存的行
    activeDomain.value = payload.domain
    await load()
  } catch {
    /* 拦截器提示（含裸端点保护拒绝的端点清单） */
  } finally {
    saving.value = false
  }
}

// ---- 行操作 ----
async function onDelete(row: PlatformPermission): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除权限行「${row.resource ?? '-'}（${row.action ?? '-'}）」？若它是某端点的唯一覆盖行，后端会拒绝并列出受影响端点。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await permissionApi.delete(row.id)
    ElMessage.success('已删除，授权规则已实时生效')
    await load()
  } catch {
    /* 拦截器提示 */
  }
}

// ---- 重载规则（SQL 直接改库后的对齐入口；CRUD 本身自动生效） ----
const reloading = ref(false)
async function onReload(): Promise<void> {
  reloading.value = true
  try {
    await permissionApi.reload()
    ElMessage.success('运行时规则已与数据库对齐')
    await load()
  } catch {
    /* 拦截器提示 */
  } finally {
    reloading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="head-row">
      <!-- 域 tab：一次只看一个域（三个域的 resource 语义完全不同，混在一起读不出重点） -->
      <el-tabs v-model="activeDomain" class="domain-tabs">
        <el-tab-pane v-for="d in DOMAINS" :key="d" :name="d">
          <template #label>
            <span class="tab-label">
              {{ d }}
              <span class="tab-count" :class="{ 'is-zero': !(countByDomain[d] ?? 0) }">{{ countByDomain[d] ?? 0 }}</span>
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>
      <div class="toolbar">
        <el-button type="primary" @click="openCreate">创建权限点</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button :loading="reloading" @click="onReload">重载规则</el-button>
      </div>
    </div>

    <div class="domain-hint">{{ domainHint }}</div>

    <el-table v-loading="loading" :data="shownRows" stripe :span-method="spanMethod">
      <el-table-column prop="code" label="权限点 (code)" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="160">
        <template #default="{ row }">
          <span>{{ row.description || '-' }}</span>
        </template>
      </el-table-column>
      <!-- 「域」列已由上方 tab 表达，去掉后把宽度让给 resource -->
      <el-table-column :label="resourceColumnLabel" min-width="300">
        <template #default="{ row }">
          <span class="mono">{{ row.resource ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="方法 (action)" width="110">
        <template #default="{ row }">
          <span class="mono">{{ row.action ?? '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column width="64" fixed="right">
        <template #default="{ row }">
          <el-dropdown trigger="click">
            <el-button link type="primary" :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="openEdit(row)">
                  <el-button link type="primary">编辑</el-button>
                </el-dropdown-item>
                <el-dropdown-item divided style="color: var(--el-color-danger)" @click="onDelete(row)">
                  <el-button link type="danger">删除</el-button>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑权限点' : '创建权限点'" width="520px">
      <el-form label-width="130px">
        <el-form-item label="权限域" required>
          <el-select v-model="form.domain" @change="onDomainChange">
            <el-option v-for="d in DOMAINS" :key="d" :label="d" :value="d" />
          </el-select>
          <div class="form-tip">
            API = 后端接口授权（resource 是 URL 模式，唯一的鉴权来源）；Page = 前端页面可见性（resource 是路由 path，不进 URL 规则表）；
            K8S = 预留域，当前无运行时消费者。
          </div>
        </el-form-item>
        <el-form-item label="资源 (resource)" required>
          <el-input
            v-model="form.resource"
            :placeholder="form.domain === 'Page'
              ? '前端路由 path，例如 /resources/workloads'
              : form.domain === 'API'
                ? 'URL 模式，例如 /tenant/**'
                : '资源名'"
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
            须以 platform:（平台族）或 tenant:（租户族）开头；同 code 可覆盖多条 URL 规则（ANY-of），角色勾选按 code 展开全部行
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="例如 新建权限点" />
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
/* tab 与操作按钮同排：省一层竖向空间，且"当前域"与"对该域的操作"在视觉上成组。
   ⚠️ 必须允许换行：窄窗口（含预览面板）下若不换行，toolbar 的 flex-shrink:0 会把 tab 压到 65px
   并把按钮顶出视口（实测 280px 宽时 toolbar 右边界 355 > 视口 280）。 */
.head-row {
  display: flex;
  align-items: center;
  gap: 10px 16px;
  flex-wrap: wrap;
}
.domain-tabs {
  flex: 1 1 260px;
  min-width: 0;
}
/* EP 的 tab header 默认带 15px 下边距；同排布局下会导致 tab 与按钮不同轴 */
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
  opacity: .45;
}
.domain-hint {
  margin: 10px 0 14px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-3);
}
.toolbar {
  /* flex:0 1 auto（可收缩）而非 0 0 auto：后者按 max-content 定宽，内部 flex-wrap 永远不会触发，
     三个按钮会整体溢出容器。允许收缩后，容器不够宽时按钮才会换到第二行。 */
  flex: 0 1 auto;
  margin-left: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 0;
}
/* flex + gap 下 EP 自带的 12px 相邻按钮外边距会叠加，收敛为 8px */
.toolbar :deep(.el-button + .el-button) {
  margin-left: 8px;
}
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.4;
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: 12.5px;
}
</style>
