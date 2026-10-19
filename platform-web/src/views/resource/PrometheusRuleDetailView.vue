<script setup lang="ts">
/**
 * PrometheusRule 详情（独立页）。
 *
 * 为什么不做抽屉：一条 CR 常有几十条规则，每行还带表达式（PromQL 可能很长），
 * 720px 的抽屉里既放不下也不能筛选。详情页要能搜、能只挑「有问题的」。
 *
 * 数据 = `GET /prometheusrules/{name}`，其中 `runtime` 是后端读 Prometheus
 * `/api/v1/rules` 后附着上来的运行状态（拉不到就是 null，页面显示「未知」，不影响 CRUD）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { prometheusRuleApi } from '@/api'
import type { K8sPrometheusRule, K8sRuleHealth } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import YamlBlock from '@/components/YamlBlock.vue'
import { fmtDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const { state, ready, currentCluster, load } = useResourceContext()

const name = computed(() => (route.query.name as string) || '')

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

const rule = ref<K8sPrometheusRule | null>(null)
const loading = ref(false)
const loadError = ref(false)
const activeTab = ref('info')
const yamlText = ref('')

async function refresh(): Promise<void> {
  if (!ready.value || !name.value) return
  loading.value = true
  loadError.value = false
  try {
    rule.value = await prometheusRuleApi.get(name.value, ctxParams.value)
  } catch {
    loadError.value = true
    rule.value = null
  } finally {
    loading.value = false
  }
}

async function loadYaml(): Promise<void> {
  if (!name.value || yamlText.value) return
  try {
    yamlText.value = await prometheusRuleApi.getYaml(name.value, ctxParams.value)
  } catch {
    /* 拦截器已提示 */
  }
}

watch(activeTab, (tab) => {
  if (tab === 'yaml') void loadYaml()
})

onMounted(() => {
  void load()
  void refresh()
})
watch([() => state.namespace, () => state.clusterId, ready], () => {
  if (ready.value) {
    yamlText.value = ''
    void refresh()
  }
})

function goBack(): void {
  router.push('/resources/prometheusrules')
}
function goEdit(): void {
  router.push({ name: 'prometheusrule-editor', query: { name: name.value } })
}

// ---------- 规则行：spec × runtime 按 (组名, 规则名) 对齐 ----------
interface RuleRow {
  group: string
  name: string
  kind: 'alert' | 'record'
  expr: string
  /** 持续 for（仅 alert） */
  for_: string
  health: string | null
  state: string | null
  activeAlerts: number | null
  lastError: string | null
}

const allRules = computed<RuleRow[]>(() => {
  const byKey = new Map<string, K8sRuleHealth>()
  for (const h of rule.value?.runtime?.rules ?? []) {
    byKey.set(`${h.group ?? ''}\u0000${h.name ?? ''}`, h)
  }
  const out: RuleRow[] = []
  for (const g of rule.value?.groups ?? []) {
    for (const r of g.rules ?? []) {
      const n = r.alert ?? r.record ?? ''
      const h = byKey.get(`${g.name ?? ''}\u0000${n}`)
      out.push({
        group: g.name ?? '',
        name: n,
        kind: r.alert ? 'alert' : 'record',
        expr: r.expr ?? '',
        for_: r.for_ ?? '',
        health: h?.health ?? null,
        state: h?.state ?? null,
        activeAlerts: h?.activeAlerts ?? null,
        lastError: h?.lastError ?? null,
      })
    }
  }
  return out
})

// ---------- 筛选 ----------
const keyword = ref('')
const onlyProblem = ref(false)

const rows = computed<RuleRow[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  return allRules.value.filter((r) => {
    if (onlyProblem.value && !(r.state === 'firing' || r.health === 'err')) return false
    if (!kw) return true
    return r.name.toLowerCase().includes(kw)
        || r.expr.toLowerCase().includes(kw)
        || r.group.toLowerCase().includes(kw)
  })
})

// ---------- 摘要 ----------
const firingCount = computed(() => allRules.value.filter((r) => r.state === 'firing').length)
const errCount = computed(() => allRules.value.filter((r) => r.health === 'err').length)
const alertCount = computed(() => allRules.value.filter((r) => r.kind === 'alert').length)
const recordCount = computed(() => allRules.value.filter((r) => r.kind === 'record').length)

/** 三态文案：loaded / missing / unknown / null（未探测） */
const loadState = computed(() => rule.value?.runtime?.loadState ?? null)
const loadStateText = computed(() => {
  switch (loadState.value) {
    case 'loaded':
      return '已加载 —— Prometheus 的规则列表里有这条 CR'
    case 'missing':
      return '未加载 —— 规则列表里找不到它。多半是集群的 Prometheus 只拾取带特定标签的规则'
        + '（kube-prometheus-stack 默认是 release: ps），也可能只是还没 reload'
    case 'unknown':
      return '对不上账 —— Prometheus 里有两个 CR 的文件名前缀相同，平台不猜归属'
    default:
      return '未知 —— 没探测到（集群没配 prometheus_url，或 Prometheus 不可达）'
  }
})
const loadStateType = computed(() => {
  switch (loadState.value) {
    case 'loaded':
      return 'success'
    case 'missing':
      return 'warning'
    case 'unknown':
      return 'info'
    default:
      return 'info'
  }
})
</script>

<template>
  <div class="res-detail">
    <PageHeader :title="`PrometheusRule · ${name || '—'}`" :description="currentCluster?.clusterName">
      <el-button @click="goBack">返回列表</el-button>
      <el-button :disabled="!ready" @click="refresh">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="goEdit">编辑</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏选择租户、集群、命名空间。" />

    <EmptyState
      v-else-if="loadError"
      title="加载 PrometheusRule 失败"
      description="它可能已被删除，或集群未安装 Prometheus Operator（CRD 不存在）。"
    >
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else v-loading="loading" class="detail-body">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="概览" name="info">
          <!-- 运行状态：这是整页最重要的一行 —— 「规则到底跑没跑」 -->
          <el-alert
            :type="loadStateType"
            :closable="false"
            show-icon
            class="state-bar"
            :title="loadStateText"
          />

          <el-descriptions :column="4" border class="summary">
            <el-descriptions-item label="规则总数">{{ allRules.length }}</el-descriptions-item>
            <el-descriptions-item label="告警规则">{{ alertCount }}</el-descriptions-item>
            <el-descriptions-item label="记录规则">{{ recordCount }}</el-descriptions-item>
            <el-descriptions-item label="状态分布">
              <el-tag v-if="firingCount" size="small" type="danger" effect="plain">firing {{ firingCount }}</el-tag>
              <el-tag v-if="errCount" size="small" type="warning" effect="plain" class="tag-gap">报错 {{ errCount }}</el-tag>
              <span v-if="!firingCount && !errCount" class="muted">无异常</span>
            </el-descriptions-item>
            <el-descriptions-item label="命名空间">{{ rule?.namespace ?? state.namespace }}</el-descriptions-item>
            <el-descriptions-item label="标签数">{{ Object.keys(rule?.labels ?? {}).length }}</el-descriptions-item>
            <el-descriptions-item label="创建时间" :span="2">{{ fmtDate(rule?.creationTime) }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-title">规则</div>
          <div class="filter-row">
            <el-input v-model="keyword" placeholder="搜索组 / 规则名 / 表达式" clearable style="width: 320px" />
            <el-checkbox v-model="onlyProblem">只看有问题的（firing / 求值报错）</el-checkbox>
            <span class="muted">显示 {{ rows.length }} / {{ allRules.length }} 条</span>
          </div>

          <el-table :data="rows" stripe size="small" class="rule-table" empty-text="没有匹配的规则">
            <el-table-column prop="group" label="组" width="150" show-overflow-tooltip />
            <el-table-column label="规则名" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <code class="res-name">{{ row.name }}</code>
                <el-tag size="small" effect="plain" class="kind-tag">{{ row.kind }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="健康" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.health === 'err'" size="small" type="warning" effect="plain">err</el-tag>
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
            <el-table-column label="活跃" width="80">
              <template #default="{ row }">
                <span v-if="row.activeAlerts !== null">{{ row.activeAlerts }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="for" width="90">
              <template #default="{ row }">
                <span v-if="row.for_">{{ row.for_ }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="表达式" min-width="320">
              <template #default="{ row }"><code class="res-name expr">{{ row.expr }}</code></template>
            </el-table-column>
          </el-table>

          <!-- 求值报错单独列一块：这是「expr 写错了」唯一能看见原文的地方 -->
          <template v-if="errCount">
            <div class="section-title">求值报错（{{ errCount }}）</div>
            <div v-for="r in allRules.filter((x) => x.health === 'err')" :key="`${r.group}/${r.name}`" class="err-block">
              <div class="err-head">
                <code class="res-name">{{ r.group }} / {{ r.name }}</code>
              </div>
              <pre class="err-text">{{ r.lastError || '（Prometheus 没给原文）' }}</pre>
            </div>
          </template>
        </el-tab-pane>

        <el-tab-pane label="YAML（只读）" name="yaml" lazy>
          <YamlBlock v-if="yamlText" :yaml="yamlText" />
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.detail-body {
  padding: 0 4px 32px;
}
.state-bar {
  margin-bottom: 12px;
}
.summary {
  margin-bottom: 4px;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  margin: 18px 0 10px;
}
.filter-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 10px;
}
.rule-table {
  width: 100%;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.expr {
  word-break: break-all;
}
.kind-tag,
.tag-gap {
  margin-left: 6px;
}
.muted {
  color: var(--text-3);
}
.yes {
  color: var(--success);
}
.err-block {
  border-left: 3px solid var(--warning);
  padding: 6px 10px;
  margin-bottom: 10px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.err-head {
  margin-bottom: 4px;
}
.err-text {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-2);
}
</style>
