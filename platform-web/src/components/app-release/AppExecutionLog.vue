<script setup lang="ts">
/**
 * 发布过程日志：每个阶段做了什么、健康检查卡在哪（逐对象 / 逐 Pod 的原因）。
 *
 * 父组件改 `refreshKey` 即重读（进度面板每次轮询 +1）；只追加不改，所以整表替换即可。
 * 新日志到达时若用户停在底部，自动滚到底；用户往上翻时不打扰。
 */
import { nextTick, ref, watch } from 'vue'
import { appReleaseApi } from '@/api'
import type { AppExecutionLog } from '@/types'
import { EXEC_STAGE } from './releaseUtil'

const props = defineProps<{
  appId: string
  releaseNo: number
  tenantId?: string
  refreshKey?: number
  offline?: boolean
}>()

const rows = ref<AppExecutionLog[]>([])
const loaded = ref(false)
const failed = ref(false)
const box = ref<HTMLElement | null>(null)

async function load(): Promise<void> {
  const el = box.value
  const atBottom = !el || el.scrollHeight - el.scrollTop - el.clientHeight < 24
  try {
    const list = await appReleaseApi.logs(props.appId, props.releaseNo, props.tenantId)
    const grew = list.length !== rows.value.length
    rows.value = list
    failed.value = false
    if (grew && atBottom) {
      await nextTick()
      if (box.value) box.value.scrollTop = box.value.scrollHeight
    }
  } catch {
    failed.value = true
  } finally {
    loaded.value = true
  }
}
watch(() => [props.appId, props.releaseNo, props.refreshKey], () => { void load() }, { immediate: true })

function stageText(s?: string | null): string {
  if (!s) return ''
  if (props.offline && s === 'PRUNE') return '删除对象'
  return EXEC_STAGE[s] ?? s
}
function time(t: string): string {
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return t
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
</script>

<template>
  <div class="app-exec-log">
    <div v-if="loaded && failed && !rows.length" class="app-exec-log__muted">过程日志读取失败（平台未升级到带日志的版本时也会这样）。</div>
    <div v-else-if="loaded && !rows.length" class="app-exec-log__muted">暂无过程日志（早于此功能的发布没有记录）。</div>
    <div v-else ref="box" class="app-exec-log__list">
      <div v-for="r in rows" :key="r.id" class="app-exec-log__row" :class="`is-${r.level.toLowerCase()}`">
        <span class="app-exec-log__time">{{ time(r.createdAt) }}</span>
        <span class="app-exec-log__stage">{{ stageText(r.stage) }}</span>
        <span class="app-exec-log__msg">{{ r.message }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-exec-log__list {
  max-height: 320px; overflow: auto; border: 1px solid var(--border); border-radius: 6px;
  background: var(--bg); padding: 6px 0; font-size: 12px; line-height: 1.6;
}
.app-exec-log__row { display: grid; grid-template-columns: 112px 72px 1fr; gap: 8px; padding: 2px 12px; }
.app-exec-log__time { color: var(--text-3); font-variant-numeric: tabular-nums; white-space: nowrap; }
.app-exec-log__stage { color: var(--text-2); white-space: nowrap; }
.app-exec-log__msg { white-space: pre-wrap; word-break: break-word; color: var(--text-1); }
.app-exec-log__row.is-warn .app-exec-log__msg { color: var(--warning); }
.app-exec-log__row.is-error .app-exec-log__msg { color: var(--danger); }
.app-exec-log__muted { color: var(--text-3); font-size: 12px; }
</style>
