<script setup lang="ts">
/**
 * 个人中心：改**自己**的密码、改**自己**的资料。
 *
 * <p>⚠️ 这一页的两个动作**后果不一样**，界面上必须说清（用户 2026-10-16 定的产品行为）：
 * - 改密码：踢掉我名下<b>其它设备</b>的会话，当前这台留着 —— 不用重登。
 * - 改资料：昵称/邮箱是**装在 token 里**的，所以**全部会话都踢掉**（含当前这台），改完回登录页。
 *
 * <p>⚠️ 还有一条必须说实话的边界：access token 是自包含 JWT（2 小时）。
 * 删会话只是让别的设备**续不了期**，它手上那张 token 最长还能再用 2 小时。
 * 别让用户以为点了按钮对方就"立刻掉线"。
 *
 * <p>⚠️ 请求打的是 **platform-auth 的源**（{@code VITE_OAUTH_ISSUER}），不是 platform-api 的 /api：
 * 这两个动作都以 HttpOnly 会话（根凭证）为准，所以要 {@code credentials:'include'}。
 * 与 {@code /session/logout}、{@code /session/keepalive} 是同一条路。
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getUserInfo, logout } from '@/auth/oauth'

const ISSUER = import.meta.env.VITE_OAUTH_ISSUER
const router = useRouter()

const info = getUserInfo()
const username = computed(() => info.sub ?? info.name ?? '')

const pw = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const profile = reactive({
  // ⚠️ token 里的 `name` 在没设昵称时会**回落到用户名** —— 那种情况下不能拿它当昵称回填，
  //    否则一保存就把"昵称 = 用户名"写进库，用户根本不知道发生了什么
  displayName: info.name && info.name !== info.sub ? info.name : '',
  email: info.email ?? '',
})

const pwBusy = ref(false)
const profileBusy = ref(false)

/** 一次自助账号请求；非 200 把后端那句原话抛出来（它比 HTTP 码有用得多）。 */
async function post(path: string, body: unknown): Promise<{ sessionsKilled: number; reloginRequired: boolean }> {
  const resp = await fetch(`${ISSUER}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const payload = (await resp.json().catch(() => null)) as
    | { code?: number; msg?: string; data?: { sessionsKilled: number; reloginRequired: boolean } }
    | null
  if (!resp.ok || !payload || payload.code !== 200) {
    throw new Error(payload?.msg || `HTTP ${resp.status}`)
  }
  return payload.data ?? { sessionsKilled: 0, reloginRequired: false }
}

async function changePassword(): Promise<void> {
  if (pw.newPassword !== pw.confirm) {
    ElMessage.warning('两次输入的新密码不一样')
    return
  }
  pwBusy.value = true
  try {
    const r = await post('/user/password', { oldPassword: pw.oldPassword, newPassword: pw.newPassword })
    pw.oldPassword = pw.newPassword = pw.confirm = ''
    ElMessage.success(
      r.sessionsKilled > 0
        ? `密码已改。这台设备保持登录；另外 ${r.sessionsKilled} 台设备的会话已被踢掉。`
        : '密码已改。这台设备保持登录（没有别的设备在线）。',
    )
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    pwBusy.value = false
  }
}

async function saveProfile(): Promise<void> {
  profileBusy.value = true
  try {
    await post('/user/profile', { displayName: profile.displayName, email: profile.email })
    ElMessage.success('已保存。昵称/邮箱装在登录凭据里，需要重新登录才会生效 —— 现在退出登录。')
    // ⚠️ 必须显式登出并回登录页：手上那张 access token 还有效（2 小时），
    //    不主动送走的话，用户会顶着一个**显示旧昵称**的界面继续用，还以为没保存成功
    await logout()
    await router.push('/login')
  } catch (e) {
    ElMessage.error((e as Error).message)
    profileBusy.value = false
  }
}
</script>

<template>
  <div class="profile-page">
    <div class="page-head">
      <h2>个人中心</h2>
      <span class="dim">{{ username }}</span>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="密码与资料都只能改自己的"
      description="改密码会踢掉你名下其它设备的登录，这台不受影响。改昵称/邮箱会让所有设备重新登录 —— 它们随登录凭据一起签发，不重登就一直显示旧值。"
      class="hint"
    />

    <div class="cards">
      <el-card shadow="never">
        <template #header>改密码</template>
        <el-form label-width="90px" @submit.prevent>
          <el-form-item label="原密码">
            <el-input v-model="pw.oldPassword" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="pw.newPassword" type="password" show-password autocomplete="new-password"
                      placeholder="至少 8 位" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="pw.confirm" type="password" show-password autocomplete="new-password"
                      @keyup.enter="changePassword" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="pwBusy" :disabled="!pw.oldPassword || !pw.newPassword"
                       @click="changePassword">
              改密码
            </el-button>
          </el-form-item>
        </el-form>
        <div class="note">
          改完之后：<b>这台设备不用重新登录</b>，你名下<b>其它设备</b>上的会话会被踢掉、续不了期。
          <br />
          <span class="dim">
            说明白一点：别的设备上<b>已经签发的那张凭据最长还能再用 2 小时</b>（它是自带的、不查服务端）。
            要做到"立刻掉线"得另加一层校验，目前没有。
          </span>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>改资料</template>
        <el-form label-width="90px" @submit.prevent>
          <el-form-item label="昵称">
            <el-input v-model="profile.displayName" maxlength="64" show-word-limit
                      placeholder="留空 = 不显示昵称（界面回落到用户名）" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="profile.email" maxlength="128" placeholder="留空 = 清掉" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="profileBusy" @click="saveProfile">保存</el-button>
          </el-form-item>
        </el-form>
        <div class="note">
          ⚠️ 保存后会<b>退出登录</b>：昵称与邮箱是随登录凭据一起签发的，不重新登录界面就一直显示旧值。
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.profile-page {
  display: flex;
  flex-direction: column;
}
.page-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
}
.hint {
  margin-bottom: 12px;
}
.cards {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.cards :deep(.el-card) {
  flex: 1 1 360px;
  min-width: 320px;
  max-width: 520px;
}
.note {
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-2);
}
.dim {
  color: var(--text-3);
}
</style>
