<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import BgpDocPanel from './BgpDocPanel.vue'
import BgpSecretRefSelect from './BgpSecretRefSelect.vue'
import { clusterApi, calicoApi, nodeApi } from '@/api'
import type { K8sCluster, BgpPeer, BgpFilter, WorkloadOption } from '@/types'

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

// ---- filters 多选数据源：该集群现有 BGPFilter（失败降级空列表，不阻塞表单）----
const filterNames = ref<string[]>([])
async function loadFilterOptions(): Promise<void> {
  if (!clusterId.value) return
  try {
    const items: BgpFilter[] = await calicoApi.bgpFilter.list({ clusterId: clusterId.value })
    filterNames.value = items.map((f) => f.name)
  } catch {
    filterNames.value = []
  }
}
watch(clusterId, loadFilterOptions)

// ---- 目标节点下拉数据源：该集群节点（失败降级空列表，仍可手输）----
const nodeNames = ref<string[]>([])
async function loadNodeOptions(): Promise<void> {
  if (!clusterId.value) return
  try {
    const nodes = await nodeApi.list({ clusterId: clusterId.value })
    nodeNames.value = nodes.map((n) => n.name).sort()
  } catch {
    nodeNames.value = []
  }
}
watch(clusterId, loadNodeOptions)

// ---- 本地 workload 选择器下拉数据源：该集群 Deployment/StatefulSet（失败降级空列表，仍可手输）----
const workloadOptions = ref<WorkloadOption[]>([])
async function loadWorkloadOptions(): Promise<void> {
  if (!clusterId.value) return
  try {
    const opts = await calicoApi.formOptions(clusterId.value)
    workloadOptions.value = opts.workloads ?? []
  } catch {
    workloadOptions.value = []
  }
}
watch(clusterId, loadWorkloadOptions)

// ---- 表单模型（name 仅创建可填；空值 = 不下发，交还 Calico 默认）----
const form = reactive({
  name: '',
  node: '',
  nodeSelector: '',
  peerIp: '',
  asNumber: '',
  localAsNumber: '',
  peerSelector: '',
  keepOriginalNextHop: '' as '' | 'true' | 'false',
  nextHopMode: '',
  passwordNs: '',
  passwordName: '',
  passwordKey: '',
  sourceAddress: '',
  maxRestartTime: null as number | null, // 秒（提交时拼 "Ns"）
  keepaliveTime: null as number | null, // 秒（提交时拼 "Ns"）
  numAllowedLocalASNumbers: null as number | null,
  ttlSecurity: null as number | null,
  reachableBy: '',
  filters: [] as string[],
  localWorkloadSelector: '',
  reversePeering: '',
})

const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !clusterId.value) return
  detailState.value = 'loading'
  try {
    const d = await calicoApi.bgpPeer.get(editing.value, clusterId.value)
    form.name = d.name
    form.node = d.node ?? ''
    form.nodeSelector = d.nodeSelector ?? ''
    form.peerIp = d.peerIp ?? ''
    form.asNumber = d.asNumber ?? ''
    form.localAsNumber = d.localAsNumber ?? ''
    form.peerSelector = d.peerSelector ?? ''
    form.keepOriginalNextHop = d.keepOriginalNextHop == null ? '' : (d.keepOriginalNextHop ? 'true' : 'false')
    form.nextHopMode = d.nextHopMode ?? ''
    form.passwordNs = d.password?.namespace ?? ''
    form.passwordName = d.password?.name ?? ''
    form.passwordKey = d.password?.key ?? ''
    form.sourceAddress = d.sourceAddress ?? ''
    form.maxRestartTime = parseDurationToSeconds(d.maxRestartTime)
    form.keepaliveTime = parseDurationToSeconds(d.keepaliveTime)
    form.numAllowedLocalASNumbers = d.numAllowedLocalASNumbers ?? null
    form.ttlSecurity = d.ttlSecurity ?? null
    form.reachableBy = d.reachableBy ?? ''
    form.filters = d.filters ?? []
    form.localWorkloadSelector = d.localWorkloadSelector ?? ''
    form.reversePeering = d.reversePeering ?? ''
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const AS_NUM_RE = /^(\d+|AS\d+)$/i

/** duration 串（如 120s/5m）→ 秒；解析不了返回 null */
function parseDurationToSeconds(v?: string | null): number | null {
  if (!v) return null
  const m = /^(\d+)(ms|s|m|h)?\s*$/.exec(v.trim())
  if (!m) return null
  const n = Number(m[1])
  switch (m[2]) {
    case 'ms': return Math.round(n / 1000)
    case 'm': return n * 60
    case 'h': return n * 3600
    default: return n
  }
}

/** BGP 密码引用：三个表单字段 ↔ 级联下拉组件的 {namespace,name,key} */
const pwd = computed({
  get: () => ({ namespace: form.passwordNs, name: form.passwordName, key: form.passwordKey }),
  set: (v: { namespace: string; name: string; key: string }) => {
    form.passwordNs = v.namespace
    form.passwordName = v.name
    form.passwordKey = v.key
  },
})

// ---- 提交（update 为整对象替换：清空字段 → 后端移除该 spec key，交还 Calico 默认）----
const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !clusterId.value) return
  const finalName = editing.value ?? form.name.trim()
  if (!finalName) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(finalName)) { ElMessage.warning('名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'); return }
  if (!form.peerIp.trim() && !form.peerSelector.trim()) { ElMessage.warning('对端 IP 与对端选择器至少填一个'); return }
  const asNumber = form.asNumber.trim()
  if (asNumber && !AS_NUM_RE.test(asNumber)) { ElMessage.warning('对端 AS 号格式：纯数字（如 65001）或 AS 前缀（如 AS65001）'); return }
  const localAsNumber = form.localAsNumber.trim()
  if (localAsNumber && !AS_NUM_RE.test(localAsNumber)) { ElMessage.warning('本地 AS 号格式：纯数字或 AS 前缀'); return }

  const body: BgpPeer = {
    name: finalName,
    clusterId: clusterId.value,
    node: form.node.trim() || null,
    nodeSelector: form.nodeSelector.trim() || null,
    peerIp: form.peerIp.trim() || null,
    asNumber: asNumber || null,
    localAsNumber: localAsNumber || null,
    peerSelector: form.peerSelector.trim() || null,
    keepOriginalNextHop: form.keepOriginalNextHop === '' ? null : form.keepOriginalNextHop === 'true',
    nextHopMode: form.nextHopMode || null,
    password: form.passwordName.trim()
      ? { namespace: form.passwordNs.trim() || null, name: form.passwordName.trim(), key: form.passwordKey.trim() || null }
      : null,
    sourceAddress: form.sourceAddress || null,
    maxRestartTime: form.maxRestartTime != null ? `${form.maxRestartTime}s` : null,
    keepaliveTime: form.keepaliveTime != null ? `${form.keepaliveTime}s` : null,
    numAllowedLocalASNumbers: form.numAllowedLocalASNumbers,
    ttlSecurity: form.ttlSecurity,
    reachableBy: form.reachableBy.trim() || null,
    filters: form.filters.filter(Boolean),
    localWorkloadSelector: form.localWorkloadSelector.trim() || null,
    reversePeering: form.reversePeering || null,
  }
  saving.value = true
  try {
    if (editing.value) {
      await calicoApi.bgpPeer.update(editing.value, clusterId.value, body)
      ElMessage.success('保存成功')
    } else {
      await calicoApi.bgpPeer.create(clusterId.value, body)
      ElMessage.success('创建成功')
    }
    router.push('/ops/bgppeers')
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/ops/bgppeers') }

const pageTitle = computed(() => (editing.value ? '编辑 BGP 对等体' : '创建 BGP 对等体'))
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

onMounted(async () => {
  await loadClusters()
  if (clusterId.value) {
    await Promise.all([loadFilterOptions(), loadNodeOptions(), loadWorkloadOptions()])
  }
  if (editing.value && clusterId.value) await loadDetail()
})
</script>

<template>
  <div class="editor-page">
    <PageHeader :title="pageTitle" description="Calico BGPPeer（集群级 CRD）">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="detailState === 'error'" title="加载失败" description="该 BGP 对等体可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <template v-if="formVisible">
        <BgpDocPanel
          what="声明「指定节点 ↔ 外部对端 IP」的一条 BGP peering 关系，典型场景是集群节点接物理路由器或四层负载均衡器。"
          how="填对端 IP + 对端 AS 号；用 node（单个节点）或 nodeSelector（节点标签选择器，字符串）圈定应用范围，都不填 = 所有节点。需要路由过滤时，在「过滤器」里勾选已有 BGPFilter（先建 filter 再挂到 peer）。"
          :warnings="[
            'peerIp / AS 号填错则 peering 建不起来，或影响既有路由——保存前与对端设备配置核对。',
            'keepOriginalNextHop 已废弃，请勿启用；控制 NextHop 行为请用 nextHopMode。',
            '删除本对象只影响对应节点到该对端的 peering，不影响节点间 mesh（那是 BGPConfiguration 管的）。',
          ]"
        />

        <el-form label-width="200px" label-position="left" class="editor-form">
          <el-form-item label="名称" required>
            <el-input v-model="form.name" :disabled="!!editing" placeholder="如 router-lab（RFC-1123）" style="max-width: 420px" />
            <FieldHelp tip="BGPPeer 名（集群级唯一，RFC-1123）。创建时可填，编辑时不可改。" />
          </el-form-item>

          <el-form-item label="目标节点（node）">
            <el-select
              v-model="form.node" filterable allow-create default-first-option clearable
              placeholder="选择节点（与 nodeSelector 二选一或同用；不选 = 不限定单节点）" style="max-width: 420px"
            >
              <el-option v-for="n in nodeNames" :key="n" :label="n" :value="n" />
            </el-select>
            <FieldHelp tip="把这条 peering 应用到指定节点。留空 = 不按单节点限定。" />
          </el-form-item>

          <el-form-item label="节点选择器（nodeSelector）">
            <el-input v-model="form.nodeSelector" placeholder="如 projectcalico.org/node-role==edge（字符串，非列表）" style="max-width: 420px" />
            <FieldHelp tip="按节点标签圈定应用范围（K8s 标签选择器语法，单个字符串）。留空 = 所有节点。" />
          </el-form-item>

          <el-form-item label="对端 IP（peerIP）">
            <el-input v-model="form.peerIp" placeholder="如 192.168.100.1（可带端口，如 192.168.100.1:179）" style="max-width: 420px" />
            <FieldHelp tip="外部对端地址。与「对端选择器」至少填一个。" />
          </el-form-item>

          <el-form-item label="对端 AS 号">
            <el-input v-model="form.asNumber" placeholder="如 65001 或 AS65001" style="max-width: 320px" />
            <FieldHelp tip="对端的 BGP AS 号（numorstring）。eBGP 必填；iBGP（与本地相同）可留空。" />
          </el-form-item>

          <el-form-item label="本地 AS 号">
            <el-input v-model="form.localAsNumber" placeholder="默认用 BGPConfiguration / Calico 全局值" style="max-width: 320px" />
            <FieldHelp tip="覆盖本条 peering 使用的本地 AS 号。留空 = 用全局配置。" />
          </el-form-item>

          <el-form-item label="对端选择器（peerSelector）">
            <el-input v-model="form.peerSelector" placeholder="按对端属性动态匹配（高级用法）" style="max-width: 420px" />
            <FieldHelp tip="用 BIRD filter 表达式按对端属性动态建立 peering（高级）。一般直接用 peerIP。" />
          </el-form-item>

          <el-form-item label="保留原始 NextHop">
            <el-select v-model="form.keepOriginalNextHop" style="width: 240px">
              <el-option label="Calico 默认（否）" value="" />
              <el-option label="是（已废弃，勿用）" value="true" />
              <el-option label="否" value="false" />
            </el-select>
            <FieldHelp tip="已废弃字段：保留路由的原始 NextHop。新集群请用 nextHopMode 代替。" />
          </el-form-item>

          <el-form-item label="NextHop 模式">
            <el-select v-model="form.nextHopMode" clearable placeholder="默认（Auto）" style="width: 200px">
              <el-option label="Auto（自动）" value="Auto" />
              <el-option label="Self（改为自身 IP）" value="Self" />
              <el-option label="Keep（保持原值）" value="Keep" />
            </el-select>
            <FieldHelp tip="新版 Calico：宣告路由的 NextHop 处理模式。旧版集群留空。" />
          </el-form-item>

          <el-form-item label="BGP 密码">
            <div class="row-list">
              <BgpSecretRefSelect :cluster-id="clusterId" v-model="pwd" />
            </div>
            <FieldHelp tip="该 peering 的 MD5 认证密码引用（只存 Secret 引用，不存密文）。留空 = 不启用。" />
          </el-form-item>

          <el-form-item label="源地址策略">
            <el-select v-model="form.sourceAddress" clearable placeholder="默认（UseNodeIP）" style="width: 200px">
              <el-option label="UseNodeIP（用节点 IP）" value="UseNodeIP" />
              <el-option label="None（不指定）" value="None" />
            </el-select>
            <FieldHelp tip="BGP 源地址选择策略。一般保持默认 UseNodeIP。" />
          </el-form-item>

          <el-form-item label="Graceful Restart 超时（秒）">
            <el-input-number v-model="form.maxRestartTime" :min="1" controls-position="right" placeholder="默认 120" style="width: 240px" />
            <FieldHelp tip="BGP 重启期间对端保留路由的最长时间（秒）。留空 = BIRD 默认 120 秒。" />
          </el-form-item>

          <el-form-item label="Keepalive 间隔（秒）">
            <el-input-number v-model="form.keepaliveTime" :min="1" controls-position="right" placeholder="默认 90" style="width: 240px" />
            <FieldHelp tip="BGP keepalive 间隔（秒）。留空 = BIRD 默认 90 秒。" />
          </el-form-item>

          <el-form-item label="允许本地 AS 数">
            <el-input-number v-model="form.numAllowedLocalASNumbers" :min="0" controls-position="right" placeholder="默认" style="width: 200px" />
            <FieldHelp tip="iBGP 场景允许路径中出现的本地 AS 数（防环路放宽）。留空 = 默认。" />
          </el-form-item>

          <el-form-item label="TTL 安全跳数">
            <el-input-number v-model="form.ttlSecurity" :min="0" controls-position="right" placeholder="默认（关闭）" style="width: 200px" />
            <FieldHelp tip="GTSM：只接受 TTL 不小于该值的 BGP 包（10 = 仅同机，254/255 = 直连）。留空 = 关闭。" />
          </el-form-item>

          <el-form-item label="静态路由网关">
            <el-input v-model="form.reachableBy" placeholder="如 192.168.100.254（对端不可达时的下一跳）" style="max-width: 420px" />
            <FieldHelp tip="reachableBy：对端 IP 不可直接路由时，经该网关可达。留空 = 直连。" />
          </el-form-item>

          <el-form-item label="应用的 BGPFilter">
            <el-select
              v-model="form.filters" multiple filterable allow-create default-first-option :reserve-keyword="false"
              placeholder="选择该集群已有的 BGPFilter（按顺序生效）" style="width: 100%; max-width: 520px"
            >
              <el-option v-for="n in filterNames" :key="n" :label="n" :value="n" />
            </el-select>
            <FieldHelp tip="引用 BGPFilter 做路由过滤（有序，多条依次评估）。不挂 filter = 走 Calico 默认 BGP 行为。" />
          </el-form-item>

          <el-form-item label="本地 Workload 选择器">
            <el-select
              v-model="form.localWorkloadSelector" filterable allow-create default-first-option clearable
              placeholder="下拉选工作负载，或手输标签选择器（高级）" style="max-width: 420px"
            >
              <el-option
                v-for="w in workloadOptions" :key="w.namespace + '/' + w.name"
                :label="`${w.namespace}/${w.name}（${w.kind}）`" :value="`app=${w.name}`"
              />
            </el-select>
            <FieldHelp tip="新版 Calico：把 peering 限定到匹配的 workload（标签选择器）。下拉选中自动填 app=&lt;名称&gt;;若 workload 未用 app 标签，请清空后手输实际选择器。旧版集群留空。" />
          </el-form-item>

          <el-form-item label="反向 Peering">
            <el-select v-model="form.reversePeering" clearable placeholder="默认（Auto）" style="width: 200px">
              <el-option label="Auto（自动）" value="Auto" />
              <el-option label="Manual（手动）" value="Manual" />
            </el-select>
            <FieldHelp tip="新版 Calico：是否自动建立对端反向 peering。旧版集群留空。" />
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
            <el-button @click="goBack">取消</el-button>
          </el-form-item>
        </el-form>
      </template>
    </div>
  </div>
</template>

<style scoped>
.editor-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.editor-panel { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 20px 24px; }
.editor-form { max-width: 860px; }
.row-list { display: flex; flex-direction: column; gap: 8px; width: 100%; max-width: 560px; }
.row-line { display: flex; align-items: center; gap: 10px; }
.row-line.wide { max-width: none; }
</style>
