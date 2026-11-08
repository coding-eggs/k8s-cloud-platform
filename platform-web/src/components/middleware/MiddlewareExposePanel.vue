<script setup lang="ts">
/**
 * 中间件「对外访问」：每个可暴露端点一行，改方式立即生效（中间件没有发布这一步）。
 * 网关 = 应用端口池分到的端口（节点 IP:端口）；NodePort = 平台在 30000-32767 分配的端口。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { middlewareApi } from '@/api'
import { useNodeAddresses } from '@/composables/useNodeAddresses'
import type { MiddlewareExpose, MiddlewareExposeEndpoint, MiddlewareExposeMode } from '@/types'

const props = defineProps<{
  instanceId: string
  clusterId?: string | null
  tenantId?: string
  canWrite: boolean
}>()

const data = ref<MiddlewareExpose | null>(null)
const loading = ref(false)
const saving = ref<string | null>(null)
const addr = useNodeAddresses()

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await middlewareApi.exposeList(props.instanceId, props.tenantId)
  } catch {
    data.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
  void addr.load(props.clusterId ?? null)
})
watch(() => props.instanceId, () => void load())

const MODE_TEXT: Record<MiddlewareExposeMode, string> = { NONE: '不暴露', GATEWAY: '网关', NODEPORT: 'NodePort' }

async function change(ep: MiddlewareExposeEndpoint, mode: MiddlewareExposeMode): Promise<void> {
  if (mode === ep.mode) return
  if (mode !== 'NONE') {
    const warn = data.value?.unauthenticated
      ? '该实例没有开启鉴权，暴露后任何能访问节点 IP 的人都能直接连上。确定要暴露吗？'
      : `暴露后集群外可以通过「节点 IP:端口」访问「${ep.label}」，网关 TCP / NodePort 都不加认证，全靠中间件自己的账号口令。确定吗？`
    try {
      await ElMessageBox.confirm(warn, `对外暴露 · ${MODE_TEXT[mode]}`, { type: 'warning', confirmButtonText: '暴露' })
    } catch {
      return
    }
  }
  saving.value = ep.key
  try {
    data.value = await middlewareApi.exposeUpdate({ instanceId: props.instanceId, endpointKey: ep.key, mode, tenantId: props.tenantId })
    ElMessage.success(mode === 'NONE' ? '已取消对外暴露' : '已生效')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = null
  }
}

function address(ep: MiddlewareExposeEndpoint): string {
  if (!ep.port) return ''
  const host = addr.defaultHost
  if (!host) return `节点 IP:${ep.port}`
  return ep.mode === 'GATEWAY' && ep.protocol === 'HTTP' ? `http://${host}:${ep.port}` : `${host}:${ep.port}`
}

function state(ep: MiddlewareExposeEndpoint): { text: string; type: 'success' | 'danger' | 'info' } | null {
  if (ep.mode !== 'GATEWAY') return null
  if (ep.programmed == null) return { text: '状态未知', type: 'info' }
  if (ep.conflicted) return { text: '端口冲突', type: 'danger' }
  return ep.programmed ? { text: '已生效', type: 'success' } : { text: '未生效', type: 'danger' }
}

const gatewayOff = computed(() => data.value?.gatewayUnavailableReason ?? null)
</script>

<template>
  <el-card shadow="never" class="panel mwx">
    <template #header>
      <span class="sec-title">对外访问</span>
      <el-button link type="primary" class="mwx-refresh" :loading="loading" @click="load">刷新</el-button>
    </template>
    <div v-if="data?.unsupportedReason" class="mwx-muted">{{ data.unsupportedReason }}</div>
    <template v-else-if="data">
      <el-table :data="data.endpoints" size="small" v-loading="loading">
        <el-table-column label="端点" min-width="110">
          <template #default="{ row }">{{ row.label }}</template>
        </el-table-column>
        <el-table-column label="集群内目标" min-width="170">
          <template #default="{ row }"><code class="mwx-mono">{{ row.target }}</code></template>
        </el-table-column>
        <el-table-column label="方式" width="150">
          <template #default="{ row }">
            <el-select :model-value="row.mode" size="small" :disabled="!canWrite || saving === row.key"
                       @update:model-value="(v: MiddlewareExposeMode) => change(row, v)">
              <el-option label="不暴露" value="NONE" />
              <el-option :label="`网关 ${row.protocol}`" value="GATEWAY" :disabled="!!gatewayOff && row.mode !== 'GATEWAY'" />
              <el-option label="NodePort" value="NODEPORT" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="对外地址" min-width="200">
          <template #default="{ row }">
            <template v-if="row.port">
              <a v-if="address(row).startsWith('http')" :href="address(row)" target="_blank" rel="noopener" class="mwx-mono">{{ address(row) }}</a>
              <code v-else class="mwx-mono">{{ address(row) }}</code>
              <el-tooltip v-if="state(row)" :disabled="!row.reason" :content="row.reason ?? ''" placement="top">
                <el-tag size="small" :type="state(row)!.type" effect="light" class="mwx-tag">{{ state(row)!.text }}</el-tag>
              </el-tooltip>
            </template>
            <span v-else class="mwx-muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="mw-form-help">
        改了立即生效。网关端口来自 Istio 组件配置的应用端口池；NodePort 由平台在 30000-32767 里分配，分配后不变。
        <template v-if="gatewayOff">网关方式当前不可用：{{ gatewayOff }}</template>
      </p>
    </template>
  </el-card>
</template>

<style>
/* 非 scoped + mwx- 前缀：下拉与确认框渲染在 teleport 里（见 Element Plus 弹窗 scoped CSS 坑） */
.mwx-refresh { float: right; }
.mwx-mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.mwx-muted { color: var(--text-3); }
.mwx-tag { margin-left: 6px; }
</style>
