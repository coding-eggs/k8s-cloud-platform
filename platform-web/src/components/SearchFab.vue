<script setup lang="ts">
/**
 * 悬浮搜索入口（可自由拖动）。
 *
 * 拖动与点击的区分：按下后位移超过 4px 才算拖动；未越过阈值就抬起 → 打开命令面板。
 * 拖动结束把 left/top 写进 localStorage，刷新后回到原位；窗口变小时重新夹回视口内。
 *
 * 位置用 left/top 而不是原来的 right/bottom —— 一旦用户自己摆过，就完全由他决定，
 * 所以内联样式把 right/bottom 显式设成 auto（否则固定定位下 left+right 同时存在会把胶囊拉宽）。
 *
 * 拖动期间关闭过渡与 hover 变换，让按钮严格跟手。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

const emit = defineEmits<{ open: [] }>()

const POS_KEY = 'platform_search_fab_pos'
/** 小于这个位移视为点击，避免手抖把按钮挪走 */
const DRAG_THRESHOLD = 4
/** 与视口边缘至少留这么多像素 */
const EDGE = 8

const fabEl = ref<HTMLElement | null>(null)
const pos = ref<{ left: number; top: number } | null>(null)
const dragging = ref(false)

let pointerId: number | null = null
let startX = 0
let startY = 0
let originX = 0
let originY = 0
let moved = false
/** pointerup 之后紧随的 click 要吞掉，否则拖完会顺手把面板打开 */
let justDragged = false

function clamp(p: { left: number; top: number }): { left: number; top: number } {
  const el = fabEl.value
  const w = el?.offsetWidth ?? 120
  const h = el?.offsetHeight ?? 40
  const maxLeft = Math.max(EDGE, window.innerWidth - w - EDGE)
  const maxTop = Math.max(EDGE, window.innerHeight - h - EDGE)
  return {
    left: Math.min(Math.max(EDGE, p.left), maxLeft),
    top: Math.min(Math.max(EDGE, p.top), maxTop),
  }
}

function restore(): void {
  try {
    const raw = JSON.parse(localStorage.getItem(POS_KEY) ?? 'null') as { left?: number; top?: number } | null
    if (raw && Number.isFinite(raw.left) && Number.isFinite(raw.top)) {
      pos.value = clamp({ left: raw.left as number, top: raw.top as number })
    }
  } catch {
    /* 损坏的 JSON / 隐私模式：用默认右下角 */
  }
}

function persist(): void {
  try {
    if (pos.value) localStorage.setItem(POS_KEY, JSON.stringify(pos.value))
  } catch {
    /* 不记即可 */
  }
}

function onPointerDown(e: PointerEvent): void {
  if (e.button !== 0) return // 只认左键/单指
  const el = fabEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  startX = e.clientX
  startY = e.clientY
  originX = rect.left
  originY = rect.top
  moved = false
  pointerId = e.pointerId
  try {
    el.setPointerCapture(e.pointerId)
  } catch {
    /* 指针已失效时浏览器会抛：不捕获也能拖，只是移出元素后会丢事件 */
  }
}

function onPointerMove(e: PointerEvent): void {
  if (pointerId === null || e.pointerId !== pointerId) return
  const dx = e.clientX - startX
  const dy = e.clientY - startY
  if (!moved && Math.hypot(dx, dy) < DRAG_THRESHOLD) return
  moved = true
  dragging.value = true
  pos.value = clamp({ left: originX + dx, top: originY + dy })
}

function endDrag(e: PointerEvent): void {
  if (pointerId === null) return
  try {
    fabEl.value?.releasePointerCapture?.(pointerId)
  } catch {
    /* 未捕获 / 指针已释放：忽略 */
  }
  pointerId = null
  dragging.value = false
  if (moved) {
    justDragged = true
    persist()
    window.setTimeout(() => {
      justDragged = false
    }, 0)
  }
  moved = false
}

function onClick(): void {
  if (justDragged) return
  emit('open')
}

/** 窗口变小可能把按钮留在视口外：重新夹一次 */
function onResize(): void {
  if (pos.value) pos.value = clamp(pos.value)
}

onMounted(() => {
  restore()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => window.removeEventListener('resize', onResize))
</script>

<template>
  <button
    ref="fabEl"
    type="button"
    class="search-fab"
    :class="{ 'is-dragging': dragging, 'is-placed': !!pos }"
    :style="pos ? { left: `${pos.left}px`, top: `${pos.top}px`, right: 'auto', bottom: 'auto' } : undefined"
    aria-label="搜索页面（Ctrl K）"
    title="搜索页面（Ctrl K）· 可拖动"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="endDrag"
    @pointercancel="endDrag"
    @click="onClick"
  >
    <el-icon class="search-fab-icon"><Search /></el-icon>
    <span class="search-fab-text">搜索</span>
    <kbd class="search-fab-kbd">Ctrl K</kbd>
  </button>
</template>

<style scoped>
/* 玻璃胶囊：默认安静，悬停才亮。唯一的强调元素是右侧那枚键帽。 */
.search-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1400; /* 低于命令面板(3000)与 EP 弹层(2000+) */
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 9px 0 12px;
  border: 1px solid color-mix(in srgb, var(--accent) 18%, var(--border));
  border-radius: 999px;
  background: color-mix(in srgb, var(--bg-elevated) 78%, transparent);
  backdrop-filter: blur(14px) saturate(140%);
  color: var(--text-1);
  font: inherit;
  font-size: 13px;
  cursor: grab;
  touch-action: none; /* 触摸拖动时不要顺带滚动页面 */
  user-select: none;
  box-shadow:
    0 10px 30px -14px rgba(0, 0, 0, .75),
    inset 0 1px 0 color-mix(in srgb, #fff 7%, transparent);
  transition: border-color .18s ease, color .18s ease, box-shadow .18s ease, transform .18s ease;
  /* backwards 而非 both：动画结束后不再占着 transform，否则 hover 的上浮会被动画终值压住 */
  animation: search-fab-in .28s cubic-bezier(.2, .8, .3, 1) backwards;
}
@keyframes search-fab-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}
.search-fab:hover {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 55%, var(--border));
  box-shadow:
    0 16px 36px -14px color-mix(in srgb, var(--accent) 50%, transparent),
    inset 0 1px 0 color-mix(in srgb, #fff 9%, transparent);
  transform: translateY(-1px);
}
.search-fab:active {
  transform: translateY(0) scale(.985);
}
.search-fab:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
/* 拖动中：严格跟手，不要过渡、不要 hover 变换 */
.search-fab.is-dragging,
.search-fab.is-dragging:hover,
.search-fab.is-dragging:active {
  cursor: grabbing;
  transform: none;
  transition: none;
  border-color: color-mix(in srgb, var(--accent) 70%, var(--border));
  box-shadow:
    0 22px 44px -16px color-mix(in srgb, var(--accent) 60%, transparent),
    inset 0 1px 0 color-mix(in srgb, #fff 12%, transparent);
}
/* 用户自己摆过位置后，右下角的默认偏移不再参与布局 */
.search-fab.is-placed {
  right: auto;
  bottom: auto;
}
.search-fab-icon {
  font-size: 15px;
  color: var(--accent);
}
.search-fab-text {
  font-weight: 500;
}
.search-fab-kbd {
  font: inherit;
  font-size: 10.5px;
  letter-spacing: .02em;
  color: var(--text-3);
  border: 1px solid var(--border);
  border-bottom-width: 2px;
  border-radius: 5px;
  padding: 0 5px 1px;
  transition: color .18s ease, border-color .18s ease;
}
.search-fab:hover .search-fab-kbd {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 42%, var(--border));
}
/* 窄屏：收起文字与键帽，只剩圆形图标，别挡内容 */
@media (max-width: 1279px) {
  .search-fab {
    right: 16px;
    bottom: 16px;
    width: 46px;
    height: 46px;
    padding: 0;
    justify-content: center;
    border-radius: 50%;
  }
  .search-fab-text,
  .search-fab-kbd {
    display: none;
  }
  .search-fab-icon {
    font-size: 17px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .search-fab {
    animation: none;
    transition: border-color .18s ease, color .18s ease;
  }
  .search-fab:hover,
  .search-fab:active {
    transform: none;
  }
}
</style>
