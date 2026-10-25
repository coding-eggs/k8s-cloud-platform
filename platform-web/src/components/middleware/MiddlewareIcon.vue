<script setup lang="ts">
/**
 * 中间件卡片图标。
 *
 * <p>按 chart/中间件名匹配已知图标（Element Plus 单色图标 + 品牌色底），匹配不到用显示名首字
 * 生成字形块。任何条目都有块像样的图标，不会出现空白方块。
 */
import { computed } from 'vue'
import type { Component } from 'vue'
import {
  Coin,
  Connection,
  DataLine,
  FolderOpened,
  Grid,
  Key,
  Odometer,
  Search,
  SetUp,
  Share,
  Timer,
  TrendCharts,
} from '@element-plus/icons-vue'

const props = withDefaults(defineProps<{
  name: string
  chartName?: string
  iconUrl?: string | null
  size?: number
}>(), { size: 48, chartName: '', iconUrl: null })

/** 已知中间件的图标与品牌色。按名字子串匹配，越具体越靠前。 */
const KNOWN: Array<{ keys: string[]; icon: Component; color: string }> = [
  { keys: ['metrics-server'], icon: Odometer, color: '#326ce5' },
  { keys: ['prometheus'], icon: DataLine, color: '#e6522c' },
  { keys: ['thanos'], icon: DataLine, color: '#6b4fbb' },
  { keys: ['grafana'], icon: TrendCharts, color: '#f46800' },
  { keys: ['tempo', 'jaeger', 'zipkin'], icon: Timer, color: '#f4a261' },
  { keys: ['elasticsearch', 'opensearch', 'kibana', 'loki'], icon: Search, color: '#005571' },
  { keys: ['milvus'], icon: DataLine, color: '#00a1ea' },
  { keys: ['redis'], icon: Coin, color: '#d82c20' },
  { keys: ['mysql'], icon: Coin, color: '#00758f' },
  { keys: ['postgres'], icon: Coin, color: '#336791' },
  { keys: ['mongo'], icon: Coin, color: '#13aa52' },
  { keys: ['rabbit'], icon: Share, color: '#ff6600' },
  { keys: ['kafka', 'pulsar'], icon: Share, color: '#231f20' },
  { keys: ['nacos'], icon: Grid, color: '#4a90d9' },
  { keys: ['nginx', 'envoy', 'ingress', 'gateway'], icon: Connection, color: '#009639' },
  { keys: ['istio', 'cilium', 'calico'], icon: Share, color: '#466bb0' },
  { keys: ['cert-manager', 'vault', 'sealed'], icon: Key, color: '#326ce5' },
  { keys: ['tekton', 'argo', 'jenkins', 'gitlab'], icon: SetUp, color: '#fd495c' },
  { keys: ['minio', 'ceph', 'rook', 'longhorn'], icon: FolderOpened, color: '#c72c48' },
  { keys: ['harbor'], icon: FolderOpened, color: '#60b932' },
]

const FALLBACK_COLORS = [
  '#4f8df5', '#7c6cf0', '#e0567a', '#e08b3c', '#2fa27a',
  '#3ba8c4', '#c2559b', '#6a8a3c', '#b06ad6', '#4a7bbd',
]

const matched = computed(() => {
  const key = (props.chartName || props.name || '').toLowerCase()
  return KNOWN.find((k) => k.keys.some((k2) => key.includes(k2))) ?? null
})

const monogram = computed(() => {
  const s = (props.name || props.chartName || '?').trim()
  // 取前两个「像字母」的字符；中文名直接取首字
  const m = s.match(/[A-Za-z0-9]/g)
  if (!m) return s.slice(0, 1).toUpperCase()
  return m.slice(0, 2).join('').toUpperCase()
})

const background = computed(() => {
  if (matched.value) return matched.value.color
  let hash = 0
  for (const ch of props.name || props.chartName || '') hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  return FALLBACK_COLORS[hash % FALLBACK_COLORS.length]
})

const iconSize = computed(() => Math.round(props.size * 0.48))
</script>

<template>
  <div
    class="app-icon"
    :style="{
      width: size + 'px',
      height: size + 'px',
      borderRadius: Math.round(size * 0.26) + 'px',
      background: iconUrl ? 'transparent' : background,
    }"
  >
    <img v-if="iconUrl" :src="iconUrl" alt="" class="app-icon-img" @error="($event.target as HTMLImageElement).style.display = 'none'" />
    <el-icon v-else-if="matched" :size="iconSize" color="#fff">
      <component :is="matched.icon" />
    </el-icon>
    <span v-else class="app-icon-mono" :style="{ fontSize: Math.round(size * 0.36) + 'px' }">
      {{ monogram }}
    </span>
  </div>
</template>

<style scoped>
.app-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  overflow: hidden;
  box-shadow: inset 0 -6px 14px rgba(0, 0, 0, 0.18), 0 2px 6px rgba(0, 0, 0, 0.12);
  user-select: none;
}
.app-icon-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.app-icon-mono {
  color: #fff;
  font-weight: 700;
  letter-spacing: 0.5px;
  line-height: 1;
}
</style>
