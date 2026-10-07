<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { clusterApi, calicoApi } from '@/api'
import type { K8sCluster, K8sIpool, K8sIpReservation, IpamIpDetail, IpamBlockStat } from '@/types'
import { isIp, cidrToRange, containsInCidr } from '@/utils/ipUtil'
import { CALICO_POOL_LABEL } from '@/utils/calico'

const route = useRoute()
const router = useRouter()

// ---- 上下文：集群级（clusterId 来自 query + 下拉切换）；?name= → 编辑回填，无 name → 创建 ----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
const editing = ref<string | null>(route.query.name as string | null)

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

// ---- 池列表（供「所属池」选择 + picker + 越界校验）----
const pools = ref<K8sIpool[]>([])
async function loadPools(): Promise<void> {
  if (!clusterId.value) return
  try {
    pools.value = await calicoApi.ippool.list({ clusterId: clusterId.value })
  } catch {
    pools.value = [] // 拦截器已提示；picker/校验降级
  }
}

// ---- 表单模型：保留项 = reservedCidrs（CIDR 列表；单 IP = "ip/32"、范围 = "cidr"）----
// poolName 可由 ?pool= 预选（地址池页「保留 IP」入口直接带池跳转）
const form = reactive({
  name: '',
  poolName: (route.query.pool as string) || '',
  reservedCidrs: [] as string[],
})

const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
/** 编辑态既有 labels（提交时合并保留，防 SSA 清掉外部打的标签） */
const existingLabels = ref<Record<string, string>>({})
async function loadDetail(): Promise<void> {
  if (!editing.value || !clusterId.value) return
  detailState.value = 'loading'
  try {
    const d = await calicoApi.ipreservation.get(editing.value, clusterId.value)
    form.name = d.name
    form.reservedCidrs = [...(d.reservedCidrs ?? [])]
    existingLabels.value = { ...(d.labels ?? {}) }
    // 预填所属池：优先读归属 label（创建时写入）；缺失/池已不存在才反查（CIDR 包含，Calico 禁池重叠 → 无歧义）
    const labeled = d.labels?.[CALICO_POOL_LABEL]
    if (labeled && pools.value.some((pl) => pl.name === labeled)) {
      form.poolName = labeled
    } else {
      const first = d.reservedCidrs?.[0]
      if (first) {
        const ip = first.includes('/') ? (first.split('/')[0] ?? first) : first
        const p = pools.value.find((pl) => pl.cidr && containsInCidr(pl.cidr, ip))
        if (p) form.poolName = p.name
      }
    }
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

// ---- 保留 CIDR 增删 ----
const newCidr = ref('')
function addCidr(): void {
  const v = newCidr.value.trim()
  if (!v) return
  if (cidrToRange(v) == null) { ElMessage.warning('非法 CIDR（如 10.48.3.5/32 或 10.48.3.64/28）'); return }
  if (form.reservedCidrs.includes(v)) { ElMessage.warning('已存在该保留项'); return }
  form.reservedCidrs.push(v)
  newCidr.value = ''
}
function removeCidr(c: string): void {
  form.reservedCidrs = form.reservedCidrs.filter((x) => x !== c)
}

// ---- picker：从所选池的空闲块点选 → 展开块内所有 IP（带状态），勾选空闲项加入保留 ----
const FREE_LIMIT = 20
const freeBlocks = ref<string[]>([])
const freeOffset = ref(0)
const freeHasMore = ref(false)
const loadingFree = ref(false)
const selectedBlock = ref('')

// 展开块的 per-IP + 勾选（数组保响应性稳妥）
const blockIps = ref<IpamIpDetail[]>([])
const loadingBlockIps = ref(false)
const checkedIps = ref<string[]>([])

async function loadNextFree(offset: number): Promise<void> {
  if (!clusterId.value || !form.poolName) { ElMessage.warning('请先选择所属池'); return }
  freeOffset.value = offset
  loadingFree.value = true
  try {
    const next = await calicoApi.ipam.nextFreeBlocks(clusterId.value, form.poolName, offset, FREE_LIMIT)
    freeBlocks.value = next
    freeHasMore.value = next.length === FREE_LIMIT // 满页 → 可能还有
  } catch { /* 拦截器提示 */ } finally {
    loadingFree.value = false
  }
}
function nextFree(): void { void loadNextFree(freeOffset.value + FREE_LIMIT) }
function prevFree(): void { if (freeOffset.value > 0) void loadNextFree(freeOffset.value - FREE_LIMIT) }

// 已认领但仍有空闲 IP 的块（ipam.blocks 过滤 free>0）→ 与候选块同走点选展开流程
const claimedBlocks = ref<IpamBlockStat[]>([])
const loadingClaimed = ref(false)
async function loadClaimedBlocks(): Promise<void> {
  if (!clusterId.value || !form.poolName) return
  loadingClaimed.value = true
  try {
    const all = await calicoApi.ipam.blocks(clusterId.value, form.poolName)
    claimedBlocks.value = all.filter((b) => (b.free ?? 0) > 0)
  } catch {
    claimedBlocks.value = [] // 拦截器已提示；降级为空
  } finally {
    loadingClaimed.value = false
  }
}
/** 选池后两个来源一起拉：未物化候选块 + 已认领有空闲的块 */
function loadPickerSources(): void {
  if (!clusterId.value || !form.poolName) return
  void loadNextFree(0)
  void loadClaimedBlocks()
}

/** 点块 → 展开该块所有 IP（free/reserved/allocated），重置勾选。 */
async function pickBlock(cidr: string): Promise<void> {
  selectedBlock.value = cidr
  checkedIps.value = []
  if (!clusterId.value) return
  loadingBlockIps.value = true
  try {
    blockIps.value = await calicoApi.ipam.blockIps(clusterId.value, cidr)
  } catch {
    blockIps.value = [] // 拦截器已提示
  } finally {
    loadingBlockIps.value = false
  }
}

function toggleIp(ip: string, status: string): void {
  if (status !== 'free') return
  const i = checkedIps.value.indexOf(ip)
  if (i >= 0) checkedIps.value.splice(i, 1)
  else checkedIps.value.push(ip)
}
function selectAllFree(): void {
  checkedIps.value = blockIps.value.filter((b) => b.status === 'free').map((b) => b.ip)
}
/** 勾选的 IP 各加为单点保留（/32、v6 /128），去重后清空勾选。 */
function addChecked(): void {
  let added = 0
  for (const ip of checkedIps.value) {
    const cidr = ip.includes(':') ? `${ip}/128` : `${ip}/32`
    if (!form.reservedCidrs.includes(cidr)) { form.reservedCidrs.push(cidr); added++ }
  }
  checkedIps.value = []
  if (added) ElMessage.success(`已加入 ${added} 条保留项`)
}
function statusTagType(status: string): 'success' | 'warning' | 'info' {
  return status === 'free' ? 'success' : status === 'reserved' ? 'warning' : 'info'
}

// ---- jump-to：点查某 IP 是否空闲；空闲则加为单 IP 保留（/32、v6 /128）----
const checkInput = ref('')
const checkResult = ref<boolean | null>(null)
const checking = ref(false)
async function doJumpCheck(): Promise<void> {
  const v = checkInput.value.trim()
  if (!v || !clusterId.value) return
  checking.value = true
  checkResult.value = null
  try {
    checkResult.value = await calicoApi.ipam.isFree(clusterId.value, v)
  } catch { /* 拦截器提示 */ } finally {
    checking.value = false
  }
}
function useJumpValue(): void {
  const v = checkInput.value.trim()
  if (!v || !isIp(v)) return
  const cidr = v.includes(':') ? `${v}/128` : `${v}/32`
  if (form.reservedCidrs.includes(cidr)) { ElMessage.warning('已存在该保留项'); return }
  form.reservedCidrs.push(cidr)
}

// ---- 提交：名称 + 至少一条 + 每条合法且落在所选池内；单 IP 创建时点查空闲（范围以 Calico 应用期为准）----
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !clusterId.value) return
  const finalName = editing.value ?? form.name.trim()
  if (!finalName) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(finalName)) { ElMessage.warning('名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'); return }

  const cidrs = form.reservedCidrs.map((c) => c.trim()).filter(Boolean)
  if (!cidrs.length) { ElMessage.warning('请至少添加一条保留 CIDR'); return }

  const pool = pools.value.find((p) => p.name === form.poolName)
  if (!pool || !pool.cidr) { ElMessage.warning('请选择所属池（用于越界校验）'); return }
  const poolRange = cidrToRange(pool.cidr)
  for (const c of cidrs) {
    const r = cidrToRange(c)
    if (r == null) { ElMessage.warning(`非法 CIDR：${c}`); return }
    if (!poolRange || r.start < poolRange.start || r.end > poolRange.end) {
      ElMessage.warning(`保留项「${c}」超出所选池「${pool.name}」（${pool.cidr}）`); return
    }
  }

  // 单 IP（/32、/128）：创建时点查必须空闲；编辑既有（含自身已占）跳过，交由 Calico 应用期校验
  if (!editing.value) {
    for (const c of cidrs) {
      const slash = c.indexOf('/')
      const prefix = slash >= 0 ? c.slice(slash + 1) : '32'
      if (prefix === '32' || prefix === '128') {
        const ip = slash >= 0 ? c.slice(0, slash) : c
        const free = await calicoApi.ipam.isFree(clusterId.value, ip).catch(() => false)
        if (!free) { ElMessage.warning(`该 IP 当前非空闲（已被分配或保留）：${ip}`); return }
      }
    }
  }

  // labels：既有合并 + 池归属 label（消费方免反查；编辑改池即更新归属）
  const body: K8sIpReservation = {
    name: finalName, clusterId: clusterId.value, reservedCidrs: cidrs,
    labels: { ...existingLabels.value, [CALICO_POOL_LABEL]: form.poolName },
  }
  saving.value = true
  try {
    if (editing.value) {
      await calicoApi.ipreservation.update(editing.value, clusterId.value, body)
      ElMessage.success('保存成功')
    } else {
      await calicoApi.ipreservation.create(clusterId.value, body)
      ElMessage.success('创建成功')
    }
    router.push('/ops/ipreservations')
  } catch { /* 拦截器提示（含 Calico 应用期校验失败） */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/ops/ipreservations') }

const pageTitle = computed(() => (editing.value ? '编辑保留 IP' : '创建保留 IP'))
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

// ---- 上下文联动：切集群重置池/派生缓存；换池重置空闲块列表 + 展开的块 IP ----
watch(clusterId, () => {
  freeBlocks.value = []
  selectedBlock.value = ''
  blockIps.value = []
  checkedIps.value = []
  void loadPools()
})
watch(() => form.poolName, (name) => {
  freeBlocks.value = []
  claimedBlocks.value = []
  selectedBlock.value = ''
  blockIps.value = []
  checkedIps.value = []
  // 选池即自动加载两个 picker 来源；清空则复位分页
  if (name && clusterId.value) loadPickerSources()
  else { freeOffset.value = 0; freeHasMore.value = false }
})

onMounted(async () => {
  await loadClusters()
  await loadPools()
  // 创建模式带 ?pool= 预选（初始值不触发 watch）→ 手动拉一次两个来源
  if (!editing.value && form.poolName) loadPickerSources()
  if (editing.value && clusterId.value) {
    const before = form.poolName
    await loadDetail()
    // 回填池与 ?pool= 相同 → watch 不触发，补拉一次
    if (form.poolName && form.poolName === before) loadPickerSources()
  }
})
</script>

<template>
  <div class="editor-page">
    <PageHeader :title="pageTitle" description="Calico IPReservation（集群级 CRD）——保留项不会被自动分配">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="detailState === 'error'" title="加载失败" description="该保留 IP 可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <el-form v-if="formVisible" label-position="left" label-width="140px" class="editor-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 vip-range-1（RFC-1123）" style="max-width: 420px" />
          <FieldHelp tip="IPReservation 名（集群级唯一，RFC-1123）。创建时可填，编辑时不可改。" />
        </el-form-item>

        <el-form-item label="所属池" required>
          <el-select v-model="form.poolName" placeholder="选择保留项所在的地址池" style="width: 320px">
            <el-option v-for="p in pools" :key="p.name" :label="`${p.name}（${p.cidr ?? '—'}）`" :value="p.name" />
          </el-select>
          <FieldHelp tip="保留项必须落在某地址池内。选择后用于越界校验，并驱动下方空闲块 picker。" />
        </el-form-item>

        <!-- 保留 CIDR 列表（核心）：单点=/32、范围=CIDR，可多条 -->
        <el-form-item label="保留 CIDR" required>
          <div class="cidr-editor">
            <div v-if="form.reservedCidrs.length" class="cidr-list">
              <el-tag v-for="c in form.reservedCidrs" :key="c" closable class="mono cidr-chip" @close="removeCidr(c)">{{ c }}</el-tag>
            </div>
            <div v-else class="muted hint">尚未添加保留项——可下方点选空闲块 / jump-to 单 IP，或直接输入 CIDR。</div>
            <div class="cidr-add">
              <el-input v-model="newCidr" placeholder="如 10.48.3.5/32（单 IP）或 10.48.3.64/28（范围）" style="width: 340px" clearable @keyup.enter="addCidr" />
              <el-button type="primary" @click="addCidr">添加</el-button>
            </div>
          </div>
        </el-form-item>

        <!-- picker：从空闲块点选 → 加整块 CIDR；jump-to 定位单 IP -->
        <el-form-item label="快速添加">
          <div class="picker">
            <div class="picker-head">
              <span class="muted">① 从所选池的空闲块点选（点块展开块内所有 IP，勾选空闲项）：</span>
              <el-button size="small" :icon="Refresh" circle :loading="loadingFree || loadingClaimed" @click="loadPickerSources" />
              <div class="pager">
                <el-button size="small" :disabled="freeOffset <= 0" @click="prevFree">上一页</el-button>
                <el-button size="small" :disabled="!freeHasMore" @click="nextFree">下一页</el-button>
              </div>
            </div>
            <div v-if="freeBlocks.length" class="free-list">
              <code v-for="c in freeBlocks" :key="c" class="mono free-chip" :class="{ active: selectedBlock === c }" @click="pickBlock(c)">{{ c }}</code>
            </div>
            <div v-else-if="!loadingFree && !loadingClaimed" class="muted hint">选择所属池后自动加载空闲块；或下方 jump-to 定位单 IP / 直接输入 CIDR。</div>

            <!-- 已认领但仍有空闲 IP 的块（点选展开真实 per-IP） -->
            <template v-if="claimedBlocks.length">
              <div class="claimed-head muted">已认领块中仍有空闲 IP（{{ claimedBlocks.length }}）：</div>
              <div class="free-list">
                <code v-for="b in claimedBlocks" :key="b.cidr" class="mono free-chip" :class="{ active: selectedBlock === b.cidr }" @click="pickBlock(b.cidr)">
                  {{ b.cidr }}<span class="chip-meta">{{ b.node ?? '—' }} · 空闲 {{ b.free }}</span>
                </code>
              </div>
            </template>

            <!-- 展开的块内 IP：勾选空闲项加入保留 -->
            <div v-if="selectedBlock" class="block-ips">
              <div class="block-ips-head">
                <span class="muted">{{ selectedBlock }} 内的 IP（{{ blockIps.length }}）：</span>
                <el-button size="small" :disabled="loadingBlockIps" @click="selectAllFree">全选空闲</el-button>
                <el-button size="small" type="primary" :disabled="!checkedIps.length" @click="addChecked">加入保留项（{{ checkedIps.length }}）</el-button>
              </div>
              <div v-loading="loadingBlockIps" class="ip-grid">
                <label v-for="b in blockIps" :key="b.ip" class="ip-cell" :class="{ 'is-disabled': b.status !== 'free' }">
                  <el-checkbox :model-value="checkedIps.includes(b.ip)" :disabled="b.status !== 'free'" @change="toggleIp(b.ip, b.status)" />
                  <code class="mono">{{ b.ip }}</code>
                  <el-tag size="small" :type="statusTagType(b.status)">{{ b.status }}</el-tag>
                </label>
              </div>
            </div>

            <div class="jump-bar">
              <span class="muted">② 定位某 IP（空闲则加为单 IP）：</span>
              <el-input v-model="checkInput" placeholder="如 10.48.3.5" style="width: 260px" clearable @keyup.enter="doJumpCheck" />
              <el-button :icon="Search" :loading="checking" @click="doJumpCheck">点查</el-button>
              <el-tag v-if="checkResult === true" type="success">空闲</el-tag>
              <el-tag v-else-if="checkResult === false" type="danger">已占用 / 保留</el-tag>
              <el-button size="small" :disabled="!(checkResult === true && isIp(checkInput.trim()))" @click="useJumpValue">加为单 IP（/32）</el-button>
            </div>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
          <el-button @click="goBack">取消</el-button>
          <span class="muted hint">保留后该段 IP 不会被自动分配；单点=/32、范围=CIDR，可多条。以 Calico 应用期校验为准。</span>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.editor-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.editor-panel {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 24px;
}
.editor-form { max-width: 820px; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }
.hint { font-size: 12px; margin-left: 8px; }

.cidr-editor { display: flex; flex-direction: column; gap: 10px; width: 100%; max-width: 640px; }
.cidr-list { display: flex; flex-wrap: wrap; gap: 8px; }
.cidr-chip { font-size: 12.5px; }
.cidr-add { display: flex; align-items: center; gap: 8px; }

.picker { display: flex; flex-direction: column; gap: 10px; width: 100%; max-width: 720px; }
.picker-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pager { display: flex; gap: 8px; }
.free-list { display: flex; flex-wrap: wrap; gap: 8px; }
.free-chip {
  padding: 3px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--panel-hover);
  font-size: 12.5px; cursor: pointer; user-select: none;
}
.free-chip:hover { border-color: var(--accent); color: var(--accent); }
.free-chip.active { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.claimed-head { margin-top: 10px; font-size: 12.5px; }
.chip-meta { margin-left: 6px; font-size: 11.5px; color: var(--text-3); font-family: inherit; }

/* 展开的块内 IP 勾选面板 */
.block-ips { display: flex; flex-direction: column; gap: 8px; padding: 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--panel-hover); }
.block-ips-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ip-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 2px 14px; max-height: 320px; overflow-y: auto; }
.ip-cell { display: flex; align-items: center; gap: 6px; font-size: 12.5px; padding: 2px 0; }
.ip-cell .mono { flex: 1 1 auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ip-cell.is-disabled { opacity: .45; }

.jump-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding-top: 4px; border-top: 1px dashed var(--border); }
</style>
