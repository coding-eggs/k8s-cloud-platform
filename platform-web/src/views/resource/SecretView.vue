<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { secretApi } from '@/api'
import type { K8sSecret } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'
import {MoreFilled, Search} from "@element-plus/icons-vue";

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()

const loading = ref(false)
const list = ref<K8sSecret[]>([])
/** 名称模糊搜索（前端过滤） */
const keyword = ref('')
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter((r) => (r.name ?? '').toLowerCase().includes(kw))
})

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    list.value = await secretApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

// ---------- 跳转编辑器（新建 / 编辑独立页） ----------
function goEditor(name: string | null): void {
  router.push(name ? `/resources/secrets/editor?name=${encodeURIComponent(name)}` : '/resources/secrets/editor')
}

/** Secret 类型 → tag 颜色 */
function typeTag(type?: string | null): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  switch (type) {
    case 'kubernetes.io/tls': return 'success'
    case 'kubernetes.io/dockerconfigjson':
    case 'kubernetes.io/dockercfg': return 'warning'
    case 'kubernetes.io/ssh-auth': return 'danger'
    case 'kubernetes.io/basic-auth':
    case 'kubernetes.io/service-account-token':
    case 'bootstrap.kubernetes.io/token': return 'primary'
    default: return 'info' // Opaque / 其它
  }
}

// ---------- 删除 ----------
async function onDelete(row: K8sSecret): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 Secret「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await secretApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（值默认掩码，可整体展开明文） ----------
const drawerVisible = ref(false)
const detail = ref<K8sSecret | null>(null)
const yamlText = ref('')
const detailTab = ref('data')
const showPlain = ref(false)

async function openDetail(row: K8sSecret): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'data'
  showPlain.value = false
  drawerVisible.value = true
}

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await secretApi.getYaml(detail.value.name, ctxParams.value)
    } catch {
      /* 拦截器已提示 */
    }
  }
})

// ---------- 上下文联动：顶栏 chip 变化时刷新 ----------
onMounted(() => {
  void load()
  void refresh()
})
watch(
  () => [state.tenantId, state.clusterId, state.namespace],
  () => {
    if (ready.value) void refresh()
  },
)

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader title="Secret" >
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="goEditor(null)">创建 Secret</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 Secret"
      description="点击右上「创建 Secret」新建，或到顶栏切换上下文查看其他命名空间。"
    />

    <div v-else class="panel table-panel">
      <div class="search-bar">
        <el-input v-model="keyword" placeholder="按名称搜索…" clearable :prefix-icon="Search" class="search-input" />
      </div>
      <el-table v-loading="loading || !ready" :data="filtered" stripe>
        <el-table-column label="名称" width="500">
          <template #default="{ row }">
            <code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="190">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="typeTag(row.type)">{{ row.type || 'Opaque' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="可编辑" min-width="150">
          <template #default="{ row }"><el-tag :type="row.immutable? 'warning' : 'success' ">{{ !row.immutable }}</el-tag></template>
        </el-table-column>
        <el-table-column label="数据项" min-width="100">
          <template #default="{ row }">{{ Object.keys(row.data ?? {}).length }} 项</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>


        <el-table-column width="64" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click">
              <el-button link type="primary" :icon="MoreFilled" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="goEditor(row.name)">编辑</el-dropdown-item>
                  <el-dropdown-item divided style="color: var(--el-color-danger)" @click="onDelete(row)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>

      </el-table>
    </div>

    <!-- 详情 -->
    <el-drawer v-model="drawerVisible" :title="`Secret · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="数据（脱敏）" name="data">
          <div class="mask-bar">
            <el-switch v-model="showPlain" inline-prompt active-text="显示明文" inactive-text="掩码" />
          </div>
          <el-table :data="Object.entries(detail?.data ?? {}).map(([key, value]) => ({ key, value }))" stripe>
            <el-table-column prop="key" label="Key" min-width="160">
              <template #default="{ row }"><code class="res-name">{{ row.key }}</code></template>
            </el-table-column>
            <el-table-column prop="value" label="Value" min-width="240">
              <template #default="{ row }">
                <span class="kv-value">{{ showPlain ? row.value : '••••••••' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
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
.search-bar {
  margin-bottom: 10px;
}
.search-input {
  width: 260px;
}
.res-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--text-1);
}
.muted {
  color: var(--text-3);
}
.mask-bar {
  margin-bottom: 10px;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
}
.kv-value {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  word-break: break-all;
  white-space: pre-wrap;
}
.yaml-block {
  margin: 0;
  padding: 14px;
  border-radius: 8px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
}
</style>
