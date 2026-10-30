<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAccessToken, getUserInfo, logout, startSessionMaintenance } from '@/auth/oauth'
import { getTheme, toggleTheme } from '@/utils/theme'
import ContextSelector from '@/components/ContextSelector.vue'
import TenantSwitcher from '@/components/TenantSwitcher.vue'
import CommandPalette from '@/components/CommandPalette.vue'
import SearchFab from '@/components/SearchFab.vue'
import { usePermission } from '@/stores/permission'
import { buildNav, labelOf, resolveNavLocation, type NavDomain, type NavDomainKey, type NavFace } from '@/nav'

const route = useRoute()
const router = useRouter()
const theme = ref(getTheme())
const perm = usePermission()

// ---------- 导航：域 → 菜单项，全部来自 nav.ts ----------
// 菜单不再手写：显隐与归属由路由上的 meta（domain / platformDomain / icon / order / nav）
// 加当前视图推导，见 src/nav.ts。
const face = computed<NavFace>(() => (perm.currentTenant ? 'tenant' : 'platform'))

/**
 * 当前视图的域列表。
 * 当前页所属域若不在本视图的域集合里（管理员在租户视图直接打开收藏的平台页），
 * 把它的域补到末尾并高亮 —— 深链不该把人扔进一个空侧栏。
 */
const location = computed(() => resolveNavLocation(face.value, route.path))

/**
 * 顶栏域 tab 列表。
 * - 当前页属于本面时正常列出；若它所属的域当前没有任何可见菜单项（例如角色只持某个 detail 码），
 *   仍补一个只有域名的空域 —— 否则 tab 与侧栏标题会一起消失。
 * - 当前页来自另一个面时（管理员深链到平台页），用**对面那一份**替换同 key 的域：
 *   两侧的「网络 / 组织」同名，沿用本面会把另一套菜单顶上来，当前页高亮不到。
 */
const domains = computed<NavDomain[]>(() => {
  const base = buildNav(face.value, perm)
  const loc = location.value
  if (!loc) return base

  if (!loc.otherFace) {
    return base.some((d) => d.key === loc.domain)
      ? base
      : [...base, { key: loc.domain, label: labelOf(loc.domain), items: [] }]
  }

  const other: NavFace = face.value === 'tenant' ? 'platform' : 'tenant'
  const extra = buildNav(other, perm).find((d) => d.key === loc.domain)
  if (!extra) return base
  const index = base.findIndex((d) => d.key === loc.domain)
  if (index < 0) return [...base, extra]
  const replaced = [...base]
  replaced[index] = extra
  return replaced
})

const activeDomain = computed<NavDomain | null>(() => {
  const loc = location.value
  return loc ? (domains.value.find((d) => d.key === loc.domain) ?? null) : null
})

const activeItems = computed(() => activeDomain.value?.items ?? [])

// ---------- 命令面板（Ctrl / Cmd + K） ----------
const paletteOpen = ref(false)

function onGlobalKeydown(e: KeyboardEvent): void {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = !paletteOpen.value
  }
}

/** 域 tab：回到该域上次停留的页面；记录已失效（权限被收）则回落到第一项 */
function goDomain(key: NavDomainKey): void {
  const domain = domains.value.find((d) => d.key === key)
  if (!domain) return
  let stored: string | null = null
  try {
    stored = localStorage.getItem(`platform_nav_last:${face.value}:${key}`)
  } catch {
    /* localStorage 不可用（隐私模式）：忽略记忆，直接取第一项 */
  }
  const target = stored && domain.items.some((i) => i.path === stored) ? stored : domain.items[0]?.path
  if (target && target !== route.path) void router.push(target)
}

/** 记住每个域最后停留的菜单页。详情页不记：它通常依赖 query，回到裸路径是坏页。 */
watch(
  () => route.path,
  (path) => {
    if (route.meta.nav === 'hidden') return
    const loc = resolveNavLocation(face.value, path)
    if (!loc || loc.otherFace) return // 另一个面的页面不记进本面的域记忆
    try {
      localStorage.setItem(`platform_nav_last:${face.value}:${loc.domain}`, path)
    } catch {
      /* 隐私模式 / 配额满：不记忆即可，不影响导航 */
    }
  },
  { immediate: true },
)

onMounted(() => {
  // 会话恢复接线（Task 17 carry）：刷新页面时 handleCallback 不会重跑，
  // 续期/保活定时器随之休眠 —— 在「已登录且进入 shell」的唯一收口点补拉起（幂等）。
  // 登录页路径由 oauth.handleCallback 自行 start，二者互不重复。
  // 权限/租户引导（bootstrap）在 router.beforeEach 首个非 public 导航里 await——
  // 早于本组件与页面 mount，自动进入唯一租户后页面首拉即用新 token。
  if (getAccessToken()) {
    startSessionMaintenance()
  }
  window.addEventListener('keydown', onGlobalKeydown)
})

function onToggleTheme(): void {
  theme.value = toggleTheme()
}

// ---------- 侧边栏：「图标轨 / 完整菜单」两态 ----------
// 宽屏（≥1280px）：跟用户偏好，持久化；窄屏：默认收敛为图标轨，
// 点 logo 展开浮层 drawer（盖在内容最上层，点遮罩收起）
const SIDEBAR_KEY = 'platform_sidebar_collapsed'
const NARROW_QUERY = '(max-width: 1279px)'

const userCollapsed = ref(localStorage.getItem(SIDEBAR_KEY) === '1')
const narrow = ref(window.matchMedia(NARROW_QUERY).matches)
const drawerOpen = ref(false)

let mql: MediaQueryList | null = null
function onBreakpoint(e: MediaQueryListEvent): void {
  narrow.value = e.matches
}
onMounted(() => {
  mql = window.matchMedia(NARROW_QUERY)
  mql.addEventListener('change', onBreakpoint)
})
onBeforeUnmount(() => {
  mql?.removeEventListener('change', onBreakpoint)
  window.removeEventListener('keydown', onGlobalKeydown)
})
// 进入窄屏时收起可能开着的 drawer
watch(narrow, (isNarrow) => {
  if (isNarrow) drawerOpen.value = false
})

const collapsed = computed(() => (narrow.value ? !drawerOpen.value : userCollapsed.value))
// 窄屏展开态：浮层 + 遮罩
const overlay = computed(() => narrow.value && drawerOpen.value)

function toggleSidebar(): void {
  if (narrow.value) {
    drawerOpen.value = !drawerOpen.value
    return
  }
  userCollapsed.value = !userCollapsed.value
  localStorage.setItem(SIDEBAR_KEY, userCollapsed.value ? '1' : '0')
}

// 窄屏浮层模式下选中菜单项后收起，让出内容区
function onMenuSelect(): void {
  if (narrow.value) drawerOpen.value = false
}

const sidebarTitle = computed(() =>
  narrow.value
    ? drawerOpen.value
      ? '关闭菜单'
      : '打开菜单'
    : userCollapsed.value
      ? '展开菜单'
      : '收起菜单',
)

// ---------- 顶栏 ----------
// 顶栏不再显示面包屑：域 tab + 侧栏当前项已经回答了「我在哪」（见 docs/superpowers/specs/2026-10-30-frontend-navigation-ia-design.md §6.1）
// 资源管理页在顶栏展示全局上下文级联（租户→集群→命名空间）。
// 集群级资源页（存储类）只需要到集群，用 'cluster' 档，别多给一级用不上的命名空间。
const contextDepth = computed<'full' | 'cluster' | null>(() => {
  const c = route.meta.context
  return c === 'full' || c === 'cluster' ? c : null
})

// 用户按钮：头像 + 名称，下拉里展示角色与退出
const userInfo = getUserInfo()
const userName = userInfo.name || 'admin'
const userInitial = (userName.trim()[0] ?? 'A').toUpperCase()
// 角色行：admin 帽子 / 当前租户成员；非管理员不会停在「平台视图」（2026-10-08 起平台视图为管理员专属），
// 故没有租户上下文只可能是「一个租户都没被分配」
const userRoleLabel = computed(() => {
  if (perm.isAdmin) return '平台管理员'
  const t = perm.currentTenant
  return t ? `租户成员 · ${t.tenantName ?? t.tenantId}` : '未加入租户'
})

function onUserCommand(cmd: string): void {
  if (cmd === 'theme') onToggleTheme()
  else if (cmd === 'profile') void router.push('/profile')
  else if (cmd === 'logout') void handleLogout()
}

async function handleLogout(): Promise<void> {
  // 必须 await：logout 会先打 platform-auth 的 /session/logout 失效服务端会话（根凭证），
  // 再清本地。只清本地会让 HttpOnly 会话继续存活 → 再点登录被静默 SSO 回原用户，换不了账号。
  await logout()
  window.location.href = '/login'
}
</script>

<template>
  <el-container class="layout">
    <!-- 侧边栏：分组导航（可折叠为图标轨） -->
    <aside class="sidebar" :class="{ 'is-collapsed': collapsed, 'is-overlay': overlay }">
      <!-- logo 即折叠按钮：宽屏切换图标轨，窄屏打开浮层 drawer -->
      <button type="button" class="logo" :title="sidebarTitle" @click="toggleSidebar">
        <span class="logo-mark">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none">
            <path
              d="M12 2.2 20.4 7.1v9.8L12 21.8 3.6 16.9V7.1L12 2.2Z"
              stroke="#fff"
              stroke-width="1.8"
              stroke-linejoin="round"
            />
            <circle cx="12" cy="12" r="3" fill="#fff" />
          </svg>
        </span>
        <span class="logo-text">K8s 云平台</span>
      </button>

      <el-menu :default-active="route.path" router class="menu" :collapse="collapsed" @select="onMenuSelect">
        <!-- 在某个域里：只列该域（域名作分组标题）。总览页不属于任何域 → 侧栏退化为完整目录
             （按域分组），否则登录后默认落在总览，侧栏会是一个菜单项都没有的空壳。 -->
        <template v-if="activeDomain">
          <div class="menu-domain">{{ activeDomain.label }}</div>
          <el-menu-item v-for="item in activeItems" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.title }}</template>
          </el-menu-item>
        </template>
        <template v-else>
          <template v-for="d in domains" :key="d.key">
            <template v-if="d.items.length">
              <div class="menu-domain">{{ d.label }}</div>
              <el-menu-item v-for="item in d.items" :key="item.path" :index="item.path">
                <el-icon><component :is="item.icon" /></el-icon>
                <template #title>{{ item.title }}</template>
              </el-menu-item>
            </template>
          </template>
        </template>
      </el-menu>
    </aside>

    <!-- 窄屏 drawer 遮罩：点击收起（最上层，压在 sidebar 之下） -->
    <div v-if="overlay" class="sidebar-backdrop" @click="drawerOpen = false" />

    <!-- 主体：显式纵向（el-container 仅识别 el-header/el-footer 子组件才自动转纵，普通标签不触发） -->
    <el-container class="body" direction="vertical">
      <header class="topbar">
        <!-- 域 tab：总览固定在最左，其后是当前视图的域集合（平台 4 / 租户 7） -->
        <nav class="domain-tabs">
          <button
            type="button"
            class="domain-tab"
            :class="{ 'is-active': route.path === '/overview' }"
            @click="router.push('/overview')"
          >
            总览
          </button>
          <span class="domain-sep" aria-hidden="true" />
          <button
            v-for="d in domains"
            :key="d.key"
            type="button"
            class="domain-tab"
            :class="{ 'is-active': activeDomain?.key === d.key }"
            @click="goDomain(d.key)"
          >
            {{ d.label }}
          </button>
        </nav>

        <ContextSelector v-if="contextDepth" :depth="contextDepth" />

        <div class="topbar-right">
          <!-- 租户切换器（自管轨）：my-tenants 非空即显示；admin 代管用 ContextSelector 筛选器（spec §4.4） -->
          <TenantSwitcher />
          <!-- 主题按钮：宽屏独立展示；窄屏收进用户下拉，给级联让位 -->
          <el-tooltip v-if="!narrow" content="切换主题" placement="bottom">
            <button class="icon-btn" @click="onToggleTheme">
              <el-icon><Sunny v-if="theme === 'dark'" /><Moon v-else /></el-icon>
            </button>
          </el-tooltip>
          <!-- 用户按钮：头像 + 名称，下拉展示角色与退出 -->
          <el-dropdown trigger="click" @command="onUserCommand">
            <button type="button" class="user-btn">
              <span class="avatar">{{ userInitial }}</span>
              <span class="user-name">{{ userName }}</span>
              <el-icon class="caret"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ userRoleLabel }}</el-dropdown-item>
                <el-dropdown-item v-if="narrow" divided command="theme">
                  <span class="logout-item"><el-icon><Sunny v-if="theme === 'dark'" /><Moon v-else /></el-icon>切换主题</span>
                </el-dropdown-item>
                <el-dropdown-item divided command="profile">
                  <span class="logout-item"><el-icon><User /></el-icon>个人中心</span>
                </el-dropdown-item>
                <el-dropdown-item command="logout">
                  <span class="logout-item"><el-icon><SwitchButton /></el-icon>退出登录</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content px-4 py-2.5">
        <router-view />
      </main>
    </el-container>
  </el-container>

  <!-- 悬浮搜索入口（可拖动）：自身管理位置与拖拽，这里只接打开事件 -->
  <SearchFab @open="paletteOpen = true" />

  <CommandPalette v-model="paletteOpen" />
</template>

<style scoped>
.layout {
  height: 100vh;
}

/* ---------- 侧边栏 ---------- */
.sidebar {
  width: 220px;
  background: var(--bg-elevated);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width .2s ease;
}
/* 折叠态：64px 图标轨（EP collapse 菜单宽 = icon 24 + padding 20×2 = 64，另加 1px 右边框） */
.sidebar.is-collapsed {
  width: 65px;
}
/* 窄屏展开态：浮层盖在内容最上层，从左滑入 */
.sidebar.is-overlay {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: 220px;
  z-index: 2501;
  transition: none;
  box-shadow: 12px 0 32px -8px rgba(0, 0, 0, .45);
  animation: sidebar-slide-in .18s ease;
}
@keyframes sidebar-slide-in {
  from { transform: translateX(-100%); }
  to { transform: translateX(0); }
}
/* drawer 遮罩：点击收起 */
.sidebar-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2500;
  background: rgba(2, 6, 17, .55);
  backdrop-filter: blur(2px);
  animation: backdrop-fade-in .18s ease;
}
@keyframes backdrop-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
.sidebar.is-collapsed .logo {
  padding: 0;
  justify-content: center;
}
.sidebar.is-collapsed .logo-text {
  display: none;
}

/* logo 兼作折叠按钮 */
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border: none;
  border-bottom: 1px solid var(--border);
  background: transparent;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
  flex-shrink: 0;
  transition: background .15s;
}
.logo:hover {
  background: var(--panel-hover);
}
.logo-mark {
  width: 27px;
  height: 27px;
  border-radius: 8px;
  background: var(--accent-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 14px color-mix(in srgb, var(--accent) 45%, transparent);
}
.logo-text {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: .02em;
  color: var(--text-1);
}

.menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--text-2);
  --el-menu-active-color: var(--accent);
  --el-menu-hover-bg-color: var(--panel-hover);
  flex: 1;
  border-right: none;
  padding: 8px;
  overflow-y: auto;
}
/* 折叠态：去掉左右内边距，让 EP 的 64px 图标几何（20/24/20）完整居中 */
.sidebar.is-collapsed .menu {
  padding: 8px 0;
}
.menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  border-radius: 8px;
  margin: 2px 0;
}
.menu :deep(.el-menu-item.is-active) {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.menu :deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: -8px;
  top: 25%;
  bottom: 25%;
  width: 3px;
  border-radius: 2px;
  background: var(--accent-grad);
}
/* 折叠态：左侧竖条会被菜单裁剪区切掉，隐藏之（选中态靠底色/字色表达） */
.sidebar.is-collapsed .menu :deep(.el-menu-item.is-active::before) {
  display: none;
}

/* 侧栏当前域名（折叠态退化为一条分隔线） */
.menu-domain {
  font-size: 11px;
  color: var(--text-3);
  letter-spacing: .12em;
  padding: 14px 12px 6px;
  user-select: none;
}
.sidebar.is-collapsed .menu-domain {
  font-size: 0;
  letter-spacing: 0;
  padding: 8px 0 2px;
  margin: 4px 12px 0;
  border-top: 1px solid var(--border);
}

/* ---------- 顶栏 ---------- */
.body {
  min-width: 0;
}
.topbar {
  height: 64px;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 16px 0 6px;
  flex-shrink: 0;
}
/* ---------- 域 tab ---------- */
.domain-tabs {
  display: flex;
  align-items: center;
  gap: 0;
  flex: 1 1 auto;
  min-width: 0;
  overflow-x: auto; /* 域多 + 360px 上下文级联在 1280px 下放不下，横向滚动兜底 */
  scrollbar-width: none;
}
.domain-tabs::-webkit-scrollbar {
  display: none;
}
/* 总览与域 tab 之间的分隔线 */
.domain-sep {
  flex-shrink: 0;
  width: 1px;
  height: 18px;
  margin: 0 6px;
  background: var(--border);
}
.domain-tab {
  flex-shrink: 0;
  height: 64px;
  padding: 0 11px;
  border: none;
  background: transparent;
  color: var(--text-2);
  font: inherit;
  font-size: 13.5px;
  cursor: pointer;
  position: relative;
  transition: color .15s ease, background-color .15s ease;
}
/* 悬停给整列一层浅底：这些是页面级的 tab，得看得出能点 */
.domain-tab:hover {
  color: var(--text-1);
  background: var(--panel-hover);
}
.domain-tab:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -3px;
}
/* 选中：主色字 + 自下而上的微光 + 2px 渐变指示线，三样是一件事而不是三种装饰 */
.domain-tab.is-active {
  color: var(--accent);
  font-weight: 600;
  background: linear-gradient(180deg, transparent 35%, color-mix(in srgb, var(--accent) 14%, transparent) 100%);
}
.domain-tab.is-active:hover {
  background: linear-gradient(180deg, color-mix(in srgb, var(--accent) 6%, transparent) 35%, color-mix(in srgb, var(--accent) 18%, transparent) 100%);
}
.domain-tab.is-active::after {
  content: '';
  position: absolute;
  left: 6px;
  right: 6px;
  bottom: 0;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--accent-grad);
  box-shadow: 0 -2px 10px -2px color-mix(in srgb, var(--accent) 70%, transparent);
}
/* 深色：顶栏加一点抬升感（微渐变 + 软阴影），避免与内容区平贴成一片黑 */
:global(html.dark) .topbar {
  background: linear-gradient(180deg, #13203c 0%, var(--bg-elevated) 92%);
  box-shadow: 0 10px 24px -14px rgba(0, 0, 0, .6);
}
.topbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}
.icon-btn {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-2);
  font-size: 17px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all .15s;
}
.icon-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}
/* 用户按钮（头像 + 名称） */
.user-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 12px 0 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-2);
  cursor: pointer;
  white-space: nowrap;
  transition: all .15s;
}
.user-btn:hover {
  border-color: var(--border-strong);
  color: var(--text-1);
}
.avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--accent-grad);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  font-size: 13px;
  font-weight: 500;
}
.caret {
  font-size: 12px;
  color: var(--text-3);
}
.logout-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* ---------- 内容区 ---------- */
.content {
  flex: 1;
  min-height: 0; /* 允许内部滚动而非撑破布局 */
  background: var(--bg);
  overflow-y: auto;
}
</style>
