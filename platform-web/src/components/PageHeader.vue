<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="page-header px-2.5 pt-1">
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
  min-width: 108px;
}
.ph-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.ph-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 8px;
}
</style>
