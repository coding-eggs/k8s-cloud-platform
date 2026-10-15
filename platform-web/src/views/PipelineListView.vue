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
import { MoreFilled } from '@element-plus/icons-vue'
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
const showCache = ref(false)
const clearing = ref(false)
const keyword = ref('')

const canManage = computed(() => perm.has(apiCodes.pipelineManage))
const canRun = computed(() => perm.has(apiCodes.pipelineRun))

const clusterId = computed(() => state.clusterId ?? '')
const namespace = computed(() => state.namespace ?? '')

/** 页面级门控（三态：`false` 才禁用 —— 「不知道」不禁，真正的拦截在后端）+ 构建缓存状态 */
const { available: capAvailable, reason: capReason, cache: cacheStatus,
        load: reloadCapability, clearCache } = usePipelineCapability(
  clusterId, namespace, computed(() => state.tenantId ?? null))

/**
 * 缓存那一行的文案。**「不知道」与「未启用」要分开说**：
 * 前者是我们没探到（显示「状态未知」），后者是集群真的给不了共享卷（那时才显示原因）。
 */
const cacheLabel = computed(() => {
  if (!cacheStatus.value) return '构建缓存：状态未知'
  return cacheStatus.value.ready
    ? `构建缓存：已启用 ${cacheStatus.value.capacity}`
    : '构建缓存：未启用'
})

/** 清缓存：删的是那块长期卷（不是里面的文件），下次运行自动重建一块空的。 */
async function doClearCache() {
  await ElMessageBox.confirm(
    `清掉「${namespace.value}」的构建缓存？

`
      + '删掉的是那块卷本身，下次运行会自动重建一块空的 —— '
      + '那一次构建要把依赖重新下载一遍（会明显变慢）。',
    '确认清缓存',
    { type: 'warning', confirmButtonText: '清掉', cancelButtonText: '算了' },
  )
  clearing.value = true
  try {
    await clearCache()
    ElMessage.success('已清掉，下次运行会重建一块空的')
  } catch {
    /* 拦截器已提示（还挂着 Pod 的卷删不掉，后端会拒） */
  } finally {
    clearing.value = false
  }
}

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

/** 行操作下拉的分发（与集群列表同一形状） */
function onCommand(cmd: string, row: Pipeline): void {
  switch (cmd) {
    case 'edit': toEditor(row.id); break
    case 'runs': toRuns(row.id!); break
    case 'run': void run(row); break
    case 'remove': void remove(row); break
  }
}
</script>

<template>
  <div class="page">
    <div class="bar">
      <el-input v-model="keyword" placeholder="按名字筛选" clearable style="width: 220px" />
      <!-- 构建缓存：作用域正好是「当前集群 + 命名空间」，与缓存卷一一对应 -->
      <el-button v-if="clusterId && namespace" size="small" plain
                 :type="cacheStatus?.ready ? 'success' : 'info'"
                 @click="showCache = true">
        {{ cacheLabel }}
      </el-button>
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
      <el-table-column label="操作" width="90" align="center">
        <!-- 行操作收敛成一个下拉（与集群 / 工作负载那几张列表页同一形状）：
             行内平铺四个按钮既挤又容易点错，尤其「删除」跟「运行」挨着 -->
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(cmd: string) => onCommand(cmd, row)">
            <el-button link type="primary" :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="edit">
                  <el-button link type="primary">编辑</el-button>
                </el-dropdown-item>
                <el-dropdown-item command="runs">
                  <el-button link type="primary">运行历史</el-button>
                </el-dropdown-item>
                <el-dropdown-item v-if="canRun" command="run" :disabled="capAvailable === false">
                  <el-button link type="primary" :disabled="capAvailable === false">运行</el-button>
                </el-dropdown-item>
                <el-dropdown-item v-if="canManage" divided command="remove">
                  <el-button link type="danger">删除</el-button>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="showCache" title="构建缓存" width="520px">
      <div class="cache-row">
        <span class="cache-key">状态</span>
        <el-tag v-if="cacheStatus?.ready" type="success" size="small">已启用</el-tag>
        <el-tag v-else-if="cacheStatus" type="info" size="small">未启用</el-tag>
        <span v-else class="dim">状态未知（没探到）</span>
      </div>
      <div v-if="cacheStatus" class="cache-row">
        <span class="cache-key">容量</span><span>{{ cacheStatus.capacity }}</span>
      </div>
      <div v-if="cacheStatus" class="cache-row">
        <span class="cache-key">卷名</span><span class="mono">{{ cacheStatus.claimName }}</span>
      </div>
      <div v-if="cacheStatus && !cacheStatus.ready && cacheStatus.reason" class="cache-why">
        {{ cacheStatus.reason }}
      </div>

      <div class="cache-note">
        构建节点（Java / Node / Go / Python，以及开了开关的命令节点）的依赖缓存在这里，
        <b>跨运行保留</b> —— 第二次构建不必再下一遍依赖。<br />
        清除会删掉那块卷本身，下次运行自动重建一块空的：<b>那一次构建会明显变慢</b>。
      </div>

      <template #footer>
        <el-button @click="showCache = false">关闭</el-button>
        <el-button v-if="canManage && cacheStatus?.exists" type="danger" plain
                   :loading="clearing" @click="doClearCache">清掉缓存</el-button>
      </template>
    </el-dialog>
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
/* 构建缓存对话框里的几行（键值对齐 + 卷名等宽） */
.cache-row {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 13px;
  margin-bottom: 6px;
}
.cache-key {
  width: 48px;
  color: var(--text-3);
}
.mono {
  font-family: monospace;
}
.cache-why {
  margin: 8px 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-color-info-light-9);
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.6;
}
.cache-note {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.7;
}
.dim {
  color: var(--text-3);
}
.name.focus {
  font-weight: 600;
}
</style>
