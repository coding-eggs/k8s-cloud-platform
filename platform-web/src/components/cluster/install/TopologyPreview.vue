<script setup lang="ts">
/**
 * 集群拓扑预览：纯 SVG，不引图库。颜色全部走 Element Plus 语义变量，亮/暗主题自动成立。
 * 只负责画，不做校验——非法组合由页面右侧的问题列表提示。
 */
import { computed } from 'vue'
import type { InstallForm, InstallNode } from '@/utils/clusterInstall/types'

const props = defineProps<{ form: InstallForm }>()

const cps = computed(() => props.form.nodes.filter((n) => n.roles.includes('control-plane')))
// 兼 worker 的控制面只在控制面那一行画一次，角色文字里会带 worker
const workers = computed(() =>
  props.form.nodes.filter((n) => n.roles.includes('worker') && !n.roles.includes('control-plane')),
)
const etcd = computed(() =>
  props.form.etcdMode === 'dedicated' ? props.form.nodes.filter((n) => n.roles.includes('etcd')) : [],
)

const BOX_W = 150
const BOX_H = 54
const GAP = 16
const PAD = 16
const ROW_GAP = 24
const ENTRY_H = 36
const ENTRY_GAP = 20

const hasEntry = computed(() => props.form.apiEntry !== 'direct')
const entryLabel = computed(() => {
  switch (props.form.apiEntry) {
    case 'kubevip-arp':
      return `kube-vip ARP · ${props.form.vip || 'VIP 未填'}`
    case 'kubevip-bgp':
      return `kube-vip BGP · ${props.form.vip || 'VIP 未填'}`
    case 'external-lb':
      return `外部 LB · ${props.form.lbAddress || '地址未填'}:${props.form.lbPort}`
    default:
      return '直连控制面'
  }
})

const maxCols = computed(() => Math.max(1, cps.value.length, workers.value.length, etcd.value.length))
const width = computed(
  () => PAD * 2 + maxCols.value * BOX_W + Math.max(0, maxCols.value - 1) * GAP,
)
const rowCount = computed(
  () => (cps.value.length ? 1 : 0) + (workers.value.length ? 1 : 0) + (etcd.value.length ? 1 : 0),
)
const top = computed(() => PAD + (hasEntry.value ? ENTRY_H + ENTRY_GAP : 0))
const height = computed(
  () => top.value + Math.max(1, rowCount.value) * BOX_H + Math.max(0, rowCount.value - 1) * ROW_GAP + PAD,
)

const cpRow = computed(() => top.value)
const workerRow = computed(() => top.value + (cps.value.length ? BOX_H + ROW_GAP : 0))
const etcdRow = computed(
  () => top.value + (cps.value.length ? BOX_H + ROW_GAP : 0) + (workers.value.length ? BOX_H + ROW_GAP : 0),
)

function xAt(count: number, index: number): number {
  const rowWidth = count * BOX_W + Math.max(0, count - 1) * GAP
  return (width.value - rowWidth) / 2 + index * (BOX_W + GAP)
}

function roleLabel(n: InstallNode): string {
  const parts: string[] = []
  if (n.roles.includes('control-plane')) parts.push('control-plane')
  if (n.roles.includes('worker')) parts.push('worker')
  if (n.roles.includes('etcd')) parts.push('etcd')
  if (props.form.etcdMode === 'stacked' && n.roles.includes('control-plane')) parts.push('etcd')
  return parts.join(' · ')
}

function clip(v: string, max = 20): string {
  return v.length > max ? `${v.slice(0, max - 1)}…` : v
}
</script>

<template>
  <svg :viewBox="`0 0 ${width} ${height}`" class="topo" role="img" aria-label="集群拓扑预览">
    <g v-if="hasEntry">
      <rect
        :x="(width - 280) / 2"
        :y="PAD"
        width="280"
        :height="ENTRY_H"
        rx="8"
        class="topo__entry"
      />
      <text :x="width / 2" :y="PAD + 23" text-anchor="middle" class="topo__entry-text">
        {{ entryLabel }}
      </text>
      <line
        :x1="width / 2"
        :y1="PAD + ENTRY_H"
        :x2="width / 2"
        :y2="cpRow"
        class="topo__link"
      />
    </g>

    <g v-for="(n, i) in cps" :key="`cp-${n.hostname}`">
      <rect :x="xAt(cps.length, i)" :y="cpRow" :width="BOX_W" :height="BOX_H" rx="8" class="topo__box" />
      <text :x="xAt(cps.length, i) + 10" :y="cpRow + 19" class="topo__title">{{ clip(n.hostname) }}</text>
      <text :x="xAt(cps.length, i) + 10" :y="cpRow + 34" class="topo__sub">{{ n.ip || 'IP 未填' }}</text>
      <text :x="xAt(cps.length, i) + 10" :y="cpRow + 47" class="topo__role">{{ roleLabel(n) }}</text>
    </g>

    <line
      v-if="cps.length && workers.length"
      :x1="width / 2"
      :y1="cpRow + BOX_H"
      :x2="width / 2"
      :y2="workerRow"
      class="topo__link"
    />

    <g v-for="(n, i) in workers" :key="`w-${n.hostname}`">
      <rect :x="xAt(workers.length, i)" :y="workerRow" :width="BOX_W" :height="BOX_H" rx="8" class="topo__box" />
      <text :x="xAt(workers.length, i) + 10" :y="workerRow + 19" class="topo__title">{{ clip(n.hostname) }}</text>
      <text :x="xAt(workers.length, i) + 10" :y="workerRow + 34" class="topo__sub">{{ n.ip || 'IP 未填' }}</text>
      <text :x="xAt(workers.length, i) + 10" :y="workerRow + 47" class="topo__role">{{ roleLabel(n) }}</text>
    </g>

    <line
      v-if="etcd.length"
      :x1="width / 2"
      :y1="workerRow + (workers.length ? BOX_H : -ROW_GAP)"
      :x2="width / 2"
      :y2="etcdRow"
      class="topo__link topo__link--dashed"
    />

    <g v-for="(n, i) in etcd" :key="`e-${n.hostname}`">
      <rect :x="xAt(etcd.length, i)" :y="etcdRow" :width="BOX_W" :height="BOX_H" rx="8" class="topo__box topo__box--etcd" />
      <text :x="xAt(etcd.length, i) + 10" :y="etcdRow + 19" class="topo__title">{{ clip(n.hostname) }}</text>
      <text :x="xAt(etcd.length, i) + 10" :y="etcdRow + 34" class="topo__sub">{{ n.ip || 'IP 未填' }}</text>
      <text :x="xAt(etcd.length, i) + 10" :y="etcdRow + 47" class="topo__role">{{ roleLabel(n) }}</text>
    </g>
  </svg>
</template>

<style scoped>
.topo {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
}
.topo__entry {
  fill: var(--el-fill-color-light);
  stroke: var(--el-border-color);
}
.topo__entry-text {
  fill: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 600;
}
.topo__box {
  fill: var(--el-fill-color-light);
  stroke: var(--el-border-color);
}
.topo__box--etcd {
  stroke: var(--el-color-warning);
}
.topo__title {
  fill: var(--el-text-color-primary);
  font-size: 11px;
  font-weight: 600;
}
.topo__sub {
  fill: var(--el-text-color-secondary);
  font-size: 10px;
}
.topo__role {
  fill: var(--el-text-color-secondary);
  font-size: 9.5px;
}
.topo__link {
  stroke: var(--el-border-color);
  stroke-width: 1.5;
}
.topo__link--dashed {
  stroke-dasharray: 4 4;
}
</style>
