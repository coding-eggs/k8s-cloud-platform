<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
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

/**
 * 固定 key 的标准类型（apiserver 按类型校验 data 的 key，见 k8s core/v1 validation）：
 * required = 必配，Key 不可改不可删；optional = 可选，经「+ 添加键值对」从下拉里补。
 * 不在表里的类型（Opaque、service-account-token、bootstrap token、平台不支持的类型）保持自由输入。
 */
const SECRET_FIXED_KEYS: Record<string, { required: string[]; optional: string[] }> = {
  'kubernetes.io/tls': { required: ['tls.crt', 'tls.key'], optional: ['ca.crt'] },
  'kubernetes.io/basic-auth': { required: ['username', 'password'], optional: [] },
  'kubernetes.io/dockerconfigjson': { required: ['.dockerconfigjson'], optional: [] },
}

/** 当前类型的固定 key 规格；null = 自由输入 */
const fixedSpec = computed(() => SECRET_FIXED_KEYS[form.type] ?? null)
/** 固定类型下 Key 列只读：新增 key 只能从 optional 里选，杜绝手敲出 apiserver 不认的 key */
const keyLocked = computed(() => !!fixedSpec.value)
/** 还没被占用的可选 key */
const addableKeys = computed(() => {
  const spec = fixedSpec.value
  if (!spec) return []
  const used = new Set(form.rows.map((r) => r.key.trim()))
  return spec.optional.filter((k) => !used.has(k))
})

/**
 * 回填期间为 true：此时 form.type 从 'Opaque' 变成对象真实类型，会触发下面的 watch，
 * 但线上的行本来就该原样呈现（包括该类型以外的 key），不该被规格重排或修剪。
 */
const hydrating = ref(false)

/**
 * 切类型时按目标类型整理行：
 * - 切到固定类型：只保留 ①该类型必需 key（值按 key 带过来）②当前行里属于该类型 optional 的
 *   （如 tls 的 ca.crt）。上一种类型留下的行一律丢掉 —— 否则 basic-auth → tls 会剩下四行。
 * - 切到自由类型（Opaque 等）：行原样保留（改 key 名要用），但把「key 有、value 空」的空壳行清掉 ——
 *   那是上一种类型铺出来的（basic-auth 的 username/password），留着等于凭空塞两个空 key 进对象。
 * 回填不走这里（见 hydrating）：线上多出来的 key 由「不重建」本身保住，不需要靠留底。
 */
watch(() => form.type, (t) => {
  if (hydrating.value) return
  const spec = SECRET_FIXED_KEYS[t]
  if (!spec) {
    const kept = form.rows.filter((r) => r.key.trim() && r.value !== '')
    form.rows = kept.length ? kept : newRows()
    return
  }
  const values = new Map(form.rows.map((r) => [r.key.trim(), r.value]))
  const rows: KvRow[] = spec.required.map((k) => ({ key: k, value: values.get(k) ?? '' }))
  for (const r of form.rows) {
    const k = r.key.trim()
    if (!k || spec.required.includes(k) || rows.some((x) => x.key === k)) continue
    if (!spec.optional.includes(k)) continue
    rows.push({ key: k, value: r.value })
  }
  form.rows = rows
})

function addFixedRow(key: string): void {
  form.rows.push({ key, value: '' })
}

/** 必需 key 不可删；自由类型至少留一行 */
function canDeleteRow(row: KvRow): boolean {
  const spec = fixedSpec.value
  if (spec) return !spec.required.includes(row.key.trim())
  return form.rows.length > 1
}

// ---------- 值：从本地文件导入（证书/私钥整段粘贴的替代） ----------
const fileInputRef = ref<HTMLInputElement | null>(null)
const pendingRowIdx = ref(-1)

function pickFile(idx: number): void {
  pendingRowIdx.value = idx
  const el = fileInputRef.value
  if (!el) return
  el.value = '' // 清空才能连续选同一个文件
  el.click()
}

function readAsText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}

async function onFileChosen(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  const idx = pendingRowIdx.value
  pendingRowIdx.value = -1
  if (!file || idx < 0) return
  const row = form.rows[idx]
  if (!row) return
  try {
    row.value = await readAsText(file)
    // 空 Key 用文件名兜底：TLS 的 key 本来就叫 tls.crt / tls.key
    if (!row.key.trim()) row.key = file.name
    ElMessage.success(`已导入「${file.name}」`)
  } catch {
    ElMessage.error(`读取文件「${file.name}」失败`)
  }
}

// ---------- 编辑回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  hydrating.value = true
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
    // 等 watch(flush: pre) 这一轮跑完再放开，回填才算真正结束
    await nextTick()
    hydrating.value = false
    detailState.value = 'loaded'
  } catch {
    hydrating.value = false
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

  // 固定类型的必配 key 必须非空：少了 apiserver 会回 422，这里先给出能对得上现场的提示
  const spec = fixedSpec.value
  if (spec) {
    const missing = spec.required.filter((k) => !data[k])
    if (missing.length) {
      ElMessage.warning(`「${form.type}」类型必须填写 ${missing.join('、')} 的值`)
      return
    }
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
              <div v-if="fixedSpec" class="form-tip">该类型的 Key 已固定，无需手填；可选 key 通过「+ 添加键值对」补。</div>
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
                  <div class="kv-row-head">
                    <el-input
                      v-model="row.key"
                      placeholder="Key（如 username）"
                      class="kv-key"
                      :disabled="dataLocked || readOnly || keyLocked"
                    />
                    <div class="kv-actions">
                      <el-button link type="primary" :disabled="dataLocked || readOnly" @click="pickFile(idx)">导入文件</el-button>
                      <el-button link type="danger" :disabled="(dataLocked || readOnly) || !canDeleteRow(row)" @click="form.rows.splice(idx, 1)">删除</el-button>
                    </div>
                  </div>
                  <el-input
                    v-model="row.value"
                    type="textarea"
                    :autosize="{ minRows: 1, maxRows: 10 }"
                    spellcheck="false"
                    class="kv-value"
                    placeholder="Value（明文提交，由 apiserver 加密存储）；证书/私钥可整段粘贴，或点右上「导入文件」"
                    :disabled="dataLocked || readOnly"
                  />
                </div>
                <input
                  ref="fileInputRef"
                  type="file"
                  hidden
                  accept=".crt,.cer,.pem,.key,.json,.txt,.yaml,.yml,text/*,application/json"
                  @change="onFileChosen"
                />
                <el-dropdown
                  v-if="fixedSpec && addableKeys.length"
                  trigger="click"
                  :disabled="dataLocked || readOnly"
                  @command="addFixedRow"
                >
                  <el-button plain :disabled="dataLocked || readOnly">
                    + 添加键值对<el-icon class="el-icon--right"><ArrowDown /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="k in addableKeys" :key="k" :command="k">{{ k }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button v-else-if="!fixedSpec" class="add-row-btn" plain :disabled="dataLocked || readOnly" @click="form.rows.push({ key: '', value: '' })">+ 添加键值对</el-button>
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
  width: 100%;
}
.kv-row {
  margin-bottom: 10px;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
}
.kv-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.kv-key {
  width: 260px;
  flex-shrink: 0;
}
/* 长值（证书/私钥/JSON）按原样换行编辑，等宽字体与详情抽屉的 .kv-value 保持一致 */
.kv-value :deep(.el-textarea__inner) {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12.5px;
  line-height: 1.5;
}
.kv-actions {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
  margin-left: auto;
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
