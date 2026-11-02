<script setup lang="ts">
/**
 * 发布对话框（设计 §8）：勾选服务 → 看差异 → 填说明 → 确认。
 *
 * 差异全部来自 `/application/release/preview`（不写集群）：待发布改动、需一并勾选的服务、对象增删。
 * 预览里的 problems 阻断发布；warnings（如 env 里疑似明文凭据）只提示，永远不挡确认。
 * 「日常发版」= 只改某服务的 tag：行内 tag 输入框与期望配置不同时，作为 `images` 覆盖随预览与创建一起发出，
 * 不需要先去服务配置页保存。
 * 创建失败（含「线上已变化，请刷新后重试」）时错误原文留在对话框内，重新拉预览，勾选与说明保持不动。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { appReleaseApi } from '@/api'
import StatusBadge from '@/components/StatusBadge.vue'
import type { AppReleasePreview, AppServiceRow } from '@/types'
import { errMsg, imageTag } from './releaseUtil'

const props = defineProps<{
  modelValue: boolean
  appId: string
  tenantId?: string
  services: AppServiceRow[]
  pendingKeys: string[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'published', executionId: number): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const selected = ref<string[]>([])
const tags = ref<Record<string, string>>({})
const note = ref('')
const preview = ref<AppReleasePreview | null>(null)
const previewing = ref(false)
const submitting = ref(false)
/** 创建 / 预览失败的原文，留在对话框内 */
const error = ref('')

const rowOf = (key: string): AppServiceRow | undefined => props.services.find((s) => s.key === key)
const isDeleted = (r: AppServiceRow): boolean => r.pending === 'DELETED'
const desiredTag = (r: AppServiceRow): string => r.desired?.imageTag ?? imageTag(r.image)

/** 只发与期望配置不同的 tag；待删除的服务没有 tag 可改 */
const images = computed<Record<string, string>>(() => {
  const out: Record<string, string> = {}
  for (const key of selected.value) {
    const r = rowOf(key)
    const t = (tags.value[key] ?? '').trim()
    if (r && !isDeleted(r) && t && t !== desiredTag(r)) out[key] = t
  }
  return out
})
const tagEdited = (r: AppServiceRow): boolean =>
  !isDeleted(r) && !!(tags.value[r.key] ?? '').trim() && (tags.value[r.key] ?? '').trim() !== desiredTag(r)

function toggle(key: string, on: unknown): void {
  selected.value = on ? [...new Set([...selected.value, key])] : selected.value.filter((k) => k !== key)
}

watch(visible, (v) => {
  if (!v) return
  selected.value = [...props.pendingKeys]
  tags.value = Object.fromEntries(props.services.map((s) => [s.key, desiredTag(s)]))
  note.value = ''
  preview.value = null
  error.value = ''
  schedulePreview(0)
}, { immediate: true })

let seq = 0
let timer: number | null = null
function schedulePreview(delay = 300): void {
  if (timer != null) window.clearTimeout(timer)
  timer = window.setTimeout(() => { void refreshPreview() }, delay)
}
onBeforeUnmount(() => { if (timer != null) window.clearTimeout(timer) })
watch([selected, images], () => { if (visible.value) { error.value = ''; schedulePreview() } }, { deep: true })

async function refreshPreview(): Promise<void> {
  const my = ++seq
  if (!selected.value.length) {
    preview.value = null
    previewing.value = false
    return
  }
  previewing.value = true
  try {
    const p = await appReleaseApi.preview({
      appId: props.appId,
      serviceKeys: [...selected.value],
      images: Object.keys(images.value).length ? images.value : undefined,
      tenantId: props.tenantId,
    })
    if (my === seq) preview.value = p
  } catch (e) {
    if (my === seq) { preview.value = null; error.value = errMsg(e, '预览失败') }
  } finally {
    if (my === seq) previewing.value = false
  }
}

const requiredKeys = computed(() => preview.value?.requiredKeys ?? [])
const problems = computed(() => preview.value?.problems ?? [])
const warnings = computed(() => preview.value?.warnings ?? [])
const canConfirm = computed(() =>
  !!selected.value.length && !!preview.value && !previewing.value && !submitting.value
  && !problems.value.length && !requiredKeys.value.length,
)

function addRequired(): void {
  selected.value = [...new Set([...selected.value, ...requiredKeys.value])]
}

const CHANGE_META = {
  ADDED: { text: '新增', type: 'success' },
  CHANGED: { text: '变更', type: 'warning' },
  DELETED: { text: '删除', type: 'danger' },
} as const

async function submit(): Promise<void> {
  if (!canConfirm.value) return
  submitting.value = true
  error.value = ''
  try {
    const id = await appReleaseApi.create({
      appId: props.appId,
      serviceKeys: [...selected.value],
      images: Object.keys(images.value).length ? images.value : undefined,
      note: note.value.trim() || undefined,
      tenantId: props.tenantId,
    })
    ElMessage.success('发布已开始')
    visible.value = false
    emit('published', id)
  } catch (e) {
    // 原文显示（含「线上已变化，请刷新后重试」）；线上变了，预览也过期了，重拉一次，勾选与说明保持原样
    const msg = errMsg(e, '发布失败')
    await refreshPreview()
    error.value = msg
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="发布" width="760px" class="app-rel-dlg" destroy-on-close>
    <div class="app-rel-dlg__lead">勾选本次要发布的服务。没勾选的服务不会被重启；只想发新版本时，直接改 tag 即可。</div>

    <el-table :data="services" size="small" row-key="key">
      <el-table-column width="46">
        <template #default="{ row }">
          <el-checkbox :model-value="selected.includes(row.key)" @change="(v: unknown) => toggle(row.key, v)" />
        </template>
      </el-table-column>
      <el-table-column label="服务" min-width="130">
        <template #default="{ row }">
          <b>{{ row.key }}</b>
          <el-tag
            v-if="row.pending"
            size="small"
            effect="light"
            class="app-rel-dlg__tag"
            :type="row.pending === 'DELETED' ? 'danger' : row.pending === 'ADDED' ? 'success' : 'warning'"
          >
            {{ row.pending === 'DELETED' ? '待删除' : row.pending === 'ADDED' ? '新增' : '有改动' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="线上 tag" width="120">
        <template #default="{ row }"><code>{{ imageTag(row.liveImage) || '—' }}</code></template>
      </el-table-column>
      <el-table-column label="发布 tag" width="200">
        <template #default="{ row }">
          <span v-if="isDeleted(row)" class="app-rel-dlg__muted">将从集群删除</span>
          <el-input
            v-else
            v-model="tags[row.key]"
            size="small"
            placeholder="镜像 tag"
            :class="{ 'is-edited': tagEdited(row) }"
          />
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!selected.length" class="app-rel-dlg__empty">请至少勾选一个服务。</div>

    <div v-loading="previewing" class="app-rel-dlg__preview">
      <el-alert
        v-if="requiredKeys.length"
        type="warning"
        :closable="false"
        show-icon
        :title="`所选服务引用了尚未发布的服务：${requiredKeys.join('、')}，需要一并勾选才能发布`"
      >
        <el-button size="small" @click="addRequired">一并勾选</el-button>
      </el-alert>
      <el-alert v-for="(p, i) in problems" :key="'p' + i" type="error" :closable="false" show-icon :title="p" />
      <el-alert
        v-for="(w, i) in warnings"
        :key="'w' + i"
        type="warning"
        :closable="false"
        show-icon
        :title="w"
        description="仅作提示，不影响发布。"
      />

      <template v-if="preview && selected.length">
        <h4>本次改动</h4>
        <div v-if="!preview.changes.length" class="app-rel-dlg__muted">没有发现配置差异。</div>
        <ul v-else class="app-rel-dlg__changes">
          <li v-for="c in preview.changes" :key="c.key" :class="{ 'is-out': !preview.selectedKeys.includes(c.key) }">
            <StatusBadge :label="CHANGE_META[c.type]?.text ?? c.type" :type="CHANGE_META[c.type]?.type ?? 'neutral'" />
            <b>{{ c.key }}</b>
            <span v-if="c.fromImage || c.toImage">
              <code>{{ imageTag(c.fromImage) || '—' }}</code> → <code>{{ imageTag(c.toImage) || '—' }}</code>
            </span>
            <span v-if="c.fields?.length" class="app-rel-dlg__muted">{{ c.fields.join('、') }}</span>
            <span v-if="!preview.selectedKeys.includes(c.key)" class="app-rel-dlg__muted">（未勾选，本次不发布）</span>
          </li>
        </ul>

        <template v-if="preview.addedObjects.length || preview.removedObjects.length">
          <h4>集群对象</h4>
          <div v-for="o in preview.addedObjects" :key="'a' + o" class="app-rel-dlg__obj is-add">+ {{ o }}</div>
          <div v-for="o in preview.removedObjects" :key="'r' + o" class="app-rel-dlg__obj is-del">- {{ o }}</div>
        </template>
      </template>
    </div>

    <el-form label-position="top" class="app-rel-dlg__note" @submit.prevent>
      <el-form-item label="发布说明（可选）">
        <el-input v-model="note" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="如：修复下单超时" />
      </el-form-item>
    </el-form>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canConfirm" @click="submit">确认发布</el-button>
    </template>
  </el-dialog>
</template>

<style>
/* el-dialog 内部节点没有 data-v-*，scoped 打不到；唯一前缀 app-rel-dlg */
.app-rel-dlg .app-rel-dlg__lead { color: var(--el-text-color-secondary); font-size: 13px; margin-bottom: 10px; }
.app-rel-dlg .app-rel-dlg__tag { margin-left: 8px; }
.app-rel-dlg .app-rel-dlg__muted { color: var(--el-text-color-secondary); font-size: 12px; }
.app-rel-dlg .app-rel-dlg__empty { margin: 10px 0; color: var(--el-text-color-secondary); font-size: 13px; }
.app-rel-dlg .app-rel-dlg__preview { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; min-height: 24px; }
.app-rel-dlg .app-rel-dlg__preview h4 { margin: 6px 0 0; font-size: 13px; }
.app-rel-dlg .app-rel-dlg__changes { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.app-rel-dlg .app-rel-dlg__changes li { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.app-rel-dlg .app-rel-dlg__changes li.is-out { opacity: 0.6; }
.app-rel-dlg .app-rel-dlg__obj { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.app-rel-dlg .app-rel-dlg__obj.is-add { color: var(--el-color-success); }
.app-rel-dlg .app-rel-dlg__obj.is-del { color: var(--el-color-danger); }
.app-rel-dlg .app-rel-dlg__note { margin-top: 12px; }
.app-rel-dlg .is-edited .el-input__wrapper { box-shadow: 0 0 0 1px var(--el-color-warning) inset; }
</style>
