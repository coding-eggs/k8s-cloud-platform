/**
 * 命令面板的匹配与排序（纯函数，不依赖 Vue）。
 *
 * - 关键字按空白切成多个词，**每个词都要命中**（同时满足），大小写不敏感；
 * - 每个词在「标题 / 页内功能所在页 / 关键词 / 拼音全拼 / 拼音首字母 / 域名 / 路径」里取最高分，词分相加；
 * - 拼音由调用方注入（pinyin-pro 懒加载，没加载完之前只是少了拼音这一路，不影响其余匹配）。
 */

export interface Searchable {
  title: string
  pageTitle: string
  keywords: string[]
  domainLabel: string
  path: string
}

export interface PinyinForms {
  /** 全拼，只取中文部分，如 gongzuofuzai（英文段本来就由标题匹配覆盖） */
  full: string
  /** 首字母，只取中文部分，如 gzfz；「RBAC 模板」→ mb（英文段混进来会拼出 cm 这类跨词假命中） */
  initials: string
}

export type PinyinOf = (text: string) => PinyinForms

/** 「新建 / 新增 / 添加」统一成「创建」：页面按钮用词不一，搜哪个都该命中 */
function norm(s: string): string {
  return s.toLowerCase().replace(/\s+/g, '').replace(/新建|新增|添加/g, '创建')
}

function scoreToken(
  token: string,
  e: Searchable,
  py: PinyinForms | null,
  pagePy: PinyinForms | null,
  kwPy: PinyinForms[],
): number {
  const title = norm(e.title)
  if (title.startsWith(token)) return 100
  if (title.includes(token)) return 80
  let best = 0
  for (const k of e.keywords) {
    const kw = norm(k)
    if (kw === token) best = Math.max(best, 70)
    else if (kw.startsWith(token)) best = Math.max(best, 60)
    // 关键词中段命中只认 ≥3 个字符：短词（ns、cm）会在 daemonset、dockerconfigjson 这类长词里乱命中
    else if (token.length >= 3 && kw.includes(token)) best = Math.max(best, 50)
  }
  for (const p of kwPy) {
    if (p.full.startsWith(token) || (token.length >= 2 && p.initials.startsWith(token))) best = Math.max(best, 48)
  }
  if (py) {
    if (py.initials.startsWith(token)) best = Math.max(best, 58)
    else if (py.full.startsWith(token)) best = Math.max(best, 55)
    // 中段命中只认 ≥2 个字母：单字母在全拼里几乎处处命中，只会制造噪声
    else if (token.length >= 2 && py.initials.includes(token)) best = Math.max(best, 42)
    // 全拼中段要 ≥3：两个字母常跨音节假命中（中间件实例 = zhongjian·jianshi 里有 ns）
    else if (token.length >= 3 && py.full.includes(token)) best = Math.max(best, 40)
  }
  const page = norm(e.pageTitle)
  if (page !== title && page.includes(token)) best = Math.max(best, 45)
  if (pagePy && ((token.length >= 2 && pagePy.initials.includes(token)) || (token.length >= 3 && pagePy.full.includes(token)))) {
    best = Math.max(best, 30)
  }
  if (norm(e.domainLabel).includes(token)) best = Math.max(best, 25)
  // 路径只认 ≥3 个字符（apps/instances 不该被 ns 命中）
  if (token.length >= 3 && e.path.toLowerCase().includes(token)) best = Math.max(best, 20)
  return best
}

const HAS_ZH = /[一-鿿]/

/** 0 = 不命中；否则分数越高越靠前 */
export function scoreEntry(keyword: string, e: Searchable, pinyinOf: PinyinOf | null): number {
  const tokens = keyword.split(/\s+/).map(norm).filter(Boolean)
  if (!tokens.length) return 0
  const py = pinyinOf ? pinyinOf(e.title) : null
  const pagePy = pinyinOf && e.pageTitle !== e.title ? pinyinOf(e.pageTitle) : null
  const kwPy = pinyinOf ? e.keywords.filter((k) => HAS_ZH.test(k)).map(pinyinOf) : []
  let total = 0
  for (const t of tokens) {
    const s = scoreToken(t, e, py, pagePy, kwPy)
    if (s === 0) return 0
    total += s
  }
  return total
}
