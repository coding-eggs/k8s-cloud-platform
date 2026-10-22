<script setup lang="ts">
import { isManaged, managedReason } from '@/utils/managedResource'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import MeshStatusBanner from '@/components/mesh/MeshStatusBanner.vue'
import YamlBlock from '@/components/YamlBlock.vue'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { usePermission } from '@/stores/permission'
import { useResourceContext } from '@/stores/context'
import { kindMeta, parseRouteKind, ROUTE_KINDS, type RouteCtx, type RouteKind, type RouteRow } from '@/utils/routeKinds'
import type { K8sRouteParentRef } from '@/types'
import { fmtDate } from '@/utils/format'

/**
 * 「路由策略」列表：5 种 route 一个页面（形态沿用 WorkloadView 的多 kind 列表）。
 *
 * <p><b>按权限逐 kind 拉</b>：没有该 kind 的 list 能力位就不发请求、不进筛选器 —— 权限表在服务端，
 * 前端短路只为"不发注定被拒的请求"（frontend-permission-conventions §1 的码表分工：页面可见性用 pageCodes，
 * 能力短路用 apiCodes）。
 *
 * <p><b>失败隔离 + 不反复报错</b>：某个 kind 拉失败（典型：该 CRD 未安装）只在本页标记一次并从后续刷新里
 * 排除；它不拖累其它 kind，也不会每刷新一次就炸一串提示（顶部给一行说明）。换集群/命名空间或用户点刷新时重新尝试。
 */
const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const perm = usePermission()
const mesh = useMeshStatus(computed(() => state.clusterId ?? null))

const ctxParams = computed<RouteCtx>(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

/** 有 list 能力位的 kind（无权限的不出现、不发请求） */
const permitted = computed(() => ROUTE_KINDS.filter((k) => perm.has(k.listCode)))
/** 拉取失败的 kind：本页内不再重试（避免反复报错）；换上下文或点刷新时清空重试 */
const unavailable = ref<RouteKind[]>([])

const loading = ref(false)
const rows = ref<RouteRow[]>([])
const kindFilter = ref<'all' | RouteKind>(parseRouteKind(route.query.kind) ?? 'all')

const filtered = computed(() =>
  kindFilter.value === 'all' ? rows.value : rows.value.filter((r) => r._kind === kindFilter.value),
)
/** 创建门禁与旧 5 个列表页一致：集群得装了 Gateway API */
const canCreate = computed(() => mesh.hasGatewayApi.value)
/** 创建下拉：有 create 能力位、且该 kind 当前可用 */
const creatable = computed(() =>
  permitted.value.filter((k) => perm.has(k.createCode) && !unavailable.value.includes(k.key)),
)

/** 空态文案：三种情形分开说清（没有任何读权限 / 该类型被筛空 / 该命名空间真的没有） */
const emptyTitle = computed(() => {
  if (!permitted.value.length) return '无可用路由类型'
  if (kindFilter.value === 'all') return '该命名空间下暂无路由策略'
  return `该命名空间下没有 ${kindMeta(kindFilter.value).label}`
})
const emptyDescription = computed(() => {
  if (!permitted.value.length) return '当前账号没有任何一种路由类型的读取权限，请联系管理员。'
  if (kindFilter.value === 'all') return '路由策略含 HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute 五种（gateway.networking.k8s.io CRD）。'
  return '切回「全部」可查看其它类型。'
})

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    const targets = permitted.value.filter((k) => !unavailable.value.includes(k.key))
    const settled = await Promise.allSettled(
      targets.map(async (k) => (await k.list(ctxParams.value)).map((r) => ({ ...(r as object), _kind: k.key }) as RouteRow)),
    )
    const out: RouteRow[] = []
    settled.forEach((s, i) => {
      if (s.status === 'fulfilled') {
        out.push(...s.value)
        return
      }
      const target = targets[i]
      if (!target) return
      unavailable.value = [...unavailable.value, target.key]
      if (kindFilter.value === target.key) kindFilter.value = 'all' // 该 kind 掉了 → 筛选回落「全部」
    })
    rows.value = out
  } finally {
    loading.value = false
  }
}

async function refreshAll(): Promise<void> {
  unavailable.value = [] // 用户显式刷新：失败的 kind 再试一次
  await mesh.refresh()
  await refresh()
}

watch([() => ready.value, () => state.clusterId, () => state.namespace], ([r]) => {
  if (!r) return
  void refresh()
}, { immediate: true })

/** 某个 CRD 装没装是**集群**属性，与命名空间无关：只有换集群（或用户点刷新）才重置「不可用」，
 *  否则每切一次命名空间就重发那几个注定失败的 list、重炸一串与用户操作无关的红条。 */
watch(() => state.clusterId, () => { unavailable.value = [] })

// ---------- 导航 ----------
function goCreate(key: RouteKind): void { router.push({ name: 'route-editor', query: { kind: key } }) }
function goEdit(row: RouteRow): void { router.push({ name: 'route-editor', query: { kind: row._kind, name: row.name } }) }

async function onDelete(row: RouteRow): Promise<void> {
  const meta = kindMeta(row._kind)
  try {
    await ElMessageBox.confirm(`确认删除 ${meta.label}「${row.name}」？${meta.deleteHint}`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await meta.remove(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

// ---------- 详情抽屉 ----------
const drawerVisible = ref(false)
const detail = ref<RouteRow | null>(null)
const detailTab = ref<'info' | 'yaml'>('info')
const yamlText = ref('')

async function openDetail(row: RouteRow, tab: 'info' | 'yaml' = 'info'): Promise<void> {
  detail.value = row
  detailTab.value = tab
  yamlText.value = ''
  drawerVisible.value = true
  if (tab === 'yaml') await loadYaml(row)
}

/** 抽屉里切到 YAML 标签才懒加载（5 份旧列表页都有这个 watch，合并时漏了它 → 点 tab 永远停在「加载中…」） */
watch(detailTab, (tab) => {
  if (tab === 'yaml' && detail.value && !yamlText.value) void loadYaml(detail.value)
})

async function loadYaml(row: RouteRow): Promise<void> {
  try {
    yamlText.value = await kindMeta(row._kind).yaml(row.name, ctxParams.value)
  } catch { /* 拦截器已提示 */ }
}

// ---------- 展示辅助（与旧 5 个列表页逐字相同的口径） ----------
function parentText(p: K8sRouteParentRef): string {
  const kind = p.kind || 'Gateway'
  const ns = p.namespace ? `${p.namespace}/` : ''
  const qual = p.sectionName || (p.port != null ? String(p.port) : '')
  return `${kind} ${ns}${p.name ?? '?'}${qual ? `:${qual}` : ''}`
}

/** 行内摘要（各 kind 的规则/后端/SNI 摘要） */
function summaryText(row: RouteRow): string {
  return kindMeta(row._kind).summary(row)
}

/** hostnames 只有 HTTP/GRPC/TLS 有 —— 联合类型上必须按"可能没有"读（否则 vue-tsc 报错） */
function rowHostnames(row: RouteRow | null): string[] {
  return ((row ?? {}) as { hostnames?: string[] }).hostnames ?? []
}

/** 抽屉「概览」里的规则行文本：HTTP/GRPC 有 matches，L4 只有后端 */
function ruleText(row: RouteRow, index: number): string {
  const rules = ((row as { rules?: unknown[] }).rules ?? []) as Record<string, unknown>[]
  const r = rules[index]
  if (!r) return '—'
  const backends = ((r.backendRefs ?? []) as Record<string, unknown>[])
    .map((b) => `${b.namespace ? `${b.namespace}/` : ''}${b.name}:${b.port ?? '?'}${b.weight != null ? `(w=${b.weight})` : ''}`)
    .join(', ')
  const matches = (r.matches ?? []) as Record<string, unknown>[]
  const parts = [r.name ? String(r.name) : '', matches.length ? `${matches.length} 条匹配` : '', backends || '—']
  return parts.filter(Boolean).join(' · ')
}

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})

function onLoad(): void { void load() }
onLoad()
</script>

<template>
  <div>
    <PageHeader title="路由策略" :description="contextDesc">
      <el-button @click="refreshAll" :disabled="!ready">刷新</el-button>
      <!-- 筛选 = 全部：下拉选类型创建；筛选 = 某 kind：直接创建该 kind -->
      <el-dropdown v-if="kindFilter === 'all' && creatable.length" @command="goCreate">
        <el-button type="primary" :disabled="!ready || !canCreate">
          创建<el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="k in creatable" :key="k.key" :command="k.key">创建 {{ k.label }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button
        v-else-if="kindFilter !== 'all' && perm.has(kindMeta(kindFilter).createCode)"
        type="primary"
        :disabled="!ready || !canCreate"
        @click="goCreate(kindFilter)"
      >
        创建 {{ kindMeta(kindFilter).label }}
      </el-button>
    </PageHeader>

    <MeshStatusBanner
      v-if="ready && state.clusterId"
      :has-gateway-api="mesh.hasGatewayApi.value"
      :gateway-api-versions="mesh.gatewayApiVersions.value"
      :has-istio="mesh.hasIstio.value"
      :istio-ambient="mesh.istioAmbient.value"
      :ambient-known="mesh.ambientKnown.value"
      :probed="mesh.probed.value"
      :can-refresh="mesh.canRefresh.value"
      @refresh="refreshAll"
    />

    <!-- 不可用的类型：一行说明（不反复报错，但用户得知道缺了什么） -->
    <el-alert
      v-if="unavailable.length"
      type="info"
      :closable="false"
      class="kind-alert"
      :title="`以下类型当前不可用（CRD 未安装或读取失败）：${unavailable.map((k) => kindMeta(k).label).join('、')}`"
    />

    <!-- 筛选行必须在空态之外：否则筛到「该类型 0 行」时整块被空态顶掉，筛选器跟着消失、用户回不到「全部」 -->
    <div v-if="ready" class="panel table-panel">
      <div class="filter-row">
        <span class="muted">类型</span>
        <el-select v-model="kindFilter" style="width: 200px">
          <el-option label="全部" value="all" />
          <el-option
            v-for="k in permitted"
            :key="k.key"
            :label="unavailable.includes(k.key) ? `${k.label}（未安装）` : k.label"
            :value="k.key"
            :disabled="unavailable.includes(k.key)"
          />
        </el-select>
      </div>

      <EmptyState v-if="!filtered.length && !loading" :title="emptyTitle" :description="emptyDescription" />

      <el-table v-else v-loading="loading || !ready" :data="filtered" stripe>
        <el-table-column label="类型" width="130">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ kindMeta(row._kind).label }}</el-tag></template>
        </el-table-column>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="父资源" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="(p, i) in (row.parentRefs ?? [])" :key="i" size="small" type="info" effect="plain" class="tag-inline">{{ parentText(p) }}</el-tag>
            <span v-if="!(row.parentRefs ?? []).length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="240">
          <template #default="{ row }"><span class="mono">{{ summaryText(row) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click">
              <el-button link><el-icon><MoreFilled /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="openDetail(row, 'info')">查看</el-dropdown-item>
                  <el-dropdown-item @click="openDetail(row, 'yaml')">YAML</el-dropdown-item>
                  <el-dropdown-item v-if="isManaged(row)" disabled>{{ managedReason(row) }}</el-dropdown-item>
                  <el-dropdown-item :disabled="isManaged(row)" @click="goEdit(row)">编辑</el-dropdown-item>
                  <el-dropdown-item divided :disabled="isManaged(row)" style="color: var(--el-color-danger)" @click="onDelete(row)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情抽屉：一份，按 kind 渲染 -->
    <el-drawer v-model="drawerVisible" :title="detail ? `路由策略 · ${kindMeta(detail._kind).label} · ${detail.name}` : ''" size="720px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item v-if="detail && kindMeta(detail._kind).usesHostnames" label="主机名 / SNI">
              {{ rowHostnames(detail).join(', ') || '全部主机名' }}
            </el-descriptions-item>
            <el-descriptions-item label="父资源">{{ (detail?.parentRefs ?? []).map(parentText).join(', ') || '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-title">规则</div>
          <el-table :data="((detail as { rules?: unknown[] } | null)?.rules ?? [])" stripe size="small">
            <el-table-column label="#" type="index" width="50" />
            <el-table-column label="摘要" min-width="480">
              <template #default="{ $index }"><span class="mono">{{ detail ? ruleText(detail, $index) : '' }}</span></template>
            </el-table-column>
          </el-table>

          <div class="section-title">父资源状态</div>
          <el-table :data="detail?.parentStatuses ?? []" stripe size="small">
            <el-table-column label="父资源" min-width="160">
              <template #default="{ row }">{{ row.parentRef ? parentText(row.parentRef) : '—' }}</template>
            </el-table-column>
            <el-table-column prop="controllerName" label="控制器" min-width="200" />
            <el-table-column label="条件" min-width="180">
              <template #default="{ row }">
                <el-tag
                  v-for="c in (row.conditions ?? [])"
                  :key="c.type"
                  size="small"
                  :type="c.status === 'True' ? 'success' : 'danger'"
                  class="tag-inline"
                >{{ c.type }}: {{ c.status }}</el-tag>
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
.table-panel { padding: 8px; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.res-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 13px; color: var(--text-1); }
.name-link { cursor: pointer; color: var(--accent); }
.name-link:hover { text-decoration: underline; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
.tag-inline { margin-right: 4px; margin-bottom: 2px; }
.section-title { font-size: 13px; font-weight: 600; color: var(--text-2); margin: 14px 0 8px; }
.filter-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.kind-alert { margin-bottom: 12px; }
</style>
