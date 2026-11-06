<script setup lang="ts">
/**
 * 页面搜索面板（Ctrl / Cmd + K）。
 *
 * 纯前端：数据来自 nav.ts 的导航（与侧栏同源），不请求后端。搜索范围：
 * - 当前视图的菜单页，以及这些页上声明的页内功能（路由 meta.actions，如「创建 ConfigMap」）；
 * - 管理员额外搜另一个视图（排在当前视图之后，带「平台视图 / 租户视图」标记，点了直接深链过去）；
 * - 匹配：标题 / 关键词（meta.keywords）/ 拼音全拼与首字母 / 域名 / 路径，空格分隔 = 同时满足。
 *   拼音库（pinyin-pro）首次打开面板时才懒加载。
 */
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { buildSearchEntries, type NavFace, type SearchEntry } from '@/nav'
import { usePermission } from '@/stores/permission'
import { readRecentVisits } from '@/composables/useRecentVisits'
import { PALETTE_ACTION_QUERY } from '@/composables/usePaletteAction'
import { scoreEntry, type PinyinForms, type PinyinOf } from '@/utils/paletteSearch'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const router = useRouter()
const perm = usePermission()

const MAX_RESULTS = 20

const keyword = ref('')
const cursor = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

const face = computed<NavFace>(() => (perm.currentTenant ? 'tenant' : 'platform'))

const entries = computed<SearchEntry[]>(() => buildSearchEntries(face.value, perm, perm.isAdmin))

// ---------- 拼音：懒加载 + 按文本缓存 ----------
const pinyinOf = shallowRef<PinyinOf | null>(null)
let pinyinLoading = false

async function ensurePinyin(): Promise<void> {
  if (pinyinOf.value || pinyinLoading) return
  pinyinLoading = true
  try {
    const { pinyin } = await import('pinyin-pro')
    const cache = new Map<string, PinyinForms>()
    pinyinOf.value = (text: string) => {
      let hit = cache.get(text)
      if (!hit) {
        // 只对中文部分取拼音：英文段混进来会拼出跨词假命中（「RBAC 模板」的 rbac·muban 里有 cm）
        const zh = text.replace(/[^一-鿿]/g, '')
        const strip = (s: string) => s.toLowerCase().replace(/[^a-z]/g, '')
        hit = {
          full: strip(pinyin(zh, { toneType: 'none', separator: '' })),
          initials: strip(pinyin(zh, { pattern: 'first', toneType: 'none', separator: '' })),
        }
        cache.set(text, hit)
      }
      return hit
    }
  } catch {
    // 拉取失败（断网 / 部署缺 chunk）：没有拼音匹配而已，其余照常
  } finally {
    pinyinLoading = false
  }
}

const results = computed<SearchEntry[]>(() => {
  const k = keyword.value.trim()
  if (!k) return []
  return entries.value
    .map((e, index) => ({ e, index, score: scoreEntry(k, e, pinyinOf.value) }))
    .filter((r) => r.score > 0)
    // 当前视图在前；同视图内按分数；同分时页面先于页内功能，再按导航原序
    .sort(
      (a, b) =>
        Number(a.e.otherFace) - Number(b.e.otherFace) ||
        b.score - a.score ||
        Number(a.e.kind === 'action') - Number(b.e.kind === 'action') ||
        a.index - b.index,
    )
    .slice(0, MAX_RESULTS)
    .map((r) => r.e)
})

/** 结果列表里另一个视图的第一条之前插一个分组标题 */
const firstOtherIndex = computed(() => results.value.findIndex((e) => e.otherFace))

function faceLabel(f: NavFace): string {
  return f === 'platform' ? '平台视图' : '租户视图'
}

const recent = computed(() => readRecentVisits().filter((v) => v.face === face.value).slice(0, 5))

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    keyword.value = ''
    cursor.value = 0
    void ensurePinyin()
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

function choose(entry?: SearchEntry): void {
  const target = entry ?? results.value[cursor.value]
  if (!target) return
  close()
  void router.push(
    target.action ? { path: target.path, query: { [PALETTE_ACTION_QUERY]: target.action } } : target.path,
  )
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
          placeholder="搜索页面或功能，支持拼音 / 英文名，空格分隔多个词…"
          @keydown="onKeydown"
        />
        <kbd class="cp-kbd">Esc</kbd>
      </div>

      <div class="cp-list">
        <template v-if="keyword.trim()">
          <template v-for="(e, i) in results" :key="e.path + '#' + (e.action ?? '')">
            <p v-if="i === firstOtherIndex" class="cp-group-title">{{ faceLabel(e.face) }}</p>
            <button
              type="button"
              class="cp-item"
              :class="{ 'is-active': i === cursor }"
              @mousemove="cursor = i"
              @click="choose(e)"
            >
              <el-icon class="cp-item-icon">
                <Plus v-if="e.kind === 'action'" />
                <component :is="e.icon" v-else />
              </el-icon>
              <span class="cp-item-title">
                {{ e.title }}
                <span v-if="e.kind === 'action'" class="cp-item-sub">· {{ e.pageTitle }}</span>
              </span>
              <span v-if="e.otherFace" class="cp-item-face">{{ faceLabel(e.face) }}</span>
              <span class="cp-item-domain">{{ e.domainLabel }}</span>
            </button>
          </template>
          <p v-if="!results.length" class="cp-empty">没有匹配的页面或功能</p>
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
          <p v-if="!recent.length" class="cp-empty">输入页面名或功能开始搜索</p>
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
.cp-item-sub {
  font-size: 12px;
  color: var(--text-3);
}
.cp-item-face {
  font-size: 11px;
  color: var(--text-2);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 5px;
}
.cp-empty {
  margin: 16px 12px;
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
