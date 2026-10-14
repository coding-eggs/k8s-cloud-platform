<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import HttpRouteForm from '@/components/route/HttpRouteForm.vue'
import GrpcRouteForm from '@/components/route/GrpcRouteForm.vue'
import TcpRouteForm from '@/components/route/TcpRouteForm.vue'
import TlsRouteForm from '@/components/route/TlsRouteForm.vue'
import UdpRouteForm from '@/components/route/UdpRouteForm.vue'
import { grpcRouteApi, httpRouteApi, tcpRouteApi, tlsRouteApi, udpRouteApi } from '@/api'
import { normalizeParentKind, useParentCandidates } from '@/composables/useParentCandidates'
import { usePermission } from '@/stores/permission'
import { useResourceContext } from '@/stores/context'
import { ROUTE_KINDS, kindMeta, parseRouteKind, type RouteKind } from '@/utils/routeKinds'
import type { CandidateBucket } from '@/composables/useParentCandidates'
import type { K8sGrpcRule, K8sHrRule, K8sL4RouteRule, K8sRouteParentRef } from '@/types'

/**
 * 「路由策略」编辑器外壳（5 种 route 共用一个页面）。
 *
 * <h2>外壳负责什么</h2>
 * 只负责**真正同形**的部分：类型选择器、路由名、父资源块、hostnames、校验编排、回填与提交。
 * rules 的内部结构完全属于子组件（`components/route/*RouteForm.vue`）—— 外壳连"一条规则长什么样"都不知道，
 * 只经三个方法与子组件对话：`setRules(raw)`（回填）、`toDto()`（取要写进 body 的 rules）、`validate()`。
 *
 * <h2>切 kind 怎么清字段</h2>
 * 子组件是 `v-if` 分支，换 kind 即**销毁重建**：HTTP 的 matches/filters、TLS 的规则… 随之消失，
 * 新 kind 从空白表单开始。不要改成 `v-show`，也不要手写"清字段"的 watcher。
 */
const route = useRoute()
const router = useRouter()
const perm = usePermission()
const { state, ready, currentTenant, currentCluster, load, namespacesOfCluster } = useResourceContext()

const editing = ref<string | null>((route.query.name as string) || null)

const kind = ref<RouteKind>(parseRouteKind(route.query.kind) ?? 'httproute')
/** 合法 `?kind=` 或编辑既有对象时锁定类型；非法/缺失时放开（用户自己选） */
const kindLocked = computed(() => !!editing.value || parseRouteKind(route.query.kind) !== null)

const ctx3 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))
const ctx2 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
}))

// ==================== 父资源块（搬迁自 HttpRouteEditorView，逐字保留） ====================

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

/** 段名候选取自**所选中的那个**父资源（不是整个命名空间）：
 *  Gateway=监听器名；Service=端口名（label 带端口/协议，与 ServiceMonitorEditorView 同格式） */
function sectionOptions(p: ParentRow): { value: string; label: string }[] {
  const name = p.name.trim()
  if (!name) return []
  const c = bucketOf(p).list.find((x) => x.name === name)
  if (!c) return []
  return p.parentKind === 'Service'
    ? c.namedPorts.map((np) => ({ value: np.name, label: np.label }))
    : c.listeners.map((n) => ({ value: n, label: n }))
}

function sectionNoDataText(p: ParentRow): string {
  if (!ready.value) return '上下文未就绪'
  if (hasUnmodeledKind(p)) return `「${p.parentKind}」类型的父资源不支持候选，请直接输入`
  const name = p.name.trim()
  if (!name) return '请先选择父资源'
  const c = bucketOf(p).list.find((x) => x.name === name)
  if (!c) return '该名称不在候选里（手输的无法列出它的段），可自行输入'
  if (p.parentKind !== 'Service') return '该 Gateway 没有监听器'
  return c.ports.length ? '该服务的端口没有命名，留空即作用于全部端口' : '该服务没有端口'
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

// ==================== 表单模型（只有同形部分） ====================

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

const form = reactive({
  name: '',
  parentRefs: [{ parentKind: '', name: '', namespace: '', sectionName: '' }] as ParentRow[],
  hostnames: [] as string[],
})

function addParent(): void { form.parentRefs.push({ parentKind: '', name: '', namespace: '', sectionName: '' }) }
function removeParent(i: number): void { form.parentRefs.splice(i, 1) }

/** 当前挂着的那个子组件实例：`v-if` 保证同一时刻只有一个，故各分支共用一个 ref 名是安全的 */
const formEl = ref<{ validate: () => string | null; setRules?: (raw: unknown) => void; toDto?: () => unknown } | null>(null)

// ---------- 回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

function fromParent(p: K8sRouteParentRef): ParentRow {
  return {
    parentKind: normalizeParentKind(p.kind),
    name: p.name ?? '',
    namespace: p.namespace ?? '',
    sectionName: p.sectionName ?? '',
    group: p.group,
    port: p.port,
  }
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    // rules 由子组件持有：这里只取出来，等表单渲染出来后再交给它（见函数尾部）
    let rules: unknown = []
    if (kind.value === 'httproute') {
      const d = await httpRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      if (!form.parentRefs.length) form.parentRefs = [{ parentKind: '', name: '', namespace: '', sectionName: '' }]
      form.hostnames = [...(d.hostnames ?? [])]
      rules = d.rules ?? []
    } else if (kind.value === 'grpcroute') {
      const d = await grpcRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      if (!form.parentRefs.length) form.parentRefs = [{ parentKind: '', name: '', namespace: '', sectionName: '' }]
      form.hostnames = [...(d.hostnames ?? [])]
      rules = d.rules ?? []
    } else if (kind.value === 'tcproute') {
      const d = await tcpRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      if (!form.parentRefs.length) form.parentRefs = [{ parentKind: '', name: '', namespace: '', sectionName: '' }]
      form.hostnames = []
      rules = d.rules ?? []
    } else if (kind.value === 'tlsroute') {
      const d = await tlsRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      if (!form.parentRefs.length) form.parentRefs = [{ parentKind: '', name: '', namespace: '', sectionName: '' }]
      form.hostnames = [...(d.hostnames ?? [])]
      rules = d.rules ?? []
    } else {
      const d = await udpRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      if (!form.parentRefs.length) form.parentRefs = [{ parentKind: '', name: '', namespace: '', sectionName: '' }]
      form.hostnames = []
      rules = d.rules ?? []
    }
    detailState.value = 'loaded'
    // ⚠️ 规则表单在 `<el-form v-if="formVisible">` 里，而 formVisible 依赖 detailState==='loaded'：
    // 这一帧之前子组件还没挂上，`formEl` 是 null —— 直接调 setRules 会被静默跳过（规则回填整条丢失）。
    // 必须等表单渲染出来再回填。
    await nextTick()
    formEl.value?.setRules?.(rules)
  } catch {
    detailState.value = 'error'
  }
}

watch([() => ready.value, () => state.namespace, () => kind.value], ([r]) => {
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
/** hostname 的宽松校验：DNS 名，允许前缀通配（* 只能是最左整段） */
const HOSTNAME_RE = /^(\*\.)?[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/

/** 外壳侧的校验；规则部分交给子组件（`formEl.validate()`） */
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
  if (kindMeta(kind.value).usesHostnames) {
    for (const h of form.hostnames) {
      if (h.trim() && !HOSTNAME_RE.test(h.trim())) return `主机名格式不合法：「${h}」`
    }
  }
  return formEl.value?.validate() ?? null
}

const saving = ref(false)
/** 保存能力：新建看 create 码、编辑看 update 码（后端仍是权威，这里只是不发注定被拒的请求） */
const canSave = computed(() => {
  const m = kindMeta(kind.value)
  return perm.has(editing.value ? m.updateCode : m.createCode)
})

async function submit(): Promise<void> {
  if (saving.value || !ready.value || !canSave.value) return
  const err = validate()
  if (err) { ElMessage.warning(err); return }

  const name = (editing.value ?? form.name).trim()
  // group/kind/port 原样带回（UI 不编辑）；kind 由「父资源类型」选择器决定（'' → null = 缺省 Gateway）
  const parentRefs = form.parentRefs
    .filter((p) => p.name.trim())
    .map((p) => ({
      name: p.name.trim(),
      namespace: p.namespace.trim() || null,
      sectionName: p.sectionName.trim() || null,
      group: p.group ?? null,
      kind: p.parentKind.trim() || null,
      port: p.port ?? null,
    }))
  const hostnames = kindMeta(kind.value).usesHostnames
    ? form.hostnames.map((h) => h.trim()).filter(Boolean).length
      ? form.hostnames.map((h) => h.trim()).filter(Boolean)
      : null
    : null

  saving.value = true
  try {
    if (kind.value === 'httproute') {
      const body = { name, namespace: state.namespace!, parentRefs, hostnames, rules: formEl.value?.toDto?.() as K8sHrRule[] }
      if (editing.value) await httpRouteApi.update(editing.value, ctx2.value, body)
      else await httpRouteApi.create(ctx2.value, body)
    } else if (kind.value === 'grpcroute') {
      const body = { name, namespace: state.namespace!, parentRefs, hostnames, rules: formEl.value?.toDto?.() as K8sGrpcRule[] }
      if (editing.value) await grpcRouteApi.update(editing.value, ctx2.value, body)
      else await grpcRouteApi.create(ctx2.value, body)
    } else if (kind.value === 'tcproute') {
      const body = { name, namespace: state.namespace!, parentRefs, rules: formEl.value?.toDto?.() as K8sL4RouteRule[] }
      if (editing.value) await tcpRouteApi.update(editing.value, ctx2.value, body)
      else await tcpRouteApi.create(ctx2.value, body)
    } else if (kind.value === 'tlsroute') {
      const body = { name, namespace: state.namespace!, parentRefs, hostnames, rules: formEl.value?.toDto?.() as K8sL4RouteRule[] }
      if (editing.value) await tlsRouteApi.update(editing.value, ctx2.value, body)
      else await tlsRouteApi.create(ctx2.value, body)
    } else {
      const body = { name, namespace: state.namespace!, parentRefs, rules: formEl.value?.toDto?.() as K8sL4RouteRule[] }
      if (editing.value) await udpRouteApi.update(editing.value, ctx2.value, body)
      else await udpRouteApi.create(ctx2.value, body)
    }
    ElMessage.success(editing.value ? '保存成功' : '创建成功')
    router.push({ name: 'routes' })
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push({ name: 'routes' }) }

const pageTitle = computed(() => `${editing.value ? '编辑' : '创建'} ${kindMeta(kind.value).label}`)
const formVisible = computed(() => ready.value && (!editing.value || detailState.value === 'loaded'))
const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})

onMounted(() => { void load() })
</script>

<template>
  <div class="editor-page">
    <PageHeader title="路由策略" :description="contextDesc">
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState
      v-if="detailState === 'error'"
      title="加载失败"
      description="该路由可能已被删除，或所选命名空间下不存在。"
    >
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <el-form v-if="formVisible" label-position="left" label-width="200px" class="editor-form">
        <el-form-item label="类型" required>
          <el-select v-model="kind" :disabled="kindLocked" style="width: 220px">
            <el-option v-for="k in ROUTE_KINDS" :key="k.key" :label="k.label" :value="k.key" />
          </el-select>
          <FieldHelp tip="路由类型决定下面能配什么：HTTPRoute / GRPCRoute 有方法/路径/头的匹配与过滤器；TLSRoute 按 SNI 匹配；TCPRoute / UDPRoute 只做端口转发。创建后不可改。" />
        </el-form-item>

        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 api-route（RFC-1123）" style="max-width: 420px" />
          <FieldHelp :tip="`${kindMeta(kind).label} 名（本命名空间内唯一）。创建后不可改。`" />
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
            <FieldHelp
              v-else-if="!hasUnmodeledKind(p)"
              tip="要挂载到的 Gateway（缺省 kind=Gateway、group=gateway.networking.k8s.io）。跨命名空间挂载需在目标 Gateway 的 allowedRoutes 放行，且目标命名空间有 ReferenceGrant。"
            />
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
              <el-option v-for="s in sectionOptions(p)" :key="s.value" :label="s.label" :value="s.value" />
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

        <el-form-item v-if="kindMeta(kind).usesHostnames" label="主机名（hostnames）" class="mt-block">
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

        <HttpRouteForm v-if="kind === 'httproute'" ref="formEl" />
        <GrpcRouteForm v-else-if="kind === 'grpcroute'" ref="formEl" />
        <TcpRouteForm v-else-if="kind === 'tcproute'" ref="formEl" />
        <TlsRouteForm v-else-if="kind === 'tlsroute'" ref="formEl" />
        <UdpRouteForm v-else-if="kind === 'udproute'" ref="formEl" />

        <el-form-item class="submit-row">
          <el-button type="primary" :loading="saving" :disabled="!canSave" @click="submit">
            {{ editing ? '保存' : '创建' }}
          </el-button>
          <el-button @click="goBack">取消</el-button>
          <span v-if="!canSave" class="permission-hint">当前账号在该类型上没有{{ editing ? '修改' : '创建' }}权限</span>
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
.sub-block {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px 14px 2px;
  margin-bottom: 12px;
  background: var(--panel-hover);
}
.sub-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.parent-hint { margin-left: 8px; font-size: 12px; color: var(--text-3); }
.mt-block { margin-top: 18px; }
.submit-row { margin-top: 18px; }
.permission-hint { margin-left: 10px; font-size: 12px; color: var(--text-3); }
</style>
