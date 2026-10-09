<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { gatewayApi, tcpRouteApi } from '@/api'
import { useResourceContext } from '@/stores/context'
import type { K8sGateway, K8sL4RouteRule, K8sRouteBackendRef, K8sRouteParentRef, K8sTcpRoute } from '@/types'

/**
 * TCPRoute 独立编辑页（命名空间级、租户域）。
 * <p>四层路由的表单只有两块：<b>父 Gateway</b> + <b>一条规则的后端列表</b>。没有 matches、没有 filters
 * （L4 不解七层协议）。
 *
 * <h2>CRD 的 rules maxItems=1</h2>
 * TCPRoute / UDPRoute 的 {@code rules} 在 CRD 里是 maxItems=1（TLSRoute 是 16）。故本页固定一条规则，
 * 不提供"添加规则" —— 加不了的东西不该画出按钮。TLS 版（{@link TlsRouteEditorView}）才有多规则。
 *
 * <p>未建模内容：{@code parentRefs[].group/kind/port} 与 spec 级未建模式键由"透传保留 + 后端
 * fetch-overlay"处理（同 HTTPRoute 编辑器）；全量在列表页的 YAML tab 可见。
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

function newParent(): ParentRow { return { name: '', namespace: '', sectionName: '' } }
function newBackend(): BackendRow { return { name: '', namespace: '', port: null, weight: null } }

const form = reactive({
  name: '',
  parentRefs: [newParent()] as ParentRow[],
  ruleName: '',
  backendRefs: [newBackend()] as BackendRow[],
})

function addParent(): void { form.parentRefs.push(newParent()) }
function removeParent(i: number): void { form.parentRefs.splice(i, 1) }
function addBackend(): void { form.backendRefs.push(newBackend()) }
function removeBackend(i: number): void { form.backendRefs.splice(i, 1) }

// ---------- 回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

function fromParent(p: K8sRouteParentRef): ParentRow {
  return { name: p.name ?? '', namespace: p.namespace ?? '', sectionName: p.sectionName ?? '', group: p.group, kind: p.kind, port: p.port }
}
function fromBackend(b: K8sRouteBackendRef): BackendRow {
  return { name: b.name ?? '', namespace: b.namespace ?? '', port: b.port ?? null, weight: b.weight ?? null, group: b.group, kind: b.kind }
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await tcpRouteApi.get(editing.value, ctx3.value)
    form.name = d.name
    form.parentRefs = (d.parentRefs ?? []).map(fromParent)
    if (!form.parentRefs.length) form.parentRefs = [newParent()]
    const rule = (d.rules ?? [])[0]
    form.ruleName = rule?.name ?? ''
    form.backendRefs = (rule?.backendRefs ?? []).map(fromBackend)
    if (!form.backendRefs.length) form.backendRefs = [newBackend()]
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

function validate(): string | null {
  const name = (editing.value ?? form.name).trim()
  if (!name) return '请输入名称'
  if (!RFC1123_RE.test(name)) return '名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'
  if (!form.parentRefs.some((p) => p.name.trim())) return '至少需要一个父 Gateway（parentRefs）'
  const backends = form.backendRefs.filter((b) => b.name.trim())
  if (!backends.length) return '至少需要一个后端（backendRefs）'
  for (const b of backends) {
    if (b.port == null || b.port < 1 || b.port > 65535) return `后端「${b.name}」的端口须为 1–65535`
    if (b.weight != null && (b.weight < 0 || b.weight > 1000000)) return `后端「${b.name}」的权重须为 0–1000000`
  }
  return null
}

const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !ready.value) return
  const err = validate()
  if (err) { ElMessage.warning(err); return }

  const rule: K8sL4RouteRule = {
    name: form.ruleName.trim() || null,
    backendRefs: form.backendRefs
      .filter((b) => b.name.trim())
      .map((b) => ({
        name: b.name.trim(),
        namespace: b.namespace.trim() || null,
        port: b.port,
        weight: b.weight,
        group: b.group ?? null,
        kind: b.kind ?? null,
      })),
  }
  const body: K8sTcpRoute = {
    name: (editing.value ?? form.name).trim(),
    // tenantId / clusterId 由 makeResourceApi 的 ctx 参数合并进请求体；namespace 必须来自 body（Create 的边界判据）
    namespace: state.namespace!,
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
    rules: [rule],
  }

  saving.value = true
  try {
    if (editing.value) {
      await tcpRouteApi.update(editing.value, ctx2.value, body)
      ElMessage.success('保存成功')
    } else {
      await tcpRouteApi.create(ctx2.value, body)
      ElMessage.success('创建成功')
    }
    router.push({ name: 'tcproutes' })
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push({ name: 'tcproutes' }) }

const pageTitle = computed(() => (editing.value ? '编辑 TCPRoute' : '创建 TCPRoute'))
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
      description="该 TCPRoute 可能已被删除，或所选命名空间下不存在。"
    >
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <el-form v-if="formVisible" label-position="left" label-width="200px" class="editor-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 mysql-route（RFC-1123）" style="max-width: 420px" />
          <FieldHelp tip="TCPRoute 名（本命名空间内唯一）。创建后不可改。" />
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
            <FieldHelp tip="要挂载到的 Gateway。该 Gateway 必须有一个 TCP 协议的监听器，否则规则不会被接受（父资源状态里会出现 Accepted=False）。" />
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

        <el-divider content-position="left">规则（rules；TCPRoute 只允许一条）</el-divider>

        <el-form-item label="规则名">
          <el-input v-model="form.ruleName" placeholder="可选" style="max-width: 300px" />
          <FieldHelp tip="规则的名称，仅用于可读性。留空即可。" />
        </el-form-item>

        <el-form-item label="后端（backendRefs）" required>
          <div class="list-rows">
            <div v-for="(b, bi) in form.backendRefs" :key="bi" class="list-row">
              <el-input v-model="b.name" placeholder="Service 名" style="flex: 1" />
              <el-input v-model="b.namespace" placeholder="命名空间（留空=本 ns）" style="width: 180px" />
              <el-input-number v-model="b.port" :min="1" :max="65535" controls-position="right" placeholder="端口" style="width: 130px" />
              <el-input-number v-model="b.weight" :min="0" :max="1000000" controls-position="right" placeholder="权重" style="width: 130px" />
              <el-button v-if="form.backendRefs.length > 1" link type="danger" @click="removeBackend(bi)">删除</el-button>
            </div>
            <el-button size="small" @click="addBackend">添加后端</el-button>
          </div>
          <FieldHelp tip="目标 Service 名 + 端口（必填）+ 权重。权重是相对值不是百分比：两个后端各 50 与各 1 效果相同；权重 0 表示不向它转发。默认 kind=Service。L4 没有按路径/头的分流，只有权重。" />
        </el-form-item>

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
.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.list-rows { display: flex; flex-direction: column; gap: 8px; width: 100%; max-width: 760px; }
.list-row { display: flex; align-items: center; gap: 10px; }
.submit-row { margin-top: 18px; }
</style>
