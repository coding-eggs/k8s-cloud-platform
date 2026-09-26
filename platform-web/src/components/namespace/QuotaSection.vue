<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { namespaceApi } from '@/api'
import type { K8sResourceQuota } from '@/types'
import { bytesToMi, coresToMilli, intOrNull, milliToCores, miToBytes } from '@/utils/quantityUnits'
import FieldHelp from '@/components/workload/FieldHelp.vue'

/**
 * 资源配额编辑区块（core/v1 ResourceQuota，平台单份：对象名固定 default）。
 *
 * 模型值 = 基础单位（cpu=核 / memory=字节 / 计数=个），显示经 utils/quantityUnits 换算为固定单位
 * （cpu 类→m、memory 类→Mi、计数→原值），与 components/workload/ResourcesEditor.vue 同一范式；
 * 单位一律用 `el-input` + `<template #append>`（el-input-number 无 #append 插槽，用了会静默丢单位）。
 * 入口换算走「剥非数字字符」的仓库惯例（milliToCores/miToBytes/intOrNull），垃圾输入 → null 而非 0。
 *
 * 线路语义（overlay）：toPayload 只带非 null 项 —— 用户清空的字段必须省略，后端据此删除该 hard key；
 * 绝不可发 0（会造出「上限 0」配额）。enabled=false → 父级提交时调 quotaDelete（D6：区分未配置与主动清空）。
 */
const props = defineProps<{ clusterId: string; namespace: string }>()

/** 平台单份约定的对象名（后端 forceSingleName 亦会覆写） */
const SINGLE_NAME = 'default'

/** 9 个建模 hard 键（顺序 = 后端 CoreV1ResourceQuotaConverter.MODELED_HARD_KEYS） */
type QuotaKey =
  | 'cpu' | 'memory' | 'pods' | 'services'
  | 'limitsCpu' | 'limitsMemory' | 'requestsCpu' | 'requestsMemory'
  | 'persistentVolumeClaims'

/** unit：'m'/'Mi' = 固定单位输入；null = 整数计数（无后缀） */
interface QuotaField {
  key: QuotaKey
  /** K8s hard 键名（与概览页一致，便于对照） */
  hard: string
  unit: 'm' | 'Mi' | null
  ph: string
  tip: string
}

const QUOTA_FIELDS: QuotaField[] = [
  {
    key: 'cpu', hard: 'cpu', unit: 'm', ph: '2000',
    tip: 'CPU 上限（核）。单位 m = 毫核，1 核 = 1000m，填 2000 = 2 核。K8s 按命名空间内各容器 CPU request 之和计量，语义同 requests.cpu（二者任选其一，同时设且不等会互相冲突）。留空 = 不设该项。',
  },
  {
    key: 'memory', hard: 'memory', unit: 'Mi', ph: '4096',
    tip: '内存上限（字节）。单位 Mi = Mebibyte，1 Mi = 1048576 字节，填 4096 = 4 GiB。K8s 按各容器 memory request 之和计量，语义同 requests.memory。留空 = 不设该项。',
  },
  {
    key: 'pods', hard: 'pods', unit: null, ph: '20',
    tip: 'Pod 数量上限（个，含未终止的 Pod；已 Succeeded/Failed 的不计入）。无单位，填整数。',
  },
  {
    key: 'services', hard: 'services', unit: null, ph: '10',
    tip: 'Service 数量上限（个）。无单位，填整数。仅统计 Service 对象数；端口/IP 类子配额（如 services.loadbalancerips）平台未建模、原样保留不受本区块影响。',
  },
  {
    key: 'limitsCpu', hard: 'limits.cpu', unit: 'm', ph: '4000',
    tip: 'CPU 上限（核）。单位 m = 毫核，1 核 = 1000m，填 4000 = 4 核。约束的是命名空间内所有 Pod 的「容器 limits.cpu 之和」，不是单容器上限（单容器上限请用限制范围 LimitRange）。',
  },
  {
    key: 'limitsMemory', hard: 'limits.memory', unit: 'Mi', ph: '8192',
    tip: '内存上限（字节）。单位 Mi = 1048576 字节，填 8192 = 8 GiB。约束所有「容器 limits.memory 之和」，不是单容器上限。',
  },
  {
    key: 'requestsCpu', hard: 'requests.cpu', unit: 'm', ph: '2000',
    tip: 'CPU 上限（核）。单位 m = 毫核，1 核 = 1000m。约束所有「容器 requests.cpu 之和」；与 cpu 同源（K8s 里 cpu 就是 requests.cpu），同时设置时以两者都满足为准。',
  },
  {
    key: 'requestsMemory', hard: 'requests.memory', unit: 'Mi', ph: '4096',
    tip: '内存上限（字节）。单位 Mi = 1048576 字节。约束所有「容器 requests.memory 之和」；与 memory 同源。',
  },
  {
    key: 'persistentVolumeClaims', hard: 'persistentvolumeclaims', unit: null, ph: '5',
    tip: 'PVC 数量上限（个）。无单位，填整数。存储总量子配额（pvc.storage 等）平台未建模、原样保留。',
  },
]

const enabled = ref(false)
/**
 * load() 是否失败（状态未知）。失败后 UI 与「未配置」无法区分，父级 saveConstraints
 * 必须整段跳过本区块（既不 upsert 也不 delete）——任何写都是盲写：
 * delete 分支会误删仍存在的对象；开回开关再空保存会 wipe 全部 hard 键。
 */
const loadFailed = ref(false)

/** 基础单位模型：null = 未设置（该项不约束） */
const q = reactive<Record<QuotaKey, number | null>>({
  cpu: null, memory: null, pods: null, services: null,
  limitsCpu: null, limitsMemory: null, requestsCpu: null, requestsMemory: null,
  persistentVolumeClaims: null,
})

function reset(): void {
  enabled.value = false
  for (const f of QUOTA_FIELDS) q[f.key] = null
}

/** 基础单位 → 输入框显示值（m / Mi / 原值） */
function displayOf(f: QuotaField): number | null {
  const v = q[f.key]
  if (v == null) return null
  if (f.unit === 'm') return coresToMilli(v)
  if (f.unit === 'Mi') return bytesToMi(v)
  return v
}

/** 输入框值（固定单位，剥非数字字符）→ 基础单位写回；空/垃圾 → null */
function onCell(f: QuotaField, raw: number | string | null | undefined): void {
  if (f.unit === 'm') q[f.key] = milliToCores(raw)
  else if (f.unit === 'Mi') q[f.key] = miToBytes(raw)
  else q[f.key] = intOrNull(raw)
}

/**
 * 回填：null（未配置）→ 关闭开关；失败（拦截器已提示）同样回落为「未配置」，不锁死编辑，
 * 但置 loadFailed=true —— 父级据此跳过本区块的保存写操作（盲写防护，见 loadFailed 注释）。
 */
async function load(): Promise<void> {
  loadFailed.value = false
  if (!props.namespace) { reset(); return }
  try {
    const cur = await namespaceApi.quotaGet(props.clusterId, props.namespace)
    reset()
    if (!cur) return
    enabled.value = true
    for (const f of QUOTA_FIELDS) q[f.key] = cur[f.key] ?? null
  } catch {
    reset()
    loadFailed.value = true
  }
}

function onToggle(val: boolean | string | number): void {
  if (val) return
  reset()
  ElMessage.warning('已关闭配额：保存将删除该命名空间的配额对象（含平台未建模的其它配额项）')
}

/** 提交体（基础单位）：只带非 null 项 → 清空的键被省略 → 后端 overlay 删除该 hard key */
function toPayload(): K8sResourceQuota {
  const payload: K8sResourceQuota = { name: SINGLE_NAME, namespace: props.namespace }
  for (const f of QUOTA_FIELDS) {
    const v = q[f.key]
    if (v != null) payload[f.key] = v
  }
  return payload
}

function hasAnyValue(): boolean {
  return QUOTA_FIELDS.some((f) => q[f.key] != null)
}

defineExpose({ load, toPayload, hasAnyValue, enabled, loadFailed })
</script>

<template>
  <el-card shadow="never" class="quota-sec">
    <template #header>
      <div class="qs-head">
        <span class="qs-title">资源配额（ResourceQuota）</span>
        <span class="qs-head-right">
          <el-switch v-model="enabled" @change="onToggle" />
          <FieldHelp tip="关闭并保存 = 删除该命名空间的配额（含平台未建模的其它配额项，如 count/deployments）。开启才会创建/更新名为 default 的配额对象。" />
        </span>
      </div>
    </template>

    <el-alert
      v-if="loadFailed"
      type="warning"
      :closable="false"
      show-icon
      class="qs-loadfail"
      title="未能读取该命名空间的现有配额——保存将跳过本区块，不会修改或删除集群中已存在的配额对象。请刷新页面（或重新选择集群）后再修改。"
    />

    <div v-if="!enabled" class="qs-off">
      未启用 —— 保存时不会创建配额；若该命名空间已有配额，将被整体删除。
    </div>

    <el-form v-else label-width="200px" label-position="left">
      <el-form-item v-for="f in QUOTA_FIELDS" :key="f.key">
        <template #label>{{ f.hard }} <FieldHelp :tip="f.tip" /></template>
        <el-input
          :model-value="displayOf(f)"
          style="width: 260px"
          :placeholder="f.ph"
          @update:model-value="(v: number | string | null | undefined) => onCell(f, v)"
        >
          <template v-if="f.unit" #append>{{ f.unit }}</template>
        </el-input>
      </el-form-item>
      <div class="qs-foot">留空 = 该项不设约束；清空的项在保存后会从配额对象中删除。</div>
    </el-form>
  </el-card>
</template>

<style scoped>
.qs-loadfail {
  margin-bottom: 10px;
}
.qs-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.qs-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.qs-head-right {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.qs-off,
.qs-foot {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}
.qs-foot {
  padding-left: 200px;
}
</style>
