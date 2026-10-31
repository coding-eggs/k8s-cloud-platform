<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { prometheusRuleApi } from '@/api'
import type { K8sPrometheusRule } from '@/types'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import { pageCodes } from '@/pageCodes'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'
import { MoreFilled } from '@element-plus/icons-vue'

const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const perm = usePermission()
/** 「创建 PrometheusRule」= 进入编辑器页，门禁与路由 requiresPerm 同源（只读角色没有它 → 按钮不渲染） */
const canCreate = computed(() => perm.has(pageCodes.prometheusRule.edit))

const loading = ref(false)
const list = ref<K8sPrometheusRule[]>([])

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await prometheusRuleApi.list(ctxParams.value)
  } catch {
    //集群未装 Prometheus Operator 时 CRD 404：拦截器已提示，这里保留空列表
    list.value = []
  } finally {
    loading.value = false
  }
}

// ---------- 创建 / 编辑：跳独立编辑页 ----------
function goCreate(): void {
  router.push({ name: 'prometheusrule-editor' })
}
function goEdit(row: K8sPrometheusRule): void {
  router.push({ name: 'prometheusrule-editor', query: { name: row.name } })
}

// ---------- 删除 ----------
async function onDelete(row: K8sPrometheusRule): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 PrometheusRule「${row.name}」？其中的告警规则将立即停止求值。`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await prometheusRuleApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 列表列的统计 ----------
function ruleCount(row: K8sPrometheusRule): number {
  return (row.groups ?? []).reduce((n, g) => n + (g.rules ?? []).length, 0)
}
function firingCount(row: K8sPrometheusRule): number {
  return (row.runtime?.rules ?? []).filter((r) => r.state === 'firing').length
}
function errCount(row: K8sPrometheusRule): number {
  return (row.runtime?.rules ?? []).filter((r) => r.health === 'err').length
}
function missingTip(): string {
  return 'Prometheus 的规则列表里没有这条规则 —— 多半是集群的 Prometheus 只拾取带特定标签的规则'
    + '（kube-prometheus-stack 默认是 release: ps）。也可能只是还没 reload，稍后刷新看看。'
}

// ---------- 详情：独立详情页（抽屉装不下几十条规则 + 长表达式） ----------
function goDetail(row: K8sPrometheusRule): void {
  router.push({ name: 'prometheusrule-detail', query: { name: row.name } })
}

// ---------- 上下文联动：顶栏 chip 变化时刷新 ----------
onMounted(() => {
  void load()
  void refresh()
})
watch(
  [() => [state.tenantId, state.clusterId, state.namespace], ready],
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
    <PageHeader title="PrometheusRule" :description="contextDesc">
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button v-if="canCreate" type="primary" :disabled="!ready" @click="goCreate">创建 PrometheusRule</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 PrometheusRule"
      description="PrometheusRule 由 Prometheus Operator 消费（monitoring.coreos.com/v1 CRD）；集群未安装时操作会提示错误。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name name-link" @click="goDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="规则" width="90">
          <template #default="{ row }">{{ ruleCount(row) }} 条</template>
        </el-table-column>
        <!-- 这一列是「这条规则到底跑没跑」—— 判据在后端（读 Prometheus 的 /api/v1/rules）。
             建了规则却因为选择器标签不匹配而没被拾取，是完全无声的；这一列让那件事有声音。 -->
        <el-table-column label="运行状态" width="150">
          <template #default="{ row }">
            <template v-if="row.runtime?.loadState === 'loaded'">
              <el-tag v-if="firingCount(row) > 0" size="small" type="danger" effect="plain">firing {{ firingCount(row) }}</el-tag>
              <el-tag v-else-if="errCount(row) > 0" size="small" type="warning" effect="plain">求值报错 {{ errCount(row) }}</el-tag>
              <span v-else class="yes">已加载</span>
            </template>
            <span v-else-if="row.runtime?.loadState === 'missing'" class="warn" :title="missingTip()">未加载</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>

        <el-table-column width="64" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click">
              <el-button link type="primary" :icon="MoreFilled" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="goEdit(row)">编辑</el-dropdown-item>
                  <el-dropdown-item divided style="color: var(--el-color-danger)" @click="onDelete(row)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情已独立成页：/resources/prometheusrules/detail?name=xxx -->
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
.expr {
  word-break: break-all;
}
.muted {
  color: var(--text-3);
}
.yes {
  color: var(--success);
}
.warn {
  color: var(--warning);
  cursor: help;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  margin: 14px 0 8px;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
</style>
