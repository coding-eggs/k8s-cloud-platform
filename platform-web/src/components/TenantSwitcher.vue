<script setup lang="ts">
/**
 * 顶栏租户切换器（自管轨，spec §4.4/§4.5）：
 * 数据源 POST /user/my-tenants（登录即端点，只返回**我所属**的租户）；选择 → switchTenant 重签含租户上下文的 token。
 * - 「平台视图」= base token（tenantId null），**仅平台管理员可见**（2026-10-08 起）：非管理员没有平台视图，
 *   只在自己所属的租户之间切换（bootstrap 已保证他们进来就落在某个租户里）；
 * - status≠1 的禁用租户置灰不可选（强切服务端会 invalid_grant，前端先挡住）；
 * - 切换失败（invalid_grant=无权进入/会话失效等）→ 回落 base token 这一确定态；非管理员那一刻没有上下文，
 *   但随后的整页重载会重跑 bootstrap，把他放回第一个可用租户。
 * admin 代管轨用 ContextSelector 筛选器（不碰 token），与本组件并存互不干扰；
 * 我的租户为 0 个时整组隐藏（管理员常态停在平台视图；非管理员则确实无处可去）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { switchTenant } from '@/auth/oauth'
import { userApi } from '@/api'
import { usePermission } from '@/stores/permission'
import { sortTenantsDefaultFirst } from '@/utils/tenantOrder'
import type { PlatformTenant } from '@/types'

const perm = usePermission()

const tenants = ref<PlatformTenant[]>([])
const loading = ref(false)
const switching = ref(false)

/** my-tenants 非空即显示（0 个→隐藏，平台视图即其常态，spec §4.5） */
const visible = computed(() => tenants.value.length > 0)

/** 当前选中项：平台视图用空串当 el-select 的 value。
 *  EP 默认把 '' 也算「空值」（emptyValues 含 ''/null/undefined）→ 会显示 placeholder 而非「平台视图」，
 *  故下方显式 :empty-values="[null, undefined]" 让 '' 成为一个正常可显示的选项值。
 *  非管理员没有 '' 这个选项，极短的无上下文瞬间（base token，bootstrap 尚未落进租户）会显示
 *  placeholder「选择租户」，而不是一片空白。 */
const modelValue = computed(() => perm.currentTenant?.tenantId ?? '')

async function loadTenants(): Promise<void> {
  loading.value = true
  try {
    tenants.value = sortTenantsDefaultFirst(await userApi.myTenants())
  } catch {
    // 拉不到（会话失效由 http.ts 统一跳登录）→ 保持隐藏，不打扰
  } finally {
    loading.value = false
  }
}

async function onSwitch(value: string): Promise<void> {
  if (switching.value) return
  if (value === (perm.currentTenant?.tenantId ?? '')) return // 未变化（含重复点平台视图）
  const beforeTenant = perm.currentTenant?.tenantId ?? ''
  const target = tenants.value.find((t) => t.id === value)
  switching.value = true
  try {
    if (value === '') {
      // 只有平台管理员能走到这里（模板里该选项对非管理员不渲染）
      if (await switchTenant(null)) {
        reloadIntoContext('已切换到平台视图')
      } else {
        ElMessage.error('切换失败，请稍后重试')
      }
      return
    }
    if (!target) {
      ElMessage.warning('无效的租户选项，请刷新后重试')
      return
    }
    const ok = await switchTenant(target.id, target.name)
    if (ok && perm.currentTenant?.tenantId === target.id && target.status === 1) {
      reloadIntoContext(`已进入租户「${target.name}」`)
      return
    }
    // 进入失败（invalid_grant=被移出/租户禁用 / 网络 / 签发与上下文不一致的竞态残余）：
    // 统一显式 switchTenant(null) 兜回平台视图——ok=false 时切换是零写入（旧上下文仍在），
    // ok=true 但状态对不上时拿到的 token 不可信；两种都回到「平台视图 + base token」这一确定态（spec §4.3）。
    const fell = await switchTenant(null)
    await perm.load()
    const message = fell ? `无权进入租户「${target.name}」，已回落平台视图` : '切换失败，请稍后重试'
    // 上下文实际变了（原本在租户里）→ 页面数据是旧视角，整页重载复位；原本就在平台视图则只提示
    if (fell && beforeTenant !== '') {
      reloadIntoContext(message, true)
    } else {
      ElMessage.warning(message)
    }
  } finally {
    switching.value = false
  }
}

/**
 * 上下文变更（切换成功 / 回落）→ 整页重载：token 变了，但资源上下文 store（/context 的
 * 租户→集群→ns 树）与各页已加载数据仍是旧 token 视角（base/admin 全量 vs 成员限定）。
 * 整页重载是最小且正确的复位方式——守卫会 await bootstrap（O(1) 空转）后各页用新 token 重拉。
 * toast 先打出来，300ms 后重载（reload 会清掉消息，留一瞬让用户看清切换落点）。
 */
function reloadIntoContext(message: string, warning = false): void {
  if (warning) ElMessage.warning(message)
  else ElMessage.success(message)
  window.setTimeout(() => window.location.reload(), 300)
}

onMounted(() => {
  void loadTenants()
})
</script>

<template>
  <el-select
    v-if="visible"
    :model-value="modelValue"
    class="tenant-switcher"
    popper-class="tenant-switcher-dropdown"
    :loading="loading"
    :disabled="switching"
    :empty-values="[null, undefined]"
    :placeholder="perm.isAdmin ? '' : '选择租户'"
    size="small"
    @update:model-value="onSwitch(String($event))"
  >
    <template #prefix>
      <el-icon><OfficeBuilding /></el-icon>
    </template>
    <!-- 平台视图只属于平台管理员：非管理员没有这个选项，只能在自己所属的租户之间切换 -->
    <el-option v-if="perm.isAdmin" label="平台视图" value="">
      <span class="opt-platform">平台视图</span>
      <span class="opt-hint">（租户管理 / 集群管理）</span>
    </el-option>
    <el-option
      v-for="t in tenants"
      :key="t.id"
      :label="t.name"
      :value="t.id"
      :disabled="t.status !== 1"
    >
      <span :class="{ 'opt-disabled': t.status !== 1 }">{{ t.name }}</span>
      <span v-if="t.status !== 1" class="opt-hint">（已禁用）</span>
    </el-option>
  </el-select>
</template>

<style>
/* 不用 scoped：el-select 内部节点（含 popper 里的选项）拿不到本组件 data-v，
   scoped :deep() 匹配不到（memory「Element Plus 弹窗 scoped CSS 坑」同源）。
   触发器样式挂 .tenant-switcher，下拉项样式挂 popper-class（弹层 teleport 到 body，不在 .tenant-switcher 内） */
.tenant-switcher {
  width: 190px;
}
.tenant-switcher-dropdown .opt-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-3);
}
.tenant-switcher-dropdown .opt-disabled {
  color: var(--text-3);
}
</style>
