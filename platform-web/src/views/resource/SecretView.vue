<script setup lang="ts">
import { isManaged, managedReason } from '@/utils/managedResource'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { secretApi } from '@/api'
import type { K8sSecret } from '@/types'
import { useResourceContext } from '@/stores/context'
import { usePermission } from '@/stores/permission'
import { pageCodes } from '@/pageCodes'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDate } from '@/utils/format'
import {MoreFilled, Search} from "@element-plus/icons-vue";
import YamlBlock from '@/components/YamlBlock.vue'

const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const router = useRouter()
const route = useRoute()
const perm = usePermission()
/** 「创建 Secret」= 进入编辑器页，门禁与路由 requiresPerm 同源（只读角色没有它 → 按钮不渲染） */
const canCreate = computed(() => perm.has(pageCodes.secret.edit))
/** ?open=<name> 只消费一次（避免每次刷新都重新弹详情） */
let autoOpened = false

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
    // ?open=<name>：从中间件详情等外部入口直达该 Secret 的详情抽屉；首次加载后清掉参数
    if (!autoOpened && route.query.open) {
      autoOpened = true
      const target = list.value.find((s) => s.name === route.query.open)
      if (target) await openDetail(target)
      const q = { ...route.query }
      delete q.open
      await router.replace({ query: q })
    }
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

/**
 * docker config 是**压成一行的长 JSON**，不排开基本没法核对 auths 里有哪些 host。
 * 只认 `.dockerconfigjson` 这一个 key；解析失败（用户塞了别的东西）返回 null，按原文显示。
 */
function prettyJson(key: string, value: string): string | null {
  if (key.trim() !== '.dockerconfigjson') return null
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return null
  }
}

/** 详情抽屉的行数据（值默认掩码；展开明文时 JSON 值会排开） */
const detailRows = computed(() =>
  Object.entries(detail.value?.data ?? {}).map(([key, value]) => ({
    key,
    value,
    json: prettyJson(key, value),
  })),
)

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
  [() => [state.tenantId, state.clusterId, state.namespace], ready],
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
      <el-button v-if="canCreate" type="primary" :disabled="!ready" @click="goEditor(null)">创建 Secret</el-button>
    </PageHeader>

    <EmptyState
      v-if="ready && list.length === 0 && !loading"
      title="该命名空间下暂无 Secret"
      :description="
        canCreate
          ? '点击右上「创建 Secret」新建，或到顶栏切换上下文查看其他命名空间。'
          : '当前账号在该租户内没有创建 Secret 的权限，只能查看。'
      "
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
                  <el-dropdown-item v-if="isManaged(row)" disabled>{{ managedReason(row) }}</el-dropdown-item>
                  <el-dropdown-item :disabled="isManaged(row)" @click="goEditor(row.name)">编辑</el-dropdown-item>
                  <el-dropdown-item divided :disabled="isManaged(row)" style="color: var(--el-color-danger)" @click="onDelete(row)">删除</el-dropdown-item>
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
          <el-table :data="detailRows" stripe>
            <el-table-column prop="key" label="Key" min-width="160">
              <template #default="{ row }"><code class="res-name">{{ row.key }}</code></template>
            </el-table-column>
            <el-table-column prop="value" label="Value" min-width="240">
              <template #default="{ row }">
                <span v-if="!showPlain" class="kv-value">••••••••</span>
                <pre v-else-if="row.json" class="kv-json">{{ row.json }}</pre>
                <span v-else class="kv-value">{{ row.value }}</span>
              </template>
            </el-table-column>
          </el-table>
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
/* docker config 排开后的样子：等宽、保留缩进、过长时在格子里滚动而不是把表格撑开 */
.kv-json {
  margin: 0;
  padding: 4px 0;
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
}
</style>
