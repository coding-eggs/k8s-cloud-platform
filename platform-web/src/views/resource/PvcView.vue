<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pvcApi, persistentVolumeApi } from '@/api'
import type { K8sPvc, K8sPersistentVolume } from '@/types'
import { useResourceContext } from '@/stores/context'
import { useResourceOptions } from '@/composables/useResourceOptions'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { fmtDate } from '@/utils/format'
import { formatBytes } from '@/utils/quantity'

/** 容量展示：基础单位字节 → 人性化（16Gi…）；null → — */
function fmtStorage(v?: number | null): string {
  return v == null ? '—' : formatBytes(v)
}

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const { options } = useResourceOptions()

const loading = ref(false)
const list = ref<K8sPvc[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))
const ctx2 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
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
  // 容量只填数字，单位固定 Gi（提交时拼成 `${n}Gi`）
  storageNum: '1',
  // dataSourceRef（可选：从已有对象填充新卷）
  dataSourceEnabled: false,
  dsKind: 'PersistentVolumeClaim',
  dsApiGroup: '',
  dsKindCustom: '',
  dsName: '',
  dsNamespace: '',
})

/** kind → apiGroup 映射（PVC=core 组，VolumeSnapshot=snapshot.storage.k8s.io；自定义用用户填的 apiGroup） */
function resolveApiGroup(kind: string): string {
  if (kind === 'PersistentVolumeClaim') return ''
  if (kind === 'VolumeSnapshot') return 'snapshot.storage.k8s.io'
  return form.dsApiGroup.trim()
}

function openCreate(): void {
  form.name = ''
  form.storageClassName = ''
  form.accessModes = ['ReadWriteOnce']
  form.storageNum = '1'
  form.dataSourceEnabled = false
  form.dsKind = 'PersistentVolumeClaim'
  form.dsApiGroup = ''
  form.dsKindCustom = ''
  form.dsName = ''
  form.dsNamespace = ''
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
  const num = form.storageNum.trim()
  if (!/^\d+(\.\d+)?$/.test(num) || parseFloat(num) <= 0) {
    ElMessage.warning('容量请输入大于 0 的数字（单位固定为 Gi）')
    return
  }

  let dataSourceRef: { apiGroup?: string; kind?: string; name?: string; namespace?: string | null } | null = null
  if (form.dataSourceEnabled) {
    const kind = form.dsKind
    const dsName = form.dsName.trim()
    if (!kind || !dsName) {
      ElMessage.warning('请选择来源类型并填写来源名称')
      return
    }
    let finalKind: string
    let apiGroup: string
    if (kind === '__custom__') {
      finalKind = form.dsKindCustom.trim()
      apiGroup = form.dsApiGroup.trim()
      if (!finalKind || !apiGroup) {
        ElMessage.warning('自定义来源需填写 apiGroup 与 kind')
        return
      }
    } else {
      finalKind = kind
      apiGroup = resolveApiGroup(kind)
    }
    dataSourceRef = {
      apiGroup,
      kind: finalKind,
      name: dsName,
      namespace: form.dsNamespace.trim() || null,
    }
  }

  saving.value = true
  try {
    await pvcApi.create(ctx2.value, {
      name,
      namespace: state.namespace!,
      storageClassName: form.storageClassName.trim() || null,
      accessModes: form.accessModes,
      storage: Number(num) * 2 ** 30,
      dataSourceRef,
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

// ---------- 查看绑定 PV（PVC 只存绑定的 PV 名；由 PV.spec.claimRef 反查） ----------
const pvDialogVisible = ref(false)
const pvLoading = ref(false)
const pvDetail = ref<K8sPersistentVolume | null>(null)
const pvYaml = ref('')
const pvTab = ref('info')

async function openBoundPv(): Promise<void> {
  if (!detail.value) return
  pvDialogVisible.value = true
  pvLoading.value = true
  pvDetail.value = null
  pvYaml.value = ''
  pvTab.value = 'info'
  try {
    const pvs = await persistentVolumeApi.list(ctx2.value)
    const ns = detail.value.namespace ?? ''
    const match = pvs.find((p) => p.claimName === detail.value!.name && (p.claimNamespace ?? '') === ns)
    if (match) {
      pvDetail.value = match
      try {
        pvYaml.value = await persistentVolumeApi.getYaml(match.name, ctx2.value)
      } catch {
        /* 拦截器已提示 */
      }
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    pvLoading.value = false
  }
}

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
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge :label="row.phase ?? 'Unknown'" :type="phaseType(row.phase)" />
          </template>
        </el-table-column>
        <el-table-column label="容量" width="100">
          <template #default="{ row }">{{ fmtStorage(row.storage) }}</template>
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
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 创建 -->
    <el-dialog v-model="dialogVisible" title="创建 PVC" width="960px" top="8vh">
      <el-form label-width="120px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="小写字母/数字/-，例如 data-pvc" />
          <div class="form-tip">K8s 资源名创建后不可修改；命名空间 = 当前上下文</div>
        </el-form-item>
        <el-form-item label="容量" required>
          <el-input v-model="form.storageNum" placeholder="仅数字，如 1、500" style="width: 240px">
            <template #append>Gi</template>
          </el-input>
        </el-form-item>
        <el-form-item label="访问模式">
          <el-select v-model="form.accessModes" multiple style="width: 100%">
            <el-option v-for="m in ACCESS_MODES" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="StorageClass">
          <el-select
            v-model="form.storageClassName"
            filterable allow-create default-first-option clearable
            placeholder="留空 = 集群默认 StorageClass" style="width: 100%"
          >
            <el-option v-for="n in options.storageClasses" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据来源">
          <el-switch v-model="form.dataSourceEnabled" active-text="从已有数据源创建（dataSourceRef）" />
          <div class="form-tip">开启后可用已有 PVC / 卷快照等对象初始化新卷；需集群支持对应 volume populator</div>
        </el-form-item>
        <template v-if="form.dataSourceEnabled">
          <el-form-item label="来源类型 kind" required>
            <el-select v-model="form.dsKind" style="width: 100%">
              <el-option label="PersistentVolumeClaim（已有 PVC）" value="PersistentVolumeClaim" />
              <el-option label="VolumeSnapshot（卷快照）" value="VolumeSnapshot" />
              <el-option label="自定义…" value="__custom__" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.dsKind === '__custom__'" label="apiGroup" required>
            <el-input v-model="form.dsApiGroup" placeholder="如 snapshot.storage.k8s.io" />
          </el-form-item>
          <el-form-item v-if="form.dsKind === '__custom__'" label="自定义 kind" required>
            <el-input v-model="form.dsKindCustom" placeholder="来源对象的 Kind" />
          </el-form-item>
          <el-form-item label="来源名称 name" required>
            <el-input v-model="form.dsName" placeholder="来源对象名称" />
          </el-form-item>
          <el-form-item label="来源命名空间">
            <el-input v-model="form.dsNamespace" placeholder="留空 = 当前命名空间（跨命名空间需开启特性门）" />
          </el-form-item>
        </template>
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
            <el-descriptions-item label="容量">{{ fmtStorage(detail?.storage) }}</el-descriptions-item>
            <el-descriptions-item label="访问模式">{{ (detail?.accessModes ?? []).join('，') || '—' }}</el-descriptions-item>
            <el-descriptions-item label="StorageClass">{{ detail?.storageClassName ?? '（默认）' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail?.dataSourceRef" label="数据来源">
              {{ detail.dataSourceRef.kind }}/{{ detail.dataSourceRef.name }}
              <span class="muted" v-if="detail.dataSourceRef.namespace">（{{ detail.dataSourceRef.namespace }}）</span>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
          <div class="pv-section">
            <el-button type="primary" plain @click="openBoundPv">查看绑定的 PV</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <!-- 绑定 PV -->
    <el-dialog v-model="pvDialogVisible" :title="`绑定的 PV${pvDetail ? ' · ' + pvDetail.name : ''}`" width="640px">
      <div v-if="pvLoading" class="muted">加载中…</div>
      <template v-else-if="pvDetail">
        <el-tabs v-model="pvTab">
          <el-tab-pane label="概览" name="info">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="状态">{{ pvDetail.phase ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="容量">{{ fmtStorage(pvDetail.capacity) }}</el-descriptions-item>
              <el-descriptions-item label="访问模式">{{ (pvDetail.accessModes ?? []).join('，') || '—' }}</el-descriptions-item>
              <el-descriptions-item label="StorageClass">{{ pvDetail.storageClassName ?? '（默认）' }}</el-descriptions-item>
              <el-descriptions-item label="回收策略">{{ pvDetail.reclaimPolicy ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ fmtDate(pvDetail.creationTime) }}</el-descriptions-item>
            </el-descriptions>
          </el-tab-pane>
          <el-tab-pane label="YAML（只读）" name="yaml">
            <pre v-if="pvYaml" class="yaml-block">{{ pvYaml }}</pre>
            <div v-else class="muted">加载中…</div>
          </el-tab-pane>
        </el-tabs>
      </template>
      <div v-else class="muted">未找到与该 PVC 绑定的 PV（可能尚未绑定，或 PV 的 claimRef 不匹配）</div>
    </el-dialog>
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
.pv-section {
  margin-top: 16px;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
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
