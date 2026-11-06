<script setup lang="ts">
/**
 * 概览顶部「状态卡」一行：四个概览页统一用它排 CountCard。
 *
 * - 有几张卡就等分几列、始终一行（无读权限的卡 v-if 掉，剩下的平分，不留空位）；
 * - 按**容器**宽度（不是视口：侧栏收起 / 展开宽度不同）降级：窄于 960px 排 3 列（6 张 = 3+3），窄于 520px 排 2 列。
 */
</script>

<template>
  <div class="ov-status-wrap">
    <div class="ov-status-cards"><slot /></div>
  </div>
</template>

<style scoped>
.ov-status-wrap {
  container-type: inline-size;
  margin-bottom: 14px;
}
.ov-status-cards {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: 12px;
}
@container (max-width: 960px) {
  .ov-status-cards {
    grid-auto-flow: row;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@container (max-width: 520px) {
  .ov-status-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
