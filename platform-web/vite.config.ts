import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 第三个参数传 '' = 连非 VITE_ 前缀的变量一起读（OAUTH_UPSTREAM 只给 dev server 用，不进前端包）
  const env = loadEnv(mode, process.cwd(), '')
  // 真实 platform-auth 的地址；部署在远端也没关系，浏览器永远只看见本 dev server 这一个 origin
  const oauthUpstream = env.OAUTH_UPSTREAM || 'http://127.0.0.1:9527'

  // ==================== OAuth 同源入口 ====================
  //
  // 为什么要有这一段：续期/keepalive/登出靠 auth 侧的 HttpOnly 会话 Cookie
  // （`SESSION; Path=/; HttpOnly; SameSite=Lax`），而 Lax 不随**跨站** fetch 发出。
  // 于是「页面在一个 host、auth 在另一个 host」时能登录、但续期恒失败。解法不是改 Cookie，
  // 而是让浏览器只跟一个 origin 打交道：SPA 与 auth 端点都由本 dev server 同源提供，
  // 由这里转发到真实的 auth（生产上等价于 platform-web/nginx.conf 里的那几条 location）。
  //
  // 两个必须点：
  //   1. changeOrigin 必须 false —— 保留浏览器带来的 Host。auth 的 issuer / 登录页地址是
  //      按请求 host+port 现算的（AuthorizationServerConfig 用 LoginUrlAuthenticationEntryPoint("/login.html")），
  //      改成 true 就会算成远端 host，又变回跨站。
  //   2. 补 X-Forwarded-Port —— dev 里其实是**冗余**的：Vite 原样转发浏览器带来的 Host（自带 :5173），
  //      auth 直接就能取到端口（2026-10-27 实测：Host=192.168.85.1:5173、不带 XFP，
  //      拼出来仍是 http://192.168.85.1:5173/auth/login.html）。保留它是为了与生产口径一致
  //      —— nginx 的 $host **不含端口**，那种拓扑下没有 XFP 就会退回默认端口（80/443）。
  //      端口从这里请求的 Host 现取，dev server 换端口也不用改这段。
  const oauthProxy: ProxyOptions = {
    target: oauthUpstream,
    changeOrigin: false,
    configure: (proxy) => {
      proxy.on('proxyReq', (proxyReq, req) => {
        const host = req.headers.host ?? ''
        proxyReq.setHeader('X-Forwarded-Proto', 'http')
        proxyReq.setHeader('X-Forwarded-Port', host.includes(':') ? (host.split(':').pop() ?? '80') : '80')
      })
    },
  }

  return {
    plugins: [
      vue(),
      vueDevTools(),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: true,
      //PORT 由启动环境注入（预览/CI），缺省 5173
      port:  5173,
      proxy: {
        // /api/** → platform-api :8081（去掉 /api 前缀；ws: true 支持 Pod exec 的 WS 升级）
        '/api': {
          target: 'http://localhost:8081',
          changeOrigin: true,
          ws: true,
          rewrite: (path) => path.replace(/^\/api/, ''),
        },
        // ---- platform-auth：同源入口（见上方注释）----
        // platform-auth 有 server.servlet.context-path=/auth，所有端点（/oauth2/**、/session/**、
        // /login.html、/user/**、/.well-known/**、登录页静态资源 /assets/**）都在这个前缀下，
        // 于是**一条前缀规则就够**，新增端点也不用再改这里。
        // ⚠️ 不要用 rewrite 把 /auth 剥掉：auth 那边正是靠这个前缀匹配的。
        '/auth': oauthProxy,
        // ⚠️ /callback 不代理：那是 SPA 自己的路由（redirect_uri 的落点），不在 /auth 下。
      },
    },
  }
})
