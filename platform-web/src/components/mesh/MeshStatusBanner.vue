<script setup lang="ts">
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

/**
 * 服务网格状态横幅（B6）：模块各页顶部一条，展示 istio / ambient / gateway-api 三项状态。
 *
 * <p><b>纯展示组件</b>：数据由调用方用 `useMeshStatus(clusterId)` 取好后经 props 传入 ——
 * 一个页面只该探测一次，横幅不是第二个数据源。
 *
 * <p>措辞口径：探测不到时不武断说"未安装"（未安装 / 未探测 / 集群断开三者在数据上不可区分），
 * 统一写「未探测到」，并把「刷新能力」按钮摆在旁边。
 */
const props = defineProps<{
  /** 是否探测到 Gateway API（模块硬门禁；false 时调用方应禁用 create） */
  hasGatewayApi: boolean
  gatewayApiVersions: string[]
  hasIstio: boolean
  istioAmbient: boolean
  /** ambient 是否已有答案：只有平台侧全量通路（/mesh/status）能给，租户侧沿能力快照派生时为 false */
  ambientKnown: boolean
  /** 有没有任何来源的答案（false = 未探测） */
  probed: boolean
  /** 当前身份能否刷新能力（写操作，独占 platform:cluster:manage） */
  canRefresh: boolean
}>()

const emit = defineEmits<{ (e: 'refresh'): void }>()

/** Istio：未探测 / 未安装 / ambient / sidecar /（已安装但 ambient 未知） */
const istioText = computed(() => {
  if (!props.probed) return '未探测'
  if (!props.hasIstio) return '未安装'
  if (!props.ambientKnown) return '已安装（ambient 未知）'
  return props.istioAmbient ? 'ambient' : 'sidecar'
})
const istioType = computed(() => {
  if (!props.probed || !props.hasIstio) return 'info'
  if (!props.ambientKnown) return 'info'
  return props.istioAmbient ? 'success' : 'warning'
})

const gatewayText = computed(() => {
  if (!props.probed) return '未探测'
  if (!props.hasGatewayApi) return '未安装'
  return props.gatewayApiVersions.length ? props.gatewayApiVersions.join(' / ') : '已安装'
})
const gatewayType = computed(() => {
  if (!props.probed) return 'info'
  return props.hasGatewayApi ? 'success' : 'danger'
})

/** 未探测到 Gateway API → 本模块整组 create 禁用，横幅给出原因 */
const blocked = computed(() => props.probed && !props.hasGatewayApi)
</script>

<template>
  <div class="mesh-banner" :class="{ 'is-blocked': blocked }">
    <span class="seg">
      <span class="label">Istio</span>
      <el-tag size="small" :type="istioType">{{ istioText }}</el-tag>
    </span>
    <span class="sep">·</span>
    <span class="seg">
      <span class="label">Gateway API</span>
      <el-tag size="small" :type="gatewayType">{{ gatewayText }}</el-tag>
    </span>
    <span v-if="blocked" class="hint">
      该集群未探测到 Gateway API（gateway.networking.k8s.io）—— 创建已禁用；若确已安装请刷新能力。
    </span>
    <span v-else-if="!probed" class="hint">尚未探测本集群的 API 能力，创建已按保守处理禁用。</span>
    <span class="spacer" />
    <el-button v-if="canRefresh" size="small" :icon="Refresh" @click="emit('refresh')">刷新能力</el-button>
  </div>
</template>

<style scoped>
.mesh-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  font-size: 13px;
  color: var(--text-2);
}
.mesh-banner.is-blocked {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
}
.seg {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}
.label {
  color: var(--text-3);
}
.sep {
  color: var(--text-3);
  opacity: .6;
}
.hint {
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.spacer {
  margin-left: auto;
}
</style>
