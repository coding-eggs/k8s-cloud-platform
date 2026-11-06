<script setup lang="ts">
/**
 * 资源页的「受管归属」标记：中间件行显示「Elasticsearch · 实例显示名」并可跳实例详情；
 * 平台组件行显示「平台组件 · <helm release>」。非受管行不渲染。
 *
 * 实例名反查用整页**一次**实例列表请求（模块级按 租户+集群 缓存 30 秒，N 行共享同一个 Promise，
 * 不会 N+1）；只有存在中间件行时才发。没权限 / 请求失败 / 实例已删 → 回退成「中间件」，并且不放死链接。
 * 组件行不发请求：/cluster/components 要实时查集群且是管理员接口，直接用对象上的 helm release 注解。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { managedByOf, managedReason } from '@/utils/managedResource'
import { helmReleaseOf, matchInstance, middlewareTypeName } from '@/utils/managedOwner'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import type { MiddlewareInstance } from '@/types'

const props = defineProps<{ row: unknown; clusterId?: string | null; tenantId?: string | null }>()

const perm = usePermission()
const by = computed(() => managedByOf(props.row))
const instance = ref<MiddlewareInstance | null>(null)
const canOpenMiddleware = computed(() => perm.has(pageCodes.app))

async function resolve(): Promise<void> {
  instance.value = null
  if (by.value !== 'MIDDLEWARE' || !props.clusterId || !canOpenMiddleware.value) return
  const list = await instancesOf(props.clusterId, props.tenantId ?? undefined)
  instance.value = matchInstance(props.row, list)
}
onMounted(resolve)
watch(() => [props.row, props.clusterId, props.tenantId], resolve)

const label = computed(() => {
  if (by.value === 'MIDDLEWARE') {
    const i = instance.value
    return i ? `${middlewareTypeName(i.code)} · ${i.displayName || i.name}` : '中间件'
  }
  const rel = helmReleaseOf(props.row)
  return rel ? `平台组件 · ${rel}` : '平台组件'
})

const tip = computed(() => {
  const i = instance.value
  if (by.value === 'MIDDLEWARE' && i) {
    const app = i.appName ? `，来自应用「${i.appName}」` : ''
    return `由${middlewareTypeName(i.code)}实例「${i.displayName || i.name}」（${i.namespace}/${i.name}）管理${app}；点击到实例页操作`
  }
  return managedReason(props.row)
})
</script>

<script lang="ts">
import { middlewareApi } from '@/api'

const TTL_MS = 30_000
const cache = new Map<string, { at: number; p: Promise<MiddlewareInstance[]> }>()

/** 一个 租户+集群 的中间件实例台账（只查平台库）；失败得到空表，且不缓存失败 */
function instancesOf(clusterId: string, tenantId?: string): Promise<MiddlewareInstance[]> {
  const key = `${tenantId ?? ''}|${clusterId}`
  const hit = cache.get(key)
  if (hit && Date.now() - hit.at < TTL_MS) return hit.p
  const p = middlewareApi.instanceList(clusterId, undefined, tenantId, undefined, true)
    .then((rows) => rows ?? [])
    .catch(() => {
      cache.delete(key)
      return [] as MiddlewareInstance[]
    })
  cache.set(key, { at: Date.now(), p })
  return p
}
</script>

<template>
  <span v-if="by" class="managed-owner-tag">
    <el-tooltip :content="tip" placement="top">
      <router-link
        v-if="instance"
        :to="{ name: 'middleware-detail', params: { id: instance.id } }"
        class="managed-owner-link"
        @click.stop
      >
        <el-tag type="warning" size="small" effect="plain">{{ label }}</el-tag>
      </router-link>
      <el-tag v-else type="warning" size="small" effect="plain">{{ label }}</el-tag>
    </el-tooltip>
  </span>
</template>

<style scoped>
.managed-owner-tag { margin-left: 6px; vertical-align: middle; }
.managed-owner-link { text-decoration: none; }
</style>
