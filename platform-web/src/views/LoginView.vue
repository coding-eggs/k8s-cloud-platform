<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { login } from '@/auth/oauth'

const route = useRoute()
const error = ref(route.query.error ? '登录失败，请重试' : '')
const loading = ref(false)

async function startLogin(): Promise<void> {
  if (loading.value) return

  error.value = ''
  loading.value = true
  try {
    await login()
  } catch {
    error.value = '无法发起登录，请重试'
    loading.value = false
  }
}

onMounted(() => {
  if (!error.value) void startLogin()
})
</script>

<template>
  <main class="login-page">
    <section class="login-status" aria-labelledby="login-brand">
      <h1 id="login-brand" lang="en">K8s Cloud Platform</h1>
      <template v-if="error">
        <p class="login-error" role="alert">{{ error }}</p>
        <el-button type="primary" @click="startLogin">重新登录</el-button>
      </template>
      <p v-else role="status">正在进入统一认证…</p>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  min-height: 100dvh;
  place-items: center;
  padding: 24px 16px;
  background: radial-gradient(ellipse at 50% -20%, #16233f 0%, #0a0f1c 55%, #070b14 100%);
}

.login-status {
  text-align: center;
  color: #e2e8f0;
}

.login-status h1 {
  margin: 0;
  font-size: 24px;
}

.login-status p {
  margin: 10px 0 0;
  color: #aebdd2;
}

.login-status .login-error {
  margin-bottom: 16px;
  color: #fecdd3;
}
</style>
