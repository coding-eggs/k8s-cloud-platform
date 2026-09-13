<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { secretApi } from '@/api'
import type { K8sSecret } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>(route.query.name as string | null)

const SECRET_TYPES = [
  'Opaque',
  'kubernetes.io/tls',
  'kubernetes.io/basic-auth',
  'kubernetes.io/dockerconfigjson',
  'kubernetes.io/service-account-token',
  'bootstrap.kubernetes.io/token',
]

/** 各 Secret 类型的用途与必需 key（选中后展示在类型下方） */
const SECRET_TYPE_DESC: Record<string, string> = {
  Opaque: '通用类型，无特殊语义，可存任意 key-value（配置、密码等）。默认类型。',
  'kubernetes.io/tls': 'TLS 证书。需 tls.crt 与 tls.key（可选 ca.crt），用于为 Pod / Ingress 挂载 TLS 证书。',
  'kubernetes.io/basic-auth': 'HTTP Basic 认证。需 username 与 password 两个 key。',
  'kubernetes.io/dockerconfigjson': '私有镜像仓库拉取凭证。需单个 key .dockerconfigjson，值为 docker config JSON（~/.docker/config.json 内容），配合 imagePullSecrets 使用。',
  'kubernetes.io/service-account-token': 'ServiceAccount Token。需 ca.crt、namespace、serviceAccountName；token 由控制器自动生成。用于为 Pod 提供访问 API Server 的凭证。',
  'bootstrap.kubernetes.io/token': '节点引导（node bootstrap）token，kubelet 加入集群时使用，一般由引导流程创建，不建议手动编辑。',
}

interface KvRow { key: string; value: string }
function newRows(): KvRow[] { return [{ key: '', value: '' }] }

const form = reactive({
  name: '',
  type: 'Opaque',
  labels: {} as Record<string, string>,
  rows: newRows() as KvRow[],
  immutable: false,
})

// ---------- 编辑回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await secretApi.get(editing.value, {
      tenantId: state.tenantId!,
      clusterId: state.clusterId!,
      namespace: state.namespace!,
    })
    form.name = d.name
    form.type = d.type || 'Opaque'
    form.labels = { ...(d.labels ?? {}) }
    const entries = Object.entries(d.data ?? {})
    form.rows = entries.length ? entries.map(([key, value]) => ({ key, value })) : newRows()
    form.immutable = d.immutable === true
    originalImmutable.value = d.immutable === true
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

/** 载入时该 Secret 是否已不可变（持久化值）。据此锁定：已 immutable → data 只读、开关不可再关（K8s 单向闩）；未 immutable → 可自由切换 */
const originalImmutable = ref(false)
const dataLocked = computed(() => !!editing.value && originalImmutable.value)

/** 非平台支持的 Secret 类型（type 不在 SECRET_TYPES 内，如 helm.sh/release.v1）：整体只读、禁止编辑保存 */
const readOnly = computed(() => !!editing.value && !SECRET_TYPES.includes(form.type))

/** 类型下拉选项：标准类型 + 当前实际类型（非标准类型也需可见，即使只读也要能显示出来） */
const typeOptions = computed(() =>
  SECRET_TYPES.includes(form.type) ? SECRET_TYPES : [...SECRET_TYPES, form.type],
)

// ---------- 提交：本地校验 → 组装 body（data 明文，后端映射到 stringData；update 为整对象替换） ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const saving = ref(false)

async function submit(): Promise<void> {
  if (!ready.value || saving.value || readOnly.value) return
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }

  const data: Record<string, string> = {}
  for (const row of form.rows) {
    const key = row.key.trim()
    if (!key) { ElMessage.warning('存在空的 Key'); return }
    if (data[key] !== undefined) { ElMessage.warning(`Key「${key}」重复`); return }
    data[key] = row.value
  }

  saving.value = true
  try {
    const ctx2 = { tenantId: state.tenantId!, clusterId: state.clusterId! }
    const body: K8sSecret = { name, namespace: state.namespace!, type: form.type, labels: form.labels, data, immutable: form.immutable }
    if (editing.value) {
      await secretApi.update(editing.value, ctx2, body)
      ElMessage.success('保存成功')
    } else {
      await secretApi.create(ctx2, body)
      ElMessage.success('创建成功')
    }
    router.push('/resources/secrets')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/resources/secrets') }

const pageTitle = computed(() => (editing.value ? '编辑 Secret' : '创建 Secret'))
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

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑 Secret。" />

    <template v-else>
      <div v-if="formVisible" class="editor-body">
        <el-alert
          v-if="readOnly"
          type="warning"
          :closable="false"
          show-icon
          :title="`此 Secret 类型（${form.type}）不受本平台支持，已设为只读`"
          description="该类型不是平台提供的标准 Secret 类型（常见于 Helm release 等由控制器管理的资源）。为避免误改，禁止在此编辑；请改用 kubectl / Helm 等原生工具管理。"
        />
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">{{ editing ? `Secret · ${form.name}` : '基础信息' }}</span></template>
          <el-form label-width="120px" label-position="left">
            <el-form-item label="名称" required>
              <template #label>名称 <FieldHelp tip="K8s 资源名创建后不可修改；命名空间 = 当前上下文" /></template>
              <el-input v-model="form.name" :disabled="!!editing" placeholder="小写字母/数字/-，例如 db-credentials" style="width: 360px" />
            </el-form-item>
            <el-form-item>
              <template #label>类型 <FieldHelp tip="不同类型对 data 的 key 有约定，选中后下方显示该类型的用途与必需 key" /></template>
              <el-select v-model="form.type" style="width: 360px" :disabled="readOnly">
                <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
              </el-select>
              <div class="form-tip">{{ SECRET_TYPE_DESC[form.type] }}</div>
            </el-form-item>
            <el-form-item label="标签">
              <LabelEditor v-model="form.labels" :disabled="readOnly" class="sub-editor" style="max-width: 520px" />
            </el-form-item>
            <el-form-item>
              <template #label>immutable <FieldHelp tip="开启后 data 不可再修改（仅可改标签等 metadata）；一旦为 true 无法改回 false。编辑已开启 immutable 的 Secret 时，下方数据区将被锁定。" /></template>
              <el-switch v-model="form.immutable" :disabled="dataLocked || readOnly" />
            </el-form-item>
            <el-form-item label="data">
              <div class="kv-editor">
                <div v-for="(row, idx) in form.rows" :key="idx" class="kv-row">
                  <el-input v-model="row.key" placeholder="Key（如 username）" class="kv-key" :disabled="dataLocked || readOnly" />
                  <el-input v-model="row.value" type="password" show-password placeholder="Value（明文提交，由 apiserver 加密存储）" :disabled="dataLocked || readOnly" />
                  <el-button link type="danger" :disabled="(dataLocked || readOnly) || form.rows.length <= 1" @click="form.rows.splice(idx, 1)">删除</el-button>
                </div>
                <el-button class="add-row-btn" plain :disabled="dataLocked || readOnly" @click="form.rows.push({ key: '', value: '' })">+ 添加键值对</el-button>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <div class="form-actions">
          <el-button @click="goBack">取消 / 返回</el-button>
          <el-button type="primary" :loading="saving" :disabled="readOnly" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
      </div>

      <EmptyState v-else-if="detailState === 'error'" title="加载 Secret 失败" description="请返回列表重试；若该资源已被删除，刷新列表即可。">
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
.kv-editor {
  width: 60rem;
}
.kv-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.kv-key {
  width: 260px;
  flex-shrink: 0;
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
