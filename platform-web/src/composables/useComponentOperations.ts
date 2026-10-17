import { ref, watch, type Ref } from 'vue'
import { clusterApi } from '@/api'
import type { K8sCalicoInstall, K8sComponentOperation, K8sThanosInstall } from '@/types'

/**
 * 组件安装/升级操作（S3.6）。
 *
 * <p>「最近一次操作」按组件 code 索引；轮询用 operationId。轮询间隔 2s，
 * 上限 15 分钟（与 HelmRunner 的进程超时一致：helm 自身 10m + 余量）——
 * 到点不再轮询，页面显示"仍未结束"。
 *
 * <p>返回普通对象（非 reactive）：消费方显式写 `.value`（同其它 composable 的约定）。
 */
export function useComponentOperations(clusterId: Ref<string | null | undefined>) {
  const latest = ref<Record<string, K8sComponentOperation>>({})
  const loading = ref(false)

  async function load(): Promise<void> {
    const id = clusterId.value
    if (!id) { latest.value = {}; return }
    loading.value = true
    try {
      const rows = await clusterApi.componentOperations(id)
      if (clusterId.value !== id) return
      latest.value = Object.fromEntries((rows ?? []).map((r) => [r.componentCode, r]))
    } catch {
      if (clusterId.value === id) latest.value = {}
    } finally {
      if (clusterId.value === id) loading.value = false
    }
  }

  async function apply(
    componentCode: string,
    action: 'INSTALL' | 'UPGRADE',
    targetVersion?: string,
    editableValues?: Record<string, unknown>,
    alertWebhookUrl?: string,
    thanosReceiveUrl?: string,
    thanosInstall?: K8sThanosInstall,
    calicoInstall?: K8sCalicoInstall,
  ): Promise<number> {
    const id = clusterId.value
    if (!id) throw new Error('缺少集群上下文')
    const operationId = await clusterApi.componentApply({
      clusterId: id, componentCode, action, targetVersion, editableValues,
      alertWebhookUrl, thanosReceiveUrl, thanosInstall, calicoInstall,
    })
    await load()
    return operationId
  }

  /** 轮询到终态；超时抛错由调用方展示 */
  async function wait(operationId: number, timeoutMs = 15 * 60 * 1000): Promise<K8sComponentOperation> {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      const op = await clusterApi.componentOperation({ operationId })
      if (op && op.status !== 'PENDING' && op.status !== 'RUNNING') {
        await load()
        return op
      }
      if (Date.now() > deadline) {
        throw new Error('操作仍未结束（超过 15 分钟），请稍后刷新查看')
      }
      await new Promise((r) => setTimeout(r, 2000))
    }
  }

  watch(clusterId, () => void load(), { immediate: true })
  return { latest, loading, load, apply, wait }
}
