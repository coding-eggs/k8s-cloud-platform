<script setup lang="ts">
/**
 * 存储类（StorageClass，集群级，只读）。<b>仅平台管理员可见</b>。
 *
 * <p>为什么没有租户视图：StorageClass 无命名空间维度，无法像 PV 那样按 claimRef.namespace 收窄到租户
 * —— name / provisioner / parameters 是整集群共享的。故访问面整体收在权限表：
 * 三个端点的码在 V2026_10_07_3 从 tenant:storageclass:* 改为 platform:cluster:manage。
 * 前端对应地用 Page 域码 platform:page:storageclass.list 收敛菜单与路由。
 *
 * <p>存储类由集群/基础设施管理，本页不提供创建/编辑/删除入口。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { storageClassApi } from '@/api'
import type { K8sStorageClass } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'
import YamlBlock from '@/components/YamlBlock.vue'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const loading = ref(false)
const list = ref<K8sStorageClass[]>([])

/**集群级资源只需两级上下文（无 namespace），故不直接用 ready */
const clusterReady = computed(() => !!(state.loaded && state.tenantId && state.clusterId))
const ctx2 = computed(() => ({ tenantId: state.tenantId!, clusterId: state.clusterId! }))

async function refresh(): Promise<void> {
  if (!clusterReady.value) return
  loading.value = true
  try {
    list.value = await storageClassApi.list(ctx2.value)
  } finally {
    loading.value = false
  }
}

// ---------- 详情抽屉（概览 / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sStorageClass | null>(null)
const yamlText = ref('')
const detailTab = ref('info')

function openDetail(row: K8sStorageClass): void {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'info'
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    yamlText.value = await storageClassApi.getYaml(detail.value.name, ctx2.value)
  }
})

onMounted(() => {
  void load()
  void refresh()
})
watch(
  [() => [state.tenantId, state.clusterId], ready],
  () => {
    if (clusterReady.value) void refresh()
  },
)

const contextDesc = computed(() => {
  if (!clusterReady.value) return '请在顶栏选择租户 / 集群'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''}`
})
</script>

<template>
  <div>
    <PageHeader title="存储类" :description="contextDesc">
      <el-button @click="refresh" :disabled="!clusterReady">刷新</el-button>
    </PageHeader>

    <EmptyState
      v-if="clusterReady && list.length === 0 && !loading"
      title="该集群下暂无存储类"
      description="StorageClass 由集群/基础设施（CSI 驱动）管理，本页只读。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading || !clusterReady" :data="list" stripe>
        <el-table-column label="名称" min-width="220">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="供应方（provisioner）" min-width="240">
          <template #default="{ row }">{{ row.provisioner ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="回收策略" width="110">
          <template #default="{ row }">{{ row.reclaimPolicy ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="允许扩容" width="100">
          <template #default="{ row }">
            <span v-if="row.allowVolumeExpansion === true" class="yes">是</span>
            <span v-else class="muted">{{ row.allowVolumeExpansion === false ? '否' : '—' }}</span>
          </template>
        </el-table-column>
        <!-- ⚠️ 这一列与左边那列**不是一回事**，别合并：左边是存储类上的开关（只表示「允许」），
             这一列是"真有没有人会执行"。2026-10-16 真撞到：两个 SC 左边都是"是"，右边其实都是
             "非 CSI 驱动"（那一态原来的说法是"没人执行"，太口语）—— 用户对着一个不动的容量猜了半天。判据在后端（唯一算法）。 -->
        <el-table-column label="在线扩容" width="150">
          <template #default="{ row }">
            <span v-if="row.expansion === 'csi'" class="yes">可以（CSI）</span>
            <span
              v-else-if="row.expansion === 'unbacked'"
              class="warn"
              title="扩容由 CSI 驱动的 external-resizer 执行，这个存储类的 provisioner 不是 CSI 驱动 —— 改了也只是把「请求」改大，status.capacity 不会动，连条件都不会挂。"
            >非 CSI 驱动</span>
            <span v-else-if="row.expansion === 'unsupported'" class="muted">不支持</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`StorageClass · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="供应方">{{ detail?.provisioner ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="回收策略">{{ detail?.reclaimPolicy ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="允许扩容">
              {{ detail?.allowVolumeExpansion === true ? '是' : detail?.allowVolumeExpansion === false ? '否' : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="在线扩容">
              <template v-if="detail?.expansion === 'csi'">
                <span class="yes">可以 —— provisioner 是集群里已注册的 CSI 驱动</span>
              </template>
              <template v-else-if="detail?.expansion === 'unbacked'">
                <span class="warn">非 CSI 驱动</span>
                <div class="muted expansion-note">
                  扩容由 CSI 驱动的 external-resizer 执行，这个存储类的 provisioner 不是 CSI 驱动
                  —— 改了也只会把「请求」改大，status.capacity 不会动，而且连一个条件都不会挂
                  （没人在试，也就没人报错）。要真能扩，得在集群侧装一个 CSI 驱动。
                </div>
              </template>
              <template v-else-if="detail?.expansion === 'unsupported'">
                <span class="muted">不支持 —— 存储类上没开 allowVolumeExpansion</span>
              </template>
              <span v-else class="muted">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>
          <div class="drawer-tip">
            供应方参数（parameters）可能含凭据，仅在 YAML 中按原样展示，请谨慎外传。
          </div>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <YamlBlock v-if="yamlText" :yaml="yamlText" />
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
.yes {
  color: var(--success);
}
.muted {
  color: var(--text-3);
}
.drawer-tip {
  margin-top: 14px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.6;
}
/* 「在线扩容」那一列的两种非绿状态：橙=写着允许但没人执行（最需要被看见的），灰=不支持 */
.warn {
  color: var(--warning, #e6a23c);
}
.expansion-note {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.7;
}
</style>
