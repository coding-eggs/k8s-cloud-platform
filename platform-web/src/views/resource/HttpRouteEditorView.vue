<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { gatewayApi, httpRouteApi } from '@/api'
import { useResourceContext } from '@/stores/context'
import type { K8sGateway, K8sHrFilter, K8sHrMatch, K8sHrRule, K8sHttpRoute, K8sRouteBackendRef, K8sRouteParentRef } from '@/types'

/**
 * HTTPRoute 独立编辑页（命名空间级、租户域）。7 类对象里字段最多、嵌套最深的一个。
 *
 * <h2>未建模内容怎么处理（三层）</h2>
 * <ol>
 *   <li><b>后端 fetch-overlay 自动保住</b>：rule 的 timeouts/retry/sessionPersistence、
 *       CORS/ExternalAuth 两种 filter、match 内的未知兄弟键 —— rules 是 atomic list，
 *       不 overlay 会被 SSA 整体抹掉，这一层在 k8s-core 的 HttpRouteConverter 里做。</li>
 *   <li><b>前端"透传保留"</b>（UI 不编辑、但必须原样回传，否则等于用户删了）：
 *       parentRefs[].group/kind/port、filter 体里未在表单出现的兄弟键。
 *       同 ServiceMonitorEditorView 对 bearerTokenFile 的口径。</li>
 *   <li><b>只读可见</b>：以上全部在列表页的 YAML tab 里能看到全量。</li>
 * </ol>
 * 当加载到的存量路由含未建模 filter 时，页面顶部给出提示（见 {@link unsupportedFilterTypes}），
 * 避免用户以为"编辑一次 CORS 就没了"。
 */
const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

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

const METHODS = ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'CONNECT', 'OPTIONS', 'TRACE', 'PATCH'] as const
const PATH_TYPES = ['PathPrefix', 'Exact', 'RegularExpression'] as const
const MATCH_TYPES = ['Exact', 'RegularExpression'] as const
const PATH_MOD_TYPES = ['ReplaceFullPath', 'ReplacePrefixMatch'] as const
/** 本版建模的 filter 类型（与后端 HttpRouteConverter.MODELED_FILTER_TYPES 对齐） */
const FILTER_TYPES = [
  'RequestHeaderModifier',
  'ResponseHeaderModifier',
  'RequestRedirect',
  'URLRewrite',
  'RequestMirror',
  'ExtensionRef',
] as const

/** 本命名空间的 Gateway 候选（parentRefs 下拉） */
const gatewayOptions = ref<K8sGateway[]>([])
const gatewayLoading = ref(false)

async function loadGateways(): Promise<void> {
  if (!ready.value) return
  gatewayLoading.value = true
  try {
    gatewayOptions.value = await gatewayApi.list({ ...ctx3.value })
  } catch {
    gatewayOptions.value = [] // 候选拉取失败 → 退化为手填
  } finally {
    gatewayLoading.value = false
  }
}

// ---------- 表单模型 ----------
interface ParentRow {
  name: string
  namespace: string
  sectionName: string
  /** UI 不编辑，仅回填透传 */
  group?: string | null
  kind?: string | null
  port?: number | null
}
interface BackendRow {
  name: string
  namespace: string
  port: number | null
  weight: number | null
  /** UI 不编辑，仅回填透传 */
  group?: string | null
  kind?: string | null
}
interface KvRow { name: string; value: string; type: string }
interface MatchRow {
  pathType: string
  pathValue: string
  method: string
  headers: KvRow[]
  queryParams: KvRow[]
}
interface FilterRow {
  type: string
  // request/response header modifier 共用一组输入
  headerSet: { name: string; value: string }[]
  headerAdd: { name: string; value: string }[]
  headerRemove: string[]
  // RequestRedirect
  redirectScheme: string
  redirectHostname: string
  redirectPathType: string
  redirectPathValue: string
  redirectPort: number | null
  redirectStatusCode: number | null
  // URLRewrite
  rewriteHostname: string
  rewritePathType: string
  rewritePathValue: string
  // RequestMirror
  mirrorName: string
  mirrorNamespace: string
  mirrorPort: number | null
  mirrorPercent: number | null
  // ExtensionRef
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
function newMatch(): MatchRow { return { pathType: 'PathPrefix', pathValue: '/', method: '', headers: [], queryParams: [] } }
function newFilter(): FilterRow {
  return {
    type: 'RequestHeaderModifier',
    headerSet: [], headerAdd: [], headerRemove: [],
    redirectScheme: '', redirectHostname: '', redirectPathType: '', redirectPathValue: '', redirectPort: null, redirectStatusCode: null,
    rewriteHostname: '', rewritePathType: '', rewritePathValue: '',
    mirrorName: '', mirrorNamespace: '', mirrorPort: null, mirrorPercent: null,
    extGroup: '', extKind: '', extName: '',
  }
}
function newRule(): RuleRow { return { name: '', matches: [newMatch()], filters: [], backendRefs: [newBackend()] } }

const form = reactive({
  name: '',
  parentRefs: [{ name: '', namespace: '', sectionName: '' }] as ParentRow[],
  hostnames: [] as string[],
  rules: [newRule()] as RuleRow[],
})

/** 加载到的存量路由里本版不支持的 filter 类型（保存不影响它们，仅提示） */
const unsupportedFilterTypes = ref<string[]>([])

function addParent(): void { form.parentRefs.push({ name: '', namespace: '', sectionName: '' }) }
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
  return { name: p.name ?? '', namespace: p.namespace ?? '', sectionName: p.sectionName ?? '', group: p.group, kind: p.kind, port: p.port }
}
function fromBackend(b: K8sRouteBackendRef): BackendRow {
  return { name: b.name ?? '', namespace: b.namespace ?? '', port: b.port ?? null, weight: b.weight ?? null, group: b.group, kind: b.kind }
}
function fromMatch(m: K8sHrMatch): MatchRow {
  return {
    pathType: m.path?.type ?? 'PathPrefix',
    pathValue: m.path?.value ?? '/',
    method: m.method ?? '',
    headers: (m.headers ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '', type: h.type ?? 'Exact' })),
    queryParams: (m.queryParams ?? []).map((q) => ({ name: q.name ?? '', value: q.value ?? '', type: q.type ?? 'Exact' })),
  }
}
function fromFilter(f: K8sHrFilter): FilterRow {
  const row = newFilter()
  row.type = f.type ?? 'RequestHeaderModifier'
  // request/response header modifier 结构相同，按 type 取对应体
  const hf = row.type === 'ResponseHeaderModifier' ? f.responseHeaderModifier : f.requestHeaderModifier
  row.headerSet = (hf?.set ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '' }))
  row.headerAdd = (hf?.add ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '' }))
  row.headerRemove = [...(hf?.remove ?? [])]
  if (f.requestRedirect) {
    row.redirectScheme = f.requestRedirect.scheme ?? ''
    row.redirectHostname = f.requestRedirect.hostname ?? ''
    row.redirectPathType = f.requestRedirect.path?.type ?? ''
    row.redirectPathValue = f.requestRedirect.path?.replaceFullPath ?? f.requestRedirect.path?.replacePrefixMatch ?? ''
    row.redirectPort = f.requestRedirect.port ?? null
    row.redirectStatusCode = f.requestRedirect.statusCode ?? null
  }
  if (f.urlRewrite) {
    row.rewriteHostname = f.urlRewrite.hostname ?? ''
    row.rewritePathType = f.urlRewrite.path?.type ?? ''
    row.rewritePathValue = f.urlRewrite.path?.replaceFullPath ?? f.urlRewrite.path?.replacePrefixMatch ?? ''
  }
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
function fromRule(r: K8sHrRule): RuleRow {
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
    const d = await httpRouteApi.get(editing.value, ctx3.value)
    form.name = d.name
    form.parentRefs = (d.parentRefs ?? []).map(fromParent)
    if (!form.parentRefs.length) form.parentRefs = [{ name: '', namespace: '', sectionName: '' }]
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
  void loadGateways()
  if (editing.value) void loadDetail()
}, { immediate: true })

// ---------- 提交 ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
/** hostname 的宽松校验：DNS 名，允许前缀通配（* 只能是最左整段） */
const HOSTNAME_RE = /^(\*\.)?[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/

function cleanKv(rows: { name: string; value: string }[]): { name: string; value: string }[] {
  return rows.filter((r) => r.name.trim()).map((r) => ({ name: r.name.trim(), value: r.value }))
}

function buildFilter(f: FilterRow): K8sHrFilter | null {
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
    case 'RequestRedirect': {
      const path = f.redirectPathType
        ? {
            type: f.redirectPathType,
            replaceFullPath: f.redirectPathType === 'ReplaceFullPath' ? f.redirectPathValue : null,
            replacePrefixMatch: f.redirectPathType === 'ReplacePrefixMatch' ? f.redirectPathValue : null,
          }
        : null
      return {
        type: f.type,
        requestRedirect: {
          scheme: f.redirectScheme.trim() || null,
          hostname: f.redirectHostname.trim() || null,
          path,
          port: f.redirectPort,
          statusCode: f.redirectStatusCode,
        },
      }
    }
    case 'URLRewrite': {
      const path = f.rewritePathType
        ? {
            type: f.rewritePathType,
            replaceFullPath: f.rewritePathType === 'ReplaceFullPath' ? f.rewritePathValue : null,
            replacePrefixMatch: f.rewritePathType === 'ReplacePrefixMatch' ? f.rewritePathValue : null,
          }
        : null
      return { type: f.type, urlRewrite: { hostname: f.rewriteHostname.trim() || null, path } }
    }
    case 'RequestMirror': {
      if (!f.mirrorName.trim()) return null
      return {
        type: f.type,
        requestMirror: {
          backendRef: {
            name: f.mirrorName.trim(),
            namespace: f.mirrorNamespace.trim() || null,
            port: f.mirrorPort,
          },
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
  if (!form.parentRefs.some((p) => p.name.trim())) return '至少需要一个父 Gateway（parentRefs）'
  for (const h of form.hostnames) {
    if (h.trim() && !HOSTNAME_RE.test(h.trim())) return `主机名格式不合法：「${h}」`
  }
  if (!form.rules.length) return '至少需要一条规则'
  for (const [ri, r] of form.rules.entries()) {
    const no = `规则 #${ri + 1}`
    const backends = r.backendRefs.filter((b) => b.name.trim())
    if (!backends.length) return `${no}：至少需要一个后端（backendRefs）`
    for (const b of backends) {
      if (b.port == null || b.port < 1 || b.port > 65535) return `${no}：后端「${b.name}」的端口须为 1–65535`
      if (b.weight != null && (b.weight < 0 || b.weight > 1000000)) return `${no}：后端「${b.name}」的权重须为 0–1000000`
    }
    const types = r.filters.map((f) => f.type)
    if (new Set(types).size !== types.length) return `${no}：同一过滤器类型只能出现一次`
    if (types.includes('RequestRedirect') && types.includes('URLRewrite')) {
      return `${no}：RequestRedirect 与 URLRewrite 互斥，只能选一个`
    }
  }
  return null
}

const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !ready.value) return
  const err = validate()
  if (err) { ElMessage.warning(err); return }

  const body: K8sHttpRoute = {
    name: (editing.value ?? form.name).trim(),
    // tenantId / clusterId 由 makeResourceApi 的 ctx 参数合并进请求体；namespace 必须来自 body（Create 的边界判据）
    namespace: state.namespace!,
    // group/kind/port 原样带回（UI 不编辑）
    parentRefs: form.parentRefs
      .filter((p) => p.name.trim())
      .map((p) => ({
        name: p.name.trim(),
        namespace: p.namespace.trim() || null,
        sectionName: p.sectionName.trim() || null,
        group: p.group ?? null,
        kind: p.kind ?? null,
        port: p.port ?? null,
      })),
    hostnames: form.hostnames.map((h) => h.trim()).filter(Boolean).length
      ? form.hostnames.map((h) => h.trim()).filter(Boolean) : null,
    rules: form.rules.map((r) => ({
      name: r.name.trim() || null,
      matches: r.matches.map((m) => ({
        path: { type: m.pathType, value: m.pathValue.trim() || null },
        method: m.method || null,
        headers: m.headers.filter((h) => h.name.trim()).length
          ? m.headers.filter((h) => h.name.trim()).map((h) => ({ type: h.type, name: h.name.trim(), value: h.value }))
          : null,
        queryParams: m.queryParams.filter((q) => q.name.trim()).length
          ? m.queryParams.filter((q) => q.name.trim()).map((q) => ({ type: q.type, name: q.name.trim(), value: q.value }))
          : null,
      })),
      filters: r.filters.map(buildFilter).filter((f): f is K8sHrFilter => f !== null).length
        ? r.filters.map(buildFilter).filter((f): f is K8sHrFilter => f !== null)
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
      await httpRouteApi.update(editing.value, ctx2.value, body)
      ElMessage.success('保存成功')
    } else {
      await httpRouteApi.create(ctx2.value, body)
      ElMessage.success('创建成功')
    }
    router.push({ name: 'httproutes' })
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push({ name: 'httproutes' }) }

const pageTitle = computed(() => (editing.value ? '编辑 HTTPRoute' : '创建 HTTPRoute'))
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
      description="该 HTTPRoute 可能已被删除，或所选命名空间下不存在。"
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
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 api-route（RFC-1123）" style="max-width: 420px" />
          <FieldHelp tip="HTTPRoute 名（本命名空间内唯一）。创建后不可改。" />
        </el-form-item>

        <el-divider content-position="left">父 Gateway（parentRefs）</el-divider>

        <div v-for="(p, i) in form.parentRefs" :key="i" class="sub-block">
          <div class="sub-head">
            <span class="sub-title">父引用 #{{ i + 1 }}</span>
            <el-button v-if="form.parentRefs.length > 1" link type="danger" @click="removeParent(i)">删除</el-button>
          </div>
          <el-form-item label="Gateway" required>
            <el-select
              v-model="p.name"
              filterable
              allow-create
              default-first-option
              :loading="gatewayLoading"
              :no-data-text="ready ? '本命名空间暂无 Gateway（可直接输入名称）' : '上下文未就绪'"
              placeholder="选择或输入本命名空间的 Gateway 名"
              style="max-width: 360px"
            >
              <el-option v-for="g in gatewayOptions" :key="g.name" :label="g.name" :value="g.name" />
            </el-select>
            <FieldHelp tip="要挂载到的 Gateway（缺省 kind=Gateway、group=gateway.networking.k8s.io）。跨命名空间挂载需在目标 Gateway 的 allowedRoutes 放行，且目标命名空间有 ReferenceGrant。" />
          </el-form-item>
          <el-form-item label="命名空间">
            <el-input v-model="p.namespace" placeholder="留空 = 本命名空间" style="max-width: 300px" />
            <FieldHelp tip="父 Gateway 所在命名空间。留空表示同命名空间。" />
          </el-form-item>
          <el-form-item label="监听器（sectionName）">
            <el-input v-model="p.sectionName" placeholder="留空 = 该 Gateway 上全部匹配的监听器" style="max-width: 300px" />
            <FieldHelp tip="只挂到指定的那一个监听器（填监听器名）。留空表示挂到所有匹配的监听器。" />
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
            placeholder="留空 = 全部主机名；回车添加，如 api.example.com"
            style="max-width: 520px"
          />
          <FieldHelp tip="只接受 Host 匹配这些名字的请求（可含前缀通配 *）。留空表示该路由接受所有主机名 —— 注意这会与同监听器上的其它路由争抢流量。" />
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
            <el-form-item label="路径类型">
              <el-select v-model="m.pathType" style="width: 200px">
                <el-option v-for="t in PATH_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
              <FieldHelp tip="PathPrefix = 前缀匹配（/api 命中 /api、/api/v1）；Exact = 完全相等；RegularExpression = 正则（实现可选支持，不支持时该条匹配会被忽略并报 ResolvedRefs=False）。" />
            </el-form-item>
            <el-form-item label="路径值">
              <el-input v-model="m.pathValue" placeholder="如 /api" style="max-width: 300px" />
              <FieldHelp tip="配合路径类型使用。留空表示不按路径匹配。" />
            </el-form-item>
            <el-form-item label="方法">
              <el-select v-model="m.method" clearable placeholder="留空 = 任意方法" style="width: 200px">
                <el-option v-for="mm in METHODS" :key="mm" :label="mm" :value="mm" />
              </el-select>
              <FieldHelp tip="HTTP 方法匹配。留空表示不限方法。" />
            </el-form-item>
            <el-form-item label="请求头">
              <div class="list-rows">
                <div v-for="(h, hi) in m.headers" :key="hi" class="list-row">
                  <el-input v-model="h.name" placeholder="头名，如 X-Api-Version" style="flex: 1" />
                  <el-select v-model="h.type" style="width: 170px">
                    <el-option v-for="t in MATCH_TYPES" :key="t" :label="t" :value="t" />
                  </el-select>
                  <el-input v-model="h.value" placeholder="值" style="flex: 1" />
                  <el-button link type="danger" @click="m.headers.splice(hi, 1)">删除</el-button>
                </div>
                <el-button size="small" @click="m.headers.push({ name: '', value: '', type: 'Exact' })">添加请求头匹配</el-button>
              </div>
              <FieldHelp tip="按请求头匹配（头名大小写不敏感）。多条之间是 AND。Exact = 值完全相等；RegularExpression = 值按正则匹配。" />
            </el-form-item>
            <el-form-item label="查询参数">
              <div class="list-rows">
                <div v-for="(q, qi) in m.queryParams" :key="qi" class="list-row">
                  <el-input v-model="q.name" placeholder="参数名" style="flex: 1" />
                  <el-select v-model="q.type" style="width: 170px">
                    <el-option v-for="t in MATCH_TYPES" :key="t" :label="t" :value="t" />
                  </el-select>
                  <el-input v-model="q.value" placeholder="值" style="flex: 1" />
                  <el-button link type="danger" @click="m.queryParams.splice(qi, 1)">删除</el-button>
                </div>
                <el-button size="small" @click="m.queryParams.push({ name: '', value: '', type: 'Exact' })">添加查询参数匹配</el-button>
              </div>
              <FieldHelp tip="按 URL 查询参数匹配（?k=v）。多条之间是 AND。" />
            </el-form-item>
          </div>
          <el-button size="small" @click="addMatch(r)">添加匹配</el-button>

          <!-- 过滤器 -->
          <div class="sub-sub-title">过滤器（filters；同一类型只能出现一次，RequestRedirect 与 URLRewrite 互斥）</div>
          <div v-for="(f, fi) in r.filters" :key="fi" class="sub-sub-block">
            <div class="sub-head">
              <span class="sub-title">过滤器 #{{ fi + 1 }}</span>
              <el-button link type="danger" @click="removeFilter(r, fi)">删除</el-button>
            </div>
            <el-form-item label="类型">
              <el-select v-model="f.type" style="width: 260px">
                <el-option v-for="t in FILTER_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
              <FieldHelp tip="RequestHeaderModifier / ResponseHeaderModifier：增删改请求/响应头。RequestRedirect：整请求重定向。URLRewrite：改写 Host/路径后再转发。RequestMirror：把副本另发一份到镜像后端。ExtensionRef：交给实现私有的过滤器 CRD。" />
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
                <FieldHelp tip="覆盖式写入：头已存在则替换，不存在则新增。" />
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
                <FieldHelp tip="追加式写入：保留已有同名头，额外再加一个。" />
              </el-form-item>
              <el-form-item label="删除（remove）">
                <el-select v-model="f.headerRemove" multiple filterable allow-create default-first-option placeholder="回车添加要删除的头名" style="max-width: 420px" />
                <FieldHelp tip="按头名移除（不看值）。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'RequestRedirect'">
              <el-form-item label="协议（scheme）">
                <el-select v-model="f.redirectScheme" clearable placeholder="留空 = 不变" style="width: 200px">
                  <el-option label="http" value="http" />
                  <el-option label="https" value="https" />
                </el-select>
                <FieldHelp tip="重定向目标协议。留空表示保持原协议。常见用法：http → https 跳转。" />
              </el-form-item>
              <el-form-item label="主机名">
                <el-input v-model="f.redirectHostname" placeholder="留空 = 不变" style="max-width: 300px" />
                <FieldHelp tip="重定向目标主机名（需精确主机名，不能用通配）。" />
              </el-form-item>
              <el-form-item label="路径重写">
                <el-select v-model="f.redirectPathType" clearable placeholder="留空 = 不变" style="width: 200px">
                  <el-option v-for="t in PATH_MOD_TYPES" :key="t" :label="t" :value="t" />
                </el-select>
                <el-input v-if="f.redirectPathType" v-model="f.redirectPathValue" placeholder="替换值，如 /new" style="max-width: 240px; margin-left: 8px" />
                <FieldHelp tip="ReplaceFullPath = 整段路径替换；ReplacePrefixMatch = 只替换前缀匹配到的那一段（常用于 /old → /new 保留后缀）。" />
              </el-form-item>
              <el-form-item label="端口">
                <el-input-number v-model="f.redirectPort" :min="1" :max="65535" controls-position="right" placeholder="不变" style="width: 160px" />
                <FieldHelp tip="重定向目标端口。留空表示保持原端口。" />
              </el-form-item>
              <el-form-item label="状态码">
                <el-select v-model="f.redirectStatusCode" clearable placeholder="缺省 302" style="width: 160px">
                  <el-option :value="301" label="301 永久" />
                  <el-option :value="302" label="302 临时" />
                </el-select>
                <FieldHelp tip="只能 301 或 302，缺省 302（临时重定向）。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'URLRewrite'">
              <el-form-item label="主机名">
                <el-input v-model="f.rewriteHostname" placeholder="留空 = 不变" style="max-width: 300px" />
                <FieldHelp tip="改写转发给后端时的 Host 头（不是重定向）。与路径重写至少给一个。" />
              </el-form-item>
              <el-form-item label="路径重写">
                <el-select v-model="f.rewritePathType" clearable placeholder="留空 = 不变" style="width: 200px">
                  <el-option v-for="t in PATH_MOD_TYPES" :key="t" :label="t" :value="t" />
                </el-select>
                <el-input v-if="f.rewritePathType" v-model="f.rewritePathValue" placeholder="替换值，如 /" style="max-width: 240px; margin-left: 8px" />
                <FieldHelp tip="转发前改写路径。ReplacePrefixMatch 适合把 /api 前缀剥掉再转给后端。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'RequestMirror'">
              <el-form-item label="镜像后端 · 名称" required>
                <el-input v-model="f.mirrorName" placeholder="Service 名" style="max-width: 300px" />
                <FieldHelp tip="副本流量另发一份到这个后端；原请求仍按 backendRefs 正常转发。" />
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
                <FieldHelp tip="0–100，只镜像这个百分比的请求。留空表示全部镜像 —— 生产上建议给个小比例，避免镜像后端被打满。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'ExtensionRef'">
              <el-form-item label="引用 · group" required>
                <el-input v-model="f.extGroup" placeholder="如 gateway.envoyproxy.io" style="max-width: 300px" />
                <FieldHelp tip="被引用过滤器 CRD 的 group（必填）。" />
              </el-form-item>
              <el-form-item label="引用 · kind" required>
                <el-input v-model="f.extKind" placeholder="如 HTTPRouteFilter" style="max-width: 300px" />
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
          <FieldHelp tip="后端 Service 名 + 端口（必填）+ 权重。权重是相对值不是百分比：两个后端各 50 与各 1 的效果相同；某个后端权重 0 表示不向它转发（常用于灰度占位）。默认 kind=Service。" />
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
