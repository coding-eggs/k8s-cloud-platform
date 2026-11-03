<script setup lang="ts">
/**
 * 「新建 Secret」快捷入口：在**新标签页**打开 Secret 编辑页（当前表单不丢），建完回来点「刷新」重新拉候选。
 *
 * 资源页按顶栏上下文取数，新标签页从 localStorage 恢复上下文 —— 所以打开前先把上下文对到应用所在的
 * 租户 / 集群 / 命名空间（与「去工作负载页」同一做法，当前页的顶栏也会跟着变）。
 * 没有 Secret 编辑页权限时不显示入口。
 */
import { useRouter } from 'vue-router'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import { useResourceContext } from '@/stores/context'
import type { SecretCtx } from './useNamespaceSecrets'

const props = defineProps<{ ctx: SecretCtx; loading?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'refresh'): void }>()

const router = useRouter()
const perm = usePermission()
const { state, setTenant, setCluster, setNamespace } = useResourceContext()

function open(): void {
  const c = props.ctx
  if (c.tenantId && state.tenantId !== c.tenantId) setTenant(c.tenantId)
  if (state.clusterId !== c.clusterId) setCluster(c.clusterId)
  if (state.namespace !== c.namespace) setNamespace(c.namespace)
  window.open(router.resolve('/resources/secrets/editor').href, '_blank')
}
</script>

<template>
  <span class="app-secret-link">
    <el-button v-if="perm.has(pageCodes.secret.edit)" link type="primary" :disabled="disabled || !ctx.namespace" @click="open">新建 Secret ↗</el-button>
    <el-button link :loading="loading" :disabled="disabled" @click="emit('refresh')">刷新</el-button>
  </span>
</template>

<style scoped>
.app-secret-link { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
.app-secret-link :deep(.el-button + .el-button) { margin-left: 4px; }
</style>
