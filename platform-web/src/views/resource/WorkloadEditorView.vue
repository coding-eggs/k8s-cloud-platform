<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { workloadApi } from '@/api'
import type {
  Affinity,
  ContainerDef,
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
import AffinityEditor from '@/components/workload/AffinityEditor.vue'
import TolerationEditor from '@/components/workload/TolerationEditor.vue'
import VolumeEditor from '@/components/workload/VolumeEditor.vue'
import PvcTemplateEditor from '@/components/workload/PvcTemplateEditor.vue'

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
    podTemplate: { spec: { containers: [{ name: '', image: '' }], restartPolicy: 'Always' } },
  }
}

const form = reactive<WorkloadDetail>(defaultForm())

/** 保证 podTemplate 存在后取 spec（类型上 spec/containers 必在，编辑回填同样保证） */
function ensureSpec(): PodSpec {
  if (!form.podTemplate) form.podTemplate = { spec: { containers: [] } }
  return form.podTemplate.spec
}

const containerListRef = ref<InstanceType<typeof ContainerListEditor> | null>(null)

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

/** 提交前归一化：imagePullPolicy '' → null；affinity 剔除空 term（空 nodeSelectorTerm 序列化为 {}，OR 语义下静默废掉整个 required） */
function normalizeForSubmit(): void {
  const spec = form.podTemplate?.spec
  if (!spec) return
  for (const c of [...spec.containers, ...(spec.initContainers ?? [])]) {
    if (c.imagePullPolicy === '') c.imagePullPolicy = null
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

  // 6) B4：requests ≤ limits（ContainerListEditor → ResourcesEditor.isValid）
  if (!containerListRef.value?.isValid()) {
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
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <!-- 上下文未选齐 -->
    <EmptyState
      v-if="!ready"
      title="尚未选择上下文"
      description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑工作负载。"
    />

    <template v-else>
      <div v-if="formVisible" class="editor-form">
        <!-- 1. 基础信息 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">基础信息</span></template>
          <el-form label-width="120px">
            <el-form-item label="类型 kind" required>
              <el-select v-model="kind" :disabled="!!editing" style="width: 200px">
                <el-option label="Deployment" value="deployment" />
                <el-option label="StatefulSet" value="statefulset" />
                <el-option label="DaemonSet" value="daemonset" />
              </el-select>
            </el-form-item>
            <el-form-item label="名称 name" required>
              <el-input v-model="form.name" :disabled="!!editing" placeholder="小写字母/数字/-，例如 web-app" style="width: 320px" />
              <div class="form-tip">RFC1123：小写字母/数字/-，以字母或数字开头结尾；创建后不可修改</div>
            </el-form-item>
            <el-form-item label="描述 description">
              <el-input v-model="description" type="textarea" :rows="2" placeholder="可选" style="width: 480px" />
            </el-form-item>
            <el-form-item label="标签 labels">
              <LabelEditor v-model="labels" class="sub-editor" />
            </el-form-item>
            <el-form-item v-if="form.kind !== 'daemonset'" label="副本数 replicas">
              <el-input-number v-model="replicas" :min="0" :max="64" controls-position="right" />
            </el-form-item>
            <el-form-item v-if="form.kind === 'statefulset'" label="serviceName">
              <el-input v-model="serviceName" placeholder="Headless Service 名称（留空默认 = 工作负载名）" style="width: 360px" />
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 2. 更新策略 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">更新策略</span></template>
          <StrategyEditor v-model="strategy" :kind="form.kind" />
        </el-card>

        <!-- 3. 容器 / 初始化容器 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">容器 / 初始化容器</span></template>
          <ContainerListEditor
            ref="containerListRef"
            v-model:main="mainContainers"
            v-model:init="initContainers"
            :volume-names="volumeNames"
          />
        </el-card>

        <!-- 4. Pod 高级 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">Pod 高级</span></template>
          <el-form label-width="150px">
            <el-form-item label="restartPolicy">
              <el-tag size="small" effect="plain" type="info">Always（固定）</el-tag>
              <span class="inline-tip">工作负载 Pod 的重启策略固定为 Always，不可修改</span>
            </el-form-item>
            <el-form-item label="serviceAccountName">
              <el-input v-model="serviceAccountName" placeholder="可选，默认 default" style="width: 280px" />
            </el-form-item>
            <el-form-item label="nodeName">
              <el-input v-model="nodeName" placeholder="指定调度到某节点（可选）" style="width: 280px" />
              <div v-if="nodeName" class="form-tip warn">设置 nodeName 后，nodeSelector / 亲和的节点选择将被忽略</div>
            </el-form-item>
            <el-form-item label="nodeSelector">
              <LabelEditor v-model="nodeSelector" class="sub-editor" />
            </el-form-item>
            <el-form-item label="affinity">
              <AffinityEditor v-model="affinity" />
            </el-form-item>
            <el-form-item label="tolerations">
              <TolerationEditor v-model="tolerations" />
            </el-form-item>
            <el-form-item label="volumes">
              <VolumeEditor v-model="volumes" />
            </el-form-item>
            <el-form-item label="imagePullSecrets">
              <div class="kv-editor">
                <div v-for="(s, i) in imagePullSecretsList" :key="i" class="kv-row">
                  <el-input v-model="s.name" placeholder="镜像仓库 Secret 名称（如 regcred）" style="width: 280px" />
                  <el-button link type="danger" @click="removeImagePullSecret(i)">删除</el-button>
                </div>
                <el-button class="add-row-btn" plain @click="addImagePullSecret">+ 添加 imagePullSecret</el-button>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 5. 存储卷模板（仅 statefulset） -->
        <el-card v-if="form.kind === 'statefulset'" shadow="never" class="sec-card">
          <template #header><span class="sec-title">存储卷模板 volumeClaimTemplates</span></template>
          <PvcTemplateEditor v-model="volumeClaimTemplates" />
        </el-card>

        <!-- 操作 -->
        <div class="form-actions">
          <el-button @click="goBack">取消 / 返回</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
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
.editor-form {
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
.inline-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-3);
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
.add-row-btn {
  width: 100%;
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
