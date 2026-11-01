import type { PermissionGroup, PermissionRuleView } from '@/types'
import { computed, type Ref } from 'vue'

export interface HighlightSegment {
  text: string
  hit: boolean
}

/**
 * 权限点的本地搜索：大小写不敏感、空格分隔 = AND；匹配范围是 code / URL / 说明。
 *
 * <p>高亮用**分段返回 + 模板渲染**，不生成 HTML（不用 v-html）—— 说明字段来自数据库，
 * 直接拼 innerHTML 会有注入面。
 */
export function usePermissionSearch(keyword: Ref<string>) {
  const terms = computed(() =>
    keyword.value
      .trim()
      .toLowerCase()
      .split(/\s+/)
      .filter(Boolean),
  )
  const active = computed(() => terms.value.length > 0)

  /** 组是否命中：所有词都出现在「code + 各规则 URL + 各规则说明」的拼接里。 */
  function groupMatches(g: PermissionGroup): boolean {
    if (!active.value) return true
    const hay = [g.code, ...g.rules.flatMap((r) => [r.resource ?? '', r.description ?? ''])].join('\n').toLowerCase()
    return terms.value.every((t) => hay.includes(t))
  }

  /** 规则行是否命中（用于高亮命中行；不决定组的显隐）。 */
  function ruleMatches(r: PermissionRuleView): boolean {
    if (!active.value) return true
    const hay = [r.resource ?? '', r.description ?? ''].join('\n').toLowerCase()
    return terms.value.every((t) => hay.includes(t))
  }

  /** 把一段文本切成「命中 / 未命中」的连续片段，交给模板着色。 */
  function segments(text: string | null | undefined): HighlightSegment[] {
    const s = text ?? ''
    if (!active.value || !s) return [{ text: s, hit: false }]

    const lower = s.toLowerCase()
    const marks: boolean[] = new Array(s.length).fill(false)
    for (const t of terms.value) {
      let from = 0
      while (from <= lower.length) {
        const i = lower.indexOf(t, from)
        if (i < 0) break
        for (let k = i; k < i + t.length; k++) marks[k] = true
        from = i + t.length
      }
    }

    const out: HighlightSegment[] = []
    for (let i = 0; i < s.length; i++) {
      const hit = marks[i]!
      if (out.length && out[out.length - 1]!.hit === hit) out[out.length - 1]!.text += s[i]
      else out.push({ text: s[i]!, hit })
    }
    return out
  }

  return { terms, active, groupMatches, ruleMatches, segments }
}
