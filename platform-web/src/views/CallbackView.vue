<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { handleCallback } from '@/auth/oauth'

const route = useRoute()
const router = useRouter()
const status = ref<'loading' | 'error'>('loading')

onMounted(async () => {
  const code = String(route.query.code ?? '')
  const state = String(route.query.state ?? '')
  if (!code) {
    status.value = 'error'
    return
  }
  const ok = await handleCallback(code, state)
  if (ok) {
    router.replace('/')
  } else {
    status.value = 'error'
  }
})

function goLogin(): void {
  router.replace({ name: 'login', query: { error: '1' } })
}
</script>

<template>
  <div class="callback-page">
    <template v-if="status === 'loading'">
      <el-icon class="spin" :size="36"><Loading /></el-icon>
      <p>正在完成登录…</p>
    </template>
    <template v-else>
      <el-result icon="error" title="登录失败" sub-title="换取令牌失败，请重新登录">
        <template #extra>
          <el-button type="primary" @click="goLogin">返回登录</el-button>
        </template>
      </el-result>
    </template>
  </div>
</template>

<style scoped>
.callback-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--text-2);
}
.spin {
  animation: rotating 2s linear infinite;
}
@keyframes rotating {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
