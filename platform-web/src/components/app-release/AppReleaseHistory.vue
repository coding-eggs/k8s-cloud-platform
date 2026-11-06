<script setup lang="ts">
/**
 * 发布记录：新到旧。只读用户也能看（含快照详情）；回滚按钮需要写权限。
 *
 * 回滚入口按后端规则收敛：
 * - 源必须是 DEPLOYED；
 * - 线上版本就是最新发布时，回滚到它没有意义（后端拒绝）→ 不显示；有更新的失败 / 中断发布时，回到线上版本合法 → 显示；
 * - 有进行中（PENDING / RUNNING）的执行时后端拒绝 → 禁用并说明原因。
 * 快照不含口令；环境变量按原样展示（引用写成 `${依赖.字段}`，Secret 只显示名与键）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { appReleaseApi } from '@/api'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppExecutionLog from './AppExecutionLog.vue'
import { fmtDate } from '@/utils/format'
import type { AppEnvRow, AppRelease } from '@/types'
import { canRollbackTo, errMsg, RELEASE_STATUS } from './releaseUtil'

const props = defineProps<{
  appId: string
  tenantId?: string
  liveNo: number
  canWrite: boolean
  /** 有 PENDING / RUNNING 的执行：此时不能回滚 */
  busy: boolean
  /** 递增即重新加载（发布 / 回滚 / 执行结束后由父页触发） */
  reloadKey: number
}>()
const emit = defineEmits<{ (e: 'rollback', releaseNo: number): void }>()

const rows = ref<AppRelease[]>([])
const loading = ref(false)
const loadError = ref('')
/** 分页：page/size 发给后端走 PageHelper，total 由后端 count 回 */
const page = ref(1)
const size = ref(10)
const total = ref(0)

let seq = 0
async function load(): Promise<void> {
  const my = ++seq
  loading.value = true
  loadError.value = ''
  try {
    const res = await appReleaseApi.list(props.appId, page.value, size.value, props.tenantId)
    if (my !== seq) return
    rows.value = res.items
    total.value = res.total
  } catch (e) {
    if (my !== seq) return
    rows.value = []
    total.value = 0
    loadError.value = errMsg(e, '加载失败')
  } finally {
    if (my === seq) loading.value = false
  }
}
onMounted(load)
// 发布 / 回滚 / 执行结束后新记录出现在最前面 → 回到第一页
watch(() => [props.reloadKey, props.appId], () => { page.value = 1; void load() })

function onPageChange(p: number): void {
  page.value = p
  void load()
}
function onSizeChange(s: number): void {
  size.value = s
  page.value = 1
  void load()
}

/**
 * 全局最新的发布号。列表按发布号倒序，所以它只会是第 1 页第 1 行；
 * 翻到后面的页时，本页没有任何一行是最新发布 → 用 +∞，让 canRollbackTo 里「线上且最新」恒不成立。
 */
const maxNo = computed(() => (page.value === 1 && rows.value.length ? rows.value[0]!.releaseNo : Number.POSITIVE_INFINITY))
const showRollback = (r: AppRelease): boolean => props.canWrite && canRollbackTo(r, props.liveNo, maxNo.value)

// ---------- 详情 ----------
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AppRelease | null>(null)
const detailError = ref('')

async function openDetail(r: AppRelease): Promise<void> {
  detailVisible.value = true
  detail.value = null
  detailError.value = ''
  detailLoading.value = true
  try {
    detail.value = await appReleaseApi.get(props.appId, r.releaseNo, props.tenantId)
  } catch (e) {
    detailError.value = errMsg(e, '读取发布详情失败')
  } finally {
    detailLoading.value = false
  }
}

const detailServices = computed(() => Object.entries(detail.value?.snapshot?.services ?? {}).sort(([a], [b]) => a.localeCompare(b)))
function envText(e: AppEnvRow): string {
  if (e.kind === 'SECRET') return `${e.name} ← Secret ${e.secretName ?? ''}/${e.secretKey ?? ''}`
  return `${e.name} = ${e.value ?? ''}`
}
function externalText(ext?: Record<string, unknown> | null): string {
  if (!ext) return ''
  return Object.entries(ext).map(([k, v]) => `${k}=${String(v)}`).join('  ')
}
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="loadError" />
    <EmptyState v-else-if="!rows.length && !loading" title="还没有发布记录" description="完成第一次发布后，记录会出现在这里。" />
    <el-table v-else :data="rows" size="default">
      <el-table-column label="发布" width="110">
        <template #default="{ row }">
          <b>#{{ row.releaseNo }}</b>
          <el-tag v-if="row.live" size="small" type="success" effect="light" class="app-rel-hist__tag">线上</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="130">
        <template #default="{ row }">
          <template v-if="row.type === 'ROLLBACK'">回滚自 #{{ row.sourceReleaseNo }}</template>
          <template v-else-if="row.type === 'OFFLINE'">下线</template>
          <template v-else>普通发布</template>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <StatusBadge :label="RELEASE_STATUS[row.status]?.text ?? row.status" :type="RELEASE_STATUS[row.status]?.type ?? 'neutral'" />
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.note || '—' }}</template>
      </el-table-column>
      <el-table-column label="发起人" width="110">
        <template #default="{ row }">{{ row.createdBy || '—' }}</template>
      </el-table-column>
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ fmtDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-tooltip v-if="showRollback(row)" :disabled="!busy" content="有进行中的发布，结束后才能回滚" placement="top">
            <span>
              <el-button link type="danger" :disabled="busy" @click="emit('rollback', row.releaseNo)">
                {{ row.releaseNo === liveNo ? '回到此版本' : '回滚' }}
              </el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="total > 0" class="app-rel-hist__pager">
      <el-pagination
        :current-page="page"
        :page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>

    <el-drawer v-model="detailVisible" :title="detail ? `发布 #${detail.releaseNo}` : '发布详情'" size="640px" class="app-rel-drawer" destroy-on-close>
      <div v-loading="detailLoading" class="app-rel-drawer__body">
        <el-alert v-if="detailError" type="error" :closable="false" show-icon :title="detailError" />
        <template v-if="detail">
          <div class="app-rel-drawer__meta">
            <StatusBadge :label="RELEASE_STATUS[detail.status]?.text ?? detail.status" :type="RELEASE_STATUS[detail.status]?.type ?? 'neutral'" />
            <span v-if="detail.type === 'ROLLBACK'">回滚自 #{{ detail.sourceReleaseNo }}</span>
            <span v-else-if="detail.type === 'OFFLINE'">下线（删除全部集群对象，数据卷保留）</span>
            <span>{{ detail.createdBy || '—' }} · {{ fmtDate(detail.createdAt) }}</span>
          </div>
          <p v-if="detail.note" class="app-rel-drawer__note">{{ detail.note }}</p>

          <h4>服务</h4>
          <div v-if="!detailServices.length" class="app-rel-drawer__muted">快照里没有服务。</div>
          <div v-for="[key, d] in detailServices" :key="key" class="app-rel-drawer__svc">
            <div class="app-rel-drawer__svc-head">
              <b>{{ key }}</b>
              <code>{{ d.imageRepo }}:{{ d.imageTag }}</code>
              <span class="app-rel-drawer__muted">
                <template v-if="d.port">端口 {{ d.port }} · </template>副本 {{ d.replicas ?? '—' }}
              </span>
            </div>
            <ul v-if="d.env?.length" class="app-rel-drawer__env">
              <li v-for="e in d.env" :key="e.name">{{ envText(e) }}</li>
            </ul>
          </div>

          <h4>依赖</h4>
          <div v-if="!detail.snapshot?.dependencies?.length" class="app-rel-drawer__muted">无。</div>
          <div v-for="dep in detail.snapshot?.dependencies ?? []" :key="dep.key" class="app-rel-drawer__dep">
            <b>{{ dep.key }}</b> {{ dep.type }}<span v-if="dep.ownership"> · {{ dep.ownership }}</span>
            <span v-if="dep.instanceId"> · 实例 {{ dep.instanceId }}</span>
            <div v-if="dep.external" class="app-rel-drawer__muted">{{ externalText(dep.external) }}</div>
          </div>

          <h4>过程日志</h4>
          <AppExecutionLog :app-id="appId" :release-no="detail.releaseNo" :tenant-id="tenantId" :offline="detail.type === 'OFFLINE'" />

          <h4>集群对象</h4>
          <div v-if="!detail.objects?.length" class="app-rel-drawer__muted">无。</div>
          <div v-for="o in detail.objects ?? []" :key="o.kind + o.name" class="app-rel-drawer__obj">{{ o.kind }}/{{ o.name }}</div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.app-rel-hist__tag { margin-left: 6px; }
.app-rel-hist__pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>

<style>
/* el-drawer 内部节点没有 data-v-*；唯一前缀 app-rel-drawer */
.app-rel-drawer .app-rel-drawer__body { min-height: 60px; }
.app-rel-drawer .app-rel-drawer__meta { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 13px; }
.app-rel-drawer .app-rel-drawer__note { margin: 10px 0 0; }
.app-rel-drawer h4 { margin: 18px 0 8px; font-size: 14px; }
.app-rel-drawer .app-rel-drawer__muted { color: var(--el-text-color-secondary); font-size: 12px; }
.app-rel-drawer .app-rel-drawer__svc { padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.app-rel-drawer .app-rel-drawer__svc-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 13px; }
.app-rel-drawer .app-rel-drawer__env { margin: 6px 0 0; padding-left: 18px; font-family: var(--el-font-family-mono, monospace); font-size: 12px; color: var(--el-text-color-regular); }
.app-rel-drawer .app-rel-drawer__dep { font-size: 13px; padding: 4px 0; }
.app-rel-drawer .app-rel-drawer__obj { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
</style>
