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

// 尽早应用主题，避免首屏闪烁（默认深色）
initTheme()

const app = createApp(App)

// 全局注册 Element Plus 图标（菜单等处按名字使用）
for (const [name, component] of Object.entries(Icons)) {
  app.component(name, component)
}

app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
