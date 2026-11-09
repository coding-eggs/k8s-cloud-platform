<script setup lang="ts">
/**
 * 发布对话框（设计 §8）：勾选服务 → 看差异 → 填说明 → 确认。
 *
 * 差异全部来自 `/application/release/preview`（不写集群）：待发布改动、需一并勾选的服务、对象增删。
 * 预览里的 problems 阻断发布；warnings（如 env 里疑似明文凭据）只提示，永远不挡确认。
 * 「日常发版」= 只改某服务的 tag：行内 tag 输入框与期望配置不同时，作为 `images` 覆盖随预览与创建一起发出，
 * 不需要先去服务配置页保存。
 * 创建失败（含「线上已变化，请刷新后重试」）时错误原文留在对话框内，重新拉预览，勾选与说明保持不动。
 * 应用设置（链路追踪 / 网格）有待发布改动时单列一行，勾选键为 APP_SETTINGS_KEY；勾上会让全部服务滚动。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { appReleaseApi } from '@/api'
import StatusBadge from '@/components/StatusBadge.vue'
import AppTagInput from './AppTagInput.vue'
import type { AppReleasePreview, AppServiceRow } from '@/types'
import { errMsg, imageTag } from './releaseUtil'
import { APP_SETTINGS_KEY } from './appFeatures'

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

const appPending = computed(() => props.pendingKeys.includes(APP_SETTINGS_KEY))
const keyLabel = (key: string): string => (key === APP_SETTINGS_KEY ? '应用设置' : key)

const rowOf = (key: string): AppServiceRow | undefined => props.services.find((s) => s.key === key)
const isDeleted = (r: AppServiceRow): boolean => r.pending === 'DELETED'
/** 灰度中的服务锁住：只能在服务页「灰度」页签全量或回退（后端同样拒绝勾选） */
const inCanary = (key: string): boolean => !!rowOf(key)?.canary
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

/** 改了发布 tag（手输 / 下拉 / 点「采用」）且与期望配置不同 → 这条服务就是要发的，自动勾上；改回原值不自动取消 */
function setTag(key: string, v: string): void {
  tags.value[key] = v
  const r = rowOf(key)
  if (r && tagEdited(r) && !inCanary(key) && !selected.value.includes(key)) selected.value = [...selected.value, key]
}

function toggle(key: string, on: unknown): void {
  if (on && inCanary(key)) return
  selected.value = on ? [...new Set([...selected.value, key])] : selected.value.filter((k) => k !== key)
}

// 预览防抖状态：必须声明在下面的 immediate watch 之前 —— 组件以打开状态挂载时，
// watch 注册当刻就会调 schedulePreview，若这两个 let 还在后面会落进 TDZ 报错
let seq = 0
let timer: number | null = null

watch(visible, (v) => {
  if (!v) return
  selected.value = props.pendingKeys.filter((k) => !inCanary(k))
  tags.value = Object.fromEntries(props.services.map((s) => [s.key, desiredTag(s)]))
  note.value = ''
  preview.value = null
  error.value = ''
  schedulePreview(0)
}, { immediate: true })

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
          <el-tooltip :disabled="!row.canary" content="灰度中：先在服务页「灰度」页签全量或回退" placement="top">
            <span><el-checkbox :model-value="selected.includes(row.key)" :disabled="!!row.canary" @change="(v: unknown) => toggle(row.key, v)" /></span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="服务" min-width="130">
        <template #default="{ row }">
          <b>{{ row.key }}</b>
          <el-tag v-if="row.canary" size="small" effect="light" type="primary" class="app-rel-dlg__tag">灰度中 {{ row.canary.weight }}%</el-tag>
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
          <AppTagInput
            v-else
            :model-value="tags[row.key] ?? ''"
            @update:model-value="(v: string) => setTag(row.key, v)"
            :repo="row.desired?.imageRepo ?? ''"
            size="small"
            :class="{ 'is-edited': tagEdited(row) }"
          />
          <el-link
            v-if="!isDeleted(row) && row.newTag && (tags[row.key] ?? '').trim() !== row.newTag"
            type="warning"
            :underline="false"
            class="app-rel-dlg__newtag"
            @click="setTag(row.key, row.newTag ?? '')"
          >● 有新版本 {{ row.newTag }}，采用</el-link>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="appPending" class="app-rel-dlg__app">
      <el-checkbox :model-value="selected.includes(APP_SETTINGS_KEY)" @change="(v: unknown) => toggle(APP_SETTINGS_KEY, v)">
        <b>应用设置</b>
      </el-checkbox>
      <el-tag size="small" effect="light" type="warning">有改动</el-tag>
      <span class="app-rel-dlg__muted">链路追踪 / 服务网格作用于全部服务：勾选后所有服务都会滚动重启。</span>
    </div>

    <div v-if="!selected.length" class="app-rel-dlg__empty">请至少勾选一项。</div>

    <div v-loading="previewing" class="app-rel-dlg__preview">
      <el-alert
        v-if="requiredKeys.length"
        type="warning"
        :closable="false"
        show-icon
        :title="`以下服务需要一并勾选才能发布：${requiredKeys.join('、')}（所选服务引用了尚未发布的服务，或它们引用的依赖连接信息已修改）`"
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
            <b>{{ keyLabel(c.key) }}</b>
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
.app-rel-dlg .app-rel-dlg__newtag { display: block; font-size: 12px; margin-top: 2px; }
.app-rel-dlg .app-rel-dlg__muted { color: var(--el-text-color-secondary); font-size: 12px; }
.app-rel-dlg .app-rel-dlg__app { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 10px; }
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
