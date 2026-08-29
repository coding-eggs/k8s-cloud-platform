<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
</script>

<template>
  <div>
    <PageHeader title="地址池" description="Calico IPPool（集群级 CRD）管理" />
    <div class="panel">
      <EmptyState
        title="该页面建设中（阶段 4）"
        description="crd.projectcalico.org/v1 IPPool 列表（CIDR / 节点选择器 / NAT 出网），创建基础表单，详情含只读 YAML。"
      />
    </div>
  </div>
</template>

<style scoped>
.panel {
  padding: 8px;
}
</style>
