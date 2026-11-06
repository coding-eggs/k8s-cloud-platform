<script setup lang="ts">
/**
 * 总览页顶部的入口区：页头下、数据上，两行紧凑按钮 —— 不占首屏数据的位置。
 *   - 常用操作：按权限过滤由调用方做，这里只渲染；为空整行不渲染；
 *   - 最近访问：传 `face` 才显示，读本视图的访问记录取前 6 条；没有记录整行不渲染
 *     （顶部不放「还没有访问记录」那种空态长句）。
 * 平台 / 租户两个总览共用。
 */
import { computed } from 'vue'
import { readRecentVisits } from '@/composables/useRecentVisits'
import type { NavFace } from '@/nav'

export interface QuickAction {
  title: string
  path: string
  icon: string
}

const props = defineProps<{ actions: QuickAction[]; face?: NavFace }>()

/** 每次挂载读一次 localStorage —— 总览每次导航都会重新挂载，够用（同 RecentVisits） */
const visits = computed(() =>
  props.face ? readRecentVisits().filter((v) => v.face === props.face).slice(0, 6) : [],
)
</script>

<template>
  <div v-if="actions.length || visits.length" class="qa">
    <div v-if="actions.length" class="qa-row">
      <span class="qa-label">常用操作</span>
      <router-link v-for="a in actions" :key="a.path" :to="a.path" class="qa-btn">
        <el-icon class="qa-icon"><component :is="a.icon" /></el-icon>
        <span>{{ a.title }}</span>
      </router-link>
    </div>
    <div v-if="visits.length" class="qa-row">
      <span class="qa-label">最近访问</span>
      <router-link v-for="v in visits" :key="v.path" :to="v.path" class="qa-btn">
        <span>{{ v.title }}</span>
        <span class="qa-sub">{{ v.domainLabel }}</span>
      </router-link>
    </div>
  </div>
</template>

<style scoped>
.qa {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
}
.qa-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.qa-label {
  width: 56px;
  flex-shrink: 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.qa-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel);
  font-size: 13px;
  color: var(--text-1);
  text-decoration: none;
  transition: border-color .15s, color .15s;
}
.qa-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.qa-icon {
  font-size: 15px;
  color: var(--accent);
}
.qa-sub {
  font-size: 11px;
  color: var(--text-3);
}
</style>
