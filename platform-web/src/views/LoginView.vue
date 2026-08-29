<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '@/auth/oauth'

const route = useRoute()
const loading = ref(false)

async function handleLogin(): Promise<void> {
  loading.value = true
  try {
    await login() // 成功后页面即跳转，这里不会继续
  } catch (e) {
    loading.value = false
    ElMessage.error((e as Error).message || '发起登录失败')
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 背景装饰：光晕 + 网格 -->
    <div class="bg-orb orb-a" />
    <div class="bg-orb orb-b" />
    <div class="bg-grid" />

    <div class="login-card">
      <div class="brand">
        <span class="brand-mark">
          <svg viewBox="0 0 24 24" width="30" height="30" fill="none">
            <path
              d="M12 2.2 20.4 7.1v9.8L12 21.8 3.6 16.9V7.1L12 2.2Z"
              stroke="#fff"
              stroke-width="1.6"
              stroke-linejoin="round"
            />
            <circle cx="12" cy="12" r="3.2" fill="#fff" />
          </svg>
        </span>
        <h1 class="brand-title">K8s 云平台</h1>
      </div>

      <p class="subtitle">集群管理 · 租户管理 · 资源运维</p>

      <el-alert
        v-if="route.query.error"
        type="error"
        :closable="false"
        title="登录失败，请重试"
        class="error-tip"
      />

      <button class="login-btn" :disabled="loading" @click="handleLogin">
        <span v-if="loading" class="spinner" />
        {{ loading ? '正在跳转…' : '使用平台账号登录' }}
      </button>

      <p class="footnote">仅限平台管理员访问 · OAuth2 PKCE</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: radial-gradient(ellipse at 50% -20%, #16233f 0%, #0a0f1c 55%, #070b14 100%);
}

/* 光晕 */
.bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: .5;
  pointer-events: none;
}
.orb-a {
  width: 480px;
  height: 480px;
  left: -120px;
  top: -140px;
  background: radial-gradient(circle, rgba(34, 211, 238, .5), transparent 70%);
}
.orb-b {
  width: 520px;
  height: 520px;
  right: -140px;
  bottom: -160px;
  background: radial-gradient(circle, rgba(139, 92, 246, .45), transparent 70%);
}

/* 细网格 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(148, 163, 184, .05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, .05) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse at center, black 30%, transparent 75%);
  pointer-events: none;
}

.login-card {
  position: relative;
  width: 420px;
  padding: 48px 44px 36px;
  text-align: center;
  border-radius: 20px;
  background: rgba(17, 26, 46, .72);
  border: 1px solid rgba(148, 163, 184, .14);
  backdrop-filter: blur(18px);
  box-shadow: 0 24px 64px rgba(0, 0, 0, .45);
}

.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}
.brand-mark {
  width: 58px;
  height: 58px;
  border-radius: 16px;
  background: linear-gradient(135deg, #22d3ee, #8b5cf6);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 28px rgba(34, 211, 238, .35);
}
.brand-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: .04em;
  color: #e2e8f0;
}

.subtitle {
  margin: 18px 0 30px;
  font-size: 13px;
  color: #94a3b8;
  letter-spacing: .06em;
}

.error-tip {
  text-align: left;
  margin-bottom: 18px;
}

.login-btn {
  width: 100%;
  height: 46px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #22d3ee, #3b82f6);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: .04em;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all .2s;
  box-shadow: 0 6px 20px rgba(59, 130, 246, .35);
}
.login-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 10px 28px rgba(59, 130, 246, .5);
}
.login-btn:disabled {
  opacity: .7;
  cursor: not-allowed;
}

.spinner {
  width: 15px;
  height: 15px;
  border: 2px solid rgba(255, 255, 255, .4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin .8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.footnote {
  margin: 22px 0 0;
  font-size: 11.5px;
  color: #475569;
}
</style>
