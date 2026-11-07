<script setup lang="ts">
/**
 * 应用设置里的「服务网格」：settings.mesh，发布勾选「应用设置」后作用于全部服务。
 *
 * <ul>
 *   <li>跟随命名空间：什么标签都不写；</li>
 *   <li>不加入：Pod 标 istio.io/dataplane-mode=none；</li>
 *   <li>L4：Pod 标 ambient（ztunnel 提供 mTLS、L4 授权、TCP 指标）；有端口的 Service 标 istio.io/use-waypoint=none，
 *       即使命名空间配了 waypoint 也不走；</li>
 *   <li>L7：在 L4 基础上，有端口的 Service 标 istio.io/use-waypoint=&lt;所选 waypoint&gt;（必选），
 *       获得 HTTP 路由、重试超时、L7 授权、请求级指标；后台进程没有 Service，仍只有 L4。</li>
 * </ul>
 *
 * <p>门禁：L4 / L7 需要 ambient-mesh 旗标可用；跟随、不加入任何时候都可设；已保存的取值始终保留（不追溯）。
 * 候选与 Service 编辑页同口径：只列本命名空间 waypoint-for = service / all 的 waypoint。
 */
import { computed, ref, watch } from 'vue'
import { gatewayApi } from '@/api'
import type { AppServiceMesh, K8sGateway, WaypointRef } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import { canHandleService, isWaypointGateway, toWaypointRef, waypointOptionLabel } from '@/utils/waypoint'

const props = defineProps<{
  modelValue?: AppServiceMesh
  original?: AppServiceMesh
  tenantId?: string
  clusterId?: string
  namespace?: string
  readonly: boolean
  meshAvailable: boolean
  meshReason?: string
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: AppServiceMesh | undefined): void }>()

const perm = usePermission()

const mode = computed<string>({
  get: () => props.modelValue?.mode ?? '',
  set: (v) => {
    if (v !== 'none' && v !== 'l4' && v !== 'l7') { emit('update:modelValue', undefined); return }
    const next: AppServiceMesh = { mode: v }
    if (v === 'l7') next.waypoint = props.modelValue?.waypoint ?? props.original?.waypoint
    if (!next.waypoint) delete next.waypoint
    emit('update:modelValue', next)
  },
})
const waypoint = computed<string>({
  get: () => props.modelValue?.waypoint ?? '',
  set: (v) => emit('update:modelValue', { mode: 'l7', ...(v ? { waypoint: v } : {}) }),
})

/** ambient 不可用时，只有原本就是该模式的才保留可选 */
const l4Locked = computed(() => !props.meshAvailable && props.original?.mode !== 'l4')
const l7Locked = computed(() => !props.meshAvailable && props.original?.mode !== 'l7')

const waypointOptions = ref<WaypointRef[]>([])
const waypointLoaded = ref(false)
async function loadWaypointOptions(): Promise<void> {
  waypointOptions.value = []
  waypointLoaded.value = false
  const { tenantId, clusterId, namespace } = props
  if (!tenantId || !clusterId || !namespace || !props.meshAvailable) return
  if (!perm.has(apiCodes.gatewayList)) { waypointLoaded.value = true; return }
  try {
    waypointOptions.value = ((await gatewayApi.list({ tenantId, clusterId, namespace })) ?? [])
      .filter((g: K8sGateway) => isWaypointGateway(g))
      .map((g) => toWaypointRef(g))
      .sort((a, b) => a.name.localeCompare(b.name))
  } catch {
    waypointOptions.value = []
  } finally {
    waypointLoaded.value = true
  }
}
watch([() => props.tenantId, () => props.clusterId, () => props.namespace, () => props.meshAvailable],
  () => { void loadWaypointOptions() }, { immediate: true })

const waypointUsable = computed(() => waypointOptions.value.filter((r) => canHandleService(r.waypointFor)))

/** 当前值不在可用候选里（类型不匹配 / 已删 / 组件不可用没加载）时的回显项 */
const currentUnusableWaypoint = computed<WaypointRef | null>(() => {
  const cur = waypoint.value
  if (!cur || waypointUsable.value.some((r) => r.name === cur)) return null
  const known = waypointOptions.value.find((r) => r.name === cur)
  return { name: cur, waypointFor: known?.waypointFor ?? (waypointLoaded.value ? '已不存在' : '未校验') }
})

const MODE_TIP: Record<string, string> = {
  '': '不写标签，由命名空间决定是否加入网格、走不走 waypoint。',
  none: '全部服务的 Pod 标 istio.io/dataplane-mode=none：显式不加入网格（即使命名空间开了 ambient）。',
  l4: '全部服务的 Pod 标 istio.io/dataplane-mode=ambient：流量经节点上的 ztunnel，自动 mTLS、按身份 / 端口的 L4 授权、TCP 指标。有端口的服务另标 istio.io/use-waypoint=none，不走 waypoint。',
  l7: '在 L4 基础上，有端口服务的 Service 标 istio.io/use-waypoint=<waypoint>：发往这些服务的流量再经 waypoint 代理，支持 HTTP 路由、重试超时、L7 授权、请求级指标与拓扑。后台进程没有 Service，只有 L4。',
}
</script>

<template>
  <el-form label-width="140px" :disabled="readonly">
    <el-form-item label="服务网格">
      <el-radio-group v-model="mode">
        <el-radio value="">跟随命名空间</el-radio>
        <el-radio value="none">不加入</el-radio>
        <el-radio value="l4" :disabled="l4Locked">L4</el-radio>
        <el-radio value="l7" :disabled="l7Locked">L7（需 waypoint）</el-radio>
      </el-radio-group>
      <div class="form-tip">{{ MODE_TIP[mode] ?? '' }}</div>
      <div v-if="l4Locked || l7Locked" class="form-tip warn">ambient 网格不可用（{{ meshReason || '依赖的集群组件未安装或未就绪' }}）：只能选跟随或不加入。</div>
    </el-form-item>
    <el-form-item v-if="mode === 'l7'" label="Waypoint" required>
      <el-select v-model="waypoint" style="width: 320px" placeholder="选择 waypoint">
        <el-option v-for="r in waypointUsable" :key="r.name" :label="waypointOptionLabel(r)" :value="r.name" />
        <el-option v-if="currentUnusableWaypoint" :key="`current-${currentUnusableWaypoint.name}`"
                   :label="`${waypointOptionLabel(currentUnusableWaypoint)}｜不会生效`"
                   :value="currentUnusableWaypoint.name" disabled />
      </el-select>
      <div v-if="!waypoint" class="form-tip warn">L7 需要选择 waypoint。</div>
      <div v-if="!perm.has(apiCodes.gatewayList)" class="form-tip">无 Gateway 列表读权限，候选取不到（已有值仍保留）。</div>
      <div v-else-if="meshAvailable && !waypointLoaded" class="form-tip">正在加载本命名空间的 waypoint 候选…</div>
      <div v-else-if="meshAvailable && !waypointUsable.length" class="form-tip">本命名空间没有处理服务流量（waypoint-for 为 service / all）的 waypoint——可在「Gateway」页创建。</div>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.form-tip { color: var(--text-3); font-size: 12px; line-height: 1.6; }
div.form-tip { width: 100%; }
.form-tip.warn { color: var(--el-color-warning); }
</style>
