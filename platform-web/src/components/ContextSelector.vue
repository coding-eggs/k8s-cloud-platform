<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useResourceContext } from '@/stores/context'

const { state, load, setTenant, setCluster, setNamespace } = useResourceContext()

// 三级级联选项：store 一次加载全量树（租户→集群→命名空间），直接映射，无额外请求
const options = computed(() =>
  state.tenants.map((t) => ({
    value: t.tenantId,
    label: t.name,
    children: t.clusters.map((c) => ({
      value: c.clusterId,
      label: c.clusterName,
      children: c.namespaces.map((ns) => ({ value: ns, label: ns })),
    })),
  })),
)

// 当前选择路径。真级联（无 checkStrictly）：逐级点选，路径累积为「租户 / 集群 / 命名空间」；
// 必须点到叶子才触发 change——没有命名空间的集群本身就是叶子，可停在两级
const path = ref<string[]>([])

function syncFromStore(): void {
  // load() 已按级联数据校验并清空失效项，这里直接映射
  const p: string[] = []
  if (state.tenantId) p.push(state.tenantId)
  if (state.clusterId) p.push(state.clusterId)
  if (state.namespace) p.push(state.namespace)
  path.value = p
}

function onChange(value: unknown): void {
  const v = Array.isArray(value) ? (value as string[]) : []
  setTenant(v[0] ?? null)
  setCluster(v.length > 1 ? v[1] ?? null : null)
  setNamespace(v.length > 2 ? v[2] ?? null : null)
}

onMounted(() => {
  void load().then(syncFromStore)
})
</script>

<template>
  <el-cascader
    v-model="path"
    class="ctx-cascader"
    :options="options"
    placeholder="租户 / 集群 / 命名空间"
    separator=" / "
    clearable
    filterable
    @change="onChange"
  />
</template>

<style>
/* 注意：不能用 scoped——EP cascader 内部用 ElTooltip 包触发器，根节点拿不到父组件 data-v，
   scoped 选择器永远匹配不上（表现为改 width 毫无效果）。类名全局唯一，直接全局写 */
.ctx-cascader {
  width: 360px;
  max-width: 56vw; /* 小屏随顶栏收缩，不挤压右侧控件 */
  /* 顶栏是 flex：空间不足时默认 shrink=1 会按占比压缩各子项，级联框最宽会被压掉大部分，
     表现为改 width 没效果。这里禁止压缩，溢出全部让给面包屑（已有省略号） */
  flex-shrink: 0;
}
</style>
