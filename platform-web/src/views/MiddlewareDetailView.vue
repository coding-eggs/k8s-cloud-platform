<script setup lang="ts">
/**
 * 中间件实例详情（参考工作负载详情页：概览 / 资源对象 / YAML / 操作记录）。
 *
 * <p>数据来自 `/middleware/instance/detail`（台账 + 规格 + 连接方式 + 集群实时对象）与
 * `/middleware/instance/operations`。编辑只做「副本数 + 资源 + 显示名」——形态与存储不可改，
 * 与后端 scale 的语义一致（Nacos 走 chart，只能改显示名）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { middlewareApi } from '@/api'
import YamlBlock from '@/components/YamlBlock.vue'
import MiddlewareMetricsPanel from '@/components/middleware/MiddlewareMetricsPanel.vue'
import { useResourceContext } from '@/stores/context'
import type { MiddlewareInstanceDetail, MiddlewareOperation } from '@/types'

const route = useRoute()
const router = useRouter()
const ctx = useResourceContext()
const instanceId = computed(() => String(route.params.id))

const detail = ref<MiddlewareInstanceDetail | null>(null)
const operations = ref<MiddlewareOperation[]>([])
const loading = ref(false)
const tab = ref('overview')

const instance = computed(() => detail.value?.instance ?? null)
const live = computed(() => detail.value?.live ?? null)
const connection = computed(() => detail.value?.connection ?? null)
const yamlText = computed(() => live.value?.yaml ?? '')

const STATUS_TAG: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  CREATING: { label: '创建中', type: 'warning' },
  READY: { label: '运行中', type: 'success' },
  DEGRADED: { label: '降级', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' },
  DELETING: { label: '删除中', type: 'info' },
}

const OP_STATUS: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  PENDING: { label: '排队中', type: 'info' },
  RUNNING: { label: '执行中', type: 'warning' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  UNKNOWN: { label: '未知', type: 'info' },
}

const OP_ACTION: Record<string, string> = { CREATE: '创建', SCALE: '扩容', DELETE: '删除', UPGRADE: '升级' }

/** Nacos 走 chart，没有 scale；单节点/单实例形态副本数固定 */
const fixedReplicas = computed(() => {
  const mode = instance.value?.mode ?? ''
  return mode === 'STANDALONE' || mode === 'SINGLE' ? 1 : null
})
const canScale = computed(() => instance.value?.code !== 'nacos')

/** 监控图的百分比上限：CPU 核数 / 内存字节（spec 里是 K8s quantity 字符串） */
const cpuLimitNum = computed(() => parseCpu(detail.value?.spec?.cpuLimit))
const memoryLimitBytes = computed(() => {
  const m = parseMemory(detail.value?.spec?.memoryLimit)
  return m.value == null ? null : m.value * (m.unit === 'Gi' ? 1024 ** 3 : 1024 ** 2)
})

// ---------------- 资源跳转 ----------------
/**
 * 资源页面（工作负载/Pod/PVC/Service/Secret）都按**全局上下文**里的集群+命名空间取对象，
 * 而中间件实例可能不在当前上下文里（管理员在平台视图选的是别的 ns）。
 * 跳转前先把上下文对齐到实例所在的集群/命名空间，否则目标页只会「找不到对象」。
 */
function goResource(path: string): void {
  const inst = instance.value
  if (!inst) return
  // 资源页按「租户 → 集群 → 命名空间」全局上下文查询（不认中间件台账）：
  // 先对齐三级，否则跳到别处只会看到空列表/找不到对象。setTenant 会清空后两级，故按顺序设。
  if (inst.tenantId && ctx.state.tenantId !== inst.tenantId) ctx.setTenant(inst.tenantId)
  if (ctx.state.clusterId !== inst.clusterId) ctx.setCluster(inst.clusterId)
  if (ctx.state.namespace !== inst.namespace) ctx.setNamespace(inst.namespace)
  void router.push(path)
}

const goWorkload = (name: string) => goResource(`/resources/workloads/detail?name=${encodeURIComponent(name)}`)
const goPod = (name: string) => goResource(`/resources/pods/detail?name=${encodeURIComponent(name)}`)
const goService = (name: string) => goResource(`/resources/services?open=${encodeURIComponent(name)}`)
const goPvc = (name: string) => goResource(`/resources/pvcs?open=${encodeURIComponent(name)}`)
const goSecret = (name: string) => goResource(`/resources/secrets?open=${encodeURIComponent(name)}`)

async function load(): Promise<void> {
  loading.value = true
  try {
    detail.value = await middlewareApi.instanceDetail(instanceId.value)
    operations.value = (await middlewareApi.instanceOperations(instanceId.value)) ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载实例详情失败')
  } finally {
    loading.value = false
  }
}

// ---------------- 编辑 ----------------
const editVisible = ref(false)
const saving = ref(false)
const editForm = ref<{
  displayName: string
  replicas: number | null
  cpuRequest: number | null
  cpuLimit: number | null
  memoryRequest: number | null
  memoryLimit: number | null
  memoryRequestUnit: 'Mi' | 'Gi'
  memoryLimitUnit: 'Mi' | 'Gi'
  ik: boolean
}>({
  displayName: '',
  replicas: null,
  cpuRequest: null,
  cpuLimit: null,
  memoryRequest: null,
  memoryLimit: null,
  memoryRequestUnit: 'Mi',
  memoryLimitUnit: 'Mi',
  ik: false,
})

/**
 * IK 已装：以集群里 CR 的现读结果为准（live.ikInstalled），读不到时退回台账 spec_json。
 * 只看台账会把「创建时就勾了 IK」的实例显示成未安装 —— 那条路径没有回写 spec_json。
 */
const ikInstalled = computed(() =>
  detail.value?.live?.ikInstalled ?? (detail.value?.spec?.ik === true),
)
const supportsIk = computed(() => instance.value?.code === 'elasticsearch')

/** "500m" → 0.5 核；"1" → 1 */
function parseCpu(q?: string | null): number | null {
  if (!q) return null
  const s = String(q).trim()
  if (s.endsWith('m')) return Number(s.slice(0, -1)) / 1000
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

/** "512Mi" → { 512, 'Mi' }；"1Gi" → { 1, 'Gi' }；纯数字按 Gi */
function parseMemory(q?: string | null): { value: number | null; unit: 'Mi' | 'Gi' } {
  if (!q) return { value: null, unit: 'Mi' }
  const s = String(q).trim()
  if (s.endsWith('Gi')) return { value: Number(s.slice(0, -2)), unit: 'Gi' }
  if (s.endsWith('Mi')) return { value: Number(s.slice(0, -2)), unit: 'Mi' }
  const n = Number(s)
  return { value: Number.isFinite(n) ? n : null, unit: 'Gi' }
}

function openEdit(): void {
  const spec = detail.value?.spec ?? {}
  const memReq = parseMemory(spec.memoryRequest)
  const memLim = parseMemory(spec.memoryLimit)
  editForm.value = {
    displayName: instance.value?.displayName ?? '',
    replicas: typeof spec.replicas === 'number' ? spec.replicas : null,
    cpuRequest: parseCpu(spec.cpuRequest),
    cpuLimit: parseCpu(spec.cpuLimit),
    memoryRequest: memReq.value,
    memoryLimit: memLim.value,
    memoryRequestUnit: memReq.unit,
    memoryLimitUnit: memLim.unit,
    // 已装的开关要如实回显（置灰且处于开启位），否则「已安装」的文案配一个关着的开关
    ik: ikInstalled.value,
  }
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  if (!instance.value) return
  saving.value = true
  try {
    if (editForm.value.displayName.trim() && editForm.value.displayName !== instance.value.displayName) {
      await middlewareApi.rename({ instanceId: instanceId.value, displayName: editForm.value.displayName.trim() })
    }
    if (canScale.value) {
      const cpuReq = editForm.value.cpuRequest
      const cpuLim = editForm.value.cpuLimit
      await middlewareApi.scale({
        instanceId: instanceId.value,
        replicas: fixedReplicas.value !== null ? undefined : (editForm.value.replicas ?? undefined),
        cpuRequest: cpuReq != null ? String(cpuReq) : undefined,
        cpuLimit: cpuLim != null ? String(cpuLim) : undefined,
        memoryRequest: editForm.value.memoryRequest != null
          ? `${editForm.value.memoryRequest}${editForm.value.memoryRequestUnit}` : undefined,
        memoryLimit: editForm.value.memoryLimit != null
          ? `${editForm.value.memoryLimit}${editForm.value.memoryLimitUnit}` : undefined,
        // IK 是单向的：只有「从没装过」才发；已装的不再发（开关也置灰）
        ik: editForm.value.ik && !ikInstalled.value ? true : undefined,
      })
    }
    ElMessage.success('已提交')
    editVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------------- 删除 ----------------
async function remove(): Promise<void> {
  if (!instance.value) return
  try {
    await ElMessageBox.confirm(
      `删除实例「${instance.value.displayName}」（${instance.value.namespace}/${instance.value.name}）？\n数据卷默认保留。`,
      '删除实例',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await middlewareApi.remove({ instanceId: instanceId.value, keepPvc: true })
    ElMessage.success('已提交删除')
    router.push('/apps')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(async () => {
  await load()
  // 列表行的「编辑」→ /apps/:id?edit=1：进来直接打开编辑弹窗，然后把 query 去掉（刷新不再弹）
  if (route.query.edit === '1' && instance.value) {
    openEdit()
    void router.replace({ path: route.path })
  }
})
</script>

<template>
  <div v-loading="loading" class="mw-detail">
    <div class="page-header">
      <div>
        <el-button link @click="router.push('/apps')">← 返回中间件</el-button>
        <h2 class="mw-title">
          {{ instance?.displayName || instance?.name || '实例详情' }}
          <el-tag v-if="instance" size="small" :type="STATUS_TAG[instance.status]?.type ?? 'info'">
            {{ STATUS_TAG[instance.status]?.label ?? instance.status }}
          </el-tag>
        </h2>
        <p class="mw-sub">
          {{ instance?.code }} · {{ instance?.mode }} · {{ instance?.namespace }}/{{ instance?.name }}
          <span v-if="instance?.message" class="mw-msg">｜{{ instance.message }}</span>
        </p>
      </div>
      <div class="ph-actions">
        <el-button @click="openEdit">编辑</el-button>
        <el-button type="danger" plain @click="remove">删除</el-button>
      </div>
    </div>

    <el-alert
      v-if="detail?.liveError"
      type="warning"
      :closable="false"
      show-icon
      :title="`集群实时对象读取失败：${detail.liveError}`"
      class="mw-alert"
    />

    <el-tabs v-model="tab" class="mw-tabs">
      <el-tab-pane label="概览" name="overview">
        <div class="mw-grid2">
          <el-card shadow="never" class="panel">
            <template #header><span class="sec-title">基本信息</span></template>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="类型">{{ instance?.code }}</el-descriptions-item>
              <el-descriptions-item label="形态">{{ instance?.mode }}</el-descriptions-item>
              <el-descriptions-item label="版本">{{ instance?.version || '—' }}</el-descriptions-item>
              <el-descriptions-item label="命名空间">{{ instance?.namespace }}</el-descriptions-item>
              <el-descriptions-item label="对象名">{{ instance?.name }}</el-descriptions-item>
              <el-descriptions-item label="创建人">{{ instance?.createdBy || '—' }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ instance?.createdAt || '—' }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag size="small" :type="STATUS_TAG[instance?.status ?? '']?.type ?? 'info'">
                  {{ STATUS_TAG[instance?.status ?? '']?.label ?? instance?.status }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <el-card shadow="never" class="panel">
            <template #header><span class="sec-title">规格</span></template>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="节点数">{{ detail?.spec?.replicas ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="CPU 请求/上限">
                {{ detail?.spec?.cpuRequest || '—' }} / {{ detail?.spec?.cpuLimit || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="内存请求/上限">
                {{ detail?.spec?.memoryRequest || '—' }} / {{ detail?.spec?.memoryLimit || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="存储">{{ detail?.spec?.storageSize || '—' }}</el-descriptions-item>
              <el-descriptions-item label="StorageClass">{{ detail?.spec?.storageClass || '集群默认' }}</el-descriptions-item>
            </el-descriptions>
          </el-card>
        </div>

        <MiddlewareMetricsPanel
          v-if="instance"
          class="panel"
          :instance-id="instance.id"
          :tenant-id="instance.tenantId ?? null"
          :cpu-limit="cpuLimitNum"
          :memory-limit="memoryLimitBytes"
          :metrics-enabled="detail?.spec?.metricsEnabled === true"
        />

        <el-card v-if="connection" shadow="never" class="panel mw-conn">
          <template #header><span class="sec-title">连接方式</span></template>
          <div class="mw-conn-block">
            <div class="mw-conn-label">访问地址（集群内）</div>
            <div v-for="l in connection.endpoints" :key="l.label" class="mw-conn-line">
              <span class="mw-conn-key">{{ l.label }}</span>
              <code class="mw-conn-value">{{ l.value }}</code>
            </div>
          </div>
          <div v-if="connection.credentials.length" class="mw-conn-block">
            <div class="mw-conn-label">凭据</div>
            <div v-for="l in connection.credentials" :key="l.label" class="mw-conn-line">
              <span class="mw-conn-key">{{ l.label }}</span>
              <span class="mw-conn-value">{{ l.value }}</span>
            </div>
          </div>
          <p class="mw-form-help">{{ connection.note }}</p>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="资源对象" name="resources">
        <el-alert
          v-if="live?.warnings?.length"
          type="warning"
          :closable="false"
          show-icon
          class="mw-alert"
          title="部分资源读不到（其余照常展示）"
        >
          <div v-for="w in live.warnings" :key="w" class="mw-warn-line">{{ w }}</div>
        </el-alert>

        <el-card shadow="never" class="panel">
          <template #header><span class="sec-title">自定义资源（CR）</span></template>
          <el-descriptions v-if="live?.kind" :column="4" border size="small">
            <el-descriptions-item label="Kind">{{ live.kind }}</el-descriptions-item>
            <el-descriptions-item label="名称">{{ live.name }}</el-descriptions-item>
            <el-descriptions-item label="apiVersion">{{ live.apiVersion }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ live.createdAt }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="未找到 CR" :image-size="60" />
        </el-card>

        <el-card v-if="live?.relatedCrs?.length" shadow="never" class="panel">
          <template #header><span class="sec-title">附属 CR（{{ live.relatedCrs.length }}）</span></template>
          <el-table :data="live.relatedCrs" size="small">
            <el-table-column prop="kind" label="Kind" width="170" />
            <el-table-column prop="name" label="名称" min-width="220" />
            <el-table-column prop="apiVersion" label="apiVersion" width="240" />
            <el-table-column prop="createdAt" label="创建时间" width="170" />
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header><span class="sec-title">工作负载（{{ live?.workloads?.length ?? 0 }}）</span></template>
          <el-table :data="live?.workloads ?? []" size="small" empty-text="没有由该实例创建的工作负载">
            <el-table-column prop="kind" label="类型" width="110" />
            <el-table-column label="名称" min-width="220">
              <template #default="{ row }">
                <el-button link type="primary" @click="goWorkload(row.name)">{{ row.name }}</el-button>
              </template>
            </el-table-column>
            <el-table-column label="副本" width="90">
              <template #default="{ row }">
                {{ row.readyReplicas ?? 0 }} / {{ row.desiredReplicas ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column prop="images" label="镜像" min-width="240" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="创建时间" width="170" />
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header><span class="sec-title">Pod（{{ live?.pods?.length ?? 0 }}）</span></template>
          <el-table :data="live?.pods ?? []" size="small" empty-text="没有 Pod">
            <el-table-column label="名称" min-width="240">
              <template #default="{ row }">
                <el-button link type="primary" @click="goPod(row.name)">{{ row.name }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="phase" label="状态" width="110" />
            <el-table-column prop="readyText" label="就绪" width="90" />
            <el-table-column prop="restarts" label="重启" width="80" />
            <el-table-column prop="node" label="节点" width="120" />
            <el-table-column prop="createdAt" label="创建时间" width="170" />
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header><span class="sec-title">PVC（{{ live?.pvcs?.length ?? 0 }}）</span></template>
          <el-table :data="live?.pvcs ?? []" size="small" empty-text="没有 PVC">
            <el-table-column label="名称" min-width="240">
              <template #default="{ row }">
                <el-button link type="primary" @click="goPvc(row.name)">{{ row.name }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="phase" label="状态" width="110" />
            <el-table-column prop="capacity" label="容量" width="100" />
            <el-table-column prop="storageClass" label="StorageClass" width="140" />
            <el-table-column prop="createdAt" label="创建时间" width="170" />
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header><span class="sec-title">Service（{{ live?.services?.length ?? 0 }}）</span></template>
          <el-table :data="live?.services ?? []" size="small" empty-text="没有 Service">
            <el-table-column label="名称" min-width="220">
              <template #default="{ row }">
                <el-button link type="primary" @click="goService(row.name)">{{ row.name }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column prop="clusterIp" label="ClusterIP" width="140" />
            <el-table-column prop="ports" label="端口" min-width="160" />
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header><span class="sec-title">凭据 Secret（{{ live?.secrets?.length ?? 0 }}）</span></template>
          <el-table :data="live?.secrets ?? []" size="small" empty-text="没有与该实例关联的 Secret">
            <el-table-column label="名称" min-width="240">
              <template #default="{ row }">
                <el-button link type="primary" @click="goSecret(row.name)">{{ row.name }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="200" />
            <el-table-column prop="keys" label="键（不含值）" min-width="180" />
            <el-table-column prop="createdAt" label="创建时间" width="170" />
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header><span class="sec-title">状态条件（CR status.conditions）</span></template>
          <el-table :data="live?.conditions ?? []" size="small" empty-text="CR 没有 status.conditions">
            <el-table-column prop="type" label="类型" width="170" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <!-- False 不一定是故障（如 ECK 的 ResourcesAwareManagement=False 只是提示），
                     真正的严重性由页面顶部的实例状态表达 -->
                <el-tag size="small" :type="row.status === 'True' ? 'success' : row.status === 'False' ? 'warning' : 'info'">
                  {{ row.status ?? '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" width="180" />
            <el-table-column prop="message" label="说明" min-width="260" show-overflow-tooltip />
            <el-table-column prop="lastTransitionTime" label="变更时间" width="180" />
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header><span class="sec-title">事件（最近 {{ live?.events?.length ?? 0 }} 条）</span></template>
          <el-table :data="live?.events ?? []" size="small" empty-text="没有相关事件">
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type === 'Warning' ? 'danger' : 'info'">
                  {{ row.type ?? '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="object" label="对象" min-width="220" />
            <el-table-column prop="reason" label="原因" width="150" />
            <el-table-column prop="message" label="说明" min-width="300" show-overflow-tooltip />
            <el-table-column prop="count" label="次数" width="70" />
            <el-table-column prop="lastTimestamp" label="时间" width="170" />
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="YAML" name="yaml">
        <div class="panel">
          <YamlBlock v-if="yamlText" :yaml="yamlText" />
          <el-empty v-else description="读不到 CR 的 YAML" :image-size="60" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="操作记录" name="operations">
        <el-table :data="operations" class="panel" size="small" empty-text="还没有操作记录">
          <el-table-column label="操作" width="90">
            <template #default="{ row }">{{ OP_ACTION[row.action] ?? row.action }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="OP_STATUS[row.status]?.type ?? 'info'">
                {{ OP_STATUS[row.status]?.label ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operator" label="操作人" width="120" />
          <el-table-column prop="startedAt" label="开始" width="170" />
          <el-table-column prop="finishedAt" label="结束" width="170" />
          <el-table-column prop="message" label="说明" min-width="220" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 编辑 ============ -->
    <el-dialog v-model="editVisible" title="编辑实例" width="560px" :close-on-click-modal="false">
      <el-form class="mw-edit-form" label-width="120px" label-position="left">
        <el-form-item label="显示名">
          <el-input v-model="editForm.displayName" placeholder="显示名（只改台账）" />
        </el-form-item>
        <template v-if="canScale">
          <el-form-item label="节点数">
            <el-input-number
              v-model="editForm.replicas"
              :disabled="fixedReplicas !== null"
              :min="fixedReplicas ?? 1"
              :max="9"
            />
            <div v-if="fixedReplicas !== null" class="mw-form-help">该形态节点数固定为 {{ fixedReplicas }}</div>
          </el-form-item>
          <el-form-item label="CPU 请求">
            <el-input-number v-model="editForm.cpuRequest" :min="0.1" :step="0.1" :precision="2" />
            <span class="mw-unit">核</span>
          </el-form-item>
          <el-form-item label="CPU 上限">
            <el-input-number v-model="editForm.cpuLimit" :min="0.1" :step="0.1" :precision="2" />
            <span class="mw-unit">核</span>
          </el-form-item>
          <el-form-item label="内存请求">
            <el-input-number v-model="editForm.memoryRequest" :min="1" :precision="0" />
            <el-select v-model="editForm.memoryRequestUnit" class="mw-unit-select">
              <el-option label="Mi" value="Mi" />
              <el-option label="Gi" value="Gi" />
            </el-select>
          </el-form-item>
          <el-form-item label="内存上限">
            <el-input-number v-model="editForm.memoryLimit" :min="1" :precision="0" />
            <el-select v-model="editForm.memoryLimitUnit" class="mw-unit-select">
              <el-option label="Mi" value="Mi" />
              <el-option label="Gi" value="Gi" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="supportsIk" label="IK 分词器">
            <el-switch v-model="editForm.ik" :disabled="ikInstalled" active-text="安装 IK 中文分词器" />
            <div class="mw-form-help">
              {{ ikInstalled
                ? '已安装（不支持在线卸载）'
                : '保存后会滚动重启 ES，并由 initContainer 下载 analysis-ik（Pod 需能访问 get.infini.cloud）' }}
            </div>
          </el-form-item>
          <p class="mw-form-help">存储与形态不可在线修改；要改请删除后重建。</p>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mw-detail { padding-bottom: 24px; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 12px; }
.mw-title { display: flex; align-items: center; gap: 10px; margin: 6px 0 4px; font-size: 18px; }
.mw-sub { margin: 0; color: var(--text-3); font-size: 12.5px; }
.mw-msg { color: var(--warning, #e6a23c); }
.ph-actions { display: flex; gap: 10px; }
.mw-alert { margin-bottom: 12px; }
.mw-warn-line { font-size: 12.5px; line-height: 1.7; }
.mw-tabs { margin-top: 4px; }
.mw-grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.panel { margin-bottom: 12px; }
.sec-title { font-weight: 600; }
.mw-conn-block { margin-bottom: 12px; }
.mw-conn-label { font-size: 12.5px; color: var(--text-3); margin-bottom: 6px; }
.mw-conn-line { display: flex; gap: 10px; padding: 3px 0; font-size: 13px; }
.mw-conn-key { color: var(--text-3); min-width: 120px; }
.mw-conn-value { color: var(--text-1); word-break: break-all; }
.mw-form-help { flex-basis: 100%; font-size: 12px; color: var(--text-3); }
.mw-unit { color: var(--text-3); font-size: 12.5px; }
.mw-unit-select { width: 80px; margin-left: 8px; }
.mw-edit-form :deep(.el-form-item__label) { justify-content: flex-start; text-align: left; }
.mw-edit-form :deep(.el-form-item__content) { flex-wrap: wrap; justify-content: flex-start; }
</style>
