<script setup lang="ts">
/**
 * 应用事件：本应用对象的 Kubernetes 事件（排障第一现场：为什么没起来 / 为什么重启）。
 *
 * <p>「只看 Warning」由后端过滤后重新拉取 —— 事件有 200 条上限，在前端筛会漏掉更早的 Warning。
 * 服务维度的筛选在前端做：每条事件都带 serviceKey，不用再请求。
 *
 * <p>事件由 apiserver 保存（默认 1 小时），所以打开页签拉一次 + 手动刷新，不做轮询。
 */
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { applicationApi } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import type { AppEvent, AppServiceRow } from '@/types'

const props = defineProps<{
  appId: string
  tenantId?: string | null
  services: AppServiceRow[]
  /** 外部指定要看的服务（服务行点「事件」跳过来时用）；空 = 全部 */
  serviceKey?: string
}>()

const emit = defineEmits<{
  (e: 'update:serviceKey', value: string): void
}>()

const events = ref<AppEvent[]>([])
const loading = ref(false)
const failed = ref<string | null>(null)
const onlyWarning = ref(false)

/** 服务筛选既能在本组件里改，也能由外层（服务行）指定 */
const serviceKey = computed({
  get: () => props.serviceKey ?? '',
  set: (v: string) => emit('update:serviceKey', v ?? ''),
})

const visible = computed(() => {
  if (!serviceKey.value) return events.value
  return events.value.filter((e) => e.serviceKey === serviceKey.value)
})

const warningCount = computed(
  () => events.value.filter((e) => (e.type ?? '').toUpperCase() === 'WARNING').length,
)

/** 服务下拉：应用里的节点 key；事件里回填不出 serviceKey 的（孤儿对象）归到「未归属」 */
const serviceOptions = computed(() => props.services.map((s) => ({ key: s.key, label: s.key })))

const unattributed = computed(() => events.value.filter((e) => !e.serviceKey).length)

async function load(): Promise<void> {
  loading.value = true
  failed.value = null
  try {
    events.value = await applicationApi.events(props.appId, props.tenantId ?? undefined, onlyWarning.value)
  } catch (e) {
    failed.value = (e as Error).message || '事件读取失败'
    events.value = []
  } finally {
    loading.value = false
  }
}

function toggleWarning(): void {
  void load()
}

onMounted(load)

function isWarning(e: AppEvent): boolean {
  return (e.type ?? '').toUpperCase() === 'WARNING'
}

function fmtRelative(ts?: string | null): string {
  if (!ts) return '—'
  const ms = Date.parse(ts)
  if (!Number.isFinite(ms)) return ts
  const diff = Date.now() - ms
  if (diff < 0) return new Date(ms).toLocaleString('zh-CN', { hour12: false })
  const sec = Math.floor(diff / 1000)
  if (sec < 60) return `${Math.max(1, sec)} 秒前`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  return `${Math.floor(hour / 24)} 天前`
}

function fmtExact(ts?: string | null): string {
  if (!ts) return '—'
  const ms = Date.parse(ts)
  return Number.isFinite(ms) ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : ts
}
</script>

<template>
  <div class="app-events">
    <div class="app-events-toolbar">
      <el-checkbox v-model="onlyWarning" border size="small" @change="toggleWarning">
        只看 Warning
        <span v-if="warningCount" class="app-events-warn-count">{{ warningCount }}</span>
      </el-checkbox>
      <el-select v-model="serviceKey" size="small" placeholder="全部服务" clearable style="width: 180px">
        <el-option v-for="s in serviceOptions" :key="s.key" :label="s.label" :value="s.key" />
      </el-select>
      <span v-if="unattributed" class="app-events-hint">
        {{ unattributed }} 条事件对不上服务（可能是已移除的对象）
      </span>
      <span class="app-events-spacer" />
      <span class="app-events-hint">事件在集群里默认只保留 1 小时</span>
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </div>

    <el-alert v-if="failed" :title="failed" type="warning" :closable="false" show-icon class="app-events-alert" />

    <el-table v-loading="loading" :data="visible" class="app-events-table" empty-text=" ">
      <el-table-column label="时间" width="130">
        <template #default="{ row }">
          <el-tooltip :content="fmtExact(row.lastTimestamp)" placement="top">
            <span class="app-events-time">{{ fmtRelative(row.lastTimestamp) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="isWarning(row) ? 'danger' : 'info'">
            {{ row.type || '—' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="对象" min-width="220">
        <template #default="{ row }">
          <span class="app-events-object">
            <span class="app-events-kind">{{ row.objectKind || '—' }}</span>
            <code>{{ row.objectName || '—' }}</code>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="原因" width="180">
        <template #default="{ row }">
          <span :class="{ 'app-events-reason-warn': isWarning(row) }">{{ row.reason || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="320">
        <template #default="{ row }">
          <el-tooltip :content="row.message || ''" placement="top" :disabled="!row.message">
            <span class="app-events-message">{{ row.message || '—' }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="次数" width="70" align="right">
        <template #default="{ row }">
          <span class="app-events-count">{{ row.count ?? 1 }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <EmptyState
          v-if="!loading"
          title="没有事件"
          :description="onlyWarning
            ? '这个时间窗里没有 Warning 事件；取消勾选可以看 Normal 事件'
            : '集群里的事件默认只保留 1 小时。没有事件不等于没问题 —— 也可以去发布记录看最近一次发布的结论'"
        />
      </template>
    </el-table>
  </div>
</template>

<style scoped>
.app-events { display: flex; flex-direction: column; gap: 10px; }
.app-events-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.app-events-spacer { flex: 1 1 auto; }
.app-events-hint { font-size: 12px; color: var(--text-3); }
.app-events-warn-count {
  margin-left: 6px; padding: 0 6px; border-radius: 999px;
  background: color-mix(in srgb, var(--danger) 16%, transparent); color: var(--danger); font-size: 11px;
}
.app-events-alert { margin-bottom: 2px; }
.app-events-table { width: 100%; }
.app-events-time { color: var(--text-2); white-space: nowrap; }
.app-events-object { display: inline-flex; align-items: baseline; gap: 8px; min-width: 0; }
.app-events-kind {
  flex: 0 0 auto; font-size: 11px; color: var(--text-3);
  padding: 0 6px; border-radius: 4px; background: var(--bg);
}
.app-events-object code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: var(--text-1);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.app-events-reason-warn { color: var(--danger); }
.app-events-message {
  display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; color: var(--text-2);
}
.app-events-count { font-variant-numeric: tabular-nums; color: var(--text-3); }
</style>
