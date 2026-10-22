<script setup lang="ts">
import { isManaged, managedReason } from '@/utils/managedResource'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pvcApi, persistentVolumeApi } from '@/api'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import type { K8sPvc, K8sPersistentVolume } from '@/types'
import { useResourceContext } from '@/stores/context'
import { useResourceOptions } from '@/composables/useResourceOptions'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { fmtDate } from '@/utils/format'
import { formatBytes } from '@/utils/quantity'
import YamlBlock from '@/components/YamlBlock.vue'

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

// ---------- 扩容（S4.1） ----------

const perm = usePermission()
/** 扩容要 `tenant:pvc:update` —— 与创建/删除分开，因为扩容是唯一能改 spec 的动作 */
const canResize = computed(() => perm.has(apiCodes.pvcUpdate))

/**
 * 扩容的**中间态**。⚠️ 它不在 `phase` 上：扩容期间 `phase` 一直是 `Bound`，
 * 只有 `status.conditions` 里看得出来 —— 不标出来，"正在扩"和"已经好了"长得一模一样，
 * 运维会把正常过程当故障处置。
 */
const RESIZE_CONDITIONS: Record<string, string> = {
  Resizing: '正在扩容',
  FileSystemResizePending: '等 Pod 重启完成',
}

/** 这个卷此刻在扩容吗（以及在等什么）；没有就是空串 */
function resizeState(row: K8sPvc): string {
  for (const c of row.conditions ?? []) {
    const text = RESIZE_CONDITIONS[c]
    if (text) return text
  }
  return ''
}

const resizeVisible = ref(false)
const resizing = ref(false)
const resizeRow = ref<K8sPvc | null>(null)
/** 只填数字，单位固定 Gi —— 与创建对话框同一套口径 */
const resizeNum = ref('')

/** 现在多少 Gi（列表给的是字节） */
const resizeNowGi = computed(() =>
  resizeRow.value?.storage == null ? null : resizeRow.value.storage / 1024 / 1024 / 1024,
)

function openResize(row: K8sPvc): void {
  resizeRow.value = row
  const now = resizeNowGi.value
  // 默认填一个"至少 +1Gi"的整数，用户改一个数就能交
  resizeNum.value = now == null ? '1' : String(Math.ceil(now) + 1)
  resizeVisible.value = true
}

async function submitResize(): Promise<void> {
  const row = resizeRow.value
  if (!row) return
  const num = resizeNum.value.trim()
  if (!/^\d+(\.\d+)?$/.test(num) || parseFloat(num) <= 0) {
    ElMessage.warning('容量请输入大于 0 的数字（单位固定为 Gi）')
    return
  }
  const now = resizeNowGi.value
  // ⚠️ 这一遍是**为体验**，不是防线：真正的判据在后端 —— 它读的是**集群上那个对象**，
  //    而这里的 row 可能是几分钟前拉的列表。两道都要有，但别把这里当权威。
  if (now != null && parseFloat(num) <= now) {
    ElMessage.warning(`只能往大改：现在是 ${now}Gi。K8s 不支持缩容，要变小只能删了重建。`)
    return
  }
  resizing.value = true
  try {
    // 走**扩容专用端点**（不是 update）—— 后端读-改-写，只盖容量：
    // 别的字段不用带，PVC 的 accessModes 必填、storageClassName 不可变，漏带反而会被拒
    await pvcApi.resize(row.name, ctxParams.value, Math.round(parseFloat(num) * 1024 * 1024 * 1024))
    ElMessage.success('已提交扩容 —— 中间态会在「状态」列标出来')
    resizeVisible.value = false
    await refresh()
  } catch {
    /* 拦截器已提示：存储类没开在线扩容、只能往大改，后端那句原话就在里面 */
  } finally {
    resizing.value = false
  }
}

function onRowCommand(cmd: string, row: K8sPvc): void {
  if (cmd === 'resize') openResize(row)
  else if (cmd === 'delete') void onDelete(row)
}

// ---------- 创建对话框 ----------
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
    <PageHeader title="PVC" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="openCreate">创建 PVC</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 PVC"
      description="点击右上「创建 PVC」新建；容量之后可以调大（行操作里的「调整容量」），但 K8s 不支持缩容，要变小只能删了重建。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <StatusBadge :label="row.phase ?? 'Unknown'" :type="phaseType(row.phase)" />
            <!-- 扩容中间态：`phase` 这时还是 Bound，不看条件就分不出"在扩"和"扩完了" -->
            <div v-if="resizeState(row)" class="resize-state">{{ resizeState(row) }}</div>
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
        <!-- ⚠️ 这一列由**后端**算好带下来（判据是 allowVolumeExpansion + provisioner 在不在
             CSIDriver 名单里）—— 前端别自己拿 storageClassName 猜：那个名单它拿不到，
             而只看 allowVolumeExpansion 是假绿灯。`null`（读不到）按"不许扩"处理。 -->
        <el-table-column label="可扩容" width="130">
          <template #default="{ row }">
            <span v-if="row.expansion === 'csi'" class="ok">可以</span>
            <span
              v-else-if="row.expansion === 'unbacked'"
              class="warn"
              title="扩容由 CSI 驱动的 external-resizer 执行，这个存储类的 provisioner 不是 CSI 驱动 —— 改了也只是把「请求」改大，status.capacity 不会动，连条件都不会挂。"
            >非 CSI 驱动</span>
            <span v-else-if="row.expansion === 'unsupported'" class="muted">不支持</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <!-- 与工作负载那几个行内动作同形：一个下拉，别在行里排一排按钮 -->
            <el-dropdown trigger="click" @command="(cmd: string) => onRowCommand(cmd, row)">
              <el-button link type="primary">操作 ▾</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <!-- ⚠️ 三个条件缺一不可：有权限、卷是 Bound、**所在存储类真能扩**。
                       第三个由后端算好（见「可扩容」列）—— 只看存储类上的 allowVolumeExpansion
                       是假绿灯：本集群两个 SC 都写着 true、两个都做不到。
                       禁用而不是隐藏：不显示会让人以为功能没做；旁边那列写着为什么。
                       `null`（读不到存储类）也禁用 —— 与后端同一个 fail-closed 口径。 -->
                  <el-dropdown-item v-if="isManaged(row)" disabled>{{ managedReason(row) }}</el-dropdown-item>
                  <el-dropdown-item
                    command="resize"
                    :disabled="isManaged(row) || !canResize || row.phase !== 'Bound' || row.expansion !== 'csi'"
                  >
                    <span :title="row.expansion === 'csi' ? '' : '这个卷所在的存储类不能在线扩容 —— 见「可扩容」列'">
                      调整容量
                    </span>
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" divided :disabled="isManaged(row)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
          <YamlBlock v-if="yamlText" :yaml="yamlText" />
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
            <YamlBlock v-if="pvYaml" :yaml="pvYaml" />
            <div v-else class="muted">加载中…</div>
          </el-tab-pane>
        </el-tabs>
      </template>
      <div v-else class="muted">未找到与该 PVC 绑定的 PV（可能尚未绑定，或 PV 的 claimRef 不匹配）</div>
    </el-dialog>

    <!-- 调整容量（扩容）。⚠️ K8s **只支持往大改**，没有缩容这条路 —— 对话框里就这么说，
         而不是让用户填完了再被后端拒一次 -->
    <el-dialog v-model="resizeVisible" title="调整容量" width="520px">
      <el-form label-width="100px">
        <el-form-item label="PVC">
          <code>{{ resizeRow?.name }}</code>
        </el-form-item>
        <el-form-item label="当前容量">
          <span class="muted">{{ fmtStorage(resizeRow?.storage) }}</span>
        </el-form-item>
        <el-form-item label="新容量" required>
          <el-input v-model="resizeNum" style="width: 160px">
            <template #append>Gi</template>
          </el-input>
          <div class="form-tip">
            只能填比上面更大的值 —— K8s 不支持缩容，要变小只能删了重建。
          </div>
        </el-form-item>
      </el-form>
      <div class="form-tip resize-note">
        ⚠️ 能不能在线扩容由存储类决定（它的 allowVolumeExpansion）—— 平台在提交时核对，
        没开的话会明确告诉你。扩容是异步的：提交后「状态」列会先显示「正在扩容」，
        文件系统那一半要等 Pod 重启后才完成。
      </div>
      <template #footer>
        <el-button @click="resizeVisible = false">取消</el-button>
        <el-button type="primary" :loading="resizing" @click="submitResize">提交</el-button>
      </template>
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
/* 扩容中间态：用「信息」色而不是警告色 —— 它是正常过程，不是异常（S4.1.3 的原话） */
.resize-state {
  margin-top: 2px;
  font-size: 12px;
  color: var(--accent, #409eff);
}
/* 扩容对话框底下那段说明：贴着底部，别跟表单字段抢注意力 */
.resize-note {
  margin-top: 4px;
  line-height: 1.7;
}
/* 「可扩容」列：绿=真能扩；橙=存储类写着允许但没人执行（最该被看见的一档）；灰=不支持/读不到 */
.ok {
  color: var(--success, #67c23a);
}
.warn {
  color: var(--warning, #e6a23c);
}
</style>
