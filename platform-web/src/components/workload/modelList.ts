import type { Ref } from 'vue'

/**
 * 取 defineModel 绑定的列表（空则初始化为 []），返回可直接 push/splice 的本地引用。
 *
 * 为什么不能写 `model.value ??= []; model.value.push(...)`：Vue 3.5 的 useModel 在父组件绑定
 * v-model 时，set() 只 emit、不同步更新本地缓存，get() 读到的仍是旧值（要等父组件重渲染、
 * prop 回流后才同步）。同 tick 内 set 后再读回是 undefined → push 抛 TypeError，表现为
 * 「空列表首次点击无效果、要点两次」。这里用本地引用绕开读回。
 */
export function ensureModelList<T>(model: Ref<T[] | null | undefined>): T[] {
  let arr = model.value
  if (!arr) {
    arr = []
    model.value = arr
  }
  return arr
}
