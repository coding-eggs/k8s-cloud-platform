<script setup lang="ts">
/**
 * 回滚确认：以历史发布的快照生成一条新发布（不是倒带，发布号继续往前走）。
 *
 * 差异在前端算：用 release/get 取「目标」和「线上」两份快照逐服务比较，不加后端端点。
 * 目标就是线上版本（失败后回到失败前）时没有差异，只给出说明。
 * 后端会再做自己的校验（依赖是否还在、是否有进行中的发布…），拒绝原文原样显示在对话框内。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { appReleaseApi } from '@/api'
import StatusBadge from '@/components/StatusBadge.vue'
import type { AppRelease } from '@/types'
import { diffDependencies, diffServices, errMsg, imageTag } from './releaseUtil'

const props = defineProps<{
  modelValue: boolean
  appId: string
  tenantId?: string
  /** 要回滚到的发布号 */
  targetNo: number | null
  /** 当前线上发布号 */
  liveNo: number | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'rolled-back', executionId: number): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const loading = ref(false)
const submitting = ref(false)
const loadError = ref('')
const error = ref('')
const note = ref('')
const target = ref<AppRelease | null>(null)
const live = ref<AppRelease | null>(null)

watch(() => [props.modelValue, props.targetNo, props.liveNo] as const, async ([open, no, liveNo]) => {
  if (!open || no == null) return
  target.value = null
  live.value = null
  note.value = ''
  error.value = ''
  loadError.value = ''
  loading.value = true
  try {
    const [t, l] = await Promise.all([
      appReleaseApi.get(props.appId, no, props.tenantId),
      liveNo != null && liveNo !== no ? appReleaseApi.get(props.appId, liveNo, props.tenantId) : Promise.resolve(null),
    ])
    target.value = t
    live.value = l ?? t
  } catch (e) {
    loadError.value = errMsg(e, '读取发布快照失败')
  } finally {
    loading.value = false
  }
}, { immediate: true })

const backToLive = computed(() => props.targetNo != null && props.targetNo === props.liveNo)
const serviceDiffs = computed(() => (target.value && live.value ? diffServices(live.value.snapshot, target.value.snapshot) : []))
const depDiffs = computed(() => (target.value && live.value ? diffDependencies(live.value.snapshot, target.value.snapshot) : []))
const TYPE_META = {
  ADDED: { text: '将重建', type: 'success' },
  REMOVED: { text: '将删除', type: 'danger' },
  CHANGED: { text: '将变更', type: 'warning' },
} as const

async function submit(): Promise<void> {
  if (props.targetNo == null) return
  submitting.value = true
  error.value = ''
  try {
    const id = await appReleaseApi.rollback({
      appId: props.appId,
      releaseNo: props.targetNo,
      note: note.value.trim() || undefined,
      tenantId: props.tenantId,
    })
    ElMessage.success('回滚已开始')
    visible.value = false
    emit('rolled-back', id)
  } catch (e) {
    error.value = errMsg(e, '回滚失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`回滚到发布 #${targetNo ?? ''}`" width="720px" class="app-rb-dlg" destroy-on-close>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="backToLive ? `回到线上版本 #${targetNo}` : '回滚会生成一条新发布'"
      :description="backToLive
        ? '失败的发布不会自动回滚。这里把应用重新对齐到失败前的线上版本；没有未发布编辑的服务期望配置会同步回该版本，有编辑的保留并继续显示为待发布。'
        : '以该历史发布的快照作为新版本重新发布，发布号继续递增。没有未发布编辑的服务，期望配置同步为旧快照；有编辑的保留，继续显示为待发布。'"
    />

    <div v-loading="loading" class="app-rb-dlg__body">
      <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="loadError" />
      <template v-else-if="target">
        <h4>相对线上 #{{ liveNo ?? '—' }} 的变化</h4>
        <div v-if="backToLive" class="app-rb-dlg__muted">
          目标就是线上快照，服务配置没有差异；若上一次发布已经下发了部分对象，回滚会按这份快照重新对齐。
        </div>
        <div v-else-if="!serviceDiffs.length && !depDiffs.length" class="app-rb-dlg__muted">两份快照没有差异。</div>
        <ul v-else class="app-rb-dlg__list">
          <li v-for="d in serviceDiffs" :key="'s' + d.key">
            <StatusBadge :label="TYPE_META[d.type].text" :type="TYPE_META[d.type].type" />
            <b>服务 {{ d.key }}</b>
            <span v-if="d.type === 'CHANGED' && imageTag(d.fromImage) !== imageTag(d.toImage)">
              <code>{{ imageTag(d.fromImage) }}</code> → <code>{{ imageTag(d.toImage) }}</code>
            </span>
            <span v-else-if="d.type !== 'CHANGED'"><code>{{ imageTag(d.fromImage || d.toImage) }}</code></span>
            <span v-if="d.fields.length" class="app-rb-dlg__muted">{{ d.fields.join('、') }}</span>
            <span v-if="d.envNotes.length" class="app-rb-dlg__env">环境变量：{{ d.envNotes.join('  ') }}</span>
          </li>
          <li v-for="d in depDiffs" :key="'d' + d.key">
            <StatusBadge :label="TYPE_META[d.type].text" :type="TYPE_META[d.type].type" />
            <b>依赖 {{ d.key }}</b>
          </li>
        </ul>
      </template>
    </div>

    <el-form label-position="top" class="app-rb-dlg__note" @submit.prevent>
      <el-form-item label="说明（可选）">
        <el-input v-model="note" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="如：新版本下单报错，先回退" />
      </el-form-item>
    </el-form>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="danger" :loading="submitting" :disabled="loading || !!loadError || !target" @click="submit">确认回滚</el-button>
    </template>
  </el-dialog>
</template>

<style>
/* el-dialog 内部节点没有 data-v-*；唯一前缀 app-rb-dlg */
.app-rb-dlg .app-rb-dlg__body { margin-top: 12px; min-height: 40px; }
.app-rb-dlg .app-rb-dlg__body h4 { margin: 0 0 8px; font-size: 13px; }
.app-rb-dlg .app-rb-dlg__muted { color: var(--el-text-color-secondary); font-size: 12px; }
.app-rb-dlg .app-rb-dlg__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.app-rb-dlg .app-rb-dlg__list li { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.app-rb-dlg .app-rb-dlg__env { font-family: var(--el-font-family-mono, monospace); font-size: 12px; color: var(--el-text-color-secondary); }
.app-rb-dlg .app-rb-dlg__note { margin-top: 12px; }
</style>
