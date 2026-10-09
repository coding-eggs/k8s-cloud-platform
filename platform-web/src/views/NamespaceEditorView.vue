<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { clusterApi, namespaceApi, calicoApi, meshApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'
import type { K8sClusterOption, K8sIpool } from '@/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import QuotaSection from '@/components/namespace/QuotaSection.vue'
import LimitRangeSection from '@/components/namespace/LimitRangeSection.vue'
import { useClusterCapability } from '@/composables/useClusterCapability'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { DATAPLANE_MODES, RESERVED_MESH_LABELS, USE_WAYPOINT_NONE } from '@/utils/waypoint'

const route = useRoute()
const router = useRouter()

// ---- 平台上下文：集群级，无租户/命名空间级联（刻意不用 stores/context.ts 的租户优先单例，本页无租户）----
/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>((route.query.name as string) || null)
const clusters = ref<K8sClusterOption[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
/** 平台级页面：ready = 集群已选 */
const ready = computed(() => !!clusterId.value)

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.options()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

// ---- 表单模型 ----
const form = reactive({
  name: '',
  description: '',
  labels: {} as Record<string, string>,
  ipv4Pools: [] as string[],
  ipv6Pools: [] as string[],
  /** '' = 不设该标签（跟随集群默认）；ambient / none */
  dataplaneMode: '',
  /** '' = 不设该标签；none = 显式不使用（压过命名空间继承）；否则 = waypoint Gateway 名 */
  useWaypoint: '',
})

function resetForm(): void {
  form.name = ''
  form.description = ''
  form.labels = {}
  form.ipv4Pools = []
  form.ipv6Pools = []
  form.dataplaneMode = ''
  form.useWaypoint = ''
  // 约束区块随表单一起回到「未启用 + 空值」：load() 在 namespace 为空时即重置
  void reloadConstraints()
}

// ---- Calico 绑定池（B3 §11；ns annotation cni.projectcalico.org/ipv{4,6}pools）----
const { hasCalico } = useClusterCapability(clusterId)
const perm = usePermission()
const ippools = ref<K8sIpool[]>([])
watch([clusterId, hasCalico], async () => {
  // 地址池候选走 /calico/**（platform:cluster:manage）。显式判权限位：
  // 此前只靠 hasCalico 间接挡住，而那依赖"PLATFORM 角色不可能持 tenant:cluster:capability:view"
  // 这条不变量 —— 一旦能力快照的码调整，本页就会对"只做命名空间运维"的角色静默漏出 403。
  if (!clusterId.value || !hasCalico.value || !perm.has(apiCodes.clusterManage)) { ippools.value = []; return }
  try {
    ippools.value = (await calicoApi.ippool.list({ clusterId: clusterId.value })) ?? []
  } catch {
    ippools.value = [] // 拦截器已提示；下拉降级为空（仍可保存其余字段）
  }
}, { immediate: true })
/** 按 CIDR 族过滤候选（含 ':' → v6） */
const v4PoolOptions = computed(() => ippools.value.filter((p) => p.cidr && !p.cidr.includes(':')))
const v6PoolOptions = computed(() => ippools.value.filter((p) => !!p.cidr?.includes(':')))

// IP 栈门禁（k8s_cluster.ip_stack）：IPv6 池仅双栈集群可选；栈未知 → 不限制
const ipStack = computed(() => clusters.value.find((c) => c.clusterId === clusterId.value)?.ipStack ?? null)
const v4PoolAllowed = computed(() => !ipStack.value || ipStack.value === 'IPV4' || ipStack.value === 'IPV4_AND_IPV6')
const v6PoolAllowed = computed(() => !ipStack.value || ipStack.value === 'IPV6' || ipStack.value === 'IPV4_AND_IPV6')

// ---- 服务网格 Ambient（B3 §11；ns label istio.io/dataplane-mode + istio.io/use-waypoint）----
// 本页正是平台页，/mesh/status 可用（platform:cluster:manage）→ 能拿到 ambient 的**活探测**结论
// （ztunnel DaemonSet 是否存在）。租户侧拿不到这个信息，见 useMeshStatus 的降级说明。
const mesh = useMeshStatus(clusterId)
/** 是否已知 ambient 的真相：未探测（集群断开 / 无 platform:cluster:manage）→ 字段禁用且不提交，防误清既有标签 */
const ambientKnown = computed(() => mesh.ambientKnown.value)
/** 集群确实装了 ambient（ztunnel 在跑） */
const ambientAvailable = computed(() => mesh.istioAmbient.value)

/** 该命名空间的 waypoint 候选（平台侧 /mesh/gateways）。Gateway 是租户域资源而本页无租户上下文，故不能走 /gateways */
const waypointOptions = ref<string[]>([])
const waypointLoaded = ref(false)
watch([clusterId, () => form.name, () => ambientKnown.value], async () => {
  waypointOptions.value = []
  waypointLoaded.value = false
  const ns = form.name?.trim()
  if (!editing.value || !clusterId.value || !ns || !ambientKnown.value || !perm.has(apiCodes.clusterManage)) return
  try {
    waypointOptions.value = (await meshApi.namespaceWaypoints(clusterId.value, ns)) ?? []
  } catch {
    waypointOptions.value = [] // 拦截器已提示；下拉降级为空（用户仍可手输或稍后重试）
  } finally {
    waypointLoaded.value = true
  }
}, { immediate: true })

/**
 * 选中的 waypoint 已不在候选里（被删了 / 名字写错）→ 标红提示。
 * 不阻断提交：Istio 对不存在的 waypoint 是**静默放行**（流量照走、L7 策略不生效），
 * 平台这边报错反而挡住用户改别的字段。提示由用户决定改或清。
 */
const waypointMissing = computed(() =>
  waypointLoaded.value
  && !!form.useWaypoint && form.useWaypoint !== USE_WAYPOINT_NONE
  && !waypointOptions.value.includes(form.useWaypoint))

// ---- 编辑回填 ----
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const saving = ref(false)
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

// ---- 约束区块（配额 / 限制范围）----
const quotaRef = ref<InstanceType<typeof QuotaSection> | null>(null)
const lrRef = ref<InstanceType<typeof LimitRangeSection> | null>(null)

/**
 * 让两区块按当前 clusterId + 命名空间名重新回填。
 * 必须先 nextTick：区块挂在 v-if="formVisible" 内（编辑态渲染前 ref 为 null），
 * 且 :namespace 要等父级重渲染才落到子组件 props，否则 load() 会读到上一个命名空间名。
 */
async function reloadConstraints(): Promise<void> {
  await nextTick()
  await quotaRef.value?.load()
  await lrRef.value?.load()
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const ns = await namespaceApi.get(clusterId.value, editing.value)
    if (!ns) { detailState.value = 'error'; return }
    form.name = ns.name
    form.description = ns.description ?? ''
    form.labels = { ...(ns.labels ?? {}) }
    form.ipv4Pools = [...(ns.ipv4Pools ?? [])]
    form.ipv6Pools = [...(ns.ipv6Pools ?? [])]
    form.dataplaneMode = ns.dataplaneMode ?? ''
    form.useWaypoint = ns.useWaypoint ?? ''
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
    return
  }
  await reloadConstraints()
}

onMounted(() => {
  void loadClusters().then(() => {
    if (editing.value) void loadDetail()
  })
})
// 切集群：编辑态按新集群重新回填（名字仍来自 query，不变）；创建态清空表单
watch(clusterId, () => {
  if (editing.value) void loadDetail()
  else resetForm()
})

/**
 * 约束链（配额 / 限制范围）落库 —— 幂等 upsert / 删除。
 * 返回 false 表示命名空间已存但约束部分失败，提交链据此走「可重试」分支（spec §8 非原子）。
 * ⚠️ 本函数在 submit 的 try/catch 之外被 await，故内部必须吞掉异常并返回 false —— 任何外抛都会
 *      让 saving 永远停在 true 并产生未处理拒绝。具体错误文案由 http 拦截器 toast。
 * 关闭开关 → 删除对象（区分「未配置」与「主动清空」，D6）：创建态两区块都关且无值 → 完全不发约束调用。
 *
 * ⚠️ 盲写防护：区块 load() 失败 ⇒ 集群现状未知，此时该区块的任何写都是盲写，两种破坏路径都存在：
 *   A) 开关关着走 delete 分支（editing 为真即触发）→ 误删仍存在的对象；
 *   B) 用户看到区块是关的、手动开回开关但不填任何值（他从没见过原值）→ upsert 全 null 载荷 →
 *      后端 overlay 删光所有建模键（配额 9 项 / 限制范围全部类型），同样毁掉现状。
 *   故 loadFailed 的区块整段跳过（既不 upsert 也不 delete）：现状存活，命名空间自身字段照常落库；
 *   区块内有行内告警说明被跳过。创建态不受影响（命名空间尚不存在 → get 返回 null 而非报错）。
 */
async function saveConstraints(cid: string, name: string): Promise<boolean> {
  try {
    const quota = quotaRef.value
    if (quota && !quota.loadFailed) {
      if (quota.enabled) await namespaceApi.quotaUpsert(cid, name, quota.toPayload())
      else if (quota.hasAnyValue() || editing.value) await namespaceApi.quotaDelete(cid, name)
    }
    const lr = lrRef.value
    if (lr && !lr.loadFailed) {
      if (lr.enabled) await namespaceApi.limitrangeUpsert(cid, name, lr.toPayload())
      else if (lr.hasAnyValue() || editing.value) await namespaceApi.limitrangeDelete(cid, name)
    }
    return true
  } catch {
    return false
  }
}

// ---- 提交：本地校验 → 命名空间 create/update → 约束链（非原子，失败可重试）----
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入命名空间名'); return }
  if (!RFC1123_RE.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }
  if (name.length > 63) { ElMessage.warning('名称长度不得超过 63 字符'); return }
  // 约束区块校验：限制范围数值序（max≥min、default≥defaultRequest、ratio≥1）违规即阻断，
  // 区块内已标红 + 行内提示；配额无跨字段约束，故不校验。
  if (lrRef.value && !lrRef.value.isValid()) {
    ElMessage.warning('限制范围数值序有误（max≥min、default≥defaultRequest、ratio≥1），请修正标红项')
    return
  }

  saving.value = true
  // 绑定池仅在 capability 已探测到 Calico 时显式提交（空数组=主动清空）；未探测→不传字段，后端保持现状防误清。
  // IP 栈不允许的族同样不传（选择器已隐藏，避免把不可见族的残留值写进去/清掉既有绑定）
  // istio 两个标签同理：只有 ambient 状态**已知**时才提交，否则（未探测 / 集群断开）字段禁用且不传。
  // 空串 = 主动移除该标签（「跟随集群默认」/「不使用 waypoint」），与后端的 null=不动、''=移除 三态对齐。
  const payload = {
    clusterId: clusterId.value, name, description: form.description.trim(), labels: form.labels,
    ...(hasCalico.value ? {
      ...(v4PoolAllowed.value ? { ipv4Pools: form.ipv4Pools } : {}),
      ...(v6PoolAllowed.value ? { ipv6Pools: form.ipv6Pools } : {}),
    } : {}),
    ...(ambientKnown.value ? { dataplaneMode: form.dataplaneMode, useWaypoint: form.useWaypoint } : {}),
  }
  try {
    if (editing.value) {
      await namespaceApi.update(payload)
    } else {
      await namespaceApi.create(payload)
    }
  } catch {
    saving.value = false
    return   // 命名空间本身失败：拦截器已提示，留在页面
  }
  // 命名空间成功 → 约束链（T11 提供）；部分失败不静默丢弃（spec §8）
  const constraintOk = await saveConstraints(clusterId.value, name)
  saving.value = false
  if (!constraintOk) {
    ElMessage.warning('命名空间已保存，但配额/限制范围设置失败，可重试编辑')
    if (!editing.value) {
      // 关键：切到编辑态（名字锁定），使重试能 upsert 三区块
      editing.value = name
      // 手动置 detailState='loaded' 使 formVisible 保持 true（否则 v-if 卸载表单、丢失用户输入）
      // 不调 loadDetail() —— 它会 reloadConstraints() 从服务端重读并覆盖用户已填的约束值
      detailState.value = 'loaded'
      await router.replace({ name: 'namespace-editor', query: { clusterId: clusterId.value, name } })
    }
    return
  }
  ElMessage.success(editing.value ? '已更新' : '创建成功')
  await router.push({ name: 'namespace-detail', query: { clusterId: clusterId.value, name } })
}

function goBack(): void {
  router.push('/namespaces')
}

const pageTitle = computed(() => (editing.value ? '编辑命名空间' : '创建命名空间'))
</script>

<template>
  <div class="res-editor">
    <PageHeader :title="pageTitle">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 220px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择集群" description="请先在上方选择一个已启用的集群，再创建或编辑命名空间。" />

    <EmptyState v-else-if="detailState === 'error'" title="加载失败" description="该命名空间可能已被删除，或不在所选集群下。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <template v-else>
      <div v-if="formVisible" class="editor-body">
        <!-- 基础信息 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">{{ editing ? `命名空间 · ${form.name}` : '基础信息' }}</span></template>
          <el-form label-width="200px" label-position="left">
            <el-form-item required>
              <template #label>名称 <FieldHelp tip="K8s 命名空间名，需符合 RFC1123（小写字母/数字/-，字母或数字开头结尾），最长 63 字符；创建后不可修改。" /></template>
              <el-input v-model="form.name" :disabled="!!editing" placeholder="例如 order-prod" style="width: 360px" />
            </el-form-item>
            <el-form-item>
              <template #label>描述 <FieldHelp tip='说明该命名空间的用途。存于 metadata.annotations["description"]（平台约定，同工作负载描述），仅用于展示、不影响 K8s 行为。' /></template>
              <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="这个命名空间放什么服务、归哪个团队" style="width: 520px" />
            </el-form-item>
            <el-form-item>
              <template #label>标签 <FieldHelp tip="K8s 标签（metadata.labels），供选择器与第三方工具使用。平台保留标签 app.kubernetes.io/managed-by 由系统维护、不可编辑；编辑时其余既有标签自动保留。istio.io/dataplane-mode 与 istio.io/use-waypoint 由下方「服务网格」区块独占管理，此处不显示（也不改动）。" /></template>
              <LabelEditor v-model="form.labels" :exclude-keys="RESERVED_MESH_LABELS" class="sub-editor" style="max-width: 520px" />
            </el-form-item>
            <el-form-item v-if="hasCalico">
              <template #label>绑定地址池（Calico）<FieldHelp tip="圈定该命名空间 Pod 自动分配 IP 使用的地址池（ns annotation cni.projectcalico.org/ipv{4,6}pools）。留空 = Calico 默认分配；引用的池须已存在。IPv6 池仅双栈集群（IPV4_AND_IPV6）可选，单栈集群隐藏对应族。工作负载「固定 IP」只能选本命名空间绑定池（未绑定则默认池）中的保留 IP。" /></template>
              <div class="pool-selects">
                <el-select v-if="v4PoolAllowed" v-model="form.ipv4Pools" multiple filterable clearable placeholder="IPv4 池（留空 = 默认分配）" style="width: 100%">
                  <el-option v-for="p in v4PoolOptions" :key="p.name" :label="`${p.name}（${p.cidr}）`" :value="p.name" />
                </el-select>
                <el-select v-if="v6PoolAllowed" v-model="form.ipv6Pools" multiple filterable clearable placeholder="IPv6 池（留空 = 默认分配）" style="width: 100%">
                  <el-option v-for="p in v6PoolOptions" :key="p.name" :label="`${p.name}（${p.cidr}）`" :value="p.name" />
                </el-select>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 服务网格（B3 §11.4「能力开关」区块）：本页正是平台页，ambient 的活探测只有这里有 -->
        <el-card v-if="ambientKnown || editing" shadow="never" class="sec-card">
          <template #header><span class="sec-title">服务网格（Istio Ambient）</span></template>
          <el-form label-width="200px" label-position="left">
            <template v-if="ambientKnown">
              <el-form-item>
                <template #label>数据面模式 <FieldHelp tip="命名空间标签 istio.io/dataplane-mode。纳入 ambient 后，本命名空间内的工作负载由节点级 ztunnel 接管四层流量；排除（none）则显式退出网格。不设标签 = 跟随集群默认（多数安装的默认是「未纳入」）。Pod 上的同名标签优先级高于命名空间。" /></template>
                <el-select v-model="form.dataplaneMode" :disabled="!ambientAvailable" style="width: 320px" placeholder="跟随集群默认（不设标签）">
                  <el-option label="跟随集群默认（不设标签）" value="" />
                  <el-option v-for="m in DATAPLANE_MODES" :key="m.value" :label="m.label" :value="m.value" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <template #label>使用 waypoint <FieldHelp tip="命名空间标签 istio.io/use-waypoint。取值 = 本命名空间 waypoint Gateway 的名字，本命名空间内所有工作负载的七层流量都会经过它（L7 路由/鉴权/遥测）；选 none 表示显式不使用 —— 用来压过从上层继承的 waypoint。不设标签 = 跟随默认（无人使用）。⚠️ 这个标签只表达意图，不保证流量真的经过：waypoint 不存在时 ztunnel 会直接放行，L7 策略静默失效。" /></template>
                <el-select v-model="form.useWaypoint" :disabled="!ambientAvailable" style="width: 320px" placeholder="不使用 waypoint（不设标签）">
                  <el-option label="不使用 waypoint（不设标签）" value="" />
                  <el-option label="显式不使用（none）" :value="USE_WAYPOINT_NONE" />
                  <el-option v-for="w in waypointOptions" :key="w" :label="w" :value="w" />
                </el-select>
                <div v-if="!ambientAvailable" class="form-tip warn">该集群未探测到 ambient（istio-system/ztunnel DaemonSet 不存在）—— 这两个标签暂不生效，字段已禁用以免误配。</div>
                <div v-else-if="!editing" class="form-tip">waypoint Gateway 需建在一个已存在的命名空间里，故创建阶段没有候选；先创建命名空间，再回来选。</div>
                <div v-else-if="waypointLoaded && !waypointOptions.length" class="form-tip">该命名空间还没有 waypoint Gateway——要在「Gateway」页用「创建 Waypoint」建一个（每个命名空间至多一个）。</div>
                <div v-if="waypointMissing" class="form-tip warn">「{{ form.useWaypoint }}」不在本命名空间的 waypoint 候选里（可能已被删除）。istio 对不存在的 waypoint 会静默放行，L7 策略不会生效——请改选或清空。</div>
              </el-form-item>
              <div v-if="!ambientAvailable || (editing && waypointLoaded && !waypointOptions.length)" class="mesh-precond">
                <el-alert
                  type="info" :closable="false" show-icon
                  title="前置条件清单"
                  description="① 目标命名空间需带 istio.io/dataplane-mode: ambient（上面第一项）；② 消费方（命名空间 / 服务 / Pod）需带 istio.io/use-waypoint=<waypoint 名> 才会走它；③ waypoint Gateway 本身要在「Gateway」页建好。三者缺一，流量都照常直连，不会有报错。"
                />
              </div>
            </template>
            <el-alert
              v-else type="warning" :closable="false" show-icon
              title="未探测到网格状态，本次不改动 istio 标签"
              description="要么集群连不上，要么当前身份没有 platform:cluster:manage（网格状态是平台侧端点）。为避免把已有的 ambient 配置误清掉，本区块不提交这两个字段；命名空间的其它字段照常保存。"
            />
          </el-form>
        </el-card>

        <!-- 约束区块：命名空间名 = form.name（创建态即待建名；区块内部对空名不发起请求） -->
        <QuotaSection ref="quotaRef" :cluster-id="clusterId" :namespace="form.name" />
        <LimitRangeSection ref="lrRef" :cluster-id="clusterId" :namespace="form.name" />

        <div class="form-actions">
          <el-button @click="goBack">{{ editing ? '取消' : '返回' }}</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
      </div>

      <div v-else class="loading-tip">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
/* 编辑器骨架样式自 HpaEditorView 复制（仓库无共享编辑器样式表，各编辑器各自复制为既有惯例） */
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
.sub-editor {
  width: 100%;
}
.pool-selects {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 520px;
}
.mesh-precond {
  margin: 0 0 8px 200px;
  max-width: 620px;
}
/* 与 WorkloadEditorView 同名同款（仓库无共享编辑器样式表，各页复制为既有惯例） */
.form-tip {
  width: 100%;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.form-tip.warn {
  color: var(--el-color-warning);
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
