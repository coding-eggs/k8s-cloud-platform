<script setup lang="ts">
/**
 * 外部连接的表单项（协议 / 主机 / 端口 / Secret 名与键），「添加依赖」与「修改外部连接」共用。
 * 渲染在调用方的 el-form 里（不自带 el-form）；样式类沿用 app-dep-dlg__*，两个调用方都挂在带该前缀的弹窗里。
 *
 * **没有口令输入框**：口令放在与应用同命名空间的 Secret 里。Secret 名与键从该命名空间的 Secret 里选
 * （仍可手填），旁边给「新建 Secret」入口（新标签页）。打开时由调用方调 `reload()` 拉候选。
 */
import { computed } from 'vue'
import { EXTERNAL_PROTOCOLS, HOST_PATTERN, type ExternalForm } from './dependencyUtil'
import { useNamespaceSecrets, type SecretCtx } from './useNamespaceSecrets'
import AppSecretCreateLink from './AppSecretCreateLink.vue'

const props = defineProps<{
  ctx: SecretCtx
  /** 修改时协议不可改 */
  protocolLocked?: boolean
}>()
const ext = defineModel<ExternalForm>({ required: true })

const secrets = useNamespaceSecrets(computed(() => props.ctx))
const secretKeys = computed(() => secrets.keysOf(ext.value.secretName))

const hostHint = computed(() => {
  const h = ext.value.host.trim()
  if (!h) return ''
  return HOST_PATTERN.test(h) ? '' : '只能是主机名或 IPv4 地址，不要带协议、端口、路径或 user:pass@'
})
const secretHint = computed(() =>
  (ext.value.usernameKey.trim() || ext.value.passwordKey.trim()) && !ext.value.secretName.trim()
    ? '填写了键名就必须同时填写 Secret 名' : '')
const valid = computed(() => !!ext.value.host.trim() && !hostHint.value && !secretHint.value)

/** 换了 Secret：键名在新 Secret 里没有就清掉；有 username / password 键就直接选上 */
function pickSecret(name: string): void {
  ext.value.secretName = name
  const keys = secrets.keysOf(name)
  if (!keys.length) return
  const fit = (cur: string, guess: string): string =>
    keys.includes(cur) ? cur : keys.includes(guess) ? guess : ''
  ext.value.usernameKey = fit(ext.value.usernameKey, 'username')
  ext.value.passwordKey = fit(ext.value.passwordKey, 'password')
}

defineExpose({ valid, reload: secrets.reload })
</script>

<template>
  <el-form-item label="协议" required>
    <el-select v-model="ext.protocol" :disabled="protocolLocked" style="width: 100%">
      <el-option v-for="p in EXTERNAL_PROTOCOLS" :key="p" :label="p" :value="p" />
    </el-select>
    <div v-if="protocolLocked" class="app-dep-dlg__tip">协议不能修改；要换协议请新增一个依赖。</div>
  </el-form-item>
  <el-form-item label="主机" required>
    <el-input v-model="ext.host" spellcheck="false" placeholder="主机名或 IPv4，如 db.internal.example.com" />
    <div v-if="hostHint" class="app-dep-dlg__err">{{ hostHint }}</div>
  </el-form-item>
  <el-form-item label="端口">
    <el-input-number v-model="ext.port" :min="1" :max="65535" :controls="false" placeholder="可留空" />
  </el-form-item>
  <el-form-item label="Secret 名">
    <div class="app-dep-dlg__row">
      <el-select
        :model-value="ext.secretName"
        :loading="secrets.loading.value"
        filterable
        allow-create
        clearable
        default-first-option
        placeholder="选择或输入与应用同命名空间的 Secret"
        no-data-text="本命名空间没有 Secret"
        style="flex: 1"
        @update:model-value="(v: string) => pickSecret(v ?? '')"
      >
        <el-option v-for="s in secrets.options.value" :key="s.name" :label="s.name" :value="s.name">
          <span>{{ s.name }}</span>
          <span class="app-dep-dlg__opt-hint">{{ s.keys.join(', ') || '无键' }}</span>
        </el-option>
      </el-select>
      <AppSecretCreateLink :ctx="ctx" :loading="secrets.loading.value" @refresh="secrets.reload" />
    </div>
  </el-form-item>
  <el-form-item label="用户名键">
    <el-select v-model="ext.usernameKey" filterable allow-create clearable default-first-option placeholder="Secret 里存用户名的键，如 username" :no-data-text="ext.secretName ? '没有候选键，可手填' : '先选 Secret'" style="width: 100%">
      <el-option v-for="k in secretKeys" :key="k" :label="k" :value="k" />
    </el-select>
  </el-form-item>
  <el-form-item label="密码键">
    <el-select v-model="ext.passwordKey" filterable allow-create clearable default-first-option placeholder="Secret 里存密码的键，如 password" :no-data-text="ext.secretName ? '没有候选键，可手填' : '先选 Secret'" style="width: 100%">
      <el-option v-for="k in secretKeys" :key="k" :label="k" :value="k" />
    </el-select>
    <div v-if="secretHint" class="app-dep-dlg__err">{{ secretHint }}</div>
    <div class="app-dep-dlg__tip">平台不接收明文口令：Secret 建在命名空间 {{ ctx.namespace }}（可点「新建 Secret」在新标签页创建，建好回来点「刷新」），这里只登记它的名字和键名。</div>
  </el-form-item>
</template>

<style>
/* 渲染在调用方的 el-dialog 里，scoped 打不到；调用方的弹窗都带 app-dep-dlg 类 */
.app-dep-dlg .app-dep-dlg__err { color: var(--el-color-danger); font-size: 12px; line-height: 1.5; }
.app-dep-dlg .app-dep-dlg__tip { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.app-dep-dlg .app-dep-dlg__row { display: flex; align-items: center; gap: 8px; width: 100%; }
.app-dep-dlg__opt-hint { float: right; margin-left: 12px; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
