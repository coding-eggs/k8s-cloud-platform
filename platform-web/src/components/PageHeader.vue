<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="page-header">
    <div class="ph-left">
      <h2 class="ph-title">{{ title }}</h2>
      <p v-if="description" class="ph-desc">{{ description }}</p>
    </div>
    <div class="ph-actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.ph-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
}
.ph-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.ph-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
