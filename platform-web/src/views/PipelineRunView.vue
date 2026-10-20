<script setup lang="ts">
/**
 * 运行历史（**列表**）。
 *
 * ⚠️ 详情在**另一个页面**（`/pipelines/run?name=…`）：原先它是这里右侧那 560px 的一栏，
 * DAG 与日志都被挤在小框里看着不方便（2026-10-16 用户的反馈）。这一页只管列与两个动作，
 * **点运行名**去详情（与集群/命名空间那几个列表页同一套：名称可点）。
 *
 * ⚠️ 「取消」放在**行操作下拉**里（`ClusterView` 那一套）：这一列原来直接摊一个「取消」按钮，
 * 而只有正在跑的行才有它 —— 跑完的行那一栏全是空的，看着像坏了（2026-10-16 用户的反馈）。
 * 收进下拉之后触发图标**每行都有**，取消对跑完的行**禁用**（灰着说明取消不了），
 * 不会再有「空栏」（中间一度整栏去掉过，随后按用户要求以这个形态加回来）。
 *
 * ⚠️ 数据源是「归档了没有」那条分界线（spec §4.3）：历史一律读库 ——
 * 集群里的 `PipelineRun` 与它的 Pod 在归档那一刻就被删了。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import { pipelineApi } from '@/api'
import type { Pipeline, PipelineRun } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useResourceContext } from '@/stores/context'
import { fmtDate } from '@/utils/format'
import PipelineRunDialog from '@/components/pipeline/PipelineRunDialog.vue'

const route = useRoute()
const router = useRouter()
const perm = usePermission()

/** 生效租户：走 k8s-server 的那几个端点都要它（平台管理员代管轨必须显式给） */
const { state: resourceContext } = useResourceContext()
const effectiveTenantId = computed(() => resourceContext.tenantId ?? undefined)

const pipelineId = computed(() => (route.query.id as string) || '')
const pipeline = ref<Pipeline | null>(null)
const runs = ref<PipelineRun[]>([])
const loading = ref(false)

const canRun = computed(() => perm.has(apiCodes.pipelineRun))
/** 删运行历史要的是**改流水线**那个码（后端 `/pipeline/run/delete` 用的就是它，与清缓存同一条理由） */
const canManage = computed(() => perm.has(apiCodes.pipelineManage))

const STATUS_TAG: Record<string, string> = {
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
  TIMEOUT: 'warning',
  RUNNING: 'primary',
}

function tagType(s: string): string {
  return STATUS_TAG[s] ?? 'info'
}

function duration(r: PipelineRun): string {
  if (!r.startTime || !r.completionTime) return '—'
  const ms = new Date(r.completionTime).getTime() - new Date(r.startTime).getTime()
  return ms > 0 ? `${Math.round(ms / 1000)}s` : '—'
}

async function load(): Promise<void> {
  if (!pipelineId.value) return
  loading.value = true
  try {
    pipeline.value = await pipelineApi.get(pipelineId.value)
    runs.value = await pipelineApi.runs(pipelineId.value)
  } catch {
    runs.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 点运行名 → 详情页。**带上流水线 id**：详情页的「← 运行历史」要靠它回到这里 */
function openRun(r: PipelineRun): void {
  void router.push({ path: '/pipelines/run', query: { name: r.runName, id: pipelineId.value } })
}

/** 触发对话框：取消勾选 = 本次屏蔽该节点及其下游 */
const runDialogOpen = ref(false)
const runCanvas = computed(() => {
  try {
    return JSON.parse(pipeline.value?.canvasJson ?? '{"nodes":[],"edges":[]}')
  } catch {
    return { nodes: [], edges: [] }
  }
})

function openRunDialog(): void {
  runDialogOpen.value = true
}

async function confirmRun(maskedNodeIds: string[]): Promise<void> {
  try {
    const runName = await pipelineApi.run(pipelineId.value, effectiveTenantId.value, maskedNodeIds)
    ElMessage.success(`已触发：${runName}`)
    runDialogOpen.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

/**
 * 行操作下拉（与 `ClusterView` 同一套：`MoreFilled` 图标 + `@command`）。
 *
 * ⚠️ 这里**只有执行得动的事**：查看详情（名称也可点，两条路都留着）、取消（只对正在跑的）、
 * 删除（只对已结束的）。**禁用而不是隐藏** —— 灰着能说明"这一条现在做不了"，
 * 比一个空菜单好读。
 */
function onCommand(cmd: string, r: PipelineRun): void {
  if (cmd === 'detail') {
    openRun(r)
    return
  }
  if (cmd === 'cancel') {
    void cancel(r)
  }
  if (cmd === 'delete') {
    void removeRun(r)
  }
}

/** 取消：**先抓一次日志再取消**（见后端 cancel 的说明），所以取消之后日志仍然看得到 */
async function cancel(r: PipelineRun): Promise<void> {
  await ElMessageBox.confirm(
    '取消这次运行？会先抓一次日志再取消 —— 所以取消之后日志仍然看得到。',
    '确认',
    { type: 'warning' },
  )
  try {
    await pipelineApi.cancelRun(r.runName, effectiveTenantId.value)
    ElMessage.success('已取消')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

/**
 * 删掉这条运行记录。**不可恢复**：归档那一刻集群里的对象与 Pod 就删了，
 * 库里的这一行是它的日志、YAML 与节点状态的**唯一留存** —— 确认框必须把这句话说清楚。
 *
 * <p>还在跑的那条后端会拒（先取消），所以这里对它**禁用**（见模板）。
 */
async function removeRun(r: PipelineRun): Promise<void> {
  // ⚠️ 运行名放**标题**、不放正文：ElMessageBox 的正文是纯文本，`\n` 会被折成空格
  await ElMessageBox.confirm(
    '删掉这条运行记录？不可恢复 —— 归档时集群里那个对象就已经删了，这行是它的日志与 YAML 的唯一留存。',
    `删除 ${r.runName}`,
    { type: 'warning', confirmButtonText: '删除' },
  )
  try {
    await pipelineApi.runDelete(r.runName)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<template>
  <div class="page">
    <div class="bar">
      <el-button link type="primary" @click="router.push('/pipelines')">← 流水线</el-button>
      <span class="title">{{ pipeline?.name ?? '—' }}</span>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button v-if="canRun" type="primary" @click="openRunDialog">运行</el-button>
    </div>

    <el-table v-loading="loading" :data="runs" row-key="id">
      <el-table-column label="运行" min-width="260">
        <template #default="{ row }">
          <!-- 详情挂在**名称**上（与集群/命名空间那几个列表页同一套：res-name + name-link）——
               整行可点会把「复制运行名」这类操作也吃掉 -->
          <code class="res-name name-link" @click="openRun(row)">{{ row.runName }}</code>
          <span v-if="(row.attemptNo ?? 1) > 1" class="attempt-tag">第 {{ row.attemptNo }} 次</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="160">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <!-- ⭐ 开始时间：光看「耗时」不知道这条运行是什么时候跑的（2026-10-16 用户提的） -->
      <el-table-column label="开始时间" width="180">
        <template #default="{ row }">{{ fmtDate(row.startTime) }}</template>
      </el-table-column>
      <el-table-column label="触发人" width="110" prop="triggeredBy" />
      <el-table-column label="耗时" width="90">
        <template #default="{ row }">{{ duration(row) }}</template>
      </el-table-column>
      <!-- 行操作下拉：与 ClusterView 同一套（宽度 64、无列头、固定在右侧） -->
      <el-table-column width="64" fixed="right">
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(cmd: string) => onCommand(cmd, row)">
            <el-button link type="primary" :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="detail">
                  <el-button link type="primary">查看详情</el-button>
                </el-dropdown-item>
                <el-dropdown-item divided command="cancel" :disabled="row.status !== 'RUNNING'">
                  <el-button link type="danger" :disabled="row.status !== 'RUNNING'">取消</el-button>
                </el-dropdown-item>
                <!-- ⚠️ 还在跑的那条删不了（后端也拒）：它的归档回写还要落在这行上，
                     而集群里那次运行还在跑 —— 想停它就点「取消」 -->
                <el-dropdown-item v-if="canManage" command="delete" :disabled="row.status === 'RUNNING'">
                  <el-button link type="danger" :disabled="row.status === 'RUNNING'">删除</el-button>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty">还没有运行过 —— 点右上角「运行」触发一次</div>
      </template>
    </el-table>
    <PipelineRunDialog
      v-model="runDialogOpen"
      :nodes="runCanvas.nodes"
      :edges="runCanvas.edges"
      :title="`触发运行 · ${pipeline?.name ?? ''}`"
      @confirm="confirmRun"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
}
.bar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.title {
  font-weight: 600;
}
.spacer {
  flex: 1;
}
/* 点运行名进详情 —— 与集群/命名空间那几个列表页同一套（res-name + name-link） */
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  word-break: break-all;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
.attempt-tag {
  margin-left: 6px;
  font-size: 10px;
  color: var(--info);
  border: 1px solid var(--info);
  border-radius: 999px;
  padding: 0 6px;
}
.empty {
  color: var(--text-3);
  font-size: 12px;
  padding: 12px 0;
}
</style>
