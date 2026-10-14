/**
 * 写剪贴板，返回是否成功。
 *
 * <p>两条路都必须有：`navigator.clipboard` 只在**安全上下文**（https 或 localhost）存在 ——
 * 这套平台常部署在 `http://<内网IP>:端口` 上，那里 `navigator.clipboard` 是 undefined
 * （不是"权限被拒"，是压根没有这个 API），只写新 API 的复制按钮在那类环境里会**静默失效**。
 * 故回退到 textarea + `execCommand('copy')`（已废弃但仍是 http 下唯一可用的路径）。
 *
 * <p>本函数原先长在 `YamlBlock.vue` 里；S3.1 的镜像浏览页也要复制，于是抽出来共用 ——
 * 复制两份的话，下一个人修了一处、漏了另一处就会分叉。
 */
export async function writeClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      /* 权限被拒 / 非用户手势 → 走下面的兜底 */
    }
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    // 不能 display:none（选不中），故移出视口
    ta.style.cssText = 'position:fixed;top:-1000px;left:-1000px;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
