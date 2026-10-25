<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applicationApi } from '@/api'
import { useApplicationContext } from '@/composables/useApplicationContext'
import type { ApplicationOptions, ApplicationView } from '@/types'

const router = useRouter()

// 集群/租户都跟随顶栏级联（context: 'full'），页面不再自己维护一份
const { tenantId, clusterId, ensure } = useApplicationContext()
const apps = ref<ApplicationView[]>([])
const loading = ref(false)
const options = ref<ApplicationOptions>({ nodeKinds: [], namespaces: [] })

const dialogVisible = ref(false)
const creating = ref(false)
const form = ref({ name: '', namespace: '', displayName: '', description: '' })

const statusMeta: Record<string, { text: string; type: 'info' | 'primary' | 'success' | 'danger' }> = {
  DRAFT: { text: '草稿', type: 'info' },
  DEPLOYING: { text: '部署中', type: 'primary' },
  READY: { text: '已就绪', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
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
      spec: { namespace: form.value.namespace, nodes: [], edges: [] },
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

function openCanvas(row: ApplicationView) {
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
        <h2>应用编排</h2>
        <p class="page__hint">在一张画布上组合服务与后台进程，平台生成资源并分阶段部署。</p>
      </div>
      <div class="page__actions">
        <!-- 集群/租户由顶栏级联决定，页面不再放自己的集群下拉 -->
        <el-button type="primary" :disabled="!clusterId" @click="openCreate">新建应用</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="apps" class="panel" empty-text="还没有应用">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="openCanvas(row)">{{ row.displayName || row.name }}</el-link>
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
      <el-table-column label="版本" width="90">
        <template #default="{ row }">v{{ row.currentRevision }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="190" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openCanvas(row)">打开画布</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
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
          创建并打开画布
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
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
.page__actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
