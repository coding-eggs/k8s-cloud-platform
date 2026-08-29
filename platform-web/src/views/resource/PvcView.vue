<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pvcApi } from '@/api'
import type { K8sPvc } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sPvc[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await pvcApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

const ACCESS_MODES = ['ReadWriteOnce', 'ReadWriteMany', 'ReadOnlyMany', 'ReadWriteOncePod']

function phaseType(phase?: string | null): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (phase) {
    case 'Bound': return 'success'
    case 'Pending': return 'warning'
    case 'Lost': return 'danger'
    default: return 'neutral'
  }
}

// ---------- 创建对话框（spec 创建后不可变，无编辑入口） ----------
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  name: '',
  storageClassName: '',
  accessModes: [] as string[],
  storage: '1Gi',
})

function openCreate(): void {
  form.name = ''
  form.storageClassName = ''
  form.accessModes = ['ReadWriteOnce']
  form.storage = '1Gi'
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('请输入名称')
    return
  }
  if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(name)) {
    ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾')
    return
  }
  if (!form.accessModes.length) {
    ElMessage.warning('请选择至少一种访问模式')
    return
  }
  if (!/^\d+(\.\d+)?(Ki|Mi|Gi|Ti|Pi|Ei|m)?$/.test(form.storage.trim())) {
    ElMessage.warning('容量格式非法（如 1Gi、500Mi、100m）')
    return
  }

  saving.value = true
  try {
    await pvcApi.create({ tenantId: state.tenantId!, clusterId: state.clusterId! }, {
      name,
      namespace: state.namespace!,
      storageClassName: form.storageClassName.trim() || null,
      accessModes: form.accessModes,
      storage: form.storage.trim(),
    })
    ElMessage.success('创建成功')
    dialogVisible.value = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

// ---------- 删除 ----------
async function onDelete(row: K8sPvc): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 PVC「${row.name}」？若存储未开启回收策略，底层数据卷可能残留。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await pvcApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sPvc | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sPvc): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await pvcApi.getYaml(detail.value.name, ctxParams.value)
    } catch {
      /* 拦截器已提示 */
    }
  }
})

// ---------- 上下文联动：顶栏 chip 变化时刷新 ----------
onMounted(() => {
  void load()
  void refresh()
})
watch(
  () => [state.tenantId, state.clusterId, state.namespace],
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
    <PageHeader title="PVC" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="openCreate">创建 PVC</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 PVC"
      description="点击右上「创建 PVC」新建；PVC spec 创建后不可变，调整容量请删除重建。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge :label="row.phase ?? 'Unknown'" :type="phaseType(row.phase)" />
          </template>
        </el-table-column>
        <el-table-column label="容量" width="100">
          <template #default="{ row }">{{ row.storage ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="访问模式" min-width="160">
          <template #default="{ row }">
            <span class="muted">{{ (row.accessModes ?? []).join('，') || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="StorageClass" width="150">
          <template #default="{ row }"><span class="muted">{{ row.storageClassName ?? '（默认）' }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 创建 -->
    <el-dialog v-model="dialogVisible" title="创建 PVC" width="560px" top="8vh">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="小写字母/数字/-，例如 data-pvc" />
          <div class="form-tip">K8s 资源名创建后不可修改；命名空间 = 当前上下文</div>
        </el-form-item>
        <el-form-item label="容量" required>
          <el-input v-model="form.storage" placeholder="如 1Gi、500Mi" style="width: 200px" />
        </el-form-item>
        <el-form-item label="访问模式">
          <el-select v-model="form.accessModes" multiple style="width: 100%">
            <el-option v-for="m in ACCESS_MODES" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="StorageClass">
          <el-input v-model="form.storageClassName" placeholder="留空 = 集群默认 StorageClass" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`PVC · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="状态">
              <StatusBadge :label="detail?.phase ?? 'Unknown'" :type="phaseType(detail?.phase)" />
            </el-descriptions-item>
            <el-descriptions-item label="容量">{{ detail?.storage ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="访问模式">{{ (detail?.accessModes ?? []).join('，') || '—' }}</el-descriptions-item>
            <el-descriptions-item label="StorageClass">{{ detail?.storageClassName ?? '（默认）' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.muted {
  color: var(--text-3);
}
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.yaml-block {
  margin: 0;
  padding: 14px;
  border-radius: 8px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
}
</style>
