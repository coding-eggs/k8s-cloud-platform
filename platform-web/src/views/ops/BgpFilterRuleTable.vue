<script setup lang="ts">
import type { BgpFilterRule } from '@/types'

defineProps<{ rules: BgpFilterRule[] }>()

function prefixLength(r: BgpFilterRule): string {
  const min = r.prefixLengthMin ?? ''
  const max = r.prefixLengthMax ?? ''
  if (min === '' && max === '') return '—'
  return `${min || '?'}–${max || '?'}`
}
function opsText(op: NonNullable<BgpFilterRule['operations']>[number]): string {
  const parts: string[] = []
  if (op.addCommunity != null) parts.push(`+community ${op.addCommunity}`)
  if (op.prependAsPath?.length) parts.push(`prepend AS [${op.prependAsPath.join(', ')}]`)
  if (op.setPriority != null) parts.push(`priority=${op.setPriority}`)
  return parts.length ? parts.join('；') : '—'
}
</script>

<template>
  <el-table :data="rules" size="small" stripe>
    <el-table-column label="CIDR" min-width="150">
      <template #default="{ row }"><code class="mono">{{ row.cidr ?? '—' }}</code></template>
    </el-table-column>
    <el-table-column label="前缀长度" width="100">
      <template #default="{ row }"><span class="muted">{{ prefixLength(row) }}</span></template>
    </el-table-column>
    <el-table-column label="匹配方式" width="100">
      <template #default="{ row }"><span class="muted">{{ row.matchOperator ?? '—' }}</span></template>
    </el-table-column>
    <el-table-column label="对端类型" width="90">
      <template #default="{ row }"><span class="muted">{{ row.peerType ?? '—' }}</span></template>
    </el-table-column>
    <el-table-column label="接口" min-width="120" show-overflow-tooltip>
      <template #default="{ row }"><code class="mono">{{ row.iface ?? '—' }}</code></template>
    </el-table-column>
    <el-table-column label="Communities" min-width="160">
      <template #default="{ row }"><span class="muted">{{ (row.communityValues ?? []).join(', ') || '—' }}</span></template>
    </el-table-column>
    <el-table-column label="AS Path 前缀" min-width="140">
      <template #default="{ row }"><span class="muted">{{ (row.asPathPrefix ?? []).join(', ') || '—' }}</span></template>
    </el-table-column>
    <el-table-column label="优先级" width="80">
      <template #default="{ row }"><span class="muted">{{ row.priority ?? '—' }}</span></template>
    </el-table-column>
    <el-table-column label="动作" width="90">
      <template #default="{ row }">
        <el-tag size="small" :type="row.action === 'Reject' ? 'danger' : 'success'">{{ row.action ?? '—' }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="附加操作" min-width="200">
      <template #default="{ row }">
        <div v-if="row.operations?.length" class="ops-wrap">
          <div v-for="(op, i) in row.operations" :key="i" class="muted">{{ opsText(op) }}</div>
        </div>
        <span v-else class="muted">—</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
.ops-wrap { display: flex; flex-direction: column; gap: 2px; font-size: 12.5px; line-height: 1.5; }
</style>
