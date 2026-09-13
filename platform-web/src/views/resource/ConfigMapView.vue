<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { configMapApi } from '@/api'
import type { K8sConfigMap } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'
import { buildPreview, fmtSize } from '@/utils/binaryPreview'
import {MoreFilled, Search} from "@element-plus/icons-vue";

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()

const loading = ref(false)
const list = ref<K8sConfigMap[]>([])
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
    list.value = await configMapApi.list(ctxParams.value)
  } finally {
    loading.value = false
  }
}

// ---------- 数据项计数：data + binaryData（有二进制时标注） ----------
function dataCount(row: K8sConfigMap): string {
  const d = Object.keys(row.data ?? {}).length
  const b = Object.keys(row.binaryData ?? {}).length
  return b > 0 ? `${d + b} 项（含 ${b} 二进制）` : `${d} 项`
}

// ---------- 跳转编辑器（新建 / 编辑独立页） ----------
function goEditor(name: string | null): void {
  router.push(name ? `/resources/configmaps/editor?name=${encodeURIComponent(name)}` : '/resources/configmaps/editor')
}

// ---------- 删除 ----------
async function onDelete(row: K8sConfigMap): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 ConfigMap「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await configMapApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 详情抽屉（Data / YAML 只读） ----------
const drawerVisible = ref(false)
const detail = ref<K8sConfigMap | null>(null)
const yamlText = ref('')
const detailTab = ref('data')

async function openDetail(row: K8sConfigMap): Promise<void> {
  detail.value = row
  yamlText.value = ''
  detailTab.value = 'data'
  drawerVisible.value = true
}

/** binaryData 预览项（图片缩略 / 文本内容 / 二进制下载） */
const binEntries = computed(() =>
  Object.entries(detail.value?.binaryData ?? {}).map(([key, b64]) => ({ key, preview: buildPreview(b64, null) })),
)

watch(detailTab, async (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) {
    try {
      yamlText.value = await configMapApi.getYaml(detail.value.name, ctxParams.value)
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
    <PageHeader title="ConfigMap" >
      <el-button @click="refresh" :disabled="!ready">刷新</el-button>
      <el-button type="primary" :disabled="!ready" @click="goEditor(null)">创建 ConfigMap</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 ConfigMap"
      description="点击右上「创建 ConfigMap」新建，或到顶栏切换上下文查看其他命名空间。"
    />

    <div v-else class="panel table-panel">
      <div class="search-bar">
        <el-input v-model="keyword" placeholder="按名称搜索…" clearable :prefix-icon="Search" class="search-input" />
      </div>
      <el-table v-loading="loading || !ready" :data="filtered" stripe>
        <el-table-column label="名称" width="500" >
          <template #default="{ row }" >
            <code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code>
          </template>
        </el-table-column>

        <el-table-column label="数据项" min-width="250">
          <template #default="{ row }">{{ dataCount(row) }}</template>
        </el-table-column>

        <el-table-column label="可编辑" min-width="150">
          <template #default="{ row }"><el-tag :type="row.immutable? 'warning' : 'success' ">{{ !row.immutable }}</el-tag></template>
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
    <el-drawer v-model="drawerVisible" :title="`ConfigMap · ${detail?.name ?? ''}`" size="640px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="数据" name="data">
          <el-table :data="Object.entries(detail?.data ?? {}).map(([key, value]) => ({ key, value }))" stripe>
            <el-table-column prop="key" label="Key" min-width="160">
              <template #default="{ row }"><code class="res-name">{{ row.key }}</code></template>
            </el-table-column>
            <el-table-column prop="value" label="Value" min-width="240">
              <template #default="{ row }"><span class="kv-value">{{ row.value }}</span></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`二进制${binEntries.length ? '（' + binEntries.length + '）' : ''}`" name="binary">
          <div v-if="binEntries.length === 0" class="muted">无二进制数据</div>
          <div v-for="(item, i) in binEntries" :key="i" class="bin-detail-item">
            <code class="res-name">{{ item.key }}</code>
            <div class="bin-preview">
              <img v-if="item.preview.kind === 'image'" :src="item.preview.dataUrl" class="bin-img" />
              <pre v-else-if="item.preview.kind === 'text'" class="bin-text">{{ item.preview.text }}</pre>
              <div v-else class="bin-bin">
                <span class="muted">{{ fmtSize(item.preview.size) }} · 二进制</span>
                <a :href="item.preview.dataUrl" download class="bin-dl">下载</a>
              </div>
            </div>
          </div>
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
.label-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.muted {
  color: var(--text-3);
}
.kv-value {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  word-break: break-all;
  white-space: pre-wrap;
}
.bin-detail-item {
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
}
.bin-detail-item:last-child {
  border-bottom: none;
}
.bin-preview {
  margin-top: 6px;
}
.bin-img {
  max-height: 96px;
  max-width: 200px;
  border-radius: 4px;
  border: 1px solid var(--border);
  object-fit: contain;
  background: #fff;
}
.bin-text {
  margin: 0;
  padding: 8px;
  border-radius: 4px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.5;
  max-height: 96px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.bin-bin {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}
.bin-dl {
  color: var(--accent);
  text-decoration: none;
}
.bin-dl:hover {
  text-decoration: underline;
}
.name-link {
  cursor: pointer;
  color: var(--accent);
}
.name-link:hover {
  text-decoration: underline;
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
