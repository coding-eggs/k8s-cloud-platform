<script setup lang="ts">
/**
 * 流水线列表（**租户级** —— 跨集群/命名空间展示，不挂顶栏级联）。
 *
 * <p>流水线定义本身是命名空间级资源（集群 + 命名空间是它的归属），所以列表把这两项作为列标出；
 * 租户仍来自上下文（token / 代管轨），新建时在编辑器里选位置。
 *
 * <p>构建缓存、待重试工作区、CI 能力探测都是**命名空间级**的，2026-11-14 起挪到编辑器页；
 * 这里点「运行」时按该行位置现探一次能力，不可用就给出原因、不弹运行对话框。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { usePaletteAction } from '@/composables/usePaletteAction'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import { pipelineApi } from '@/api'
import type { Pipeline, PipelineCanvas } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useApplicationContext } from '@/composables/useApplicationContext'
import PipelineRunDialog from '@/components/pipeline/PipelineRunDialog.vue'

const route = useRoute()
const router = useRouter()
const perm = usePermission()
// 租户取自上下文（token / 代管轨）；集群与命名空间不跟顶栏 —— 列表跨集群列出本租户全部流水线
const { tenantId, ensure } = useApplicationContext()

const rows = ref<Pipeline[]>([])
const loading = ref(false)
const keyword = ref('')
/** 首轮加载完成前不响应租户变化（onMounted 自己会按上下文加载一次），避免重复请求 */
const ready = ref(false)

/** 触发对话框：取消勾选 = 本次屏蔽该节点及其下游 */
const runDialogOpen = ref(false)
const runTarget = ref<Pipeline | null>(null)
const runCanvas = computed<PipelineCanvas>(() => {
  try {
    return JSON.parse(runTarget.value?.canvasJson ?? '{"nodes":[],"edges":[]}')
  } catch {
    return { nodes: [], edges: [] }
  }
})

const canManage = computed(() => perm.has(apiCodes.pipelineManage))
const canRun = computed(() => perm.has(apiCodes.pipelineRun))

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return k ? rows.value.filter((r) => r.name.toLowerCase().includes(k)) : rows.value
})

async function load() {
  loading.value = true
  try {
    // 不带集群/命名空间 = 本租户全部集群
    rows.value = (await pipelineApi.list(undefined, undefined, tenantId.value)) ?? []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await ensure()
  await load()
  ready.value = true
})
watch(tenantId, () => {
  if (!ready.value) return
  void load()
})

/** 新建 = 只带租户进编辑器（位置在编辑器里选）；编辑 = 带上该行的位置 */
function toEditor(row?: Pipeline) {
  router.push({
    path: '/pipelines/editor',
    query: {
      id: row?.id,
      clusterId: row?.clusterId ?? '',
      namespace: row?.namespace ?? '',
      tenantId: tenantId.value ?? '',
    },
  })
}

function toRuns(row: Pipeline) {
  router.push({ path: '/pipelines/runs', query: { id: row.id } })
}

/** 运行前按该行的集群 + 命名空间现探 CI 能力（列表不再有页面级能力横幅） */
async function openRun(row: Pipeline) {
  if (row.clusterId && row.namespace) {
    try {
      const cap = await pipelineApi.capability(row.clusterId, tenantId.value, row.namespace)
      if (cap?.available === false) {
        ElMessage.warning(`这个集群现在跑不了 CI：${cap.reason ?? '未探测到原因'}`)
        return
      }
    } catch {
      // 探测失败按「不知道」放行 —— 真正的拦截在后端
    }
  }
  runTarget.value = row
  runDialogOpen.value = true
}

async function confirmRun(maskedNodeIds: string[]) {
  const row = runTarget.value
  if (!row?.id) return
  try {
    const runName = await pipelineApi.run(row.id, tenantId.value, maskedNodeIds)
    ElMessage.success(`已触发：${runName}`)
    runDialogOpen.value = false
    toRuns(row)
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

// 命令面板（Ctrl+K）页内功能：路由 meta.actions 的落点，条件与页面上对应按钮的禁用条件一致
usePaletteAction({
  create: { run: () => toEditor(), ready: () => canManage.value },
})
</script>

<template>
  <div class="page">
    <div class="bar">
      <el-input v-model="keyword" placeholder="按名字筛选" clearable style="width: 220px" />
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button v-if="canManage" type="primary" @click="toEditor()">新建流水线</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="filtered"
      row-key="id"
      size="default"
      empty-text="还没有流水线"
    >
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <a class="name" :class="{ focus: row.id === focusId }" @click="toEditor(row)">{{ row.name }}</a>
        </template>
      </el-table-column>
      <!-- 列表按租户跨集群：位置作为列标出（集群名拿不到时退回 id） -->
      <el-table-column label="集群" width="160">
        <template #default="{ row }">{{ row.clusterName || row.clusterId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="namespace" label="命名空间" width="150" />
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="createdBy" label="创建人" width="140" />
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ row.updatedAt ?? row.createdAt ?? '—' }}</template>
      </el-table-column>
      <!-- 行操作摊开成行内按钮（与运行历史页同一套，2026-11-04）：
           不再收进下拉；「运行」在点的时候按该行位置现探能力 -->
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="toEditor(row)">编辑</el-button>
          <el-button link type="primary" @click="toRuns(row)">运行历史</el-button>
          <el-button v-if="canRun" link type="primary" title="运行" @click="openRun(row)">
            <el-icon :size="18"><VideoPlay /></el-icon>
          </el-button>
          <el-button v-if="canManage" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <PipelineRunDialog
      v-model="runDialogOpen"
      :nodes="runCanvas.nodes"
      :edges="runCanvas.edges"
      :title="`触发运行 · ${runTarget?.name ?? ''}`"
      @confirm="confirmRun"
    />
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
.name {
  color: var(--el-color-primary);
  cursor: pointer;
}
.name.focus {
  font-weight: 600;
}
</style>
