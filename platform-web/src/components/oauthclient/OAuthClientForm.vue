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
  return [...GRANT_OPTIONS, ...extra.map((g) => ({ value: g, label: g, desc: '旧数据里的值，原样保留', publicOk: true }))]
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

/** 不可勾选的原因；可勾选返回 null */
function grantBlocked(g: { value: string; publicOk: boolean }): string | null {
  if (isPublic.value && !g.publicOk) return '公共客户端不能认证自己，用不了'
  if (g.value === GRANT_REFRESH && !hasAuthCode.value && !form.grantTypes.includes(GRANT_DEVICE_CODE)) {
    return '要先勾选授权码或设备码'
  }
  return null
}

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
    size="760px"
    class="oauth-form-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="builtin"
      type="info"
      :closable="false"
      show-icon
      title="平台内置客户端：只能修改回调地址、登出回调与 Access Token 有效期，其余配置由平台固定。"
      class="oauth-form-alert"
    />
    <el-form label-position="top" class="oauth-form" @submit.prevent>
      <section class="oauth-form-section">
        <h4 class="oauth-form-title">基本信息</h4>
        <div class="oauth-form-row">
          <el-form-item label="名称" :required="!builtin">
            <el-input v-model="form.clientName" :disabled="builtin" placeholder="如：财务报销系统" />
          </el-form-item>
          <el-form-item label="client_id">
            <el-input v-model="form.clientId" :disabled="editing" placeholder="留空自动生成 ext- 开头的 ID" />
            <div class="oauth-form-tip">建后不可改</div>
          </el-form-item>
        </div>
        <el-form-item v-if="!builtin" label="客户端类型">
          <el-radio-group v-model="form.type" :disabled="editing" class="oauth-tiles">
            <el-radio value="CONFIDENTIAL" border class="oauth-tile">
              <span class="oauth-tile-name">机密客户端</span>
              <span class="oauth-tile-desc">有后端、能保管密钥，可以用全部授权方式</span>
            </el-radio>
            <el-radio value="PUBLIC" border class="oauth-tile">
              <span class="oauth-tile-name">公共客户端</span>
              <span class="oauth-tile-desc">纯前端或移动端，没有密钥，只能授权码（PKCE）与会话续期</span>
            </el-radio>
          </el-radio-group>
          <div v-if="editing" class="oauth-form-tip">类型建后不可改</div>
        </el-form-item>
      </section>

      <section v-if="!builtin" class="oauth-form-section">
        <h4 class="oauth-form-title">授权</h4>
        <el-form-item label="授权方式" required>
          <el-checkbox-group v-model="form.grantTypes" class="oauth-tiles">
            <el-checkbox
              v-for="g in grantChoices"
              :key="g.value"
              :value="g.value"
              :disabled="grantBlocked(g) !== null"
              border
              class="oauth-tile"
            >
              <span class="oauth-tile-name">{{ g.label }}</span>
              <span class="oauth-tile-desc">{{ grantBlocked(g) ?? g.desc }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="客户端认证方式" required>
          <div v-if="isPublic" class="oauth-form-fixed">固定为 none：公共客户端没有密钥，靠 PKCE 证明是同一个发起方。</div>
          <el-checkbox-group v-else v-model="form.clientAuthenticationMethods" class="oauth-tiles">
            <el-checkbox v-for="m in AUTH_METHOD_OPTIONS" :key="m.value" :value="m.value" border class="oauth-tile">
              <span class="oauth-tile-name">{{ m.label }}</span>
              <span class="oauth-tile-desc">{{ m.desc }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="scope">
          <el-checkbox-group v-model="form.scopes" class="oauth-scopes">
            <el-checkbox v-for="s in scopeChoices" :key="s.name" :value="s.name" border :title="s.description">
              {{ s.name }}<span class="oauth-scope-desc">{{ s.description }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </section>

      <section class="oauth-form-section">
        <h4 class="oauth-form-title">回调地址</h4>
        <el-form-item label="登录回调" :required="hasAuthCode || builtin">
          <el-input
            v-model="redirectText"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder="一行一个，如 https://app.example.com/callback"
          />
        </el-form-item>
        <el-form-item label="登出回调">
          <el-input
            v-model="postLogoutText"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder="一行一个，可留空"
          />
        </el-form-item>
      </section>

      <section class="oauth-form-section">
        <h4 class="oauth-form-title">安全与有效期</h4>
        <div v-if="!builtin" class="oauth-switches">
          <div v-if="hasAuthCode" class="oauth-switch-row">
            <div>
              <div class="oauth-switch-name">强制 PKCE</div>
              <div class="oauth-form-tip">
                {{ isPublic ? '公共客户端必须开启' : '可以关闭，以兼容不支持 PKCE 的老系统' }}
              </div>
            </div>
            <el-switch v-model="form.requireProofKey" :disabled="isPublic" />
          </div>
          <div class="oauth-switch-row">
            <div>
              <div class="oauth-switch-name">需要用户确认授权</div>
              <div class="oauth-form-tip">首次登录时让用户看到并同意对方申请的 scope</div>
            </div>
            <el-switch v-model="form.requireAuthorizationConsent" />
          </div>
        </div>
        <div class="oauth-form-row">
          <el-form-item label="Access Token 有效期（秒）">
            <el-input-number
              v-model="form.accessTokenTimeToLive"
              :min="builtin ? 300 : 60"
              :max="builtin ? 28799 : 86400"
              :step="300"
              controls-position="right"
              class="oauth-form-number"
            />
            <div class="oauth-form-tip">{{ builtin ? '必须小于会话空闲超时 28800' : '60 ~ 86400' }}</div>
          </el-form-item>
          <el-form-item v-if="!builtin && form.grantTypes.includes(GRANT_REFRESH)" label="Refresh Token 有效期（秒）">
            <el-input-number
              v-model="form.refreshTokenTimeToLive"
              :min="300"
              :step="3600"
              controls-position="right"
              class="oauth-form-number"
            />
            <div class="oauth-form-tip">不小于 300</div>
          </el-form-item>
        </div>
      </section>

      <section v-if="!builtin" class="oauth-form-section">
        <h4 class="oauth-form-title">token 形态</h4>
        <el-form-item label="类型">
          <el-radio-group v-model="form.jwtType">
            <el-radio-button value="JWS">JWS 签名</el-radio-button>
            <el-radio-button value="JWE">JWE 签名后再加密</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div class="oauth-form-row">
          <el-form-item label="签名算法">
            <el-select v-model="form.jwsSigAlg">
              <el-option v-for="a in jwsAlgs" :key="a" :label="a === 'RS256' ? 'RS256（平台公钥，推荐）' : a" :value="a" />
            </el-select>
            <div v-if="ASYM_JWS.includes(form.jwsSigAlg) && form.jwsSigAlg !== 'RS256'" class="oauth-form-tip">
              平台必须已有该算法的签名密钥，否则签发 token 会失败
            </div>
          </el-form-item>
          <div v-if="form.jwtType !== 'JWE'" />
        </div>
        <div v-if="form.jwtType === 'JWE'" class="oauth-form-row">
          <el-form-item label="密钥管理算法" required>
            <el-select v-model="form.jweKeyAlg" placeholder="选择算法">
              <el-option v-for="a in jweAlgs" :key="a" :label="a" :value="a" />
            </el-select>
          </el-form-item>
          <el-form-item label="内容加密算法">
            <el-select v-model="form.jweEncMethod">
              <el-option v-for="e in ENC" :key="e" :label="e" :value="e" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item v-if="needsJwkUrl" label="对方公钥地址（JWK Set）" required>
          <el-input v-model="form.jweJwkUrl" placeholder="https://app.example.com/.well-known/jwks.json" />
          <div class="oauth-form-tip">Private Key JWT 用其中的签名公钥验证对方，非对称 JWE 用其中的加密公钥加密 token</div>
        </el-form-item>
        <el-alert
          v-if="needsSymmetricKey"
          type="warning"
          :closable="false"
          show-icon
          title="对称算法的密钥由平台生成，保存后只显示一次。算法不变时沿用已有密钥，可在列表里重置。"
        />
      </section>
    </el-form>

    <template #footer>
      <div class="oauth-form-footer">
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建客户端' }}</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style>
/* 非 scoped：el-drawer 渲染到 body，内部节点不带 data-v，scoped 选择器匹配不到；统一 oauth- 前缀防串 */
.oauth-form-drawer .el-drawer__body {
  padding: 4px 24px 24px;
}
.oauth-form-drawer .el-drawer__footer {
  border-top: 1px solid var(--border);
  padding: 12px 24px;
}
.oauth-form-alert {
  margin: 12px 0 4px;
}
.oauth-form-section {
  padding: 20px 0 8px;
}
.oauth-form-section + .oauth-form-section {
  border-top: 1px solid var(--border);
}
.oauth-form-title {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}
.oauth-form .el-form-item {
  margin-bottom: 18px;
}
.oauth-form .el-form-item__label {
  font-weight: 500;
  color: var(--text-2);
}
.oauth-form .el-select,
.oauth-form-number {
  width: 100%;
}
.oauth-form-number .el-input__inner {
  text-align: left;
}
.oauth-form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.oauth-form-tip {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-3);
  margin-top: 4px;
  width: 100%;
}
.oauth-form-fixed {
  font-size: 13px;
  color: var(--text-2);
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--accent-soft);
  width: 100%;
}

/* 选项块：名字 + 一句话说明，两列；比一排挤在一起的小复选框更易读 */
.oauth-tiles {
  display: grid;
  grid-template-columns: 1fr 1fr;
  /* el-radio-group 自带 align-items: center，会让同一行的选项块各按内容高度居中 —— 改成拉伸对齐 */
  align-items: stretch;
  gap: 10px;
  width: 100%;
}
.oauth-tiles .oauth-tile.el-checkbox,
.oauth-tiles .oauth-tile.el-radio {
  height: auto;
  margin: 0;
  padding: 10px 12px;
  align-items: flex-start;
  white-space: normal;
  border-radius: 8px;
}
.oauth-tiles .oauth-tile .el-checkbox__input,
.oauth-tiles .oauth-tile .el-radio__input {
  margin-top: 3px;
}
.oauth-tile .el-checkbox__label,
.oauth-tile .el-radio__label {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.45;
}
.oauth-tile-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-1);
}
.oauth-tile-desc {
  font-size: 12px;
  color: var(--text-3);
}
.oauth-tile.is-checked {
  background: var(--accent-soft);
}
.oauth-tile.is-disabled .oauth-tile-name {
  color: var(--text-3);
}

.oauth-scopes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.oauth-scopes .el-checkbox.is-bordered {
  margin: 0;
  border-radius: 8px;
}
.oauth-scope-desc {
  margin-left: 6px;
  font-size: 12px;
  color: var(--text-3);
}

.oauth-switches {
  border: 1px solid var(--border);
  border-radius: 8px;
  margin-bottom: 18px;
}
.oauth-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 14px;
}
.oauth-switch-row + .oauth-switch-row {
  border-top: 1px solid var(--border);
}
.oauth-switch-name {
  font-size: 13px;
  color: var(--text-1);
}

.oauth-form-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 760px) {
  .oauth-form-row,
  .oauth-tiles {
    grid-template-columns: 1fr;
  }
}
</style>
