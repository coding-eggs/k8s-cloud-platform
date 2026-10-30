<script setup lang="ts">
/**
 * 总览：按当前视图分发到两个互不复用的实现。
 * 平台视图 = 平台管理员的健康与入口；租户视图 = 租户成员的工作台。
 * 视图判定口径与 MainLayout / nav.ts 完全一致（`perm.currentTenant`）。
 */
import { computed } from 'vue'
import { usePermission } from '@/stores/permission'
import PlatformOverview from '@/views/overview/PlatformOverview.vue'
import TenantOverview from '@/views/overview/TenantOverview.vue'

const perm = usePermission()
const isPlatformView = computed(() => perm.currentTenant == null)
</script>

<template>
  <PlatformOverview v-if="isPlatformView" />
  <TenantOverview v-else />
</template>
