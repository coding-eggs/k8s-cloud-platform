<script setup lang="ts">
/**
 * 变更文件清单（触发对话框与运行详情共用）。
 *
 * <p>两段信息，按需分两次取：
 * <ul>
 *   <li><b>清单</b>（改了哪些文件、各多少行）—— 跟着 {@code withDiff} / {@code /run/diff} 一次回来；</li>
 *   <li><b>补丁正文</b>（这个文件具体改了哪几行）—— 点开某一行才去取（{@code /run/diff/file}），
 *       因为一个大文件的补丁可能几百 KB，没人点就不该下。</li>
 * </ul>
 *
 * <p>⚠️ 状态用**单字母**（A/M/D/R/C）而不是「新增/修改」：中文字宽不稳，窄列里会竖着折行。
 * 中文全称放 {@code title}，悬停即得。
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import type { PipelineFileChange, PipelineFilePatch, PipelineSourceRef } from '@/types'

const props = defineProps<{
  source: PipelineSourceRef
  /** 清单最多显示多高（超出滚动） */
  maxHeight?: number
  /**
   * 取某个文件的补丁 —— 两处的来源不同（预览走 pipelineId，历史运行走 runName），
   * 所以由调用方给，组件只管什么时候去要。
   */
  patchLoader?: (source: PipelineSourceRef, path: string) => Promise<PipelineFilePatch>
}>()

const expanded = reactive<Record<string, boolean>>({})
const patches = reactive<Record<string, PipelineFilePatch | null>>({})
const loading = reactive<Record<string, boolean>>({})

/** 状态字母 + 中文全称（全称只出现在 title 里） */
const STATUS: Record<string, { label: string; cls: string }> = {
  A: { label: '新增', cls: 'is-add' },
  M: { label: '修改', cls: 'is-mod' },
  D: { label: '删除', cls: 'is-del' },
  R: { label: '改名', cls: 'is-ren' },
  C: { label: '复制', cls: 'is-ren' },
}

function statusOf(f: PipelineFileChange) {
  return STATUS[f.status] ?? { label: f.status, cls: 'is-mod' }
}

/** 二进制文件数不出行数，显示「二进制」而不是「+0 −0」 */
function countsText(f: PipelineFileChange): string {
  return f.binary ? '二进制' : `+${f.additions} −${f.deletions}`
}

async function toggle(f: PipelineFileChange) {
  if (!props.patchLoader || f.binary) return
  const key = f.path
  if (expanded[key]) {
    expanded[key] = false
    return
  }
  expanded[key] = true
  // 取过一次就留着：反复展开/收起不该反复下载
  if (patches[key]) return
  loading[key] = true
  try {
    patches[key] = await props.patchLoader(props.source, f.path)
  } catch (e) {
    patches[key] = {
      path: f.path,
      truncated: false,
      binary: false,
      error: (e as Error)?.message || '取不到补丁',
    }
  } finally {
    loading[key] = false
  }
}

function patchLines(text?: string | null): string[] {
  return (text ?? '').split('\n')
}

/** 补丁的逐行着色：hunk 头 / 文件头元信息 / 增 / 删 / 上下文 */
function lineCls(line: string): string {
  if (line.startsWith('@@')) return 'p-hunk'
  if (line.startsWith('diff ') || line.startsWith('index ')
    || line.startsWith('--- ') || line.startsWith('+++ ')
    || line.startsWith('new file ') || line.startsWith('deleted file ')
    || line.startsWith('similarity ') || line.startsWith('rename ')) {
    return 'p-meta'
  }
  if (line.startsWith('+')) return 'p-add'
  if (line.startsWith('-')) return 'p-del'
  return 'p-ctx'
}

// ---- 全屏 ----

/**
 * 全屏查看：清单在触发对话框里只有一小块，几百行的补丁挤在里面很难读。
 *
 * <p>⚠️ 用 {@link Teleport} 到 body，而不是靠 z-index 硬叠：组件在 `el-dialog` 里时，
 * 对话框自己就是一个 fixed 浮层，在里面再 fixed 一个很容易被它的定位上下文吃掉。
 */
const fullscreen = ref(false)

/** 全屏时列表撑满剩余高度；内联的 maxHeight 会让它反而更矮，所以要显式撤掉 */
const listStyle = computed(() => (fullscreen.value || !props.maxHeight
  ? undefined
  : { maxHeight: `${props.maxHeight}px` }))

/** Esc 退出：**捕获阶段**拦一下，否则 el-dialog 会顺手把整个对话框关掉 */
function onEsc(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  e.stopPropagation()
  fullscreen.value = false
}

watch(fullscreen, (on) => {
  if (on) window.addEventListener('keydown', onEsc, true)
  else window.removeEventListener('keydown', onEsc, true)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onEsc, true))

const fileCount = () => props.source.fileCount ?? props.source.files?.length ?? 0
const hiddenCount = () => Math.max(0, fileCount() - (props.source.files?.length ?? 0))
const baseLabel = () => (props.source.baseKind === 'parent'
  ? '上一提交'
  : (props.source.baseRunName || '上次成功运行'))
</script>

<template>
  <!-- 全屏时整个挪到 body 下（见脚本里的说明），不靠 z-index 硬叠 -->
  <Teleport to="body" :disabled="!fullscreen">
    <div class="files" :class="{ 'is-full': fullscreen }">
      <div class="files-head">
        <span>变更 <b>{{ fileCount() }}</b> 个文件</span>
        <span class="add">+{{ source.additions ?? 0 }}</span>
        <span class="del">−{{ source.deletions ?? 0 }}</span>
        <span v-if="source.baseSha" class="dim">
          对比 <code>{{ source.baseSha.slice(0, 7) }}</code>（{{ baseLabel() }}）
        </span>
        <span v-if="patchLoader" class="dim tip">点文件看它具体改了哪几行</span>
        <span v-if="fullscreen" class="dim esc-hint">Esc 退出</span>
        <el-button link type="primary" class="fs-btn" @click="fullscreen = !fullscreen">
          {{ fullscreen ? '退出全屏' : '全屏' }}
        </el-button>
      </div>

      <div class="list" :style="listStyle">
        <div v-for="f in source.files ?? []" :key="`${f.status}:${f.path}`" class="item">
          <div
            class="row"
            :class="{ open: expanded[f.path], clickable: !!patchLoader && !f.binary }"
            @click="toggle(f)"
          >
            <span class="st" :class="statusOf(f).cls" :title="statusOf(f).label">{{ f.status }}</span>
            <span class="path" :title="f.oldPath ? `${f.oldPath} → ${f.path}` : f.path">
              <template v-if="f.oldPath"><span class="old">{{ f.oldPath }}</span> → </template>{{ f.path }}
            </span>
            <span class="counts" :class="{ dim: f.binary }">{{ countsText(f) }}</span>
            <span v-if="patchLoader && !f.binary" class="chev">{{ expanded[f.path] ? '▾' : '▸' }}</span>
          </div>

          <div v-if="expanded[f.path]" class="patch-wrap">
            <div v-if="loading[f.path]" class="dim">正在取补丁…</div>
            <div v-else-if="patches[f.path]?.error" class="warn">{{ patches[f.path]?.error }}</div>
            <div v-else-if="patches[f.path]?.binary" class="dim">二进制文件，没有可读的补丁</div>
            <template v-else-if="patches[f.path]?.text">
              <pre class="patch"><div v-for="(l, i) in patchLines(patches[f.path]?.text)" :key="i" :class="lineCls(l)">{{ l || ' ' }}</div></pre>
              <div v-if="patches[f.path]?.truncated" class="dim">补丁太长，只显示了前面一部分</div>
            </template>
          </div>
        </div>
        <div v-if="!(source.files ?? []).length" class="dim empty">没有文件变更</div>
      </div>

      <div v-if="source.truncated" class="dim trunc">
        只列出前 {{ (source.files ?? []).length }} 个；另有 {{ hiddenCount() }} 个未显示
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.files {
  font-size: 12px;
}

/* 全屏：铺满窗口（已 Teleport 到 body，这里的 fixed 是相对视口的） */
.files.is-full {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  flex-direction: column;
  padding: 16px 24px;
  background: var(--bg);
  font-size: 13px;
}
.files.is-full .list {
  flex: 1 1 auto;
  min-height: 0;
}
/* 全屏时补丁跟着整页滚，不再单独限高 —— 于是不会出现「页面里再套一个滚动条」 */
.files.is-full .patch {
  max-height: none;
}
.fs-btn {
  flex-shrink: 0;
  font-size: 12px;
}
.esc-hint {
  flex-shrink: 0;
}
.files-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  margin-bottom: 6px;
}
.files-head b {
  color: var(--text-1);
}
.files-head code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  color: var(--text-3);
}
.tip {
  margin-left: auto;
}
.add {
  color: var(--success);
}
.del {
  color: var(--danger);
}
.dim {
  color: var(--text-3);
}
.warn {
  color: var(--warning);
}

/* 清单本体：一行一个文件，行悬停高亮；可点的行左键展开补丁 */
.list {
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: auto;
  background: var(--bg);
}
.item + .item {
  border-top: 1px solid var(--border);
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 3px 8px;
  line-height: 20px;
}
.row.clickable {
  cursor: pointer;
}
.row.clickable:hover,
.row.open {
  background: var(--panel-hover);
}

/* 状态徽标：固定 20px，单字母不会折行（中文全称在 title 里） */
.st {
  flex: 0 0 20px;
  width: 20px;
  text-align: center;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  background: var(--panel-hover);
}
.is-add {
  color: var(--success);
}
.is-del {
  color: var(--danger);
}
.is-mod {
  color: var(--warning);
}
.is-ren {
  color: var(--accent);
}

.path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  color: var(--text-2);
}
.old {
  color: var(--text-3);
}
.counts {
  flex-shrink: 0;
  min-width: 64px;
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--text-3);
}
.chev {
  flex-shrink: 0;
  width: 10px;
  color: var(--text-3);
}

/* 补丁：等宽、逐行着色，横向可滚（长行不折，折了就看不出哪行是新增的） */
.patch-wrap {
  padding: 0 8px 8px 36px;
}
.patch {
  margin: 0;
  padding: 6px 10px;
  max-height: 320px;
  overflow: auto;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.5;
  white-space: pre;
  color: var(--text-2);
}
.p-hunk {
  color: var(--accent);
}
.p-meta {
  color: var(--text-3);
}
.p-add {
  color: var(--success);
}
.p-del {
  color: var(--danger);
}
.p-ctx {
  color: var(--text-2);
}

.empty {
  padding: 8px;
}
.trunc {
  margin-top: 4px;
}
</style>
