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
import { takeNewLines } from '@/utils/logLines'

const props = defineProps<{
  modelValue: boolean
  pod: K8sPod | null
  /** scope=cluster（节点详情等集群域）时无需 tenantId，走 /nodes/{node}/pods/... 端点 */
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

/** 看**上一次容器实例**（CrashLoopBackOff 的死因在那一代里）。只在 Pod 真重启过时给这个开关。 */
const showPreviousToggle = computed(() => (props.pod?.restarts ?? 0) > 0)
const previous = ref(false)

/**
 * ⚠️ 上一次实例是**已终止**的容器，不会再产出任何行 —— 实测（真集群）
 * `--previous --since-time=<现在>` 的输出长度是 0。所以这个模式下只做一次 tailLines 快照、
 * **不轮询**（见 spec §9.2）。切容器或回看行数时留在同一模式里，只有重开弹窗才复位。
 */
function setPrevious(v: boolean): void {
  previous.value = v
  beginSession()
}

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
    ? `/api/nodes/${encodeURIComponent(props.nodeName ?? '')}/pods/${encodeURIComponent(props.namespace)}/${podName}/logs`
    : `/api/pods/${podName}/logs`
  // ⚠️ previous 模式没有增量可言（死掉的容器不产出新行），游标一律不发
  const since = previous.value ? undefined : sinceTime
  const q = new URLSearchParams()
  if (!clusterScoped && props.tenantId) q.set('tenantId', props.tenantId)
  q.set('clusterId', props.clusterId)
  q.set('namespace', props.namespace)
  if (logContainer.value) q.set('container', logContainer.value)
  if (previous.value) q.set('previous', 'true')
  if (since) q.set('sinceTime', since)
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

// ⚠️ k8s 时间戳前缀的拆解与比大小**不在这里**了 —— 抽到 `@/utils/logLines` 与工作负载级的
//    聚合视图共用。理由：同一份日志在两个视图里必须显示出**一样的行**，各写一份迟早分叉。
//    （顺带修掉了原来"找第一个制表符"那个写法：内容里自带制表符的行会被从中间劈开，
//     那个"假时间戳"还会被当成游标推上去，之后的增量全乱。）

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
  const { lines, nextCursor } = takeNewLines(text, startCursor)
  const newContents = lines.map((l) => l.content)
  const hadTs = lines.some((l) => !!l.ts)
  if (!hadTs && newContents.length) {
    // 无前缀：无法精确去重 —— 保持既有降级（整批追加 + 每次会话只提示一次）
    if (!warnedNoTs) {
      warnedNoTs = true
      console.warn('[PodLog] 响应缺少 k8s 时间戳前缀（<ts>\\t），请确认 k8s-server 已重建并启用 usingTimestamps；当前退化为按批次追加。')
    }
    return { newContents, nextCursor: new Date().toISOString() }
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
  if (previous.value) return // 上一次实例不会再产出（见 setPrevious 的说明）
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
  // ⚠️ previous 模式是一次性快照，不起轮询（死掉的容器不会再产出）
  if (!previous.value) {
    pollTimer = window.setInterval(() => void pollOnce(), POLL_MS)
  }
}

/** el-dialog @opened：DOM 就绪后创建终端并开始拉取（每次打开都触发） */
function onOpened(): void {
  ensureTerm()
  fit()
  // ⚠️ 每次打开都回到"当前实例"—— 上一次是排障时临时切过去看的，不该粘住
  previous.value = false
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
        v-if="showPreviousToggle"
        size="small"
        :type="previous ? 'warning' : 'default'"
        :title="previous ? '正在看上一次容器实例（已终止，不会再刷新）' : '看看上一次容器实例为什么死的'"
        @click="setPrevious(!previous)"
      >
        上一次
      </el-button>
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
