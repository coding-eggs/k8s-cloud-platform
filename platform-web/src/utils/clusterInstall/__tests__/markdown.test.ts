import { test } from 'node:test'
import assert from 'node:assert/strict'
import { renderMarkdown } from '../markdown.ts'

test('标题与段落', () => {
  const html = renderMarkdown('# 标题\n\n正文一段')
  assert.match(html, /<h1>标题<\/h1>/)
  assert.match(html, /<p>正文一段<\/p>/)
})

test('无序与有序列表', () => {
  const html = renderMarkdown('- a\n- b\n\n1. x\n2. y')
  assert.match(html, /<ul>\n<li>a<\/li>\n<li>b<\/li>\n<\/ul>/)
  assert.match(html, /<ol>\n<li>x<\/li>\n<li>y<\/li>\n<\/ol>/)
})

test('围栏代码块保留内容并转义尖括号', () => {
  const html = renderMarkdown('```\nkubectl get node <name>\n```')
  assert.match(html, /<pre><code>kubectl get node &lt;name&gt;<\/code><\/pre>/)
})

test('行内 code 与加粗', () => {
  const html = renderMarkdown('跑 `ansible-playbook`，**不要**直接执行')
  assert.match(html, /<code>ansible-playbook<\/code>/)
  assert.match(html, /<strong>不要<\/strong>/)
})

test('原始 HTML 被转义，不会执行', () => {
  const html = renderMarkdown('<script>alert(1)</script>')
  assert.doesNotMatch(html, /<script>/)
  assert.match(html, /&lt;script&gt;/)
})

test('引用块', () => {
  const html = renderMarkdown('> ⚠️ 注意明文密码')
  assert.match(html, /<blockquote>⚠️ 注意明文密码<\/blockquote>/)
})

test('列表项的缩进续行并入同一项，不会被拆成段落', () => {
  const html = renderMarkdown('- 第一行\n  第二行\n- 另一项')
  assert.equal((html.match(/<li>/g) ?? []).length, 2)
  assert.match(html, /<li>第一行 第二行<\/li>/)
  assert.doesNotMatch(html, /<p>第二行<\/p>/)
})

test('连续的引用行合成一个引用块', () => {
  const html = renderMarkdown('> 第一行\n> 第二行\n\n正文')
  assert.equal((html.match(/<blockquote>/g) ?? []).length, 1)
  assert.match(html, /<blockquote>第一行 第二行<\/blockquote>/)
  assert.match(html, /<p>正文<\/p>/)
})
