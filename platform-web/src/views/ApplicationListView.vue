<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applicationApi } from '@/api'
import { useApplicationContext } from '@/composables/useApplicationContext'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import type { ApplicationOptions, ApplicationView } from '@/types'

const router = useRouter()
const perm = usePermission()
const canWrite = computed(() => perm.has(apiCodes.applicationWrite))

// 集群/租户都跟随顶栏级联（context: 'full'），页面不再自己维护一份
const { tenantId, clusterId, ensure } = useApplicationContext()
const apps = ref<ApplicationView[]>([])
const loading = ref(false)
const options = ref<ApplicationOptions>({ namespaces: [] })

const dialogVisible = ref(false)
const creating = ref(false)
const form = ref({ name: '', namespace: '', displayName: '', description: '' })

// 台账状态（发布执行器写入）：没发布过 → 未发布；线上服务的逐个健康在应用页现读
const statusMeta: Record<string, { text: string; type: 'info' | 'primary' | 'success' | 'danger' }> = {
  DRAFT: { text: '未发布', type: 'info' },
  DEPLOYING: { text: '发布中', type: 'primary' },
  READY: { text: '运行正常', type: 'success' },
  FAILED: { text: '发布失败', type: 'danger' },
  INTERRUPTED: { text: '已中断', type: 'danger' },
}

const canCreate = computed(() => !!clusterId.value && !!form.value.name.trim()
  && !!form.value.namespace)

async function load() {
  if (!clusterId.value) {
    apps.value = []
    return
  }
  loading.value = true
  try {
    apps.value = await applicationApi.list(clusterId.value, tenantId.value)
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  if (!tenantId.value) {
    ElMessage.warning('请先在顶栏选择租户：应用归属租户')
    return
  }
  const cid = clusterId.value
  if (!cid) {
    ElMessage.warning('请先在顶栏选择集群')
    return
  }
  form.value = { name: '', namespace: '', displayName: '', description: '' }
  options.value = await applicationApi.options(cid, tenantId.value)
  const only = options.value.namespaces[0]
  if (options.value.namespaces.length === 1 && only) {
    form.value.namespace = only
  }
  dialogVisible.value = true
}

async function create() {
  if (!canCreate.value) {
    ElMessage.warning('请填写应用名并选择命名空间')
    return
  }
  const cid = clusterId.value
  if (!cid) {
    ElMessage.warning('请先在顶栏选择集群')
    return
  }
  creating.value = true
  try {
    const created = await applicationApi.create({
      clusterId: cid,
      namespace: form.value.namespace,
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

watch([tenantId, clusterId], () => {
  load()
})

onMounted(async () => {
  await ensure()
  await load()
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
        <!-- 集群/租户由顶栏级联决定，页面不再放自己的集群下拉 -->
        <el-button type="primary" :disabled="!clusterId || !canWrite" :title="canWrite ? '' : '没有应用写权限'" @click="openCreate">新建应用</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="apps" class="panel" empty-text="还没有应用">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="openApp(row)">{{ row.displayName || row.name }}</el-link>
          <el-tag v-if="row.legacy" class="app-list__legacy" type="warning" size="small" effect="plain" title="画布时代创建的应用，只能删除">旧模型</el-tag>
        </template>
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
        <el-form-item label="命名空间" required>
          <el-select v-model="form.namespace" placeholder="选择本租户已分配的命名空间" style="width: 100%">
            <el-option v-for="ns in options.namespaces" :key="ns" :label="ns" :value="ns" />
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
.app-list__legacy {
  margin-left: 6px;
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
