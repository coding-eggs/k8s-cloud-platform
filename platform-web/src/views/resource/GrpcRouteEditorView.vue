<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { grpcRouteApi } from '@/api'
import { useParentCandidates } from '@/composables/useParentCandidates'
import { useResourceContext } from '@/stores/context'
import type { K8sGrpcFilter, K8sGrpcMatch, K8sGrpcRule, K8sGrpcRoute, K8sRouteBackendRef, K8sRouteParentRef } from '@/types'
import type { CandidateBucket } from '@/composables/useParentCandidates'

/**
 * GRPCRoute 独立编辑页（命名空间级、租户域）。骨架同 HTTPRoute，但"匹配什么"和"能挂什么过滤器"不同：
 * <ul>
 *   <li>matches 里是 <b>服务名 + 方法名</b>（不是 URL 路径）+ 请求头（gRPC metadata）——
 *       <b>没有 path、没有 queryParams</b>。</li>
 *   <li>filters 只有 4 种：RequestHeaderModifier / ResponseHeaderModifier / RequestMirror / ExtensionRef
 *       —— <b>没有 RequestRedirect / URLRewrite</b>（gRPC 没有"重定向到另一个 URL"的概念）。</li>
 * </ul>
 *
 * <h2>未建模内容（三层，同 HTTPRoute 编辑器）</h2>
 * rule 级 {@code sessionPersistence}、未建模 filter type 由后端 fetch-overlay 保住；
 * {@code parentRefs[].group/kind/port} 由前端原样回传；全量在列表页 YAML tab 可见。
 */
const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load, namespacesOfCluster } = useResourceContext()

const editing = ref<string | null>((route.query.name as string) || null)

const ctx3 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))
const ctx2 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
}))

/** 本版建模的 filter 类型（与后端 GrpcRouteConverter.MODELED_FILTER_TYPES 对齐） */
const FILTER_TYPES = ['RequestHeaderModifier', 'ResponseHeaderModifier', 'RequestMirror', 'ExtensionRef'] as const
const METHOD_TYPES = ['Exact', 'RegularExpression'] as const

/** 父引用名称候选（按 kind + 命名空间缓存；见 composables/useParentCandidates.ts） */
const { bucket, ensure } = useParentCandidates()

/** 父引用的生效命名空间：'' = 本命名空间 */
function effectiveNs(p: ParentRow): string {
  return p.namespace.trim() || state.namespace!
}

/** kind 不在 {缺省, Service} → 未建模：只读展示、无候选、名称与段名手输 */
function hasUnmodeledKind(p: ParentRow): boolean {
  return !!p.parentKind && p.parentKind !== 'Service'
}

function bucketOf(p: ParentRow): CandidateBucket {
  return bucket(p.parentKind, effectiveNs(p))
}

/**
 * 用户改了父资源类型：候选与段名语义都换了 → 清掉名称与段名；
 * group 属于旧 kind 的透传值（如 ServiceImport 的 multicluster.x-k8s.io），一并归位。
 * 必须挂 @change：回填也会改 modelValue，写成 watch 会把刚加载的值抹掉。
 */
function onParentKindChange(p: ParentRow): void {
  p.name = ''
  p.sectionName = ''
  p.group = null
}

/** 用户改了命名空间：候选取自新命名空间，旧名字/段名必然失效 → 清掉（同样只挂 @change） */
function onParentNamespaceChange(p: ParentRow): void {
  p.name = ''
  p.sectionName = ''
}

function noDataText(p: ParentRow): string {
  if (!ready.value) return '上下文未就绪'
  if (hasUnmodeledKind(p)) return `「${p.parentKind}」类型的父资源不支持候选，请直接输入名称`
  const b = bucketOf(p)
  if (b.failed) return '候选拉取失败（权限或 CRD 未装），可直接输入名称'
  return p.parentKind === 'Service'
    ? '该命名空间暂无 Service（可直接输入名称）'
    : '该命名空间暂无 Gateway（可直接输入名称）'
}

function namePlaceholder(p: ParentRow): string {
  if (hasUnmodeledKind(p)) return `输入 ${p.parentKind} 名称`
  return p.parentKind === 'Service'
    ? '选择或输入该命名空间的 Service 名'
    : '选择或输入该命名空间的 Gateway 名'
}

/** 段名候选取自**所选中的那个**父资源（不是整个命名空间） */
function sectionOptions(p: ParentRow): string[] {
  const name = p.name.trim()
  if (!name) return []
  const c = bucketOf(p).list.find((x) => x.name === name)
  if (!c) return []
  return p.parentKind === 'Service' ? c.portNames : c.listeners
}

function sectionNoDataText(p: ParentRow): string {
  if (!ready.value) return '上下文未就绪'
  if (hasUnmodeledKind(p)) return `「${p.parentKind}」类型的父资源不支持候选，请直接输入`
  const name = p.name.trim()
  if (!name) return '请先选择父资源'
  const c = bucketOf(p).list.find((x) => x.name === name)
  if (!c) return '该名称不在候选里（手输的无法列出它的段），可自行输入'
  if (p.parentKind === 'Service' && !c.portNames.length) return '该服务的端口没有命名，留空即作用于全部端口'
  return p.parentKind === 'Service' ? '该服务没有可选的命名端口' : '该 Gateway 没有监听器'
}

/** 名称行的行名：未建模 kind 显示它自己的值 —— 别把 ServiceImport 之类的父引用标成 Gateway */
function nameRowLabel(p: ParentRow): string {
  return p.parentKind === 'Service' ? 'Service' : p.parentKind || 'Gateway'
}

/** 段名行的行名：未建模 kind 用 spec 的通用说法（它怎么解释 sectionName 由实现自定） */
function sectionRowLabel(p: ParentRow): string {
  if (p.parentKind === 'Service') return '端口名（sectionName）'
  return hasUnmodeledKind(p) ? '段名（sectionName）' : '监听器（sectionName）'
}

// ---------- 表单模型 ----------
interface ParentRow {
  /** '' = 缺省 Gateway；'Service'；其他值 = 未建模 kind（原样透传，本页不改） */
  parentKind: string
  name: string
  namespace: string
  sectionName: string
  /** UI 不编辑，仅回填透传 */
  group?: string | null
  port?: number | null
}
interface BackendRow {
  name: string
  namespace: string
  port: number | null
  weight: number | null
  group?: string | null
  kind?: string | null
}
interface KvRow { name: string; value: string; type: string }
interface MatchRow {
  methodType: string
  methodService: string
  methodMethod: string
  headers: KvRow[]
}
interface FilterRow {
  type: string
  headerSet: { name: string; value: string }[]
  headerAdd: { name: string; value: string }[]
  headerRemove: string[]
  mirrorName: string
  mirrorNamespace: string
  mirrorPort: number | null
  mirrorPercent: number | null
  extGroup: string
  extKind: string
  extName: string
}
interface RuleRow {
  name: string
  matches: MatchRow[]
  filters: FilterRow[]
  backendRefs: BackendRow[]
}

function newBackend(): BackendRow { return { name: '', namespace: '', port: null, weight: null } }
function newMatch(): MatchRow { return { methodType: '', methodService: '', methodMethod: '', headers: [] } }
function newFilter(): FilterRow {
  return {
    type: 'RequestHeaderModifier',
    headerSet: [], headerAdd: [], headerRemove: [],
    mirrorName: '', mirrorNamespace: '', mirrorPort: null, mirrorPercent: null,
    extGroup: '', extKind: '', extName: '',
  }
}
function newRule(): RuleRow { return { name: '', matches: [newMatch()], filters: [], backendRefs: [newBackend()] } }

const form = reactive({
  name: '',
  parentRefs: [{ parentKind: '', name: '', namespace: '', sectionName: '' }] as ParentRow[],
  hostnames: [] as string[],
  rules: [newRule()] as RuleRow[],
})

/** 加载到的存量路由里本版不支持的 filter 类型（保存不影响它们，仅提示） */
const unsupportedFilterTypes = ref<string[]>([])

function addParent(): void { form.parentRefs.push({ parentKind: '', name: '', namespace: '', sectionName: '' }) }
function removeParent(i: number): void { form.parentRefs.splice(i, 1) }
function addRule(): void { form.rules.push(newRule()) }
function removeRule(i: number): void { form.rules.splice(i, 1) }
function addMatch(r: RuleRow): void { r.matches.push(newMatch()) }
function removeMatch(r: RuleRow, i: number): void { r.matches.splice(i, 1) }
function addFilter(r: RuleRow): void { r.filters.push(newFilter()) }
function removeFilter(r: RuleRow, i: number): void { r.filters.splice(i, 1) }
function addBackend(r: RuleRow): void { r.backendRefs.push(newBackend()) }
function removeBackend(r: RuleRow, i: number): void { r.backendRefs.splice(i, 1) }

// ---------- 回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

function fromParent(p: K8sRouteParentRef): ParentRow {
  return {
    parentKind: p.kind ?? '',
    name: p.name ?? '',
    namespace: p.namespace ?? '',
    sectionName: p.sectionName ?? '',
    group: p.group,
    port: p.port,
  }
}
function fromBackend(b: K8sRouteBackendRef): BackendRow {
  return { name: b.name ?? '', namespace: b.namespace ?? '', port: b.port ?? null, weight: b.weight ?? null, group: b.group, kind: b.kind }
}
function fromMatch(m: K8sGrpcMatch): MatchRow {
  return {
    methodType: m.method?.type ?? '',
    methodService: m.method?.service ?? '',
    methodMethod: m.method?.method ?? '',
    headers: (m.headers ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '', type: h.type ?? 'Exact' })),
  }
}
function fromFilter(f: K8sGrpcFilter): FilterRow {
  const row = newFilter()
  row.type = f.type ?? 'RequestHeaderModifier'
  const hf = row.type === 'ResponseHeaderModifier' ? f.responseHeaderModifier : f.requestHeaderModifier
  row.headerSet = (hf?.set ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '' }))
  row.headerAdd = (hf?.add ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '' }))
  row.headerRemove = [...(hf?.remove ?? [])]
  if (f.requestMirror) {
    row.mirrorName = f.requestMirror.backendRef?.name ?? ''
    row.mirrorNamespace = f.requestMirror.backendRef?.namespace ?? ''
    row.mirrorPort = f.requestMirror.backendRef?.port ?? null
    row.mirrorPercent = f.requestMirror.percent ?? null
  }
  if (f.extensionRef) {
    row.extGroup = f.extensionRef.group ?? ''
    row.extKind = f.extensionRef.kind ?? ''
    row.extName = f.extensionRef.name ?? ''
  }
  return row
}
function fromRule(r: K8sGrpcRule): RuleRow {
  return {
    name: r.name ?? '',
    matches: (r.matches ?? []).map(fromMatch),
    filters: (r.filters ?? []).map(fromFilter),
    backendRefs: (r.backendRefs ?? []).map(fromBackend),
  }
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await grpcRouteApi.get(editing.value, ctx3.value)
    form.name = d.name
    form.parentRefs = (d.parentRefs ?? []).map(fromParent)
    if (!form.parentRefs.length) form.parentRefs = [{ parentKind: '', name: '', namespace: '', sectionName: '' }]
    form.hostnames = [...(d.hostnames ?? [])]
    form.rules = (d.rules ?? []).map(fromRule)
    if (!form.rules.length) form.rules = [newRule()]
    unsupportedFilterTypes.value = [
      ...new Set(
        (d.rules ?? [])
          .flatMap((r) => r.filters ?? [])
          .map((f) => f.type ?? '')
          .filter((t) => t && !(FILTER_TYPES as readonly string[]).includes(t)),
      ),
    ]
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

watch([() => ready.value, () => state.namespace], ([r]) => {
  if (!r) return
  if (editing.value) void loadDetail()
}, { immediate: true })

/** 父引用候选预热：kind / 命名空间任一变化即按需拉取（命中缓存则不发请求）。
 *  串进 form.parentRefs 的 key 是为了回填之后也预热到加载进来的那些行。 */
watch(
  () => `${ready.value}|${state.namespace ?? ''}|${form.parentRefs.map((p) => `${p.parentKind}|${p.namespace}`).join('|')}`,
  () => {
    if (!ready.value) return
    for (const p of form.parentRefs) ensure(p.parentKind, effectiveNs(p))
  },
  { immediate: true },
)

// ---------- 提交 ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
/** SectionName 的 CRD pattern（≤253，见 Gateway API shared_types） */
const SECTION_NAME_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/
const HOSTNAME_RE = /^(\*\.)?[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/
/** gRPC 服务名/方法名的宽松校验：字母数字、点、下划线、短横 */
const GRPC_NAME_RE = /^[A-Za-z_][A-Za-z0-9_.-]*$/

function cleanKv(rows: { name: string; value: string }[]): { name: string; value: string }[] {
  return rows.filter((r) => r.name.trim()).map((r) => ({ name: r.name.trim(), value: r.value }))
}

function buildFilter(f: FilterRow): K8sGrpcFilter | null {
  switch (f.type) {
    case 'RequestHeaderModifier':
    case 'ResponseHeaderModifier': {
      const body = {
        set: cleanKv(f.headerSet).length ? cleanKv(f.headerSet) : null,
        add: cleanKv(f.headerAdd).length ? cleanKv(f.headerAdd) : null,
        remove: f.headerRemove.map((s) => s.trim()).filter(Boolean).length
          ? f.headerRemove.map((s) => s.trim()).filter(Boolean) : null,
      }
      if (!body.set && !body.add && !body.remove) return null
      return f.type === 'RequestHeaderModifier'
        ? { type: f.type, requestHeaderModifier: body }
        : { type: f.type, responseHeaderModifier: body }
    }
    case 'RequestMirror': {
      if (!f.mirrorName.trim()) return null
      return {
        type: f.type,
        requestMirror: {
          backendRef: { name: f.mirrorName.trim(), namespace: f.mirrorNamespace.trim() || null, port: f.mirrorPort },
          percent: f.mirrorPercent,
        },
      }
    }
    case 'ExtensionRef': {
      if (!f.extGroup.trim() || !f.extKind.trim() || !f.extName.trim()) return null
      return { type: f.type, extensionRef: { group: f.extGroup.trim(), kind: f.extKind.trim(), name: f.extName.trim() } }
    }
    default:
      return null
  }
}

function validate(): string | null {
  const name = (editing.value ?? form.name).trim()
  if (!name) return '请输入名称'
  if (!RFC1123_RE.test(name)) return '名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'
  if (!form.parentRefs.some((p) => p.name.trim())) return '至少需要一个父资源（parentRefs）'
  for (const [pi, p] of form.parentRefs.entries()) {
    const sec = p.sectionName.trim()
    if (!sec) continue
    // spec 的 SectionName：pattern + MaxLength 253（填错监听器名/端口名是"静默不挂载"，故提交前拦一道）
    if (sec.length > 253 || !SECTION_NAME_RE.test(sec)) {
      return `父引用 #${pi + 1}：sectionName 须为合法段名（小写字母/数字/'-'/'.'，≤253）：「${sec}」`
    }
  }
  for (const h of form.hostnames) {
    if (h.trim() && !HOSTNAME_RE.test(h.trim())) return `主机名格式不合法：「${h}」`
  }
  if (!form.rules.length) return '至少需要一条规则'
  for (const [ri, r] of form.rules.entries()) {
    const no = `规则 #${ri + 1}`
    for (const [mi, m] of r.matches.entries()) {
      if (m.methodService.trim() && !GRPC_NAME_RE.test(m.methodService.trim())) {
        return `${no} 匹配 #${mi + 1}：服务名格式不合法（如 helloworld.Greeter）`
      }
      if (m.methodMethod.trim() && !GRPC_NAME_RE.test(m.methodMethod.trim())) {
        return `${no} 匹配 #${mi + 1}：方法名格式不合法（如 SayHello）`
      }
    }
    const backends = r.backendRefs.filter((b) => b.name.trim())
    if (!backends.length) return `${no}：至少需要一个后端（backendRefs）`
    for (const b of backends) {
      if (b.port == null || b.port < 1 || b.port > 65535) return `${no}：后端「${b.name}」的端口须为 1–65535`
      if (b.weight != null && (b.weight < 0 || b.weight > 1000000)) return `${no}：后端「${b.name}」的权重须为 0–1000000`
    }
    const types = r.filters.map((f) => f.type)
    if (new Set(types).size !== types.length) return `${no}：同一过滤器类型只能出现一次`
  }
  return null
}

const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !ready.value) return
  const err = validate()
  if (err) { ElMessage.warning(err); return }

  const body: K8sGrpcRoute = {
    name: (editing.value ?? form.name).trim(),
    // tenantId / clusterId 由 makeResourceApi 的 ctx 参数合并进请求体；namespace 必须来自 body（Create 的边界判据）
    namespace: state.namespace!,
    // kind 由「父资源类型」选择器决定（'' → null = 缺省 Gateway）；group 仍是透传
    // （只有未建模 kind 才带值），kind=Service 时的显式空串由后端写盘侧归一
    parentRefs: form.parentRefs
      .filter((p) => p.name.trim())
      .map((p) => ({
        name: p.name.trim(),
        namespace: p.namespace.trim() || null,
        sectionName: p.sectionName.trim() || null,
        group: p.group ?? null,
        kind: p.parentKind.trim() || null,
        port: p.port ?? null,
      })),
    hostnames: form.hostnames.map((h) => h.trim()).filter(Boolean).length
      ? form.hostnames.map((h) => h.trim()).filter(Boolean) : null,
    rules: form.rules.map((r) => ({
      name: r.name.trim() || null,
      matches: r.matches.map((m) => ({
        method: (m.methodType || m.methodService.trim() || m.methodMethod.trim())
          ? {
              type: m.methodType || null,
              service: m.methodService.trim() || null,
              method: m.methodMethod.trim() || null,
            }
          : null,
        headers: m.headers.filter((h) => h.name.trim()).length
          ? m.headers.filter((h) => h.name.trim()).map((h) => ({ type: h.type, name: h.name.trim(), value: h.value }))
          : null,
      })),
      filters: r.filters.map(buildFilter).filter((f): f is K8sGrpcFilter => f !== null).length
        ? r.filters.map(buildFilter).filter((f): f is K8sGrpcFilter => f !== null)
        : null,
      backendRefs: r.backendRefs
        .filter((b) => b.name.trim())
        .map((b) => ({
          name: b.name.trim(),
          namespace: b.namespace.trim() || null,
          port: b.port,
          weight: b.weight,
          group: b.group ?? null,
          kind: b.kind ?? null,
        })),
    })),
  }

  saving.value = true
  try {
    if (editing.value) {
      await grpcRouteApi.update(editing.value, ctx2.value, body)
      ElMessage.success('保存成功')
    } else {
      await grpcRouteApi.create(ctx2.value, body)
      ElMessage.success('创建成功')
    }
    router.push({ name: 'grpcroutes' })
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push({ name: 'grpcroutes' }) }

const pageTitle = computed(() => (editing.value ? '编辑 GRPCRoute' : '创建 GRPCRoute'))
const formVisible = computed(() => ready.value && (!editing.value || detailState.value === 'loaded'))
const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})

onMounted(() => { void load() })
</script>

<template>
  <div class="editor-page">
    <PageHeader :title="pageTitle" :description="contextDesc">
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState
      v-if="detailState === 'error'"
      title="加载失败"
      description="该 GRPCRoute 可能已被删除，或所选命名空间下不存在。"
    >
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <el-alert
        v-if="unsupportedFilterTypes.length"
        type="info"
        show-icon
        :closable="false"
        class="unsupported-alert"
        :title="`本条路由含本版不建模的过滤器：${unsupportedFilterTypes.join('、')}`"
        description="它们不会因本次编辑丢失（后端会原样保留），但本页看不到也改不了 —— 需要时请在列表页的 YAML 标签里查看全量。"
      />

      <el-form v-if="formVisible" label-position="left" label-width="200px" class="editor-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 greeter-route（RFC-1123）" style="max-width: 420px" />
          <FieldHelp tip="GRPCRoute 名（本命名空间内唯一）。创建后不可改。" />
        </el-form-item>

        <el-divider content-position="left">父资源（parentRefs）</el-divider>

        <div v-for="(p, i) in form.parentRefs" :key="i" class="sub-block">
          <div class="sub-head">
            <span class="sub-title">父引用 #{{ i + 1 }}</span>
            <span v-if="p.port != null" class="parent-hint">port={{ p.port }}（本页不改，可在 YAML 里改）</span>
            <el-button v-if="form.parentRefs.length > 1" link type="danger" @click="removeParent(i)">删除</el-button>
          </div>

          <el-form-item label="父资源类型" required>
            <el-select
              v-model="p.parentKind"
              placeholder="Gateway（缺省）"
              style="max-width: 240px"
              @change="onParentKindChange(p)"
            >
              <el-option label="Gateway（缺省）" value="" />
              <el-option label="Service" value="Service" />
            </el-select>
            <span v-if="hasUnmodeledKind(p)" class="parent-hint">
              kind={{ p.parentKind }} 未建模，本页不改它（可在 YAML 里改）
            </span>
            <FieldHelp tip="父资源可以是 Gateway，也可以是 Service（Istio mesh：把本路由的规则挂到该服务上）。缺省 Gateway；指向 Service 时 group 必须显式为空串，由平台自动写入。" />
          </el-form-item>

          <el-form-item label="命名空间">
            <el-select
              v-model="p.namespace"
              filterable
              allow-create
              default-first-option
              placeholder="本命名空间（留空）"
              style="max-width: 300px"
              @change="onParentNamespaceChange(p)"
            >
              <el-option label="本命名空间（留空）" value="" />
              <el-option v-for="ns in namespacesOfCluster" :key="ns" :label="ns" :value="ns" />
            </el-select>
            <FieldHelp tip="父资源所在命名空间。候选取本租户在当前集群的命名空间；留空 = 本命名空间。跨命名空间引用需目标侧放行（Gateway 的 allowedRoutes + ReferenceGrant），否则引用不会被接受。" />
          </el-form-item>

          <el-form-item :label="nameRowLabel(p)" required>
            <el-select
              v-model="p.name"
              filterable
              allow-create
              default-first-option
              :loading="bucketOf(p).loading"
              :no-data-text="noDataText(p)"
              :placeholder="namePlaceholder(p)"
              style="max-width: 360px"
            >
              <el-option
                v-for="c in bucketOf(p).list"
                :key="c.name"
                :label="c.waypointFor ? `${c.name} · waypoint` : c.name"
                :value="c.name"
              />
            </el-select>
            <FieldHelp
              v-if="p.parentKind === 'Service'"
              tip="把本路由的规则挂到该 Service 上（Istio mesh）。跨命名空间同样需要目标命名空间放行。"
            />
            <FieldHelp v-else-if="!hasUnmodeledKind(p)" tip="要挂载到的 Gateway。它需要一个支持 GRPCRoute 的监听器（HTTP/HTTPS 协议的监听器通常都能挂）。" />
          </el-form-item>

          <el-form-item :label="sectionRowLabel(p)">
            <el-select
              v-model="p.sectionName"
              filterable
              allow-create
              default-first-option
              :no-data-text="sectionNoDataText(p)"
              :placeholder="
                p.parentKind === 'Service'
                  ? '留空 = 该服务全部端口'
                  : '留空 = 该 Gateway 上全部匹配的监听器'
              "
              style="max-width: 300px"
            >
              <el-option v-for="s in sectionOptions(p)" :key="s" :label="s" :value="s" />
            </el-select>
            <FieldHelp
              v-if="p.parentKind === 'Service'"
              tip="只作用于该 Service 的指定端口（填端口名）。留空 = 作用于该服务全部端口。"
            />
            <FieldHelp
              v-else-if="!hasUnmodeledKind(p)"
              tip="只挂到指定的那一个监听器（填监听器名）。留空表示挂到所有匹配的监听器。"
            />
          </el-form-item>
        </div>
        <el-button size="small" @click="addParent">添加父引用</el-button>

        <el-form-item label="主机名（hostnames）" class="mt-block">
          <el-select
            v-model="form.hostnames"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="留空 = 全部主机名；回车添加，如 grpc.example.com"
            style="max-width: 520px"
          />
          <FieldHelp tip="按 HTTP/2 :authority（即请求的 Host）匹配。留空表示接受所有主机名。" />
        </el-form-item>

        <el-divider content-position="left">规则（rules）</el-divider>

        <div v-for="(r, ri) in form.rules" :key="ri" class="sub-block rule-block">
          <div class="sub-head">
            <span class="sub-title">规则 #{{ ri + 1 }}</span>
            <el-button v-if="form.rules.length > 1" link type="danger" @click="removeRule(ri)">删除规则</el-button>
          </div>

          <el-form-item label="规则名">
            <el-input v-model="r.name" placeholder="可选（本路由内唯一）" style="max-width: 300px" />
            <FieldHelp tip="规则的名称，仅用于可读性与状态引用。留空即可。" />
          </el-form-item>

          <!-- 匹配 -->
          <div class="sub-sub-title">匹配条件（matches；多条之间是 OR，同一条内各维度 AND）</div>
          <div v-for="(m, mi) in r.matches" :key="mi" class="sub-sub-block">
            <div class="sub-head">
              <span class="sub-title">匹配 #{{ mi + 1 }}</span>
              <el-button v-if="r.matches.length > 1" link type="danger" @click="removeMatch(r, mi)">删除</el-button>
            </div>
            <el-form-item label="服务名（service）">
              <el-input v-model="m.methodService" placeholder="如 helloworld.Greeter；留空 = 任意服务" style="max-width: 360px" />
              <FieldHelp tip="gRPC 服务名，即 proto 里的 package.Service（如 helloworld.Greeter）。留空表示不限服务。" />
            </el-form-item>
            <el-form-item label="方法名（method）">
              <el-input v-model="m.methodMethod" placeholder="如 SayHello；留空 = 该服务任意方法" style="max-width: 300px" />
              <FieldHelp tip="gRPC 方法名（如 SayHello）。留空表示该服务下的任意方法。" />
            </el-form-item>
            <el-form-item label="匹配方式（type）">
              <el-select v-model="m.methodType" clearable placeholder="缺省 Exact（精确相等）" style="width: 240px">
                <el-option v-for="t in METHOD_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
              <FieldHelp tip="Exact = 服务名/方法名完全相等（缺省）；RegularExpression = 按正则匹配（实现可选支持，不支持时该条匹配被忽略并报 ResolvedRefs=False）。" />
            </el-form-item>
            <el-form-item label="请求头（metadata）">
              <div class="list-rows">
                <div v-for="(h, hi) in m.headers" :key="hi" class="list-row">
                  <el-input v-model="h.name" placeholder="头名，如 x-tenant-id" style="flex: 1" />
                  <el-select v-model="h.type" style="width: 170px">
                    <el-option v-for="t in METHOD_TYPES" :key="t" :label="t" :value="t" />
                  </el-select>
                  <el-input v-model="h.value" placeholder="值" style="flex: 1" />
                  <el-button link type="danger" @click="m.headers.splice(hi, 1)">删除</el-button>
                </div>
                <el-button size="small" @click="m.headers.push({ name: '', value: '', type: 'Exact' })">添加请求头匹配</el-button>
              </div>
              <FieldHelp tip="按 gRPC metadata（请求头）匹配。多条之间是 AND。" />
            </el-form-item>
          </div>
          <el-button size="small" @click="addMatch(r)">添加匹配</el-button>

          <!-- 过滤器 -->
          <div class="sub-sub-title">过滤器（filters；同一类型只能出现一次。gRPC 没有重定向/路径改写）</div>
          <div v-for="(f, fi) in r.filters" :key="fi" class="sub-sub-block">
            <div class="sub-head">
              <span class="sub-title">过滤器 #{{ fi + 1 }}</span>
              <el-button link type="danger" @click="removeFilter(r, fi)">删除</el-button>
            </div>
            <el-form-item label="类型">
              <el-select v-model="f.type" style="width: 260px">
                <el-option v-for="t in FILTER_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
              <FieldHelp tip="RequestHeaderModifier / ResponseHeaderModifier：增删改请求/响应 metadata。RequestMirror：把副本另发一份到镜像后端。ExtensionRef：交给实现私有的过滤器 CRD。" />
            </el-form-item>

            <template v-if="f.type === 'RequestHeaderModifier' || f.type === 'ResponseHeaderModifier'">
              <el-form-item label="设置（set）">
                <div class="list-rows">
                  <div v-for="(h, hi) in f.headerSet" :key="hi" class="list-row">
                    <el-input v-model="h.name" placeholder="头名" style="flex: 1" />
                    <el-input v-model="h.value" placeholder="值" style="flex: 1" />
                    <el-button link type="danger" @click="f.headerSet.splice(hi, 1)">删除</el-button>
                  </div>
                  <el-button size="small" @click="f.headerSet.push({ name: '', value: '' })">添加 set</el-button>
                </div>
                <FieldHelp tip="覆盖式写入：metadata 已存在则替换，不存在则新增。" />
              </el-form-item>
              <el-form-item label="追加（add）">
                <div class="list-rows">
                  <div v-for="(h, hi) in f.headerAdd" :key="hi" class="list-row">
                    <el-input v-model="h.name" placeholder="头名" style="flex: 1" />
                    <el-input v-model="h.value" placeholder="值" style="flex: 1" />
                    <el-button link type="danger" @click="f.headerAdd.splice(hi, 1)">删除</el-button>
                  </div>
                  <el-button size="small" @click="f.headerAdd.push({ name: '', value: '' })">添加 add</el-button>
                </div>
                <FieldHelp tip="追加式写入：保留已有同名 metadata，额外再加一个。" />
              </el-form-item>
              <el-form-item label="删除（remove）">
                <el-select v-model="f.headerRemove" multiple filterable allow-create default-first-option placeholder="回车添加要删除的头名" style="max-width: 420px" />
                <FieldHelp tip="按头名移除（不看值）。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'RequestMirror'">
              <el-form-item label="镜像后端 · 名称" required>
                <el-input v-model="f.mirrorName" placeholder="Service 名" style="max-width: 300px" />
                <FieldHelp tip="副本流量另发一份到这个后端；原请求仍按 backendRefs 正常转发。常用来把线上流量复制一份到新版本做验证。" />
              </el-form-item>
              <el-form-item label="镜像后端 · 命名空间">
                <el-input v-model="f.mirrorNamespace" placeholder="留空 = 本命名空间" style="max-width: 300px" />
                <FieldHelp tip="跨命名空间镜像需目标命名空间有 ReferenceGrant。" />
              </el-form-item>
              <el-form-item label="镜像后端 · 端口">
                <el-input-number v-model="f.mirrorPort" :min="1" :max="65535" controls-position="right" style="width: 160px" />
                <FieldHelp tip="目标 Service 的端口号。" />
              </el-form-item>
              <el-form-item label="镜像比例（percent）">
                <el-input-number v-model="f.mirrorPercent" :min="0" :max="100" controls-position="right" placeholder="缺省全部" style="width: 160px" />
                <FieldHelp tip="0–100，只镜像这个百分比的请求。留空表示全部镜像 —— 生产上建议给个小比例。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'ExtensionRef'">
              <el-form-item label="引用 · group" required>
                <el-input v-model="f.extGroup" placeholder="如 gateway.envoyproxy.io" style="max-width: 300px" />
                <FieldHelp tip="被引用过滤器 CRD 的 group（必填）。" />
              </el-form-item>
              <el-form-item label="引用 · kind" required>
                <el-input v-model="f.extKind" placeholder="如 GRPCRouteFilter" style="max-width: 300px" />
                <FieldHelp tip="被引用过滤器 CRD 的 kind（必填）。" />
              </el-form-item>
              <el-form-item label="引用 · 名称" required>
                <el-input v-model="f.extName" placeholder="对象名" style="max-width: 300px" />
                <FieldHelp tip="被引用对象名（必填，需与本 Route 同命名空间）。" />
              </el-form-item>
            </template>
          </div>
          <el-button size="small" @click="addFilter(r)">添加过滤器</el-button>

          <!-- 后端 -->
          <div class="sub-sub-title">后端（backendRefs；weight 非百分比，占比 = weight / 本规则权重和）</div>
          <div v-for="(b, bi) in r.backendRefs" :key="bi" class="list-row backend-row">
            <el-input v-model="b.name" placeholder="Service 名" style="flex: 1" />
            <el-input v-model="b.namespace" placeholder="命名空间（留空=本 ns）" style="width: 190px" />
            <el-input-number v-model="b.port" :min="1" :max="65535" controls-position="right" placeholder="端口" style="width: 140px" />
            <el-input-number v-model="b.weight" :min="0" :max="1000000" controls-position="right" placeholder="权重" style="width: 140px" />
            <el-button v-if="r.backendRefs.length > 1" link type="danger" @click="removeBackend(r, bi)">删除</el-button>
          </div>
          <el-button size="small" @click="addBackend(r)">添加后端</el-button>
          <FieldHelp tip="后端 Service 名 + 端口（必填）+ 权重（相对值不是百分比）。两个后端各 50 与各 1 效果相同；权重 0 表示不转发。" />
        </div>
        <el-button size="small" @click="addRule">添加规则</el-button>

        <el-form-item class="submit-row">
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
          <el-button @click="goBack">取消</el-button>
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
.editor-form { max-width: 980px; }
.unsupported-alert { margin-bottom: 16px; }
.sub-block {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px 14px 2px;
  margin-bottom: 12px;
  background: var(--panel-hover);
}
.rule-block { background: var(--panel); }
.sub-sub-block {
  border: 1px dashed var(--border);
  border-radius: 8px;
  padding: 10px 12px 2px;
  margin-bottom: 12px;
}
.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.parent-hint { margin-left: 8px; font-size: 12px; color: var(--text-3); }
.sub-sub-title {
  font-size: 12px;
  color: var(--text-3);
  margin: 12px 0 8px;
}
.list-rows { display: flex; flex-direction: column; gap: 8px; width: 100%; max-width: 620px; }
.list-row { display: flex; align-items: center; gap: 10px; }
.backend-row { max-width: 780px; margin-bottom: 8px; }
.mt-block { margin-top: 18px; }
.submit-row { margin-top: 18px; }
</style>
