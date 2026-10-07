<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import BgpDocPanel from './BgpDocPanel.vue'
import BgpSecretRefSelect from './BgpSecretRefSelect.vue'
import { clusterApi, calicoApi } from '@/api'
import type { K8sCluster, BgpConfiguration } from '@/types'

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

// ---- 表单模型（name 仅创建可填；空值 = 不下发，交还 Calico 默认）----
interface CidrRow { cidr: string }
interface CommunityRow { name: string; value: string }
interface PrefixAdRow { cidr: string; communities: string[] }

const form = reactive({
  name: '',
  asNumber: '',
  nodeToNodeMesh: '' as '' | 'true' | 'false', // '' = Calico 默认（启用）
  listenPort: null as number | null,
  logSeverityScreen: '',
  bindMode: '',
  serviceClusterIPs: [] as CidrRow[],
  serviceExternalIPs: [] as CidrRow[],
  serviceLoadBalancerIPs: [] as CidrRow[],
  communities: [] as CommunityRow[],
  prefixAdvertisements: [] as PrefixAdRow[],
  nodeMeshPasswordNs: '',
  nodeMeshPasswordName: '',
  nodeMeshPasswordKey: '',
  ignoredInterfaces: [] as CidrRow[], // 复用 {cidr} 行结构，字段语义=接口名
  // 新版字段（旧版 Calico 上留空即可）
  serviceLoadBalancerAggregation: '',
  programClusterRoutes: '',
  localWorkloadPeeringIPV4: '',
  localWorkloadPeeringIPV6: '',
  ipv4NormalRoutePriority: null as number | null,
  ipv6NormalRoutePriority: null as number | null,
})

const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !clusterId.value) return
  detailState.value = 'loading'
  try {
    const d = await calicoApi.bgpConfiguration.get(editing.value, clusterId.value)
    form.name = d.name
    form.asNumber = d.asNumber ?? ''
    form.nodeToNodeMesh = d.nodeToNodeMeshEnabled == null ? '' : (d.nodeToNodeMeshEnabled ? 'true' : 'false')
    form.listenPort = d.listenPort ?? null
    form.logSeverityScreen = d.logSeverityScreen ?? ''
    form.bindMode = d.bindMode ?? ''
    form.serviceClusterIPs = (d.serviceClusterIPs ?? []).map((b) => ({ cidr: b.cidr ?? '' }))
    form.serviceExternalIPs = (d.serviceExternalIPs ?? []).map((b) => ({ cidr: b.cidr ?? '' }))
    form.serviceLoadBalancerIPs = (d.serviceLoadBalancerIPs ?? []).map((b) => ({ cidr: b.cidr ?? '' }))
    form.communities = (d.communities ?? []).map((c) => ({ name: c.name ?? '', value: c.value ?? '' }))
    form.prefixAdvertisements = (d.prefixAdvertisements ?? []).map((p) => ({ cidr: p.cidr ?? '', communities: p.communities ?? [] }))
    form.nodeMeshPasswordNs = d.nodeMeshPassword?.namespace ?? ''
    form.nodeMeshPasswordName = d.nodeMeshPassword?.name ?? ''
    form.nodeMeshPasswordKey = d.nodeMeshPassword?.key ?? ''
    form.ignoredInterfaces = (d.ignoredInterfaces ?? []).map((v) => ({ cidr: v }))
    form.serviceLoadBalancerAggregation = d.serviceLoadBalancerAggregation ?? ''
    form.programClusterRoutes = d.programClusterRoutes ?? ''
    form.localWorkloadPeeringIPV4 = d.localWorkloadPeeringIPV4 ?? ''
    form.localWorkloadPeeringIPV6 = d.localWorkloadPeeringIPV6 ?? ''
    form.ipv4NormalRoutePriority = d.ipv4NormalRoutePriority ?? null
    form.ipv6NormalRoutePriority = d.ipv6NormalRoutePriority ?? null
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

function addRow<T>(list: T[], item: T): void { list.push(item) }
function removeRow(list: unknown[], i: number): void { (list as unknown[]).splice(i, 1) }

const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const AS_NUM_RE = /^(\d+|AS\d+)$/i

// ---- 提交（update 为整对象替换：清空字段 → 后端移除该 spec key，交还 Calico 默认）----
const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !clusterId.value) return
  const finalName = editing.value ?? form.name.trim()
  if (!finalName) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(finalName)) { ElMessage.warning('名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'); return }
  const asNumber = form.asNumber.trim()
  if (asNumber && !AS_NUM_RE.test(asNumber)) { ElMessage.warning('AS 号格式：纯数字（如 64512）或 AS 前缀（如 AS65000）'); return }
  if (form.listenPort != null && (form.listenPort < 1 || form.listenPort > 65535)) { ElMessage.warning('监听端口须在 1-65535'); return }

  const cidrRows = (rows: CidrRow[]) => rows.map((r) => r.cidr.trim()).filter(Boolean).map((cidr) => ({ cidr }))
  const body: BgpConfiguration = {
    name: finalName,
    clusterId: clusterId.value,
    asNumber: asNumber || null,
    nodeToNodeMeshEnabled: form.nodeToNodeMesh === '' ? null : form.nodeToNodeMesh === 'true',
    listenPort: form.listenPort,
    logSeverityScreen: form.logSeverityScreen.trim() || null,
    bindMode: form.bindMode || null,
    serviceClusterIPs: cidrRows(form.serviceClusterIPs),
    serviceExternalIPs: cidrRows(form.serviceExternalIPs),
    serviceLoadBalancerIPs: cidrRows(form.serviceLoadBalancerIPs),
    communities: form.communities
      .filter((c) => c.name.trim() || c.value.trim())
      .map((c) => ({ name: c.name.trim(), value: c.value.trim() })),
    prefixAdvertisements: form.prefixAdvertisements
      .filter((p) => p.cidr.trim())
      .map((p) => ({ cidr: p.cidr.trim(), communities: p.communities.filter(Boolean) })),
    nodeMeshPassword: form.nodeMeshPasswordName.trim()
      ? { namespace: form.nodeMeshPasswordNs.trim() || null, name: form.nodeMeshPasswordName.trim(), key: form.nodeMeshPasswordKey.trim() || null }
      : null,
    ignoredInterfaces: form.ignoredInterfaces.map((r) => r.cidr.trim()).filter(Boolean),
    serviceLoadBalancerAggregation: form.serviceLoadBalancerAggregation.trim() || null,
    programClusterRoutes: form.programClusterRoutes.trim() || null,
    localWorkloadPeeringIPV4: form.localWorkloadPeeringIPV4.trim() || null,
    localWorkloadPeeringIPV6: form.localWorkloadPeeringIPV6.trim() || null,
    ipv4NormalRoutePriority: form.ipv4NormalRoutePriority,
    ipv6NormalRoutePriority: form.ipv6NormalRoutePriority,
  }
  saving.value = true
  try {
    if (editing.value) {
      await calicoApi.bgpConfiguration.update(editing.value, clusterId.value, body)
      ElMessage.success('保存成功')
    } else {
      await calicoApi.bgpConfiguration.create(clusterId.value, body)
      ElMessage.success('创建成功')
    }
    router.push('/ops/bgpconfigurations')
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/ops/bgpconfigurations') }

const pageTitle = computed(() => (editing.value ? '编辑 BGP 配置' : '创建 BGP 配置'))
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

/** NodeMesh 密码引用：三个表单字段 ↔ 级联下拉组件的 {namespace,name,key} */
const meshPwd = computed({
  get: () => ({ namespace: form.nodeMeshPasswordNs, name: form.nodeMeshPasswordName, key: form.nodeMeshPasswordKey }),
  set: (v: { namespace: string; name: string; key: string }) => {
    form.nodeMeshPasswordNs = v.namespace
    form.nodeMeshPasswordName = v.name
    form.nodeMeshPasswordKey = v.key
  },
})

onMounted(async () => {
  await loadClusters()
  if (editing.value && clusterId.value) await loadDetail()
})
</script>

<template>
  <div class="editor-page">
    <PageHeader :title="pageTitle" description="Calico BGPConfiguration（集群级 CRD）——仅平台管理员可操作">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="detailState === 'error'" title="加载失败" description="该 BGP 配置可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <template v-if="formVisible">
        <BgpDocPanel
          what="Calico 节点 BGP 的集群级全局默认配置：AS 号、节点间全 mesh（nodeToNodeMeshEnabled）、监听端口、日志级别、宣告哪些 Service IP 段（ClusterIP / ExternalIP / LoadBalancer IP）、communities 注册表等。"
          how="创建后对集群内启用 BGP 的节点整体生效，通常一个对象即可（如 name=default）；需要按对端细化时配合 BGPPeer（spec.filters 引用 BGPFilter）。留空的字段使用 Calico 内置默认值。"
          :warnings="[
            '这是全局配置：修改 AS 号、关闭节点 Mesh 或改动监听端口，可能立即中断节点间与对外 BGP 连通性，请在变更窗口操作。',
            '删除唯一的 BGPConfiguration 会让集群回落到 Calico 内置默认值（AS 64512、mesh 开启），效果等同一次全局变更。',
            '仅平台管理员可创建/编辑/删除本对象；BGPPeer / BGPFilter 的权限更宽，请按需拆分职责。',
          ]"
        />

        <el-form label-width="200px" label-position="left" class="editor-form">
          <el-form-item label="名称" required>
            <el-input v-model="form.name" :disabled="!!editing" placeholder="如 default（RFC-1123）" style="max-width: 420px" />
            <FieldHelp tip="BGPConfiguration 名（集群级唯一，RFC-1123）。创建时可填，编辑时不可改。" />
          </el-form-item>

          <el-form-item label="AS 号（asNumber）">
            <el-input v-model="form.asNumber" placeholder="如 64512 或 AS65000" style="max-width: 320px" />
            <FieldHelp tip="本集群节点对外使用的 BGP AS 号（numorstring）。留空用 Calico 默认 64512。改错会直接影响所有 peering。" />
          </el-form-item>

          <el-form-item label="节点 Mesh">
            <el-select v-model="form.nodeToNodeMesh" style="width: 200px">
              <el-option label="Calico 默认（启用）" value="" />
              <el-option label="启用" value="true" />
              <el-option label="禁用" value="false" />
            </el-select>
            <FieldHelp tip="节点间 BGP 全 mesh。禁用后节点之间不再交换路由，仅保留到外部对端的 peering——一般不要动。" />
          </el-form-item>

          <el-form-item label="监听端口">
            <el-input-number v-model="form.listenPort" :min="1" :max="65535" controls-position="right" placeholder="默认 179" style="width: 200px" />
            <FieldHelp tip="BGP 监听端口，默认 179。改动前确认对端/防火墙同步调整。" />
          </el-form-item>

          <el-form-item label="日志级别">
            <el-select v-model="form.logSeverityScreen" clearable placeholder="默认 Info" style="width: 240px">
              <el-option label="Debug（排查 peering 用）" value="Debug" />
              <el-option label="Info（默认）" value="Info" />
              <el-option label="Warning" value="Warning" />
              <el-option label="Error" value="Error" />
              <el-option label="Critical" value="Critical" />
            </el-select>
            <FieldHelp tip="BGP 日志输出到屏幕的最低级别，默认 Info。排查 peering 问题可临时调成 Debug。" />
          </el-form-item>

          <el-form-item label="绑定模式（bindMode）">
            <el-select v-model="form.bindMode" clearable placeholder="默认（全部地址）" style="width: 200px">
              <el-option label="None（不额外绑定）" value="None" />
              <el-option label="NodeIP（仅节点 IP）" value="NodeIP" />
            </el-select>
            <FieldHelp tip="控制 BGP 绑定哪些本地地址，默认全部。多网卡环境可用 NodeIP 收敛。" />
          </el-form-item>

          <el-form-item label="Service ClusterIPs">
            <div class="row-list">
              <div v-for="(r, i) in form.serviceClusterIPs" :key="i" class="row-line">
                <el-input v-model="r.cidr" placeholder="如 10.96.0.0/12" style="flex: 1" />
                <el-button link type="danger" @click="removeRow(form.serviceClusterIPs, i)">删除</el-button>
              </div>
              <el-button size="small" @click="addRow(form.serviceClusterIPs, { cidr: '' })">添加 CIDR</el-button>
            </div>
            <FieldHelp tip="宣告给 BGP 对端的 Service ClusterIP 段。留空 = 不额外宣告（Calico 默认行为）。" />
          </el-form-item>

          <el-form-item label="Service ExternalIPs">
            <div class="row-list">
              <div v-for="(r, i) in form.serviceExternalIPs" :key="i" class="row-line">
                <el-input v-model="r.cidr" placeholder="如 192.168.50.0/24" style="flex: 1" />
                <el-button link type="danger" @click="removeRow(form.serviceExternalIPs, i)">删除</el-button>
              </div>
              <el-button size="small" @click="addRow(form.serviceExternalIPs, { cidr: '' })">添加 CIDR</el-button>
            </div>
            <FieldHelp tip="宣告 Service 的 externalIPs 段。留空 = 不额外宣告。" />
          </el-form-item>

          <el-form-item label="Service LoadBalancerIPs">
            <div class="row-list">
              <div v-for="(r, i) in form.serviceLoadBalancerIPs" :key="i" class="row-line">
                <el-input v-model="r.cidr" placeholder="如 192.168.60.0/24" style="flex: 1" />
                <el-button link type="danger" @click="removeRow(form.serviceLoadBalancerIPs, i)">删除</el-button>
              </div>
              <el-button size="small" @click="addRow(form.serviceLoadBalancerIPs, { cidr: '' })">添加 CIDR</el-button>
            </div>
            <FieldHelp tip="宣告 Service 的 loadBalancerIP 段。留空 = 不额外宣告。" />
          </el-form-item>

          <el-form-item label="Communities 注册表">
            <div class="row-list">
              <div v-for="(r, i) in form.communities" :key="i" class="row-line">
                <el-input v-model="r.name" placeholder="名称，如 INTERNAL" style="flex: 1" />
                <el-input v-model="r.value" placeholder="值，如 64512:64512" style="flex: 1" />
                <el-button link type="danger" @click="removeRow(form.communities, i)">删除</el-button>
              </div>
              <el-button size="small" @click="addRow(form.communities, { name: '', value: '' })">添加 community</el-button>
            </div>
            <FieldHelp tip="命名 community 注册表（名称 → 值），供 BGPFilter 规则按名字引用。留空 = 不定义。" />
          </el-form-item>

          <el-form-item label="PrefixAdvertisements">
            <div class="row-list">
              <div v-for="(r, i) in form.prefixAdvertisements" :key="i" class="row-line wide">
                <el-input v-model="r.cidr" placeholder="CIDR，如 10.48.0.0/16" style="width: 220px" />
                <el-select
                  v-model="r.communities" multiple filterable allow-create default-first-option :reserve-keyword="false"
                  placeholder="附加 communities（回车添加）" style="flex: 1"
                />
                <el-button link type="danger" @click="removeRow(form.prefixAdvertisements, i)">删除</el-button>
              </div>
              <el-button size="small" @click="addRow(form.prefixAdvertisements, { cidr: '', communities: [] })">添加宣告</el-button>
            </div>
            <FieldHelp tip="对指定前缀宣告时附加的 communities（如标记、降权）。留空 = 不设置。" />
          </el-form-item>

          <el-form-item label="NodeMesh 密码">
            <div class="row-list">
              <BgpSecretRefSelect :cluster-id="clusterId" v-model="meshPwd" />
            </div>
            <FieldHelp tip="节点间 mesh 的 BGP 认证密码引用（只存 Secret 引用，不存密文）。留空 = 不启用。" />
          </el-form-item>

          <el-form-item label="忽略网卡">
            <div class="row-list">
              <div v-for="(r, i) in form.ignoredInterfaces" :key="i" class="row-line">
                <el-input v-model="r.cidr" placeholder="如 kube-ipvs0、cali+ 之外的自定义网卡名" style="flex: 1" />
                <el-button link type="danger" @click="removeRow(form.ignoredInterfaces, i)">删除</el-button>
              </div>
              <el-button size="small" @click="addRow(form.ignoredInterfaces, { cidr: '' })">添加接口名</el-button>
            </div>
            <FieldHelp tip="BGP 在这些网卡上不建立 peering（glob 模式）。留空 = Calico 默认忽略集。" />
          </el-form-item>

          <el-divider content-position="left">新版字段（旧版 Calico 上请留空）</el-divider>

          <el-form-item label="LB IP 聚合">
            <el-input v-model="form.serviceLoadBalancerAggregation" placeholder="如 /16（按掩码聚合 LB IP 宣告）" style="max-width: 320px" />
            <FieldHelp tip="新版 Calico：把 LoadBalancer IP 按掩码聚合后宣告，减少路由条数。旧版集群留空。" />
          </el-form-item>

          <el-form-item label="集群路由编程">
            <el-select v-model="form.programClusterRoutes" clearable placeholder="默认" style="width: 200px">
              <el-option label="true（启用）" value="true" />
              <el-option label="false（禁用）" value="false" />
            </el-select>
            <FieldHelp tip="新版 Calico：是否由 BGP 编程集群内部路由。旧版集群留空。" />
          </el-form-item>

          <el-form-item label="Workload Peering v4">
            <el-input v-model="form.localWorkloadPeeringIPV4" placeholder="如 10.200.0.0/16" style="max-width: 320px" />
            <FieldHelp tip="新版 Calico：本地 workload peering 的 IPv4 段。旧版集群留空。" />
          </el-form-item>

          <el-form-item label="Workload Peering v6">
            <el-input v-model="form.localWorkloadPeeringIPV6" placeholder="如 fd12::/64" style="max-width: 320px" />
            <FieldHelp tip="新版 Calico：本地 workload peering 的 IPv6 段。旧版集群留空。" />
          </el-form-item>

          <el-form-item label="普通路由优先级 v4/v6">
            <div class="row-line" style="max-width: 420px">
              <el-input-number v-model="form.ipv4NormalRoutePriority" :min="0" controls-position="right" placeholder="v4" style="flex: 1" />
              <el-input-number v-model="form.ipv6NormalRoutePriority" :min="0" controls-position="right" placeholder="v6" style="flex: 1" />
            </div>
            <FieldHelp tip="新版 Calico：normal 路由的 BGP LOCAL_PREF。旧版集群留空。" />
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
