/**
 * 日志行的**纯函数**口径 —— 单 Pod 的日志弹窗与工作负载级的聚合视图共用同一份。
 *
 * ⚠️ 为什么必须共用：同一份日志在两个视图里要显示出**一样的行**。各写一份的话，
 *    前缀正则或小数秒的比法迟早分叉，而那种分叉极难发现（症状只是"两处看着有点不一样"）。
 *
 * ⚠️ 本仓前端**没有测试框架**（没有 vitest/jest），所以这一层的验证走浏览器冒烟台：
 *    `smoke-log-lines.ts` 直接 import 本模块断言 —— 那是本仓既有的做法。
 */

/** k8s 注入的时间戳前缀：RFC3339Nano（UTC 'Z'）+ 制表符或空格。只认 UTC-Z，见 splitLogLine。 */
export const K8S_TS_PREFIX = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)[ \t]+/

/**
 * 一行 `<RFC3339Nano>\t<内容>` → `[时间戳, 内容]`；没有前缀则时间戳为空串。
 *
 * ⚠️ 刻意**不**用「找第一个制表符」那种写法：内容里本来就带制表符的行（`ls -l` 的输出、
 *    表格化的应用日志）会被从中间劈开，前半个当成时间戳、后半个当成内容 ——
 *    更糟的是那个"假时间戳"还会被当成游标推上去，**之后的增量全乱**。
 *    分隔符可能是制表符（kubelet 原样）也可能是空格（透传链路规整过），两种都认。
 * ⚠️ 只认 UTC 的 `Z`：认 `+08:00` 会把应用自己打在行首的时间戳剥掉。
 */
export function splitLogLine(line: string): [string, string] {
  const m = K8S_TS_PREFIX.exec(line)
  return m ? [m[1] ?? '', line.slice(m[0].length)] : ['', line]
}

/**
 * 小数秒补到固定 9 位，让同一来源的时间戳能**按字典序**比较。
 * ⚠️ 直接比字符串是不对的：`…:00.5Z` 与 `…:00.45Z` 按字符串比会得出前者更小，而按时间它更大。
 */
export function normTs(ts: string): string {
  const m = /^(.*?)(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$/.exec(ts)
  if (!m) return ts
  const frac = ((m[2] ?? '').slice(1) + '000000000').slice(0, 9)
  return m[1] + '.' + frac + (m[3] ?? '')
}

/** 一行日志：k8s 时间戳（没有前缀时是空串）+ **已剥掉前缀**的内容。 */
export interface LogLine {
  ts: string
  content: string
}

/**
 * 拆一批文本 → 只要**严格晚于** `startCursor` 的新行。
 *
 * ⚠️ 为什么是"严格晚于"：k8s 的 `sinceTime` 是「该时刻之后」**且边界那行会重复来一次** ——
 *    不滤的话每轮都会多出一行"上一轮的尾巴"。
 * ⚠️ 没有 k8s 前缀的行**无法比大小也无法去重**，原样收下且**不推进游标**
 *    （调用方据此知道"这批没法增量"，见 PodLogDialog 的降级处理）。
 */
export function takeNewLines(
  text: string,
  startCursor: string,
): { lines: LogLine[]; nextCursor: string } {
  const lines: LogLine[] = []
  let nextCursor = startCursor
  for (const raw of text.split('\n')) {
    if (!raw) continue
    const [ts, content] = splitLogLine(raw)
    if (!ts) {
      lines.push({ ts: '', content })
      continue
    }
    if (normTs(ts) > normTs(nextCursor)) {
      lines.push({ ts, content })
      nextCursor = ts
    }
  }
  return { lines, nextCursor }
}

/**
 * 两个时间戳的先后（-1 / 0 / 1）。**两个视图都调它** —— 各写一份比较器迟早分叉，
 * 而那种分叉的症状只是"两处的顺序偶尔不一样"，极难发现。
 * ⚠️ 约定：**没有时间戳的一律算"更晚"**（于是排在后面），两个都没有则相等。
 */
export function compareTs(a: string, b: string): number {
  if (!a && !b) return 0
  if (!a) return 1
  if (!b) return -1
  const x = normTs(a)
  const y = normTs(b)
  return x < y ? -1 : x > y ? 1 : 0
}

/**
 * 批内排序：有时间戳的按时间戳，**没有时间戳的一律排在后面**（保持它们原来那点相对顺序）。
 *
 * ⚠️ 只排"这一批"。跨批全局有序要缓存"还没到齐的更早行"，延迟与内存都不好控 ——
 *    而排障时多等几秒就是副作用（spec §3.3）。
 */
export function sortByTs(lines: LogLine[]): LogLine[] {
  return lines
    .map((l, i) => ({ l, i }))
    .sort((a, b) => compareTs(a.l.ts, b.l.ts) || a.i - b.i) // 相等时保持原顺序（Array.sort 现代实现是稳定的）
    .map((x) => x.l)
}

/**
 * 聚合视图的行前缀 —— **照抄 `kubectl logs -l … --all-containers --prefix`** 的形态：
 * `[pod/<pod名>/<容器名>]`。照抄的理由：排障的人多半先想到 kubectl 那条命令，
 * 两边长得一样就不用重新认（真跑出来的样子见 spec §0）。
 */
export function prefixOf(pod: string, container: string): string {
  return `[pod/${pod}/${container}] `
}
