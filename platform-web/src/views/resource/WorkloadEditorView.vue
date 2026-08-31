<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { workloadApi } from '@/api'
import type {
  Affinity,
  ContainerDef,
  ContainerPort,
  NodeSelectorTerm,
  PodSpec,
  PvcTemplate,
  Strategy,
  Toleration,
  VolumeDef,
  WorkloadDetail,
  WorkloadKind,
} from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import StrategyEditor from '@/components/workload/StrategyEditor.vue'
import ContainerListEditor from '@/components/workload/ContainerListEditor.vue'
import PortEditor from '@/components/workload/PortEditor.vue'
import AffinityEditor from '@/components/workload/AffinityEditor.vue'
import TolerationEditor from '@/components/workload/TolerationEditor.vue'
import VolumeEditor from '@/components/workload/VolumeEditor.vue'
import PvcTemplateEditor from '@/components/workload/PvcTemplateEditor.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>(route.query.name as string | null)

// ---------- form state（骨架保证 podTemplate.spec + 非空 containers） ----------
function defaultForm(): WorkloadDetail {
  return {
    kind: 'deployment',
    name: '',
    namespace: '',
    labels: null,
    description: null,
    replicas: 1,
    serviceName: null,
    strategy: { type: 'RollingUpdate' },
    volumeClaimTemplates: null,
    podTemplate: { spec: { containers: [{ name: '', image: '', imagePullPolicy: 'IfNotPresent' }], restartPolicy: 'Always' } },
  }
}

const form = reactive<WorkloadDetail>(defaultForm())

/** 保证 podTemplate 存在后取 spec（类型上 spec/containers 必在，编辑回填同样保证） */
function ensureSpec(): PodSpec {
  if (!form.podTemplate) form.podTemplate = { spec: { containers: [] } }
  return form.podTemplate.spec
}

/** 两个容器 tab 列表的 ref（v-show 常驻 → B4 校验覆盖全部容器，与当前激活模块无关） */
const mainTabsRef = ref<InstanceType<typeof ContainerListEditor> | null>(null)
const initTabsRef = ref<InstanceType<typeof ContainerListEditor> | null>(null)

/** 右侧导航：当前显示的模块。用 v-show 切换（全部常驻 DOM）→ 子编辑器 ref / B4 校验不受切模块影响 */
const activeModule = ref<'basic' | 'containers' | 'init' | 'strategy' | 'scheduling' | 'storage'>('basic')

// ---------- 主容器（containers[0]）常用字段：与「Pod 容器」模块的 ContainerEditor 绑同一对象，自动同步 ----------
function ensurePrimary(): ContainerDef {
  if (!form.podTemplate) form.podTemplate = { spec: { containers: [] } }
  const spec = form.podTemplate.spec
  if (!spec.containers || spec.containers.length === 0) spec.containers = [{ name: '', image: '' }]
  return spec.containers[0]! // guard 已保证非空
}
const pName = computed<string>({ get: () => ensurePrimary().name, set: (v) => { ensurePrimary().name = v } })
const pImage = computed<string>({ get: () => ensurePrimary().image ?? '', set: (v) => { ensurePrimary().image = v } })
const pWorkingDir = computed<string>({ get: () => ensurePrimary().workingDir ?? '', set: (v) => { ensurePrimary().workingDir = v || null } })
const pPullPolicy = computed<string | undefined>({ get: () => ensurePrimary().imagePullPolicy ?? undefined, set: (v) => { ensurePrimary().imagePullPolicy = v } })
const pPorts = computed<ContainerPort[] | undefined>({ get: () => ensurePrimary().ports ?? undefined, set: (v) => { ensurePrimary().ports = v } })
/** command/args 多值：textarea 按行/逗号切分（与 ContainerEditor 同一做法） */
const pCommandText = computed<string>({ get: () => ensurePrimary().command?.join('\n') ?? '', set: (t) => { ensurePrimary().command = t.split(/[\n,]/).map((s) => s.trim()).filter(Boolean) } })
const pArgsText = computed<string>({ get: () => ensurePrimary().args?.join('\n') ?? '', set: (t) => { ensurePrimary().args = t.split(/[\n,]/).map((s) => s.trim()).filter(Boolean) } })

/** C2 跨列表重名检测：把另一侧容器名传入各自 tab 组件 */
const mainNames = computed<string[]>(() => (form.podTemplate?.spec.containers ?? []).map((c) => c.name))
const initNames = computed<string[]>(() => (form.podTemplate?.spec.initContainers ?? []).map((c) => c.name))

// ---------- null↔undefined 桥接：WorkloadDetail 字段为 X|null，子编辑器 defineModel 为 X|undefined ----------
const kind = computed<WorkloadKind>({
  get: () => form.kind,
  set: (v) => { form.kind = v as WorkloadKind },
})

const labels = computed<Record<string, string> | undefined>({
  get: () => form.labels ?? undefined,
  set: (v) => { form.labels = v },
})

const strategy = computed<Strategy | undefined>({
  get: () => form.strategy ?? undefined,
  set: (v) => { form.strategy = v },
})

const description = computed<string>({
  get: () => form.description ?? '',
  set: (v) => { form.description = v || null },
})

/** replicas 可清空（undefined 回显为空）；daemonset 不展示，提交时置 null */
const replicas = computed<number | undefined>({
  get: () => form.replicas ?? undefined,
  set: (v) => { form.replicas = v ?? null },
})

const serviceName = computed<string>({
  get: () => form.serviceName ?? '',
  set: (v) => { form.serviceName = v || null },
})

const mainContainers = computed<ContainerDef[] | undefined>({
  get: () => form.podTemplate?.spec.containers,
  set: (v) => { ensureSpec().containers = v ?? [] },
})

const initContainers = computed<ContainerDef[] | undefined>({
  get: () => form.podTemplate?.spec.initContainers ?? undefined,
  set: (v) => { ensureSpec().initContainers = v },
})

const serviceAccountName = computed<string>({
  get: () => form.podTemplate?.spec.serviceAccountName ?? '',
  set: (v) => { ensureSpec().serviceAccountName = v || null },
})

const nodeName = computed<string>({
  get: () => form.podTemplate?.spec.nodeName ?? '',
  set: (v) => { ensureSpec().nodeName = v || null },
})

const nodeSelector = computed<Record<string, string> | undefined>({
  get: () => form.podTemplate?.spec.nodeSelector ?? undefined,
  set: (v) => { ensureSpec().nodeSelector = v },
})

const affinity = computed<Affinity | undefined>({
  get: () => form.podTemplate?.spec.affinity ?? undefined,
  set: (v) => { ensureSpec().affinity = v },
})

const tolerations = computed<Toleration[] | undefined>({
  get: () => form.podTemplate?.spec.tolerations ?? undefined,
  set: (v) => { ensureSpec().tolerations = v },
})

const volumes = computed<VolumeDef[] | undefined>({
  get: () => form.podTemplate?.spec.volumes ?? undefined,
  set: (v) => { ensureSpec().volumes = v },
})

/** imagePullSecrets：简单 name 行列表；增删走 setter（null 时 get 返回临时数组，直接改会丢） */
const imagePullSecretsList = computed<{ name: string }[]>({
  get: () => form.podTemplate?.spec.imagePullSecrets ?? [],
  set: (v) => { ensureSpec().imagePullSecrets = v },
})

function addImagePullSecret(): void {
  imagePullSecretsList.value = [...imagePullSecretsList.value, { name: '' }]
}

function removeImagePullSecret(i: number): void {
  const next = [...imagePullSecretsList.value]
  next.splice(i, 1)
  imagePullSecretsList.value = next
}

const volumeClaimTemplates = computed<PvcTemplate[] | undefined>({
  get: () => form.volumeClaimTemplates ?? undefined,
  set: (v) => { form.volumeClaimTemplates = v },
})

// ---------- volumeNames：spec.volumes ∪ STS volumeClaimTemplates（模板名同样可被容器按名挂载） ----------
const volumeNames = computed(() => [
  ...(form.podTemplate?.spec.volumes ?? []).map((v) => v.name),
  ...(form.kind === 'statefulset' ? (form.volumeClaimTemplates ?? []).map((t) => t.name) : []),
])

// ---------- kind 切换：清掉 statefulset 专属字段，避免提交不一致 body ----------
watch(
  () => form.kind,
  (k) => {
    if (k !== 'statefulset') {
      form.serviceName = null
      form.volumeClaimTemplates = null
    }
  },
)

// ---------- 编辑回填（deep-merge 到骨架，缺省字段落回默认） ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

function applyDetail(d: WorkloadDetail): void {
  Object.assign(form, d)
  const spec = form.podTemplate?.spec
  // 保证 podTemplate.spec + 非空 containers；B1：restartPolicy 固定 Always
  if (!form.podTemplate || !spec || (spec.containers?.length ?? 0) === 0) {
    form.podTemplate = defaultForm().podTemplate
  } else {
    spec.restartPolicy = 'Always'
  }
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await workloadApi.get(editing.value, {
      tenantId: state.tenantId!,
      clusterId: state.clusterId!,
      namespace: state.namespace!,
    })
    applyDetail(d)
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

onMounted(() => {
  void load()
  if (editing.value && ready.value) void loadDetail()
})
// 上下文晚于挂载才选齐（未持久化上次选择）时补拉详情
watch(ready, (r) => {
  if (r && editing.value && detailState.value === 'idle') void loadDetail()
})

const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

// ---------- 提交：本地校验 → 归一化 → 组装 body ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

/** NodeSelectorTerm 是否有实际内容（空 term 在 OR 语义下匹配所有节点，会令整个 required 失效） */
function termHasContent(t: NodeSelectorTerm): boolean {
  return (t.matchExpressions?.length ?? 0) > 0 || (t.matchFields?.length ?? 0) > 0
}

/** 提交前归一化：imagePullPolicy '' → null；无 handler（httpGet/tcpSocket/exec）的 probe 壳 → null（A2 兜底）；affinity 剔除空 term（空 nodeSelectorTerm 序列化为 {}，OR 语义下静默废掉整个 required） */
function normalizeForSubmit(): void {
  const spec = form.podTemplate?.spec
  if (!spec) return
  for (const c of [...spec.containers, ...(spec.initContainers ?? [])]) {
    if (c.imagePullPolicy === '') c.imagePullPolicy = null
    for (const k of ['livenessProbe', 'readinessProbe', 'startupProbe'] as const) {
      const p = c[k]
      if (p && !p.httpGet && !p.tcpSocket && !p.exec) c[k] = null
    }
    // 端口默认补的一行可能没填 containerPort → 剔除空端口，全空则置 null（避免提交 {containerPort:null}）
    if (c.ports && c.ports.length > 0) {
      const kept = c.ports.filter((p) => p.containerPort != null)
      c.ports = kept.length > 0 ? kept : null
    }
  }
  const aff = spec.affinity
  if (!aff) return
  const na = aff.nodeAffinity
  if (na) {
    if (na.required) {
      const kept = na.required.nodeSelectorTerms.filter(termHasContent)
      na.required = kept.length > 0 ? { nodeSelectorTerms: kept } : null
    }
    if (na.preferred) {
      const keptPref = na.preferred.filter((p) => termHasContent(p.preference))
      na.preferred = keptPref.length > 0 ? keptPref : null
    }
    if (!na.required && !na.preferred) aff.nodeAffinity = null
  }
  const pa = aff.podAntiAffinity
  if (pa) {
    if (pa.required && pa.required.length === 0) pa.required = null
    if (pa.preferred && pa.preferred.length === 0) pa.preferred = null
    if (!pa.required && !pa.preferred) aff.podAntiAffinity = null
  }
  if (!aff.nodeAffinity && !aff.podAntiAffinity) spec.affinity = null
}

const saving = ref(false)

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return

  // 1) name RFC1123
  const name = form.name.trim()
  if (!name || !RFC1123_RE.test(name)) {
    ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾')
    return
  }

  const spec = form.podTemplate?.spec
  const mains = spec?.containers ?? []
  const inits = spec?.initContainers ?? []

  // 2) 至少一个主容器
  if (mains.length === 0) {
    ElMessage.warning('至少需要一个容器')
    return
  }

  // 3) 容器名在 main ∪ init 内唯一（空名 = 未填写，交给后端提示）
  const seen = new Set<string>()
  for (const c of [...mains, ...inits]) {
    const n = c.name.trim()
    if (!n) continue
    if (seen.has(n)) {
      ElMessage.warning(`容器名重复：${n}`)
      return
    }
    seen.add(n)
  }

  // 4) volumeMount 引用完整性（含 STS 模板名）
  const defined = new Set(volumeNames.value)
  for (const c of [...mains, ...inits]) {
    for (const m of c.volumeMounts ?? []) {
      if (!defined.has(m.name)) {
        ElMessage.warning(`volumeMount「${m.name}」没有对应的 Volume（容器：${c.name || '未命名'}）`)
        return
      }
    }
  }

  // 5) STS C4：每个 volumeClaimTemplate 须被某容器以同名 volumeMount 引用
  if (form.kind === 'statefulset') {
    const mounted = new Set([...mains, ...inits].flatMap((c) => (c.volumeMounts ?? []).map((m) => m.name)))
    for (const t of form.volumeClaimTemplates ?? []) {
      if (!mounted.has(t.name)) {
        ElMessage.warning(`volumeClaimTemplate「${t.name || '未命名'}」未被任何容器以同名 volumeMount 引用`)
        return
      }
    }
  }

  // 6) B4：requests ≤ limits（主容器 + init 容器 tab 列表 → ResourcesEditor.isValid）
  if (!(mainTabsRef.value?.isValid() ?? true) || !(initTabsRef.value?.isValid() ?? true)) {
    ElMessage.warning('资源 requests 不能大于 limits')
    return
  }

  normalizeForSubmit()
  const body: WorkloadDetail = { ...form, namespace: state.namespace!, kind: form.kind }
  if (body.kind === 'daemonset') body.replicas = null

  saving.value = true
  try {
    const ctx2 = { tenantId: state.tenantId!, clusterId: state.clusterId! }
    if (editing.value) {
      await workloadApi.update(editing.value, ctx2, body)
      ElMessage.success('保存成功')
    } else {
      await workloadApi.create(ctx2, body)
      ElMessage.success('创建成功')
    }
    router.push('/resources/workloads')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function goBack(): void {
  router.push('/resources/workloads')
}

// ---------- 页面元信息 ----------
const pageTitle = computed(() => (editing.value ? `编辑工作负载 · ${editing.value}` : '创建工作负载'))

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div>
    <PageHeader :title="pageTitle" :description="contextDesc">
      <!-- 类型 kind：与标题同行、靠右，但不顶到最右（与「返回」之间留间距） -->
      <div class="ph-kind">
        <span class="ph-kind-label">类型 <FieldHelp tip="工作负载类型：Deployment（无状态）、StatefulSet（有状态，稳定网络标识+独立存储）、DaemonSet（每个节点一个副本）。创建后不可修改。" /></span>
        <el-select v-model="kind" :disabled="!!editing" style="width: 180px">
          <el-option label="Deployment" value="deployment" />
          <el-option label="StatefulSet" value="statefulset" />
          <el-option label="DaemonSet" value="daemonset" />
        </el-select>
      </div>
      <el-button class="ph-back" @click="goBack">返回</el-button>
    </PageHeader>

    <!-- 上下文未选齐 -->
    <EmptyState
      v-if="!ready"
      title="尚未选择上下文"
      description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑工作负载。"
    />

    <template v-else>
      <div v-if="formVisible" class="editor-layout">
        <!-- 主内容区：模块用 v-show 切换，全部常驻 DOM（子编辑器 ref / B4 校验不受切模块影响） -->
        <div class="editor-main">
          <!-- 模块①：基础信息（含更新策略、主容器常用字段、镜像拉取/账号） -->
          <div v-show="activeModule === 'basic'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header><span class="sec-title">基础信息</span></template>
              <el-form label-width="140px" label-position="left">
                <el-form-item required>
                  <template #label>名称 <FieldHelp tip="工作负载名称，须符合 RFC1123（小写字母/数字/-，以字母或数字开头结尾）。创建后不可修改。" /></template>
                  <el-input v-model="form.name" :disabled="!!editing" placeholder="例如 web-app" style="width: 360px" />
                </el-form-item>
                <el-form-item>
                  <template #label>描述 <FieldHelp tip="可选的描述信息，会存到 K8s 注解里，便于识别用途。" /></template>
                  <el-input v-model="description" type="textarea" :rows="2" placeholder="可选" style="width: 360px" />
                </el-form-item>

                  <!-- #3 名称在最上 -->
                  <el-form-item>
                    <template #label>容器名称 <FieldHelp tip="主容器的名字，须为 DNS_LABEL（小写字母/数字/连字符，≤63 位）。同一 Pod 内唯一。" /></template>
                    <el-input v-model="pName" placeholder="如 web" style="width: 360px" />
                  </el-form-item>
                  <!-- #3 镜像在名称下；#5 拉取策略与镜像同行、在右侧 -->

                    <el-form-item>
                      <template #label>镜像 <FieldHelp tip="主容器运行的容器镜像，如 nginx:1.27。" /></template>
                      <el-input v-model="pImage" placeholder="如 nginx:1.27" style="width: 360px" />
                    </el-form-item>
                    <el-form-item>
                      <template #label>拉取策略 <FieldHelp tip="imagePullPolicy：Always=每次拉取 / IfNotPresent=本地有则用 / Never=仅本地。留空由 K8s 按 tag 决定。" /></template>
                      <el-select v-model="pPullPolicy" clearable placeholder="默认" style="width: 360px">
                        <el-option v-for="p in ['IfNotPresent', 'Always', 'Never']" :key="p" :label="p" :value="p" />
                      </el-select>
                    </el-form-item>


                  <!-- #6 端口在镜像和命令中间 -->
                  <el-form-item>
                    <template #label>端口 <FieldHelp tip="容器监听的端口，供 Service / 探针按名或按号引用。" /></template>
                    <PortEditor v-model="pPorts" />
                  </el-form-item>
                  <!-- #4 命令在镜像下；参数与命令同行、在命令左侧 -->
                  <div class="field-pair">
                    <el-form-item>
                      <template #label>Command <FieldHelp tip="覆盖镜像默认的启动命令，每行一个或用逗号分隔。" /></template>
                      <el-input v-model="pCommandText" type="textarea" :rows="2" placeholder="每行一个（或逗号分隔）" style="width: 360px" />
                    </el-form-item>
                    <el-form-item label-width="60px">
                      <template #label>Args <FieldHelp tip="传给启动命令的参数，每行一个或用逗号分隔。" /></template>
                      <el-input v-model="pArgsText" type="textarea" :rows="2" placeholder="每行一个（或逗号分隔）" style="width: 360px" />
                    </el-form-item>
                  </div>
                  <!-- #7 工作目录在命令 / 参数下面 -->
                  <el-form-item>
                    <template #label>工作目录 <FieldHelp tip="workingDir：容器内进程的工作目录（可选）。" /></template>
                    <el-input v-model="pWorkingDir" placeholder="可选，如 /app" style="width: 360px" />
                  </el-form-item>
                <el-form-item>
                  <template #label>标签<FieldHelp tip="键值标签，用于筛选与分组（kubectl -l、Service selector 等）。" /></template>
                  <LabelEditor v-model="labels" class="sub-editor" style="max-width: 520px" />
                </el-form-item>
                <el-form-item v-if="form.kind !== 'daemonset'">
                  <template #label>副本 <FieldHelp tip="期望的副本数量。DaemonSet 由节点数决定，不设置此项。" /></template>
                  <el-input-number v-model="replicas" :min="0" :max="64" controls-position="right" />
                </el-form-item>
                <el-form-item v-if="form.kind === 'statefulset'">
                  <template #label>Headless Service<FieldHelp tip="StatefulSet 关联的 Headless Service 名称，提供稳定网络标识；留空默认等于工作负载名。创建后不可修改。" /></template>
                  <el-input v-model="serviceName" :disabled="!!editing" placeholder="留空默认 = 工作负载名" style="width: 360px" />
                </el-form-item>

                <el-form-item>
                  <template #label>ServiceAccount <FieldHelp tip="Pod 使用的 ServiceAccount，决定其访问 K8s API 的权限；留空默认 default。" /></template>
                  <el-input v-model="serviceAccountName" placeholder="可选，默认 default" style="width: 360px" />
                </el-form-item>
              </el-form>
            </el-card>

          </div>

          <!-- 模块②：Pod 容器（主容器 tab） -->
          <div v-show="activeModule === 'containers'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header>
                <div class="sec-head">
                  <span class="sec-title">容器 containers</span>
                  <el-button plain size="medium" @click="mainTabsRef?.add()">+ 添加容器</el-button>
                </div>
              </template>
              <ContainerListEditor
                ref="mainTabsRef"
                v-model="mainContainers"
                :is-init="false"
                :volume-names="volumeNames"
                :external-names="initNames"
              />
            </el-card>
          </div>

          <!-- 模块③：初始化容器（init 容器 tab） -->
          <div v-show="activeModule === 'init'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header>
                <div class="sec-head">
                  <span class="sec-title">Init 容器 initContainers（可选）</span>
                  <el-button plain size="medium" @click="initTabsRef?.add()">+ 添加容器</el-button>
                </div>
              </template>
              <ContainerListEditor
                ref="initTabsRef"
                v-model="initContainers"
                :is-init="true"
                :volume-names="volumeNames"
                :external-names="mainNames"
              />
            </el-card>
          </div>

          <!-- 模块：更新策略（不常用，单独一屏；放在初始化容器之后） -->
          <div v-show="activeModule === 'strategy'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header><span class="sec-title">更新策略</span></template>
              <StrategyEditor v-model="strategy" :kind="form.kind" />
            </el-card>
          </div>

          <!-- 模块④：调度策略 -->
          <div v-show="activeModule === 'scheduling'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header><span class="sec-title">调度策略</span></template>
              <el-form label-width="150px" label-position="left">
                <el-form-item>
                  <template #label>nodeName <FieldHelp tip="把 Pod 固定调度到指定节点（一般不用）。设置后会忽略 nodeSelector / 亲和。" /></template>
                  <el-input v-model="nodeName" placeholder="可选，指定某节点名" style="width: 280px" />
                  <div v-if="nodeName" class="form-tip warn">设置 nodeName 后，nodeSelector / 亲和的节点选择将被忽略</div>
                </el-form-item>
                <el-form-item>
                  <template #label>nodeSelector <FieldHelp tip="按节点标签筛选可调度节点（所有键值须全部匹配）。" /></template>
                  <LabelEditor v-model="nodeSelector" class="sub-editor" />
                </el-form-item>
                <el-form-item>
                  <template #label>affinity <FieldHelp tip="更灵活的节点 / Pod 亲和与反亲和规则（required 必须满足，preferred 尽量满足）。" /></template>
                  <AffinityEditor v-model="affinity" />
                </el-form-item>
                <el-form-item>
                  <template #label>tolerations <FieldHelp tip="容忍度：让 Pod 可调度到带有对应污点（taint）的节点。" /></template>
                  <TolerationEditor v-model="tolerations" />
                </el-form-item>
              </el-form>
            </el-card>
          </div>

          <!-- 模块⑤：存储（volumes 全 kind；pvc 模板仅 statefulset） -->
          <div v-show="activeModule === 'storage'" class="module-block">
            <el-card shadow="never" class="sec-card">
              <template #header><span class="sec-title">卷 volumes <FieldHelp tip="Pod 级卷定义，供容器按名挂载（emptyDir / configMap / secret / PVC 等）。" /></span></template>
              <VolumeEditor v-model="volumes" />
            </el-card>
            <el-card v-if="form.kind === 'statefulset'" shadow="never" class="sec-card">
              <template #header><span class="sec-title">存储卷模板 volumeClaimTemplates <FieldHelp tip="StatefulSet 专属：为每个副本自动创建独立 PVC 的模板。" /></span></template>
              <PvcTemplateEditor v-model="volumeClaimTemplates" />
            </el-card>
          </div>

          <!-- 操作 -->
          <div class="form-actions">
            <el-button @click="goBack">取消 / 返回</el-button>
            <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
          </div>
        </div>

        <!-- 右侧导航 -->
        <nav class="editor-nav">
          <button type="button" class="nav-item" :class="{ active: activeModule === 'basic' }" @click="activeModule = 'basic'">基础信息</button>
          <button type="button" class="nav-item" :class="{ active: activeModule === 'containers' }" @click="activeModule = 'containers'">Pod 容器</button>
          <button type="button" class="nav-item" :class="{ active: activeModule === 'init' }" @click="activeModule = 'init'">初始化容器</button>
          <button type="button" class="nav-item" :class="{ active: activeModule === 'strategy' }" @click="activeModule = 'strategy'">更新策略</button>
          <button type="button" class="nav-item" :class="{ active: activeModule === 'scheduling' }" @click="activeModule = 'scheduling'">调度策略</button>
          <button type="button" class="nav-item" :class="{ active: activeModule === 'storage' }" @click="activeModule = 'storage'">存储</button>
        </nav>
      </div>

      <!-- 编辑加载失败：不渲染可编辑表单 -->
      <EmptyState
        v-else-if="detailState === 'error'"
        title="加载工作负载失败"
        description="请返回列表重试；若该工作负载已被删除，刷新列表即可。"
      >
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </EmptyState>

      <div v-else class="loading-tip">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
.editor-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}


.editor-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.module-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 一行内多字段：按内容宽度顺位排列（不平分整行） */
.field-pair {
  display: flex;
  gap: 5rem;
  align-items: flex-start;
}
/* 类型选择器：与标题同行、靠右（不顶到最右） */
.ph-kind {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ph-kind-label {
  font-size: 13px;
  color: var(--text-2);
  white-space: nowrap;
}
/* 「返回」与 kind 之间留间距，让 kind 靠右但不贴最右边缘 */
.ph-back {
  margin-left: 40px;
}
/* 卡片头：标题左、操作按钮右 */
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.editor-nav {
  width: 180px;
  flex-shrink: 0;
  position: sticky;
  top: 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.nav-item {
  text-align: left;
  padding: 10px 14px;
  border: 1px solid var(--el-border-color);
  background: var(--el-bg-color, #fff);
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-2);
}
.nav-item:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}
.nav-item.active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  font-weight: 600;
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
.form-tip {
  width: 100%;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.form-tip.warn {
  color: var(--el-color-warning);
}
.kv-editor {
  width: 100%;
}
.kv-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
/* add 按钮按内容自适应宽度，不再铺满整行 */
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
