<script setup lang="ts">
/**
 * 总览页顶部的常用操作：一行紧凑按钮（页头下、计数卡上），不占首屏数据的位置。
 * 平台 / 租户两个总览共用；按权限过滤由调用方做，这里只负责渲染。为空时整行不渲染。
 */
export interface QuickAction {
  title: string
  path: string
  icon: string
}

defineProps<{ actions: QuickAction[] }>()
</script>

<template>
  <div v-if="actions.length" class="qa-row">
    <span class="qa-label">常用操作</span>
    <router-link v-for="a in actions" :key="a.path" :to="a.path" class="qa-btn">
      <el-icon class="qa-icon"><component :is="a.icon" /></el-icon>
      <span>{{ a.title }}</span>
    </router-link>
  </div>
</template>

<style scoped>
.qa-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}
.qa-label {
  font-size: 12.5px;
  color: var(--text-3);
  margin-right: 4px;
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
</style>
