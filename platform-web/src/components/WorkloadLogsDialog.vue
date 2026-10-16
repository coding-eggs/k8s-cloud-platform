<script setup lang="ts">
/**
 * **工作负载级日志**（S2.1）：勾选若干 Pod → 一条带 `[pod/<pod>/<容器>]` 前缀的流。
 *
 * 形态照抄 `kubectl logs -l … --all-containers --prefix`（spec §0）——
 * k8s 核心只有 Pod 的 `logs` 子资源，没有工作负载级的；kubectl 那套是**客户端扇出**，
 * 本组件就是那个扇出：每个 (pod, 容器) 各自一个 sinceTime 游标，每 2 秒收一次新行，
 * **在本轮内**按时间戳排好再追加（不跨轮缓存排序 —— spec §3.3）。
 *
 * 工具栏（行数 / 搜索 / 清除 / 重新加载 / 全屏）与单 Pod 的 `PodLogDialog` 对齐 ——
 * 两个视图看的是同一批日志，控制项摆哪儿、叫什么名字不该各来一套。
 * 有意**没有**的两个：容器下拉（这里天然跟全部容器，靠前缀分）、彩色（那要 xterm 渲染 ANSI，
 * 本视图是 `<pre>` 纯文本）。
 *
 * ⚠️ 每一步抓取都走**既有**的单 Pod 日志端点，授权与今天逐个点开完全一样 ——
 *    所以本批不新增端点、不新增权限行（spec §5）。
 */
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import type { K8sPod } from '@/types'
import { getAccessToken } from '@/auth/oauth'
import EmptyState from '@/components/EmptyState.vue'
import { prefixOf, compareTs, sortByTs, takeNewLines, type LogLine } from '@/utils/logLines'
import { toHtml, escapeHtml } from '@/utils/logColor'

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
/** 首次回看行数：默认 200，可下拉切换（切换 = 重新加载 —— 与单 Pod 弹窗同一个语义） */
const TAIL_OPTIONS = [100, 200, 500, 1000]
const tailLines = ref(200)
/**
 * 文本缓冲的字符上限。20 条来源一起往同一个 `<pre>` 里灌，不封顶的话长跟随会越用越卡 ——
 * 单 Pod 那边有同样的兜底（`MAX_LINES`），而聚合这侧来源更多、更该设。
 * ⚠️ 到顶**才**裁：每轮都从头量一遍会把"追加"变成 O(n) 的常态开销，而这条路径每 2 秒就跑一次
 *    （所以字符总量是**跟着增删走**的 `totalChars`，不重算）。
 */
const MAX_CHARS = 2_000_000
/** 一行都没有时的占位：画面空着看不出是"还在跟"还是"已经停了" */
const NO_LINES_HINT = '（还没有日志）'
/** 无时间戳的降级只提示一次（与单 Pod 弹窗同一条口径） */
let warnedNoTs = false

/** 一条来源 = 一个 (pod, 容器) */
interface Source {
  pod: string
  container: string
  cursor: string
  /** 已经提示过取不到，别每轮刷屏 */
  warned: boolean
}

const phase = ref<'pick' | 'stream'>('pick')
/** 全屏：20 条来源一起看时，`min(1280px,94vw) × 56vh` 那个框太挤了（spec §3.1 承诺过复用外壳的观感） */
const fullscreen = ref(false)
const picked = ref<string[]>([])
const sources = ref<Source[]>([])

/**
 * 已收下的行。**前缀与正文分开存**，不是拼好的一整条串：
 * ① 着色（`utils/logColor` 的 Spring Boot 正则）要求行**以时间戳开头**，前缀一拼上去就匹配不上了 ——
 *    那样开关在、却一行都不变色；
 * ② 搜索要能按 pod 名找（前缀也算内容）。
 */
interface LogRow {
  /** `[pod/<pod>/<容器>] ` —— 最终显示时在正文前，但不参与着色 */
  prefix: string
  text: string
}

const logLines = ref<LogRow[]>([])
/** 行数组的字符总量，跟着增删走（见 MAX_CHARS 的说明） */
let totalChars = 0
/** 搜索/过滤关键词（大小写不敏感）；非空时只显示包含它的行 */
const searchTerm = ref('')
/** 彩色开关：按 Spring Boot 级别分段着色（与单 Pod 弹窗同一套规则，见 utils/logColor） */
const colorEnabled = ref(true)

const textEl = ref<HTMLElement | null>(null)
const atBottom = ref(true)

const shownLines = computed(() => {
  const q = searchTerm.value.trim().toLowerCase()
  // 搜索匹配「前缀 + 正文」：这样既能按内容找，也能只盯某一个 Pod
  return q ? logLines.value.filter((r) => (r.prefix + r.text).toLowerCase().includes(q)) : logLines.value
})
/**
 * ⚠️ 走 `v-html` 是为了着色（`<pre>` 渲染不了 ANSI），所以**每一段都必须先转义** ——
 *    日志内容是集群里的进程写出来的，等于不可信输入（`toHtml` / `escapeHtml` 负责这件事）。
 */
const displayHtml = computed(() => {
  const rows = shownLines.value
  if (!rows.length) return NO_LINES_HINT
  return rows
    .map((r) => escapeHtml(r.prefix) + (colorEnabled.value ? toHtml(r.text) : escapeHtml(r.text)))
    .join('\n')
})
/** 有搜索才报匹配数：否则那只是"总行数"，跟"筛掉了多少"混在一起反而看不懂 */
const matchCountText = computed(() => (searchTerm.value.trim() ? `${shownLines.value.length} 条匹配` : ''))

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
  clearBuffer()
  sources.value = []
  searchTerm.value = ''
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
  else q.set('tailLines', String(tailLines.value))
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

/** 某个来源取不到 —— 只插一行说明，**不影响其余**（Review Focus #2 / spec §3.4） */
function warnOnce(s: Source, why: string): LogRow | null {
  if (s.warned) return null
  s.warned = true
  return { prefix: prefixOf(s.pod, s.container), text: `暂时取不到日志（${why}）` }
}

function clearBuffer(): void {
  logLines.value = []
  totalChars = 0
}

/** 缓冲到顶才裁（见 MAX_CHARS 的说明）。从头部丢，并在丢掉处留一句 —— 静默丢行比慢更糟。 */
function trimIfHuge(): void {
  if (totalChars <= MAX_CHARS) return
  const all = logLines.value
  let drop = 0
  let freed = 0
  // 至少留最后一行：全丢光会让画面变成空白，看不出是"还在跟"还是"已经停了"
  while (drop < all.length - 1 && totalChars - freed > MAX_CHARS) {
    const r = all[drop]
    freed += (r?.prefix.length ?? 0) + (r?.text.length ?? 0) + 1
    drop++
  }
  if (!drop) return
  const marker: LogRow = { prefix: '', text: `（更早的 ${drop} 行已折叠）` }
  logLines.value = [marker, ...all.slice(drop)]
  totalChars = totalChars - freed + marker.text.length + 1
}

/** 收下新行：入缓冲（含裁剪）后贴底 */
function appendLines(rows: LogRow[]): void {
  if (!rows.length) return
  for (const r of rows) {
    logLines.value.push(r)
    totalChars += r.prefix.length + r.text.length + 1
  }
  trimIfHuge()
  scrollToBottom()
}

/**
 * 推进某个来源的游标。
 *
 * ⚠️ 一批里**一个 k8s 时间戳都没有**时 `takeNewLines` 不会推进游标（它没法比大小），
 *    于是下一轮又会用 `tailLines` 重拉最近 200 行、把同一批**再追加一遍** ——
 *    每来源每 2 秒一轮的重复洪水。触发条件是"platform-web 先上线、k8s-server 还是旧构建"
 *    （timestamps 未启用），正是单 Pod 弹窗那条 console.warn 针对的状态。
 *    这里照它的降级：改用**墙钟游标**（之后照常发 sinceTime，服务端仍按时间过滤），并只告警一次。
 */
function advanceCursor(s: Source, lines: LogLine[], nextCursor: string): void {
  if (lines.length && !lines.some((l) => l.ts)) {
    if (!warnedNoTs) {
      warnedNoTs = true
      console.warn('[WorkloadLogs] 响应缺少 k8s 时间戳前缀（<ts>\\t）—— 已退化为墙钟游标；'
        + '请确认 k8s-server 已重建并启用 usingTimestamps。')
    }
    s.cursor = new Date().toISOString()
    return
  }
  if (nextCursor) s.cursor = nextCursor
}

/** 首次：每个来源各拉一次 tailLines 快照（串行 —— 20 条流一起打出去只会互相挤） */
async function snapshot(): Promise<void> {
  const rows: LogRow[] = []
  for (const s of sources.value) {
    const prefix = prefixOf(s.pod, s.container)
    try {
      const text = await fetchSource(s)
      const { lines, nextCursor } = takeNewLines(text, '')
      advanceCursor(s, lines, nextCursor)
      for (const l of sortByTs(lines)) rows.push({ prefix, text: l.content })
    } catch (e) {
      const w = warnOnce(s, (e as Error).message)
      if (w) rows.push(w)
    }
  }
  appendLines(rows)
}

function start(): void {
  sources.value = picked.value.flatMap((name) => {
    const p = podOf(name)
    return p ? sourcesOf(p) : []
  })
  phase.value = 'stream'
  reload()
}

/** 重新加载：清空显示、各来源游标归零，按当前的「最近 N 行」重开一轮快照（换行数 / 手点都走这里） */
function reload(): void {
  clearBuffer()
  warnedNoTs = false
  for (const s of sources.value) {
    s.cursor = ''
    s.warned = false
  }
  stopPolling()
  // 先出一份快照（首屏立刻有内容），再各自按游标增量跟
  void snapshot().then(() => {
    timer = window.setInterval(() => void pollOnce(), POLL_MS)
  })
}

/** 清除：清空显示，游标推到"现在" —— 之后只显示新行（与单 Pod 弹窗 `clearLog` 同一个语义） */
function clearLog(): void {
  clearBuffer()
  const now = new Date().toISOString()
  for (const s of sources.value) s.cursor = now
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
  const rows: LogRow[] = []
  const collected: Array<{ s: Source; l: LogLine }> = []
  for (const r of results) {
    // ⭐ 某个来源没了（Pod 被滚动更新删掉）不能让整条流停掉或清空
    if (r.err) {
      const w = warnOnce(r.s, r.err.message)
      if (w) rows.push(w)
    }
    advanceCursor(r.s, r.lines, r.nextCursor)
    for (const l of r.lines) collected.push({ s: r.s, l })
  }
  collected.sort((a, b) => compareTs(a.l.ts, b.l.ts)) // 相等时保持收集顺序（现代 Array.sort 稳定）
  for (const x of collected) rows.push({ prefix: prefixOf(x.s.pod, x.s.container), text: x.l.content })
  appendLines(rows)
}

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <el-dialog v-model="visible" title="全部 Pod 日志" width="min(1280px, 94vw)" class="wl-logs"
             :fullscreen="fullscreen">
    <!-- 工具栏在两种阶段都在（勾选阶段只有全屏）—— 全屏的落点是右上角，
         不跟着内容在"页脚左下角"和"工具栏右侧"之间跳。 -->
    <div class="wl-toolbar">
      <template v-if="phase === 'stream'">
        <el-select v-model="tailLines" size="small" style="width: 130px" @change="reload()">
          <el-option v-for="n in TAIL_OPTIONS" :key="n" :label="`最近 ${n} 行`" :value="n" />
        </el-select>
        <el-input v-model="searchTerm" size="small" clearable placeholder="搜索 / 过滤日志…" class="wl-search" />
        <span v-if="matchCountText" class="match-count">{{ matchCountText }}</span>
        <el-button size="small" @click="clearLog()">清除</el-button>
        <el-button size="small" style="margin-left: 0" @click="reload()">重新加载</el-button>
        <!-- 换一批 = 回到勾选那一步；它改的是"跟哪些来源"，所以和上面几个摆一起 -->
        <el-button size="small" style="margin-left: 0" @click="resetPick">换一批 Pod</el-button>
      </template>
      <div class="spacer" />
      <!-- 彩色与单 Pod 弹窗同一个开关位置（spacer 之后、全屏之前），别在两处摆到不同角落 -->
      <el-button size="small" :type="colorEnabled ? 'primary' : 'default'" @click="colorEnabled = !colorEnabled">
        彩色
      </el-button>
      <el-button size="small" style="margin-left: 0" @click="fullscreen = !fullscreen">{{ fullscreen ? '退出全屏' : '全屏' }}</el-button>
    </div>

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
      <div class="stream-note">正在跟 {{ sources.length }} 条来源（{{ picked.length }} 个 Pod）。</div>
      <pre ref="textEl" class="wl-log" v-html="displayHtml" @scroll="onScroll"></pre>
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
.wl-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.spacer {
  flex: 1;
}
.wl-search {
  flex: 1;
  min-width: 120px;
  max-width: 340px;
}
.match-count {
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
}
.pick-note,
.stream-note {
  font-size: 12px;
  color: var(--text-3);
  padding-bottom: 8px;
}
.wl-log {
  /* 高度交给 flex（见文末非 scoped 块里的 `.wl-logs`）：原来写死 56vh，
     结果全屏之后还是一半屏幕多一点 —— 弹窗长大了，日志框没跟着长。 */
  flex: 1;
  min-height: 0;
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

<style>
/* el-dialog 内部结构被 Element Plus 剥离了 scoped 属性，无法用 :deep 触达；
   改用 .wl-logs 前缀的全局规则（该类为本组件独有）约束弹窗盒体与 body 高度。

   ⚠️ `margin-top` 只能写在 `:not(.is-fullscreen)` 里 —— 否则全屏时弹窗会比屏幕还高：
   EP 的全屏规则只把 `--el-dialog-margin-top` 改成 0，`margin` 简写仍由 `.el-dialog` 给
   （同特异性），我们这条 longhand 排在 EP 样式之后，**全屏下也照样生效**，
   于是 `height:100%` 之外又叠了一个上边距，最外层 `.el-overlay` 就长出滚动条。 */
.wl-logs {
  display: flex;
  flex-direction: column;
}
.wl-logs:not(.is-fullscreen) {
  height: min(720px, 85vh);
  margin-top: 7rem;
}
.wl-logs .el-dialog__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 彩色：v-html 生成的片段不带 data-v-*（Vue 只给模板里的节点打标记），scoped 规则够不着，
   所以这段也必须放在非 scoped 块里，用 .wl-log 前缀圈住作用域。配色跟主题变量走。 */
.wl-log .lg-info { color: var(--success); }
.wl-log .lg-warn { color: var(--warning); }
.wl-log .lg-error { color: var(--danger); }
.wl-log .lg-thread { color: var(--accent); }
.wl-log .lg-class { color: var(--info); }
</style>
