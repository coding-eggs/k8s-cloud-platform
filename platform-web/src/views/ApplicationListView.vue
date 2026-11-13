<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { usePaletteAction } from '@/composables/usePaletteAction'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applicationApi } from '@/api'
import { useApplicationContext } from '@/composables/useApplicationContext'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import type { ApplicationOptions, ApplicationTarget, ApplicationView } from '@/types'
import { useQueryFilter } from '@/composables/useQueryFilter'

const router = useRouter()
const perm = usePermission()
const canWrite = computed(() => perm.has(apiCodes.applicationWrite))

// 应用按**租户**展示（2026-11-13 用户定）：不挂顶栏集群/命名空间级联，列表跨集群列出本租户全部应用，
// 「集群」「命名空间」作为列标出；新建时在表单里一次选定集群 + 命名空间。租户仍取自上下文（token / 代管）
const { tenantId, ensure } = useApplicationContext()
const apps = ref<ApplicationView[]>([])
const loading = ref(false)
const options = ref<ApplicationOptions>({ namespaces: [], targets: [] })

const dialogVisible = ref(false)
const creating = ref(false)
/** target = `${clusterId}/${namespace}`（下拉的值；提交时拆开） */
const form = ref({ name: '', target: '', displayName: '', description: '' })
const targetKey = (t: ApplicationTarget) => `${t.clusterId}/${t.namespace}`
const selectedTarget = computed(() =>
  (options.value.targets ?? []).find((t) => targetKey(t) === form.value.target))
/** 下拉按集群分组 */
const targetGroups = computed(() => {
  const groups = new Map<string, { label: string; items: ApplicationTarget[] }>()
  for (const t of options.value.targets ?? []) {
    const g = groups.get(t.clusterId) ?? { label: t.clusterName, items: [] }
    g.items.push(t)
    groups.set(t.clusterId, g)
  }
  return [...groups.values()]
})

// 台账状态（发布执行器写入）：没发布过 → 未发布；线上服务的逐个健康在应用页现读
const statusMeta: Record<string, { text: string; type: 'info' | 'primary' | 'success' | 'danger' }> = {
  DRAFT: { text: '未发布', type: 'info' },
  DEPLOYING: { text: '发布中', type: 'primary' },
  READY: { text: '运行正常', type: 'success' },
  FAILED: { text: '发布失败', type: 'danger' },
  INTERRUPTED: { text: '已中断', type: 'danger' },
  OFFLINE: { text: '已下线', type: 'info' },
}

/** 状态筛选（台账原值，与 URL `?status=` 同步；总览应用卡点「失败 1」带 FAILED 过来） */
const statusFilter = useQueryFilter('status')
const shown = computed(() =>
  statusFilter.value ? apps.value.filter((a) => a.status === statusFilter.value) : apps.value,
)

const canCreate = computed(() => !!form.value.name.trim() && !!selectedTarget.value)

async function load() {
  loading.value = true
  try {
    // 不带集群 = 本租户全部集群
    apps.value = await applicationApi.list(undefined, tenantId.value)
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  if (!tenantId.value) {
    ElMessage.warning('当前没有租户上下文，无法新建应用（应用归属租户）')
    return
  }
  form.value = { name: '', target: '', displayName: '', description: '' }
  options.value = await applicationApi.options(undefined, tenantId.value)
  const targets = options.value.targets ?? []
  if (!targets.length) {
    ElMessage.warning('本租户还没有分配任何命名空间，无法新建应用')
    return
  }
  const only = targets[0]
  if (targets.length === 1 && only) {
    form.value.target = targetKey(only)
  }
  dialogVisible.value = true
}

async function create() {
  if (!canCreate.value) {
    ElMessage.warning('请填写应用名并选择集群与命名空间')
    return
  }
  const target = selectedTarget.value!
  creating.value = true
  try {
    const created = await applicationApi.create({
      clusterId: target.clusterId,
      namespace: target.namespace,
      name: form.value.name.trim(),
      displayName: form.value.displayName || undefined,
      description: form.value.description || undefined,
      tenantId: tenantId.value,
    })
    dialogVisible.value = false
    router.push(`/applications/${created.id}`)
  } finally {
    creating.value = false
  }
}

async function remove(row: ApplicationView) {
  await ElMessageBox.confirm(
    `删除应用「${row.displayName || row.name}」？集群里由它创建的对象会被删除（PVC 保留）。`,
    '删除应用',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
  await applicationApi.remove(row.id, tenantId.value)
  ElMessage.success('已删除')
  await load()
}

function openApp(row: ApplicationView) {
  router.push(`/applications/${row.id}`)
}

watch(tenantId, () => {
  load()
})

onMounted(async () => {
  await ensure()
  await load()
})

// 命令面板（Ctrl+K）页内功能：路由 meta.actions 的落点，条件与页面上对应按钮的禁用条件一致
usePaletteAction({
  create: { run: () => void openCreate(), ready: () => canWrite.value },
})
</script>

<template>
  <div class="page">
    <div class="page__header">
      <div>
        <h2>应用</h2>
        <p class="page__hint">应用是平台上可发布的单位：配置服务与依赖，按服务勾选发布，随时回滚。</p>
      </div>
      <div class="page__actions">
        <!-- 应用按租户展示：集群与命名空间在新建表单里选 -->
        <el-button type="primary" :disabled="!canWrite" :title="canWrite ? '' : '没有应用写权限'" @click="openCreate">新建应用</el-button>
      </div>
    </div>

    <div class="page__filters">
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px">
        <el-option v-for="(m, k) in statusMeta" :key="k" :label="m.text" :value="k" />
      </el-select>
    </div>

    <el-table
      v-loading="loading"
      :data="shown"
      class="panel"
      :empty-text="statusFilter && apps.length ? '没有该状态的应用' : '还没有应用'"
    >
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="openApp(row)">{{ row.displayName || row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="集群" width="160">
        <template #default="{ row }">{{ row.clusterName || row.clusterId }}</template>
      </el-table-column>
      <el-table-column prop="namespace" label="命名空间" width="180" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status]?.type ?? 'info'" effect="plain">
            {{ statusMeta[row.status]?.text ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="线上发布" width="110">
        <template #default="{ row }">
          <span v-if="row.liveReleaseNo">#{{ row.liveReleaseNo }}</span>
          <span v-else class="muted">未发布</span>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="190" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openApp(row)">管理</el-button>
          <el-button v-if="canWrite" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建应用" width="480px">
      <el-form label-width="90px">
        <el-form-item label="应用名" required>
          <el-input v-model="form.name" placeholder="例如 order-service（决定资源名前缀）" maxlength="60" />
        </el-form-item>
        <el-form-item label="部署位置" required>
          <el-select v-model="form.target" placeholder="选择集群 / 命名空间（本租户已分配的）" filterable
                     style="width: 100%">
            <el-option-group v-for="g in targetGroups" :key="g.label" :label="g.label">
              <el-option v-for="t in g.items" :key="targetKey(t)" :value="targetKey(t)"
                         :label="`${t.clusterName} / ${t.namespace}`" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" maxlength="60" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!canCreate" @click="create">
          创建并进入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page__filters {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page__hint {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.page__actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
