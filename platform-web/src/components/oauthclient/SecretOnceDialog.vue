<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { writeClipboard } from '@/utils/clipboard'
import type { OAuthClientSecrets } from '@/types/oauthClient'

/**
 * 一次性展示新生成的密钥。平台只存哈希 / 密文，关掉这个弹窗就再也看不到明文 ——
 * 丢了只能重置。复制走 writeClipboard（http 内网下 navigator.clipboard 不存在，有 execCommand 兜底）。
 */
const props = defineProps<{ modelValue: boolean; clientId: string; secrets: OAuthClientSecrets | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const items = computed(() => {
  const s = props.secrets
  if (!s) return []
  return [
    { label: 'client_secret', value: s.clientSecret, tip: '对方后端换 token 时用（HTTP Basic）' },
    { label: 'JWS 签名密钥', value: s.jwsSecret, tip: '对方用它校验 token 签名（HMAC）' },
    { label: 'JWE 加密密钥', value: s.jweSecret, tip: '对方用它解密 token' },
  ].filter((i): i is { label: string; value: string; tip: string } => !!i.value)
})

async function copy(text: string): Promise<void> {
  if (await writeClipboard(text)) ElMessage.success('已复制')
  else ElMessage.error('复制失败，请手动选中复制')
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="请立即保存密钥"
    width="560px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert type="warning" :closable="false" show-icon
      title="关闭后不再显示。平台不保存明文，丢失只能重置（旧密钥随即失效）。" />
    <div class="oauth-secret-client">client_id：<code>{{ clientId }}</code></div>
    <div v-for="i in items" :key="i.label" class="oauth-secret-row">
      <div class="oauth-secret-label">{{ i.label }}</div>
      <div class="oauth-secret-value">
        <el-input :model-value="i.value" readonly />
        <el-button @click="copy(i.value)">复制</el-button>
      </div>
      <div class="oauth-secret-tip">{{ i.tip }}</div>
    </div>
    <template #footer>
      <el-button type="primary" @click="emit('update:modelValue', false)">我已保存</el-button>
    </template>
  </el-dialog>
</template>

<style>
/* 非 scoped：el-dialog 渲染到 body，内部节点不带 data-v，scoped 选择器匹配不到；用独有前缀防串 */
.oauth-secret-client {
  margin: 12px 0 4px;
  font-size: 13px;
}
.oauth-secret-row {
  margin-top: 12px;
}
.oauth-secret-label {
  font-size: 13px;
  margin-bottom: 4px;
}
.oauth-secret-value {
  display: flex;
  gap: 8px;
}
.oauth-secret-value .el-input__inner {
  font-family: var(--el-font-family-mono, monospace);
}
.oauth-secret-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
