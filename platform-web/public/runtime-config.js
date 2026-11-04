// 开发用占位：生产环境这份文件由 nginx 按容器环境变量**现生成**，不会用到本文件
// （见 nginx.conf / nginx-https.conf 的 `location = /runtime-config.js`）。
//
// 存在的意义：index.html 里 <script src="/runtime-config.js"> 是同步脚本，dev server 下
// 没有它就会 404，控制台多一条无谓的报错。保持内容为空对象，前端照常回落到
// 「构建期 VITE_OAUTH_REDIRECT_URI → 页面 origin + /callback」。
window.__PLATFORM_RUNTIME_CONFIG__ = {}
