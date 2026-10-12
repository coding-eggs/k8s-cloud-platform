<script setup lang="ts">
/**
 * 回滚对话框：列出该工作负载的历史版本，选一个回滚。
 *
 * <p>版本来自 K8s 自身的 rollout 历史（Deployment→ReplicaSet、StatefulSet/DaemonSet→ControllerRevision），
 * 由后端归一成同一结构（见 types/workload.ts 的 WorkloadRevision）。
 *
 * <p>回滚的语义要说清楚，因为它和"编辑"不一样：把那个版本的 pod 模板<b>覆盖</b>到当前工作负载上，
 * 于是触发一次滚动更新，并产生一个<b>新</b>版本号（旧版本号不会被复用）。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { workloadApi } from '@/api'
import type { WorkloadRevision } from '@/types/workload'
import { fmtAge, fmtDate } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'

const props = defineProps<{
  modelValue: boolean
  /** 只用到 name：历史由后端按名字取。故不绑 WorkloadDetail —— 列表页的行类型是 K8sWorkload */
  row: { name: string } | null
  ctx: { tenantId: string; clusterId: string; namespace: string }
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; done: [] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const loading = ref(false)
const submitting = ref(false)
const revisions = ref<WorkloadRevision[]>([])
const selected = ref<number | null>(null)

/** 能回滚的版本：当前版本选了也是白滚（模板一模一样），故禁选 */
const hasTarget = computed(() => selected.value != null)

async function load(): Promise<void> {
  const name = props.row?.name
  if (!name) return
  loading.value = true
  revisions.value = []
  selected.value = null
  try {
    revisions.value = await workloadApi.revisions(name, props.ctx)
    // 默认停在「上一个版本」：列表已按新→旧，第一条非当前的即为所求
    selected.value = revisions.value.find((r) => !r.current)?.revision ?? null
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.row?.name] as const,
  ([open]) => { if (open) void load() },
)

async function submit(): Promise<void> {
  const name = props.row?.name
  if (!name || selected.value == null) return
  submitting.value = true
  try {
    await workloadApi.rollback(name, { ...props.ctx, revision: selected.value })
    ElMessage.success(`已回滚到版本 ${selected.value}，工作负载将滚动更新`)
    visible.value = false
    emit('done')
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`回滚 · ${row?.name ?? ''}`" width="1080px">
    <div v-loading="loading">
      <EmptyState
        v-if="!loading && revisions.length === 0"
        title="没有可回滚的历史版本"
        description="该工作负载的 pod 模板从未变更过（历史由 K8s 的 rollout 记录保留，可能已被控制器回收）。"
      />
      <template v-else>
        <div class="hint">
          回滚会把所选版本的 pod template <b>覆盖</b>到当前工作负载上，触发一次滚动更新，
          并产生一个新版本号（旧版本号不会被复用）。
        </div>
        <el-table :data="revisions" size="small" max-height="380">
          <el-table-column label="选择" width="70">
            <template #default="{ row: r }">
              <el-radio v-model="selected" :value="r.revision" :disabled="r.current"><span /></el-radio>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="90">
            <template #default="{ row: r }">
              <code class="res-name">{{ r.revision }}</code>
              <el-tag v-if="r.current" type="success" size="small" effect="plain" class="cur-tag">当前</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="镜像" min-width="240">
            <template #default="{ row: r }">
              <div v-for="(img, i) in r.images ?? []" :key="i" class="img-line">{{ img }}</div>
              <span v-if="!(r.images ?? []).length" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="变更说明" width="140">
            <template #default="{ row: r }">
              <span v-if="r.changeCause">{{ r.changeCause }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="160">
            <template #default="{ row: r }">
              <div>{{ fmtDate(r.createdAt) }}</div>
              <div class="muted">{{ fmtAge(r.createdAt) }}</div>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!hasTarget" @click="submit">
        回滚到所选版本
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  color: var(--text-1);
}
.cur-tag {
  margin-left: 6px;
}
.img-line {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.5;
  word-break: break-all;
}
.muted {
  color: var(--text-3);
}
</style>
