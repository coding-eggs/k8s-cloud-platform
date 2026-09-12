<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()

const name = computed(() => (route.query.name as string) ?? '')
const clusterId = computed(() => (route.query.clusterId as string) ?? '')

function back(): void {
  router.push('/nodes')
}
</script>

<template>
  <div>
    <PageHeader :title="`节点详情 · ${name}`" description="（建设中）">
      <el-button @click="back">返回</el-button>
    </PageHeader>
    <div class="panel">
      <EmptyState title="节点详情页建设中" :description="`集群：${clusterId} / 节点：${name}`" />
    </div>
  </div>
</template>

<style scoped>
.panel { padding: 8px; }
</style>
