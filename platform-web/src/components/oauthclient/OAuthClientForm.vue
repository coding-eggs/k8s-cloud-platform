<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { oauthClientApi } from '@/api'
import type { OAuthClient, OAuthClientCreated, OAuthClientRequest, OAuthScope } from '@/types/oauthClient'
import {
  ASYM_JWE,
  ASYM_JWS,
  AUTH_METHOD_OPTIONS,
  ENC,
  GRANT_AUTH_CODE,
  GRANT_DEVICE_CODE,
  GRANT_OPTIONS,
  GRANT_REFRESH,
  HMAC,
  SYM_JWE,
} from './options'

/**
 * 新建 / 编辑 OAuth 客户端的抽屉。规则的权威在 platform-auth（这里只是不让人填出注定被拒的组合）：
 * - 公共客户端只有授权码（强制 PKCE）与会话续期，且不能用任何对称算法（没处保管密钥）；
 * - 机密客户端的认证方式、PKCE 开关、设备码等与旧管理页一致，可以不配但都在；
 * - 内置客户端（平台前端）只能改回调地址、登出回调与 Access Token 有效期。
 * 对称密钥由服务端生成，保存后在一次性弹窗里展示。
 */
const props = defineProps<{ modelValue: boolean; client: OAuthClient | null; scopes: OAuthScope[] }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', result: OAuthClientCreated): void
}>()

const saving = ref(false)
const editing = computed(() => props.client !== null)
const builtin = computed(() => props.client?.builtin === true)

function emptyForm(): OAuthClientRequest {
  return {
    clientId: '',
    clientName: '',
    type: 'CONFIDENTIAL',
    grantTypes: [GRANT_AUTH_CODE],
    clientAuthenticationMethods: ['client_secret_basic'],
    scopes: ['openid', 'profile'],
    redirectUris: [],
    postLogoutRedirectUris: [],
    requireProofKey: true,
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
        // 库内的非常规值（旧页面写进去的）原样带回，后端允许保留
        grantTypes: [...c.grantTypes],
        clientAuthenticationMethods: [...c.clientAuthenticationMethods],
        scopes: [...c.scopes],
        requireProofKey: c.requireProofKey,
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
const hasAuthCode = computed(() => form.grantTypes.includes(GRANT_AUTH_CODE))
const asymJwe = computed(() => form.jwtType === 'JWE' && !!form.jweKeyAlg && !SYM_JWE.includes(form.jweKeyAlg))
const privateKeyJwt = computed(() => form.clientAuthenticationMethods.includes('private_key_jwt'))
/** 对方 JWK Set 地址：private_key_jwt 认证与非对称 JWE 共用 */
const needsJwkUrl = computed(() => privateKeyJwt.value || asymJwe.value)
const jwsAlgs = computed(() => (isPublic.value ? ASYM_JWS : [...ASYM_JWS, ...HMAC]))
/** 列表里没有、但库里已有的值（旧页面写进去的）也要能显示成选项，否则勾不掉也看不见 */
const grantChoices = computed(() => {
  const extra = form.grantTypes.filter((g) => !GRANT_OPTIONS.some((o) => o.value === g))
  return [...GRANT_OPTIONS, ...extra.map((g) => ({ value: g, label: g, publicOk: true }))]
})
const scopeChoices = computed(() => {
  const extra = form.scopes.filter((s) => !props.scopes.some((x) => x.name === s))
  return [...props.scopes, ...extra.map((s) => ({ name: s, description: '目录外，旧数据保留' }))]
})
const jweAlgs = computed(() => (isPublic.value ? ASYM_JWE : [...SYM_JWE, ...ASYM_JWE]))
/** 选中对称算法 → 保存后会生成新密钥（算法没变时沿用旧密钥） */
const needsSymmetricKey = computed(
  () => HMAC.includes(form.jwsSigAlg) || (form.jwtType === 'JWE' && SYM_JWE.includes(form.jweKeyAlg ?? '')),
)

watch(
  () => form.type,
  (t) => {
    if (builtin.value) return
    if (t === 'CONFIDENTIAL') {
      if (!form.clientAuthenticationMethods.some((m) => m !== 'none')) form.clientAuthenticationMethods = ['client_secret_basic']
      return
    }
    form.grantTypes = form.grantTypes.filter((g) => GRANT_OPTIONS.find((o) => o.value === g)?.publicOk)
    if (form.grantTypes.length === 0) form.grantTypes = [GRANT_AUTH_CODE]
    form.clientAuthenticationMethods = ['none']
    form.requireProofKey = true
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
        <div class="form-tip">机密客户端按认证方式决定是否生成 client_secret；公共客户端没有密钥，只能授权码（PKCE）与会话续期。建后不可改。</div>
      </el-form-item>

      <template v-if="!builtin">
        <el-form-item label="授权方式" required>
          <el-checkbox-group v-model="form.grantTypes">
            <el-checkbox
              v-for="g in grantChoices"
              :key="g.value"
              :value="g.value"
              :disabled="(isPublic && !g.publicOk) || (g.value === GRANT_REFRESH && !hasAuthCode && !form.grantTypes.includes(GRANT_DEVICE_CODE))"
            >{{ g.label }}</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">
            客户端凭据拿到的 token 不含任何用户信息。设备码、刷新令牌需要客户端能认证自己，公共客户端不可用。
          </div>
        </el-form-item>
        <el-form-item label="认证方式" required>
          <el-checkbox-group v-model="form.clientAuthenticationMethods" :disabled="isPublic">
            <el-checkbox v-for="m in AUTH_METHOD_OPTIONS" :key="m.value" :value="m.value">{{ m.label }}</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">
            Basic / Post 会生成 client_secret（只显示一次）；Private Key JWT 用对方公钥验签，需要填写对方公钥地址。公共客户端固定为 none。
          </div>
        </el-form-item>
        <el-form-item label="scope">
          <el-checkbox-group v-model="form.scopes">
            <el-checkbox v-for="s in scopeChoices" :key="s.name" :value="s.name">{{ s.name }}（{{ s.description }}）</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </template>

      <el-form-item label="回调地址" :required="hasAuthCode || builtin">
        <el-input v-model="redirectText" type="textarea" :rows="3" placeholder="一行一个，如 https://app.example.com/callback" />
      </el-form-item>
      <el-form-item label="登出回调">
        <el-input v-model="postLogoutText" type="textarea" :rows="2" placeholder="一行一个，可留空" />
      </el-form-item>
      <el-form-item v-if="!builtin && hasAuthCode" label="强制 PKCE">
        <el-switch v-model="form.requireProofKey" :disabled="isPublic" />
        <div class="form-tip">公共客户端没有密钥，必须用 PKCE；机密客户端可以关闭以兼容不支持 PKCE 的老系统。</div>
      </el-form-item>
      <el-form-item v-if="!builtin" label="需要用户确认授权">
        <el-switch v-model="form.requireAuthorizationConsent" />
      </el-form-item>
      <el-form-item label="Access Token 有效期">
        <el-input-number v-model="form.accessTokenTimeToLive" :min="builtin ? 300 : 60" :max="builtin ? 28799 : 86400" :step="300" />
        <span class="unit">秒</span>
        <div class="form-tip">{{ builtin ? '必须小于会话空闲超时（28800 秒）' : '60 ~ 86400 秒' }}</div>
      </el-form-item>
      <el-form-item v-if="!builtin && form.grantTypes.includes(GRANT_REFRESH)" label="Refresh Token 有效期">
        <el-input-number v-model="form.refreshTokenTimeToLive" :min="300" :step="3600" />
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
          <div v-if="ASYM_JWS.includes(form.jwsSigAlg) && form.jwsSigAlg !== 'RS256'" class="form-tip">
            平台必须已有该算法的签名密钥（JWK），否则签发 token 时会失败。
          </div>
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
        </template>
        <el-form-item v-if="needsJwkUrl" label="对方公钥地址" required>
          <el-input v-model="form.jweJwkUrl" placeholder="https://app.example.com/.well-known/jwks.json" />
          <div class="form-tip">对方的 JWK Set：Private Key JWT 用其中的签名公钥验对方身份，非对称 JWE 用其中的加密公钥加密 token</div>
        </el-form-item>
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
