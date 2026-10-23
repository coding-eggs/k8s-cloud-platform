<script setup lang="ts">
/**
 * 中间件商店（批①-1：固定清单 + Redis 实例链）。
 *
 * <p>三块：商店卡片（Operator 未装则禁用）、实例列表（统一展示）、Operator（管理员安装）。
 * 卡片点「创建实例」→ 手写表单（字段来自 middlewareForms.ts）→ 提交 → 轮询操作记录。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, middlewareApi, resourceContextApi } from '@/api'
import { usePermission } from '@/stores/permission'
import MiddlewareIcon from '@/components/middleware/MiddlewareIcon.vue'
import { MIDDLEWARE_FIELDS, toQuantity } from '@/components/middleware/middlewareForms'
import type { MiddlewareField } from '@/components/middleware/middlewareForms'
import type { K8sCluster, MiddlewareCatalogItem, MiddlewareConnection, MiddlewareInstance } from '@/types'

const perm = usePermission()

const tab = ref<'store' | 'instances' | 'operator'>('store')
const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
const catalog = ref<MiddlewareCatalogItem[]>([])
const instances = ref<MiddlewareInstance[]>([])
const loading = ref(false)
const operating = ref('')

// ---------------- 创建 ----------------
const createVisible = ref(false)
const creating = ref(false)
const createItem = ref<MiddlewareCatalogItem | null>(null)
const form = ref<Record<string, any>>({})
const createMode = ref('')
/** 内存/存储的单位（与 form 里的数字分开存；提交时拼成 quantity） */
const formUnits = ref<Record<string, string>>({})
/** 命名空间与 StorageClass 都由后端按租户/权限算好 */
const formOptions = ref<{ namespaces: string[]; storageClasses: string[] }>({
  namespaces: [],
  storageClasses: [],
})

const createFields = computed(() =>
  createItem.value ? (MIDDLEWARE_FIELDS[createItem.value.code] ?? []) : [],
)

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
const connectionVisible = ref(false)
const connection = ref<MiddlewareConnection | null>(null)

async function showConnection(instanceId: string): Promise<void> {
  try {
    connection.value = await middlewareApi.connection(instanceId)
    connectionVisible.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '获取连接方式失败')
  }
}

// Operator 页只列真正需要装 Operator 的条目（Nacos 走 chart，没有 Operator 可装）
const operatorCatalog = computed(() => catalog.value.filter((i) => !i.chartBacked))

// ---------------- 删除 ----------------
const deleteVisible = ref(false)
const deleting = ref(false)
const deleteTarget = ref<MiddlewareInstance | null>(null)
const keepPvc = ref(true)

const pollTimer = ref<number | null>(null)

const STATUS_TAG: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  CREATING: { label: '创建中', type: 'warning' },
  READY: { label: '运行中', type: 'success' },
  DEGRADED: { label: '降级', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' },
  DELETING: { label: '删除中', type: 'info' },
}

function nameOf(code: string): string {
  return catalog.value.find((c) => c.code === code)?.name ?? code
}

async function loadClusters(): Promise<void> {
  if (perm.isAdmin) {
    clusters.value = (await clusterApi.list()) ?? []
  } else {
    // 租户没有 platform:cluster:manage（/cluster/list 会 403）：从 /context 的分配表取集群
    const ctx = await resourceContextApi.get()
    const seen = new Map<string, K8sCluster>()
    for (const t of ctx?.tenants ?? []) {
      for (const c of t.clusters ?? []) {
        if (!seen.has(c.clusterId)) {
          seen.set(c.clusterId, { clusterId: c.clusterId, clusterName: c.clusterName } as unknown as K8sCluster)
        }
      }
    }
    clusters.value = [...seen.values()]
  }
  const firstEnabled = clusters.value.find((c) => c.enabled === 1)
  clusterId.value = firstEnabled?.clusterId ?? clusters.value[0]?.clusterId ?? ''
}

async function loadCatalog(): Promise<void> {
  if (!clusterId.value) {
    catalog.value = []
    return
  }
  catalog.value = (await middlewareApi.catalog(clusterId.value)) ?? []
}

async function loadInstances(): Promise<void> {
  if (!clusterId.value) {
    instances.value = []
    return
  }
  instances.value = (await middlewareApi.instanceList(clusterId.value)) ?? []
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    await Promise.all([loadCatalog(), loadInstances()])
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
  }
  const units: Record<string, string> = {}
  for (const field of MIDDLEWARE_FIELDS[item.code] ?? []) {
    f[field.key] = field.default ?? (field.type === 'number' ? 1 : '')
    if (field.unit === 'size') units[field.key] = field.unitDefault ?? 'Gi'
  }
  // 版本从后端下发的清单里选（清单为空 = 不展示）
  f.version = item.versions?.[0] ?? ''
  // ES 默认不勾 Kibana
  f.kibana = false
  form.value = f
  formUnits.value = units
  // 单节点/单实例形态：把节点数直接钉成固定值
  if (item.modes[0]?.fixedReplicas != null) {
    form.value.replicas = item.modes[0].fixedReplicas
  }
}

async function loadFormOptions(): Promise<void> {
  if (!clusterId.value) {
    formOptions.value = { namespaces: [], storageClasses: [] }
    return
  }
  try {
    const options = await middlewareApi.formOptions(clusterId.value)
    formOptions.value = {
      namespaces: options?.namespaces ?? [],
      storageClasses: options?.storageClasses ?? [],
    }
  } catch {
    formOptions.value = { namespaces: [], storageClasses: [] }
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
    const fields: MiddlewareField[] = MIDDLEWARE_FIELDS[createItem.value.code] ?? []
    const payload: Record<string, any> = {
      clusterId: clusterId.value,
      code: createItem.value.code,
      mode: createMode.value,
      namespace: form.value.namespace,
      displayName: form.value.displayName?.trim() || undefined,
      version: form.value.version || undefined,
      storageClass: form.value.storageClass || undefined,
      kibana: createItem.value.code === 'elasticsearch' ? !!form.value.kibana : undefined,
      replicasPerShard: createItem.value.code === 'redis' && createMode.value === 'CLUSTER'
        ? (form.value.replicasPerShard ?? 1)
        : undefined,
    }
    for (const field of fields) {
      payload[field.key] = toQuantity(field, form.value[field.key], formUnits.value[field.key])
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

// ---------------- 删除 ----------------
function openDelete(row: MiddlewareInstance): void {
  deleteTarget.value = row
  keepPvc.value = true
  deleteVisible.value = true
}

async function submitDelete(): Promise<void> {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    const opId = await middlewareApi.remove({
      instanceId: deleteTarget.value.id,
      keepPvc: keepPvc.value,
    })
    deleteVisible.value = false
    ElMessage.success('已提交删除')
    poll(opId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    deleting.value = false
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
  await loadClusters()
  await refresh()
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
        <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px" @change="refresh">
          <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
        </el-select>
        <el-button :disabled="!clusterId" @click="refresh">刷新</el-button>
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

      <!-- ============ 实例 ============ -->
      <el-tab-pane label="实例" name="instances">
        <el-table :data="instances" class="panel" empty-text="这个集群还没有中间件实例">
          <el-table-column label="类型" width="140">
            <template #default="{ row }">{{ nameOf(row.code) }}</template>
          </el-table-column>
          <el-table-column prop="displayName" label="名称" min-width="160" />
          <el-table-column prop="namespace" label="命名空间" width="140" />
          <el-table-column prop="mode" label="形态" width="120" />
          <el-table-column prop="version" label="版本" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="STATUS_TAG[row.status]?.type ?? 'info'">
                {{ STATUS_TAG[row.status]?.label ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="170" />
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="showConnection(row.id)">连接</el-button>
              <el-button link type="danger" @click="openDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
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
          <el-table-column prop="chartVersion" label="chart 版本" width="130" />
          <el-table-column prop="operatorMessage" label="说明" min-width="200" />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.modes.length"
                link
                type="primary"
                :loading="operating === row.code"
                @click="installOperator(row)"
              >{{ row.operatorInstalled ? '升级' : '安装' }}</el-button>
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
        <el-form-item v-if="createItem?.code === 'elasticsearch'" label="Kibana">
          <el-switch v-model="form.kibana" active-text="同时部署 Kibana" />
          <div class="mw-form-help">（勾选后平台会额外创建一个 Kibana 实例，指向当前 ES）</div>
        </el-form-item>
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
        <template v-else>
          凭据由对应 Operator 生成，可在实例命名空间查看（MySQL：&lt;名称&gt;-secrets；Elasticsearch：&lt;名称&gt;-es-elastic-user；PostgreSQL：&lt;名称&gt;-app；RabbitMQ：&lt;名称&gt;-default-user）。
        </template>
      </p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============ 连接方式 ============ -->
    <el-dialog v-model="connectionVisible" title="连接方式" width="680px">
      <p class="mw-conn-title">{{ connection?.namespace }} / {{ connection?.instanceName }}</p>
      <div class="mw-conn-block">
        <div class="mw-conn-label">访问地址（集群内）</div>
        <div v-for="l in connection?.endpoints ?? []" :key="l.label" class="mw-conn-line">
          <span class="mw-conn-key">{{ l.label }}</span>
          <code class="mw-conn-value">{{ l.value }}</code>
        </div>
      </div>
      <div v-if="connection?.credentials?.length" class="mw-conn-block">
        <div class="mw-conn-label">凭据</div>
        <div v-for="l in connection?.credentials ?? []" :key="l.label" class="mw-conn-line">
          <span class="mw-conn-key">{{ l.label }}</span>
          <span class="mw-conn-value">{{ l.value }}</span>
        </div>
      </div>
      <p class="mw-form-note">{{ connection?.note }}</p>
      <template #footer>
        <el-button type="primary" @click="connectionVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- ============ 删除 ============ -->
    <el-dialog v-model="deleteVisible" title="删除实例" width="480px" :close-on-click-modal="false">
      <p class="mw-delete-text">
        删除「{{ deleteTarget?.displayName }}」（{{ deleteTarget?.namespace }}/{{ deleteTarget?.name }}）？
      </p>
      <el-checkbox v-model="keepPvc">保留数据卷（PVC 不删除，数据可留待恢复）</el-checkbox>
      <template #footer>
        <el-button @click="deleteVisible = false">取消</el-button>
        <el-button type="danger" :loading="deleting" @click="submitDelete">删除</el-button>
      </template>
    </el-dialog>
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
.mw-conn-title { margin: 0 0 12px; color: var(--text-2); font-size: 13px; }
.mw-conn-block { margin-bottom: 14px; }
.mw-conn-label { font-size: 12.5px; color: var(--text-3); margin-bottom: 6px; }
.mw-conn-line { display: flex; gap: 10px; padding: 4px 0; font-size: 13px; }
.mw-conn-key { color: var(--text-3); min-width: 120px; }
.mw-conn-value { color: var(--text-1); word-break: break-all; }

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
.mw-delete-text { margin: 0 0 12px; font-size: 13px; color: var(--text-1); }
</style>
