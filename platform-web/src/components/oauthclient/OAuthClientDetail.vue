<script setup lang="ts">
import type { OAuthClient } from '@/types/oauthClient'
import { authMethodLabel, grantLabel } from './options'

/** 只读详情（旧管理页的「客户端详情」）。不含任何密钥材料，只有「有没有」与 kid */
defineProps<{ modelValue: boolean; client: OAuthClient | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

function seconds(v: number | null): string {
  if (v == null) return '—'
  if (v % 3600 === 0) return `${v} 秒（${v / 3600} 小时）`
  if (v % 60 === 0) return `${v} 秒（${v / 60} 分钟）`
  return `${v} 秒`
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="`客户端详情 · ${client?.clientName || client?.clientId || ''}`"
    width="680px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-descriptions v-if="client" :column="1" border class="oauth-detail">
      <el-descriptions-item label="client_id"><code>{{ client.clientId }}</code></el-descriptions-item>
      <el-descriptions-item label="名称">
        {{ client.clientName || '—' }}
        <el-tag v-if="client.builtin" size="small" effect="plain">内置</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="类型">{{ client.type === 'PUBLIC' ? '公共' : '机密' }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ client.enabled ? '启用' : '停用' }}</el-descriptions-item>
      <el-descriptions-item label="授权方式">
        <el-tag v-for="g in client.grantTypes" :key="g" size="small" class="oauth-detail-tag">{{ grantLabel(g) }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="认证方式">
        <el-tag v-for="m in client.clientAuthenticationMethods" :key="m" size="small" type="info" class="oauth-detail-tag">
          {{ authMethodLabel(m) }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="scope">
        <el-tag v-for="s in client.scopes" :key="s" size="small" type="info" class="oauth-detail-tag">{{ s }}</el-tag>
        <span v-if="client.scopes.length === 0">—</span>
      </el-descriptions-item>
      <el-descriptions-item label="回调地址">
        <div v-for="u in client.redirectUris" :key="u" class="oauth-detail-uri">{{ u }}</div>
        <span v-if="client.redirectUris.length === 0">—</span>
      </el-descriptions-item>
      <el-descriptions-item label="登出回调">
        <div v-for="u in client.postLogoutRedirectUris" :key="u" class="oauth-detail-uri">{{ u }}</div>
        <span v-if="client.postLogoutRedirectUris.length === 0">—</span>
      </el-descriptions-item>
      <el-descriptions-item label="PKCE">{{ client.requireProofKey ? '强制' : '不强制' }}</el-descriptions-item>
      <el-descriptions-item label="用户确认授权">{{ client.requireAuthorizationConsent ? '需要' : '不需要' }}</el-descriptions-item>
      <el-descriptions-item label="Access Token">{{ seconds(client.accessTokenTimeToLive) }}</el-descriptions-item>
      <el-descriptions-item label="Refresh Token">{{ seconds(client.refreshTokenTimeToLive) }}</el-descriptions-item>
      <el-descriptions-item label="token 形态">
        {{ client.jwtType }} · 签名 {{ client.jwsSigAlg }}
        <template v-if="client.jwtType === 'JWE'"> · 加密 {{ client.jweKeyAlg }} / {{ client.jweEncMethod || 'A256GCM' }}</template>
      </el-descriptions-item>
      <el-descriptions-item label="对方公钥地址">{{ client.jweJwkUrl || '—' }}</el-descriptions-item>
      <el-descriptions-item label="密钥">
        <div>client_secret：{{ client.hasClientSecret ? '已设置' : '无' }}</div>
        <div>JWS 签名密钥：{{ client.hasJwsSecret ? `已设置（kid ${client.jwsSecretKid ?? '—'}）` : '无' }}</div>
        <div>JWE 加密密钥：{{ client.hasJweSecret ? `已设置（kid ${client.jweSecretKid ?? '—'}）` : '无' }}</div>
      </el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ client.clientIdIssuedAt || '—' }}</el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<style>
/* 非 scoped：el-dialog 渲染到 body，内部节点不带 data-v */
.oauth-detail .el-descriptions__label {
  width: 120px;
}
.oauth-detail-tag {
  margin: 0 6px 4px 0;
}
.oauth-detail-uri {
  font-size: 12px;
  word-break: break-all;
}
</style>
