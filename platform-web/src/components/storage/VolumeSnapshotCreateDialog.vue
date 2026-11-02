<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { volumeSnapshotApi } from '@/api'
import type { K8sPvc, VolumeSnapshotCapability } from '@/types'
import { CREATE_NOTES, UNSUPPORTED_TEXT, defaultSnapshotName, deletionPolicyText } from '@/utils/volumeSnapshot'

/**
 * 创建快照（spec §6.2）。两个入口：快照页「创建」（自选 PVC）与 PVC 行操作（`pvcName` 预填并锁定）。
 * 只有能打快照的 PVC 可选；不能选的显示为禁用项并写明原因。最终校验在后端。
 */
const props = defineProps<{
  modelValue: boolean
  ctx: { tenantId: string; clusterId: string; namespace: string }
  capability: VolumeSnapshotCapability | null
  pvcs: K8sPvc[]
  /** 有值 = 从 PVC 行操作进来，来源锁定 */
  pvcName?: string | null
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'created'): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const form = reactive({ name: '', pvc: '', className: '' })
/** 用户手改过名称就不再跟着来源 PVC 自动刷新 */
const nameTouched = ref(false)
const saving = ref(false)

/** 这个 PVC 不能打快照的原因；能打返回空串 */
function blockReason(p: K8sPvc): string {
  if (p.phase !== 'Bound') return 'PVC 未绑定'
  const sc = props.capability?.storageClasses.find((s) => s.name === p.storageClassName)
  if (!sc || sc.snapshotClasses.length === 0) return UNSUPPORTED_TEXT
  return ''
}

/** 当前来源 PVC 可用的快照类（按驱动过滤） */
const classOptions = computed(() => {
  const p = props.pvcs.find((x) => x.name === form.pvc)
  const sc = props.capability?.storageClasses.find((s) => s.name === p?.storageClassName)
  const names = new Set(sc?.snapshotClasses ?? [])
  return (props.capability?.classes ?? []).filter((c) => names.has(c.name))
})

const chosenClass = computed(() => classOptions.value.find((c) => c.name === form.className) ?? null)

/** 快照类默认值：驱动的默认类，否则唯一匹配，否则留空让用户选 */
function pickDefaultClass(): void {
  const opts = classOptions.value
  const defaults = opts.filter((c) => c.isDefault)
  form.className = defaults.length === 1 ? defaults[0]!.name : opts.length === 1 ? opts[0]!.name : ''
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    nameTouched.value = false
    form.pvc = props.pvcName ?? ''
    form.name = form.pvc ? defaultSnapshotName(form.pvc) : ''
    pickDefaultClass()
  },
)

watch(
  () => form.pvc,
  (pvc) => {
    if (!nameTouched.value) form.name = pvc ? defaultSnapshotName(pvc) : ''
    pickDefaultClass()
  },
)

async function submit(): Promise<void> {
  const name = form.name.trim()
  if (!form.pvc) {
    ElMessage.warning('请选择来源 PVC')
    return
  }
  if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(name) || name.length > 63) {
    ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，以字母或数字开头结尾，不超过 63 个字符')
    return
  }
  if (!form.className && classOptions.value.length > 1) {
    ElMessage.warning('请选择快照类')
    return
  }
  saving.value = true
  try {
    await volumeSnapshotApi.create(
      { tenantId: props.ctx.tenantId, clusterId: props.ctx.clusterId },
      { name, namespace: props.ctx.namespace, sourcePvcName: form.pvc, volumeSnapshotClassName: form.className || null },
    )
    ElMessage.success('已提交创建快照 —— 状态会在快照列表里更新')
    visible.value = false
    emit('created')
  } catch {
    /* 拦截器已提示：PVC 未绑定、存储类不支持等，后端那句原话就在里面 */
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="创建快照" width="640px">
    <el-form label-width="100px">
      <el-form-item label="来源 PVC" required>
        <el-select v-model="form.pvc" :disabled="!!pvcName" filterable placeholder="选择要打快照的 PVC" style="width: 100%">
          <el-option v-for="p in pvcs" :key="p.name" :label="p.name" :value="p.name" :disabled="!!blockReason(p)">
            <span>{{ p.name }}</span>
            <span v-if="blockReason(p)" class="vs-opt-reason">{{ blockReason(p) }}</span>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="63" show-word-limit @input="nameTouched = true" />
      </el-form-item>
      <el-form-item label="快照类">
        <el-select v-model="form.className" :disabled="!form.pvc" placeholder="选择快照类" style="width: 100%">
          <el-option
            v-for="c in classOptions"
            :key="c.name"
            :label="c.isDefault ? `${c.name}（默认）` : c.name"
            :value="c.name"
          />
        </el-select>
        <div v-if="chosenClass" class="vs-tip">{{ deletionPolicyText(chosenClass.deletionPolicy) }}</div>
      </el-form-item>
    </el-form>
    <ul class="vs-notes">
      <li v-for="n in CREATE_NOTES" :key="n">{{ n }}</li>
    </ul>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">创建</el-button>
    </template>
  </el-dialog>
</template>

<!-- el-dialog 内部节点没有 data-v-*，scoped :deep 匹配不到 —— 用非 scoped 块 + 独有前缀 -->
<style>
.vs-opt-reason {
  float: right;
  margin-left: 12px;
  color: var(--text-3);
  font-size: 12px;
}
.vs-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.vs-notes {
  margin: 4px 0 0;
  padding-left: 18px;
  color: var(--warning, #e6a23c);
  font-size: 12px;
  line-height: 1.7;
}
</style>
