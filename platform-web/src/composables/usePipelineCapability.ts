import { ref, watch, type Ref } from 'vue'
import { pipelineApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'

/**
 * 「这个集群能不能跑 CI」—— Tekton 与可动态供应的 StorageClass 缺一不可（spec §9）。
 *
 * <p>入参 clusterId ref；切换集群自动重读。**三态**：
 * <ul>
 *   <li>{@code true} / {@code false} —— 探测到了，页面据此提示并把动作按下去；</li>
 *   <li>{@code null}（{@link available} 为 null）= **不知道**：没权限读、还没拉回来、或拉失败。
 *       这一态**不做门控**（不显示横幅、不禁用）—— 门控是「别让用户点了才收 500」的体验改进，
 *       不是安全边界，真正的拦截永远在后端。用「不知道」去禁用，只会让没权限的人看到一片灰。</li>
 * </ul>
 *
 * <p>⚠️ <b>为什么不复用 /cluster/capability/get</b>：那份快照里**没有 Tekton**，
 * 而且它读的是 DB 里的 capability 列（纯 discovery）。本文要问的第二件事 ——
 * 「有没有可动态供应的 StorageClass」—— 是个**活探测**（每次真问集群），
 * 所以它有自己的端点与自己的码。
 *
 * <p><b>权限位短路</b>：`/pipeline/capability` 的码是 `tenant:pipeline:read`，
 * 没有就不发请求（省一次注定被拒的往返，也不会一进页面就弹 403）。
 *
 * <p>返回的是一组普通 ref —— 在 `<script setup>` 顶层解构后，模板里可直接写
 * `v-if="cap.available === false"`（顶层绑定会被解包）。
 */
export function usePipelineCapability(clusterId: Ref<string | null | undefined>) {
  const perm = usePermission()
  const available = ref<boolean | null>(null)
  const reason = ref<string | null>(null)
  const loading = ref(false)

  async function load(): Promise<void> {
    const id = clusterId.value
    if (!id || !perm.has(apiCodes.pipelineRead)) {
      available.value = null
      reason.value = null
      return
    }
    loading.value = true
    try {
      const c = await pipelineApi.capability(id)
      // 加载期间切了集群 → 丢弃过期结果，等下一次 watch 触发
      if (clusterId.value !== id) return
      available.value = c?.available ?? null
      reason.value = c?.reason ?? null
    } catch {
      if (clusterId.value !== id) return
      available.value = null // 拦截器已提示；按「不知道」降级，不禁用
      reason.value = null
    } finally {
      loading.value = false
    }
  }

  watch(clusterId, () => { void load() }, { immediate: true })

  return { available, reason, loading, load }
}
