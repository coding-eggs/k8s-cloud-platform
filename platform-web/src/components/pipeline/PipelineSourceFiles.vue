<script setup lang="ts">
/**
 * 变更文件清单（触发对话框与运行详情共用）。
 *
 * <p>数据来自后端 {@code withDiff} 那一次浅取：预览时是 {@code /run/preview}，
 * 历史运行是 {@code /run/diff}。这里只负责画，不取数 —— 两处的取数时机不一样
 * （对话框是用户点「查看变更」，详情是展开某个节点）。
 */
import type { PipelineFileChange, PipelineSourceRef } from '@/types'

const props = defineProps<{
  source: PipelineSourceRef
  /** 清单最多显示多少行（超出滚动；数据本身由后端的 max-files 截断） */
  maxHeight?: number
}>()

/** 状态色沿用资源状态的语义（新增=绿、删除=红、改名=中性强调） */
const STATUS: Record<string, { text: string; cls: string }> = {
  A: { text: '新增', cls: 'is-add' },
  M: { text: '修改', cls: 'is-mod' },
  D: { text: '删除', cls: 'is-del' },
  R: { text: '改名', cls: 'is-ren' },
  C: { text: '复制', cls: 'is-ren' },
}

function statusOf(f: PipelineFileChange) {
  return STATUS[f.status] ?? { text: f.status, cls: 'is-mod' }
}

/** 二进制文件数不出行数，显示「二进制」而不是「+0 −0」 */
function countsText(f: PipelineFileChange): string {
  return f.binary ? '二进制' : `+${f.additions} −${f.deletions}`
}

const fileCount = () => props.source.fileCount ?? props.source.files?.length ?? 0
const hiddenCount = () => Math.max(0, fileCount() - (props.source.files?.length ?? 0))
</script>

<template>
  <div class="files">
    <div class="files-head">
      <span>变更 <b>{{ fileCount() }}</b> 个文件</span>
      <span class="add">+{{ source.additions ?? 0 }}</span>
      <span class="del">−{{ source.deletions ?? 0 }}</span>
      <span v-if="source.baseSha" class="dim">
        对比
        <code>{{ source.baseSha.slice(0, 7) }}</code>
        （{{ source.baseKind === 'parent' ? '上一提交' : (source.baseRunName || '上次成功运行') }}）
      </span>
    </div>

    <div class="file-list" :style="maxHeight ? { maxHeight: `${maxHeight}px` } : undefined">
      <div v-for="f in source.files ?? []" :key="`${f.status}:${f.path}`" class="file">
        <span class="st" :class="statusOf(f).cls">{{ statusOf(f).text }}</span>
        <span class="path" :title="f.oldPath ? `${f.oldPath} → ${f.path}` : f.path">
          <template v-if="f.oldPath"><span class="old">{{ f.oldPath }}</span> → </template>{{ f.path }}
        </span>
        <span class="counts" :class="{ dim: f.binary }">{{ countsText(f) }}</span>
      </div>
      <div v-if="!(source.files ?? []).length" class="dim">没有文件变更</div>
    </div>

    <div v-if="source.truncated" class="dim trunc">
      只列出前 {{ (source.files ?? []).length }} 个；另有 {{ hiddenCount() }} 个未显示
    </div>
  </div>
</template>

<style scoped>
.files {
  font-size: 12px;
}
.files-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  margin-bottom: 4px;
}
.files-head code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  color: var(--text-3);
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
.file-list {
  overflow: auto;
}
.file {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  line-height: 20px;
}
.st {
  flex-shrink: 0;
  width: 30px;
  text-align: center;
  border-radius: 4px;
  padding: 0 4px;
  font-size: 11px;
  border: 1px solid transparent;
}
.is-add {
  color: var(--success);
  border-color: var(--success);
}
.is-del {
  color: var(--danger);
  border-color: var(--danger);
}
.is-mod {
  color: var(--warning);
  border-color: var(--warning);
}
.is-ren {
  color: var(--accent);
  border-color: var(--accent);
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
  font-variant-numeric: tabular-nums;
  color: var(--text-3);
}
.trunc {
  margin-top: 4px;
}
</style>
