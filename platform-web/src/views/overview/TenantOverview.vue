<script setup lang="ts">
/**
 * 租户视图总览 = 我的租户（原「组织 › 我的租户」并入，2026-11-06）。
 *
 * 结构：页头 → 常用操作 / 最近访问（两行紧凑按钮）→ TenantDetailPanel 三页签
 *   - 概览：TenantOverviewPanel（计数卡 / 配额 / 需要关注 / 曲线 / 命名空间明细）；
 *   - 命名空间分配 / 成员与角色：只对持 `tenant:page:tenant.self` 的人显示（沿用原「我的租户」页的门槛），
 *     只能看概览的最小权限角色不会多看到成员信息。
 *
 * 概览能力 = ANY-of `tenant:overview:view` / `platform:allocation:list`（同 /tenant/overview 的授权）；
 * 两者都没有时不发请求，只显示无权提示 —— 否则每次进总览吃一个 403 红条。
 */
import { computed } from 'vue'
import { apiCodes } from '@/apiCodes'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import TenantDetailPanel from '@/components/TenantDetailPanel.vue'
import QuickActions from '@/components/overview/QuickActions.vue'

const perm = usePermission()

const tenant = computed(() => perm.currentTenant)
const tenantName = computed(() => tenant.value?.tenantName ?? tenant.value?.tenantId ?? '当前租户')

const canView = computed(() => perm.has(apiCodes.tenantOverviewView) || perm.has(apiCodes.allocationList))
const canManage = computed(() => perm.has(pageCodes.tenant.self))

const quickActions = computed(() =>
  [
    { title: '新建应用', path: '/applications', icon: 'Connection', perm: pageCodes.application },
    { title: '新建中间件', path: '/apps', icon: 'Grid', perm: pageCodes.app },
    { title: '跑流水线', path: '/pipelines', icon: 'Operation', perm: pageCodes.pipeline },
    { title: '看日志', path: '/observability/logs', icon: 'Document', perm: pageCodes.observability.logs },
    { title: '查链路', path: '/mesh/traces', icon: 'Timer', perm: pageCodes.mesh.traceTenant },
  ].filter((a) => perm.has(a.perm)),
)
</script>

<template>
  <div>
    <div class="page-header">
      <h2 class="ph-title">{{ tenantName }}</h2>
      <p class="ph-desc">
        <template v-if="perm.isAdmin">当前代管 {{ tenantName }} —— 这里的数字只统计该租户</template>
        <template v-else>你的租户概况、命名空间与成员</template>
      </p>
    </div>

    <QuickActions :actions="quickActions" face="tenant" />

    <TenantDetailPanel
      v-if="tenant"
      :tenant-id="tenant.tenantId"
      :tenant-name="tenantName"
      :manage-tabs="canManage"
      :overview-allowed="canView"
    />
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 12px;
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
</style>
