<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import YamlBlock from '@/components/YamlBlock.vue'
import { clusterApi, listenerSetApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import type { K8sCluster, K8sCondition, K8sListenerOwner, K8sListenerSet } from '@/types'
import { fmtDate } from '@/utils/format'

/**
 * ListenerSet 只读页（命名空间级、租户域）。
 * - 租户视角：顶栏命名空间里的全部 ListenerSet；
 * - 平台视角（持 platform:listenerset:list-all）：直接列出所选集群全部命名空间的 ListenerSet；
 *   只选集群，用页内下拉（路由 platformContext:'none' 让顶栏不出级联），不需要租户 / 命名空间。
 * 没有创建 / 编辑 / 删除：平台维护的 platform-ingress 由应用发布按端口分配表整份重算，改了也会被覆盖。
 */
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const perm = usePermission()

/** 平台视角：没有当前租户（管理员平台面）且持全局列举码 */
const platformFace = computed(() => !perm.currentTenant && perm.has(apiCodes.listenerSetListAll))

// ---------- 平台视角：页内集群下拉 ----------
const clusters = ref<K8sCluster[]>([])
const platformClusterId = ref('')
async function loadClusters(): Promise<void> {
  try {
    clusters.value = await clusterApi.list()
  } catch {
    clusters.value = []
  }
  const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
  if (first && !platformClusterId.value) platformClusterId.value = first.clusterId
}
/** 能不能查：平台视角看页内集群，租户视角看顶栏三元组 */
const canQuery = computed(() => (platformFace.value ? !!platformClusterId.value : ready.value))

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

const loading = ref(false)
const rows = ref<K8sListenerSet[]>([])
const keyword = ref('')
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter((r) =>
    (r.name ?? '').toLowerCase().includes(kw) || (r.namespace ?? '').toLowerCase().includes(kw))
})

async function refresh(): Promise<void> {
  if (!canQuery.value) return
  loading.value = true
  try {
    // list-all 不看租户（k8s-server 只认平台侧身份）
    rows.value = platformFace.value
      ? await listenerSetApi.listAll({ clusterId: platformClusterId.value })
      : await listenerSetApi.list(ctxParams.value)
  } catch {
    rows.value = [] // 集群没有 ListenerSet CRD 时 404：拦截器已提示
  } finally {
    loading.value = false
  }
}

// ---------- 详情抽屉：概览用列表行（已含全部字段）；YAML 只在本命名空间可取 ----------
const drawerVisible = ref(false)
const detail = ref<K8sListenerSet | null>(null)
const detailTab = ref('info')
const yamlText = ref('')
/** 平台视角跨命名空间时，按名取 YAML 会按顶栏命名空间判边界、落到别的对象上 → 只在同一命名空间开放 */
const yamlAvailable = computed(() => !platformFace.value && !!detail.value && detail.value.namespace === state.namespace)

function openDetail(row: K8sListenerSet): void {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && yamlAvailable.value && !yamlText.value) {
    try {
      yamlText.value = await listenerSetApi.getYaml(detail.value.name, ctxParams.value)
    } catch { /* 拦截器已提示 */ }
  }
})

// ---------- 展示辅助 ----------
function condStatus(conditions: K8sCondition[] | null | undefined, type: string): string | null {
  return (conditions ?? []).find((c) => c.type === type)?.status ?? null
}

/**
 * 列表状态按「每条 listener」判，不按顶层 Programmed：
 * 顶层 Programmed 是父 Gateway 的地址判定（Istio 把 Gateway 的 AddressNotUsable 原样抄给每个 ListenerSet），
 * 只要网关 Service 上有任何一个端口在 istiod 索引里查不到端点，所有 ListenerSet 顶层都会是 False，
 * 但各 listener 自己 Programmed=True、流量照通（2026-11-08 实测）。顶层原因放进 tooltip。
 */
function statusTag(row: K8sListenerSet): { text: string; type: 'success' | 'warning' | 'danger' | 'info'; tip: string } {
  const accepted = condStatus(row.conditions, 'Accepted')
  const top = (row.conditions ?? []).find((c) => c.type === 'Programmed' && c.status === 'False')
  const topTip = top ? `父 Gateway 地址告警（不影响已生效的监听器）：${top.reason ?? ''} ${top.message ?? ''}`.trim() : ''
  if (accepted === 'False') return { text: '未接受', type: 'danger', tip: '' }
  const listeners = row.listeners ?? []
  const statuses = row.listenerStatuses ?? []
  if (listeners.length && statuses.length) {
    let ok = 0
    let conflicted = 0
    for (const l of listeners) {
      const st = statuses.find((s) => s.name === l.name)
      if (condStatus(st?.conditions, 'Conflicted') === 'True') conflicted++
      else if (condStatus(st?.conditions, 'Programmed') === 'True') ok++
    }
    if (conflicted) return { text: '端口冲突', type: 'danger', tip: `${conflicted} 个监听器端口冲突` }
    if (ok === listeners.length) return { text: '已生效', type: 'success', tip: topTip }
    if (ok > 0) return { text: '部分生效', type: 'warning', tip: `${ok}/${listeners.length} 个监听器已生效` }
    return { text: '未生效', type: 'danger', tip: topTip }
  }
  if (accepted == null && !top) return { text: '未知', type: 'info', tip: '' }
  if (top) return { text: '未生效', type: 'danger', tip: topTip }
  return { text: '已生效', type: 'success', tip: '' }
}

function ownerOf(row: K8sListenerSet | null, listenerName?: string | null): K8sListenerOwner | undefined {
  return (row?.owners ?? []).find((o) => o.listenerName === listenerName)
}

/** 每条 listener 的展示行：spec + status + 归属合在一起 */
const listenerRows = computed(() => {
  const d = detail.value
  if (!d) return []
  return (d.listeners ?? []).map((l) => {
    const st = (d.listenerStatuses ?? []).find((s) => s.name === l.name)
    const programmed = condStatus(st?.conditions, 'Programmed')
    const conflicted = condStatus(st?.conditions, 'Conflicted')
    const bad = (st?.conditions ?? []).find((c) =>
      (c.type === 'Programmed' && c.status === 'False') || (c.type === 'Conflicted' && c.status === 'True'))
    return {
      ...l,
      attachedRoutes: st?.attachedRoutes ?? null,
      programmed,
      conflicted,
      reason: bad ? `${bad.reason ?? ''} ${bad.message ?? ''}`.trim() : '',
      owner: ownerOf(d, l.name),
    }
  })
})

function listenerState(r: { programmed: string | null; conflicted: string | null }): { text: string; type: 'success' | 'danger' | 'info' } {
  if (r.conflicted === 'True') return { text: '端口冲突', type: 'danger' }
  if (r.programmed === 'True') return { text: '已生效', type: 'success' }
  if (r.programmed === 'False') return { text: '未生效', type: 'danger' }
  return { text: '状态未知', type: 'info' }
}

/** 应用详情只在租户视角可跳（平台视角跨租户，应用页按顶栏租户判归属） */
function appLink(o?: K8sListenerOwner): string | null {
  if (platformFace.value || !o?.appId || !o.appName) return null
  return o.ownerKind === 'MIDDLEWARE'
    ? `/apps/${encodeURIComponent(o.appId)}`
    : `/applications/${encodeURIComponent(o.appId)}`
}

// ---------- 上下文联动 ----------
onMounted(() => {
  if (platformFace.value) void loadClusters()
  else void load()
  void refresh()
})
watch(platformFace, (p) => { if (p && !clusters.value.length) void loadClusters() })
watch(
  [() => [state.tenantId, state.clusterId, state.namespace], ready, platformFace, platformClusterId],
  () => { if (canQuery.value) void refresh() },
)

const contextDesc = computed(() => {
  if (platformFace.value) return '所选集群 · 全部命名空间（只读）'
  if (!ready.value) return '请在顶栏选择集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}（只读）`
})
</script>

<template>
  <div>
    <PageHeader title="ListenerSet" :description="contextDesc">
      <el-select v-if="platformFace" v-model="platformClusterId" placeholder="选择集群" class="ls-cluster">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-input v-model="keyword" placeholder="按名称 / 命名空间过滤" clearable class="ls-search" />
      <el-button @click="refresh" :disabled="!canQuery">刷新</el-button>
    </PageHeader>

    <el-alert type="info" :closable="false" show-icon class="ls-tip">
      <template #title>
        只读。<code>platform-ingress</code> 由平台维护：应用服务选「网关 HTTP / TCP」后，发布时按端口分配表自动生成，
        集群外用「节点 IP:端口」访问；手工修改会被下一次同步覆盖。
      </template>
    </el-alert>

    <EmptyState
      v-if="canQuery && !filtered.length && !loading"
      :title="platformFace ? '该集群暂无 ListenerSet' : '该命名空间下暂无 ListenerSet'"
      description="ListenerSet 属于 Gateway API（gateway.networking.k8s.io/v1）；集群未安装对应 CRD 时会提示错误。"
    />

    <div v-else class="ls-panel">
      <el-table v-loading="loading || !canQuery" :data="filtered" stripe>
        <el-table-column label="名称" min-width="190">
          <template #default="{ row }">
            <code class="ls-name ls-link" @click="openDetail(row)">{{ row.name }}</code>
            <el-tag v-if="row.platformManaged" size="small" effect="plain" class="ls-managed">平台维护</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="platformFace" label="命名空间" min-width="140">
          <template #default="{ row }"><code class="ls-mono">{{ row.namespace }}</code></template>
        </el-table-column>
        <el-table-column label="父 Gateway" min-width="200">
          <template #default="{ row }">
            <code class="ls-mono">{{ row.parentNamespace ? `${row.parentNamespace}/` : '' }}{{ row.parentName ?? '—' }}</code>
          </template>
        </el-table-column>
        <el-table-column label="监听器" min-width="260">
          <template #default="{ row }">
            <el-tag v-for="(l, i) in (row.listeners ?? [])" :key="i" size="small" effect="plain" class="ls-listener">
              {{ l.protocol ?? '?' }}:{{ l.port ?? '?' }}
            </el-tag>
            <span v-if="!(row.listeners ?? []).length" class="ls-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip :disabled="!statusTag(row).tip" :content="statusTag(row).tip" placement="top">
              <el-tag size="small" :type="statusTag(row).type">{{ statusTag(row).text }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="drawerVisible" :title="`ListenerSet · ${detail?.name ?? ''}`" size="760px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="命名空间"><code class="ls-mono">{{ detail?.namespace }}</code></el-descriptions-item>
            <el-descriptions-item label="父 Gateway">
              <code class="ls-mono">{{ detail?.parentNamespace ? `${detail.parentNamespace}/` : '' }}{{ detail?.parentName ?? '—' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="维护方">{{ detail?.platformManaged ? '平台（应用网关端口，自动生成）' : '用户 / 其他控制器' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>

          <div class="ls-section">监听器</div>
          <el-table :data="listenerRows" stripe size="small">
            <el-table-column prop="name" label="名称" width="100" />
            <el-table-column prop="protocol" label="协议" width="70" />
            <el-table-column prop="port" label="端口" width="80" />
            <el-table-column label="已挂路由" width="84">
              <template #default="{ row }">{{ row.attachedRoutes ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="96">
              <template #default="{ row }">
                <el-tooltip :disabled="!row.reason" :content="row.reason" placement="top">
                  <el-tag size="small" :type="listenerState(row).type">{{ listenerState(row).text }}</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column v-if="detail?.platformManaged" label="归属（应用 / 服务 或 中间件 / 端点）" min-width="220">
              <template #default="{ row }">
                <template v-if="row.owner?.appId">
                  <el-tag v-if="row.owner.ownerKind === 'MIDDLEWARE'" size="small" effect="plain" class="ls-managed">中间件</el-tag>
                  <router-link v-if="appLink(row.owner)" :to="appLink(row.owner)!" class="ls-link">{{ row.owner.appName }}</router-link>
                  <span v-else>{{ row.owner.appName ?? `已删除的${row.owner.ownerKind === 'MIDDLEWARE' ? '实例' : '应用'}（${row.owner.appId}）` }}</span>
                  <span class="ls-muted"> / </span><code class="ls-mono">{{ row.owner.serviceKey }}</code>
                </template>
                <el-tooltip v-else content="端口分配表里没有这个端口：多半是同步失败留下的残留，该命名空间下次发布 / 下线时会被摘掉" placement="top">
                  <el-tag size="small" type="warning">无归属（残留）</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>

          <div class="ls-section">状态条件</div>
          <el-table :data="detail?.conditions ?? []" stripe size="small">
            <el-table-column prop="type" label="Type" width="130" />
            <el-table-column prop="status" label="Status" width="80" />
            <el-table-column prop="reason" label="Reason" min-width="140" />
            <el-table-column prop="message" label="Message" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <template v-if="yamlAvailable">
            <YamlBlock v-if="yamlText" :yaml="yamlText" />
            <div v-else class="ls-muted">加载中…</div>
          </template>
          <div v-else class="ls-muted">平台视角（跨命名空间）不提供 YAML：概览里已有全部字段；需要原文请到租户视角的命名空间「{{ detail?.namespace }}」查看。</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<style>
/* 非 scoped + ls- 前缀：抽屉内容渲染在 teleport 里，scoped 选择器打不到（见 Element Plus 弹窗 scoped CSS 坑） */
.ls-search { width: 220px; margin-right: 8px; }
.ls-cluster { width: 200px; margin-right: 8px; }
.ls-tip { margin-bottom: 12px; }
.ls-panel { padding: 8px; background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.ls-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 13px; }
.ls-link { cursor: pointer; color: var(--accent); }
.ls-link:hover { text-decoration: underline; }
.ls-mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.ls-muted { color: var(--text-3); }
.ls-managed { margin-left: 6px; }
.ls-listener { margin-right: 4px; margin-bottom: 2px; }
.ls-section { font-size: 13px; font-weight: 600; color: var(--text-2); margin: 14px 0 8px; }
</style>
