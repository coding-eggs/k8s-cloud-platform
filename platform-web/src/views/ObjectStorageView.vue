<script setup lang="ts">
/**
 * 对象存储（SeaweedFS 集群组件）。
 *
 * <p>租户视图：只看自己名下的桶 + 桶里的对象（只读）+ 自己的凭据。
 * 管理员视图：全部租户的桶、代建桶、设置每租户的桶数上限、发放/吊销凭据。
 *
 * <p>对象是**只读**的（v1 不做上传/删除）；下载经平台代理（S3 网关只在集群内），
 * 单次上限 100MB，超过请用 SDK 直连。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, objectStorageApi, resourceContextApi, tenantApi } from '@/api'
import { usePermission } from '@/stores/permission'
import { humanizeBytes } from '@/utils/metrics'
import type {
  K8sCluster, ObjectStorageBucket, ObjectStorageCredential, ObjectStorageCredentialSecret,
  ObjectStorageObjectItem, ObjectStorageQuota, PlatformTenant,
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
  } finally {
    loading.value = false
  }
}

// ---------------- 建桶（管理员） ----------------

const createVisible = ref(false)
const creating = ref(false)
const createForm = ref({ tenantId: '', name: '', displayName: '' })

function openCreate(): void {
  createForm.value = { tenantId: tenantFilter.value || '', name: '', displayName: '' }
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

// ---------------- 对象浏览（只读） ----------------

const objectsVisible = ref(false)
const objBucket = ref<ObjectStorageBucket | null>(null)
const objPrefix = ref('')
const objItems = ref<ObjectStorageObjectItem[]>([])
const objLoading = ref(false)
/** 分页游标栈：第 0 页是空游标 */
const objCursors = ref<string[]>([''])
const objPage = ref(0)
const objNext = ref<string | null>(null)
const downloading = ref('')

function openObjects(row: ObjectStorageBucket): void {
  objBucket.value = row
  objPrefix.value = ''
  objCursors.value = ['']
  objPage.value = 0
  objectsVisible.value = true
  void loadObjects()
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
    })
    objItems.value = page?.items ?? []
    objNext.value = page?.nextToken ?? null
  } catch (e) {
    objItems.value = []
    objNext.value = null
    ElMessage.error(e instanceof Error ? e.message : '列对象失败')
  } finally {
    objLoading.value = false
  }
}

function applyPrefix(): void {
  objCursors.value = ['']
  objPage.value = 0
  void loadObjects()
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

/** 列表里显示相对前缀的 key（前缀本身已在输入框里） */
function relKey(key: string): string {
  return objPrefix.value && key.startsWith(objPrefix.value) ? key.slice(objPrefix.value.length) : key
}

async function downloadObject(row: ObjectStorageObjectItem): Promise<void> {
  if (!objBucket.value) return
  downloading.value = row.key
  try {
    const blob = await objectStorageApi.downloadObject({
      clusterId: clusterId.value, tenantId: scopedTenantId(), bucketId: objBucket.value.id, key: row.key,
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = relKey(row.key).split('/').pop() || 'download'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '下载失败')
  } finally {
    downloading.value = ''
  }
}

/** 桶的 S3 地址（给应用用）：展示用，真实地址以组件安装时的 endpoint 为准 */
const s3Hint = computed(() => 'seaweedfs-s3.platform-storage:8333')

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
        <el-button v-if="perm.isAdmin && quota" @click="openQuota">
          配额 {{ quota.used }}/{{ quota.maxBuckets }}
        </el-button>
        <el-button v-if="perm.isAdmin" type="primary" :disabled="!clusterId" @click="openCreate">
          创建桶
        </el-button>
      </div>
    </div>

    <p v-if="perm.isAdmin && !tenantFilter" class="os-hint">
      当前显示全部租户的桶。要设置配额或代建桶，请先在右上角选择租户。
    </p>

    <el-table :data="buckets" class="panel" empty-text="还没有桶（管理员可在右上角创建）">
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
      <el-table-column v-if="perm.isAdmin && !tenantFilter" label="租户" width="160">
        <template #default="{ row }">{{ tenantName(row.tenantId) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="凭据" width="90">
        <template #default="{ row }">{{ row.credentialCount }}</template>
      </el-table-column>
      <el-table-column prop="createdBy" label="创建人" width="120" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openObjects(row)">浏览</el-button>
          <el-button link type="primary" @click="openCredentials(row)">凭据</el-button>
          <el-button v-if="perm.isAdmin" link type="danger" @click="removeBucket(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

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
      </el-form>
      <p v-if="quota" class="os-form-help">
        当前租户已用 {{ quota.used }}/{{ quota.maxBuckets }} 个桶
      </p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
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

    <!-- ============ 对象浏览（只读） ============ -->
    <el-dialog v-model="objectsVisible" width="900px"
               :title="`对象 · ${objBucket?.bucket ?? ''}`">
      <div class="os-obj-bar">
        <el-input v-model="objPrefix" placeholder="前缀（输入目录前缀可只看该目录，如 logs/2026/）"
                  spellcheck="false" @keyup.enter="applyPrefix" />
        <el-button @click="applyPrefix">应用</el-button>
      </div>
      <el-table v-loading="objLoading" :data="objItems" size="small" empty-text="没有对象">
        <el-table-column label="名称" min-width="360">
          <template #default="{ row }"><code>{{ relKey(row.key) }}</code></template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ humanizeBytes(row.size) }}</template>
        </el-table-column>
        <el-table-column prop="lastModified" label="修改时间" width="200" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="downloading === row.key"
                       @click="downloadObject(row)">下载</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="os-obj-page">
        <el-button size="small" :disabled="objPage === 0" @click="prevPage">上一页</el-button>
        <span class="os-form-help">第 {{ objPage + 1 }} 页</span>
        <el-button size="small" :disabled="!objNext" @click="nextPage">下一页</el-button>
        <span class="os-form-help">只读；单次下载 ≤100MB，超过请用 SDK 直连</span>
      </div>
      <template #footer>
        <el-button @click="objectsVisible = false">关闭</el-button>
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
.os-obj-bar { display: flex; gap: 8px; margin-bottom: 10px; }
.os-obj-page { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
</style>
