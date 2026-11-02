<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { pvcApi, volumeSnapshotApi } from '@/api'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import type { K8sPvc, K8sVolumeSnapshot, VolumeSnapshotCapability } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import YamlBlock from '@/components/YamlBlock.vue'
import VolumeSnapshotCreateDialog from '@/components/storage/VolumeSnapshotCreateDialog.vue'
import VolumeSnapshotRestoreDialog from '@/components/storage/VolumeSnapshotRestoreDialog.vue'
import { fmtDate } from '@/utils/format'
import { formatBytes } from '@/utils/quantity'
import {
  GATE_TEXT,
  SNAPSHOT_STATE_LABEL,
  deletionPolicyText,
  isUnfinished,
  snapshotState,
  type SnapshotState,
} from '@/utils/volumeSnapshot'

/**
 * 卷快照（S4.3，spec §6.1）。门控在页面内而不是隐藏菜单：能不能用要调接口才知道、且每个集群不同，
 * 放在页里用户才看得到为什么用不了。
 */
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()
const perm = usePermission()
const canCreate = computed(() => perm.has(apiCodes.volumeSnapshotCreate))
const canDelete = computed(() => perm.has(apiCodes.volumeSnapshotDelete))
/** 恢复 = 创建 PVC */
const canRestore = computed(() => perm.has(apiCodes.pvcCreate))

const ctx3 = computed(() => ({ tenantId: state.tenantId!, clusterId: state.clusterId!, namespace: state.namespace! }))
const ctx2 = computed(() => ({ tenantId: state.tenantId!, clusterId: state.clusterId! }))

const loading = ref(false)
const capability = ref<VolumeSnapshotCapability | null>(null)
/** 门控探测本身失败（不是「不支持」）：显示报错和刷新，不把入口藏起来 */
const probeFailed = ref(false)
const list = ref<K8sVolumeSnapshot[]>([])
const pvcs = ref<K8sPvc[]>([])
/** 驱动状态列的「现在」：轮询时更新，让「超过 5 分钟」能翻过去 */
const now = ref(Date.now())

let pollTimer: ReturnType<typeof setInterval> | null = null
/**
 * 每次 refresh / 卸载都加一：在途请求回来时代数变了就丢弃结果、也不再续轮询。
 * 否则离开页面时恰好在途的那次轮询会重新 setInterval，没人清。
 */
let generation = 0

function stopPolling(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 有没结束的快照就每 5 秒拉一次；全部结束即停 */
function syncPolling(): void {
  if (list.value.some(isUnfinished)) {
    if (!pollTimer) pollTimer = setInterval(() => void reloadList(), 5000)
  } else {
    stopPolling()
  }
}

async function reloadList(): Promise<void> {
  const gen = generation
  try {
    const snaps = await volumeSnapshotApi.list(ctx3.value)
    if (gen !== generation) return
    list.value = snaps
    now.value = Date.now()
  } catch {
    if (gen === generation) stopPolling()
    return
  }
  syncPolling()
}

async function refresh(): Promise<void> {
  if (!ready.value) return
  const gen = ++generation
  loading.value = true
  probeFailed.value = false
  stopPolling()
  try {
    try {
      const cap = await volumeSnapshotApi.capability(ctx2.value)
      if (gen !== generation) return
      capability.value = cap
    } catch {
      if (gen !== generation) return
      capability.value = null
      probeFailed.value = true
      return
    }
    if (!capability.value.available) {
      list.value = []
      return
    }
    // PVC 列表只给两个弹窗用：没有 PVC 列表权限（或请求失败）不能把快照列表也拖空
    const [snaps, claims] = await Promise.all([
      volumeSnapshotApi.list(ctx3.value),
      pvcApi.list(ctx3.value).catch(() => [] as K8sPvc[]),
    ])
    if (gen !== generation) return
    list.value = snaps
    pvcs.value = claims
    now.value = Date.now()
    syncPolling()
  } catch {
    /* 拦截器已提示 */
  } finally {
    if (gen === generation) loading.value = false
  }
}

const gateText = computed(() => {
  const reason = capability.value?.reason
  return reason ? GATE_TEXT[reason] : ''
})

function badgeType(st: SnapshotState): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (st) {
    case 'ready': return 'success'
    case 'failed': return 'danger'
    case 'stalled': return 'warning'
    case 'deleting': return 'neutral'
    default: return 'info'
  }
}

function classOf(row: K8sVolumeSnapshot) {
  return capability.value?.classes.find((c) => c.name === row.volumeSnapshotClassName) ?? null
}

// ---------- 创建 / 恢复 ----------
const createVisible = ref(false)
const restoreVisible = ref(false)
const restoreTarget = ref<K8sVolumeSnapshot | null>(null)

function openRestore(row: K8sVolumeSnapshot): void {
  restoreTarget.value = row
  restoreVisible.value = true
}

function onRestored(): void {
  // 恢复建的是新 PVC：跳过去，用户接着要改工作负载去挂载
  void router.push({ name: 'pvcs' })
}

// ---------- 删除 ----------
async function onDelete(row: K8sVolumeSnapshot): Promise<void> {
  const cls = classOf(row)
  try {
    await ElMessageBox.confirm(
      `确认删除快照「${row.name}」？${deletionPolicyText(cls?.deletionPolicy)}`,
      '删除快照',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await volumeSnapshotApi.delete(row.name, ctx3.value)
    ElMessage.success('已提交删除')
    await reloadList()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- YAML ----------
const yamlVisible = ref(false)
const yamlTitle = ref('')
const yamlText = ref('')

async function openYaml(row: K8sVolumeSnapshot): Promise<void> {
  yamlTitle.value = row.name
  yamlText.value = ''
  yamlVisible.value = true
  try {
    yamlText.value = await volumeSnapshotApi.getYaml(row.name, ctx3.value)
  } catch {
    /* 拦截器已提示 */
  }
}

function onRowCommand(cmd: string, row: K8sVolumeSnapshot): void {
  if (cmd === 'restore') openRestore(row)
  else if (cmd === 'yaml') void openYaml(row)
  else if (cmd === 'delete') void onDelete(row)
}

onMounted(() => {
  void load()
  void refresh()
})
onBeforeUnmount(() => {
  generation++
  stopPolling()
})
watch(
  [() => [state.tenantId, state.clusterId, state.namespace], ready],
  () => {
    if (ready.value) void refresh()
  },
)

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader title="卷快照" :description="contextDesc">
      <el-button :disabled="!ready" @click="refresh">刷新</el-button>
      <el-button
        v-if="canCreate && capability?.available"
        type="primary"
        :disabled="!ready"
        @click="createVisible = true"
      >创建快照</el-button>
    </PageHeader>

    <div v-if="probeFailed" class="panel vs-gate">
      <div>没能确认这个集群是否支持卷快照（探测接口出错）。可以稍后点「刷新」重试。</div>
    </div>
    <div v-else-if="capability && !capability.available" class="panel vs-gate">
      <div>{{ gateText }}</div>
    </div>

    <template v-else-if="capability?.available">
      <EmptyState
        v-if="list.length === 0 && !loading"
        title="该命名空间下暂无卷快照"
        :description="canCreate ? '点击右上「创建快照」，或在 PVC 页的行操作里给某个 PVC 打快照。' : '当前账号没有创建快照的权限，只能查看。'"
      />
      <div v-else class="panel table-panel">
        <el-table v-loading="loading" :data="list" stripe>
          <el-table-column label="名称" min-width="200">
            <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
          </el-table-column>
          <el-table-column label="来源 PVC" min-width="150">
            <template #default="{ row }"><span class="muted">{{ row.sourcePvcName ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="状态" min-width="220">
            <template #default="{ row }">
              <StatusBadge
                :label="SNAPSHOT_STATE_LABEL[snapshotState(row, now)]"
                :type="badgeType(snapshotState(row, now))"
              />
              <div v-if="snapshotState(row, now) === 'failed'" class="state-note danger">{{ row.errorMessage }}</div>
              <div v-else-if="snapshotState(row, now) === 'stalled'" class="state-note warn">
                超过 5 分钟还没绑定快照内容：可能没有运行 snapshot-controller，或快照类配置有误
              </div>
              <div v-else-if="snapshotState(row, now) === 'deleting'" class="state-note">
                可能还有 PVC 正在从它恢复，恢复完成后才会删掉
              </div>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="110">
            <template #default="{ row }">{{ row.restoreSize ? formatBytes(row.restoreSize) : '—' }}</template>
          </el-table-column>
          <el-table-column label="快照类" min-width="160">
            <template #default="{ row }"><span class="muted">{{ row.volumeSnapshotClassName ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-dropdown trigger="click" @command="(cmd: string) => onRowCommand(cmd, row)">
                <el-button link type="primary">操作 ▾</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      command="restore"
                      :disabled="!canRestore || snapshotState(row, now) !== 'ready'"
                    >恢复为新 PVC</el-dropdown-item>
                    <el-dropdown-item command="yaml">查看 YAML</el-dropdown-item>
                    <el-dropdown-item
                      command="delete"
                      divided
                      :disabled="!canDelete || snapshotState(row, now) === 'deleting'"
                    >删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <VolumeSnapshotCreateDialog
      v-if="ready"
      v-model="createVisible"
      :ctx="ctx3"
      :capability="capability"
      :pvcs="pvcs"
      @created="reloadList"
    />
    <VolumeSnapshotRestoreDialog
      v-if="ready"
      v-model="restoreVisible"
      :ctx="ctx3"
      :snapshot="restoreTarget"
      :capability="capability"
      :pvcs="pvcs"
      @restored="onRestored"
    />

    <el-drawer v-model="yamlVisible" :title="`卷快照 · ${yamlTitle}`" size="640px">
      <YamlBlock v-if="yamlText" :yaml="yamlText" />
      <div v-else class="muted">加载中…</div>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.vs-gate {
  padding: 24px;
  color: var(--text-2);
  line-height: 1.7;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.muted {
  color: var(--text-3);
}
.state-note {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-3);
}
.state-note.danger {
  color: var(--danger, #f56c6c);
}
.state-note.warn {
  color: var(--warning, #e6a23c);
}
</style>
