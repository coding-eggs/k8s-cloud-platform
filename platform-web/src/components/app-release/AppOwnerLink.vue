<script setup lang="ts">
/**
 * 资源页的「所属应用」链接。依据是应用图表给对象打的 `platform.coding/app` 标签（值 = 应用 id，不是 slug，
 * 正好是 `/applications/:id` 的路由参数，所以不需要任何反查就能跳转）。
 *
 * 名称用整页**一次**应用列表请求解出来（模块级按 租户+集群 缓存 60 秒，列表里 N 行共享同一个 Promise，
 * 不会 N+1）；没有应用页权限、请求失败或应用已删除时退回通用文案「所属应用」，链接照样可用。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'

const props = defineProps<{ appId: string; clusterId?: string | null; tenantId?: string | null }>()

const perm = usePermission()
const name = ref('')
const allowed = computed(() => perm.has(pageCodes.application))

async function resolve(): Promise<void> {
  name.value = ''
  if (!allowed.value || !props.clusterId) return
  name.value = (await namesOf(props.clusterId, props.tenantId ?? undefined)).get(props.appId) ?? ''
}
onMounted(resolve)
watch(() => [props.appId, props.clusterId, props.tenantId], resolve)
</script>

<script lang="ts">
import { applicationApi as api } from '@/api'

const TTL_MS = 60_000
const cache = new Map<string, { at: number; p: Promise<Map<string, string>> }>()

/** 一个 租户+集群 的 应用 id → 展示名；失败得到空表（并且不缓存失败，下次再试） */
function namesOf(clusterId: string, tenantId?: string): Promise<Map<string, string>> {
  const key = `${tenantId ?? ''}|${clusterId}`
  const hit = cache.get(key)
  if (hit && Date.now() - hit.at < TTL_MS) return hit.p
  const p = api.list(clusterId, tenantId)
    .then((rows) => new Map((rows ?? []).map((a) => [a.id, a.displayName || a.name] as [string, string])))
    .catch(() => {
      cache.delete(key)
      return new Map<string, string>()
    })
  cache.set(key, { at: Date.now(), p })
  return p
}
</script>

<template>
  <router-link
    v-if="allowed"
    :to="{ name: 'application-detail', params: { id: appId } }"
    class="app-owner-link"
    :title="'所属应用' + (name ? '：' + name : '')"
    @click.stop
  >
    <el-tag size="small" type="success" effect="plain">应用 · {{ name || '所属应用' }}</el-tag>
  </router-link>
</template>

<style scoped>
.app-owner-link { text-decoration: none; margin-left: 6px; }
</style>
