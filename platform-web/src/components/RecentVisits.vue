<script setup lang="ts">
/**
 * 最近访问列表（总览页与命令面板空态共用）。
 * 只读快照：每次挂载时读一次 localStorage —— 总览每次导航都会重新挂载，够用。
 */
import { computed } from 'vue'
import { readRecentVisits } from '@/composables/useRecentVisits'
import type { NavFace } from '@/nav'

const props = defineProps<{
  face: NavFace
  limit?: number
}>()

const visits = computed(() => readRecentVisits().filter((v) => v.face === props.face).slice(0, props.limit ?? 8))
</script>

<template>
  <div v-if="visits.length" class="recent">
    <router-link v-for="v in visits" :key="v.path" :to="v.path" class="recent-item">
      <span class="recent-title">{{ v.title }}</span>
      <span class="recent-domain">{{ v.domainLabel }}</span>
    </router-link>
  </div>
  <p v-else class="recent-empty">还没有访问记录 —— 用过几次后这里会出现你最近去过的页面。</p>
</template>

<style scoped>
.recent {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.recent-item {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  text-decoration: none;
  transition: all .15s;
}
.recent-item:hover {
  border-color: var(--accent);
}
.recent-title {
  font-size: 13px;
  color: var(--text-1);
}
.recent-item:hover .recent-title {
  color: var(--accent);
}
.recent-domain {
  font-size: 11px;
  color: var(--text-3);
}
.recent-empty {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
