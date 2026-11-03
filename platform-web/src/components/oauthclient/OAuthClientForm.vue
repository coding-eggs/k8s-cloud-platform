<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { oauthClientApi } from '@/api'
import type { OAuthClient, OAuthClientCreated, OAuthClientRequest, OAuthScope } from '@/types/oauthClient'

/**
 * 新建 / 编辑 OAuth 客户端的抽屉。规则的权威在 platform-auth（这里只是不让人填出注定被拒的组合）：
 * - 公共客户端只能授权码，且不能用任何对称算法（没处保管密钥）；
 * - 授权码一律强制 PKCE（后端自动，无需选）；
 * - 内置客户端（平台前端）只能改回调地址、登出回调与 Access Token 有效期。
 * 对称密钥由服务端生成，保存后在一次性弹窗里展示。
 */
const props = defineProps<{ modelValue: boolean; client: OAuthClient | null; scopes: OAuthScope[] }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', result: OAuthClientCreated): void
}>()

const HMAC = ['HS256', 'HS384', 'HS512']
const SYM_JWE = ['dir', 'A128KW', 'A192KW', 'A256KW']
const ASYM_JWE = ['RSA-OAEP-256', 'RSA-OAEP-384', 'RSA-OAEP-512', 'ECDH-ES', 'ECDH-ES+A128KW', 'ECDH-ES+A256KW']
const ENC = ['A128GCM', 'A192GCM', 'A256GCM', 'A128CBC-HS256', 'A192CBC-HS384', 'A256CBC-HS512']

const saving = ref(false)
const editing = computed(() => props.client !== null)
const builtin = computed(() => props.client?.builtin === true)

function emptyForm(): OAuthClientRequest {
  return {
    clientId: '',
    clientName: '',
    type: 'CONFIDENTIAL',
    grantTypes: ['authorization_code'],
    scopes: ['openid', 'profile'],
    redirectUris: [],
    postLogoutRedirectUris: [],
    requireAuthorizationConsent: true,
    accessTokenTimeToLive: 3600,
    refreshTokenTimeToLive: 604800,
    jwtType: 'JWS',
    jwsSigAlg: 'RS256',
    jweKeyAlg: null,
    jweEncMethod: 'A256GCM',
    jweJwkUrl: null,
  }
}
const form = reactive<OAuthClientRequest>(emptyForm())
/** 回调地址一行一个 */
const redirectText = ref('')
const postLogoutText = ref('')

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    const c = props.client
    Object.assign(form, emptyForm())
    if (c) {
      Object.assign(form, {
        id: c.id,
        clientId: c.clientId,
        clientName: c.clientName,
        type: c.type,
        grantTypes: [...c.grantTypes],
        // 老客户端可能带着目录外的 scope：保存时后端会规整掉，这里先只勾目录内的
        scopes: c.scopes.filter((s) => props.scopes.some((x) => x.name === s)),
        requireAuthorizationConsent: c.requireAuthorizationConsent,
        accessTokenTimeToLive: c.accessTokenTimeToLive,
        refreshTokenTimeToLive: c.refreshTokenTimeToLive ?? 604800,
        jwtType: c.jwtType,
        jwsSigAlg: c.jwsSigAlg,
        jweKeyAlg: c.jweKeyAlg,
        jweEncMethod: c.jweEncMethod ?? 'A256GCM',
        jweJwkUrl: c.jweJwkUrl,
      })
    }
    redirectText.value = (c?.redirectUris ?? []).join('\n')
    postLogoutText.value = (c?.postLogoutRedirectUris ?? []).join('\n')
  },
)

const isPublic = computed(() => form.type === 'PUBLIC')
const hasAuthCode = computed(() => form.grantTypes.includes('authorization_code'))
const asymJwe = computed(() => form.jwtType === 'JWE' && !!form.jweKeyAlg && !SYM_JWE.includes(form.jweKeyAlg))
const jwsAlgs = computed(() => (isPublic.value ? ['RS256'] : ['RS256', ...HMAC]))
const jweAlgs = computed(() => (isPublic.value ? ASYM_JWE : [...SYM_JWE, ...ASYM_JWE]))
/** 选中对称算法 → 保存后会生成新密钥（算法没变时沿用旧密钥） */
const needsSymmetricKey = computed(
  () => HMAC.includes(form.jwsSigAlg) || (form.jwtType === 'JWE' && SYM_JWE.includes(form.jweKeyAlg ?? '')),
)

watch(
  () => form.type,
  (t) => {
    if (t !== 'PUBLIC' || builtin.value) return
    form.grantTypes = ['authorization_code']
    if (HMAC.includes(form.jwsSigAlg)) form.jwsSigAlg = 'RS256'
    if (SYM_JWE.includes(form.jweKeyAlg ?? '')) form.jweKeyAlg = null
  },
)

function lines(text: string): string[] {
  return text.split('\n').map((s) => s.trim()).filter(Boolean)
}

async function submit(): Promise<void> {
  const body: OAuthClientRequest = {
    ...form,
    redirectUris: lines(redirectText.value),
    postLogoutRedirectUris: lines(postLogoutText.value),
  }
  if (!builtin.value && !body.clientName.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const result = editing.value ? await oauthClientApi.update(body) : await oauthClientApi.create(body)
    ElMessage.success(editing.value ? '已保存' : '已创建')
    emit('update:modelValue', false)
    emit('saved', result)
  } catch {
    /* 拦截器已提示（规则校验失败带具体原因） */
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="editing ? `编辑 ${client?.clientName || client?.clientId}` : '新建 OAuth 客户端'"
    size="620px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="builtin"
      type="info"
      :closable="false"
      show-icon
      title="平台内置客户端：只能修改回调地址、登出回调与 Access Token 有效期，其余配置由平台固定。"
      style="margin-bottom: 16px"
    />
    <el-form label-width="140px" label-position="left">
      <el-form-item label="名称" :required="!builtin">
        <el-input v-model="form.clientName" :disabled="builtin" placeholder="如 某某业务系统" />
      </el-form-item>
      <el-form-item label="client_id">
        <el-input v-model="form.clientId" :disabled="editing" placeholder="留空自动生成 ext- 开头的 ID（建后不可改）" />
      </el-form-item>
      <el-form-item v-if="!builtin" label="客户端类型">
        <el-radio-group v-model="form.type" :disabled="editing">
          <el-radio value="CONFIDENTIAL">机密（有后端）</el-radio>
          <el-radio value="PUBLIC">公共（纯前端 / 移动端）</el-radio>
        </el-radio-group>
        <div class="form-tip">机密客户端会生成 client_secret；公共客户端没有密钥，只能授权码 + PKCE。建后不可改。</div>
      </el-form-item>

      <template v-if="!builtin">
        <el-form-item label="授权方式" required>
          <el-checkbox-group v-model="form.grantTypes">
            <el-checkbox value="authorization_code">授权码（单点登录）</el-checkbox>
            <el-checkbox value="refresh_token" :disabled="isPublic || !hasAuthCode">刷新令牌</el-checkbox>
            <el-checkbox value="client_credentials" :disabled="isPublic">客户端凭据（无用户）</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">授权码一律强制 PKCE。客户端凭据拿到的 token 不含任何用户信息。</div>
        </el-form-item>
        <el-form-item label="scope">
          <el-checkbox-group v-model="form.scopes">
            <el-checkbox v-for="s in scopes" :key="s.name" :value="s.name">{{ s.name }}（{{ s.description }}）</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </template>

      <el-form-item label="回调地址" :required="hasAuthCode || builtin">
        <el-input v-model="redirectText" type="textarea" :rows="3" placeholder="一行一个，如 https://app.example.com/callback" />
      </el-form-item>
      <el-form-item label="登出回调">
        <el-input v-model="postLogoutText" type="textarea" :rows="2" placeholder="一行一个，可留空" />
      </el-form-item>
      <el-form-item v-if="!builtin" label="需要用户确认授权">
        <el-switch v-model="form.requireAuthorizationConsent" />
      </el-form-item>
      <el-form-item label="Access Token 有效期">
        <el-input-number v-model="form.accessTokenTimeToLive" :min="300" :max="builtin ? 28799 : 86400" :step="300" />
        <span class="unit">秒</span>
        <div class="form-tip">{{ builtin ? '必须小于会话空闲超时（28800 秒）' : '300 ~ 86400 秒' }}</div>
      </el-form-item>
      <el-form-item v-if="!builtin && form.grantTypes.includes('refresh_token')" label="Refresh Token 有效期">
        <el-input-number v-model="form.refreshTokenTimeToLive" :min="3600" :max="2592000" :step="3600" />
        <span class="unit">秒</span>
      </el-form-item>

      <template v-if="!builtin">
        <el-divider content-position="left">token 形态</el-divider>
        <el-form-item label="类型">
          <el-radio-group v-model="form.jwtType">
            <el-radio value="JWS">JWS（签名）</el-radio>
            <el-radio value="JWE">JWE（签名后再加密）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="签名算法">
          <el-select v-model="form.jwsSigAlg" style="width: 220px">
            <el-option v-for="a in jwsAlgs" :key="a" :label="a === 'RS256' ? 'RS256（平台公钥，推荐）' : a" :value="a" />
          </el-select>
        </el-form-item>
        <template v-if="form.jwtType === 'JWE'">
          <el-form-item label="密钥管理算法" required>
            <el-select v-model="form.jweKeyAlg" placeholder="选择算法" style="width: 220px">
              <el-option v-for="a in jweAlgs" :key="a" :label="a" :value="a" />
            </el-select>
          </el-form-item>
          <el-form-item label="内容加密算法">
            <el-select v-model="form.jweEncMethod" style="width: 220px">
              <el-option v-for="e in ENC" :key="e" :label="e" :value="e" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="asymJwe" label="对方公钥地址" required>
            <el-input v-model="form.jweJwkUrl" placeholder="https://app.example.com/.well-known/jwks.json" />
            <div class="form-tip">平台用对方 JWK Set 里的加密公钥加密 token</div>
          </el-form-item>
        </template>
        <div v-if="needsSymmetricKey" class="form-tip sym-tip">
          对称算法的密钥由平台生成，保存后只显示一次；算法不变时沿用已有密钥，可在列表里重置。
        </div>
      </template>
    </el-form>
    <div class="form-actions">
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 4px;
  width: 100%;
}
.sym-tip {
  padding-left: 140px;
}
.unit {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 0;
}
</style>
