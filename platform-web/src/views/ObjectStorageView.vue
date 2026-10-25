<script setup lang="ts">
/**
 * 对象存储（SeaweedFS 集群组件）。
 *
 * <p>租户视图：只看自己名下的桶 + 桶里的对象（只读）+ 自己的凭据。
 * 管理员视图：全部租户的桶、代建桶、设置每租户的桶数上限、发放/吊销凭据。
 *
 * <p>对象可读可写（租户可写自己名下的桶）：上传/删除单个对象，不做按前缀批量删；
 * 下载经平台代理（S3 网关只在集群内），单次上传/下载上限 100MB，超过请用 SDK 直连。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, objectStorageApi, resourceContextApi, tenantApi } from '@/api'
import { usePermission } from '@/stores/permission'
import { humanizeBytes } from '@/utils/metrics'
import type {
  K8sCluster, ObjectStorageBucket, ObjectStorageBucketStat, ObjectStorageCredential,
  ObjectStorageCredentialSecret, ObjectStorageObjectItem, ObjectStorageQuota, PlatformTenant,
} from '@/types'

const perm = usePermission()

const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
const tenants = ref<PlatformTenant[]>([])
/** 管理员可切换看哪个租户；空 = 全部 */
const tenantFilter = ref('')
const buckets = ref<ObjectStorageBucket[]>([])
const quota = ref<ObjectStorageQuota | null>(null)
const loading = ref(false)

function tenantName(id: string): string {
  return tenants.value.find((t) => t.id === id)?.name ?? id
}

/** 租户用户：后端按 token 收窄；管理员：按筛选框 */
function scopedTenantId(): string | undefined {
  return perm.isAdmin ? (tenantFilter.value || undefined) : undefined
}

async function loadClusters(): Promise<void> {
  if (perm.isAdmin) {
    clusters.value = (await clusterApi.list()) ?? []
  } else {
    const ctx = await resourceContextApi.get()
    const seen = new Map<string, K8sCluster>()
    for (const t of ctx?.tenants ?? []) {
      for (const c of t.clusters ?? []) {
        if (!seen.has(c.clusterId)) {
          seen.set(c.clusterId, { clusterId: c.clusterId, clusterName: c.clusterName } as unknown as K8sCluster)
        }
      }
    }
    clusters.value = [...seen.values()]
  }
  const firstEnabled = clusters.value.find((c) => c.enabled === 1)
  clusterId.value = firstEnabled?.clusterId ?? clusters.value[0]?.clusterId ?? ''
}

async function loadTenants(): Promise<void> {
  if (!perm.isAdmin) return
  try {
    tenants.value = (await tenantApi.list()) ?? []
  } catch {
    tenants.value = []
  }
}

async function refresh(): Promise<void> {
  // 换集群/租户、或刚建删过桶：退回桶列表，避免停在别的租户的桶里
  closeObjects()
  if (!clusterId.value) {
    buckets.value = []
    quota.value = null
    return
  }
  loading.value = true
  try {
    const tenantId = scopedTenantId()
    buckets.value = (await objectStorageApi.listBuckets({ clusterId: clusterId.value, tenantId })) ?? []
    // 配额按租户查：管理员没选租户时不查（没有「全部租户的配额」这个概念）
    quota.value = tenantId
      ? await objectStorageApi.quota({ clusterId: clusterId.value, tenantId })
      : null
    // 用量单独异步加载：S3 没有桶大小 API，后端要列举求和，不能让列表等它
    void loadStats()
  } finally {
    loading.value = false
  }
}

/** 桶名 → 用量；统计失败不影响列表本身 */
const stats = ref<Record<string, ObjectStorageBucketStat>>({})

async function loadStats(): Promise<void> {
  if (!buckets.value.length) {
    stats.value = {}
    return
  }
  try {
    const rows = await objectStorageApi.bucketStats({
      clusterId: clusterId.value,
      tenantId: scopedTenantId(),
      buckets: buckets.value.map((b) => b.bucket),
    })
    const map: Record<string, ObjectStorageBucketStat> = {}
    for (const row of rows ?? []) map[row.bucket] = row
    stats.value = map
  } catch {
    stats.value = {}
  }
}

function statOf(row: ObjectStorageBucket): ObjectStorageBucketStat | undefined {
  return stats.value[row.bucket]
}

/** truncated = 只统计到上限，显示「≥」而不是假装精确；有配额时带上「/ 上限」 */
function statText(row: ObjectStorageBucket): string {
  const s = stats.value[row.bucket]
  const used = s ? (s.truncated ? '≥ ' : '') + humanizeBytes(s.bytes) : '—'
  return row.quotaBytes ? `${used} / ${humanizeBytes(row.quotaBytes)}` : used
}

function statTip(row: ObjectStorageBucket): string {
  const s = stats.value[row.bucket]
  if (!s) return '正在统计…'
  return `${s.objectCount}${s.truncated ? '+' : ''} 个对象` +
    (s.truncated ? '（超过统计上限，这里显示的是下限）' : '')
}

// ---------------- 建桶（管理员） ----------------

const createVisible = ref(false)
const creating = ref(false)
const UNIT_BYTES: Record<'MB' | 'GB' | 'TB', number> = {
  MB: 1024 ** 2, GB: 1024 ** 3, TB: 1024 ** 4,
}
const createForm = ref({
  tenantId: '',
  name: '',
  displayName: '',
  /** 容量上限；null = 不限（由 SeaweedFS 每桶 quota 执行） */
  quotaSize: null as number | null,
  quotaUnit: 'GB' as 'MB' | 'GB' | 'TB',
})

function openCreate(): void {
  createForm.value = {
    tenantId: tenantFilter.value || '', name: '', displayName: '', quotaSize: null, quotaUnit: 'GB',
  }
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!createForm.value.tenantId) {
    ElMessage.error('请选择桶归属的租户')
    return
  }
  creating.value = true
  try {
    await objectStorageApi.createBucket({
      clusterId: clusterId.value,
      tenantId: createForm.value.tenantId,
      name: createForm.value.name.trim(),
      displayName: createForm.value.displayName.trim() || undefined,
      quotaBytes: createForm.value.quotaSize
        ? Math.round(createForm.value.quotaSize * UNIT_BYTES[createForm.value.quotaUnit])
        : undefined,
    })
    createVisible.value = false
    ElMessage.success('桶已创建（记住桶名，应用侧需要它）')
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '建桶失败')
  } finally {
    creating.value = false
  }
}

async function removeBucket(row: ObjectStorageBucket): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除桶「${row.bucket}」？\n桶里还有对象时会失败（S3 不允许删除非空桶）；桶上的凭据会一并吊销。`,
      '删除桶',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await objectStorageApi.deleteBucket({
      clusterId: clusterId.value, tenantId: scopedTenantId(), bucketId: row.id,
    })
    ElMessage.success('桶已删除')
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------------- 配额（管理员） ----------------

const quotaVisible = ref(false)
const quotaDraft = ref(10)
const quotaSaving = ref(false)

function openQuota(): void {
  if (!quota.value) {
    ElMessage.warning('请先在右上角选择要查看的租户')
    return
  }
  quotaDraft.value = quota.value.maxBuckets
  quotaVisible.value = true
}

async function saveQuota(): Promise<void> {
  if (!quota.value) return
  quotaSaving.value = true
  try {
    await objectStorageApi.setQuota({
      clusterId: clusterId.value, tenantId: quota.value.tenantId, maxBuckets: quotaDraft.value,
    })
    quotaVisible.value = false
    ElMessage.success('上限已保存')
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    quotaSaving.value = false
  }
}

// ---------------- 凭据 ----------------

const credVisible = ref(false)
const credBucket = ref<ObjectStorageBucket | null>(null)
const credentials = ref<ObjectStorageCredential[]>([])
const credLoading = ref(false)
/** 刚发放 / 刚查看的明文（只展示一次，关掉就不在本页保留） */
const revealed = ref<ObjectStorageCredentialSecret | null>(null)

async function openCredentials(row: ObjectStorageBucket): Promise<void> {
  credBucket.value = row
  revealed.value = null
  credVisible.value = true
  await loadCredentials()
}

async function loadCredentials(): Promise<void> {
  if (!credBucket.value) return
  credLoading.value = true
  try {
    credentials.value = (await objectStorageApi.credentials({
      clusterId: clusterId.value, tenantId: scopedTenantId(), bucketId: credBucket.value.id,
    })) ?? []
  } finally {
    credLoading.value = false
  }
}

async function issueCredential(): Promise<void> {
  if (!credBucket.value) return
  try {
    revealed.value = await objectStorageApi.issueCredential({
      clusterId: clusterId.value, tenantId: scopedTenantId(), bucketId: credBucket.value.id,
    })
    ElMessage.success('凭据已生成（secretKey 只显示这一次）')
    await loadCredentials()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发放失败')
  }
}

async function viewCredential(row: ObjectStorageCredential): Promise<void> {
  try {
    revealed.value = await objectStorageApi.viewCredential({
      clusterId: clusterId.value, tenantId: scopedTenantId(), credentialId: row.id,
    })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '读取失败')
  }
}

async function revokeCredential(row: ObjectStorageCredential): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `吊销凭据 ${row.accessKey}？\n吊销后 S3 网关会滚动一次（秒级），使用它的应用会立即开始 403。`,
      '吊销凭据', { type: 'warning', confirmButtonText: '吊销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await objectStorageApi.revokeCredential({
      clusterId: clusterId.value, tenantId: scopedTenantId(), credentialId: row.id,
    })
    ElMessage.success('已吊销')
    revealed.value = null
    await loadCredentials()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '吊销失败')
  }
}

async function copy(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    // http 内网部署下 clipboard 不可用，退到 execCommand
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
    ElMessage.success('已复制')
  }
}

// ---------------- 对象浏览（只读，文件浏览器式：点桶下钻） ----------------

/** null = 正在看桶列表；非 null = 已进入该桶 */
const objBucket = ref<ObjectStorageBucket | null>(null)
/** 当前目录前缀（"" = 桶根；否则以 / 结尾） */
const objPrefix = ref('')
const objItems = ref<ObjectStorageObjectItem[]>([])
/** 当前层的子目录（S3 CommonPrefixes） */
const objFolders = ref<string[]>([])
const objLoading = ref(false)
/** 分页游标栈：第 0 页是空游标 */
const objCursors = ref<string[]>([''])
const objPage = ref(0)
const objNext = ref<string | null>(null)
const downloading = ref('')

type ObjRow =
  | { kind: 'folder'; key: string }
  | { kind: 'file'; key: string; size: number; lastModified?: string | null }

/** 目录在前、文件在后；两者共用一张表，才像文件浏览器 */
const objRows = computed<ObjRow[]>(() => [
  ...objFolders.value.map((key) => ({ kind: 'folder' as const, key })),
  ...objItems.value.map((i) => ({
    kind: 'file' as const, key: i.key, size: i.size, lastModified: i.lastModified,
  })),
])

function openObjects(row: ObjectStorageBucket): void {
  objBucket.value = row
  enterPrefix('')
}

function closeObjects(): void {
  objBucket.value = null
  objItems.value = []
  objFolders.value = []
}

/** 进入某个目录：重置分页并从该前缀重新列 */
function enterPrefix(prefix: string): void {
  objPrefix.value = prefix
  objCursors.value = ['']
  objPage.value = 0
  void loadObjects()
}

/** 面包屑：桶名 / a / b / c；每段都能点回去 */
const objCrumbs = computed(() => {
  const parts = objPrefix.value.split('/').filter(Boolean)
  return parts.map((name, i) => ({ name, prefix: parts.slice(0, i + 1).join('/') + '/' }))
})

/** 上一级（到桶根时禁用） */
function upOne(): void {
  const parts = objPrefix.value.split('/').filter(Boolean)
  parts.pop()
  enterPrefix(parts.length ? parts.join('/') + '/' : '')
}

/** 当前层显示的名字：去掉当前前缀与结尾的 / */
function baseName(key: string): string {
  const rel = objPrefix.value && key.startsWith(objPrefix.value) ? key.slice(objPrefix.value.length) : key
  return rel.replace(/\/$/, '')
}

// ---------------- 上传 / 删除（写操作：租户可写自己名下的桶） ----------------

/** 与后端 platform.object-storage.max-upload-mb 对齐；前端先挡一次，避免白传 */
const MAX_UPLOAD_MB = 100
const canWrite = computed(() => perm.has('tenant:object-storage:write'))
const uploading = ref(false)
const uploadPercent = ref(0)
const fileInput = ref<HTMLInputElement | null>(null)

function pickFile(): void {
  fileInput.value?.click()
}

async function onFilePicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 清空才能连续选同一个文件
  if (!file || !objBucket.value) return
  if (file.size > MAX_UPLOAD_MB * 1024 * 1024) {
    ElMessage.error(`单个文件不能超过 ${MAX_UPLOAD_MB}MB，更大的请用 SDK/客户端直连`)
    return
  }
  uploading.value = true
  uploadPercent.value = 0
  try {
    await objectStorageApi.uploadObject(
      {
        bucketId: objBucket.value.id,
        // 上传到"当前目录"：key = 当前前缀 + 文件名（S3 没有目录，前缀就是目录）
        key: objPrefix.value + file.name,
        size: file.size,
        contentType: file.type || undefined,
        tenantId: scopedTenantId(),
      },
      file,
      (percent) => { uploadPercent.value = percent },
    )
    ElMessage.success(`已上传 ${file.name}`)
    await loadObjects()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '上传失败')
  } finally {
    uploading.value = false
  }
}

async function removeObject(row: { key: string }): Promise<void> {
  if (!objBucket.value) return
  try {
    await ElMessageBox.confirm(`删除对象「${baseName(row.key)}」？删除后不可恢复。`, '删除对象',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    await objectStorageApi.deleteObject({
      bucketId: objBucket.value.id, key: row.key, tenantId: scopedTenantId(),
    })
    ElMessage.success('已删除')
    await loadObjects()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function loadObjects(): Promise<void> {
  if (!objBucket.value) return
  objLoading.value = true
  try {
    const page = await objectStorageApi.objects({
      clusterId: clusterId.value,
      tenantId: scopedTenantId(),
      bucketId: objBucket.value.id,
      prefix: objPrefix.value || undefined,
      after: objCursors.value[objPage.value] || undefined,
      maxKeys: 200,
      // 按目录列：当前层的文件进 items、子目录进 folders（S3 CommonPrefixes）
      delimiter: '/',
    })
    objItems.value = page?.items ?? []
    objFolders.value = page?.folders ?? []
    objNext.value = page?.nextToken ?? null
  } catch (e) {
    objItems.value = []
    objFolders.value = []
    objNext.value = null
    ElMessage.error(e instanceof Error ? e.message : '列对象失败')
  } finally {
    objLoading.value = false
  }
}

function nextPage(): void {
  if (!objNext.value) return
  objCursors.value = objCursors.value.slice(0, objPage.value + 1)
  objCursors.value.push(objNext.value)
  objPage.value += 1
  void loadObjects()
}

function prevPage(): void {
  if (objPage.value === 0) return
  objPage.value -= 1
  void loadObjects()
}

async function downloadObject(row: { key: string }): Promise<void> {
  if (!objBucket.value) return
  downloading.value = row.key
  try {
    const blob = await objectStorageApi.downloadObject({
      clusterId: clusterId.value, tenantId: scopedTenantId(), bucketId: objBucket.value.id, key: row.key,
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = baseName(row.key).split('/').pop() || 'download'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '下载失败')
  } finally {
    downloading.value = ''
  }
}

/** 桶的 S3 地址（给应用用，展示用）：真实入口以组件声明的「S3 对外地址」为准；平台自己连 S3 走集群管理里的「对象存储地址」 */
const s3Hint = computed(() => 'seaweedfs-s3.platform-storage:8333')

// ---------------- 修改已有桶的容量上限（管理员） ----------------

const quotaEditVisible = ref(false)
const quotaEditBucket = ref<ObjectStorageBucket | null>(null)
const quotaEditForm = ref<{ size: number | null; unit: 'MB' | 'GB' | 'TB' }>({ size: null, unit: 'GB' })

/** 把字节反推成「数量 + 单位」，让编辑框里是 1 GB 而不是 1073741824 */
function bytesToSizeUnit(bytes?: number | null): { size: number | null; unit: 'MB' | 'GB' | 'TB' } {
  if (!bytes || bytes <= 0) return { size: null, unit: 'GB' }
  if (bytes % UNIT_BYTES.TB === 0) return { size: bytes / UNIT_BYTES.TB, unit: 'TB' }
  if (bytes % UNIT_BYTES.GB === 0) return { size: bytes / UNIT_BYTES.GB, unit: 'GB' }
  return { size: Math.round(bytes / UNIT_BYTES.MB), unit: 'MB' }
}

function openQuotaEdit(row: ObjectStorageBucket): void {
  quotaEditBucket.value = row
  quotaEditForm.value = bytesToSizeUnit(row.quotaBytes)
  quotaEditVisible.value = true
}

async function submitQuotaEdit(): Promise<void> {
  if (!quotaEditBucket.value) return
  const size = quotaEditForm.value.size
  const quotaBytes = size && size > 0
    ? Math.round(size * UNIT_BYTES[quotaEditForm.value.unit])
    : null
  try {
    await objectStorageApi.setBucketQuota({
      bucketId: quotaEditBucket.value.id, tenantId: scopedTenantId(), quotaBytes,
    })
    quotaEditVisible.value = false
    ElMessage.success(quotaBytes ? '容量上限已更新' : '已取消容量上限')
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '设置失败')
  }
}

onMounted(async () => {
  await loadClusters()
  await loadTenants()
  await refresh()
})
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">对象存储</h2>
        <p class="ph-desc">
          S3 桶与访问凭据（SeaweedFS 集群组件）；应用侧用 endpoint + 桶名 + AK/SK 直连，
          <code>{{ s3Hint }}</code>
        </p>
      </div>
      <div class="ph-actions">
        <el-select v-model="clusterId" placeholder="选择集群" style="width: 200px" @change="refresh">
          <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
        </el-select>
        <el-select v-if="perm.isAdmin" v-model="tenantFilter" clearable placeholder="全部租户"
                   style="width: 170px" @change="refresh">
          <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-button :disabled="!clusterId" @click="refresh">刷新</el-button>
        <el-button v-if="perm.isAdmin && quota && !objBucket" @click="openQuota">
          配额 {{ quota.used }}/{{ quota.maxBuckets }}
        </el-button>
        <el-button v-if="perm.isAdmin && !objBucket" type="primary" :disabled="!clusterId" @click="openCreate">
          创建桶
        </el-button>
      </div>
    </div>

    <p v-if="perm.isAdmin && !tenantFilter && !objBucket" class="os-hint">
      当前显示全部租户的桶。要设置配额或代建桶，请先在右上角选择租户。
    </p>

    <el-table v-if="!objBucket" :data="buckets" class="panel" empty-text="还没有桶（管理员可在右上角创建）">
      <el-table-column label="桶名" min-width="240">
        <template #default="{ row }">
          <div class="os-bucket">
            <el-button link type="primary" @click="openObjects(row)">{{ row.bucket }}</el-button>
            <span v-if="row.displayName && row.displayName !== row.bucket" class="os-bucket-alias">
              {{ row.displayName }}
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="当前大小" width="180">
        <template #default="{ row }">
          <el-tooltip :content="statTip(row)" placement="top">
            <span>{{ statText(row) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column v-if="perm.isAdmin && !tenantFilter" label="租户" width="160">
        <template #default="{ row }">{{ tenantName(row.tenantId) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="凭据" width="90">
        <template #default="{ row }">{{ row.credentialCount }}</template>
      </el-table-column>
      <el-table-column prop="createdBy" label="创建人" width="120" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="270" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openObjects(row)">浏览</el-button>
          <el-button link type="primary" @click="openCredentials(row)">凭据</el-button>
          <el-button v-if="perm.isAdmin" link type="primary" @click="openQuotaEdit(row)">配额</el-button>
          <el-button v-if="perm.isAdmin" link type="danger" @click="removeBucket(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ============ 对象浏览（只读，文件浏览器式：点桶下钻） ============ -->
    <div v-else class="panel os-browser">
      <div class="os-browser-bar">
        <el-button link type="primary" @click="closeObjects">← 返回桶列表</el-button>
        <el-breadcrumb separator="/" class="os-crumbs">
          <el-breadcrumb-item>
            <el-button link type="primary" @click="enterPrefix('')">{{ objBucket.bucket }}</el-button>
          </el-breadcrumb-item>
          <el-breadcrumb-item v-for="c in objCrumbs" :key="c.prefix">
            <el-button link type="primary" @click="enterPrefix(c.prefix)">{{ c.name }}</el-button>
          </el-breadcrumb-item>
        </el-breadcrumb>
        <div class="os-browser-actions">
          <el-button v-if="canWrite" size="small" type="primary" :loading="uploading" @click="pickFile">
            {{ uploading ? `上传中 ${uploadPercent}%` : '上传' }}
          </el-button>
          <el-button size="small" :disabled="!objPrefix" @click="upOne">上一级</el-button>
          <el-button size="small" @click="loadObjects">刷新</el-button>
        </div>
      </div>
      <input ref="fileInput" type="file" hidden @change="onFilePicked" />

      <el-table v-loading="objLoading" :data="objRows" size="small" empty-text="这个目录是空的">
        <el-table-column label="名称" min-width="420">
          <template #default="{ row }">
            <span v-if="row.kind === 'folder'" class="os-folder" @click="enterPrefix(row.key)">
              📁 {{ baseName(row.key) }}
            </span>
            <span v-else class="os-file">{{ baseName(row.key) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ row.kind === 'folder' ? '—' : humanizeBytes(row.size) }}</template>
        </el-table-column>
        <el-table-column label="修改时间" width="200">
          <template #default="{ row }">{{ row.kind === 'folder' ? '—' : row.lastModified }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <template v-if="row.kind === 'file'">
              <el-button link type="primary"
                         :loading="downloading === row.key" @click="downloadObject(row)">下载</el-button>
              <el-button v-if="canWrite" link type="danger" @click="removeObject(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div class="os-obj-page">
        <el-button size="small" :disabled="objPage === 0" @click="prevPage">上一页</el-button>
        <span class="os-form-help">第 {{ objPage + 1 }} 页</span>
        <el-button size="small" :disabled="!objNext" @click="nextPage">下一页</el-button>
        <span class="os-form-help">单次上传/下载 ≤100MB，超过请用 SDK 直连；删除只针对单个对象</span>
      </div>
    </div>

    <!-- ============ 创建桶 ============ -->
    <el-dialog v-model="createVisible" title="创建桶" width="560px" :close-on-click-modal="false">
      <el-form label-width="110px" label-position="left" class="os-form">
        <el-form-item label="归属租户" required>
          <el-select v-model="createForm.tenantId" filterable placeholder="选择租户" style="width: 100%">
            <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="桶名" required>
          <el-input v-model="createForm.name" placeholder="例如 logs、backup" spellcheck="false" />
          <div class="os-form-help">
            最终桶名会加上租户前缀：<code>t-&lt;租户短码&gt;-{{ createForm.name || '名字' }}</code>；
            只能用 3~30 位小写字母/数字/连字符
          </div>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createForm.displayName" placeholder="可选，只用于平台展示" spellcheck="false" />
        </el-form-item>
        <el-form-item label="容量上限">
          <div class="os-quota-row">
            <el-input-number v-model="createForm.quotaSize" :min="1" :max="1048576"
                             :controls="false" placeholder="留空 = 不限" style="width: 170px" />
            <el-select v-model="createForm.quotaUnit" style="width: 96px">
              <el-option label="MB" value="MB" />
              <el-option label="GB" value="GB" />
              <el-option label="TB" value="TB" />
            </el-select>
          </div>
          <div class="os-form-help">
            由 SeaweedFS 按桶执行（每分钟检查）：超限后写入返回 403，删除仍允许；
            清出空间后自动恢复可写。留空 = 不限
          </div>
        </el-form-item>
      </el-form>
      <p v-if="quota" class="os-form-help">
        当前租户已用 {{ quota.used }}/{{ quota.maxBuckets }} 个桶
      </p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============ 修改桶容量上限 ============ -->
    <el-dialog v-model="quotaEditVisible" title="容量上限" width="520px">
      <p class="os-form-help" style="margin: 0 0 12px">
        桶 <code>{{ quotaEditBucket?.bucket }}</code> 的容量上限；留空 = 不限。
        由 SeaweedFS 每分钟检查：超限后写入返回 403（删除仍允许），清出空间后自动恢复。
      </p>
      <div class="os-quota-row">
        <el-input-number v-model="quotaEditForm.size" :min="1" :max="1048576"
                         :controls="false" placeholder="留空 = 不限" style="width: 180px" />
        <el-select v-model="quotaEditForm.unit" style="width: 96px">
          <el-option label="MB" value="MB" />
          <el-option label="GB" value="GB" />
          <el-option label="TB" value="TB" />
        </el-select>
      </div>
      <template #footer>
        <el-button @click="quotaEditVisible = false">取消</el-button>
        <el-button type="primary" @click="submitQuotaEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 配额 ============ -->
    <el-dialog v-model="quotaVisible" title="桶数上限" width="460px">
      <p class="os-form-help">
        租户：<strong>{{ quota ? tenantName(quota.tenantId) : '' }}</strong>；
        已用 {{ quota?.used ?? 0 }} 个
      </p>
      <el-input-number v-model="quotaDraft" :min="0" :max="100" controls-position="right" />
      <span class="os-form-help">个桶</span>
      <template #footer>
        <el-button @click="quotaVisible = false">取消</el-button>
        <el-button type="primary" :loading="quotaSaving" @click="saveQuota">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 凭据 ============ -->
    <el-dialog v-model="credVisible" width="720px"
               :title="`访问凭据 · ${credBucket?.bucket ?? ''}`">
      <div v-if="revealed" class="os-secret">
        <div class="os-secret-title">请立即复制保存（关闭后不再显示）</div>
        <div class="os-secret-row">
          <span class="os-secret-label">Access Key</span>
          <code>{{ revealed.accessKey }}</code>
          <el-button link type="primary" @click="copy(revealed.accessKey)">复制</el-button>
        </div>
        <div class="os-secret-row">
          <span class="os-secret-label">Secret Key</span>
          <code>{{ revealed.secretKey }}</code>
          <el-button link type="primary" @click="copy(revealed.secretKey ?? '')">复制</el-button>
        </div>
      </div>
      <el-table v-loading="credLoading" :data="credentials" size="small" empty-text="还没有凭据">
        <el-table-column prop="accessKey" label="Access Key" min-width="200" />
        <el-table-column prop="createdBy" label="创建人" width="120" />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewCredential(row)">查看密钥</el-button>
            <el-button v-if="perm.isAdmin" link type="danger" @click="revokeCredential(row)">吊销</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="os-form-help">
        每桶一组凭据，只能读写这一个桶（S3 的 bucket-scoped 权限）。吊销/发放会让 S3 网关滚动一次（秒级）。
      </p>
      <template #footer>
        <el-button @click="credVisible = false">关闭</el-button>
        <el-button v-if="perm.isAdmin" type="primary" @click="issueCredential">生成新凭据</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.ph-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--text-1); }
.ph-desc { margin: 4px 0 0; font-size: 12.5px; color: var(--text-3); }
.ph-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.os-hint { margin: 0 0 10px; font-size: 12.5px; color: var(--text-3); }
.os-bucket { display: flex; align-items: baseline; gap: 8px; }
.os-quota-row { display: flex; gap: 8px; align-items: center; }
.os-bucket-alias { font-size: 12px; color: var(--text-3); }
.os-form-help { margin-top: 4px; font-size: 12px; color: var(--text-3); line-height: 1.5; }
.os-form :deep(.el-form-item__label) { justify-content: flex-start; text-align: left; }
.os-secret {
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-strong, var(--border));
  border-radius: 8px;
  background: var(--bg-2, rgba(0, 0, 0, 0.03));
}
.os-secret-title { font-size: 12.5px; font-weight: 600; margin-bottom: 8px; }
.os-secret-row { display: flex; align-items: center; gap: 10px; padding: 3px 0; }
.os-secret-label { font-size: 12px; color: var(--text-3); width: 90px; }
.os-secret-row code { word-break: break-all; }
.os-obj-page { display: flex; align-items: center; gap: 10px; margin-top: 10px; }

/* 对象浏览：文件浏览器式下钻 */
.os-browser { padding: 12px 16px 16px; }
.os-browser-bar { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; }
.os-crumbs { flex: 1; min-width: 0; }
.os-crumbs :deep(.el-breadcrumb__item) { display: inline-flex; }
.os-browser-actions { display: flex; gap: 8px; }
.os-folder { cursor: pointer; color: var(--accent); }
.os-folder:hover { text-decoration: underline; }
.os-file { color: var(--text-1); }
</style>
