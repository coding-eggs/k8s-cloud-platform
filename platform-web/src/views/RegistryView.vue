<script setup lang="ts">
/**
 * 镜像仓库页（spec §3.3）：左栏是「仓库」（一个 host 一行），右栏是选中仓库的
 * 连接 / 项目 / 浏览三块。
 *
 * ⚠️ 本页不做任何 host 归一或来源判定 —— 那两件事都在后端（RegistryService.warehouses）。
 * 前端再做一遍就是第二个真相源。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { credentialApi, registryApi } from '@/api'
import type {
  CredentialView, K8sRegistryProject, RegistryBrowseResult, RegistryNode, RegistryWarehouse,
} from '@/types'
import { writeClipboard } from '@/utils/clipboard'
import { fmtDate } from '@/utils/format'

const warehouses = ref<RegistryWarehouse[]>([])
const selectedId = ref('')
const connections = ref<CredentialView[]>([])
const projects = ref<K8sRegistryProject[]>([])
const path = ref<string[]>([])
const result = ref<RegistryBrowseResult | null>(null)

const loading = ref(false)
const detailLoading = ref(false)
/** 与「这一层是空的」区分开 —— 浏览失败必须让用户看见 */
const error = ref('')

const selected = computed(() => warehouses.value.find((w) => w.id === selectedId.value) ?? null)

/**
 * 选中的仓库下、当前用户可见的连接（后端已按可见性过滤，这里只按 host 分组）。
 * ⚠️ 必须排除 GIT —— 同主机上的 Git 连接与镜像仓库无关。
 */
const selectedConnections = computed(() =>
  connections.value.filter(
    (c) => c.type !== 'GIT' && c.host === (selected.value?.host ?? ''),
  ))

/** 浏览用哪条连接：优先项目级 robot（它按项目收窄），否则第一条启用的 */
const browseCredential = computed(() =>
  selectedConnections.value.find((c) => c.enabled && c.source === 'HARBOR')
  ?? selectedConnections.value.find((c) => c.enabled)
  ?? null)

async function loadWarehouses(): Promise<void> {
  loading.value = true
  try {
    warehouses.value = await registryApi.warehouses()
    const first = warehouses.value[0]
    if (first && !selectedId.value) selectedId.value = first.id
  } catch (e) {
    ElMessage.error((e as Error).message)
    warehouses.value = []
  } finally {
    loading.value = false
  }
}

async function loadDetail(): Promise<void> {
  const w = selected.value
  if (!w) return
  detailLoading.value = true
  error.value = ''
  result.value = null
  path.value = []
  try {
    // 连接：一次取全部可见的（量小），按 host 过滤 —— 免得为「按 host 查连接」再造一个端点
    connections.value = await credentialApi.list()
    projects.value = w.clusterId ? await registryApi.projects(w.clusterId) : []
    const browseId = browseCredential.value?.id
      ?? (w.origin === 'BUILTIN' ? w.id : null)
    if (browseId) {
      result.value = await registryApi.browse(browseId, [])
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    detailLoading.value = false
  }
}

/** 当前浏览用的来源 id */
function browseId(): string {
  return browseCredential.value?.id ?? selectedId.value
}

async function browse(id: string, p: string[]): Promise<void> {
  path.value = p
  detailLoading.value = true
  error.value = ''
  try {
    result.value = await registryApi.browse(id, p)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    detailLoading.value = false
  }
}

function drillInto(node: RegistryNode): void {
  if (!node.hasChildren) return
  void browse(browseId(), [...path.value, node.name])
}

function goTo(p: string[]): void {
  void browse(browseId(), p)
}

async function copyImage(node: RegistryNode): Promise<void> {
  if (!node.copyText) return
  const ok = await writeClipboard(node.copyText)
  if (ok) ElMessage.success('已复制镜像地址')
  else ElMessage.warning('当前浏览器不允许自动复制，请手动选中复制')
}

watch(selectedId, () => void loadDetail())
onMounted(loadWarehouses)
</script>

<template>
  <div class="registry-page">
    <div class="page-head"><h2>镜像仓库</h2></div>

    <div class="registry-body" v-loading="loading">
      <aside class="wh-list">
        <div v-for="w in warehouses" :key="w.id"
             class="wh-item" :class="{ active: w.id === selectedId }"
             @click="selectedId = w.id">
          <div class="wh-name">
            {{ w.displayName }}
            <el-tag size="small"
                    :type="w.origin === 'INSTALLED' ? 'primary'
                      : w.origin === 'BUILTIN' ? 'info' : 'warning'">
              {{ w.origin === 'INSTALLED' ? '平台' : w.origin === 'BUILTIN' ? '公共' : '外部' }}
            </el-tag>
          </div>
          <div class="wh-host">{{ w.host }}</div>
          <div class="wh-meta">
            <span>{{ w.kind === 'DOCKER' ? 'Docker' : 'Harbor' }}</span>
            <span v-if="w.connectionCount">{{ w.connectionCount }} 个连接</span>
            <span v-if="w.projectCount">{{ w.projectCount }} 个项目</span>
            <el-tag v-if="w.health === 'ERROR'" size="small" type="danger">异常</el-tag>
          </div>
        </div>
        <el-empty v-if="!loading && !warehouses.length" description="还没有可用的镜像仓库" />
      </aside>

      <section class="wh-detail" v-loading="detailLoading">
        <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

        <template v-if="selected">
          <h3>连接</h3>
          <el-table :data="selectedConnections" size="small" stripe>
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column label="归属" width="120">
              <template #default="{ row }">{{ row.tenantName || '平台级' }}</template>
            </el-table-column>
            <el-table-column label="认证" width="90">
              <template #default="{ row }">{{ row.authType === 'NONE' ? '匿名' : 'Basic' }}</template>
            </el-table-column>
            <el-table-column label="上次验证" min-width="150">
              <template #default="{ row }">
                {{ row.lastVerifiedAt ? fmtDate(row.lastVerifiedAt) : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="流水线可用" width="110">
              <template #default="{ row }">{{ row.usableByPipelines ? '是' : '否' }}</template>
            </el-table-column>
          </el-table>

          <template v-if="projects.length">
            <h3>项目</h3>
            <el-table :data="projects" size="small" stripe>
              <el-table-column prop="tenantName" label="归属" min-width="120" />
              <el-table-column prop="projectName" label="项目" min-width="160" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'READY' ? 'success' : 'danger'" size="small">
                    {{ row.status === 'READY' ? '就绪' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="上次同步" width="160">
                <template #default="{ row }">{{ row.lastSyncedAt ? fmtDate(row.lastSyncedAt) : '—' }}</template>
              </el-table-column>
            </el-table>
          </template>

          <h3>浏览</h3>
          <div class="crumbs">
            <el-button link @click="goTo([])">{{ selected.displayName }}</el-button>
            <template v-for="(c, i) in result?.breadcrumb ?? []" :key="i">
              <span class="sep">/</span>
              <el-button link @click="goTo(c.path)">{{ c.label }}</el-button>
            </template>
          </div>
          <el-table :data="result?.children ?? []" size="small" stripe>
            <el-table-column label="名称" min-width="220">
              <template #default="{ row }">
                <el-button v-if="row.hasChildren" link type="primary" @click="drillInto(row)">
                  {{ row.name }}
                </el-button>
                <span v-else>{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column v-for="d in result?.children?.[0]?.details ?? []" :key="d.label"
                             :label="d.label" min-width="140">
              <template #default="{ row }">
                {{ row.details.find((x: { label: string }) => x.label === d.label)?.value ?? '' }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.copyText" link type="primary" @click="copyImage(row)">
                  复制地址
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.registry-page {
  padding: 16px;
}
.page-head {
  margin-bottom: 12px;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
}
.registry-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.wh-list {
  width: 280px;
  flex: none;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 12px;
}
.wh-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
}
.wh-item.active {
  background: var(--el-color-primary-light-9);
}
.wh-name {
  font-weight: 600;
  display: flex;
  gap: 8px;
  align-items: center;
}
.wh-host {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 2px;
}
.wh-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
  display: flex;
  gap: 10px;
  align-items: center;
}
.wh-detail {
  flex: 1;
  min-width: 0;
}
.wh-detail h3 {
  margin: 16px 0 8px;
  font-size: 14px;
}
.crumbs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.sep {
  color: var(--el-text-color-placeholder);
  margin: 0 2px;
}
</style>
