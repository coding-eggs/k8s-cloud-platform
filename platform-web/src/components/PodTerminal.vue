<script setup lang="ts">
/**
 * Pod exec 完整终端（xterm.js）。
 * WS 协议（与 k8s-server PodExecWebSocketHandler 对齐，经 platform-api 中继）：
 *   C→S: {"type":"input","data"} / {"type":"resize","cols","rows"} / {"type":"ping"}
 *   S→C: {"type":"output","data"} / {"type":"exit","code"} / {"type":"error","message"} / {"type":"pong"}
 *
 * 心跳：open 状态每 25s 发 ping（api 中继直接回 pong、不转发给 k8s-server），让 nginx / 网关 / LB
 * 这几跳都看到流量（常见 LB 空闲上限 60s）。连续 60s 收不到任何帧（输出或 pong）→ 主动断开并提示。
 * 断开后可「重新连接」：用当前 token 新建连接 = 新的 shell 会话，旧会话状态不保留。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { getAccessToken } from '@/auth/oauth'
import { chunkTerminalInput } from '@/utils/terminalInput'

const props = defineProps<{
  /** scope=cluster（节点详情等集群域）：走 admin client，无需 tenantId */
  scope?: 'namespace' | 'cluster'
  tenantId?: string
  clusterId: string
  namespace: string
  name: string
  container?: string
}>()

const PING_INTERVAL_MS = 25_000
const SILENCE_LIMIT_MS = 60_000

const containerEl = ref<HTMLElement | null>(null)
const status = ref<'connecting' | 'open' | 'closed'>('connecting')
const exitCode = ref<number | null>(null)
const errorMsg = ref('')

let term: Terminal | null = null
let fit: FitAddon | null = null
let ws: WebSocket | null = null
let pingTimer: ReturnType<typeof setInterval> | null = null
let lastFrameAt = 0

function focusTerm(): void {
  term?.focus()
}

function buildUrl(): string {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const q = new URLSearchParams({
    access_token: getAccessToken() ?? '',
    clusterId: props.clusterId,
    namespace: props.namespace,
    name: props.name,
  })
  if (props.scope === 'cluster') {
    q.set('scope', 'cluster')
  } else if (props.tenantId) {
    q.set('tenantId', props.tenantId)
  }
  if (props.container) q.set('container', props.container)
  return `${proto}://${location.host}/api/ws/pod/exec?${q.toString()}`
}

function send(obj: Record<string, unknown>): void {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj))
  }
}

function doFit(): void {
  try {
    fit?.fit()
    if (term && term.cols > 0 && term.rows > 0) {
      send({ type: 'resize', cols: term.cols, rows: term.rows })
    }
  } catch {
    /* 容器未就绪时忽略 */
  }
}

function stopHeartbeat(): void {
  if (pingTimer !== null) {
    clearInterval(pingTimer)
    pingTimer = null
  }
}

/** 每个 tick：先查静默是否超限，再发 ping（检查间隔 25s，最晚约 85s 判定断线） */
function startHeartbeat(): void {
  stopHeartbeat()
  lastFrameAt = Date.now()
  pingTimer = setInterval(() => {
    if (Date.now() - lastFrameAt > SILENCE_LIMIT_MS) {
      markClosed('\r\n\x1b[90m[连接已断开（心跳超时）]\x1b[0m\r\n')
      ws?.close()
      return
    }
    send({ type: 'ping' })
  }, PING_INTERVAL_MS)
}

/** 置为 closed 并在终端写一行提示（只写一次） */
function markClosed(line: string): void {
  stopHeartbeat()
  if (status.value !== 'closed') {
    status.value = 'closed'
    term?.write(line)
  }
}

function connect(): void {
  stopHeartbeat()
  status.value = 'connecting'
  exitCode.value = null
  errorMsg.value = ''
  const socket = new WebSocket(buildUrl())
  ws = socket
  // 旧连接的迟到事件不能改动新连接的状态
  const current = () => ws === socket
  socket.onopen = () => {
    if (!current()) return
    status.value = 'open'
    startHeartbeat()
    //连接建立后再同步一次尺寸（首帧 resize）
    doFit()
  }
  socket.onmessage = (ev) => {
    if (!current() || !term) return
    lastFrameAt = Date.now()
    try {
      const msg = JSON.parse(String(ev.data)) as { type: string; data?: string; code?: number; message?: string }
      switch (msg.type) {
        case 'output':
          term.write(msg.data ?? '')
          break
        case 'exit':
          exitCode.value = msg.code ?? null
          markClosed(`\r\n\x1b[90m[进程已退出，code=${msg.code ?? '?'}]\x1b[0m\r\n`)
          break
        case 'error':
          errorMsg.value = msg.message ?? '未知错误'
          markClosed(`\r\n\x1b[31m[错误] ${errorMsg.value}\x1b[0m\r\n`)
          break
      }
    } catch {
      /* 非 JSON 帧忽略 */
    }
  }
  socket.onerror = () => {
    if (!current()) return
    if (status.value === 'connecting') {
      errorMsg.value = '连接失败（请检查登录状态与网络）'
      markClosed(`\r\n\x1b[31m[错误] ${errorMsg.value}\x1b[0m\r\n`)
    }
  }
  socket.onclose = () => {
    if (!current()) return
    markClosed('\r\n\x1b[90m[连接已断开]\x1b[0m\r\n')
  }
}

function reconnect(): void {
  try {
    ws?.close()
  } catch {
    /* ignore */
  }
  term?.write('\r\n\x1b[90m[正在打开新的会话…]\x1b[0m\r\n')
  connect()
  focusTerm()
}

onMounted(() => {
  if (!containerEl.value) return
  term = new Terminal({
    cursorBlink: true,
    fontSize: 13,
    fontFamily: "Consolas, 'JetBrains Mono', monospace",
    theme: {
      background: '#0d1117',
      foreground: '#e6edf3',
    },
  })
  fit = new FitAddon()
  term.loadAddon(fit)
  term.open(containerEl.value)
  doFit()

  // 大段粘贴切块发：整段一帧会超 api（Tomcat）WS 文本缓冲 8192 字符 → 1009 断开
  term.onData((data) => chunkTerminalInput(data).forEach((part) => send({ type: 'input', data: part })))
  window.addEventListener('resize', doFit)

  connect()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', doFit)
  stopHeartbeat()
  const socket = ws
  ws = null
  try {
    socket?.close()
  } catch {
    /* ignore */
  }
  term?.dispose()
  term = null
})
</script>

<template>
  <div class="terminal-wrap">
    <div class="terminal-status">
      <span v-if="status === 'connecting'" class="muted">连接中…</span>
      <span v-else-if="status === 'open'" class="ok">已连接</span>
      <template v-else>
        <span class="bad">
          已断开<template v-if="exitCode !== null">（exit {{ exitCode }}）</template><template v-if="errorMsg">：{{ errorMsg }}</template>
        </span>
        <el-button size="small" link type="primary" @click="reconnect">重新连接</el-button>
        <span class="muted">将打开新的会话</span>
      </template>
    </div>
    <div ref="containerEl" class="terminal-box" @click="focusTerm" />
  </div>
</template>

<style scoped>
.terminal-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.terminal-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.terminal-status .ok {
  color: var(--el-color-success);
}
.terminal-status .bad {
  color: var(--el-color-danger);
}
.muted {
  color: var(--text-3);
}
.terminal-box {
  height: 480px;
  padding: 6px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: #0d1117;
  overflow: hidden;
}
</style>
