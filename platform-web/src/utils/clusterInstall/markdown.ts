/**
 * 极小的 Markdown → HTML 渲染器，只服务于向导生成的 README（预览用）。
 *
 * <p>支持的子集：`#` 标题、无序/有序列表、围栏代码块、引用块、段落，行内 `code` 与 **加粗**。
 * 先整体转义 HTML 再做行内替换，所以产物里的原始 HTML 不会被执行（README 是本地生成的，但预览层不该有洞）。
 */

export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function inline(raw: string): string {
  return escapeHtml(raw)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
}

export function renderMarkdown(md: string): string {
  const lines = md.split(/\r?\n/)
  const out: string[] = []
  let list: 'ul' | 'ol' | null = null
  let para: string[] = []
  let code: string[] | null = null
  let quote: string[] = []

  const flushPara = (): void => {
    if (para.length > 0) {
      out.push(`<p>${inline(para.join(' '))}</p>`)
      para = []
    }
  }
  // 连续的 > 行属于同一个引用块，合成一段再输出（否则每行一个方框）
  const flushQuote = (): void => {
    if (quote.length > 0) {
      out.push(`<blockquote>${inline(quote.join(' '))}</blockquote>`)
      quote = []
    }
  }
  const closeList = (): void => {
    if (list) {
      out.push(`</${list}>`)
      list = null
    }
  }
  const openList = (kind: 'ul' | 'ol'): void => {
    if (list === kind) return
    closeList()
    out.push(`<${kind}>`)
    list = kind
  }

  for (const line of lines) {
    // 代码块：原样收集，最后统一转义
    const fence = /^\s*```(.*)$/.exec(line)
    if (fence) {
      if (code) {
        out.push(`<pre><code>${escapeHtml(code.join('\n'))}</code></pre>`)
        code = null
      } else {
        flushPara()
        flushQuote()
        closeList()
        code = []
      }
      continue
    }
    if (code) {
      code.push(line)
      continue
    }

    if (!line.trim()) {
      flushPara()
      flushQuote()
      closeList()
      continue
    }

    const heading = /^(#{1,4})\s+(.*)$/.exec(line)
    if (heading) {
      flushPara()
      flushQuote()
      closeList()
      const level = (heading[1] ?? '#').length
      out.push(`<h${level}>${inline(heading[2] ?? '')}</h${level}>`)
      continue
    }

    const quoted = /^\s*>\s?(.*)$/.exec(line)
    if (quoted) {
      flushPara()
      closeList()
      quote.push(quoted[1] ?? '')
      continue
    }

    const ul = /^\s*[-*]\s+(.*)$/.exec(line)
    if (ul) {
      flushQuote()
      openList('ul')
      out.push(`<li>${inline(ul[1] ?? '')}</li>`)
      continue
    }
    const ol = /^\s*\d+\.\s+(.*)$/.exec(line)
    if (ol) {
      flushQuote()
      openList('ol')
      out.push(`<li>${inline(ol[1] ?? '')}</li>`)
      continue
    }

    // 列表项的续行（缩进、且上一段输出是 <li>）：并进同一项，而不是另起一个段落
    if (list && /^\s+\S/.test(line) && (out[out.length - 1] ?? '').endsWith('</li>')) {
      const last = out.pop() ?? ''
      out.push(`${last.slice(0, -'</li>'.length)} ${inline(line.trim())}</li>`)
      continue
    }

    flushQuote()
    closeList()
    para.push(line.trim())
  }

  if (code) out.push(`<pre><code>${escapeHtml(code.join('\n'))}</code></pre>`)
  flushQuote()
  flushPara()
  closeList()
  return out.join('\n')
}
