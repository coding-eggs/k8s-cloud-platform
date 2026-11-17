<script setup lang="ts">
/**
 * 集群拓扑预览：HTML/CSS 画，不用 SVG。颜色全部走 Element Plus 语义变量，亮/暗主题自动成立。
 * 只负责画，不做校验——非法组合由页面右侧的问题列表提示。
 *
 * <p><b>为什么不是 SVG</b>：SVG 是等比缩放的。右栏只有 420~680px，而一行放下 3 台机器
 * 的 viewBox 就有 570+，整体缩到 0.7 倍后 13px 的标题在屏幕上只剩 9px，根本读不了。
 * 换成 HTML 格子后字号恒等于 CSS 里写的 px，窄栏只是每行少放一格（grid auto-fit 自动折行）。
 *
 * <p><b>大集群</b>：一行最多画 {@link MAX_COLS} 台，超出的折成「+N 台」一格，
 * 100 个 worker 也只占一格；总数与各角色分布在底部那行。
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

const MAX_COLS = 3

interface Tile {
  key: string
  title: string
  sub: string
  role: string
  /** more = 折叠出来的「+N 台」 */
  more?: boolean
}

function roleLabel(n: InstallNode): string {
  const parts: string[] = []
  if (n.roles.includes('control-plane')) parts.push('control-plane')
  if (n.roles.includes('worker')) parts.push('worker')
  if (n.roles.includes('etcd')) parts.push('etcd')
  if (props.form.etcdMode === 'stacked' && n.roles.includes('control-plane')) parts.push('etcd')
  return parts.join(' · ')
}

function tile(n: InstallNode): Tile {
  return { key: n.hostname, title: clip(n.hostname), sub: n.ip || 'IP 未填', role: roleLabel(n) }
}

/** 一行一个节点；超过 MAX_COLS 时只留前 MAX_COLS-1 个，后面折成一格 */
function toTiles(nodes: InstallNode[], label: string): Tile[] {
  if (nodes.length <= MAX_COLS) return nodes.map(tile)
  const shown = nodes.slice(0, MAX_COLS - 1)
  return [
    ...shown.map(tile),
    {
      key: `more-${label}`,
      title: `+${nodes.length - shown.length} 台`,
      sub: '同角色',
      role: label,
      more: true,
    },
  ]
}

const cpTiles = computed(() => toTiles(cps.value, 'control-plane'))
const workerTiles = computed(() => toTiles(workers.value, 'worker'))
const etcdTiles = computed(() => toTiles(etcd.value, 'etcd'))

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

function clip(v: string, max = 18): string {
  return v.length > max ? `${v.slice(0, max - 1)}…` : v
}

/** 卡片底部那句话：总数与各角色分布，节点很多时一眼能看出规模 */
const caption = computed(() => {
  const parts: string[] = [`共 ${props.form.nodes.length} 台`]
  if (cps.value.length) parts.push(`控制面 ${cps.value.length}`)
  if (workers.value.length) parts.push(`worker ${workers.value.length}`)
  if (etcd.value.length) parts.push(`etcd ${etcd.value.length}`)
  return parts.join(' · ')
})
</script>

<template>
  <div class="topo" role="img" :aria-label="`集群拓扑：${caption}`">
    <template v-if="hasEntry">
      <div class="topo__entry">{{ entryLabel }}</div>
      <div class="topo__link" />
    </template>

    <div v-if="cpTiles.length" class="topo__row">
      <div v-for="t in cpTiles" :key="`cp-${t.key}`" class="topo__box" :title="t.title">
        <div class="topo__title">{{ t.title }}</div>
        <div class="topo__sub">{{ t.sub }}</div>
        <div class="topo__role">{{ t.role }}</div>
      </div>
    </div>

    <div v-if="cpTiles.length && (workerTiles.length || etcdTiles.length)" class="topo__link" />

    <div v-if="workerTiles.length" class="topo__row">
      <div
        v-for="t in workerTiles"
        :key="`w-${t.key}`"
        :class="['topo__box', { 'topo__box--more': t.more }]"
        :title="t.title"
      >
        <div class="topo__title">{{ t.title }}</div>
        <div class="topo__sub">{{ t.sub }}</div>
        <div class="topo__role">{{ t.role }}</div>
      </div>
    </div>

    <div v-if="workerTiles.length && etcdTiles.length" class="topo__link topo__link--dashed" />

    <div v-if="etcdTiles.length" class="topo__row">
      <div
        v-for="t in etcdTiles"
        :key="`e-${t.key}`"
        :class="['topo__box', 'topo__box--etcd', { 'topo__box--more': t.more }]"
        :title="t.title"
      >
        <div class="topo__title">{{ t.title }}</div>
        <div class="topo__sub">{{ t.sub }}</div>
        <div class="topo__role">{{ t.role }}</div>
      </div>
    </div>

    <div class="topo__caption">{{ caption }}</div>
  </div>
</template>

<style scoped>
.topo {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.topo__entry {
  align-self: center;
  max-width: 100%;
  padding: 8px 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 行间连接线：拓扑只表达「谁在上谁在下」，一条居中的竖线足够 */
.topo__link {
  width: 2px;
  height: 16px;
  margin: 0 auto;
  background: var(--el-border-color);
  flex: none;
}
.topo__link--dashed {
  background: repeating-linear-gradient(
    to bottom,
    var(--el-border-color) 0 4px,
    transparent 4px 8px
  );
}
/* 一行节点：auto-fit + 110px 下限，窄栏自动少放一格，字号不跟着缩 */
.topo__row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  justify-items: center;
  gap: 10px;
  width: 100%;
  max-width: 540px;
  margin: 0 auto;
}
.topo__box {
  width: 100%;
  max-width: 200px;
  min-width: 0;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.topo__box--more {
  border-style: dashed;
  border-color: var(--el-border-color-darker);
}
.topo__box--etcd {
  border-color: var(--el-color-warning);
}
.topo__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.topo__sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.topo__role {
  margin-top: 1px;
  font-size: 11px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.topo__caption {
  margin-top: 2px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
