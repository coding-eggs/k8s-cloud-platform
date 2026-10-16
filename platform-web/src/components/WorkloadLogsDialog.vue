<script setup lang="ts">
/**
 * **工作负载级日志**（S2.1）：勾选若干 Pod → 一条带 `[pod/<pod>/<容器>]` 前缀的流。
 *
 * 形态照抄 `kubectl logs -l … --all-containers --prefix`（spec §0）——
 * k8s 核心只有 Pod 的 `logs` 子资源，没有工作负载级的；kubectl 那套是**客户端扇出**，
 * 本组件就是那个扇出：每个 (pod, 容器) 各自一个 sinceTime 游标，每 2 秒收一次新行，
 * **在本轮内**按时间戳排好再追加（不跨轮缓存排序 —— spec §3.3）。
 *
 * ⚠️ 每一步抓取都走**既有**的单 Pod 日志端点，授权与今天逐个点开完全一样 ——
 *    所以本批不新增端点、不新增权限行（spec §5）。
 */
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import type { K8sPod } from '@/types'
import { getAccessToken } from '@/auth/oauth'
import EmptyState from '@/components/EmptyState.vue'
import { prefixOf, compareTs, sortByTs, takeNewLines, type LogLine } from '@/utils/logLines'

const props = defineProps<{
  modelValue: boolean
  pods: K8sPod[]
  tenantId?: string
  clusterId: string
  namespace: string
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

/** 默认勾选数与硬上限：抓取量 = Pod 数 × 容器数，不封顶会直接撞到集群（spec §3.2） */
const DEFAULT_PICK = 10
const HARD_MAX = 20
/** 首次回看最近多少行（每个来源各自一份） */
const TAIL_LINES = 200

/** 一条来源 = 一个 (pod, 容器) */
interface Source {
  pod: string
  container: string
  cursor: string
  /** 已经提示过取不到，别每轮刷屏 */
  warned: boolean
}

const phase = ref<'pick' | 'stream'>('pick')
const picked = ref<string[]>([])
const sources = ref<Source[]>([])
const logText = ref('')
const textEl = ref<HTMLElement | null>(null)
const atBottom = ref(true)
const busy = ref(false)

/** 最近创建的排前面 —— 纯按时间，不做"异常优先"那套（用户裁定，spec §3.2） */
const podsByNewest = computed(() =>
  [...props.pods].sort((a, b) => (b.creationTime ?? '').localeCompare(a.creationTime ?? '')),
)

const overLimit = computed(() => picked.value.length >= HARD_MAX)
const allPicked = computed(
  () => props.pods.length > 0 && picked.value.length === Math.min(props.pods.length, HARD_MAX),
)

function podLabel(p: K8sPod): string {
  const reason = p.statusReason ? `（${p.statusReason}）` : ''
  return `${p.phase ?? '—'}${reason}`
}

function toggleAll(v: boolean): void {
  picked.value = v ? podsByNewest.value.slice(0, HARD_MAX).map((p) => p.name) : []
}

function togglePick(name: string): void {
  picked.value = picked.value.includes(name)
    ? picked.value.filter((n) => n !== name)
    : [...picked.value, name]
}

/** 弹窗每次打开都回到勾选这一步，并按"最近创建的 10 个"预选 */
function resetPick(): void {
  stopPolling()
  phase.value = 'pick'
  picked.value = podsByNewest.value.slice(0, DEFAULT_PICK).map((p) => p.name)
  sources.value = []
  logText.value = ''
  atBottom.value = true
}

watch(visible, (v) => {
  // 关掉就别在后台接着轮询（N 条流一起转，人走了还在打集群）
  if (!v) stopPolling()
  else resetPick()
})

function podOf(name: string): K8sPod | undefined {
  return props.pods.find((p) => p.name === name)
}

/** 一个 Pod 有几个容器就跟几个 —— 前缀把它们分开（Review Focus #1） */
function sourcesOf(pod: K8sPod): Source[] {
  const cs = pod.containers?.length ? pod.containers : ['']
  return cs.map((c) => ({ pod: pod.name, container: c, cursor: '', warned: false }))
}

function requestUrl(s: Source, sinceTime?: string): string {
  const q = new URLSearchParams()
  if (props.tenantId) q.set('tenantId', props.tenantId)
  q.set('clusterId', props.clusterId)
  q.set('namespace', props.namespace)
  if (s.container) q.set('container', s.container)
  if (sinceTime) q.set('sinceTime', sinceTime)
  else q.set('tailLines', String(TAIL_LINES))
  return `/api/pods/${encodeURIComponent(s.pod)}/logs?${q.toString()}`
}

async function fetchSource(s: Source, sinceTime?: string): Promise<string> {
  const resp = await fetch(requestUrl(s, sinceTime), {
    headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
  })
  const ctype = resp.headers.get('content-type') ?? ''
  if (!resp.ok || ctype.includes('application/json')) {
    let msg = `HTTP ${resp.status}`
    try {
      const body = (await resp.json()) as { msg?: string }
      if (body.msg) msg = body.msg
    } catch {
      /* 保留 HTTP 状态 */
    }
    throw new Error(msg)
  }
  return await resp.text()
}

/** 把 (来源, 文本) 收成带前缀、排好序的整块文本 */
function render(s: Source, lines: LogLine[]): string {
  return lines.map((l) => prefixOf(s.pod, s.container) + l.content).join('\n') + (lines.length ? '\n' : '')
}

/** 某个来源取不到 —— 只插一行说明，**不影响其余**（Review Focus #2 / spec §3.4） */
function warnOnce(s: Source, why: string): string {
  if (s.warned) return ''
  s.warned = true
  return `${prefixOf(s.pod, s.container)}暂时取不到日志（${why}）\n`
}

/** 首次：每个来源各拉一次 tailLines 快照 */
async function snapshot(): Promise<void> {
  busy.value = true
  try {
    const chunks: string[] = []
    for (const s of sources.value) {
      try {
        const text = await fetchSource(s)
        const { lines, nextCursor } = takeNewLines(text, '')
        s.cursor = nextCursor
        chunks.push(render(s, sortByTs(lines)))
      } catch (e) {
        chunks.push(warnOnce(s, (e as Error).message))
      }
    }
    logText.value = chunks.join('')
    scrollToBottom()
  } finally {
    busy.value = false
  }
}

function start(): void {
  sources.value = picked.value.flatMap((name) => {
    const p = podOf(name)
    return p ? sourcesOf(p) : []
  })
  phase.value = 'stream'
  logText.value = ''
  stopPolling()
  // 先出一份快照（首屏立刻有内容），再各自按游标增量跟
  void snapshot().then(() => {
    timer = window.setInterval(() => void pollOnce(), POLL_MS)
  })
}

function scrollToBottom(): void {
  if (!atBottom.value) return
  void nextTick(() => {
    const el = textEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function onScroll(): void {
  const el = textEl.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 8
}

function jumpToBottom(): void {
  atBottom.value = true
  scrollToBottom()
}

// ---- 增量跟着走 ----

const POLL_MS = 2000
let timer: number | undefined

function stopPolling(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

/**
 * 一轮：把**所有来源**的新行收上来，**在本轮内**按时间戳排好，再追加到末尾。
 *
 * ⚠️ 为什么"收齐再排"而不是各收各的边收边追加：后者会让"两个 Pod 的行交错成什么顺序"
 *    取决于网络快慢 —— 同一份日志每看一次都不一样。收齐再排，至少本轮顺序是稳定的。
 * ⚠️ 只排本轮：跨批全局有序要缓存"还没到齐的更早行"，延迟与内存都不好控（spec §3.3）。
 * ⚠️ 比较用 `compareTs`（**与单 Pod 视图同一份**），别再写第二个比较器。
 */
async function pollOnce(): Promise<void> {
  const collected: Array<{ s: Source; l: LogLine }> = []
  let extra = ''
  const results = await Promise.all(
    sources.value.map(async (s) => {
      try {
        const text = await fetchSource(s, s.cursor || undefined)
        const { lines, nextCursor } = takeNewLines(text, s.cursor)
        return { s, lines, nextCursor, err: null as Error | null }
      } catch (e) {
        return { s, lines: [] as LogLine[], nextCursor: s.cursor, err: e as Error }
      }
    }),
  )
  for (const r of results) {
    // ⭐ 某个来源没了（Pod 被滚动更新删掉）不能让整条流停掉或清空
    if (r.err) extra += warnOnce(r.s, r.err.message)
    if (r.nextCursor) r.s.cursor = r.nextCursor
    for (const l of r.lines) collected.push({ s: r.s, l })
  }
  collected.sort((a, b) => compareTs(a.l.ts, b.l.ts)) // 相等时保持收集顺序（现代 Array.sort 稳定）
  const body = collected.map((x) => prefixOf(x.s.pod, x.s.container) + x.l.content).join('\n')
  if (body || extra) {
    logText.value += extra + (body ? body + '\n' : '')
    scrollToBottom()
  }
}

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <el-dialog v-model="visible" title="全部 Pod 日志" width="min(1280px, 94vw)" class="wl-logs">
    <template v-if="phase === 'pick'">
      <div class="pick-note">
        这个负载共 {{ pods.length }} 个 Pod，已勾 {{ picked.length }} 个（最多 {{ HARD_MAX }} 个）。
        勾的是 Pod —— 一个 Pod 里的每个容器都会跟，靠前缀分开。
      </div>
      <EmptyState v-if="!pods.length" title="这个工作负载当前没有 Pod"
                  description="没有 Pod 就没有日志可跟。" />
      <el-table v-else :data="podsByNewest" size="small" max-height="52vh">
        <el-table-column width="46">
          <template #header>
            <el-checkbox :model-value="allPicked" :disabled="!pods.length" @change="toggleAll($event as boolean)" />
          </template>
          <template #default="{ row }">
            <!-- ⚠️ 不用「数组 v-model」那种写法（`v-model="picked" :value=…`）—— 那要靠 el-checkbox
                 内部认数组，行为不稳。显式读写 picked 最清楚，也好断言。 -->
            <el-checkbox :model-value="picked.includes(row.name)"
                         :disabled="overLimit && !picked.includes(row.name)"
                         @change="togglePick(row.name)" />
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="300">
          <template #default="{ row }"><code class="mono">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="180">
          <template #default="{ row }">{{ podLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="重启" width="70">
          <template #default="{ row }">{{ row.restarts ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="调度节点" min-width="120">
          <template #default="{ row }">{{ row.nodeName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ row.creationTime ?? '—' }}</template>
        </el-table-column>
      </el-table>
    </template>

    <template v-else>
      <div class="stream-note">
        正在跟 {{ sources.length }} 条来源（{{ picked.length }} 个 Pod）。
        <el-button link type="primary" @click="resetPick">换一批 Pod</el-button>
      </div>
      <pre ref="textEl" class="wl-log" @scroll="onScroll">{{ logText || '（还没有日志）' }}</pre>
      <div class="wl-foot">
        <el-button v-if="!atBottom" link type="primary" @click="jumpToBottom">↓ 回到底部</el-button>
      </div>
    </template>

    <template #footer>
      <el-button v-if="phase === 'pick'" @click="visible = false">取消</el-button>
      <el-button v-if="phase === 'pick'" type="primary" :disabled="!picked.length" @click="start">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pick-note,
.stream-note {
  font-size: 12px;
  color: var(--text-3);
  padding-bottom: 8px;
}
.wl-log {
  height: 56vh;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--border, #ebeef5);
  border-radius: 4px;
  background: var(--bg);
  color: var(--text-1);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.wl-foot {
  height: 22px;
}
</style>
