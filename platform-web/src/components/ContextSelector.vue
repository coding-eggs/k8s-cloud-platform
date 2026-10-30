<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'

const { state, load, ensureDefaults, setTenant, setCluster, setNamespace } = useResourceContext()
const perm = usePermission()

/**
 * 租户视图下只显示两级（集群 / 命名空间）。
 *
 * `/context` 对带租户帽的 token **只返回本租户**，所以租户级那一列永远只有一个选项 ——
 * 占一列宽度、零信息量，还让"换命名空间"多一步。平台视图（代管轨）保留三级：
 * 那里的租户级是代管筛选器（选看哪个租户的资源），不能去掉。
 */
const twoLevel = computed(() => perm.currentTenant != null)

// 级联选项：store 一次加载全量树（租户→集群→命名空间），直接映射，无额外请求。
// 两级模式把本租户的集群列表直接提到顶层。
const options = computed(() => {
  if (twoLevel.value) {
    const tenant =
      state.tenants.find((t) => t.tenantId === perm.currentTenant?.tenantId) ?? state.tenants[0]
    return (tenant?.clusters ?? []).map((c) => ({
      value: c.clusterId,
      label: c.clusterName,
      children: c.namespaces.map((ns) => ({ value: ns, label: ns })),
    }))
  }
  return state.tenants.map((t) => ({
    value: t.tenantId,
    label: t.name,
    children: t.clusters.map((c) => ({
      value: c.clusterId,
      label: c.clusterName,
      children: c.namespaces.map((ns) => ({ value: ns, label: ns })),
    })),
  }))
})

// 当前选择路径（真级联，无 checkStrictly）：两级模式下只是少一级租户
const path = ref<string[]>([])

function syncFromStore(): void {
  // load() 已按级联数据校验并清空失效项，这里直接映射
  const p: string[] = []
  if (!twoLevel.value && state.tenantId) p.push(state.tenantId)
  if (state.clusterId) p.push(state.clusterId)
  if (state.namespace) p.push(state.namespace)
  path.value = p
}

function onChange(value: unknown): void {
  const v = Array.isArray(value) ? (value as string[]) : []
  if (twoLevel.value) {
    setCluster(v[0] ?? null)
    setNamespace(v[1] ?? null)
    return
  }
  setTenant(v[0] ?? null)
  setCluster(v.length > 1 ? v[1] ?? null : null)
  setNamespace(v.length > 2 ? v[2] ?? null : null)
}

onMounted(() => {
  // 选择器每次进资源页都会重新挂载：load（或已加载）后补默认缺级，
  // 顶栏自动落到首个可用 租户/集群/命名空间，无需手动选择
  void load().then(() => {
    ensureDefaults()
    syncFromStore()
  })
})
</script>

<template>
  <el-cascader
    v-model="path"
    :class="['ctx-cascader', { 'is-two-level': twoLevel }]"
    :options="options"
    :placeholder="twoLevel ? '集群 / 命名空间' : '租户 / 集群 / 命名空间'"
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
/* 两级模式（租户视图）：文字从「租户 / 集群 / 命名空间」缩成「集群 / 命名空间」，框也跟着收 */
.ctx-cascader.is-two-level {
  width: 280px;
}
/* 顶栏控件统一语言：把 EP 输入框的默认观感（亮边、蓝 focus）换成本平台的面板色与主色边。
   变量必须打在 .el-input 上 —— EP 自己在这个元素上重新定义了同名变量，挂在祖先不生效。 */
.ctx-cascader .el-input {
  --el-input-border-color: var(--border);
  --el-input-bg-color: var(--panel);
  --el-input-text-color: var(--text-1);
  --el-input-placeholder-color: var(--text-3);
  --el-input-border-radius: 10px;
  --el-input-hover-border-color: color-mix(in srgb, var(--accent) 45%, var(--border));
  --el-input-focus-border-color: var(--accent);
}
.ctx-cascader .el-input__inner {
  font-size: 13px;
}
</style>
