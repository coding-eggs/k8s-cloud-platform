<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getUserInfo, logout } from '@/auth/oauth'
import { getTheme, toggleTheme } from '@/utils/theme'
import ContextSelector from '@/components/ContextSelector.vue'

const route = useRoute()
const theme = ref(getTheme())

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
const pageTitle = computed(() => (route.meta.title as string) ?? '')
const pageGroup = computed(() => (route.meta.group as string) ?? '')
// 资源管理页在顶栏展示全局上下文级联（租户→集群→命名空间）
const showContextSelector = computed(() => route.meta.context === 'full')

// 用户按钮：头像 + 名称，下拉里展示角色与退出
const userInfo = getUserInfo()
const userName = userInfo.name || 'admin'
const userInitial = (userName.trim()[0] ?? 'A').toUpperCase()

function onUserCommand(cmd: string): void {
  if (cmd === 'theme') onToggleTheme()
  else if (cmd === 'logout') handleLogout()
}

function handleLogout(): void {
  logout()
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
        <el-menu-item index="/overview">
          <el-icon><Odometer /></el-icon>
          <template #title>总览</template>
        </el-menu-item>

        <div class="menu-group">平台管理</div>
        <el-menu-item index="/clusters">
          <el-icon><Monitor /></el-icon>
          <template #title>集群管理</template>
        </el-menu-item>
        <el-menu-item index="/nodes">
          <el-icon><Cpu /></el-icon>
          <template #title>节点管理</template>
        </el-menu-item>
        <el-menu-item index="/tenants">
          <el-icon><OfficeBuilding /></el-icon>
          <template #title>租户管理</template>
        </el-menu-item>
        <el-menu-item index="/namespaces">
          <el-icon><FolderOpened /></el-icon>
          <template #title>命名空间管理</template>
        </el-menu-item>
        <el-menu-item index="/templates">
          <el-icon><CollectionTag /></el-icon>
          <template #title>RBAC 模板</template>
        </el-menu-item>

        <div class="menu-group">资源管理</div>
        <el-menu-item index="/resources/workloads">
          <el-icon><Box /></el-icon>
          <template #title>工作负载</template>
        </el-menu-item>
        <el-menu-item index="/resources/pods">
          <el-icon><Cpu /></el-icon>
          <template #title>Pod</template>
        </el-menu-item>
        <el-menu-item index="/resources/configmaps">
          <el-icon><Document /></el-icon>
          <template #title>ConfigMap</template>
        </el-menu-item>
        <el-menu-item index="/resources/secrets">
          <el-icon><Key /></el-icon>
          <template #title>Secret</template>
        </el-menu-item>
        <el-menu-item index="/resources/services">
          <el-icon><Link /></el-icon>
          <template #title>Service</template>
        </el-menu-item>
        <el-menu-item index="/resources/pvcs">
          <el-icon><Coin /></el-icon>
          <template #title>PVC</template>
        </el-menu-item>
        <el-menu-item index="/resources/servicemonitors">
          <el-icon><DataLine /></el-icon>
          <template #title>ServiceMonitor</template>
        </el-menu-item>
        <el-menu-item index="/resources/podmonitors">
          <el-icon><DataLine /></el-icon>
          <template #title>PodMonitor</template>
        </el-menu-item>
        <el-menu-item index="/resources/hpas">
          <el-icon><TrendCharts /></el-icon>
          <template #title>HPA</template>
        </el-menu-item>

        <div class="menu-group">集群运维</div>
        <el-menu-item index="/ops/ippools">
          <el-icon><Grid /></el-icon>
          <template #title>地址池</template>
        </el-menu-item>
      </el-menu>
    </aside>

    <!-- 窄屏 drawer 遮罩：点击收起（最上层，压在 sidebar 之下） -->
    <div v-if="overlay" class="sidebar-backdrop" @click="drawerOpen = false" />

    <!-- 主体：显式纵向（el-container 仅识别 el-header/el-footer 子组件才自动转纵，普通标签不触发） -->
    <el-container class="body" direction="vertical">
      <header class="topbar">
<!--        <div class="crumb">-->
<!--          <span v-if="pageGroup" class="crumb-group">{{ pageGroup }}</span>-->
<!--          <span v-if="pageGroup" class="crumb-sep">/</span>-->
<!--          <span class="crumb-title">{{ pageTitle }}</span>-->
<!--        </div>-->

        <ContextSelector v-if="showContextSelector" />

        <div class="topbar-right">
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
                <el-dropdown-item disabled>平台管理员</el-dropdown-item>
                <el-dropdown-item v-if="narrow" divided command="theme">
                  <span class="logout-item"><el-icon><Sunny v-if="theme === 'dark'" /><Moon v-else /></el-icon>切换主题</span>
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
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

.menu-group {
  font-size: 11px;
  color: var(--text-3);
  letter-spacing: .12em;
  padding: 14px 12px 6px;
  user-select: none;
}
/* 折叠态：分组标题退化为一条分隔线 */
.sidebar.is-collapsed .menu-group {
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
  gap: 20px;
  padding: 0 24px;
  flex-shrink: 0;
}
/* 深色：顶栏加一点抬升感（微渐变 + 软阴影），避免与内容区平贴成一片黑 */
:global(html.dark) .topbar {
  background: linear-gradient(180deg, #13203c 0%, var(--bg-elevated) 92%);
  box-shadow: 0 10px 24px -14px rgba(0, 0, 0, .6);
}
.crumb {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  flex-shrink: 1; /* 空间不足时优先压缩面包屑 */
}
.crumb-group {
  font-size: 13px;
  color: var(--text-3);
  white-space: nowrap;
  flex-shrink: 0;
}
.crumb-sep {
  color: var(--text-3);
  opacity: .5;
  flex-shrink: 0;
}
.crumb-title {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: .01em;
  color: var(--text-1);
  /* 防 CJK 逐字竖排：不换行 + 溢出省略 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
