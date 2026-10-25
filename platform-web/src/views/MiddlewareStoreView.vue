<script setup lang="ts">
/**
 * 中间件商店（批①-1：固定清单 + Redis 实例链）。
 *
 * <p>两块：商店卡片（Operator 未装则禁用）、Operator（管理员安装）。
 * 卡片点「创建实例」→ 手写表单（字段来自 middlewareForms.ts）→ 提交 → 轮询操作记录。
 * **已建实例的列表在独立页面 `/apps/instances`**（原先是本页的一个 tab）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { middlewareApi } from '@/api'
import { usePermission } from '@/stores/permission'
import MiddlewareIcon from '@/components/middleware/MiddlewareIcon.vue'
import MiddlewareConnectionDialog from '@/components/middleware/MiddlewareConnectionDialog.vue'
import { loadMiddlewareClusters } from '@/components/middleware/middlewareClusters'
import { useMiddlewareTenant } from '@/components/middleware/middlewareTenant'
import { MIDDLEWARE_FIELDS, toQuantity } from '@/components/middleware/middlewareForms'
import type { MiddlewareField } from '@/components/middleware/middlewareForms'
import type { K8sCluster, MiddlewareCatalogItem } from '@/types'

const perm = usePermission()
const router = useRouter()
// 租户/集群作用域直接取顶栏（资源上下文）：平台桶的归属、实例计数、按哪个集群查都跟着它
const { tenantId, clusterId: ctxClusterId, setCluster, ensure: ensureTenant } = useMiddlewareTenant()

const tab = ref<'store' | 'operator'>('store')
const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
const catalog = ref<MiddlewareCatalogItem[]>([])
const loading = ref(false)
const operating = ref('')
/** 首轮加载完成前不响应顶栏变化（onMounted 自己会按上下文加载一次） */
const ready = ref(false)

// ---------------- 创建 ----------------
const createVisible = ref(false)
const creating = ref(false)
const createItem = ref<MiddlewareCatalogItem | null>(null)
const form = ref<Record<string, any>>({})
const createMode = ref('')
/** 内存/存储的单位（与 form 里的数字分开存；提交时拼成 quantity） */
const formUnits = ref<Record<string, string>>({})
/** 命名空间与 StorageClass 都由后端按租户/权限算好 */
const formOptions = ref<{ namespaces: string[]; storageClasses: string[]; objectStorageAvailable: boolean }>({
  namespaces: [],
  storageClasses: [],
  objectStorageAvailable: false,
})

const createFields = computed(() => {
  const all = createItem.value ? (MIDDLEWARE_FIELDS[createItem.value.code] ?? []) : []
  if (createItem.value?.code !== 'milvus') return all
  return all.filter((f) => {
    if (f.key === 'localStorageSize') return createMode.value === 'STANDALONE'
    if (f.key === 'storageSize') return form.value.objectStorageType !== 'CUSTOM'
    return true
  })
})

const modeMinReplicas = computed(
  () => createItem.value?.modes.find((m) => m.mode === createMode.value)?.minReplicas ?? 1,
)

/** 单节点/单实例形态：节点数固定，表单只读 */
const modeFixedReplicas = computed(
  () => createItem.value?.modes.find((m) => m.mode === createMode.value)?.fixedReplicas ?? null,
)

/** 节点数在该形态下的含义（主节点数 / 数据节点数 / Broker 数 ...） */
const modeReplicasLabel = computed(
  () => createItem.value?.modes.find((m) => m.mode === createMode.value)?.replicasLabel || '节点数',
)

/** Redis 集群形态才有「每个主节点的副本数」 */
const showReplicasPerShard = computed(
  () => createItem.value?.code === 'redis' && createMode.value === 'CLUSTER',
)

// ---------------- 连接方式 ----------------
// 创建成功后把连接方式就地弹给用户（地址不依赖实例是否已就绪）；实例列表页也有同一入口
const connectionVisible = ref(false)
const connectionId = ref<string | null>(null)

function showConnection(instanceId: string): void {
  connectionId.value = instanceId
  connectionVisible.value = true
}

// Operator 页只列真正需要装 Operator 的条目（Nacos 走 chart，没有 Operator 可装）
const operatorCatalog = computed(() => catalog.value.filter((i) => !i.chartBacked))

const pollTimer = ref<number | null>(null)

async function loadClusters(): Promise<void> {
  clusters.value = await loadMiddlewareClusters(perm.isAdmin)
  // 顶栏已经选了集群就用它（中间件的「当前集群」跟顶栏是同一个值）
  const preferred = ctxClusterId.value
  if (preferred && clusters.value.some((c) => c.clusterId === preferred)) {
    clusterId.value = preferred
    return
  }
  const firstEnabled = clusters.value.find((c) => c.enabled === 1)
  clusterId.value = firstEnabled?.clusterId ?? clusters.value[0]?.clusterId ?? ''
}

/** 页面上的集群下拉只是顶栏的另一个入口：写回上下文，由 watch 统一重查 */
function onClusterChange(id: string): void {
  if (id === ctxClusterId.value) {
    void refresh()
    return
  }
  setCluster(id)
}

async function loadCatalog(): Promise<void> {
  if (!clusterId.value) {
    catalog.value = []
    return
  }
  catalog.value = (await middlewareApi.catalog(clusterId.value, tenantId.value)) ?? []
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    await loadCatalog()
  } finally {
    loading.value = false
  }
}

// ---------------- 创建 ----------------
function resetForm(item: MiddlewareCatalogItem): void {
  createItem.value = item
  createMode.value = item.modes[0]?.mode ?? ''
  const f: Record<string, any> = {
    namespace: '',
    displayName: '',
    storageClass: '',
    // Redis 集群形态：每个主节点配几个副本（默认 1）
    replicasPerShard: 1,
    // Milvus：etcd 节点数与对象存储来源（来源的默认值在 form-options 回来后定）
    etcdReplicas: 1,
    objectStorageType: '',
    objectStorageEndpoint: '',
    objectStorageBucket: '',
    objectStorageAccessKey: '',
    objectStorageSecretKey: '',
    objectStorageUseSsl: false,
  }
  const units: Record<string, string> = {}
  for (const field of MIDDLEWARE_FIELDS[item.code] ?? []) {
    f[field.key] = field.default ?? (field.type === 'number' ? 1 : '')
    if (field.unit === 'size') units[field.key] = field.unitDefault ?? 'Gi'
  }
  // 版本从后端下发的清单里选（清单为空 = 不展示）
  f.version = item.versions?.[0] ?? ''
  // ES 默认不勾 Kibana、默认开 HTTPS、默认不装 IK
  f.kibana = false
  f.tls = true
  f.ik = false
  // 业务指标默认不采：勾选后才装 exporter / 打开指标端点并建 Service + ServiceMonitor
  f.metricsEnabled = false
  form.value = f
  formUnits.value = units
  // 单节点/单实例形态：把节点数直接钉成固定值
  if (item.modes[0]?.fixedReplicas != null) {
    form.value.replicas = item.modes[0].fixedReplicas
  }
}

async function loadFormOptions(): Promise<void> {
  if (!clusterId.value) {
    formOptions.value = { namespaces: [], storageClasses: [], objectStorageAvailable: false }
    return
  }
  try {
    const options = await middlewareApi.formOptions(clusterId.value, tenantId.value)
    formOptions.value = {
      namespaces: options?.namespaces ?? [],
      storageClasses: options?.storageClasses ?? [],
      objectStorageAvailable: options?.objectStorageAvailable ?? false,
    }
    // Milvus：有对象存储组件 + 有租户上下文时默认用平台桶，否则只能自带 S3
    if (createItem.value?.code === 'milvus' && !form.value.objectStorageType) {
      form.value.objectStorageType =
        formOptions.value.objectStorageAvailable && !!tenantId.value ? 'PLATFORM' : 'CUSTOM'
    }
  } catch {
    formOptions.value = { namespaces: [], storageClasses: [], objectStorageAvailable: false }
  }
}

function openCreate(item: MiddlewareCatalogItem): void {
  resetForm(item)
  void loadFormOptions()
  createVisible.value = true
}

/** size 字段的最小值随单位变：Mi 至少 16，Gi 至少 1（不换算就是这个数） */
function unitMin(field: MiddlewareField): number {
  if (field.unit !== 'size') return field.min ?? 0
  return (formUnits.value[field.key] ?? field.unitDefault ?? 'Gi') === 'Mi' ? 16 : 1
}

/** 切换单位时换算数值：Gi → Mi ×1024，Mi → Gi ÷1024，避免「128Mi」切成「128Gi」 */
function changeUnit(field: MiddlewareField, next: 'Mi' | 'Gi'): void {
  const prev = (formUnits.value[field.key] ?? field.unitDefault ?? 'Gi') as 'Mi' | 'Gi'
  if (prev === next) return
  const cur = Number(form.value[field.key] ?? 0)
  if (Number.isFinite(cur) && cur > 0) {
    // 只保留整数值：Gi → Mi 直接 ×1024；Mi → Gi 向上取整（512Mi → 1Gi），避免出现 0.5Gi 这类小数
    form.value[field.key] = next === 'Mi'
        ? Math.max(16, Math.round(cur * 1024))
        : Math.max(1, Math.ceil(cur / 1024))
  }
  formUnits.value[field.key] = next
}

function onModeChange(): void {
  // Milvus：etcd 节点数跟形态走（单节点 1 / 集群 3），用户仍可在 1~3 内改
  if (createItem.value?.code === 'milvus') {
    form.value.etcdReplicas = createMode.value === 'CLUSTER' ? 3 : 1
  }
  // 固定节点数的形态直接钉死；可变形态不满足下限时抬到下限
  if (modeFixedReplicas.value !== null) {
    form.value.replicas = modeFixedReplicas.value
    return
  }
  const min = modeMinReplicas.value
  if (typeof form.value.replicas !== 'number' || form.value.replicas < min) {
    form.value.replicas = min
  }
}

async function submitCreate(): Promise<void> {
  if (!createItem.value || !clusterId.value) return
  if (creating.value) return   // 防连点：按钮 loading 生效前的那一下也不能再发一次
  if (!form.value.namespace) {
    ElMessage.error('请选择命名空间')
    return
  }
  const min = modeMinReplicas.value
  if (typeof form.value.replicas === 'number' && form.value.replicas < min) {
    ElMessage.error(`该形态至少 ${min} 个节点`)
    return
  }
  creating.value = true
  try {
    if (createItem.value.code === 'milvus' && form.value.objectStorageType === 'PLATFORM' && !tenantId.value) {
      ElMessage.error('平台对象存储需要租户上下文：请先在顶栏选择租户，或改用「自带 S3」')
      return
    }
    const fields: MiddlewareField[] = createFields.value
    const payload: Record<string, any> = {
      clusterId: clusterId.value,
      code: createItem.value.code,
      mode: createMode.value,
      namespace: form.value.namespace,
      displayName: form.value.displayName?.trim() || undefined,
      version: form.value.version || undefined,
      storageClass: form.value.storageClass || undefined,
      kibana: createItem.value.code === 'elasticsearch' ? !!form.value.kibana : undefined,
      tls: createItem.value.code === 'elasticsearch' ? !!form.value.tls : undefined,
      ik: createItem.value.code === 'elasticsearch' ? !!form.value.ik : undefined,
      metricsEnabled: !!form.value.metricsEnabled,
      // 顶栏的租户（代管轨）：实例归属与平台桶归属都靠它；租户用户后端会以 token 为准
      tenantId: tenantId.value,
      replicasPerShard: createItem.value.code === 'redis' && createMode.value === 'CLUSTER'
        ? (form.value.replicasPerShard ?? 1)
        : undefined,
    }
    for (const field of fields) {
      payload[field.key] = toQuantity(field, form.value[field.key], formUnits.value[field.key])
    }
    if (createItem.value.code === 'milvus') {
      payload.etcdReplicas = form.value.etcdReplicas
      payload.objectStorageType = form.value.objectStorageType
      if (form.value.objectStorageType === 'CUSTOM') {
        payload.objectStorageEndpoint = form.value.objectStorageEndpoint?.trim() || undefined
        payload.objectStorageBucket = form.value.objectStorageBucket?.trim() || undefined
        payload.objectStorageAccessKey = form.value.objectStorageAccessKey?.trim() || undefined
        payload.objectStorageSecretKey = form.value.objectStorageSecretKey || undefined
        payload.objectStorageUseSsl = !!form.value.objectStorageUseSsl
      }
    }
    const opId = await middlewareApi.create(payload as any)
    createVisible.value = false
    ElMessage.success('已提交创建')
    poll(opId)
    // 创建成功后把连接方式弹给用户（地址不依赖实例是否已就绪）
    try {
      const op = await middlewareApi.operation(opId)
      if (op?.instanceId) void showConnection(op.instanceId)
    } catch {
      // 拿不到实例 id 就不弹，实例列表里还有「连接」入口
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}

// ---------------- Operator ----------------
async function installOperator(item: MiddlewareCatalogItem): Promise<void> {
  if (!clusterId.value) return
  await ElMessageBox.confirm(
    `在集群上安装 ${item.name} Operator？\n只有管理员可操作；安装后租户才能创建对应实例。`,
    '安装 Operator',
    { type: 'warning', confirmButtonText: '安装', cancelButtonText: '取消' },
  )
  operating.value = item.code
  try {
    const status = await middlewareApi.operatorInstall(clusterId.value, item.code)
    ElMessage.success(status.ready ? 'Operator 已就绪' : (status.message ?? '安装已提交'))
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '安装失败')
  } finally {
    operating.value = ''
  }
}

// ---------------- 轮询 ----------------
function poll(operationId: number): void {
  if (pollTimer.value !== null) {
    window.clearTimeout(pollTimer.value)
  }
  let tries = 0
  const tick = async (): Promise<void> => {
    tries += 1
    try {
      const op = await middlewareApi.operation(operationId)
      if (op.status === 'SUCCESS') {
        pollTimer.value = null
        ElMessage.success('操作成功')
        await refresh()
        return
      }
      if (op.status === 'FAILED' || op.status === 'UNKNOWN') {
        pollTimer.value = null
        ElMessage.error(op.message ?? '操作失败')
        await refresh()
        return
      }
    } catch {
      // 网络抖动不终止
    }
    if (tries >= 150) {
      pollTimer.value = null
      ElMessage.warning('操作仍在进行，请稍后刷新查看')
      return
    }
    pollTimer.value = window.setTimeout(tick, 2000)
  }
  void tick()
}

onMounted(async () => {
  // 先等顶栏的租户上下文（ensureDefaults 会落到第一个可用租户），再按它查目录
  await ensureTenant()
  await loadClusters()
  await refresh()
  ready.value = true
})

// 顶栏切租户或集群：目录计数、表单可选项都要跟着换（ready 之前由 onMounted 负责首轮）
watch([tenantId, ctxClusterId], async ([, cid]) => {
  if (!ready.value) return
  if (cid && clusters.value.some((c) => c.clusterId === cid)) {
    clusterId.value = cid
  }
  await refresh()
  if (createVisible.value) void loadFormOptions()
})

onBeforeUnmount(() => {
  if (pollTimer.value !== null) {
    window.clearTimeout(pollTimer.value)
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">中间件</h2>
        <p class="ph-desc">固定清单：Operator 中间件由平台维护，实例由管理员与租户创建</p>
      </div>
      <div class="ph-actions">
        <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px" @change="onClusterChange">
          <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
        </el-select>
        <el-button :disabled="!clusterId" @click="refresh">刷新</el-button>
        <el-button @click="router.push('/apps/instances')">实例列表</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" class="mw-tabs">
      <!-- ============ 商店 ============ -->
      <el-tab-pane label="商店" name="store">
        <div class="mw-grid">
          <div v-for="item in catalog" :key="item.code" class="mw-card">
            <div class="mw-card-head">
              <MiddlewareIcon :name="item.name" :chart-name="item.code" />
              <div class="mw-card-title">
                <div class="mw-card-name">{{ item.name }}</div>
                <div class="mw-card-sub">{{ item.code }}</div>
              </div>
              <el-tag v-if="item.chartBacked" size="small" type="info" effect="plain">无需 Operator</el-tag>
              <el-tag v-else-if="item.operatorReady" size="small" type="success" effect="plain">可用</el-tag>
              <el-tag v-else-if="item.operatorInstalled" size="small" type="warning" effect="plain">Operator 异常</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">未装 Operator</el-tag>
            </div>
            <div class="mw-card-desc">{{ item.description || '暂无描述' }}</div>
            <div class="mw-card-meta">
              <span v-for="m in item.modes" :key="m.mode" class="mw-mode-chip">{{ m.label }}</span>
              <span v-if="!item.modes.length" class="mw-mode-chip is-muted">即将支持</span>
              <span class="mw-count" v-if="item.instanceCount">已有 {{ item.instanceCount }} 个实例</span>
            </div>
            <div class="mw-card-foot">
              <template v-if="!item.modes.length">
                <span class="mw-hint">该中间件将在后续批次开放</span>
              </template>
              <template v-else>
                <el-button
                  v-if="perm.isAdmin && !item.chartBacked && !item.operatorReady"
                  size="small"
                  :loading="operating === item.code"
                  @click="installOperator(item)"
                >安装 Operator</el-button>
                <el-button
                  v-else
                  type="primary"
                  size="small"
                  :disabled="!clusterId || (!item.chartBacked && !item.operatorReady)"
                  @click="openCreate(item)"
                >创建实例</el-button>
                <span v-if="!item.chartBacked && !item.operatorReady" class="mw-hint">
                  需要管理员先安装 {{ item.name }} Operator
                </span>
              </template>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ Operator（管理员） ============ -->
      <el-tab-pane v-if="perm.isAdmin" label="Operator" name="operator">
        <!-- chart 安装的条目（Nacos）没有 Operator：留在表里会出现一个点了必然失败的「安装」按钮 -->
        <el-table :data="operatorCatalog" class="panel" empty-text="没有可安装的 Operator">
          <el-table-column prop="name" label="中间件" width="160" />
          <el-table-column prop="code" label="code" width="140" />
          <el-table-column label="状态" width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="row.operatorReady ? 'success' : row.operatorInstalled ? 'warning' : 'info'">
                {{ row.operatorReady ? '就绪' : row.operatorInstalled ? '异常' : '未安装' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="chart 版本" width="170">
            <template #default="{ row }">
              <span>{{ row.installedChartVersion || '—' }}</span>
              <el-tag v-if="row.upgradeAvailable" size="small" type="warning" style="margin-left: 6px">
                可升到 {{ row.chartVersion }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operatorMessage" label="说明" min-width="200" />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.modes.length"
                link
                type="primary"
                :loading="operating === row.code"
                @click="installOperator(row)"
              >{{ !row.operatorInstalled ? '安装' : row.upgradeAvailable ? '升级' : '重装' }}</el-button>
              <span v-else class="mw-hint">即将支持</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 创建实例 ============ -->
    <el-dialog
      v-model="createVisible"
      :title="createItem ? `创建 ${createItem.name} 实例` : '创建实例'"
      width="960px"
      :close-on-click-modal="false"
    >
      <el-form class="mw-create-form" label-width="150px" label-position="left">
        <el-form-item label="形态">
          <div class="mw-mode-wrap">
            <el-radio-group v-model="createMode" @change="onModeChange">
              <el-radio v-for="m in createItem?.modes ?? []" :key="m.mode" :value="m.mode">
                {{ m.label }}
              </el-radio>
            </el-radio-group>
            <div v-if="createItem?.modes.find((m) => m.mode === createMode)?.help" class="mw-form-help">
              {{ createItem?.modes.find((m) => m.mode === createMode)?.help }}
            </div>
          </div>
        </el-form-item>
        <el-form-item label="命名空间" required>
          <el-select v-model="form.namespace" filterable placeholder="选择本租户已分配的命名空间" style="width: 100%">
            <el-option v-for="ns in formOptions.namespaces" :key="ns" :label="ns" :value="ns" />
          </el-select>
          <div v-if="!formOptions.namespaces.length" class="mw-form-help">
            该租户在当前集群还没有分配命名空间，请先在「命名空间管理」里分配
          </div>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" spellcheck="false" placeholder="默认按类型 + 短码生成" />
        </el-form-item>
        <el-form-item v-if="createItem?.versions?.length" label="版本">
          <el-select v-model="form.version" style="width: 100%">
            <el-option v-for="v in createItem.versions" :key="v" :label="v" :value="v" />
          </el-select>
          <div class="mw-form-help">只能从平台支持的版本里选</div>
        </el-form-item>
        <el-form-item
          v-for="field in createFields"
          :key="field.key"
          :label="field.key === 'replicas' ? modeReplicasLabel : field.label"
        >
          <div class="mw-resource-row">
            <el-input-number
              v-model="form[field.key]"
              :disabled="field.key === 'replicas' && modeFixedReplicas !== null"
              :min="field.key === 'replicas' ? (modeFixedReplicas ?? modeMinReplicas) : unitMin(field)"
              :max="field.max ?? 9999"
              :step="field.step ?? 1"
              :precision="field.key === 'replicas' || field.unit === 'size' ? 0 : 2"
              controls-position="right"
            />
            <span v-if="field.unit === 'cpu'" class="mw-unit">核</span>
            <el-select
              v-else-if="field.unit === 'size'"
              :model-value="formUnits[field.key]"
              class="mw-unit-select"
              @update:model-value="(u: string) => changeUnit(field, u as 'Mi' | 'Gi')"
            >
              <el-option label="Mi" value="Mi" />
              <el-option label="Gi" value="Gi" />
            </el-select>
          </div>
          <div v-if="field.key === 'replicas' && modeFixedReplicas !== null" class="mw-form-help">
            {{ modeReplicasLabel }}固定为 {{ modeFixedReplicas }}，不可调整
          </div>
          <div v-if="field.help" class="mw-form-help">{{ field.help }}</div>
        </el-form-item>
        <el-form-item v-if="showReplicasPerShard" label="每个主节点的副本数">
          <el-input-number v-model="form.replicasPerShard" :min="0" :max="5" />
          <div class="mw-form-help">
            默认 1：每个主节点再配 1 个副本做备份；填 0 = 不配备份（主节点挂了数据就没了）
          </div>
        </el-form-item>
        <el-form-item label="StorageClass">
          <el-select v-model="form.storageClass" style="width: 100%" clearable placeholder="集群默认">
            <el-option v-for="sc in formOptions.storageClasses" :key="sc" :label="sc" :value="sc" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务指标">
          <el-switch v-model="form.metricsEnabled" active-text="采集该中间件的业务指标" />
          <div class="mw-form-help">
            关闭时不装 exporter、不建 ServiceMonitor；资源用量（CPU/内存/网络/磁盘）不受影响。
            Redis/ES/MySQL 会多一个 exporter 边车容器
          </div>
        </el-form-item>
        <el-form-item v-if="createItem?.code === 'elasticsearch'" label="HTTPS">
          <el-switch v-model="form.tls" active-text="启用 HTTPS（自签证书）" />
          <div class="mw-form-help">
            关闭后 Elasticsearch 与 Kibana 都走明文 http，仅建议内网测试使用
          </div>
        </el-form-item>
        <el-form-item v-if="createItem?.code === 'elasticsearch'" label="IK 分词器">
          <el-switch v-model="form.ik" active-text="安装 IK 中文分词器" />
          <div class="mw-form-help">
            创建时用 initContainer 下载 analysis-ik（版本与 ES 一致）；集群 Pod 需要能访问 get.infini.cloud
          </div>
        </el-form-item>
        <el-form-item v-if="createItem?.code === 'elasticsearch'" label="Kibana">
          <el-switch v-model="form.kibana" active-text="同时部署 Kibana" />
          <div class="mw-form-help">（勾选后平台会额外创建一个 Kibana 实例，指向当前 ES）</div>
        </el-form-item>
        <template v-if="createItem?.code === 'milvus'">
          <el-form-item label="etcd 节点数">
            <el-input-number v-model="form.etcdReplicas" :min="1" :max="3" />
            <div class="mw-form-help">1~3；单节点默认 1、集群默认 3；创建后不可修改</div>
          </el-form-item>
          <el-form-item label="对象存储来源" required>
            <el-radio-group v-model="form.objectStorageType">
              <el-radio value="PLATFORM" :disabled="!formOptions.objectStorageAvailable || !tenantId">平台对象存储</el-radio>
              <el-radio value="CUSTOM">自带 S3</el-radio>
            </el-radio-group>
            <div v-if="!formOptions.objectStorageAvailable" class="mw-form-help">
              本集群未安装对象存储组件，只能用「自带 S3」
            </div>
            <div v-else-if="!tenantId" class="mw-form-help">
              平台桶必须归属某个租户：请先在顶栏选择租户，或改用「自带 S3」
            </div>
          </el-form-item>
          <template v-if="form.objectStorageType === 'CUSTOM'">
            <el-form-item label="S3 Endpoint" required>
              <el-input v-model="form.objectStorageEndpoint" spellcheck="false"
                        placeholder="s3.example.com:9000（http:// 前缀可有可无）" />
            </el-form-item>
            <el-form-item label="桶名" required>
              <el-input v-model="form.objectStorageBucket" spellcheck="false" placeholder="milvus-data" />
            </el-form-item>
            <el-form-item label="AccessKey" required>
              <el-input v-model="form.objectStorageAccessKey" spellcheck="false" autocomplete="off" />
            </el-form-item>
            <el-form-item label="SecretKey" required>
              <el-input v-model="form.objectStorageSecretKey" type="password" show-password
                        autocomplete="new-password" />
            </el-form-item>
            <el-form-item label="HTTPS">
              <el-switch v-model="form.objectStorageUseSsl" active-text="S3 使用 TLS" />
            </el-form-item>
          </template>
        </template>
      </el-form>
      <p class="mw-form-note">
        <template v-if="createItem?.code === 'redis'">
          初始密码由平台生成并写入同命名空间的 Secret（&lt;名称&gt;-credentials）。
        </template>
        <template v-else-if="createItem?.code === 'nacos'">
          Nacos 的鉴权由 chart 控制，默认未开启；对外暴露前请自行配置鉴权。
        </template>
        <template v-else-if="createItem?.code === 'kafka'">
          Kafka 监听器为集群内 plain（9092），未开启认证，没有初始凭据。
        </template>
        <template v-else-if="createItem?.code === 'milvus'">
          Milvus 默认未开启鉴权；对象存储的 AK/SK 会写进同命名空间的 Secret（&lt;名称&gt;-milvus-storage）。
        </template>
        <template v-else>
          凭据由对应 Operator 生成，可在实例命名空间查看（MySQL：&lt;名称&gt;-secrets；Elasticsearch：&lt;名称&gt;-es-elastic-user；PostgreSQL：&lt;名称&gt;-app；RabbitMQ：&lt;名称&gt;-default-user）。
        </template>
      </p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============ 连接方式（创建成功后自动弹） ============ -->
    <MiddlewareConnectionDialog v-model="connectionVisible" :instance-id="connectionId" />
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.ph-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--text-1); }
.ph-desc { margin: 4px 0 0; font-size: 12.5px; color: var(--text-3); }
.ph-actions { display: flex; gap: 10px; align-items: center; }
.mw-tabs { margin-top: -4px; }
.mw-resource-row { display: flex; align-items: center; gap: 8px; }
/* 表单统一左对齐：label 顶格、控件从同一列开始 */
.mw-create-form :deep(.el-form-item__label) { justify-content: flex-start; text-align: left; }
.mw-create-form :deep(.el-form-item__content) { justify-content: flex-start; flex-wrap: wrap; }
/* 说明文字单独占一行，别和输入框挤在同一行 */
.mw-create-form :deep(.el-form-item__content) > .mw-form-help { flex-basis: 100%; }
.mw-create-form :deep(.el-input-number) { width: 160px; }
.mw-create-form :deep(.el-form-item__content > .el-input) { width: 100%; }
.mw-unit { color: var(--text-3); font-size: 12.5px; }
.mw-unit-select { width: 80px; }

.mw-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(288px, 1fr));
  gap: 14px;
  padding-bottom: 8px;
}
.mw-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--panel);
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
}
.mw-card:hover {
  border-color: var(--border-strong);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.08);
  transform: translateY(-1px);
}
.mw-card-head { display: flex; align-items: center; gap: 12px; min-width: 0; }
.mw-card-title { min-width: 0; flex: 1; }
.mw-card-name { font-size: 14.5px; font-weight: 600; color: var(--text-1); }
.mw-card-sub { margin-top: 2px; font-size: 12px; color: var(--text-3); font-family: ui-monospace, monospace; }
.mw-card-desc { font-size: 12.5px; color: var(--text-2); line-height: 1.55; min-height: 39px; }
.mw-card-meta { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.mw-mode-chip {
  font-size: 11.5px;
  padding: 1px 8px;
  border-radius: 10px;
  background: var(--bg-2, rgba(255, 255, 255, 0.06));
  color: var(--text-2);
}
.mw-mode-chip.is-muted { color: var(--text-3); }
.mw-count { font-size: 11.5px; color: var(--text-3); }
.mw-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}
.mw-hint { font-size: 12px; color: var(--text-3); }
.mw-form-help { margin-top: 4px; font-size: 12px; color: var(--text-3); line-height: 1.5; }
.mw-mode-wrap { display: flex; flex-direction: column; gap: 2px; }
.mw-form-note { margin: 4px 0 0; font-size: 12px; color: var(--text-3); line-height: 1.6; }
</style>
