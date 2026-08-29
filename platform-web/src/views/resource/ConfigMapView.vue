<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { configMapApi } from '@/api'
import type { K8sConfigMap } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sConfigMap[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await configMapApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

// ---------- 创建 / 编辑对话框（基础表单：名称 + key-value 行） ----------
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const form = reactive({
  name: '',
  rows: [] as { key: string; value: string }[],
})

function newRows(): { key: string; value: string }[] {
  return [{ key: '', value: '' }]
}

function openCreate(): void {
  isEdit.value = false
  form.name = ''
  form.rows = newRows()
  dialogVisible.value = true
}

function openEdit(row: K8sConfigMap): void {
  isEdit.value = true
  form.name = row.name
  const entries = Object.entries(row.data ?? {})
  form.rows = entries.length ? entries.map(([key, value]) => ({ key, value })) : newRows()
  dialogVisible.value = true
}

function addRow(): void {
  form.rows.push({ key: '', value: '' })
}

function removeRow(index: number): void {
  form.rows.splice(index, 1)
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
  const data: Record<string, string> = {}
  for (const row of form.rows) {
    const key = row.key.trim()
    if (!key) {
      ElMessage.warning('存在空的 Key')
      return
    }
    if (data[key] !== undefined) {
      ElMessage.warning(`Key「${key}」重复`)
      return
    }
    data[key] = row.value
  }

  saving.value = true
  try {
    if (isEdit.value) {
      await configMapApi.update(name, { tenantId: state.tenantId!, clusterId: state.clusterId! }, {
        name,
        namespace: state.namespace!,
        data,
      })
      ElMessage.success('已更新')
    } else {
      await configMapApi.create({ tenantId: state.tenantId!, clusterId: state.clusterId! }, {
        name,
        namespace: state.namespace!,
        data,
      })
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

// ---------- 删除 ----------
async function onDelete(row: K8sConfigMap): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 ConfigMap「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await configMapApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（Data / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sConfigMap | null>(null)
const yamlText = ref('')
const detailTab = ref('data')

async function openDetail(row: K8sConfigMap): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'data'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await configMapApi.getYaml(detail.value.name, ctxParams.value)
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
    <PageHeader title="ConfigMap" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="openCreate">创建 ConfigMap</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 ConfigMap"
      description="点击右上「创建 ConfigMap」新建，或到顶栏切换上下文查看其他命名空间。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="标签" min-width="180">
          <template #default="{ row }">
            <template v-if="row.labels && Object.keys(row.labels).length">
              <el-tag
                v-for="(v, k) in row.labels"
                :key="k"
                size="small"
                effect="plain"
                class="label-tag"
              >{{ k }}={{ v }}</el-tag>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="数据项" width="100">
          <template #default="{ row }">{{ Object.keys(row.data ?? {}).length }} 项</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 创建 / 编辑 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? `编辑 ConfigMap · ${form.name}` : '创建 ConfigMap'" width="640px" top="8vh">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="isEdit" placeholder="小写字母/数字/-，例如 app-config" />
          <div class="form-tip">K8s 资源名创建后不可修改；命名空间 = 当前上下文</div>
        </el-form-item>
        <el-form-item label="数据">
          <div class="kv-editor">
            <div v-for="(row, idx) in form.rows" :key="idx" class="kv-row">
              <el-input v-model="row.key" placeholder="Key（如 log.level）" class="kv-key" />
              <el-input v-model="row.value" placeholder="Value" />
              <el-button link type="danger" :disabled="form.rows.length <= 1" @click="removeRow(idx)">删除</el-button>
            </div>
            <el-button class="add-row-btn" plain @click="addRow">+ 添加键值对</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`ConfigMap · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="数据" name="data">
          <el-table :data="Object.entries(detail?.data ?? {}).map(([key, value]) => ({ key, value }))" stripe>
            <el-table-column prop="key" label="Key" min-width="160">
              <template #default="{ row }"><code class="res-name">{{ row.key }}</code></template>
            </el-table-column>
            <el-table-column prop="value" label="Value" min-width="240">
              <template #default="{ row }"><span class="kv-value">{{ row.value }}</span></template>
            </el-table-column>
          </el-table>
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
.label-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.muted {
  color: var(--text-3);
}
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.kv-editor {
  width: 100%;
}
.kv-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.kv-key {
  width: 40%;
  flex-shrink: 0;
}
.add-row-btn {
  width: 100%;
}
.kv-value {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  word-break: break-all;
  white-space: pre-wrap;
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
