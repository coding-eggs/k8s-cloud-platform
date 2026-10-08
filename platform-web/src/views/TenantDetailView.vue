<script setup lang="ts">
/**
 * 我的租户（Task 20 / Task 18 裁定 #2）：租户上下文成员（无 platform:tenant:read）的自管详情页。
 * - 路由 /tenants/detail，path 无 tenantId（管理域约定：上下文即租户），tenantId 强制取当前租户
 *   （permission store currentTenant，与 token hat 成对写，切换器 reload 后即为新上下文）；
 * - 数据面 = TenantDetailPanel（与 admin 代管轨同一 UI）：member/list + namespace/list 都带
 *   tenantId=当前租户，后端 TenantContextResolver 校验与 hat 一致（不一致 TENANT_MISMATCH）；
 *   namespace/list 服务层还会按 token 租户强制覆盖入参（自管只见本租户）。
 * - 进入方式：顶栏切换器选租户（reload）+ MainLayout「我的租户」入口（currentTenant 且无
 *   platform:tenant:read 时显示）；不做路由自动跳转（裁定 #3「KEEP MINIMAL」）。
 * - admin 也有 tenant:overview:view 时同页可进（此时等价代管但锁当前 hat；要管别的租户走 /tenants）。
 */
import { computed } from 'vue'
import { ElEmpty } from 'element-plus'
import { usePermission } from '@/stores/permission'
import TenantDetailPanel from '@/components/TenantDetailPanel.vue'

const perm = usePermission()

/** 当前租户上下文（token hat）。管理员在平台视图下为 null（本页无事可做）；
 *  非管理员不会停在无租户态（bootstrap 会把成员放进其租户），为 null 即「一个租户都没被分配」 */
const tenant = computed(() => perm.currentTenant)
</script>

<template>
  <div>
    <template v-if="tenant">
      <div class="head">
        <span class="title">我的租户 · {{ tenant.tenantName ?? tenant.tenantId }}</span>
        <el-tag size="small" type="info">租户上下文（自管轨）</el-tag>
      </div>
      <TenantDetailPanel :tenant-id="tenant.tenantId" :tenant-name="tenant.tenantName ?? tenant.tenantId" />
    </template>
    <ElEmpty
      v-else
      :description="perm.isAdmin
        ? '当前处于平台视图：先用顶栏租户切换器进入一个租户'
        : '你还未被分配到任何租户，请联系平台管理员'"
    />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-1);
}
</style>
