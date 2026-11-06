<script setup lang="ts">
/**
 * 租户视图总览：当前租户的工作台。
 *
 * 首屏两次请求：`/tenant/overview`（合计、异常 Pod / 不健康工作负载名单、配额三档）+
 * `/tenant/overview/summary`（应用 / 中间件 / CI / 告警计数，无读权限的块为 null）。
 *
 * 单位口径沿用 TenantOverviewPanel：CPU=核、内存=字节（WaterBar 内部走 formatMetricValue）。
 * null 一律显示「—」，不显示 0（B4 口径）。
 */
import { computed, onMounted, ref } from 'vue'
import { tenantApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import RecentVisits from '@/components/RecentVisits.vue'
import type { K8sTenantOverview } from '@/types'
import CountCard from '@/components/overview/CountCard.vue'
import WaterBar from '@/components/overview/WaterBar.vue'
import { podSegments, segmentsOf, type Segment } from '@/components/overview/labels'
import type { ScopeSummary } from '@/types/overview'

const perm = usePermission()

const loading = ref(false)
const loadError = ref(false)
const overview = ref<K8sTenantOverview | null>(null)
const summary = ref<ScopeSummary | null>(null)

const tenantId = computed(() => perm.currentTenant?.tenantId ?? null)
const tenantName = computed(() => perm.currentTenant?.tenantName ?? perm.currentTenant?.tenantId ?? '当前租户')

/**
 * `/tenant/overview` 的授权是 ANY-of `tenant:overview:view` / `platform:allocation:list`。
 * 自定义的最小权限角色（例如只持 `tenant:page:workload.list`）两者都没有 —— 不门控就会
 * 每次进总览吃一个 403 红条，还会被下面的告警误说成「可稍后重试」。这里先判能力再发请求。
 */
const canView = computed(() => perm.has(apiCodes.tenantOverviewView) || perm.has(apiCodes.allocationList))

async function load(): Promise<void> {
  const target = tenantId.value
  if (!target || !canView.value) return
  loading.value = true
  loadError.value = false
  // 计数卡（应用 / 中间件 / CI / 告警）独立降级：失败只是不显示这几张卡；先清空，免得切租户时残留上一个租户的数
  summary.value = null
  tenantApi.summary(target)
    .then((s) => { if (target === tenantId.value) summary.value = s })
    .catch(() => { if (target === tenantId.value) summary.value = null })
  try {
    const data = await tenantApi.overview({ tenantId: target })
    if (target !== tenantId.value) return // 期间切了租户：丢弃这次结果
    overview.value = data
  } catch {
    if (target === tenantId.value) {
      overview.value = null
      loadError.value = true
    }
  } finally {
    if (target === tenantId.value) loading.value = false
  }
}


/** 工作负载 / Pod 卡来自 /tenant/overview；应用 / 中间件 / CI / 告警卡来自 /tenant/overview/summary（无读权限的块为 null → 不渲染） */
const workloadTotal = computed(() => {
  const t = overview.value?.total
  return t ? t.deploymentCount + t.statefulsetCount + t.daemonsetCount : null
})
const workloadSegments = computed<Segment[]>(() =>
  overview.value ? [{ label: '不健康', value: overview.value.unhealthyWorkloadTotal, tone: 'danger' }] : [],
)
const podSegs = computed<Segment[]>(() => {
  const t = overview.value?.total
  if (!t) return []
  const running = t.podRunning ?? 0
  const pending = t.podPending ?? 0
  const succeeded = t.podSucceeded ?? 0
  return podSegments({ total: t.podCount, running, pending, succeeded, abnormal: Math.max(0, t.podCount - running - pending - succeeded) })
})

/** 异常 reason 取前 3 个（后端已按严重度降序） */
const podReasons = computed(() => {
  const counts = overview.value?.abnormalPodReasonCounts ?? {}
  return Object.entries(counts)
    .slice(0, 3)
    .map(([reason, count]) => `${reason || '未知'} ×${count}`)
})

const unhealthy = computed(() =>
  (overview.value?.unhealthyWorkloads ?? [])
    .slice(0, 3)
    .map((w) => `${w.name ?? '—'}（${w.kind ?? '—'}，就绪 ${w.readyReplicas}/${w.replicas}）`),
)

const quotaGaps = computed(() => {
  const missing = overview.value?.namespacesWithoutQuota ?? []
  const partial = overview.value?.namespacesPartialQuota ?? []
  const parts: string[] = []
  if (missing.length) parts.push(`未配配额：${missing.slice(0, 3).join('、')}${missing.length > 3 ? ` 等 ${missing.length} 个` : ''}`)
  if (partial.length) parts.push(`配额不完整：${partial.slice(0, 3).join('、')}${partial.length > 3 ? ` 等 ${partial.length} 个` : ''}`)
  return parts
})

const quickActions = computed(() =>
  [
    { title: '新建应用', path: '/applications', icon: 'Connection', perm: pageCodes.application },
    { title: '新建中间件', path: '/apps', icon: 'Grid', perm: pageCodes.app },
    { title: '跑流水线', path: '/pipelines', icon: 'Operation', perm: pageCodes.pipeline },
    { title: '看日志', path: '/observability/logs', icon: 'Document', perm: pageCodes.observability.logs },
    { title: '查链路', path: '/mesh/traces', icon: 'Timer', perm: pageCodes.mesh.traceTenant },
  ].filter((a) => perm.has(a.perm)),
)

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">{{ tenantName }} 工作台</h2>
        <p class="ph-desc">
          <template v-if="perm.isAdmin">当前代管 {{ tenantName }} —— 这里的数字只统计该租户</template>
          <template v-else>你的资源概况、需要关注的问题与常用入口</template>
        </p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <div v-if="!canView" class="panel no-perm">
      当前角色没有查看租户概览的权限 —— 下面的常用操作与最近访问照常可用。
    </div>

    <template v-if="canView">
      <el-alert
        v-if="loadError"
        class="load-error"
        type="warning"
        :closable="false"
        title="概览数据暂时取不到"
        description="可能是集群不可用或聚合未就绪。其余页面不受影响，可稍后重试。"
      />

      <div class="ov-cards6">
        <CountCard v-if="summary?.applications" title="应用" :total="summary.applications.total" :segments="segmentsOf('app', summary.applications)" />
        <CountCard v-if="summary?.middleware" title="中间件" :total="summary.middleware.total" :segments="segmentsOf('middleware', summary.middleware)" />
        <CountCard title="工作负载" :total="workloadTotal" :segments="workloadSegments" />
        <CountCard title="Pod" :total="overview?.total?.podCount ?? null" :segments="podSegs" />
        <CountCard v-if="summary?.pipelines" title="CI（最近一次运行）" :total="summary.pipelines.total" :segments="segmentsOf('pipeline', summary.pipelines)" />
        <CountCard v-if="summary?.alerts" title="告警触发中" :total="summary.alerts.total" :segments="segmentsOf('alert', summary.alerts)" />
      </div>
      <div class="panel ov-water2">
        <WaterBar label="配额 CPU" :used="overview?.quotaCpuUsed" :total="overview?.quotaCpuHard" unit="core" />
        <WaterBar label="配额内存" :used="overview?.quotaMemUsed" :total="overview?.quotaMemHard" unit="byte" />
      </div>

      <h3 class="section-title">需要关注</h3>
      <div class="panel watch-panel">
        <template v-if="podReasons.length || unhealthy.length || quotaGaps.length">
          <div v-if="podReasons.length" class="watch-row">
            <span class="watch-label">异常 Pod</span>
            <span class="watch-value">{{ podReasons.join(' · ') }}</span>
          </div>
          <div v-if="unhealthy.length" class="watch-row">
            <span class="watch-label">不健康工作负载</span>
            <span class="watch-value">{{ unhealthy.join(' · ') }}</span>
          </div>
          <div v-if="quotaGaps.length" class="watch-row">
            <span class="watch-label">配额缺口</span>
            <span class="watch-value">{{ quotaGaps.join(' · ') }}</span>
          </div>
        </template>
        <p v-else-if="overview" class="watch-ok">一切正常 —— 没有异常 Pod、不健康工作负载或配额缺口。</p>
        <p v-else-if="!loading" class="watch-ok">暂无数据。</p>
      </div>
    </template>

    <h3 class="section-title">常用操作</h3>
    <div class="shortcut-grid">
      <router-link v-for="a in quickActions" :key="a.path" :to="a.path" class="panel shortcut-card">
        <el-icon class="sc-icon"><component :is="a.icon" /></el-icon>
        <span class="sc-title">{{ a.title }}</span>
      </router-link>
    </div>

    <h3 class="section-title">最近访问</h3>
    <RecentVisits face="tenant" />
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.ph-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
}
.ph-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.load-error {
  margin-bottom: 14px;
}
.no-perm {
  padding: 18px;
  font-size: 13px;
  color: var(--text-3);
}
.ov-cards6 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.ov-water2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  padding: 16px 18px;
  margin-top: 14px;
}
.section-title {
  margin: 26px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-2);
}
.watch-panel {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.watch-row {
  display: flex;
  gap: 14px;
  font-size: 13px;
}
.watch-label {
  width: 96px;
  flex-shrink: 0;
  color: var(--text-3);
}
.watch-value {
  color: var(--text-1);
  line-height: 1.6;
}
.watch-ok {
  margin: 0;
  font-size: 13px;
  color: var(--text-3);
}
.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
}
.shortcut-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  text-decoration: none;
  transition: all .15s;
}
.shortcut-card:hover {
  border-color: var(--accent);
  transform: translateY(-2px);
  box-shadow: 0 6px 18px color-mix(in srgb, var(--accent) 18%, transparent);
}
.sc-icon {
  font-size: 20px;
  color: var(--accent);
  flex-shrink: 0;
}
.sc-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
@media (max-width: 1200px) {
  .shortcut-grid { grid-template-columns: repeat(3, 1fr); }
}
</style>
