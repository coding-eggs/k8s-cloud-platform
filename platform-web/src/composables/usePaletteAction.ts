/**
 * 接住命令面板的页内功能（路由 meta.actions → `?do=<key>`）。
 *
 * 用法（页面 setup 里）：
 *   usePaletteAction({
 *     create: { run: openCreate, ready: () => canCreate.value && ready.value },
 *   })
 *
 * - 进入页面或停在本页时 `do` 变化都会触发；先把 `do` 从地址栏摘掉（刷新/后退不会重放），再执行；
 * - `ready` 未就绪（集群/命名空间还没选好、能力探测/列表还在加载）就等它变真，最多等 WAIT_MS；
 *   到点仍不就绪 = 当前确实做不了（缺权限 / 没选上下文 / 集群不支持），给一句提示后放弃，
 *   与页面上那个按钮此刻是禁用态的结论一致。
 */
import { onBeforeUnmount, watch, type WatchStopHandle } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

export const PALETTE_ACTION_QUERY = 'do'
const WAIT_MS = 6000

export interface PaletteActionHandler {
  run: () => void
  /** 缺省视为随时可执行 */
  ready?: () => boolean
}

export function usePaletteAction(handlers: Record<string, PaletteActionHandler>): void {
  const route = useRoute()
  const router = useRouter()
  let stopWait: WatchStopHandle | null = null
  let timer: ReturnType<typeof setTimeout> | null = null

  function cancelPending(): void {
    stopWait?.()
    stopWait = null
    if (timer) clearTimeout(timer)
    timer = null
  }

  function execute(handler: PaletteActionHandler): void {
    cancelPending()
    const ready = handler.ready ?? (() => true)
    if (ready()) {
      handler.run()
      return
    }
    stopWait = watch(ready, (ok) => {
      if (!ok) return
      cancelPending()
      handler.run()
    })
    timer = setTimeout(() => {
      cancelPending()
      ElMessage.info('当前无法执行该操作：可能缺少权限，或还没选好集群 / 命名空间')
    }, WAIT_MS)
  }

  watch(
    () => route.query[PALETTE_ACTION_QUERY],
    (raw) => {
      const key = typeof raw === 'string' ? raw : null
      if (!key) return
      const { [PALETTE_ACTION_QUERY]: _drop, ...rest } = route.query
      void router.replace({ path: route.path, query: rest, hash: route.hash })
      const handler = handlers[key]
      if (handler) execute(handler)
    },
    { immediate: true },
  )

  onBeforeUnmount(cancelPending)
}
