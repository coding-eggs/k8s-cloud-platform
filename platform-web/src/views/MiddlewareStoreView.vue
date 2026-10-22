<script setup lang="ts">
/**
 * 中间件商店（批①-1：固定清单 + Redis 实例链）。
 *
 * <p>三块：商店卡片（Operator 未装则禁用）、实例列表（统一展示）、Operator（管理员安装）。
 * 卡片点「创建实例」→ 手写表单（字段来自 middlewareForms.ts）→ 提交 → 轮询操作记录。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, middlewareApi } from '@/api'
import { usePermission } from '@/stores/permission'
import MiddlewareIcon from '@/components/middleware/MiddlewareIcon.vue'
import { MIDDLEWARE_FIELDS } from '@/components/middleware/middlewareForms'
import type { K8sCluster, MiddlewareCatalogItem, MiddlewareInstance } from '@/types'

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

const createFields = computed(() =>
  createItem.value ? (MIDDLEWARE_FIELDS[createItem.value.code] ?? []) : [],
)

const modeMinReplicas = computed(
  () => createItem.value?.modes.find((m) => m.mode === createMode.value)?.minReplicas ?? 1,
)

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
  clusters.value = (await clusterApi.list()) ?? []
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
  const f: Record<string, any> = { namespace: '', displayName: '' }
  for (const field of MIDDLEWARE_FIELDS[item.code] ?? []) {
    f[field.key] = field.default ?? (field.type === 'number' ? 1 : '')
  }
  form.value = f
}

function openCreate(item: MiddlewareCatalogItem): void {
  resetForm(item)
  createVisible.value = true
}

function onModeChange(): void {
  const min = modeMinReplicas.value
  if (typeof form.value.replicas === 'number' && form.value.replicas < min) {
    form.value.replicas = min
  }
}

async function submitCreate(): Promise<void> {
  if (!createItem.value || !clusterId.value) return
  if (!form.value.namespace?.trim()) {
    ElMessage.error('请填写命名空间')
    return
  }
  const min = modeMinReplicas.value
  if (typeof form.value.replicas === 'number' && form.value.replicas < min) {
    ElMessage.error(`该形态至少 ${min} 个节点`)
    return
  }
  creating.value = true
  try {
    const fields = MIDDLEWARE_FIELDS[createItem.value.code] ?? []
    const payload: Record<string, any> = {
      clusterId: clusterId.value,
      code: createItem.value.code,
      mode: createMode.value,
      namespace: form.value.namespace.trim(),
      displayName: form.value.displayName?.trim() || undefined,
    }
    for (const field of fields) {
      payload[field.key] = form.value[field.key]
    }
    const opId = await middlewareApi.create(payload as any)
    createVisible.value = false
    ElMessage.success('已提交创建')
    poll(opId)
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
              <el-tag v-if="item.operatorReady" size="small" type="success" effect="plain">可用</el-tag>
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
                  v-if="perm.isAdmin && !item.operatorReady"
                  size="small"
                  :loading="operating === item.code"
                  @click="installOperator(item)"
                >安装 Operator</el-button>
                <el-button
                  v-else
                  type="primary"
                  size="small"
                  :disabled="!item.operatorReady || !clusterId"
                  @click="openCreate(item)"
                >创建实例</el-button>
                <span v-if="!item.operatorReady" class="mw-hint">
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
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="openDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ Operator（管理员） ============ -->
      <el-tab-pane v-if="perm.isAdmin" label="Operator" name="operator">
        <el-table :data="catalog" class="panel" empty-text="没有可安装的 Operator">
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
      width="680px"
      :close-on-click-modal="false"
    >
      <el-form label-width="110px">
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
          <el-input v-model="form.namespace" spellcheck="false" placeholder="demo（租户仅限分配表内的命名空间）" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" spellcheck="false" placeholder="默认按类型 + 短码生成" />
        </el-form-item>
        <el-form-item v-for="field in createFields" :key="field.key" :label="field.label">
          <el-input-number
            v-if="field.type === 'number'"
            v-model="form[field.key]"
            :min="field.key === 'replicas' ? modeMinReplicas : (field.min ?? 0)"
            :max="field.max ?? 999"
          />
          <el-input v-else v-model="form[field.key]" spellcheck="false" :placeholder="field.placeholder" />
          <div v-if="field.help" class="mw-form-help">{{ field.help }}</div>
        </el-form-item>
      </el-form>
      <p class="mw-form-note">
        初始密码由平台生成并写入同命名空间的 Secret（{{ '<名称>-credentials' }}），详情里可以查到 Secret 名。
      </p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
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
