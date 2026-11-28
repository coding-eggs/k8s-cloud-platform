<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiCodes } from '@/apiCodes'
import { jumpWithContext } from '@/composables/useContextJump'
import { pageCodes } from '@/pageCodes'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import type { AlertBreakdown } from '@/types/overview'
import { alertTargetsOf, segmentsOf, type NsTarget } from './labels'

const props = defineProps<{
  alerts: AlertBreakdown | null
  clusterName: (id: string) => string
}>()
const router = useRouter()
const perm = usePermission()
const ctx = useResourceContext()
const selectedSeverity = ref<string | null>(null)
const targets = computed(() => alertTargetsOf(props.alerts, selectedSeverity.value || undefined))
const unscopedCount = computed(() => (props.alerts?.byNamespace ?? [])
  .reduce((sum, row) => sum + (row.namespace ? 0 : row.total), 0))
const segments = computed(() => segmentsOf('alert', props.alerts).filter(s => s.value > 0))
const canJump = (severity?: string) => perm.has(pageCodes.alert) && alertTargetsOf(props.alerts, severity).length > 0

async function go(target: NsTarget, severity?: string): Promise<void> {
  selectedSeverity.value = null
  const query = { status: 'firing', severity }
  if (!perm.currentTenant && perm.has(apiCodes.alertRead)) {
    await ctx.load()
    const assigned = ctx.state.tenants.some(tenant => tenant.clusters.some(cluster =>
      cluster.clusterId === target.clusterId && cluster.namespaces.includes(target.namespace)))
    if (!assigned) {
      await router.push({ name: 'alerts', query: { ...query, clusterId: target.clusterId, namespace: target.namespace } })
      return
    }
  }
  await jumpWithContext(router, target, { name: 'alerts', query })
}

function open(severity = ''): void {
  const matches = alertTargetsOf(props.alerts, severity || undefined)
  if (matches.length === 1) {
    void go(matches[0]!, severity || undefined)
  } else if (matches.length > 1) {
    selectedSeverity.value = selectedSeverity.value === severity ? null : severity
  }
}
</script>

<template>
  <div class="alert-summary" @keydown.esc="selectedSeverity = null">
    <div class="alert-count">
      <el-button v-if="canJump()" link type="primary" :aria-expanded="selectedSeverity === ''" @click="open()">
        {{ alerts?.total }} 条触发中
      </el-button>
      <span v-else>{{ alerts == null ? '—' : alerts.total === 0 ? '没有触发中的告警' : `${alerts.total} 条触发中` }}</span>
    </div>
    <div v-if="unscopedCount" class="unscoped">{{ unscopedCount }} 条未标注命名空间</div>
    <div v-if="segments.length" class="severities">
      <template v-for="segment in segments" :key="segment.label">
        <el-button
          v-if="segment.query?.severity && canJump(segment.query.severity)"
          link
          :type="segment.tone"
          :aria-expanded="selectedSeverity === segment.query.severity"
          @click="open(segment.query.severity)"
        >{{ segment.label }} {{ segment.value }}</el-button>
        <span v-else>{{ segment.label }} {{ segment.value }}</span>
      </template>
    </div>
    <div v-if="selectedSeverity !== null && targets.length > 1" class="alert-targets" aria-label="选择告警所在命名空间">
      <el-button
        v-for="target in targets"
        :key="`${target.clusterId}/${target.namespace}`"
        link
        type="primary"
        @click="go(target, selectedSeverity || undefined)"
      >{{ clusterName(target.clusterId) }} / {{ target.namespace }} · {{ target.count }}</el-button>
    </div>
  </div>
</template>

<style scoped>
.alert-summary { min-width: 0; }
.alert-count { font-size: 14px; font-weight: 650; }
.alert-count :deep(button) { font-weight: inherit; }
.unscoped { margin-top: 4px; color: var(--text-2); font-size: 12px; }
.severities, .alert-targets { display: flex; flex-wrap: wrap; gap: 4px 12px; margin-top: 5px; font-size: 12px; color: var(--text-2); }
.alert-targets { flex-direction: column; align-items: flex-start; }
.alert-summary :deep(.el-button + .el-button) { margin-left: 0; }
.alert-targets :deep(.el-button) { max-width: 100%; height: auto; text-align: left; white-space: normal; overflow-wrap: anywhere; }
</style>
