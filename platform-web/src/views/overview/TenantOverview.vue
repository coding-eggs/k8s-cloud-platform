<script setup lang="ts">
/**
 * 租户视图总览：当前租户的工作台。
 *
 * 首屏只打 `/tenant/overview` 一次 —— 后端一次返回合计、逐集群小计、
 * 异常 Pod 名单、不健康工作负载名单与配额三档，够回答「现在有什么需要我管」。
 *
 * 单位口径沿用 TenantOverviewPanel：CPU=核、内存=字节，格式化走 formatMetricValue。
 * null 一律显示「—」，不显示 0（B4 口径）。
 */
import { computed, onMounted, ref } from 'vue'
import { tenantApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import RecentVisits from '@/components/RecentVisits.vue'
import { formatMetricValue } from '@/utils/metrics'
import type { K8sTenantOverview } from '@/types'

const perm = usePermission()

const loading = ref(false)
const loadError = ref(false)
const overview = ref<K8sTenantOverview | null>(null)

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

function fmtCpu(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '核', false)
}
function fmtMem(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '字节', false)
}

const cards = computed(() => {
  const o = overview.value
  return [
    { label: 'CPU 配额占用', value: `${fmtCpu(o?.quotaCpuUsed)} / ${fmtCpu(o?.quotaCpuHard)}` },
    { label: '内存配额占用', value: `${fmtMem(o?.quotaMemUsed)} / ${fmtMem(o?.quotaMemHard)}` },
    { label: '异常 Pod', value: o ? String(o.abnormalPodTotal) : '—' },
    { label: '不健康工作负载', value: o ? String(o.unhealthyWorkloadTotal) : '—' },
  ]
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

      <div class="stat-grid">
        <div v-for="c in cards" :key="c.label" class="panel stat-card">
          <div class="stat-value">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </div>
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
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.stat-card {
  padding: 18px;
}
.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-1);
  line-height: 1.2;
}
.stat-label {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--text-3);
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
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .shortcut-grid { grid-template-columns: repeat(3, 1fr); }
}
</style>
