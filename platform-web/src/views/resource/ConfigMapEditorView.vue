<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { configMapApi } from '@/api'
import type { K8sConfigMap } from '@/types'
import { buildPreview, fmtSize } from '@/utils/binaryPreview'
import type { BinPreview } from '@/utils/binaryPreview'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import FieldHelp from "@/components/workload/FieldHelp.vue";

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>(route.query.name as string | null)

interface KvRow { key: string; value: string }
function newRows(): KvRow[] { return [{ key: '', value: '' }] }

/** binaryData 行：b64 为 base64 字符串（对应 K8s binaryData 值）；fileName/mime 仅新选文件时有，preview 由 b64 推导 */
interface BinRow { key: string; b64: string | null; fileName: string | null; mime: string | null; preview: BinPreview | null }
function newBinRows(): BinRow[] { return [{ key: '', b64: null, fileName: null, mime: null, preview: null }] }

const form = reactive({
  name: '',
  labels: {} as Record<string, string>,
  rows: newRows() as KvRow[],
  binaryRows: newBinRows() as BinRow[],
  immutable: false,
})

// ---------- 编辑回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await configMapApi.get(editing.value, {
      tenantId: state.tenantId!,
      clusterId: state.clusterId!,
      namespace: state.namespace!,
    })
    form.name = d.name
    form.labels = { ...(d.labels ?? {}) }
    const entries = Object.entries(d.data ?? {})
    form.rows = entries.length ? entries.map(([key, value]) => ({ key, value })) : newRows()
    const binEntries = Object.entries(d.binaryData ?? {})
    form.binaryRows = binEntries.length
      ? binEntries.map(([key, b64]) => ({ key, b64, fileName: null, mime: null, preview: buildPreview(b64, null) }))
      : newBinRows()
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

/** 载入时该 ConfigMap 是否已不可变（持久化值）。据此锁定：已 immutable → 数据区只读、开关不可再关（K8s 单向闩）；未 immutable → 表单里可自由切换开关 */
const originalImmutable = ref(false)
const dataLocked = computed(() => !!editing.value && originalImmutable.value)

// ---------- binaryData：文件 → base64 + 预览 ----------
function readAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const bytes = new Uint8Array(reader.result as ArrayBuffer)
      let bin = ''
      for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i] ?? 0)
      resolve(btoa(bin))
    }
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(file)
  })
}

function guessMime(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() ?? ''
  const map: Record<string, string> = {
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml',
    txt: 'text/plain', log: 'text/plain', md: 'text/markdown', csv: 'text/csv',
    json: 'application/json', xml: 'application/xml', yaml: 'application/yaml', yml: 'application/x-yaml',
  }
  return map[ext] ?? 'application/octet-stream'
}

async function onBinFilePick(idx: number, f: UploadFile): Promise<void> {
  const raw = f.raw
  const row = form.binaryRows[idx]
  if (!raw || !row) return
  try {
    const b64 = await readAsBase64(raw)
    const mime = raw.type || guessMime(raw.name)
    row.b64 = b64
    row.fileName = raw.name
    row.mime = mime
    row.preview = buildPreview(b64, mime)
  } catch {
    ElMessage.error(`读取文件「${raw.name}」失败`)
  }
}

// ---------- 提交：本地校验 → 组装 body（update 为整对象替换，labels/data/binaryData 必须随体提交） ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const saving = ref(false)

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }

  const data: Record<string, string> = {}
  for (const row of form.rows) {
    const key = row.key.trim()
    if (!key) continue
    if (data[key] !== undefined) { ElMessage.warning(`Key「${key}」重复`); return }
    data[key] = row.value
  }

  const binaryData: Record<string, string> = {}
  for (const row of form.binaryRows) {
    const key = row.key.trim()
    if (!key) continue // 空 Key → 跳过（编辑时即删除该项）
    if (!row.b64) { ElMessage.warning(`binaryData「${key}」未选择文件`); return }
    if (data[key] !== undefined) { ElMessage.warning(`Key「${key}」与 data 重复，不得重叠`); return }
    if (binaryData[key] !== undefined) { ElMessage.warning(`binaryData Key「${key}」重复`); return }
    binaryData[key] = row.b64
  }

  saving.value = true
  try {
    const ctx2 = { tenantId: state.tenantId!, clusterId: state.clusterId! }
    const body: K8sConfigMap = {
      name,
      namespace: state.namespace!,
      labels: form.labels,
      data,
      binaryData,
      immutable: form.immutable,
    }
    if (editing.value) {
      await configMapApi.update(editing.value, ctx2, body)
      ElMessage.success('保存成功')
    } else {
      await configMapApi.create(ctx2, body)
      ElMessage.success('创建成功')
    }
    router.push('/resources/configmaps')
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/resources/configmaps') }

const pageTitle = computed(() => (editing.value ? '编辑 ConfigMap' : '创建 ConfigMap'))
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

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑 ConfigMap。" />

    <template v-else>
      <div v-if="formVisible" class="editor-body">
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">{{ editing ? `ConfigMap · ${form.name}` : '基础信息' }}</span></template>
          <el-form label-width="120px" label-position="left">
            <el-form-item required>
              <template #label>名称 <FieldHelp tip="K8s 资源名创建后不可修改；命名空间 = 当前上下文" /></template>
              <el-input v-model="form.name" :disabled="!!editing" placeholder="小写字母/数字/-，例如 app-config" style="width: 360px" />
            </el-form-item>
            <el-form-item label="标签">
              <LabelEditor v-model="form.labels" class="sub-editor" style="max-width: 520px" />
            </el-form-item>
            <el-form-item>
              <template #label>immutable <FieldHelp tip="开启后 data / binaryData 不可再修改（仅可改标签等 metadata）；一旦为 true 无法改回 false。编辑已开启 immutable 的 ConfigMap 时，下方数据区将被锁定。" /></template>
              <el-switch v-model="form.immutable" :disabled="dataLocked" />
            </el-form-item>
            <el-form-item label="data">
              <div class="kv-editor">
                <div v-for="(row, idx) in form.rows" :key="idx" class="kv-row">
                  <el-input v-model="row.key" placeholder="Key（如 log.level）" class="kv-key" :disabled="dataLocked" />
                  <el-input v-model="row.value" type="textarea" :autosize="{ minRows: 1, maxRows: 8 }" placeholder="Value" :disabled="dataLocked" />
                  <el-button link type="danger" :disabled="dataLocked || form.rows.length <= 1" @click="form.rows.splice(idx, 1)">删除</el-button>
                </div>
                <el-button class="add-row-btn" plain :disabled="dataLocked" @click="form.rows.push({ key: '', value: '' })">+ 添加键值对</el-button>
              </div>
            </el-form-item>
            <el-form-item label="binaryData">
              <div class="kv-editor">
                <div v-for="(row, idx) in form.binaryRows" :key="'b' + idx" class="bin-row">
                  <div class="bin-row-top">
                    <el-input v-model="row.key" placeholder="Key（如 cert.pem）" class="kv-key" :disabled="dataLocked" />
                    <el-upload :show-file-list="false" :auto-upload="false" accept="*/*" @change="(f: UploadFile) => onBinFilePick(idx, f)">
                      <el-button size="small" plain :disabled="dataLocked">选择文件</el-button>
                    </el-upload>
                    <span class="bin-meta muted">{{ row.fileName ?? (row.b64 ? '（已有内容）' : '') }}</span>
                    <el-button link type="danger" :disabled="dataLocked || form.binaryRows.length <= 1" @click="form.binaryRows.splice(idx, 1)">删除</el-button>
                  </div>
                  <div v-if="row.preview" class="bin-preview">
                    <img v-if="row.preview.kind === 'image'" :src="row.preview.dataUrl" class="bin-img" />
                    <pre v-else-if="row.preview.kind === 'text'" class="bin-text">{{ row.preview.text }}</pre>
                    <div v-else class="bin-bin">
                      <span class="muted">{{ fmtSize(row.preview.size) }} · 二进制</span>
                      <a :href="row.preview.dataUrl" download class="bin-dl">下载</a>
                    </div>
                  </div>
                </div>
                <el-button class="add-row-btn" plain :disabled="dataLocked" @click="form.binaryRows.push({ key: '', b64: null, fileName: null, mime: null, preview: null })">+ 添加二进制项</el-button>
              </div>
            </el-form-item>
          </el-form>
        </el-card>

        <div class="form-actions">
          <el-button @click="goBack">取消 / 返回</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
      </div>

      <EmptyState v-else-if="detailState === 'error'" title="加载 ConfigMap 失败" description="请返回列表重试；若该资源已被删除，刷新列表即可。">
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
  align-items: flex-start;
}
.kv-key {
  width: 260px;
  flex-shrink: 0;
}
.bin-row {
  margin-bottom: 12px;
}
.bin-row-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.bin-meta {
  font-size: 12px;
  word-break: break-all;
}
.bin-preview {
  padding-left: 268px;
}
.bin-img {
  max-height: 96px;
  max-width: 200px;
  border-radius: 4px;
  border: 1px solid var(--border);
  object-fit: contain;
  background: #fff;
}
.bin-text {
  margin: 0;
  padding: 8px;
  border-radius: 4px;
  background: var(--panel-hover);
  border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.5;
  max-height: 96px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.bin-bin {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}
.bin-dl {
  color: var(--accent);
  text-decoration: none;
}
.bin-dl:hover {
  text-decoration: underline;
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
