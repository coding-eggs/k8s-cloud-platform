<script setup lang="ts">
/**
 * 总览页头右侧：「更新于 X 前」+ 自动刷新开关 + 刷新按钮。
 *
 * - 自动刷新每 60s 触发一次 `refresh`；页面不可见（切走标签页）时跳过，回到页面时若已过期立即补一次；
 * - 开关是每个浏览器自己的偏好（localStorage，读写都包 try/catch，取不到就当关）；
 * - 相对时间每 15s 重算一次；`updatedAt` 为 null（还没成功过）显示「—」；
 * - `failed`：最近一次刷新失败（页面仍显示上一次的数），文案改成红色的「刷新失败」。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

const props = defineProps<{
  updatedAt: number | null
  loading?: boolean
  failed?: boolean
}>()
const emit = defineEmits<{ refresh: [] }>()

const INTERVAL_MS = 60_000
const STORAGE_KEY = 'platform_overview_auto_refresh'

function readPref(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === '1'
  } catch {
    return false
  }
}
const auto = ref(readPref())
watch(auto, (v) => {
  try {
    localStorage.setItem(STORAGE_KEY, v ? '1' : '0')
  } catch {
    /* 隐私模式等：只是记不住，不影响本次 */
  }
})

const now = ref(Date.now())
const ago = computed(() => {
  if (props.updatedAt == null) return '—'
  const s = Math.max(0, Math.round((now.value - props.updatedAt) / 1000))
  if (s < 10) return '刚刚'
  if (s < 60) return `${s} 秒前`
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  return `${Math.floor(s / 3600)} 小时前`
})

function due(): boolean {
  return props.updatedAt == null || Date.now() - props.updatedAt >= INTERVAL_MS
}
function tick(): void {
  now.value = Date.now()
  if (auto.value && !props.loading && !document.hidden && due()) emit('refresh')
}
function onVisible(): void {
  if (!document.hidden) tick()
}

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  timer = setInterval(tick, 15_000)
  document.addEventListener('visibilitychange', onVisible)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisible)
})
// 刚打开开关且数据已过期：立即刷一次，不用等下一个 15s 刻度
watch(auto, (v) => {
  if (v) tick()
})
</script>

<template>
  <div class="rc">
    <span class="rc-ago" :class="{ failed }">
      <template v-if="failed">刷新失败 · 数据停在 {{ ago }}</template>
      <template v-else>更新于 {{ ago }}</template>
    </span>
    <el-tooltip content="每 60 秒自动刷新（切走页面时暂停）" placement="bottom">
      <span class="rc-auto">
        <el-switch v-model="auto" size="small" />
        <span>自动刷新</span>
      </span>
    </el-tooltip>
    <el-button :icon="Refresh" :loading="loading" @click="emit('refresh')">刷新</el-button>
  </div>
</template>

<style scoped>
.rc {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12.5px;
  color: var(--text-3);
}
.rc-ago.failed {
  color: var(--danger);
}
.rc-auto {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: default;
}
</style>
