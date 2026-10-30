<script setup lang="ts">
/**
 * 页面搜索面板（Ctrl / Cmd + K）。
 *
 * 纯前端：数据来自 nav.ts 的当前视图导航，不请求后端。
 * 只搜「当前视图可见」的页面 —— 与侧栏同源，因此不会搜到正在显示的菜单里没有的东西。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { buildNav, type NavDomainKey } from '@/nav'
import { usePermission } from '@/stores/permission'
import { readRecentVisits } from '@/composables/useRecentVisits'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const router = useRouter()
const perm = usePermission()

interface Entry {
  title: string
  path: string
  icon: string
  domain: NavDomainKey
  domainLabel: string
}

const keyword = ref('')
const cursor = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

const face = computed(() => (perm.currentTenant ? 'tenant' : 'platform'))

const entries = computed<Entry[]>(() =>
  buildNav(face.value, perm).flatMap((d) =>
    d.items.map((i) => ({ title: i.title, path: i.path, icon: i.icon, domain: d.key, domainLabel: d.label })),
  ),
)

const results = computed<Entry[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return []
  return entries.value
    .filter((e) => e.title.toLowerCase().includes(k) || e.domainLabel.includes(k))
    .slice(0, 12)
})

const recent = computed(() => readRecentVisits().filter((v) => v.face === face.value).slice(0, 5))

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    keyword.value = ''
    cursor.value = 0
    await nextTick()
    inputEl.value?.focus()
  },
)

watch(results, () => {
  cursor.value = 0
})

function close(): void {
  emit('update:modelValue', false)
}

function goPath(path: string): void {
  close()
  void router.push(path)
}

function choose(entry?: Entry): void {
  const target = entry ?? results.value[cursor.value]
  if (target) goPath(target.path)
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    close()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    cursor.value = Math.min(cursor.value + 1, Math.max(results.value.length - 1, 0))
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    cursor.value = Math.max(cursor.value - 1, 0)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    choose()
  }
}
</script>

<template>
  <div v-if="modelValue" class="cp-mask" @click.self="close">
    <div class="cp-panel" role="dialog" aria-label="页面搜索">
      <div class="cp-input-row">
        <el-icon class="cp-search-icon"><Search /></el-icon>
        <input
          ref="inputEl"
          v-model="keyword"
          class="cp-input"
          placeholder="搜索页面…"
          @keydown="onKeydown"
        />
        <kbd class="cp-kbd">Esc</kbd>
      </div>

      <div class="cp-list">
        <template v-if="keyword.trim()">
          <button
            v-for="(e, i) in results"
            :key="e.path"
            type="button"
            class="cp-item"
            :class="{ 'is-active': i === cursor }"
            @mousemove="cursor = i"
            @click="choose(e)"
          >
            <el-icon class="cp-item-icon"><component :is="e.icon" /></el-icon>
            <span class="cp-item-title">{{ e.title }}</span>
            <span class="cp-item-domain">{{ e.domainLabel }}</span>
          </button>
          <p v-if="!results.length" class="cp-empty">没有匹配的页面</p>
        </template>

        <template v-else>
          <p class="cp-group-title">最近访问</p>
          <button
            v-for="v in recent"
            :key="v.path"
            type="button"
            class="cp-item"
            @click="goPath(v.path)"
          >
            <el-icon class="cp-item-icon"><Clock /></el-icon>
            <span class="cp-item-title">{{ v.title }}</span>
            <span class="cp-item-domain">{{ v.domainLabel }}</span>
          </button>
          <p v-if="!recent.length" class="cp-empty">输入页面名开始搜索</p>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cp-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(2, 6, 17, .55);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 14vh;
}
.cp-panel {
  width: 560px;
  max-width: 92vw;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 14px;
  box-shadow: 0 24px 64px -12px rgba(0, 0, 0, .6);
  overflow: hidden;
}
.cp-input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
}
.cp-search-icon {
  font-size: 16px;
  color: var(--text-3);
}
.cp-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text-1);
  font: inherit;
  font-size: 15px;
}
.cp-input::placeholder {
  color: var(--text-3);
}
.cp-kbd {
  font-size: 11px;
  color: var(--text-3);
  border: 1px solid var(--border);
  border-radius: 5px;
  padding: 1px 6px;
}
.cp-list {
  max-height: 46vh;
  overflow-y: auto;
  padding: 6px;
}
.cp-group-title {
  margin: 6px 10px 4px;
  font-size: 11px;
  letter-spacing: .1em;
  color: var(--text-3);
}
.cp-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-1);
  font: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
}
.cp-item.is-active {
  background: var(--accent-soft);
  color: var(--accent);
}
.cp-item-icon {
  font-size: 15px;
  color: var(--text-3);
}
.cp-item.is-active .cp-item-icon {
  color: var(--accent);
}
.cp-item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cp-item-domain {
  font-size: 11px;
  color: var(--text-3);
}
.cp-empty {
  margin: 16px 12px;
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
