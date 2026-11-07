<script setup lang="ts">
/**
 * 应用设置里的「服务网格」：settings.mesh，发布勾选「应用设置」后作用于全部服务。
 * - dataplaneMode → 每个服务 Pod 模板标签 istio.io/dataplane-mode（跟随 / ambient / none）；
 * - useWaypoint → 每个有端口服务的 Service 标签 istio.io/use-waypoint（跟随 / none / waypoint 名）。
 *
 * <p>门禁：ambient 或具体 waypoint 需要 ambient-mesh 旗标可用；「跟随」和 none 任何时候都可设。
 * 已保存的取值始终保留可选（不追溯）。候选与 Service 编辑页同口径：只列 waypoint-for = service / all。
 */
import { computed, ref, watch } from 'vue'
import { gatewayApi } from '@/api'
import type { AppServiceMesh, K8sGateway, WaypointRef } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import {
  canHandleService, isWaypointGateway, toWaypointRef, waypointOptionLabel, USE_WAYPOINT_NONE,
} from '@/utils/waypoint'

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

function patch(p: Partial<AppServiceMesh>): void {
  const next: AppServiceMesh = { ...(props.modelValue ?? {}), ...p }
  if (!next.dataplaneMode) delete next.dataplaneMode
  if (!next.useWaypoint) delete next.useWaypoint
  emit('update:modelValue', next.dataplaneMode || next.useWaypoint ? next : undefined)
}

const dataplaneMode = computed<string>({
  get: () => props.modelValue?.dataplaneMode ?? '',
  set: (v) => patch({ dataplaneMode: v === 'ambient' || v === 'none' ? v : undefined }),
})
const useWaypoint = computed<string>({
  get: () => props.modelValue?.useWaypoint ?? '',
  set: (v) => patch({ useWaypoint: v || undefined }),
})

/** ambient 不可用且未保存为 ambient → 不能选 ambient */
const ambientLocked = computed(() => !props.meshAvailable && props.original?.dataplaneMode !== 'ambient')

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
const originalWaypoint = computed(() => props.original?.useWaypoint ?? '')

/** 当前值不在可用候选里（类型不匹配 / 已删 / 组件不可用没加载）时的回显项 */
const currentUnusableWaypoint = computed<WaypointRef | null>(() => {
  const cur = useWaypoint.value
  if (!cur || cur === USE_WAYPOINT_NONE) return null
  if (waypointUsable.value.some((r) => r.name === cur)) return null
  const known = waypointOptions.value.find((r) => r.name === cur)
  return { name: cur, waypointFor: known?.waypointFor ?? (waypointLoaded.value ? '已不存在' : '未校验') }
})
</script>

<template>
  <el-form label-width="140px" :disabled="readonly">
    <el-form-item label="数据面模式">
      <el-select v-model="dataplaneMode" style="width: 320px" placeholder="跟随命名空间">
        <el-option label="跟随命名空间" value="" />
        <el-option label="ambient（加入网格）" value="ambient" :disabled="ambientLocked" />
        <el-option label="none（显式不加入）" value="none" />
      </el-select>
      <div class="form-tip">写到全部服务的 Pod 模板标签 istio.io/dataplane-mode；跟随 = 不写标签，由命名空间决定。</div>
      <div v-if="ambientLocked" class="form-tip warn">ambient 网格不可用（{{ meshReason || '依赖的集群组件未安装或未就绪' }}）：只能选跟随或 none。</div>
    </el-form-item>
    <el-form-item label="Waypoint">
      <el-select v-model="useWaypoint" style="width: 320px" placeholder="跟随命名空间">
        <el-option label="跟随命名空间" value="" />
        <el-option label="显式不使用（none）" :value="USE_WAYPOINT_NONE" />
        <el-option v-for="r in waypointUsable" :key="r.name" :label="waypointOptionLabel(r)" :value="r.name"
                   :disabled="!meshAvailable && r.name !== originalWaypoint" />
        <el-option v-if="currentUnusableWaypoint" :key="`current-${currentUnusableWaypoint.name}`"
                   :label="`${waypointOptionLabel(currentUnusableWaypoint)}｜不会生效`"
                   :value="currentUnusableWaypoint.name" disabled />
      </el-select>
      <div class="form-tip">写到每个有端口服务的 Service 标签 istio.io/use-waypoint：七层流量走哪个 waypoint（后台进程没有 Service，不受影响）。只列 waypoint-for 为 service / all 的候选。</div>
      <div v-if="!meshAvailable" class="form-tip warn">ambient 网格不可用：只能选跟随或 none。</div>
      <div v-else-if="!perm.has(apiCodes.gatewayList)" class="form-tip">无 Gateway 列表读权限，候选取不到（已有值仍保留）。</div>
      <div v-else-if="!waypointLoaded" class="form-tip">正在加载本命名空间的 waypoint 候选…</div>
      <div v-else-if="!waypointUsable.length" class="form-tip">本命名空间没有处理服务流量的 waypoint——可在「Gateway」页创建。</div>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.form-tip { color: var(--text-3); font-size: 12px; line-height: 1.6; }
div.form-tip { width: 100%; }
span.form-tip { margin-left: 10px; }
.form-tip.warn { color: var(--el-color-warning); }
</style>
