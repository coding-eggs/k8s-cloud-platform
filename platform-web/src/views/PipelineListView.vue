<script setup lang="ts">
/**
 * 流水线列表（**命名空间级** —— 跟 Deployment/Service 一样，集群与命名空间来自顶栏级联）。
 *
 * ⚠️ 页面按 capability 门控（`/pipeline/capability`）：集群没装 Tekton / 没有可动态供应的
 * StorageClass 时，这里给一句「为什么用不了」并把「运行」按下去，而不是让用户点了才收一个 500。
 * **只挡运行** —— 定义照常可编辑保存：渲染是纯函数，不需要集群有 Tekton，
 * 用户完全可以先把流水线配好，等集群装完再点运行。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pipelineApi } from '@/api'
import type { Pipeline } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useResourceContext } from '@/stores/context'
import { usePipelineCapability } from '@/composables/usePipelineCapability'

const route = useRoute()
const router = useRouter()
const perm = usePermission()
const { state } = useResourceContext()

const rows = ref<Pipeline[]>([])
const loading = ref(false)
const keyword = ref('')

const canManage = computed(() => perm.has(apiCodes.pipelineManage))
const canRun = computed(() => perm.has(apiCodes.pipelineRun))

const clusterId = computed(() => state.clusterId ?? '')
const namespace = computed(() => state.namespace ?? '')

/** 页面级门控（三态：`false` 才禁用 —— 「不知道」不禁，真正的拦截在后端） */
const { available: capAvailable, reason: capReason, load: reloadCapability } =
  usePipelineCapability(clusterId)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return k ? rows.value.filter((r) => r.name.toLowerCase().includes(k)) : rows.value
})

async function load() {
  if (!clusterId.value || !namespace.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    rows.value = await pipelineApi.list(clusterId.value, namespace.value)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch([clusterId, namespace], load)

function toEditor(id?: string) {
  router.push({
    path: '/pipelines/editor',
    // tenantId 一起带过去：编辑器不进顶栏级联，而触发运行要它（平台管理员代管轨必须显式给）
    query: { id, clusterId: clusterId.value, namespace: namespace.value, tenantId: state.tenantId ?? '' },
  })
}

function toRuns(id: string) {
  router.push({ path: '/pipelines/runs', query: { id } })
}

async function run(row: Pipeline) {
  try {
    const runName = await pipelineApi.run(row.id!, state.tenantId ?? undefined)
    ElMessage.success(`已触发：${runName}`)
    toRuns(row.id!)
  } catch {
    /* 拦截器已提示 */
  }
}

async function remove(row: Pipeline) {
  await ElMessageBox.confirm(
    `删除流水线「${row.name}」？运行记录与日志**不会**跟着删（那是历史）。`,
    '确认',
    { type: 'warning' },
  )
  await pipelineApi.remove(row.id!)
  ElMessage.success('已删除')
  await load()
}

/** `?id=` 让运行历史页能跳回来（面包屑用） */
const focusId = computed(() => (route.query.id as string) || '')
</script>

<template>
  <div class="page">
    <div class="bar">
      <el-input v-model="keyword" placeholder="按名字筛选" clearable style="width: 220px" />
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button v-if="canManage" type="primary" @click="toEditor()">新建流水线</el-button>
    </div>

    <el-alert
      v-if="!clusterId || !namespace"
      type="info"
      :closable="false"
      title="先在顶栏选一个集群与命名空间 —— 流水线是命名空间级的"
    />

    <!-- 门控只挡「运行」：定义照常可编辑保存（渲染是纯函数，不需要集群装 Tekton）——
         用户可以先在平台里把流水线配好，等集群装完 Tekton 再点运行 -->
    <div v-if="clusterId && namespace && capAvailable === false" class="cap-banner">
      <span>
        这个集群现在跑不了 CI：{{ capReason }}。流水线可以照常编辑保存，但「运行」已禁用。
      </span>
      <el-button size="small" @click="reloadCapability">重新探测</el-button>
    </div>

    <el-table
      v-if="clusterId && namespace"
      v-loading="loading"
      :data="filtered"
      row-key="id"
      size="default"
    >
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <a class="name" :class="{ focus: row.id === focusId }" @click="toEditor(row.id)">{{ row.name }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column prop="createdBy" label="创建人" width="140" />
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ row.updatedAt ?? row.createdAt ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button link type="primary" @click="toEditor(row.id)">编辑</el-button>
          <el-button link type="primary" @click="toRuns(row.id)">运行历史</el-button>
          <el-button v-if="canRun" link type="primary" :disabled="capAvailable === false" @click="run(row)">
            运行
          </el-button>
          <el-button v-if="canManage" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.bar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.spacer {
  flex: 1;
}
/* capability 门控横幅：与 Calico 那几个页面同一形状 */
.cap-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
  color: var(--text-2);
  font-size: 13px;
}
.name {
  color: var(--el-color-primary);
  cursor: pointer;
}
.name.focus {
  font-weight: 600;
}
</style>
