<script setup lang="ts">
type BadgeType = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

defineProps<{
  label: string
  type?: BadgeType
}>()
</script>

<template>
  <span class="status-badge" :class="`is-${type ?? 'neutral'}`">
    <i class="dot" />
    {{ label }}
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  line-height: 1;
  padding: 3px 9px;
  border-radius: 20px;
  white-space: nowrap;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.is-neutral {
  color: var(--text-3);
  background: var(--panel-hover);
}
.is-success {
  color: var(--success);
  background: color-mix(in srgb, var(--success) 12%, transparent);
}
.is-warning {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 12%, transparent);
}
.is-danger {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 12%, transparent);
}
.is-info {
  color: var(--info);
  background: color-mix(in srgb, var(--info) 12%, transparent);
}
</style>
