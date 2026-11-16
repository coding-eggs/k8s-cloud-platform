<script setup lang="ts">
/**
 * 免登录页的外壳：只给**不需要登录**的独立工具页用（当前：新建集群向导）。
 *
 * 不能挂 MainLayout：它依赖登录态与权限引导（`perm.bootstrap()` 在守卫里 await，
 * 公共路由直接放行不会跑），拿不到权限就会拉一堆 401 的接口并把侧栏渲染成空壳。
 * 这里只给一条品牌条 + 主题切换 + 「登录 / 进平台」入口，内容区自己撑满。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAccessToken } from '@/auth/oauth'
import { getTheme, toggleTheme } from '@/utils/theme'

const router = useRouter()
const theme = ref(getTheme())
/** 有 token 就是平台内用户深链过来的，给一条回平台的路；没有就只给登录 */
const loggedIn = computed(() => Boolean(getAccessToken()))

function onToggleTheme(): void {
  theme.value = toggleTheme()
}
</script>

<template>
  <div class="pub">
    <header class="pub__bar">
      <button type="button" class="pub__brand" @click="router.push(loggedIn ? '/overview' : '/login')">
        <span class="pub__mark">
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
        <span class="pub__name">K8s 云平台</span>
      </button>

      <div class="pub__right">
        <el-tooltip :content="theme === 'dark' ? '切换浅色' : '切换深色'" placement="bottom">
          <button type="button" class="pub__icon" @click="onToggleTheme">
            <el-icon><Sunny v-if="theme === 'dark'" /><Moon v-else /></el-icon>
          </button>
        </el-tooltip>
        <el-button v-if="loggedIn" size="small" @click="router.push('/overview')">进入平台</el-button>
        <el-button v-else size="small" type="primary" @click="router.push('/login')">登录平台</el-button>
      </div>
    </header>

    <main class="pub__content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.pub {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}
.pub__bar {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 18px;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
}
:global(html.dark) .pub__bar {
  background: linear-gradient(180deg, #13203c 0%, var(--bg-elevated) 92%);
}
.pub__brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  padding: 0;
  border: none;
  background: transparent;
  font: inherit;
  color: inherit;
  cursor: pointer;
}
.pub__mark {
  width: 27px;
  height: 27px;
  border-radius: 8px;
  background: var(--accent-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 14px color-mix(in srgb, var(--accent) 45%, transparent);
}
.pub__name {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: .02em;
  color: var(--text-1);
}
.pub__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}
.pub__icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-2);
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all .15s;
}
.pub__icon:hover {
  color: var(--accent);
  border-color: var(--accent);
}
.pub__content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 20px 24px;
}
</style>
