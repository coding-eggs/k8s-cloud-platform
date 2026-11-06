import { ref, watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 列表页的一个筛选值与 URL 查询参数双向同步（总览卡片点「异常 4」带 `?status=abnormal` 跳过来）。
 *
 * - 进页面时从 `route.query[key]` 取初值，取不到 = ''（全部）；
 * - 用户改筛选 → `router.replace` 改 URL（不新增历史记录），清空即删掉该参数，刷新/分享链接仍是同一个视图；
 * - 同一页内再被带不同参数跳一次（组件复用）→ 跟着 URL 变。
 */
export function useQueryFilter(key: string): Ref<string> {
  const route = useRoute()
  const router = useRouter()
  const read = (): string => {
    const v = route.query[key]
    return typeof v === 'string' ? v : ''
  }
  const value = ref(read())

  watch(value, (v) => {
    if (v === read()) return
    const query = { ...route.query }
    if (v) query[key] = v
    else delete query[key]
    void router.replace({ query })
  })
  watch(
    () => route.query[key],
    () => {
      const v = read()
      if (v !== value.value) value.value = v
    },
  )
  return value
}
