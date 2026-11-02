<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pvcApi } from '@/api'
import type { K8sPvc, K8sVolumeSnapshot, VolumeSnapshotCapability } from '@/types'
import { formatBytes } from '@/utils/quantity'
import { RESTORE_NOTE, defaultRestoreName, defaultRestoreStorage } from '@/utils/volumeSnapshot'

/**
 * 从快照恢复为新 PVC（spec §6.3）。提交到 `POST /pvcs`，带 dataSourceRef；总是建新卷，不覆盖原卷。
 * 存储类只列与快照驱动一致的；容量下限是快照大小（驱动没报大小时不设下限）。
 */
const props = defineProps<{
  modelValue: boolean
  ctx: { tenantId: string; clusterId: string; namespace: string }
  snapshot: K8sVolumeSnapshot | null
  capability: VolumeSnapshotCapability | null
  pvcs: K8sPvc[]
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'restored', name: string): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const GI = 2 ** 30
const ACCESS_MODES = ['ReadWriteOnce', 'ReadWriteMany', 'ReadOnlyMany', 'ReadWriteOncePod']

const form = reactive({ name: '', storageClassName: '', storageNum: '', accessModes: [] as string[] })
const saving = ref(false)

const sourcePvc = computed(() => props.pvcs.find((p) => p.name === props.snapshot?.sourcePvcName) ?? null)

/** 快照所用快照类的驱动 */
const driver = computed(() =>
  props.capability?.classes.find((c) => c.name === props.snapshot?.volumeSnapshotClassName)?.driver ?? null,
)

/** 只列 provisioner 与快照驱动一致的存储类；驱动未知时不过滤，交给后端判断 */
const storageClassOptions = computed(() =>
  (props.capability?.storageClasses ?? []).filter((s) => !driver.value || s.provisioner === driver.value),
)

/** 容量下限（字节）；快照大小为空或 0 时没有下限 */
const minBytes = computed(() => {
  const r = props.snapshot?.restoreSize
  return r != null && r > 0 ? r : null
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open || !props.snapshot) return
    form.name = defaultRestoreName(props.snapshot.name)
    const fromSource = sourcePvc.value?.storageClassName
    form.storageClassName = storageClassOptions.value.some((s) => s.name === fromSource)
      ? fromSource!
      : storageClassOptions.value[0]?.name ?? ''
    const bytes = defaultRestoreStorage(sourcePvc.value?.storage, props.snapshot.restoreSize)
    form.storageNum = bytes == null ? '1' : String(Math.ceil(bytes / GI))
    form.accessModes = sourcePvc.value?.accessModes?.length ? [...sourcePvc.value.accessModes] : ['ReadWriteOnce']
  },
)

async function submit(): Promise<void> {
  const snap = props.snapshot
  if (!snap) return
  const name = form.name.trim()
  if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(name) || name.length > 63) {
    ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，以字母或数字开头结尾，不超过 63 个字符')
    return
  }
  const num = form.storageNum.trim()
  if (!/^\d+(\.\d+)?$/.test(num) || parseFloat(num) <= 0) {
    ElMessage.warning('容量请输入大于 0 的数字（单位固定为 Gi）')
    return
  }
  const bytes = Math.round(parseFloat(num) * GI)
  if (minBytes.value != null && bytes < minBytes.value) {
    ElMessage.warning(`容量不能小于快照大小 ${formatBytes(minBytes.value)}`)
    return
  }
  if (!form.accessModes.length) {
    ElMessage.warning('请选择至少一种访问模式')
    return
  }
  saving.value = true
  try {
    await pvcApi.create(
      { tenantId: props.ctx.tenantId, clusterId: props.ctx.clusterId },
      {
        name,
        namespace: props.ctx.namespace,
        storageClassName: form.storageClassName || null,
        accessModes: form.accessModes,
        storage: bytes,
        dataSourceRef: { apiGroup: 'snapshot.storage.k8s.io', kind: 'VolumeSnapshot', name: snap.name },
      },
    )
    ElMessage.success('已创建恢复用的 PVC —— 要使用它，请修改工作负载去挂载')
    visible.value = false
    emit('restored', name)
  } catch {
    /* 拦截器已提示：快照未就绪、容量太小、驱动不一致等 */
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="恢复为新 PVC" width="640px">
    <el-form label-width="100px">
      <el-form-item label="快照">
        <code>{{ snapshot?.name }}</code>
        <span class="vs-tip" style="margin-left: 8px">大小 {{ minBytes != null ? formatBytes(minBytes) : '未知' }}</span>
      </el-form-item>
      <el-form-item label="新 PVC 名称" required>
        <el-input v-model="form.name" maxlength="63" show-word-limit />
      </el-form-item>
      <el-form-item label="存储类">
        <el-select v-model="form.storageClassName" placeholder="与快照同驱动的存储类" style="width: 100%">
          <el-option v-for="s in storageClassOptions" :key="s.name" :label="s.name" :value="s.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="容量" required>
        <el-input v-model="form.storageNum" style="width: 200px">
          <template #append>Gi</template>
        </el-input>
      </el-form-item>
      <el-form-item label="访问模式">
        <el-select v-model="form.accessModes" multiple style="width: 100%">
          <el-option v-for="m in ACCESS_MODES" :key="m" :label="m" :value="m" />
        </el-select>
      </el-form-item>
    </el-form>
    <div class="vs-tip">{{ RESTORE_NOTE }}</div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">恢复</el-button>
    </template>
  </el-dialog>
</template>

<!-- el-dialog 内部节点没有 data-v-*，scoped 匹配不到 —— 用非 scoped 块 + 独有前缀 -->
<style>
.vs-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
</style>
