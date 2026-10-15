<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { credentialApi } from '@/api'
import type { CredentialTestResult, CredentialView as Credential } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'

const perm = usePermission()

const rows = ref<Credential[]>([])
const loading = ref(false)

/** 表单：新建与编辑共用。id 为空 = 新建 */
const form = reactive({
  visible: false,
  id: '',
  name: '',
  type: 'GIT' as 'GIT' | 'HARBOR' | 'DOCKERHUB',
  endpoint: '',
  username: '',
  secret: '',
  description: '',
  enabled: true,
  /** 允许在流水线里使用（默认关）—— 逐条显式授权，不是权限码 */
  usableByPipelines: false,
})
/** 认证方式。NONE = 匿名（无凭据，出站不带 Authorization 头）—— 后端按类型校验可否 */
const authType = ref<'BASIC' | 'NONE'>('BASIC')

const isEdit = computed(() => !!form.id)

// ⚠️ 这里**没有 Docker Hub** —— 它 2026-10-14 改成了平台的**内置档**（BuiltinRegistry），
// 不经凭据表：没有行、没有 authType、没有密文、没有启停状态，重放迁移后开箱即用。
// 它的方言实现仍在，只是不再由凭据提供。
const TYPE_OPTIONS = [
  { value: 'GIT', label: 'Git 仓库' },
  { value: 'HARBOR', label: 'Harbor 镜像仓库' },
  // ⚠️ 这一项专指**私有**仓库（用户名 + PAT）。公共镜像的来源是平台的**内置档**，
  // 不用配置、也不在这儿 —— 2026-10-15 用户撤销了 2026-10-14 那条「只允许匿名」的裁定
  { value: 'DOCKERHUB', label: 'Docker Hub（私有仓库）' },
]

/** Docker Hub 的地址是恒定的：它没有"你自己的地址"，出站的两个站点由后端写死 */
const DOCKER_HUB = 'DOCKERHUB'
const isDockerHub = computed(() => form.type === DOCKER_HUB)

const AUTH_OPTIONS = [
  { value: 'BASIC', label: '用户名 + 密码/令牌' },
  { value: 'NONE', label: '匿名（不使用凭据）' },
]

/**
 * 这一档能选哪些认证方式。Docker Hub 只有 BASIC —— 匿名的那一份是**内置档**，
 * 在这儿再建一条匿名的，只会多出一个与它一模一样、候选完全重复的来源（后端也会拒）。
 */
const authOptions = computed(() =>
  isDockerHub.value ? AUTH_OPTIONS.filter((o) => o.value === 'BASIC') : AUTH_OPTIONS,
)

/** 换类型时的联动：Docker Hub 的地址恒定、且必须带认证 */
function onTypeChange(v: string): void {
  if (v === DOCKER_HUB) {
    form.endpoint = 'docker.io'
    authType.value = 'BASIC'
  } else if (!form.endpoint.trim() || form.endpoint === 'docker.io') {
    form.endpoint = ''
  }
}

/**
 * 换认证方式时的联动：改成匿名就把已输入的凭据清掉 —— 那些输入框随即隐藏，
 * 留着只会让「保存」报一个用户没法处理的错（见 save 里的 body 注释）。
 */
function onAuthTypeChange(v: 'BASIC' | 'NONE'): void {
  if (v === 'NONE') {
    form.username = ''
    form.secret = ''
  }
}

function typeLabel(t: string): string {
  return TYPE_OPTIONS.find((o) => o.value === t)?.label ?? t
}

function fmtDate(v?: string | null): string {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await credentialApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  Object.assign(form, {
    visible: true,
    id: '',
    name: '',
    type: 'GIT',
    endpoint: '',
    username: '',
    secret: '',
    description: '',
    enabled: true,
  })
  authType.value = 'BASIC'
}

function openEdit(row: Credential): void {
  Object.assign(form, {
    visible: true,
    id: row.id,
    name: row.name,
    type: row.type,
    endpoint: row.endpoint,
    // 敏感字段永远是空的：后端不回显，所以「空」只能解读为「保持不变」
    username: '',
    secret: '',
    description: row.description ?? '',
    enabled: row.enabled,
    usableByPipelines: row.usableByPipelines === 1,
  })
  authType.value = row.authType
}

async function save(): Promise<void> {
  if (!form.name.trim() || !form.endpoint.trim()) {
    ElMessage.warning('名称与地址必填')
    return
  }
  if (authType.value === 'BASIC') {
    if (!isEdit.value && (!form.username.trim() || !form.secret)) {
      ElMessage.warning('新建时必须填写用户名与密码/令牌')
      return
    }
    if (isEdit.value && form.secret && !form.username.trim()) {
      // 只改密码时用户名可留空（沿用旧值），但完全不给用户名又给了密码是自相矛盾的输入
      ElMessage.warning('填写了新凭据内容时，请一并确认用户名')
      return
    }
  }

  const body = {
    name: form.name.trim(),
    type: form.type,
    endpoint: form.endpoint.trim(),
    authType: authType.value,
    // ⚠️ 匿名时**根本不发**这两个字段 —— 上面的 v-if 只卸载 DOM，用户此前输过的值仍留在
    // reactive 里。带上去会被后端拒（这正是它要拒的），而那两个框此刻**用户看不到也删不掉**，
    // 只能切回「用户名密码」手动清两次，或者关掉抽屉重来。
    username: authType.value === 'NONE' ? undefined : form.username,
    secret: authType.value === 'NONE' ? undefined : form.secret,
    description: form.description.trim() || null,
    enabled: form.enabled,
    // 显式发 false/true：后端「留空 = 保持不变」，而这里用户看得见开关，就该以开关为准
    usableByPipelines: form.usableByPipelines,
  }

  if (isEdit.value) {
    await credentialApi.update({ ...body, id: form.id })
    ElMessage.success('已更新')
  } else {
    await credentialApi.create(body)
    ElMessage.success('已创建')
  }
  form.visible = false
  await load()
}

async function remove(row: Credential): Promise<void> {
  await ElMessageBox.confirm(
    `删除凭据「${row.name}」？此操作不可恢复（硬删除，密文一并消失）。`,
    '确认删除',
    { type: 'warning' },
  )
  await credentialApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

// ---- 测试连接 ----

const testVisible = ref(false)
const testRow = ref<Credential | null>(null)
const probeUrl = ref('')
const testing = ref(false)
const testResult = ref<CredentialTestResult | null>(null)

/** GIT 需要用户给一个探测仓库地址；REGISTRY 直接打 /v2/，不需要额外输入 */
const needsProbeUrl = computed(() => testRow.value?.type === 'GIT')

function openTest(row: Credential): void {
  testRow.value = row
  probeUrl.value = ''
  testResult.value = null
  // ⚠️ 两种类型都要开弹窗 —— 结果 alert 只在这个弹窗里，不开就等于点了没反应。
  // （GIT 是「先填地址再点测试」，REGISTRY 是「开了就直接打」。）
  testVisible.value = true
  if (!needsProbeUrl.value) {
    void runTest()
  }
}

async function runTest(): Promise<void> {
  if (!testRow.value) return
  if (needsProbeUrl.value && !probeUrl.value.trim()) {
    ElMessage.warning('请填写一个用于测试的仓库地址')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await credentialApi.test(testRow.value.id, probeUrl.value.trim() || undefined)
    await load()
  } finally {
    testing.value = false
  }
}

/** 3xx 的「对端要求地址为 X」—— 一键回填，别让用户手工抄一个长 URL */
function applySuggested(): void {
  if (testResult.value?.suggestedUrl) {
    probeUrl.value = testResult.value.suggestedUrl
    testResult.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="credential-page">
    <div class="page-head">
      <h2>凭据管理</h2>
      <el-button type="primary" :disabled="!perm.has(apiCodes.credentialWrite)" @click="openCreate">
        新建凭据
      </el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="凭据内容永不回显"
      description="列表与详情只显示「是否已配置」与指纹。编辑时输入框留空表示保持不变。凭据是平台级的：用它做的操作在 GitLab / Harbor 侧都记在平台名下，租户间的仓库隔离请靠对端各自的 token / robot account 权限。"
      class="hint"
    />

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="endpoint" label="地址" min-width="220" show-overflow-tooltip />
      <el-table-column prop="authType" label="认证方式" width="100" />
      <el-table-column label="指纹" width="150">
        <template #default="{ row }">
          <span v-if="row.fingerprint" class="mono">{{ row.fingerprint }}</span>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="上次验证" min-width="150">
        <template #default="{ row }">
          <span v-if="row.lastVerifyError" class="err">{{ row.lastVerifyError }}</span>
          <span v-else-if="row.lastVerifiedAt">通过（{{ fmtDate(row.lastVerifiedAt) }}）</span>
          <span v-else class="dim">未验证</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="流水线可用" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.usableByPipelines === 1" type="warning" size="small">可用于流水线</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openTest(row)">测试连接</el-button>
          <el-button link type="primary" :disabled="!perm.has(apiCodes.credentialWrite)" @click="openEdit(row)">
            编辑
          </el-button>
          <el-button link type="danger" :disabled="!perm.has(apiCodes.credentialWrite)" @click="remove(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建 / 编辑 -->
    <el-drawer v-model="form.visible" :title="isEdit ? '编辑凭据' : '新建凭据'" size="520px">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 harbor-prod / gitlab-main" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width: 100%" @change="onTypeChange">
            <el-option v-for="o in TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input
            v-model="form.endpoint"
            :disabled="isDockerHub"
            :placeholder="form.type === 'GIT' ? 'https://gitlab.example.com' : isDockerHub ? 'docker.io' : 'harbor.example.com'"
          />
          <div class="tip">填 host 基址（站点级），不是具体仓库或 project</div>
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="authType" style="width: 100%" @change="onAuthTypeChange">
            <el-option v-for="o in authOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <div v-if="authType === 'NONE'" class="tip">匿名连接不带凭据，只能访问对端公开的资源</div>
          <div v-if="isDockerHub" class="tip">
            公共镜像由平台<b>内置</b>的 Docker Hub 提供（不用配、也不在这里）—— 这条连接只用于
            <b>你自己的私有仓库</b>：候选补全、浏览、以及流水线推送。
            <br />
            令牌请用 <b>Access Token（PAT）</b>，不是登录密码。
          </div>
        </el-form-item>
        <el-form-item v-if="authType === 'BASIC'" label="用户名">
          <el-input
            v-model="form.username"
            :placeholder="isEdit ? '留空 = 保持不变' : isDockerHub ? 'Docker ID' : 'robot$platform+ci / ci-bot'"
          />
        </el-form-item>
        <el-form-item v-if="authType === 'BASIC'" label="密码/令牌">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            :placeholder="isEdit ? '留空 = 保持不变' : 'PAT / robot account 密钥'"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="允许在流水线里使用">
          <el-switch v-model="form.usableByPipelines" />
          <div class="tip">
            开：租户的「{{ form.type === 'GIT' ? 'git 克隆' : '构建并推送镜像' }}」节点能从下拉里选到它。
            关（默认）：它只在这张凭据页里存在，流水线看不见它。
            <br />
            ⚠️ 这个开关是平台级的：开了之后<b>任意租户</b>的流水线都能引用它（引用按连接 id 走，
            不按租户收窄）—— 请只对「大家共用的出站凭据」这类连接开。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>

    <!-- 测试连接：GIT 要先填探测地址，REGISTRY 打开即测 -->
    <el-dialog
      v-model="testVisible"
      :title="needsProbeUrl ? '测试 Git 凭据' : '测试镜像仓库连接'"
      width="560px"
    >
      <el-form v-if="needsProbeUrl" label-width="120px">
        <el-form-item label="探测仓库地址" required>
          <el-input
            v-model="probeUrl"
            placeholder="https://gitlab.example.com/group/repo.git"
          />
          <div class="tip">
            凭据是按 host 配的，能不能用取决于具体仓库的权限 —— 请填完整地址（带上 .git）
          </div>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="testResult && !testResult.ok"
        type="error"
        :closable="false"
        show-icon
        :title="testResult.message"
        class="result"
      >
        <el-button v-if="testResult.suggestedUrl" link type="primary" @click="applySuggested">
          改用 {{ testResult.suggestedUrl }}
        </el-button>
      </el-alert>
      <el-alert
        v-else-if="testResult?.ok"
        type="success"
        :closable="false"
        show-icon
        title="连接成功"
        class="result"
      />
      <template #footer>
        <el-button @click="testVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testing" @click="runTest">测试</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.credential-page {
  padding: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
}
.hint {
  margin-bottom: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.err {
  color: var(--el-color-danger);
}
.dim {
  color: var(--text-3);
}
</style>

<style>
/* ⚠️ 非 scoped：el-drawer / el-dialog 的内部节点没有 data-v-*，scoped 的 :deep() 匹配不到。
   用 .el-drawer / .el-dialog 前缀限制作用域（本仓既有约定）。 */
.el-drawer .tip,
.el-dialog .tip {
  margin-top: 4px;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.el-dialog .result {
  margin-top: 8px;
}
</style>
