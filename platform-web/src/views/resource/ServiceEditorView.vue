<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { serviceApi, workloadApi, podApi } from '@/api'
import type { K8sService, K8sServicePort } from '@/types'
import type { WorkloadDetail } from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import FieldHelp from "@/components/workload/FieldHelp.vue";

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>(route.query.name as string | null)

const SERVICE_TYPES = ['ClusterIP', 'NodePort', 'LoadBalancer', 'ExternalName']
const PROTOCOLS = ['TCP', 'UDP', 'SCTP']
const IP_FAMILIES = ['IPv4', 'IPv6']
const IP_FAMILY_POLICIES = ['SingleStack', 'PreferDualStack', 'RequireDualStack']
const TRAFFIC_POLICIES = ['Cluster', 'Local']

/** 各 Service 类型的用途（选中后展示在类型下方） */
const TYPE_DESC: Record<string, string> = {
  ClusterIP: '集群内部虚拟 IP，仅集群内可访问。clusterIP=None 时为 headless（无虚拟 IP，直连端点）。',
  NodePort: '在 ClusterIP 基础上，每个节点分配一个端口对外暴露。',
  LoadBalancer: '在 NodePort 基础上创建外部负载均衡器（需云厂商支持）。',
  ExternalName: '将服务别名到外部名称（DNS CNAME），无代理、无端点。',
}

interface PortRow { name: string; port: number | null; targetPort: string; nodePort: number | null; protocol: string; appProtocol: string }
function newPortRow(): PortRow { return { name: '', port: null, targetPort: '', nodePort: null, protocol: 'TCP', appProtocol: '' } }

const form = reactive({
  name: '',
  type: 'ClusterIP',
  labels: {} as Record<string, string>,
  ports: [newPortRow()] as PortRow[],
  // 网络 / IP
  clusterIp: '' as string,            // ''=自动分配 / 'None'=headless / 具体 IP（主）
  secondaryClusterIp: '' as string,   // 双栈第二个 IP
  familyA: 'IPv4' as string,          // 双栈 family 0
  familyB: 'IPv6' as string,          // 双栈 family 1
  ipFamilyPolicy: 'SingleStack' as string,
  externalIps: [] as string[],
  // 流量策略
  internalTrafficPolicy: 'Cluster' as string,
  externalTrafficPolicy: 'Cluster' as string,
  sessionAffinity: 'None' as string,
  sessionAffinityTimeoutSeconds: null as number | null,
  publishNotReadyAddresses: false,
  // LoadBalancer
  allocateLoadBalancerNodePorts: true,
  healthCheckNodePort: null as number | null,
  loadBalancerClass: '' as string,
  loadBalancerSourceRanges: [] as string[],
  // ExternalName
  externalName: '' as string,
})

const showNodePort = computed(() => form.type === 'NodePort')
const isExternalName = computed(() => form.type === 'ExternalName')
const isLoadBalancer = computed(() => form.type === 'LoadBalancer')
/** 当前集群是否双栈（决定能否展示双栈表单） */
const isDualStackCluster = computed(() => currentCluster.value?.ipStack === 'IPV4_AND_IPV6')
/** 双栈模式：集群支持双栈 且 策略非 SingleStack（展开成对 family + IP 输入） */
const dualMode = computed(() => isDualStackCluster.value && form.ipFamilyPolicy !== 'SingleStack' && !isExternalName.value)
/** healthCheckNodePort 仅在 LB 且 externalTrafficPolicy=Local 时适用 */
const showHealthCheckNodePort = computed(() => isLoadBalancer.value && form.externalTrafficPolicy === 'Local')

// ---------- selector 绑定（前端只传来源，后端解析成 label map） ----------
type BindMode = 'workload' | 'pod'
const bindMode = ref<BindMode>('workload')            // 默认绑定工作负载
const boundWorkload = ref('')                          // 选中的工作负载名
const boundPod = ref('')                               // 选中的 Pod 名
/** 编辑回显：当前 selector（只读 chips）；重新选择工作负载/Pod 时由后端覆盖 */
const currentSelector = ref<Record<string, string>>({})

const workloadOptions = ref<WorkloadDetail[]>([])
const podOptions = ref<string[]>([])
const optsLoading = ref(false)

async function loadBindingOptions(): Promise<void> {
  if (!ready.value || isExternalName.value) return
  optsLoading.value = true
  try {
    const ctx = { tenantId: state.tenantId!, clusterId: state.clusterId!, namespace: state.namespace! }
    const [ws, ps] = await Promise.all([workloadApi.list(ctx), podApi.list(ctx)])
    workloadOptions.value = ws
    podOptions.value = ps.map((p) => p.name)
  } catch {
    /* 拦截器已提示 */
  } finally {
    optsLoading.value = false
  }
}

// ---------- 编辑回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

/** 不可变字段持久化值：healthCheckNodePort / loadBalancerClass 一旦设置不可改 → 编辑且已有值则锁 */
const originalHealthCheckNodePort = ref<number | null>(null)
const originalLoadBalancerClass = ref('')
const healthCheckLocked = computed(() => !!editing.value && originalHealthCheckNodePort.value != null)
const lbClassLocked = computed(() => !!editing.value && !!originalLoadBalancerClass.value)
/** clusterIP：编辑态只读（K8s 禁改，除非切 ExternalName） */
const ipLocked = computed(() => !!editing.value)

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await serviceApi.get(editing.value, {
      tenantId: state.tenantId!,
      clusterId: state.clusterId!,
      namespace: state.namespace!,
    })
    form.name = d.name
    form.type = d.type || 'ClusterIP'
    form.labels = { ...(d.labels ?? {}) }
    const ports = (d.ports ?? []).map((p) => ({
      name: p.name ?? '',
      port: p.port ?? null,
      targetPort: p.targetPort ?? '',
      nodePort: p.nodePort ?? null,
      protocol: p.protocol ?? 'TCP',
      appProtocol: p.appProtocol ?? '',
    }))
    form.ports = ports.length ? ports : [newPortRow()]
    // 网络 / IP
    form.clusterIp = d.clusterIp ?? ''
    const ips = d.clusterIps ?? []
    form.secondaryClusterIp = ips[1] ?? ''
    const fams = d.ipFamilies ?? []
    form.familyA = fams[0] || 'IPv4'
    form.familyB = fams[1] || 'IPv6'
    form.ipFamilyPolicy = d.ipFamilyPolicy || 'SingleStack'
    form.externalIps = [...(d.externalIps ?? [])]
    // 流量策略
    form.internalTrafficPolicy = d.internalTrafficPolicy || 'Cluster'
    form.externalTrafficPolicy = d.externalTrafficPolicy || 'Cluster'
    form.sessionAffinity = d.sessionAffinity || 'None'
    form.sessionAffinityTimeoutSeconds = d.sessionAffinityTimeoutSeconds ?? null
    form.publishNotReadyAddresses = d.publishNotReadyAddresses === true
    // selector 回显（只读）；默认绑定工作负载，未重新选择则原样回填
    currentSelector.value = { ...(d.selector ?? {}) }
    // LoadBalancer
    form.allocateLoadBalancerNodePorts = d.allocateLoadBalancerNodePorts !== false
    form.healthCheckNodePort = d.healthCheckNodePort ?? null
    originalHealthCheckNodePort.value = d.healthCheckNodePort ?? null
    form.loadBalancerClass = d.loadBalancerClass ?? ''
    originalLoadBalancerClass.value = d.loadBalancerClass ?? ''
    form.loadBalancerSourceRanges = [...(d.loadBalancerSourceRanges ?? [])]
    // ExternalName
    form.externalName = d.externalName ?? ''
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

onMounted(() => {
  void load()
  if (ready.value) {
    void loadBindingOptions()
    if (editing.value) void loadDetail()
  }
})
// 上下文晚于挂载才选齐（未持久化上次选择）时补拉详情 + 绑定选项
watch(ready, (r) => {
  if (!r) return
  void loadBindingOptions()
  if (editing.value && detailState.value === 'idle') void loadDetail()
})

const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

// ---------- 提交：本地校验 → 组装 body（update 为整对象替换，各字段须随体提交） ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
/** externalName：小写 RFC-1123 主机名（可含多级域名） */
const RFC1123_HOST_RE = /^(?=.{1,253}$)[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/
const saving = ref(false)

function cleanList(arr: string[]): string[] {
  return arr.map((s) => s.trim()).filter(Boolean)
}

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }

  const body: K8sService = { name, namespace: state.namespace!, type: form.type, labels: form.labels }

  if (isExternalName.value) {
    // ExternalName：仅外部别名，无端口 / 网络字段
    const en = form.externalName.trim()
    if (!en) { ElMessage.warning('ExternalName 类型需填写 externalName'); return }
    if (!RFC1123_HOST_RE.test(en)) { ElMessage.warning('externalName 需为小写 RFC-1123 主机名（如 external.example.com）'); return }
    body.externalName = en
  } else {
    // 端口（非 ExternalName 必填）
    if (form.ports.length === 0) { ElMessage.warning('至少需要一个端口'); return }
    const ports: K8sServicePort[] = []
    for (const p of form.ports) {
      if (!p.port || p.port < 1 || p.port > 65535) { ElMessage.warning('存在非法的 Service 端口（1-65535）'); return }
      if (!p.targetPort.trim()) { ElMessage.warning('每个端口需填写 targetPort（数字或命名端口）'); return }
      ports.push({
        name: p.name.trim() || null,
        port: p.port,
        targetPort: p.targetPort.trim(),
        nodePort: showNodePort.value ? p.nodePort : null,
        protocol: p.protocol,
        appProtocol: p.appProtocol.trim() || null, // 透传保留（UI 不编辑）
      })
    }
    body.ports = ports

    // 网络 / IP
    const primary = form.clusterIp.trim()
    if (dualMode.value) {
      const secondary = form.secondaryClusterIp.trim()
      if ((primary === 'None') !== (secondary === 'None')) { ElMessage.warning('headless（None）需两个 clusterIP 都为 None'); return }
      body.ipFamilyPolicy = form.ipFamilyPolicy
      body.ipFamilies = [form.familyA, form.familyB]
      body.clusterIps = [primary, secondary]
    } else {
      body.clusterIp = primary || null
    }
    const extIps = cleanList(form.externalIps)
    if (extIps.length) body.externalIps = extIps

    // 流量策略
    body.internalTrafficPolicy = form.internalTrafficPolicy
    if (showNodePort.value || isLoadBalancer.value) body.externalTrafficPolicy = form.externalTrafficPolicy
    body.sessionAffinity = form.sessionAffinity
    if (form.sessionAffinity === 'ClientIP') {
      const t = form.sessionAffinityTimeoutSeconds
      if (t == null || t < 1 || t > 86400) { ElMessage.warning('ClientIP 会话保持秒数需为 1–86400'); return }
      body.sessionAffinityTimeoutSeconds = t
    }
    body.publishNotReadyAddresses = form.publishNotReadyAddresses

    // selector 绑定：选了工作负载/Pod → 传 selectorRef（后端解析覆盖）；否则回填原 selector
    if (bindMode.value === 'workload' && boundWorkload.value) {
      body.selectorRef = { type: 'workload', name: boundWorkload.value }
    } else if (bindMode.value === 'pod' && boundPod.value) {
      body.selectorRef = { type: 'pod', name: boundPod.value }
    } else if (!editing.value) {
      ElMessage.warning('请选择要绑定的工作负载或 Pod')
      return
    } else if (Object.keys(currentSelector.value).length) {
      body.selector = { ...currentSelector.value } // 编辑未重选 → 原样回填
    }

    // LoadBalancer 专属
    if (isLoadBalancer.value) {
      body.allocateLoadBalancerNodePorts = form.allocateLoadBalancerNodePorts
      if (showHealthCheckNodePort.value && form.healthCheckNodePort != null) body.healthCheckNodePort = form.healthCheckNodePort
      const lbClass = form.loadBalancerClass.trim()
      if (lbClass) body.loadBalancerClass = lbClass
      const ranges = cleanList(form.loadBalancerSourceRanges)
      if (ranges.length) body.loadBalancerSourceRanges = ranges
    }
  }

  saving.value = true
  try {
    const ctx2 = { tenantId: state.tenantId!, clusterId: state.clusterId! }
    if (editing.value) {
      await serviceApi.update(editing.value, ctx2, body)
      ElMessage.success('保存成功')
    } else {
      await serviceApi.create(ctx2, body)
      ElMessage.success('创建成功')
    }
    router.push('/resources/services')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/resources/services') }

const pageTitle = computed(() => (editing.value ? '编辑 Service' : '创建 Service'))
const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div class="res-editor">
    <PageHeader :title="pageTitle" :description="contextDesc">
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑 Service。" />

    <template v-else>
      <div v-if="formVisible" class="editor-body">
        <!-- 基础信息 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">{{ editing ? `Service · ${form.name}` : '基础信息' }}</span></template>
          <el-form label-width="250px" label-position="left">
            <el-form-item label="名称" required>
              <template #label>名称 <FieldHelp tip="K8s 资源名创建后不可修改；命名空间 = 当前上下文" /></template>

              <el-input v-model="form.name" :disabled="!!editing" placeholder="小写字母/数字/-，例如 app-svc" style="width: 360px" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="form.type" style="width: 360px">
                <el-option v-for="t in SERVICE_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
              <div class="form-tip">{{ TYPE_DESC[form.type] }}</div>
            </el-form-item>
            <el-form-item label="标签">
              <LabelEditor v-model="form.labels" class="sub-editor" style="max-width: 520px" />
            </el-form-item>
            <el-form-item v-if="!isExternalName" label="端口">
              <div class="port-editor">
                <div v-for="(p, idx) in form.ports" :key="idx" class="port-row">
                  <el-input v-model="p.name" placeholder="名称（可空）" class="port-name" />
                  <el-input-number v-model="p.port" :min="1" :max="65535" controls-position="right" placeholder="Port" class="port-num" />
                  <el-input v-model="p.targetPort" placeholder="TargetPort（数字/命名）" class="port-target" />
                  <el-input-number
                    v-if="showNodePort"
                    v-model="p.nodePort"
                    :min="30000"
                    :max="32767"
                    controls-position="right"
                    placeholder="NodePort（可空）"
                    class="port-num"
                  />
                  <el-select v-model="p.protocol" class="port-proto">
                    <el-option v-for="pr in PROTOCOLS" :key="pr" :label="pr" :value="pr" />
                  </el-select>
                  <el-button link type="danger" :disabled="form.ports.length <= 1" @click="form.ports.splice(idx, 1)">删除</el-button>
                </div>
                <el-button class="add-row-btn" plain @click="form.ports.push(newPortRow())">+ 添加端口</el-button>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 端点选择 selector（非 ExternalName） -->
        <el-card v-if="!isExternalName" shadow="never" class="sec-card">
          <template #header><span class="sec-title">端点选择</span></template>
          <el-form label-width="250px" label-position="left">
            <el-form-item label="selector">
              <template #label>selector <FieldHelp tip="决定流量路由到哪些 Pod。选一个工作负载或 Pod，后端解析其标签作为 selector；不要手填标签以免写错。" /></template>
              <div class="bind-editor">
                <el-radio-group v-model="bindMode" size="small">
                  <el-radio-button value="workload">绑定工作负载</el-radio-button>
                  <el-radio-button value="pod">按 Pod 绑定</el-radio-button>
                </el-radio-group>
                <el-select
                  v-if="bindMode === 'workload'"
                  v-model="boundWorkload"
                  :loading="optsLoading"
                  filterable
                  clearable
                  placeholder="选择工作负载（Deployment / StatefulSet / DaemonSet）"
                  style="width: 360px; margin-top: 8px"
                >
                  <el-option v-for="w in workloadOptions" :key="w.kind + w.name" :label="`${w.name}（${w.kind}）`" :value="w.name" />
                </el-select>
                <el-select
                  v-else
                  v-model="boundPod"
                  :loading="optsLoading"
                  filterable
                  clearable
                  placeholder="选择 Pod"
                  style="width: 360px; margin-top: 8px"
                >
                  <el-option v-for="p in podOptions" :key="p" :label="p" :value="p" />
                </el-select>
              </div>
            </el-form-item>
            <el-form-item v-if="Object.keys(currentSelector).length" label="当前 selector">
              <div class="selector-chips">
                <el-tag v-for="(v, k) in currentSelector" :key="k" size="small" effect="plain" class="sel-chip">{{ k }}={{ v }}</el-tag>
                <span class="form-tip">只读；重新选择上方工作负载 / Pod 后由后端覆盖。</span>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 网络与 IP（非 ExternalName） -->
        <el-card v-if="!isExternalName" shadow="never" class="sec-card">
          <template #header><span class="sec-title">网络与 IP</span></template>
          <el-form label-width="250px" label-position="left">
            <!-- 单栈 / SingleStack：单个 clusterIP -->
            <el-form-item v-if="!dualMode" label="clusterIP">
              <el-input v-model="form.clusterIp" :disabled="ipLocked" placeholder="留空=自动分配；None=headless（无虚拟 IP）；或具体 IP" style="width: 460px" />
              <div class="form-tip">留空由集群自动分配；填 None 为 headless service（无虚拟 IP，直连端点）；也可指定一个空闲的合法 IP。编辑时不可修改。</div>
            </el-form-item>

            <!-- ipFamilyPolicy：仅双栈集群展示 -->
            <el-form-item v-if="isDualStackCluster" label="ipFamilyPolicy">
              <el-select v-model="form.ipFamilyPolicy" style="width: 360px">
                <el-option v-for="p in IP_FAMILY_POLICIES" :key="p" :label="p" :value="p" />
              </el-select>
              <div class="form-tip">SingleStack=单栈；PreferDualStack / RequireDualStack=双栈（本集群支持，展开成对输入）。</div>
            </el-form-item>

            <!-- 双栈：成对 family + clusterIP -->
            <template v-if="dualMode">
              <el-form-item label="ipFamilies">
                <el-select v-model="form.familyA" style="width: 150px">
                  <el-option v-for="f in IP_FAMILIES" :key="f" :label="f" :value="f" />
                </el-select>
                <span class="pair-sep">+</span>
                <el-select v-model="form.familyB" style="width: 150px">
                  <el-option v-for="f in IP_FAMILIES" :key="f" :label="f" :value="f" />
                </el-select>
              </el-form-item>
              <el-form-item label="clusterIPs">
                <el-input v-model="form.clusterIp" :disabled="ipLocked" placeholder="主 IP（留空=自动 / None=headless）" style="width: 260px" />
                <span class="pair-sep">,</span>
                <el-input v-model="form.secondaryClusterIp" :disabled="ipLocked" placeholder="次 IP（留空=自动 / None=headless）" style="width: 260px" />
                <div class="form-tip">两个 IP 须分别对应上面的两个 IP 族；headless 时两者都填 None。编辑时不可修改。</div>
              </el-form-item>
            </template>

            <!-- externalIPs -->
            <el-form-item label="externalIPs">
              <template #label>externalIPs <FieldHelp tip="节点额外接受的 IP（K8s 不管理、不校验），外部流量打这些 IP 也会路由到该 Service。" /></template>
              <div class="list-editor">
                <div v-for="(ip, idx) in form.externalIps" :key="idx" class="list-row">
                  <el-input
                    :model-value="ip ?? ''"
                    @update:model-value="(v: string) => (form.externalIps[idx] = v)"
                    placeholder="IP（K8s 不管理，节点额外接受该 IP 的流量）"
                    style="width: 360px"
                  />
                  <el-button link type="danger" @click="form.externalIps.splice(idx, 1)">删除</el-button>
                </div>
                <el-button class="add-row-btn" plain @click="form.externalIps.push('')">+ 添加 IP</el-button>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 流量策略（非 ExternalName） -->
        <el-card v-if="!isExternalName" shadow="never" class="sec-card">
          <template #header><span class="sec-title">流量策略</span></template>
          <el-form label-width="250px" label-position="left">
            <el-form-item label="internalTrafficPolicy">
              <el-select v-model="form.internalTrafficPolicy" style="width: 360px">
                <el-option v-for="t in TRAFFIC_POLICIES" :key="t" :label="t" :value="t" />
              </el-select>
              <div class="form-tip">集群内部（ClusterIP）流量：Cluster=路由到所有端点；Local=只路由到本节点端点（无本地端点则丢弃）。</div>
            </el-form-item>
            <el-form-item v-if="showNodePort || isLoadBalancer" label="externalTrafficPolicy">
              <el-select v-model="form.externalTrafficPolicy" style="width: 360px">
                <el-option v-for="t in TRAFFIC_POLICIES" :key="t" :label="t" :value="t" />
              </el-select>
              <div class="form-tip">外部（NodePort / ExternalIPs / LB）流量：Cluster=均衡到所有端点；Local=保留源 IP、只路由到本节点端点。</div>
            </el-form-item>
            <el-form-item label="sessionAffinity">
              <template #label>sessionAffinity <FieldHelp tip="会话保持：None=不保持；ClientIP=同一客户端 IP 固定路由到同一 Pod（默认超时 10800s）。" /></template>
              <el-select v-model="form.sessionAffinity" style="width: 360px">
                <el-option label="None" value="None" />
                <el-option label="ClientIP" value="ClientIP" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="form.sessionAffinity === 'ClientIP'" label="会话保持秒数">
              <template #label>会话保持秒数 <FieldHelp tip="sessionAffinityConfig.clientIP.timeoutSeconds：1–86400，默认 10800。" /></template>
              <el-input-number v-model="form.sessionAffinityTimeoutSeconds" :min="1" :max="86400" controls-position="right" placeholder="默认 10800" class="port-num" />
            </el-form-item>
            <el-form-item label="publishNotReadyAddresses">
              <template #label>publishNotReadyAddresses <FieldHelp tip="忽略 ready/not-ready，未就绪的 Pod 也纳入端点（StatefulSet headless 对等发现常用）。" /></template>
              <el-switch v-model="form.publishNotReadyAddresses" />
            </el-form-item>
          </el-form>
        </el-card>

        <!-- LoadBalancer（仅 LB） -->
        <el-card v-if="isLoadBalancer" shadow="never" class="sec-card">
          <template #header><span class="sec-title">LoadBalancer</span></template>
          <el-form label-width="150px" label-position="left">
            <el-form-item label="自动分配 NodePort">
              <el-switch v-model="form.allocateLoadBalancerNodePorts" />
              <div class="form-tip">allocateLoadBalancerNodePorts：是否自动为 LB 分配 NodePort；若手动指定了 NodePort 则忽略此开关。</div>
            </el-form-item>
            <el-form-item v-if="showHealthCheckNodePort" label="healthCheckNodePort">
              <el-input-number v-model="form.healthCheckNodePort" :min="30000" :max="32767" controls-position="right" placeholder="可空，自动分配" class="port-num" />
              <div class="form-tip">externalTrafficPolicy=Local 时供外部 LB 探测节点是否持有端点；一旦设置不可修改。</div>
            </el-form-item>
            <el-form-item label="loadBalancerClass">
              <el-input v-model="form.loadBalancerClass" :disabled="lbClassLocked" placeholder="如 internal-vip / example.com/internal-vip（可空=默认实现）" style="width: 460px" />
              <div class="form-tip">指定 LB 实现类别；一旦设置不可修改，切非 LoadBalancer 时会被清空。</div>
            </el-form-item>
            <el-form-item label="LB 源 IP 限制">
              <div class="list-editor">
                <div v-for="(r, idx) in form.loadBalancerSourceRanges" :key="idx" class="list-row">
                  <el-input
                    :model-value="r ?? ''"
                    @update:model-value="(v: string) => (form.loadBalancerSourceRanges[idx] = v)"
                    placeholder="源 IP / CIDR（如 10.0.0.0/8）"
                    style="width: 360px"
                  />
                  <el-button link type="danger" @click="form.loadBalancerSourceRanges.splice(idx, 1)">删除</el-button>
                </div>
                <el-button class="add-row-btn" plain @click="form.loadBalancerSourceRanges.push('')">+ 添加 CIDR</el-button>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- ExternalName（仅 ExternalName） -->
        <el-card v-if="isExternalName" shadow="never" class="sec-card">
          <template #header><span class="sec-title">ExternalName</span></template>
          <el-form label-width="150px" label-position="left">
            <el-form-item label="externalName" required>
              <el-input v-model="form.externalName" placeholder="如 external.example.com（小写 RFC-1123）" style="width: 360px" />
              <div class="form-tip">外部别名，DNS 返回 CNAME 指向该名称；无代理、无端点，其余网络字段不适用。</div>
            </el-form-item>
          </el-form>
        </el-card>

        <div class="form-actions">
          <el-button @click="goBack">取消 / 返回</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
      </div>

      <EmptyState v-else-if="detailState === 'error'" title="加载 Service 失败" description="请返回列表重试；若该资源已被删除，刷新列表即可。">
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </EmptyState>

      <div v-else class="loading-tip">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
.editor-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sec-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.sec-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.form-tip {
  width: 100%;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.sub-editor {
  width: 100%;
}
.port-editor {
  width: 100%;
}
.port-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.port-name {
  width: 24%;
  min-width: 300px;
  flex-shrink: 0;
}
.port-num {
  width: 170px;
  flex-shrink: 0;
}
.port-target {
  width: 16%;
  min-width: 100px;
  flex-shrink: 0;
}
.port-proto {
  width: 90px;
  flex-shrink: 0;
}
.list-editor {
  width: 100%;
}
.list-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.pair-sep {
  color: var(--text-3);
  padding: 0 4px;
}
.bind-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
}
.selector-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.sel-chip {
  font-family: Consolas, 'JetBrains Mono', monospace;
}
.add-row-btn {
  width: auto;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 4px 0 16px;
}
.loading-tip {
  padding: 48px;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}
</style>
