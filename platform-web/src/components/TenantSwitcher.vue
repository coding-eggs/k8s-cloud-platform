<script setup lang="ts">
/**
 * 顶栏「当前视图 / 租户」切换器（自管轨，spec §4.4/§4.5）：
 * 数据源 POST /user/my-tenants（登录即端点，只返回**我所属**的租户）；选择 → switchTenant 重签含租户上下文的 token。
 * - 「平台视图」= base token（tenantId null），**仅平台管理员可见**（2026-10-08 起）：非管理员没有平台视图，
 *   只在自己所属的租户之间切换（bootstrap 已保证他们进来就落在某个租户里）；
 * - status≠1 的禁用租户置灰不可选（强切服务端会 invalid_grant，前端先挡住）；
 * - 切换失败（invalid_grant=无权进入/会话失效等）→ 回落 base token 这一确定态；非管理员那一刻没有上下文，
 *   但随后的整页重载会重跑 bootstrap，把他放回第一个可用租户。
 * - **换视图（平台↔租户）一律落到该视图的默认页（/overview）**：管理员切到平台视图后平台角色持有的
 *   租户族码仍在闭包里，路由守卫不会拦「当前页不属于这个视图」，不主动走开就会停在上一视图的页面上。
 *   同一视图内换租户则保留当前页（同类页面换个租户看）。
 * admin 代管轨用 ContextSelector 筛选器（不碰 token），与本组件并存互不干扰；
 * 我的租户为 0 个时整组隐藏（管理员常态停在平台视图；非管理员则确实无处可去）。
 *
 * 形态：不是 el-select。这里回答的是「我现在以什么身份在看」，不是「从表单里选一个值」，
 * 所以做成 scope chip（标识方块 + 名称 + caret）+ 自定义浮层，与顶栏其余控件同一套语言。
 * 无切换余地时（非管理员且只有一个租户）退化为静态标签，不给一个点了没反应的控件。
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
const open = ref(false)

/** my-tenants 非空即显示（0 个→隐藏，平台视图即其常态，spec §4.5） */
const visible = computed(() => tenants.value.length > 0)

/** 当前租户 id；平台视图是 '' */
const currentId = computed(() => perm.currentTenant?.tenantId ?? '')
const isPlatformView = computed(() => perm.currentTenant == null)

/** 有切换余地才给交互：管理员总能回平台视图；非管理员只有多于一个租户时才有意义 */
const canSwitch = computed(() => perm.isAdmin || tenants.value.length > 1)

/** 非管理员在 base token 的极短瞬间（bootstrap 尚未落进租户）不谎称「平台视图」 */
const displayName = computed(() => {
  if (isPlatformView.value) return perm.isAdmin ? '平台视图' : '选择租户'
  return perm.currentTenant?.tenantName ?? perm.currentTenant?.tenantId ?? ''
})

/** 标识方块里的字：租户取名字首字；平台视图用图标（见模板） */
const tenantMark = computed(() => {
  if (isPlatformView.value) return ''
  const name = displayName.value.trim()
  return name ? name.charAt(0).toUpperCase() : '租'
})

const chipTitle = computed(() => (canSwitch.value ? '切换视图 / 租户' : displayName.value))

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

function choose(value: string): void {
  open.value = false
  void onSwitch(value)
}

async function onSwitch(value: string): Promise<void> {
  if (switching.value) return
  if (value === currentId.value) return // 未变化（含重复点平台视图）
  const beforeTenant = currentId.value
  const target = tenants.value.find((t) => t.id === value)
  switching.value = true
  try {
    if (value === '') {
      // 只有平台管理员能走到这里（浮层里该选项对非管理员不渲染）
      if (await switchTenant(null)) {
        // 切视图必须离开当前页：管理员切到平台视图后，平台角色持有的租户族码仍在闭包里，
        // 守卫不会认为 /pipelines 越权 —— 不主动走开就会停在一个「不属于这个视图」的页面上。
        leaveTo('已切换到平台视图', '/overview')
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
      // 从平台视图进入租户 = 换视图 → 落到租户工作台；租户之间互切保留当前页（同类页面换个租户看）
      const enteringView = beforeTenant === ''
      leaveTo(`已进入租户「${target.name}」`, enteringView ? '/overview' : undefined)
      return
    }
    // 进入失败（invalid_grant=被移出/租户禁用 / 网络 / 签发与上下文不一致的竞态残余）：
    // 统一显式 switchTenant(null) 兜回平台视图——ok=false 时切换是零写入（旧上下文仍在），
    // ok=true 但状态对不上时拿到的 token 不可信；两种都回到「平台视图 + base token」这一确定态（spec §4.3）。
    const fell = await switchTenant(null)
    await perm.load()
    const message = fell ? `无权进入租户「${target.name}」，已回落平台视图` : '切换失败，请稍后重试'
    // 上下文实际变了（原本在租户里）→ 页面数据是旧视角，必须离开；离开租户 = 换视图 → 回总览
    if (fell && beforeTenant !== '') {
      leaveTo(message, '/overview', true)
    } else {
      ElMessage.warning(message)
    }
  } finally {
    switching.value = false
  }
}

/**
 * 上下文变更（切换成功 / 回落）→ 整页离开：token 变了，但资源上下文 store（/context 的
 * 租户→集群→ns 树）与各页已加载数据仍是旧 token 视角（base/admin 全量 vs 成员限定）。
 * 整页导航是最小且正确的复位方式——守卫会 await bootstrap（O(1) 空转）后各页用新 token 重拉。
 *
 * `path` 有值 = 换视图（平台↔租户），落到该视图的默认页；缺省 = 原地重载（同一视图内换租户）。
 * 换视图必须显式离开：平台角色同时持有租户族码，守卫不会因为「这一页不属于当前视图」而拦人。
 *
 * toast 先打出来，300ms 后离开（导航会清掉消息，留一瞬让用户看清切换落点）。
 */
function leaveTo(message: string, path?: string, warning = false): void {
  if (warning) ElMessage.warning(message)
  else ElMessage.success(message)
  window.setTimeout(() => {
    if (!path) {
      window.location.reload()
      return
    }
    // 已经在目标页：赋值同一 URL 在部分浏览器里是 no-op，token 就白换了 —— 显式 reload
    if (window.location.pathname === path) window.location.reload()
    else window.location.href = path
  }, 300)
}

onMounted(() => {
  void loadTenants()
})
</script>

<template>
  <el-popover
    v-if="visible"
    v-model:visible="open"
    placement="bottom-end"
    :width="260"
    :offset="8"
    :show-arrow="false"
    trigger="click"
    popper-class="scope-popper"
    :disabled="!canSwitch || switching || loading"
  >
    <template #reference>
      <button
        type="button"
        class="scope-chip"
        :class="{ 'is-static': !canSwitch }"
        :disabled="!canSwitch || switching || loading"
        :title="chipTitle"
      >
        <span class="scope-mark" :class="{ 'is-platform': isPlatformView }">
          <el-icon v-if="isPlatformView"><Grid /></el-icon>
          <template v-else>{{ tenantMark }}</template>
        </span>
        <span class="scope-name">{{ displayName }}</span>
        <el-icon v-if="switching" class="scope-caret is-spinning"><Loading /></el-icon>
        <el-icon v-else-if="canSwitch" class="scope-caret"><ArrowDown /></el-icon>
      </button>
    </template>

    <div class="scope-menu">
      <!-- 平台视图只属于平台管理员：非管理员没有这个选项，只能在自己所属的租户之间切换 -->
      <template v-if="perm.isAdmin">
        <button
          type="button"
          class="scope-item"
          :class="{ 'is-active': isPlatformView }"
          @click="choose('')"
        >
          <span class="scope-mark is-platform"><el-icon><Grid /></el-icon></span>
          <span class="scope-item-body">
            <span class="scope-item-name">平台视图</span>
            <span class="scope-item-hint">集群 · 租户 · 权限 · 运维</span>
          </span>
          <el-icon v-if="isPlatformView" class="scope-check"><Check /></el-icon>
        </button>
        <div class="scope-divider" />
        <div class="scope-section">我的租户</div>
      </template>

      <button
        v-for="t in tenants"
        :key="t.id"
        type="button"
        class="scope-item"
        :class="{ 'is-active': t.id === currentId, 'is-disabled': t.status !== 1 }"
        :disabled="t.status !== 1"
        @click="choose(t.id)"
      >
        <span class="scope-mark">{{ t.name.trim().charAt(0).toUpperCase() || '租' }}</span>
        <span class="scope-item-body">
          <span class="scope-item-name">{{ t.name }}</span>
          <span v-if="t.status !== 1" class="scope-item-hint">已禁用</span>
        </span>
        <el-icon v-if="t.id === currentId" class="scope-check"><Check /></el-icon>
      </button>
    </div>
  </el-popover>
</template>

<style>
/* 不用 scoped：popover 内容被 teleport 到 body，拿不到本组件 data-v
   （memory「Element Plus 弹窗 scoped CSS 坑」同源）。类名全局唯一，直接全局写。 */

/* ---------- 触发器：范围胶囊 ---------- */
.scope-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 220px;
  height: 34px;
  padding: 0 10px 0 7px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel);
  color: var(--text-1);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: border-color .15s ease, background .15s ease;
}
.scope-chip:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--border));
  background: var(--panel-hover);
}
.scope-chip:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
.scope-chip:disabled {
  cursor: default;
}
/* 无切换余地：保留完整对比度，只是不再像控件 */
.scope-chip.is-static {
  border-color: transparent;
  background: transparent;
  padding-right: 7px;
}
.scope-mark {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  background: var(--accent-soft);
  color: var(--accent);
}
.scope-mark.is-platform {
  background: var(--accent-grad);
  color: #fff;
  font-size: 11px;
}
.scope-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.scope-caret {
  font-size: 12px;
  color: var(--text-3);
  flex-shrink: 0;
}
.scope-caret.is-spinning {
  animation: scope-spin 1s linear infinite;
}
@keyframes scope-spin {
  to { transform: rotate(360deg); }
}

/* ---------- 浮层 ---------- */
.scope-popper {
  padding: 6px !important;
  border-radius: 12px !important;
  border: 1px solid var(--border) !important;
  background: var(--bg-elevated) !important;
  box-shadow: 0 24px 48px -20px rgba(0, 0, 0, .8) !important;
}
.scope-menu {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.scope-section {
  padding: 6px 10px 4px;
  font-size: 11px;
  letter-spacing: .1em;
  color: var(--text-3);
  user-select: none;
}
.scope-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-1);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background .12s ease;
}
.scope-item:hover:not(:disabled) {
  background: var(--panel-hover);
}
.scope-item.is-active {
  background: var(--accent-soft);
}
.scope-item.is-active .scope-item-name {
  color: var(--accent);
  font-weight: 600;
}
.scope-item.is-disabled {
  color: var(--text-3);
  cursor: not-allowed;
}
.scope-item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.scope-item-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.scope-item-hint {
  font-size: 11.5px;
  color: var(--text-3);
}
.scope-check {
  font-size: 14px;
  color: var(--accent);
  flex-shrink: 0;
}
.scope-divider {
  height: 1px;
  margin: 4px 6px;
  background: var(--border);
}
</style>
