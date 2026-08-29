<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="empty-state">
    <el-icon class="es-icon"><Compass /></el-icon>
    <h3 class="es-title">{{ title }}</h3>
    <p v-if="description" class="es-desc">{{ description }}</p>
    <div v-if="$slots.default" class="es-actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 56px 24px;
  text-align: center;
}
.es-icon {
  font-size: 40px;
  color: var(--text-3);
  opacity: .6;
  margin-bottom: 14px;
}
.es-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-2);
}
.es-desc {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--text-3);
  max-width: 420px;
  line-height: 1.6;
}
.es-actions {
  margin-top: 18px;
  display: flex;
  gap: 8px;
}
</style>
