<script setup lang="ts">
/**
 * Pod 日志弹窗（xterm.js 原生终端 + sinceTime 增量轮询）。
 * - 显示：用 xterm.js 渲染，等宽/配色/换行/滚动缓冲跟 shell 一致；可交互（本地回显，回车打空行）。
 * - 跟随：不用 follow=true 长连接（多容器 Pod 上 kubelet/运行时流式不稳定，见 k8s#108678、fabric8#368）。
 *   改为每 POLL_MS 用 sinceTime=<上次时间戳> 增量拉取「上次之后的所有新行」→ 不丢行；
 *   后端恒带 timestamps=true（每行 RFC3339Nano 前缀），前端据此做游标、展示时剥掉前缀还原原始日志。
 * - 首次打开用 tailLines（默认 200，可下拉切换）回看最近 N 行做引导；容器安静也不空，优于 sinceSeconds 时间窗。
 * - 自动滚动：写入默认贴底；用户上翻查看历史时不抢，并浮出「回到底部」按钮。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import type { K8sPod } from '@/types'
import { getAccessToken } from '@/auth/oauth'

const props = defineProps<{
  modelValue: boolean
  pod: K8sPod | null
  /** scope=cluster（节点详情等集群域）时无需 tenantId，走 /resource/nodes/{node}/pods/... 端点 */
  scope?: 'namespace' | 'cluster'
  nodeName?: string
  tenantId?: string
  clusterId: string
  namespace: string
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const logContainer = ref('')
// 初始化回看行数（tailLines）：默认 200，切换即重新拉取重渲染；可选值见 TAIL_OPTIONS
const tailLines = ref(200)
const isFullscreen = ref(false)
// 是否贴底（xterm 上翻查看历史时置 false，浮出「回到底部」按钮）
const atBottom = ref(true)
// 搜索/过滤关键词（大小写不敏感）；非空时只显示包含它的行
const searchTerm = ref('')
// 彩色开关：按 Spring Boot 级别分段着色
const colorEnabled = ref(true)
// 当前显示行数（无搜索=总行数；有搜索=匹配数）
const matchCount = ref(0)
const matchCountText = computed(() => (searchTerm.value.trim() ? `${matchCount.value} 条匹配` : ''))

let term: Terminal | null = null
let fitAddon: FitAddon | null = null
let pollTimer: number | null = null
let logAbort: AbortController | null = null
/** 已显示的最后一条日志的时间戳（原始串，作为下次 sinceTime 游标） */
let cursor = ''
/** 是否已提示过「响应缺时间戳」（每次会话重置），避免每轮刷屏 */
let warnedNoTs = false
/** 内存里保留的原始日志行（已剥 k8s 前缀、未着色），供搜索/彩色切换时整体重渲染 */
let displayLines: string[] = []
/** 搜索输入防抖定时器 */
let searchTimer: number | null = null

const termEl = ref<HTMLElement | null>(null)

/** 轮询间隔（ms） */
const POLL_MS = 3000
/** 初始化回看行数可选项（tailLines：返回最近 N 行，容器安静也不空，优于 sinceSeconds 时间窗） */
const TAIL_OPTIONS = [100, 200, 500, 1000]
/** 内存缓冲上限（超过丢弃最旧行），避免话痨容器无限吃内存 */
const MAX_LINES = 10000

function fit(): void {
  try {
    fitAddon?.fit()
  } catch {
    /* 容器尚未就绪时忽略 */
  }
}

/** Ctrl/Cmd+C：有选区则复制选中日志到剪贴板（xterm 已剥 ANSI，拿到干净原文）。
 *  capture 阶段拦截并 stopPropagation，避免再被 onData 回显成 \x03、也不触发浏览器默认。 */
function onTermKeydown(e: KeyboardEvent): void {
  const isCopy = (e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey && (e.key === 'c' || e.key === 'C')
  if (isCopy && term?.hasSelection()) {
    e.preventDefault()
    e.stopPropagation()
    void navigator.clipboard.writeText(term.getSelection())
  }
}

/** 懒创建 xterm（幂等）：只读终端 + 固定 scrollback 缓冲 */
function ensureTerm(): void {
  if (term || !termEl.value) return
  term = new Terminal({
    fontSize: 13,
    fontFamily: "Consolas, 'JetBrains Mono', Menlo, monospace",
    theme: { background: '#0d1117', foreground: '#e6edf3', cursor: '#e6edf3' },
    scrollback: 5000,
    convertEol: true,
    cursorBlink: true, // 像 shell：底部闪烁块光标
  })
  fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.open(termEl.value)
  fit()
  // 可交互（像 shell）：本地回显按键，回车 \r → 换行打出空行
  term.onData((data) => term!.write(data.replace(/\r/g, '\n')))
  term.onScroll(() => {
    const buf = term!.buffer.active
    atBottom.value = buf.viewportY >= buf.baseY
  })
  // capture 阶段挂在外层宿主上，先于 xterm 内部 handler；仅拦截「有选区的 Ctrl/Cmd+C」
  termEl.value.addEventListener('keydown', onTermKeydown, true)
}

/** 拉取日志：sinceTime（增量）或 tailLines（首次回看最近 N 行）二选一；返回原始文本 */
async function fetchLogs(sinceTime?: string, tailLinesParam?: number): Promise<string> {
  const clusterScoped = props.scope === 'cluster'
  const podName = encodeURIComponent(props.pod!.name)
  const basePath = clusterScoped
    ? `/api/resource/nodes/${encodeURIComponent(props.nodeName ?? '')}/pods/${encodeURIComponent(props.namespace)}/${podName}/logs`
    : `/api/resource/pods/${podName}/logs`
  const q = new URLSearchParams()
  if (!clusterScoped && props.tenantId) q.set('tenantId', props.tenantId)
  q.set('clusterId', props.clusterId)
  q.set('namespace', props.namespace)
  if (logContainer.value) q.set('container', logContainer.value)
  if (sinceTime) q.set('sinceTime', sinceTime)
  else if (tailLinesParam != null) q.set('tailLines', String(tailLinesParam))
  const resp = await fetch(
    `${basePath}?${q.toString()}`,
    { headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` }, signal: logAbort!.signal },
  )
  const ctype = resp.headers.get('content-type') ?? ''
  if (!resp.ok || ctype.includes('application/json')) {
    let msg = `HTTP ${resp.status}`
    try {
      const body = (await resp.json()) as { code?: number; msg?: string }
      if (body.msg) msg = body.msg
    } catch { /* 保留 HTTP 状态 */ }
    throw new Error(msg)
  }
  return await resp.text()
}

/** k8s 注入的时间戳前缀：RFC3339Nano（UTC 'Z'）+ 制表符/空格。只匹配 UTC-Z，避免误伤应用自己的 +08:00 时间戳 */
const K8S_TS_PREFIX = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)[ \t]+/

/**
 * 拆出 [k8s时间戳, 原始内容]（无 k8s 前缀则 ts=''）。
 * kubelet 标准分隔符是制表符；但透传链路可能把它规整成空格，故再加一条「行首 UTC-Z RFC3339 + 空白」兜底。
 */
function splitLine(line: string): [string, string] {
  const i = line.indexOf('\t')
  if (i > 0) return [line.slice(0, i), line.slice(i + 1)]
  const m = K8S_TS_PREFIX.exec(line)
  if (m) {
    return [m[1] ?? '', line.slice(m[0].length)]
  }
  return ['', line]
}

/** 分数秒补到固定 9 位，使同一容器（同格式/时区）的时间戳可按字典序比较 */
function normTs(ts: string): string {
  const m = /^(.*?)(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$/.exec(ts)
  if (!m) return ts
  const frac = ((m[2] || '').slice(1) + '000000000').slice(0, 9)
  return m[1] + '.' + frac + (m[3] || '')
}

/** Spring Boot 日志分段着色（保留原始对齐，只给指定片段套 ANSI 色）：
 *  时间/pid/服务名/msg = 默认；级别 INFO·DEBUG·TRACE 绿 / WARN 黄 / ERROR 红；[线程] 紫；类名蓝 */
const C_GREEN = '\x1b[32m'
const C_YELLOW = '\x1b[33m'
const C_RED = '\x1b[31m'
const C_PURPLE = '\x1b[35m'
const C_BLUE = '\x1b[36m'
const C_RESET = '\x1b[0m'

/** 级别 → 颜色（未识别的级别不着色） */
const LEVEL_COLOR: Record<string, string> = {
  INFO: C_GREEN, DEBUG: C_GREEN, TRACE: C_GREEN,
  WARN: C_YELLOW, ERROR: C_RED,
}

// <ts> <LEVEL> <pid> --- [service] [thread] <class> : <msg>
// 捕获各段之间的原始空白（m[2]/m[4]/...），重建时原样保留 → 列对齐不变；方括号是字面量需手动补回
const SPRING_LINE_RE = /^(\S+)(\s+)(\w{2,7})(\s+)(\S+)(\s+)---(\s+)\[([^\]]*)\](\s+)\[([^\]]*)\](\s+)(.+?)(\s*:\s*)(.*)$/

function colorize(line: string): string {
  const m = SPRING_LINE_RE.exec(line)
  if (!m) return line // 非 Spring Boot 格式（如堆栈续行）：原样
  const lvlColor = LEVEL_COLOR[(m[3] ?? '').toUpperCase()] ?? ''
  return [
    m[1],                                            // 时间：默认
    m[2],
    lvlColor ? lvlColor + m[3] + C_RESET : m[3],     // 级别：绿/黄/红
    m[4],
    m[5],                                            // pid：默认
    m[6],
    '---',                                           // 分隔符：默认
    m[7],
    '[' + m[8] + ']',                                // 服务名：默认
    m[9],
    C_PURPLE + '[' + m[10] + ']' + C_RESET,          // [线程]：紫
    m[11],
    C_BLUE + m[12] + C_RESET,                        // 类名：蓝
    m[13],                                           // " : " 分隔符：默认
    m[14],                                           // msg：默认
  ].join('')
}

/**
 * 解析一次拉回的日志文本，返回「新增的原始行（已剥 k8s 前缀、未着色）+ 新游标」。
 * - 有 k8s 时间戳（<RFC3339Nano>\t）：按时间戳严格去重、推进游标。
 * - 无时间戳（后端未启用 timestamps / 旧构建）：退化为「整批追加 + 墙钟游标」，保证仍能滚动，并告警一次。
 */
function processLog(text: string, startCursor: string): { newContents: string[]; nextCursor: string } {
  const newContents: string[] = []
  let nextCursor = startCursor
  let hadTs = false
  for (const line of text.split('\n')) {
    if (!line) continue
    const [ts, content] = splitLine(line)
    if (ts) {
      hadTs = true
      if (normTs(ts) > normTs(nextCursor)) {
        newContents.push(content)
        nextCursor = ts
      }
    } else {
      // 无前缀：无法精确去重，直接追加（避免整屏冻结）
      newContents.push(content)
    }
  }
  if (!hadTs && newContents.length) {
    nextCursor = new Date().toISOString()
    if (!warnedNoTs) {
      warnedNoTs = true
      console.warn('[PodLog] 响应缺少 k8s 时间戳前缀（<ts>\\t），请确认 k8s-server 已重建并启用 usingTimestamps；当前退化为按批次追加。')
    }
  }
  return { newContents, nextCursor }
}

/** 内存缓冲封顶：超过 MAX_LINES 丢弃最旧行 */
function trimLines(): void {
  if (displayLines.length > MAX_LINES) displayLines.splice(0, displayLines.length - MAX_LINES)
}

/** 更新计数：无搜索=总行数；有搜索=匹配数 */
function updateMatchCount(): void {
  const q = searchTerm.value.trim().toLowerCase()
  matchCount.value = q ? displayLines.filter((l) => l.toLowerCase().includes(q)).length : displayLines.length
}

/** 追加新行：入内存缓冲（封顶），按当前过滤+彩色增量写入终端，更新计数 */
function appendNew(newContents: string[]): void {
  if (!newContents.length || !term) return
  for (const c of newContents) displayLines.push(c)
  trimLines()
  const q = searchTerm.value.trim().toLowerCase()
  let buf = ''
  for (const c of newContents) {
    if (q && !c.toLowerCase().includes(q)) continue
    buf += (colorEnabled.value ? colorize(c) : c) + '\n'
  }
  if (buf) term.write(buf)
  updateMatchCount()
}

/** 整体重渲染（搜索/彩色切换时）：清屏后按当前过滤+彩色重写全部缓冲行 */
function renderAll(): void {
  if (!term) return
  term.clear()
  const q = searchTerm.value.trim().toLowerCase()
  let buf = ''
  for (const line of displayLines) {
    if (q && !line.toLowerCase().includes(q)) continue
    buf += (colorEnabled.value ? colorize(line) : line) + '\n'
  }
  term.write(buf)
  updateMatchCount()
}

/** 首次回看：拉最近 tailLines 行，写入终端并定初始游标 */
async function bootstrap(): Promise<void> {
  if (!props.pod) return
  try {
    const text = await fetchLogs(undefined, tailLines.value)
    const { newContents, nextCursor } = processLog(text, '')
    appendNew(newContents)
    cursor = nextCursor || new Date().toISOString() // 无历史则从当前时刻开始追新
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      term?.write(`\r\n[错误] ${(e as Error).message}\n`)
    }
  }
}

/** 增量轮询：拉 sinceTime=cursor 之后的新行（严格更新），追加并推进游标 */
async function pollOnce(): Promise<void> {
  if (!props.pod || !logAbort || !cursor) return
  try {
    const text = await fetchLogs(cursor)
    const { newContents, nextCursor } = processLog(text, cursor)
    appendNew(newContents)
    if (nextCursor !== cursor) cursor = nextCursor
  } catch {
    /* 轮询偶发失败忽略，保留已有内容 */
  }
}

/** 开始/重新开始一次会话：清屏、重置游标、立即拉取并启动轮询 */
function beginSession(): void {
  if (!props.pod) return
  stopPolling()
  logAbort = new AbortController()
  term?.clear()
  displayLines = []
  cursor = ''
  warnedNoTs = false
  matchCount.value = 0
  void bootstrap()
  pollTimer = window.setInterval(() => void pollOnce(), POLL_MS)
}

/** el-dialog @opened：DOM 就绪后创建终端并开始拉取（每次打开都触发） */
function onOpened(): void {
  ensureTerm()
  fit()
  beginSession()
}

function stopPolling(): void {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  logAbort?.abort()
  logAbort = null
}

/** 清除：清空显示与缓冲，之后只显示新日志（游标跳到当前时刻） */
function clearLog(): void {
  term?.clear()
  displayLines = []
  cursor = new Date().toISOString()
  matchCount.value = 0
}

function jumpToBottom(): void {
  term?.scrollToBottom()
  atBottom.value = true
}

/** 打开：设默认容器、重置全屏；关闭：停轮询。数据拉取统一在 @opened 里启动 */
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      logContainer.value = (props.pod?.containers ?? [])[0] ?? ''
      tailLines.value = 200 // 重置为默认回看行数
      isFullscreen.value = false
      searchTerm.value = ''
      matchCount.value = 0
      stopPolling()
    } else {
      stopPolling()
    }
  },
  { immediate: true },
)

/** 全屏切换后重排终端尺寸 */
watch(isFullscreen, () => nextTick(fit))

/** 彩色开关切换 → 整体重渲染（对已有历史行即时生效） */
watch(colorEnabled, () => renderAll())

/** 搜索词变化（含清空按钮）→ 防抖后整体重渲染过滤结果 */
watch(searchTerm, () => {
  if (searchTimer != null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => renderAll(), 250)
})

function onResize(): void {
  fit()
}
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (searchTimer != null) window.clearTimeout(searchTimer)
  stopPolling()
  termEl.value?.removeEventListener('keydown', onTermKeydown, true)
  term?.dispose()
  term = null
  fitAddon = null
})
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`日志 · ${pod?.name ?? ''}`"
    width="min(1280px, 94vw)"
    class="log-dialog"
    :fullscreen="isFullscreen"
    @opened="onOpened"
  >
    <div class="log-toolbar">
      <el-select v-model="logContainer" size="small" style="width: 170px" @change="beginSession()">
        <el-option v-for="c in pod?.containers ?? []" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="tailLines" size="small" style="width: 130px" @change="beginSession()">
        <el-option v-for="n in TAIL_OPTIONS" :key="n" :label="`最近 ${n} 行`" :value="n" />
      </el-select>
      <el-input
        v-model="searchTerm"
        size="small"
        clearable
        placeholder="搜索 / 过滤日志…"
        class="log-search"
      />
      <span v-if="matchCountText" class="match-count">{{ matchCountText }}</span>
      <el-button size="small" @click="clearLog()">清除</el-button>
      <el-button size="small" @click="beginSession()" style="margin-left: 0">重新加载</el-button>
      <div class="spacer" />
      <el-button
        size="small"
        :type="colorEnabled ? 'primary' : 'default'"
        @click="colorEnabled = !colorEnabled"
      >
        彩色
      </el-button>
      <el-button size="small" @click="isFullscreen = !isFullscreen" style="margin-left: 0">
        {{ isFullscreen ? '退出全屏' : '全屏' }}
      </el-button>
    </div>

    <div class="log-wrap">
      <div ref="termEl" class="xterm-host"></div>
      <el-button
        v-if="!atBottom"
        class="jump-bottom"
        size="small"
        circle
        type="primary"
        @click="jumpToBottom"
      >
        <el-icon><ArrowDown /></el-icon>
      </el-button>
    </div>
  </el-dialog>
</template>

<style scoped>
.log-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.spacer {
  flex: 1;
}
.log-search {
  flex: 1;
  min-width: 120px;
  max-width: 340px;
}
.match-count {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  white-space: nowrap;
}
.log-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
}
.xterm-host {
  flex: 1;
  min-height: 0;
  padding: 8px;
  border-radius: 8px;
  background: #0d1117;
  border: 1px solid var(--border);
  overflow: hidden;
}
.jump-bottom {
  position: absolute;
  right: 16px;
  bottom: 16px;
}
</style>

<style>
/* el-dialog 内部结构被 Element Plus 剥离了 scoped 属性，无法用 :deep 触达；
   改用 .log-dialog 前缀的全局规则（该类为本组件独有）约束弹窗盒体与 body 高度。 */
.log-dialog {
  display: flex;
  flex-direction: column;
  margin-top: 7rem;
  min-width: 720px;
}
.log-dialog:not(.is-fullscreen) {
  height: min(720px, 85vh);
}
.log-dialog .el-dialog__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
