<script setup lang="ts">
/**
 * 镜像仓库页（spec §3.3）：左栏是「仓库」（一个 host 一行），右栏是选中仓库的
 * 连接 / 项目 / 浏览三块。
 *
 * ⚠️ 本页不做任何 host 归一或来源判定 —— 那两件事都在后端（RegistryService.warehouses）。
 * 前端再做一遍就是第二个真相源。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { credentialApi, registryApi } from '@/api'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import type {
  CredentialView, K8sRegistryProject, RegistryBrowseResult, RegistryNode, RegistryWarehouse,
} from '@/types'
import { writeClipboard } from '@/utils/clipboard'
import { fmtDate } from '@/utils/format'

const perm = usePermission()

const warehouses = ref<RegistryWarehouse[]>([])
const selectedId = ref('')
const connections = ref<CredentialView[]>([])
const projects = ref<K8sRegistryProject[]>([])
const path = ref<string[]>([])
const result = ref<RegistryBrowseResult | null>(null)

const loading = ref(false)
const detailLoading = ref(false)
/** 与「这一层是空的」区分开 —— 浏览失败必须让用户看见 */
const error = ref('')

const selected = computed(() => warehouses.value.find((w) => w.id === selectedId.value) ?? null)

/**
 * 选中的仓库下、当前用户可见的连接（后端已按可见性过滤，这里只按 host 分组）。
 * ⚠️ 必须排除 GIT —— 同主机上的 Git 连接与镜像仓库无关。
 */
const selectedConnections = computed(() =>
  connections.value.filter(
    (c) => c.type !== 'GIT' && c.host === (selected.value?.host ?? ''),
  ))

/** 浏览用哪条连接：优先项目级 robot（它按项目收窄），否则第一条启用的 */
const browseCredential = computed(() =>
  selectedConnections.value.find((c) => c.enabled && c.source === 'HARBOR')
  ?? selectedConnections.value.find((c) => c.enabled)
  ?? null)

/**
 * 能不能改：外部连接看凭据写权限 —— **两个码 ANY-of**。
 * 租户角色不能持 platform: 码，只认 platform:credential:write 会把租户管理员的按钮一直灰着。
 */
const canWriteConnections = computed(() =>
  perm.hasAny([apiCodes.credentialWrite, apiCodes.credentialWriteTenant]))
/** Harbor 租户项目的同步 / 重建只给平台管理员 */
const canManageProjects = computed(() => perm.has(apiCodes.registryProjectManage))
/** 平台 Harbor：地址固定、连接是同步发出来的 robot，不接受手工新建 */
const selectedIsInstalled = computed(() => selected.value?.origin === 'INSTALLED')

/** 连接表单：新建与编辑共用。id 为空 = 新建 */
const form = reactive({
  visible: false,
  id: '',
  name: '',
  type: 'HARBOR' as 'HARBOR' | 'DOCKERHUB',
  endpoint: '',
  /** 认证方式：Harbor 支持匿名；Docker Hub 只接受用户名+令牌（方言决定） */
  authType: 'BASIC' as 'BASIC' | 'NONE',
  username: '',
  secret: '',
  description: '',
  enabled: true,
  usableByPipelines: false,
})
const isEdit = computed(() => !!form.id)

function openCreate(): void {
  Object.assign(form, {
    visible: true,
    id: '',
    name: '',
    // 平台 Harbor 那条走不到这里（按钮不出现）；能新建的一定是外部仓库
    type: selected.value?.kind === 'DOCKER' ? 'DOCKERHUB' : 'HARBOR',
    endpoint: selected.value?.kind === 'DOCKER' ? 'docker.io' : '',
    authType: 'BASIC',
    username: '',
    secret: '',
    description: '',
    enabled: true,
    usableByPipelines: false,
  })
}

function openEdit(row: CredentialView): void {
  Object.assign(form, {
    visible: true,
    id: row.id,
    name: row.name,
    // form.type 只有两档，收窄一次（本表里本来就不会有 GIT）
    type: row.type === 'DOCKERHUB' ? 'DOCKERHUB' : 'HARBOR',
    endpoint: row.endpoint,
    // ⚠️ 必须回填：写死 BASIC 会把匿名连接悄悄改成"必须有口令"，
    //    用户只改了个说明就保存失败，而且页面上没有任何地方能切回匿名
    authType: row.authType,
    username: '',
    secret: '',
    description: row.description ?? '',
    enabled: row.enabled,
    usableByPipelines: !!row.usableByPipelines,
  })
}

async function save(): Promise<void> {
  const authType = form.type === 'DOCKERHUB' ? 'BASIC' as const : form.authType
  try {
    const body = {
      name: form.name.trim(),
      type: form.type,
      endpoint: form.endpoint.trim(),
      authType,
      // 匿名时根本不发这两个字段（后端会拒「匿名 + 带凭据」）
      username: authType === 'NONE' ? undefined : form.username || undefined,
      secret: authType === 'NONE' ? undefined : form.secret || undefined,
      description: form.description.trim() || null,
      enabled: form.enabled,
      usableByPipelines: form.usableByPipelines,
    }
    if (isEdit.value) await credentialApi.update({ ...body, id: form.id })
    else await credentialApi.create(body)
    ElMessage.success('已保存')
    form.visible = false
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 能不能测这条连接：管理员随便测；租户只能测自己租户的行（后端同一口径） */
function canTest(row: CredentialView): boolean {
  return canManageProjects.value || !!row.tenantId
}

async function testConn(row: CredentialView): Promise<void> {
  try {
    const r = await credentialApi.test(row.id)
    if (r.ok) ElMessage.success('连接成功')
    else ElMessage.error(r.suggestedUrl ? `${r.message}（建议改用 ${r.suggestedUrl}）` : r.message)
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(row: CredentialView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除连接「${row.name}」？被流水线引用时会直接失效（硬删除，密文一并消失）。`,
      '确认删除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await credentialApi.remove(row.id)
    ElMessage.success('已删除')
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function syncAll(): Promise<void> {
  const clusterId = selected.value?.clusterId
  if (!clusterId) return
  try {
    const r = await registryApi.syncProjects(clusterId)
    if (r.failed > 0) ElMessage.warning(`同步完成，${r.failed} 个租户失败（见项目表）`)
    else ElMessage.success(`同步完成（${r.items.length} 项）`)
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function rebuild(row: K8sRegistryProject): Promise<void> {
  const clusterId = selected.value?.clusterId
  if (!clusterId) return
  try {
    await ElMessageBox.confirm(
      `重建「${row.tenantName}」的 Harbor 机器人账号？旧账号会被删除，凭据密文会覆盖。`,
      '确认重建', { type: 'warning' })
  } catch {
    return
  }
  try {
    const item = await registryApi.rebuildProject(clusterId, row.tenantId ?? null)
    if (item.status === 'READY') ElMessage.success('已重建')
    else ElMessage.error(item.message || '重建失败')
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function loadWarehouses(): Promise<void> {
  loading.value = true
  try {
    warehouses.value = await registryApi.warehouses()
    const first = warehouses.value[0]
    if (first && !selectedId.value) selectedId.value = first.id
  } catch (e) {
    ElMessage.error((e as Error).message)
    warehouses.value = []
  } finally {
    loading.value = false
  }
}

async function loadDetail(): Promise<void> {
  const w = selected.value
  if (!w) return
  detailLoading.value = true
  error.value = ''
  result.value = null
  path.value = []

  // 三块分属三个权限域（tenant:credential:read / tenant:registry:project:read /
  // tenant:registry:catalog），各自 try/catch —— 一处 403 不能连带打死另外两块。
  try {
    connections.value = await credentialApi.list()
  } catch (e) {
    connections.value = []
    error.value = (e as Error).message
  }
  try {
    projects.value = w.clusterId ? await registryApi.projects(w.clusterId) : []
  } catch {
    // 项目台账是附加信息：租户没有平台 Harbor 的项目时它就是空，不该挡住浏览
    projects.value = []
  }
  const id = browseId()
  if (id) {
    try {
      result.value = await registryApi.browse(id, [])
    } catch (e) {
      error.value = (e as Error).message
    }
  }
  detailLoading.value = false
}

/** 当前浏览用的来源 id；没有可用来源（例如装了 Harbor 但一条凭据都没有）时为空串 */
function browseId(): string {
  const c = browseCredential.value
  if (c) return c.id
  return selected.value?.origin === 'BUILTIN' ? selectedId.value : ''
}

/** 有没有东西可浏览 —— 没有就别渲染浏览块，免得点一下报「连接不存在」 */
const canBrowse = computed(() => !!browseId())

async function browse(id: string, p: string[]): Promise<void> {
  path.value = p
  detailLoading.value = true
  error.value = ''
  try {
    result.value = await registryApi.browse(id, p)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    detailLoading.value = false
  }
}

function drillInto(node: RegistryNode): void {
  if (!node.hasChildren) return
  void browse(browseId(), [...path.value, node.name])
}

function goTo(p: string[]): void {
  void browse(browseId(), p)
}

async function copyImage(node: RegistryNode): Promise<void> {
  if (!node.copyText) return
  const ok = await writeClipboard(node.copyText)
  if (ok) ElMessage.success('已复制镜像地址')
  else ElMessage.warning('当前浏览器不允许自动复制，请手动选中复制')
}

watch(selectedId, () => void loadDetail())
onMounted(loadWarehouses)
</script>

<template>
  <div class="registry-page">
    <div class="page-head"><h2>镜像仓库</h2></div>

    <div class="registry-body" v-loading="loading">
      <aside class="wh-list">
        <div v-for="w in warehouses" :key="w.id"
             class="wh-item" :class="{ active: w.id === selectedId }"
             @click="selectedId = w.id">
          <div class="wh-name">
            {{ w.displayName }}
            <el-tag size="small"
                    :type="w.origin === 'INSTALLED' ? 'primary'
                      : w.origin === 'BUILTIN' ? 'info' : 'warning'">
              {{ w.origin === 'INSTALLED' ? '平台' : w.origin === 'BUILTIN' ? '公共' : '外部' }}
            </el-tag>
          </div>
          <div class="wh-host">{{ w.host }}</div>
          <div class="wh-meta">
            <span>{{ w.kind === 'DOCKER' ? 'Docker' : 'Harbor' }}</span>
            <span v-if="w.connectionCount">{{ w.connectionCount }} 个连接</span>
            <span v-if="w.projectCount">{{ w.projectCount }} 个项目</span>
            <el-tag v-if="w.health === 'ERROR'" size="small" type="danger">异常</el-tag>
          </div>
        </div>
        <el-empty v-if="!loading && !warehouses.length" description="还没有可用的镜像仓库" />
      </aside>

      <section class="wh-detail" v-loading="detailLoading">
        <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

        <template v-if="selected">
          <div class="block-head">
            <h3>连接</h3>
            <div>
              <!-- 平台 Harbor 的连接是同步发出来的 robot，不能手工新建 -->
              <el-button v-if="canWriteConnections && !selectedIsInstalled"
                         type="primary" size="small" @click="openCreate">新建连接</el-button>
              <el-button v-if="canManageProjects && selectedIsInstalled"
                         size="small" @click="syncAll">同步全部</el-button>
            </div>
          </div>
          <el-table :data="selectedConnections" size="small" stripe>
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column label="归属" width="120">
              <template #default="{ row }">{{ row.tenantName || '平台级' }}</template>
            </el-table-column>
            <el-table-column label="认证" width="90">
              <template #default="{ row }">{{ row.authType === 'NONE' ? '匿名' : 'Basic' }}</template>
            </el-table-column>
            <el-table-column label="上次验证" min-width="150">
              <template #default="{ row }">
                {{ row.lastVerifiedAt ? fmtDate(row.lastVerifiedAt) : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="流水线可用" width="110">
              <template #default="{ row }">{{ row.usableByPipelines ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="!canTest(row)" @click="testConn(row)">
                  测试
                </el-button>
                <!-- 平台 robot 行只读：换口令走「重建」，不是改表单（后端也会拒） -->
                <el-button link type="primary"
                           :disabled="!canWriteConnections || row.source === 'HARBOR'"
                           @click="openEdit(row)">
                  编辑
                </el-button>
                <el-button link type="danger"
                           :disabled="!canWriteConnections || row.source === 'HARBOR'"
                           @click="remove(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <template v-if="projects.length">
            <h3>项目</h3>
            <el-table :data="projects" size="small" stripe>
              <el-table-column prop="tenantName" label="归属" min-width="120" />
              <el-table-column prop="projectName" label="项目" min-width="160" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'READY' ? 'success' : 'danger'" size="small">
                    {{ row.status === 'READY' ? '就绪' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="上次同步" width="160">
                <template #default="{ row }">{{ row.lastSyncedAt ? fmtDate(row.lastSyncedAt) : '—' }}</template>
              </el-table-column>
              <el-table-column v-if="canManageProjects" label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="rebuild(row)">重建账号</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <template v-if="canBrowse">
            <h3>浏览</h3>
            <div class="crumbs">
              <el-button link @click="goTo([])">{{ selected.displayName }}</el-button>
              <template v-for="(c, i) in result?.breadcrumb ?? []" :key="i">
                <span class="sep">/</span>
                <el-button link @click="goTo(c.path)">{{ c.label }}</el-button>
              </template>
            </div>
            <el-table :data="result?.children ?? []" size="small" stripe>
              <el-table-column label="名称" min-width="220">
                <template #default="{ row }">
                  <el-button v-if="row.hasChildren" link type="primary" @click="drillInto(row)">
                    {{ row.name }}
                  </el-button>
                  <span v-else>{{ row.name }}</span>
                </template>
              </el-table-column>
              <el-table-column v-for="d in result?.children?.[0]?.details ?? []" :key="d.label"
                               :label="d.label" min-width="140">
                <template #default="{ row }">
                  {{ row.details.find((x: { label: string }) => x.label === d.label)?.value ?? '' }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="row.copyText" link type="primary" @click="copyImage(row)">
                    复制地址
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </template>
      </section>
    </div>

    <el-drawer v-model="form.visible" :title="isEdit ? '编辑连接' : '新建连接'" size="480px">
      <el-form label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" :disabled="isEdit" style="width: 100%">
            <!-- 平台 Harbor 不在选项里：它的连接由同步自动创建 -->
            <el-option value="HARBOR" label="Harbor（外部）" />
            <el-option value="DOCKERHUB" label="Docker Hub（私有账号）" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.type === 'HARBOR'" label="地址" required>
          <el-input v-model="form.endpoint" placeholder="harbor.corp.example.com" />
          <div class="tip">只填主机名（可带端口），不要带 https:// 与路径</div>
        </el-form-item>
        <el-form-item v-else label="地址">
          <el-input model-value="docker.io" disabled />
          <div class="tip">Docker Hub 的地址是固定的，不用填</div>
        </el-form-item>
        <el-form-item label="认证方式">
          <!-- Docker Hub 只接受用户名 + 令牌（方言决定），匿名那一份是平台内置档 -->
          <el-select v-if="form.type === 'HARBOR'" v-model="form.authType" style="width: 100%">
            <el-option value="BASIC" label="用户名 + 密码/令牌" />
            <el-option value="NONE" label="匿名（不使用凭据）" />
          </el-select>
          <el-input v-else model-value="用户名 + 令牌" disabled />
        </el-form-item>
        <el-form-item v-if="form.type === 'DOCKERHUB' || form.authType === 'BASIC'" label="用户名">
          <el-input v-model="form.username" :placeholder="isEdit ? '留空 = 保持不变' : 'robot$project+ci / Docker ID'" />
        </el-form-item>
        <el-form-item v-if="form.type === 'DOCKERHUB' || form.authType === 'BASIC'"
                      :label="isEdit ? '密码/令牌（留空不改）' : '密码/令牌'">
          <el-input v-model="form.secret" type="password" show-password />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="允许流水线使用"><el-switch v-model="form.usableByPipelines" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.registry-page {
  padding: 16px;
}
.page-head {
  margin-bottom: 12px;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
}
.registry-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.wh-list {
  width: 280px;
  flex: none;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 12px;
}
.wh-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
}
.wh-item.active {
  background: var(--el-color-primary-light-9);
}
.wh-name {
  font-weight: 600;
  display: flex;
  gap: 8px;
  align-items: center;
}
.wh-host {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 2px;
}
.wh-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
  display: flex;
  gap: 10px;
  align-items: center;
}
.wh-detail {
  flex: 1;
  min-width: 0;
}
.wh-detail h3 {
  margin: 16px 0 8px;
  font-size: 14px;
}
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.block-head h3 {
  margin: 16px 0 8px;
}
.crumbs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.sep {
  color: var(--el-text-color-placeholder);
  margin: 0 2px;
}
</style>

<style>
/* ⚠️ 非 scoped：el-drawer 的内部节点没有 data-v-*，scoped 的 :deep() 匹配不到。
   用 .el-drawer 前缀限制作用域（本仓既有约定）。 */
.el-drawer .tip {
  margin-top: 4px;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
</style>
