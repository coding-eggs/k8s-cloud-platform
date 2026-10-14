<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { tlsRouteApi } from '@/api'
import { normalizeParentKind, useParentCandidates } from '@/composables/useParentCandidates'
import { useResourceContext } from '@/stores/context'
import type { K8sL4RouteRule, K8sRouteBackendRef, K8sRouteParentRef, K8sTlsRoute } from '@/types'
import type { CandidateBucket } from '@/composables/useParentCandidates'

/**
 * TLSRoute 独立编辑页（命名空间级、租户域）。
 * <p>比 TCP/UDP 多两件事：
 * <ol>
 *   <li><b>SNI 主机名</b>（{@code spec.hostnames}）—— 按 TLS ClientHello 里的 SNI 选路由。
 *       注意它<b>不在 rules 里</b>：旧实验 API 的 {@code rules[].matches[].sniHostname} 在 Gateway API
 *       所有 v1.x 版本里都已不存在。</li>
 *   <li><b>允许多条规则</b>（CRD maxItems=16，TCP/UDP 是 1）。这一点必须支持：若本页只发一条规则，
 *       保存会把线上其余规则一起删掉 —— 单规则表单对 TLS 是破坏性的。</li>
 * </ol>
 * <p>未建模内容（{@code parentRefs[].group/kind/port}、spec 级未建模式键）由"透传保留 + 后端
 * fetch-overlay"处理；全量在列表页的 YAML tab 可见。
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

/** CRD 的 rules maxItems */
const MAX_RULES = 16

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
interface RuleRow {
  name: string
  backendRefs: BackendRow[]
}

function newParent(): ParentRow { return { parentKind: '', name: '', namespace: '', sectionName: '' } }
function newBackend(): BackendRow { return { name: '', namespace: '', port: null, weight: null } }
function newRule(): RuleRow { return { name: '', backendRefs: [newBackend()] } }

const form = reactive({
  name: '',
  hostnames: [] as string[],
  parentRefs: [newParent()] as ParentRow[],
  rules: [newRule()] as RuleRow[],
})

function addParent(): void { form.parentRefs.push(newParent()) }
function removeParent(i: number): void { form.parentRefs.splice(i, 1) }
function addRule(): void {
  if (form.rules.length >= MAX_RULES) { ElMessage.warning(`最多 ${MAX_RULES} 条规则`); return }
  form.rules.push(newRule())
}
function removeRule(i: number): void { form.rules.splice(i, 1) }
function addBackend(r: RuleRow): void { r.backendRefs.push(newBackend()) }
function removeBackend(r: RuleRow, i: number): void { r.backendRefs.splice(i, 1) }

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
function fromBackend(b: K8sRouteBackendRef): BackendRow {
  return { name: b.name ?? '', namespace: b.namespace ?? '', port: b.port ?? null, weight: b.weight ?? null, group: b.group, kind: b.kind }
}
function fromRule(r: K8sL4RouteRule): RuleRow {
  return { name: r.name ?? '', backendRefs: (r.backendRefs ?? []).map(fromBackend) }
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await tlsRouteApi.get(editing.value, ctx3.value)
    form.name = d.name
    form.hostnames = [...(d.hostnames ?? [])]
    form.parentRefs = (d.parentRefs ?? []).map(fromParent)
    if (!form.parentRefs.length) form.parentRefs = [newParent()]
    form.rules = (d.rules ?? []).map(fromRule)
    if (!form.rules.length) form.rules = [newRule()]
    form.rules.forEach((r) => { if (!r.backendRefs.length) r.backendRefs = [newBackend()] })
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
/** SNI 名：DNS 名，允许前缀通配；不允许 IP（RFC 6066） */
const SNI_RE = /^(\*\.)?[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/

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
    if (h.trim() && !SNI_RE.test(h.trim())) return `SNI 名格式不合法：「${h}」`
  }
  if (!form.rules.length) return '至少需要一条规则'
  for (const [ri, r] of form.rules.entries()) {
    const no = `规则 #${ri + 1}`
    const backends = r.backendRefs.filter((b) => b.name.trim())
    if (!backends.length) return `${no}：至少需要一个后端`
    for (const b of backends) {
      if (b.port == null || b.port < 1 || b.port > 65535) return `${no}：后端「${b.name}」的端口须为 1–65535`
      if (b.weight != null && (b.weight < 0 || b.weight > 1000000)) return `${no}：后端「${b.name}」的权重须为 0–1000000`
    }
  }
  return null
}

const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !ready.value) return
  const err = validate()
  if (err) { ElMessage.warning(err); return }

  const body: K8sTlsRoute = {
    name: (editing.value ?? form.name).trim(),
    // tenantId / clusterId 由 makeResourceApi 的 ctx 参数合并进请求体；namespace 必须来自 body（Create 的边界判据）
    namespace: state.namespace!,
    hostnames: form.hostnames.map((h) => h.trim()).filter(Boolean).length
      ? form.hostnames.map((h) => h.trim()).filter(Boolean) : null,
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
    rules: form.rules.map((r) => ({
      name: r.name.trim() || null,
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
      await tlsRouteApi.update(editing.value, ctx2.value, body)
      ElMessage.success('保存成功')
    } else {
      await tlsRouteApi.create(ctx2.value, body)
      ElMessage.success('创建成功')
    }
    router.push({ name: 'tlsroutes' })
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push({ name: 'tlsroutes' }) }

const pageTitle = computed(() => (editing.value ? '编辑 TLSRoute' : '创建 TLSRoute'))
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
      description="该 TLSRoute 可能已被删除，或所选命名空间下不存在。"
    >
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <el-form v-if="formVisible" label-position="left" label-width="200px" class="editor-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 db-tls-route（RFC-1123）" style="max-width: 420px" />
          <FieldHelp tip="TLSRoute 名（本命名空间内唯一）。创建后不可改。" />
        </el-form-item>

        <el-form-item label="SNI 主机名（hostnames）">
          <el-select
            v-model="form.hostnames"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="留空 = 匹配全部 SNI；回车添加，如 db.example.com 或 *.example.com"
            style="max-width: 560px"
          />
          <FieldHelp tip="按 TLS ClientHello 里的 SNI 名匹配（只转发、不解密）。留空表示该路由接受所有 SNI —— 注意这会与同监听器上的其它 TLSRoute 争抢流量。IP 不能作为 SNI，通配符只能是最左整段。" />
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
            <FieldHelp v-else-if="!hasUnmodeledKind(p)" tip="要挂载到的 Gateway。它需要一个 TLS 协议（Passthrough）的监听器，否则规则不会被接受。" />
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

        <el-divider content-position="left">规则（rules；最多 16 条）</el-divider>

        <div v-for="(r, ri) in form.rules" :key="ri" class="sub-block rule-block">
          <div class="sub-head">
            <span class="sub-title">规则 #{{ ri + 1 }}</span>
            <el-button v-if="form.rules.length > 1" link type="danger" @click="removeRule(ri)">删除规则</el-button>
          </div>

          <el-form-item label="规则名">
            <el-input v-model="r.name" placeholder="可选（本路由内唯一）" style="max-width: 300px" />
            <FieldHelp tip="规则的名称，仅用于可读性。留空即可。" />
          </el-form-item>

          <el-form-item label="后端（backendRefs）" required>
            <div class="list-rows">
              <div v-for="(b, bi) in r.backendRefs" :key="bi" class="list-row">
                <el-input v-model="b.name" placeholder="Service 名" style="flex: 1" />
                <el-input v-model="b.namespace" placeholder="命名空间（留空=本 ns）" style="width: 180px" />
                <el-input-number v-model="b.port" :min="1" :max="65535" controls-position="right" placeholder="端口" style="width: 130px" />
                <el-input-number v-model="b.weight" :min="0" :max="1000000" controls-position="right" placeholder="权重" style="width: 130px" />
                <el-button v-if="r.backendRefs.length > 1" link type="danger" @click="removeBackend(r, bi)">删除</el-button>
              </div>
              <el-button size="small" @click="addBackend(r)">添加后端</el-button>
            </div>
            <FieldHelp tip="目标 Service 名 + 端口（必填）+ 权重（相对值不是百分比；0 = 不转发）。TLS 的分配维度只有权重。" />
          </el-form-item>
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
.editor-form { max-width: 900px; }
.sub-block {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px 14px 2px;
  margin-bottom: 12px;
  background: var(--panel-hover);
}
.rule-block { background: var(--panel); }
.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.parent-hint { margin-left: 8px; font-size: 12px; color: var(--text-3); }
.list-rows { display: flex; flex-direction: column; gap: 8px; width: 100%; max-width: 760px; }
.list-row { display: flex; align-items: center; gap: 10px; }
.submit-row { margin-top: 18px; }
</style>
