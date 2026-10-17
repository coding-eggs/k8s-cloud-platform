<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { prometheusRuleApi } from '@/api'
import type { K8sPrometheusRule, K8sRuleHealth } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'
import { MoreFilled } from '@element-plus/icons-vue'
import YamlBlock from '@/components/YamlBlock.vue'

const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

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

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sPrometheusRule | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

async function openDetail(row: K8sPrometheusRule): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await prometheusRuleApi.getYaml(detail.value.name, ctxParams.value)
    } catch {
      /* 拦截器已提示 */
    }
  }
})

interface DetailRuleRow {
  group: string
  name: string
  kind: string
  expr: string
  health: string | null
  state: string | null
  lastError: string | null
}

/** spec 里的规则 × runtime 的运行状态，按 (组名, 规则名) 对齐。 */
const detailRules = computed<DetailRuleRow[]>(() => {
  const byKey = new Map<string, K8sRuleHealth>()
  for (const h of detail.value?.runtime?.rules ?? []) {
    byKey.set(`${h.group ?? ''}\u0000${h.name ?? ''}`, h)
  }
  const out: DetailRuleRow[] = []
  for (const g of detail.value?.groups ?? []) {
    for (const r of g.rules ?? []) {
      const name = r.alert ?? r.record ?? ''
      const h = byKey.get(`${g.name ?? ''}\u0000${name}`)
      out.push({
        group: g.name ?? '',
        name,
        kind: r.alert ? 'alert' : 'record',
        expr: r.expr ?? '',
        health: h?.health ?? null,
        state: h?.state ?? null,
        lastError: h?.lastError ?? null,
      })
    }
  }
  return out
})

const runtimeSummary = computed<string>(() => {
  const rt = detail.value?.runtime
  if (!rt) return '未探测（后端没拿到运行状态）'
  if (rt.loadState === 'loaded') {
    const rules = rt.rules ?? []
    const firing = rules.filter((r) => r.state === 'firing').length
    const errors = rules.filter((r) => r.health === 'err').length
    return `已加载 · ${rules.length} 条规则${firing ? ` · firing ${firing}` : ''}${errors ? ` · 求值报错 ${errors}` : ''}`
  }
  if (rt.loadState === 'missing') return '未加载 —— Prometheus 的规则列表里没有这条规则'
  return '状态未知（拉不到 Prometheus，或文件名前缀对不上账）'
})

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
      <el-button type="primary" :disabled="!ready" @click="goCreate">创建 PrometheusRule</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 PrometheusRule"
      description="PrometheusRule 由 Prometheus Operator 消费（monitoring.coreos.com/v1 CRD）；集群未安装时操作会提示错误。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !ready" :data="list" stripe>
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
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

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`PrometheusRule · ${detail?.name ?? ''}`" size="720px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <div class="section-title">运行状态</div>
          <div class="runtime-line">{{ runtimeSummary }}</div>

          <div class="section-title">规则</div>
          <el-table :data="detailRules" stripe size="small">
            <el-table-column prop="group" label="组" width="130" />
            <el-table-column prop="name" label="规则名" min-width="180">
              <template #default="{ row }">
                <code class="res-name">{{ row.name }}</code>
                <el-tag size="small" effect="plain" class="kind-tag">{{ row.kind }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="健康" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.health === 'err'" size="small" type="warning" effect="plain" :title="row.lastError">err</el-tag>
                <span v-else-if="row.health === 'ok'" class="yes">ok</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.state === 'firing'" size="small" type="danger" effect="plain">firing</el-tag>
                <el-tag v-else-if="row.state === 'pending'" size="small" type="warning" effect="plain">pending</el-tag>
                <span v-else-if="row.state === 'inactive'" class="muted">inactive</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="expr" label="表达式" min-width="240">
              <template #default="{ row }"><code class="res-name expr">{{ row.expr }}</code></template>
            </el-table-column>
          </el-table>

          <div class="section-title">基本信息</div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="规则总数">{{ detailRules.length }} 条</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <YamlBlock v-if="yamlText" :yaml="yamlText" />
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
.expr {
  word-break: break-all;
}
.kind-tag {
  margin-left: 6px;
}
.runtime-line {
  font-size: 13px;
  color: var(--text-2);
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
