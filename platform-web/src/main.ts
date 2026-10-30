import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// EP 官方 dark 变量集（.dark 作用域），随后由 theme.css 覆盖为我们的调色板
import 'element-plus/theme-chalk/dark/css-vars.css'
import './assets/theme.css'
import './assets/main.css'
import * as Icons from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { initTheme } from './utils/theme'
import { installRecentVisitsTracking } from './composables/useRecentVisits'

// 尽早应用主题，避免首屏闪烁（默认深色）
initTheme()

const app = createApp(App)

// 全局注册 Element Plus 图标（菜单等处按名字使用）
for (const [name, component] of Object.entries(Icons)) {
  app.component(name, component)
}

app.use(ElementPlus, { locale: zhCn })
app.use(router)

// 最近访问记录（localStorage，纯前端）。必须在 app.use(router) 之后安装，
// 否则首次导航的 afterEach 钩子还没挂上。
installRecentVisitsTracking(router)

// 开发期导航一致性检查：菜单页缺 domain/icon 或域 key 拼错时当场报出来。
// 生产构建里 import.meta.env.DEV 为 false，整段被 tree-shake 掉；
// 用动态 import 也是为了不在生产包的首屏路径上拖入整张路由表。
if (import.meta.env.DEV) {
  void import('@/nav').then(({ navIntegrityProblems }) => {
    const problems = navIntegrityProblems()
    if (problems.length) console.error('[nav] 路由元数据不一致：\n' + problems.join('\n'))
  })
}

app.mount('#app')
