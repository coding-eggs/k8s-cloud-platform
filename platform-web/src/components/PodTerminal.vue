<script setup lang="ts">
/**
 * Pod exec 完整终端（xterm.js）。
 * WS 协议（与 k8s-server PodExecWebSocketHandler 对齐，经 platform-api 中继）：
 *   C→S: {"type":"input","data"} / {"type":"resize","cols","rows"}
 *   S→C: {"type":"output","data"} / {"type":"exit","code"} / {"type":"error","message"}
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { getAccessToken } from '@/auth/oauth'

const props = defineProps<{
  tenantId: string
  clusterId: string
  namespace: string
  name: string
  container?: string
}>()

const containerEl = ref<HTMLElement | null>(null)
const status = ref<'connecting' | 'open' | 'closed'>('connecting')
const exitCode = ref<number | null>(null)
const errorMsg = ref('')

let term: Terminal | null = null
let fit: FitAddon | null = null
let ws: WebSocket | null = null

function focusTerm(): void {
  term?.focus()
}

function buildUrl(): string {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const q = new URLSearchParams({
    access_token: getAccessToken() ?? '',
    tenantId: props.tenantId,
    clusterId: props.clusterId,
    namespace: props.namespace,
    name: props.name,
  })
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

  term.onData((data) => send({ type: 'input', data }))
  window.addEventListener('resize', doFit)

  ws = new WebSocket(buildUrl())
  ws.onopen = () => {
    status.value = 'open'
    //连接建立后再同步一次尺寸（首帧 resize）
    doFit()
  }
  ws.onmessage = (ev) => {
    if (!term) return
    try {
      const msg = JSON.parse(String(ev.data)) as { type: string; data?: string; code?: number; message?: string }
      switch (msg.type) {
        case 'output':
          term.write(msg.data ?? '')
          break
        case 'exit':
          exitCode.value = msg.code ?? null
          status.value = 'closed'
          term.write(`\r\n\x1b[90m[进程已退出，code=${msg.code ?? '?'}]\x1b[0m\r\n`)
          break
        case 'error':
          errorMsg.value = msg.message ?? '未知错误'
          status.value = 'closed'
          term.write(`\r\n\x1b[31m[错误] ${errorMsg.value}\x1b[0m\r\n`)
          break
      }
    } catch {
      /* 非 JSON 帧忽略 */
    }
  }
  ws.onerror = () => {
    if (status.value === 'connecting') {
      errorMsg.value = '连接失败（请检查登录状态与网络）'
      status.value = 'closed'
      term?.write(`\r\n\x1b[31m[错误] ${errorMsg.value}\x1b[0m\r\n`)
    }
  }
  ws.onclose = () => {
    if (status.value !== 'closed') {
      status.value = 'closed'
      term?.write('\r\n\x1b[90m[连接已断开]\x1b[0m\r\n')
    }
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', doFit)
  try {
    ws?.close()
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
      <span v-else class="bad">
        已断开<template v-if="exitCode !== null">（exit {{ exitCode }}）</template><template v-if="errorMsg">：{{ errorMsg }}</template>
      </span>
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
