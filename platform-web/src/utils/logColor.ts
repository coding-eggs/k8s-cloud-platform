/**
 * 日志行着色：**一套规则，两个渲染器**。
 *
 * 单 Pod 弹窗用 xterm 渲染，要的是 ANSI 转义序列；工作负载聚合视图是 `<pre>`，要的是 HTML 片段。
 * 但"哪一段是什么"必须只有一份定义 —— 同一个 Spring Boot 行在两个视图里显示成不同的颜色，
 * 比不显示颜色更让人怀疑自己看错了。
 *
 * 切分口径见下面的 `SPRING_LINE_RE`：`<ts> <LEVEL> <pid> --- [service] [thread] <class> : <msg>`。
 * 各段之间的原始空白都单独成段并原样保留，所以重建后**列对齐不变**。
 */
export type SegmentKind = 'plain' | 'level-info' | 'level-warn' | 'level-error' | 'thread' | 'class'

export interface LogSegment {
  text: string
  kind: SegmentKind
}

/** 级别 → 分段种类（未识别的级别不着色，保持 plain） */
const LEVEL_KIND: Record<string, SegmentKind> = {
  INFO: 'level-info',
  DEBUG: 'level-info',
  TRACE: 'level-info',
  WARN: 'level-warn',
  ERROR: 'level-error',
}

// <ts> <LEVEL> <pid> --- [service] [thread] <class> : <msg>
// 捕获各段之间的原始空白（m[2]/m[4]/…），重建时原样保留 → 列对齐不变；方括号是字面量，重建时手动补回
const SPRING_LINE_RE =
  /^(\S+)(\s+)(\w{2,7})(\s+)(\S+)(\s+)---(\s+)\[([^\]]*)\](\s+)\[([^\]]*)\](\s+)(.+?)(\s*:\s*)(.*)$/

/** 拆成带种类的小段；**不是** Spring Boot 格式（如堆栈续行）时返回 null，由调用方原样输出 */
export function springSegments(line: string): LogSegment[] | null {
  const m = SPRING_LINE_RE.exec(line)
  if (!m) return null
  return [
    { text: m[1] ?? '', kind: 'plain' },                    // 时间
    { text: m[2] ?? '', kind: 'plain' },
    { text: m[3] ?? '', kind: LEVEL_KIND[(m[3] ?? '').toUpperCase()] ?? 'plain' }, // 级别
    { text: m[4] ?? '', kind: 'plain' },
    { text: m[5] ?? '', kind: 'plain' },                    // pid
    { text: m[6] ?? '', kind: 'plain' },
    { text: '---', kind: 'plain' },
    { text: m[7] ?? '', kind: 'plain' },
    { text: '[' + (m[8] ?? '') + ']', kind: 'plain' },      // 服务名
    { text: m[9] ?? '', kind: 'plain' },
    { text: '[' + (m[10] ?? '') + ']', kind: 'thread' },    // [线程]
    { text: m[11] ?? '', kind: 'plain' },
    { text: m[12] ?? '', kind: 'class' },                   // 类名
    { text: m[13] ?? '', kind: 'plain' },                   // " : " 分隔符
    { text: m[14] ?? '', kind: 'plain' },                   // msg
  ]
}

const ANSI: Record<SegmentKind, string> = {
  plain: '',
  'level-info': '\x1b[32m',
  'level-warn': '\x1b[33m',
  'level-error': '\x1b[31m',
  thread: '\x1b[35m',
  class: '\x1b[36m',
}
const ANSI_RESET = '\x1b[0m'

/** xterm 用：套 ANSI 色（保留原始对齐，只给指定片段上色） */
export function toAnsi(line: string): string {
  const segs = springSegments(line)
  if (!segs) return line
  return segs.map((s) => (ANSI[s.kind] ? ANSI[s.kind] + s.text + ANSI_RESET : s.text)).join('')
}

/** HTML 片段的 class（配色在消费方的样式里，用各自的主题变量） */
const HTML_CLASS: Record<SegmentKind, string> = {
  plain: '',
  'level-info': 'lg-info',
  'level-warn': 'lg-warn',
  'level-error': 'lg-error',
  thread: 'lg-thread',
  class: 'lg-class',
}

/** ⚠️ 日志内容是**集群里的进程**写出来的，等于不可信输入 —— 走 v-html 前必须转义 */
export function escapeHtml(s: string): string {
  return s.replace(/[&<>]/g, (c) => (c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'))
}

/** `<pre>` 用：套 `<span class>`（**已转义**），不成段时整行转义后原样返回 */
export function toHtml(line: string): string {
  const segs = springSegments(line) ?? [{ text: line, kind: 'plain' as SegmentKind }]
  return segs
    .map((s) => (HTML_CLASS[s.kind] ? `<span class="${HTML_CLASS[s.kind]}">${escapeHtml(s.text)}</span>` : escapeHtml(s.text)))
    .join('')
}
