<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { usePaletteAction } from '@/composables/usePaletteAction'
import { ElMessage, ElMessageBox } from 'element-plus'
import { oauthClientApi } from '@/api'
import type { OAuthClient, OAuthClientCreated, OAuthClientSecrets, OAuthScope, OAuthSecretKind } from '@/types/oauthClient'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import OAuthClientForm from '@/components/oauthclient/OAuthClientForm.vue'
import SecretOnceDialog from '@/components/oauthclient/SecretOnceDialog.vue'
import OAuthClientDetail from '@/components/oauthclient/OAuthClientDetail.vue'
import { grantLabel } from '@/components/oauthclient/options'

/**
 * OAuth 客户端（**平台管理员专属**）。外部系统接平台单点登录用；平台前端自己是「内置」客户端，
 * 只能改回调与 token 有效期，不能删、不能停。
 * 列表与详情不含任何密钥 —— 密钥只在新建 / 重置 / 改成对称算法后的一次性弹窗里出现。
 */
const perm = usePermission()
const canManage = computed(() => perm.hasAny([apiCodes.oauthClientManage]))

const rows = ref<OAuthClient[]>([])
const scopes = ref<OAuthScope[]>([])
const loading = ref(false)
const drawer = ref(false)
const editing = ref<OAuthClient | null>(null)
const secretDialog = ref(false)
const shownSecrets = ref<OAuthClientSecrets | null>(null)
const shownClientId = ref('')
const detailVisible = ref(false)
const detailClient = ref<OAuthClient | null>(null)

function tokenForm(row: OAuthClient): string {
  if (row.jwtType === 'JWE') return `JWE · ${row.jweKeyAlg ?? '?'} / ${row.jweEncMethod ?? 'A256GCM'}`
  return `JWS · ${row.jwsSigAlg}`
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    rows.value = await oauthClientApi.list()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadScopes(): Promise<void> {
  try {
    scopes.value = await oauthClientApi.scopes()
  } catch {
    scopes.value = []
  }
}

function openCreate(): void {
  editing.value = null
  drawer.value = true
}

function openDetail(row: OAuthClient): void {
  detailClient.value = row
  detailVisible.value = true
}

function openEdit(row: OAuthClient): void {
  editing.value = row
  drawer.value = true
}

function showSecrets(clientId: string, secrets: OAuthClientSecrets | null): void {
  if (!secrets || Object.values(secrets).every((v) => !v)) return
  shownClientId.value = clientId
  shownSecrets.value = secrets
  secretDialog.value = true
}

async function onSaved(result: OAuthClientCreated): Promise<void> {
  showSecrets(result.client.clientId, result.secrets)
  await refresh()
}

async function onToggle(row: OAuthClient, enabled: boolean): Promise<void> {
  if (!enabled) {
    try {
      await ElMessageBox.confirm(
        `停用「${row.clientName || row.clientId}」后，它不能再发起登录、也换不到新 token；已签出的 token 到期前仍有效。`,
        '停用客户端',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  try {
    await oauthClientApi.enable(row.id, enabled)
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch {
    /* 拦截器已提示 */
  }
  await refresh()
}

async function onReset(row: OAuthClient, which: OAuthSecretKind): Promise<void> {
  const name = which === 'client' ? 'client_secret' : which === 'jws' ? 'JWS 签名密钥' : 'JWE 加密密钥'
  try {
    await ElMessageBox.confirm(`重置后旧的${name}立即失效，对方需要换上新密钥。确认重置？`, '重置密钥', { type: 'warning' })
  } catch {
    return
  }
  try {
    showSecrets(row.clientId, await oauthClientApi.resetSecret(row.id, which))
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

async function onDelete(row: OAuthClient): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除客户端「${row.clientName || row.clientId}」？对方系统将无法再用它登录。`, '删除', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await oauthClientApi.remove(row.id)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

onMounted(async () => {
  await Promise.all([refresh(), loadScopes()])
})

// 命令面板（Ctrl+K）页内功能：路由 meta.actions 的落点，条件与页面上对应按钮的禁用条件一致
usePaletteAction({
  create: { run: openCreate, ready: () => canManage.value },
})
</script>

<template>
  <div>
    <PageHeader title="OAuth 客户端" description="外部系统接入平台单点登录的客户端；外部客户端拿到的 token 只含用户名与显示名">
      <el-button @click="refresh">刷新</el-button>
      <el-button v-if="canManage" type="primary" @click="openCreate">新建客户端</el-button>
    </PageHeader>

    <EmptyState v-if="rows.length === 0 && !loading" title="还没有客户端" description="平台内置客户端未打标时这里也会为空，请先重放迁移。" />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column label="名称" min-width="260">
          <template #default="{ row }">
            {{ row.clientName || '—' }}
            <el-tag v-if="row.builtin" size="small" effect="plain" class="builtin-tag">内置</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="client_id" min-width="280">
          <template #default="{ row }"><code class="res-name">{{ row.clientId }}</code></template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ row.type === 'PUBLIC' ? '公共' : '机密' }}</template>
        </el-table-column>
        <el-table-column label="授权方式" min-width="150">
          <template #default="{ row }">
            <span class="grants">{{ row.grantTypes.map(grantLabel).join(' / ') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="回调地址" min-width="220">
          <template #default="{ row }">
            <div v-for="u in row.redirectUris" :key="u" class="uri">{{ u }}</div>
            <span v-if="row.redirectUris.length === 0" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="token 形态" min-width="160">
          <template #default="{ row }">{{ tokenForm(row) }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :disabled="row.builtin || !canManage"
              @change="(v: string | number | boolean) => onToggle(row, v === true)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="canManage ? 260 : 80" fixed="right">
          <template #default="{ row }">
            <div class="row-ops">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="canManage" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-dropdown v-if="canManage && (row.hasClientSecret || row.hasJwsSecret || row.hasJweSecret)" trigger="click">
                <el-button link type="primary">重置密钥</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="row.hasClientSecret" @click="onReset(row, 'client')">client_secret</el-dropdown-item>
                    <el-dropdown-item v-if="row.hasJwsSecret" @click="onReset(row, 'jws')">JWS 签名密钥</el-dropdown-item>
                    <el-dropdown-item v-if="row.hasJweSecret" @click="onReset(row, 'jwe')">JWE 加密密钥</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button v-if="canManage && !row.builtin" link type="danger" @click="onDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <OAuthClientForm v-model="drawer" :client="editing" :scopes="scopes" @saved="onSaved" />
    <SecretOnceDialog v-model="secretDialog" :client-id="shownClientId" :secrets="shownSecrets" />
    <OAuthClientDetail v-model="detailVisible" :client="detailClient" />
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.res-name {
  font-family: var(--el-font-family-mono, monospace);
}
.builtin-tag {
  margin-left: 6px;
}
.uri {
  font-size: 12px;
  word-break: break-all;
}
.grants {
  font-size: 12px;
}
/* 按钮被 el-dropdown 包住后，EP 的「相邻按钮 margin-left」不再生效，间距忽大忽小、窄时还会折行 —— 统一用 flex gap */
.row-ops {
  display: flex;
  align-items: center;
  gap: 12px;
  white-space: nowrap;
}
.row-ops .el-button + .el-button {
  margin-left: 0;
}
</style>
