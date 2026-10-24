<script setup lang="ts">
/**
 * 「连接方式」弹窗：集群内访问地址 + 凭据位置。
 *
 * <p>两处入口共用：商店创建成功后就地弹（地址不依赖实例是否已就绪）、实例列表每行的「连接」。
 * 打开时才拉数据 —— 列表页上每行都持有地址没有意义。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { middlewareApi } from '@/api'
import type { MiddlewareConnection } from '@/types'

const props = defineProps<{
  instanceId: string | null
  tenantId?: string | null
}>()

const visible = defineModel<boolean>({ required: true })
const connection = ref<MiddlewareConnection | null>(null)
const loading = ref(false)

watch(visible, async (open) => {
  if (!open) return
  connection.value = null
  if (!props.instanceId) return
  loading.value = true
  try {
    connection.value = await middlewareApi.connection(props.instanceId, props.tenantId ?? undefined)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '获取连接方式失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <el-dialog v-model="visible" title="连接方式" width="680px">
    <div v-loading="loading">
      <p class="mw-conn-title">{{ connection?.namespace }} / {{ connection?.instanceName }}</p>
      <div class="mw-conn-block">
        <div class="mw-conn-label">访问地址（集群内）</div>
        <div v-for="l in connection?.endpoints ?? []" :key="l.label" class="mw-conn-line">
          <span class="mw-conn-key">{{ l.label }}</span>
          <code class="mw-conn-value">{{ l.value }}</code>
        </div>
      </div>
      <div v-if="connection?.credentials?.length" class="mw-conn-block">
        <div class="mw-conn-label">凭据</div>
        <div v-for="l in connection?.credentials ?? []" :key="l.label" class="mw-conn-line">
          <span class="mw-conn-key">{{ l.label }}</span>
          <span class="mw-conn-value">{{ l.value }}</span>
        </div>
      </div>
      <p class="mw-conn-note">{{ connection?.note }}</p>
    </div>
    <template #footer>
      <el-button type="primary" @click="visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mw-conn-title { margin: 0 0 12px; color: var(--text-2); font-size: 13px; }
.mw-conn-block { margin-bottom: 14px; }
.mw-conn-label { font-size: 12.5px; color: var(--text-3); margin-bottom: 6px; }
.mw-conn-line { display: flex; gap: 10px; padding: 4px 0; font-size: 13px; }
.mw-conn-key { color: var(--text-3); min-width: 120px; }
.mw-conn-value { color: var(--text-1); word-break: break-all; }
.mw-conn-note { margin: 4px 0 0; font-size: 12px; color: var(--text-3); line-height: 1.6; }
</style>
