<script setup lang="ts">
/**
 * 中间件实例列表（独立页面）。
 *
 * <p>与「中间件商店」分家：商店只管清单、创建与 Operator 安装，本页只管**已建实例**的
 * 查看 / 编辑 / 连接 / 删除。原先它是商店页里的一个 tab，实例一多就要在两个 tab 之间来回跳。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { middlewareApi } from '@/api'
import { usePermission } from '@/stores/permission'
import MiddlewareConnectionDialog from '@/components/middleware/MiddlewareConnectionDialog.vue'
import { loadMiddlewareClusters } from '@/components/middleware/middlewareClusters'
import { useMiddlewareTenant } from '@/components/middleware/middlewareTenant'
import type { K8sCluster, MiddlewareCatalogItem, MiddlewareInstance } from '@/types'

const perm = usePermission()
const router = useRouter()
// 租户/集群作用域直接取顶栏（资源上下文）：列表可见范围、代操作身份、按哪个集群/命名空间查都跟着它
const { tenantId, clusterId: ctxClusterId, namespace: ctxNamespace, namespaces,
  ensure: ensureTenant } = useMiddlewareTenant()

const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
const catalog = ref<MiddlewareCatalogItem[]>([])
const instances = ref<MiddlewareInstance[]>([])
/** 类型过滤（空 = 全部）：按类型查，避免一次把所有类型的实时状态都拉一遍 */
const code = ref('')
/** 命名空间过滤（空 = 全部）：跟随顶栏级联的命名空间层，也可在本页放宽 */
const namespaceFilter = ref('')
const loading = ref(false)
/** 首轮加载完成前不响应顶栏变化（onMounted 自己会按上下文加载一次） */
const ready = ref(false)

const STATUS_TAG: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  CREATING: { label: '创建中', type: 'warning' },
  READY: { label: '运行中', type: 'success' },
  DEGRADED: { label: '降级', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' },
  DELETING: { label: '删除中', type: 'info' },
}

function nameOf(itemCode: string): string {
  return catalog.value.find((c) => c.code === itemCode)?.name ?? itemCode
}

// ---------------- 列表 ----------------
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

/** 目录只用来把 code 翻成名字、以及「类型」下拉的选项，跟着集群走 */
async function loadCatalog(): Promise<void> {
  if (!clusterId.value) {
    catalog.value = []
    return
  }
  catalog.value = (await middlewareApi.catalog(clusterId.value, tenantId.value)) ?? []
}

async function loadInstances(): Promise<void> {
  if (!clusterId.value) {
    instances.value = []
    return
  }
  instances.value = (await middlewareApi.instanceList(
    clusterId.value, code.value || undefined, tenantId.value,
    namespaceFilter.value || undefined)) ?? []
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    await Promise.all([loadCatalog(), loadInstances()])
  } catch {
    // 切租户/集群后请求可能因归属或权限失败（http 层已弹提示）：
    // 别把上一个视角的行留在屏幕上 —— 那既是「没刷新」的错觉，也是跨租户的数据残留
    instances.value = []
    catalog.value = []
  } finally {
    loading.value = false
  }
}

// ---------------- 连接 ----------------
const connectionVisible = ref(false)
const connectionId = ref<string | null>(null)

function showConnection(row: MiddlewareInstance): void {
  connectionId.value = row.id
  connectionVisible.value = true
}

// ---------------- 删除 ----------------
const deleteVisible = ref(false)
const deleting = ref(false)
const deleteTarget = ref<MiddlewareInstance | null>(null)
const keepPvc = ref(true)

function openDelete(row: MiddlewareInstance): void {
  deleteTarget.value = row
  keepPvc.value = true
  deleteVisible.value = true
}

const pollTimer = ref<number | null>(null)

async function submitDelete(): Promise<void> {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    const opId = await middlewareApi.remove({
      instanceId: deleteTarget.value.id,
      keepPvc: keepPvc.value,
      tenantId: tenantId.value,
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

/** 删除是异步操作：轮询到终态再刷新列表，期间状态列显示「删除中」由后端台账给出 */
function poll(operationId: number): void {
  if (pollTimer.value !== null) window.clearTimeout(pollTimer.value)
  let tries = 0
  const tick = async (): Promise<void> => {
    tries += 1
    try {
      const op = await middlewareApi.operation(operationId)
      if (op.status === 'SUCCESS') {
        pollTimer.value = null
        ElMessage.success('删除成功')
        await refresh()
        return
      }
      if (op.status === 'FAILED' || op.status === 'UNKNOWN') {
        pollTimer.value = null
        ElMessage.error(op.message ?? '删除失败')
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
  // 先等顶栏的租户上下文（ensureDefaults 会落到第一个可用租户），再按它查列表
  await ensureTenant()
  namespaceFilter.value = ctxNamespace.value ?? ''
  await loadClusters()
  await refresh()
  ready.value = true
})

// 顶栏切租户/集群/命名空间：先清掉旧视角的数据，再按新上下文重查。
// ready 之前不响应：首轮 onMounted 自己会加载，否则 watch 会在集群列表还没到手时白查一次
watch([tenantId, ctxClusterId, ctxNamespace], async ([, cid, ns]) => {
  if (!ready.value) return
  instances.value = []
  if (cid && clusters.value.some((c) => c.clusterId === cid)) {
    clusterId.value = cid
  }
  const nextNs = ns ?? ''
  if (nextNs !== namespaceFilter.value) {
    // 交给 namespaceFilter 的 watch 触发重查（避免同一次切换查两遍）
    namespaceFilter.value = nextNs
    return
  }
  await refresh()
})

// 页面上的命名空间筛选（含"全部"）：用户放宽/收窄后重查
watch(namespaceFilter, () => {
  if (!ready.value) return
  instances.value = []
  void refresh()
})

onBeforeUnmount(() => {
  if (pollTimer.value !== null) window.clearTimeout(pollTimer.value)
})
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">中间件实例</h2>
        <p class="ph-desc">已创建的中间件实例：查看详情、修改规格、取连接方式、删除</p>
      </div>
      <div class="ph-actions">
        <el-button :disabled="!clusterId" @click="refresh">刷新</el-button>
        <el-button type="primary" @click="router.push('/apps')">去商店创建</el-button>
      </div>
    </div>

    <div class="mw-filter">
      <span class="mw-filter-label">命名空间</span>
      <el-select v-model="namespaceFilter" clearable placeholder="全部" style="width: 180px">
        <el-option v-for="ns in namespaces" :key="ns" :label="ns" :value="ns" />
      </el-select>
      <span class="mw-filter-label">类型</span>
      <el-select v-model="code" clearable placeholder="全部" style="width: 180px" @change="refresh">
        <el-option v-for="i in catalog" :key="i.code" :label="i.name" :value="i.code" />
      </el-select>
      <span class="mw-form-help">按类型查询，只拉这一类实例的实时状态</span>
    </div>

    <el-table :data="instances" class="panel" empty-text="这个集群还没有中间件实例">
      <el-table-column label="类型" width="140">
        <template #default="{ row }">{{ nameOf(row.code) }}</template>
      </el-table-column>
      <el-table-column label="名称" min-width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/apps/${row.id}`)">
            {{ row.displayName || row.name }}
          </el-button>
        </template>
      </el-table-column>
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
      <el-table-column label="创建人" width="120">
        <template #default="{ row }">{{ row.createdBy || '—' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/apps/${row.id}`)">详情</el-button>
          <el-button link type="primary" @click="router.push({ path: `/apps/${row.id}`, query: { edit: '1' } })">编辑</el-button>
          <el-button link type="primary" @click="showConnection(row)">连接</el-button>
          <el-button link type="danger" @click="openDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <MiddlewareConnectionDialog v-model="connectionVisible" :instance-id="connectionId" :tenant-id="tenantId" />

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
.mw-filter { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.mw-filter-label { color: var(--text-3); font-size: 12.5px; }
.mw-form-help { margin-top: 4px; font-size: 12px; color: var(--text-3); line-height: 1.5; }
.mw-delete-text { margin: 0 0 12px; font-size: 13px; color: var(--text-1); }
</style>
