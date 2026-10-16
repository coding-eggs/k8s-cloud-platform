<script setup lang="ts">
/**
 * 运行历史（**列表**）。
 *
 * ⚠️ 详情在**另一个页面**（`/pipelines/run?name=…`）：原先它是这里右侧那 560px 的一栏，
 * DAG 与日志都被挤在小框里看着不方便（2026-10-16 用户的反馈）。这一页只管列与两个动作，
 * **点运行名**去详情（与集群/命名空间那几个列表页同一套：名称可点）。
 *
 * ⚠️ 「取消」不在这张表上：它就是「操作」那栏唯一的东西，而绝大多数行（跑完的）那栏是空的
 * （2026-10-16 用户的反馈：空栏不如去掉）。取消在**详情页**头部 —— 打开正在跑的那条就能点。
 *
 * ⚠️ 数据源是「归档了没有」那条分界线（spec §4.3）：历史一律读库 ——
 * 集群里的 `PipelineRun` 与它的 Pod 在归档那一刻就被删了。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { pipelineApi } from '@/api'
import type { Pipeline, PipelineRun } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { useResourceContext } from '@/stores/context'
import { fmtDate } from '@/utils/format'

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

async function run(): Promise<void> {
  try {
    const runName = await pipelineApi.run(pipelineId.value, effectiveTenantId.value)
    ElMessage.success(`已触发：${runName}`)
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
      <el-button v-if="canRun" type="primary" @click="run">运行</el-button>
    </div>

    <el-table v-loading="loading" :data="runs" row-key="id">
      <el-table-column label="运行" min-width="260">
        <template #default="{ row }">
          <!-- 详情挂在**名称**上（与集群/命名空间那几个列表页同一套：res-name + name-link）——
               整行可点会把「复制运行名」这类操作也吃掉 -->
          <code class="res-name name-link" @click="openRun(row)">{{ row.runName }}</code>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
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
      <template #empty>
        <div class="empty">还没有运行过 —— 点右上角「运行」触发一次</div>
      </template>
    </el-table>
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
.empty {
  color: var(--text-3);
  font-size: 12px;
  padding: 12px 0;
}
</style>
