<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, MoreFilled } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { workloadApi, podApi } from '@/api'
import type { K8sPod, PodContainerDetail } from '@/types'
import type { WorkloadDetail, Affinity, Toleration, VolumeDef, PvcTemplate } from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import KvTags from '@/components/KvTags.vue'
import MetricsPanel from '@/components/workload/MetricsPanel.vue'
import PodTerminal from '@/components/PodTerminal.vue'
import PodLogDialog from '@/components/PodLogDialog.vue'
import { fmtAge, fmtDate, podPhaseType, workloadStatus } from '@/utils/format'
import { sumCpuCores, sumMemBytes } from '@/utils/metrics'
import FieldHelp from "@/components/workload/FieldHelp.vue";

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const name = computed(() => (route.query.name as string) || '')

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

const detail = ref<WorkloadDetail | null>(null)
const pods = ref<K8sPod[]>([])
const loading = ref(false)
const loadError = ref(false)
/** 监控面板引用：全局刷新时联动重取指标 */
const metricsRef = ref<InstanceType<typeof MetricsPanel> | null>(null)

/**把 spec.selector.matchLabels 拼成 K8s labelSelector（等值集合 → k=v,k2=v2）；缺失时退回平台约定 app=name */
function podLabelSelector(sel?: Record<string, string> | null): string {
  const entries = Object.entries(sel ?? {})
  if (entries.length) return entries.map(([k, v]) => `${k}=${v}`).join(',')
  return `app=${name.value}`
}

async function refresh(): Promise<void> {
  if (!ready.value || !name.value) return
  const target = name.value // 记录发起时的目标；期间若切到别的工作负载，丢弃过期结果（避免慢响应跨页覆盖）
  loading.value = true
  loadError.value = false
  try {
    // 先取工作负载（拿它的 selector），再用 selector 反查 Pod —— 有依赖，串行而非并行
    const d = await workloadApi.get(target, ctxParams.value)
    if (target !== name.value) return
    detail.value = d
    pods.value = (await podApi.list({ ...ctxParams.value, labelSelector: podLabelSelector(d.selector) })) ?? []
    if (target !== name.value) return
  } catch {
    if (target === name.value) loadError.value = true
  } finally {
    loading.value = false
  }
}

function goBack(): void {
  router.push('/resources/workloads')
}

/** 全局刷新：工作负载 + Pod 列表 + 监控指标一起重取（顶栏按钮 / 自动刷新用）。
 * 上下文/名称切换仍走 refresh()，指标由 MetricsPanel 自身的 prop watch 触发，避免重复请求。 */
async function refreshAll(): Promise<void> {
  await refresh()
  metricsRef.value?.refresh()
}
function goEditor(): void {
  if (isOpManaged.value) {
    ElMessage.warning('该工作负载由 Operator 管理，不可编辑')
    return
  }
  if (name.value) router.push(`/resources/workloads/editor?name=${encodeURIComponent(name.value)}`)
}
function goPod(podName: string): void {
  router.push(`/resources/pods/detail?name=${encodeURIComponent(podName)}`)
}

/** 查看绑定的 Service 详情（跳 Service 列表并自动打开对应抽屉） */
function goService(name: string): void {
  router.push(`/resources/services?open=${encodeURIComponent(name)}`)
}

// ---------- Pod 操作：日志 / Exec / Yaml / 删除（复用 PodView 的交互模式）----------
// 删除（由控制器重建）
async function onDeletePod(row: K8sPod): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 Pod「${row.name}」？若属于工作负载，控制器会自动重建新 Pod。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await podApi.delete(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

// Yaml（只读）
const yamlVisible = ref(false)
const yamlPod = ref<K8sPod | null>(null)
const yamlText = ref('')
async function openYaml(row: K8sPod): Promise<void> {
  yamlPod.value = row
  yamlText.value = ''
  yamlVisible.value = true
  try {
    yamlText.value = await podApi.getYaml(row.name, ctxParams.value)
  } catch {
    /* 拦截器已提示 */
  }
}

// 日志（PodLogDialog：恒跟随 + 底部自动滚动 + 全屏）
const logVisible = ref(false)
const logPod = ref<K8sPod | null>(null)

function openLogs(row: K8sPod): void {
  logPod.value = row
  logVisible.value = true
}

// Exec（xterm 完整终端，WS 经 platform-api 中继）
const execVisible = ref(false)
const execPod = ref<K8sPod | null>(null)
const execContainer = ref('')

function openExec(row: K8sPod): void {
  execPod.value = row
  execContainer.value = (row.containers ?? [])[0] ?? ''
  execVisible.value = true
}

// Pod 操作下拉：日志 / Exec / Yaml / 删除
function onPodCommand(cmd: string, row: K8sPod): void {
  switch (cmd) {
    case 'logs': openLogs(row); break
    case 'exec': openExec(row); break
    case 'yaml': openYaml(row); break
    case 'delete': onDeletePod(row); break
  }
}

// ---------- 自动刷新 ----------
/** 0 = 不刷新；其余为毫秒间隔 */
const REFRESH_OPTIONS: { label: string; value: number }[] = [
  { label: '不刷新', value: 0 },
  { label: '10s', value: 10_000 },
  { label: '20s', value: 20_000 },
  { label: '60s', value: 60_000 },
]
const autoRefresh = ref<number>(0)
let timer: number | null = null

function stopAutoRefresh(): void {
  if (timer != null) { window.clearInterval(timer); timer = null }
}
function scheduleAutoRefresh(): void {
  stopAutoRefresh()
  if (autoRefresh.value > 0) {
    // 上一次还没回来就跳过这一拍，避免请求堆积
    timer = window.setInterval(() => { if (!loading.value) void refreshAll() }, autoRefresh.value)
  }
}
watch(autoRefresh, scheduleAutoRefresh)
onBeforeUnmount(stopAutoRefresh)

// ---------- 左侧：基本信息派生 ----------
const kindLabel = computed(() => {
  switch (detail.value?.kind) {
    case 'deployment': return 'Deployment'
    case 'statefulset': return 'StatefulSet'
    case 'daemonset': return 'DaemonSet'
    default: return detail.value?.kind ?? '—'
  }
})
const status = computed(() => workloadStatus(detail.value?.kind, detail.value?.replicas, detail.value?.readyReplicas))
const replicasText = computed(() => {
  const d = detail.value
  if (!d) return '—'
  if (d.kind === 'daemonset') return d.readyReplicas != null ? `${d.readyReplicas} 就绪` : '—'
  return `${d.readyReplicas ?? 0}/${d.replicas ?? 0}`
})
const serviceAccount = computed(() => detail.value?.podTemplate?.spec?.serviceAccountName || 'default')

/** 是否由 Operator/控制器管理（ownerReferences 非空）→ 禁用编辑并展示管理方 */
const isOpManaged = computed(() => (detail.value?.ownerReferences?.length ?? 0) > 0)

// ---------- 监控指标配额（CPU/内存 % 切换用；指标是全体 pod 的 sum，故 limit = 单 pod × 副本数）----------
const metricContainers = computed(() => detail.value?.podTemplate?.spec?.containers ?? [])
const replicaFactor = computed(() => {
  const d = detail.value
  if (!d) return 1
  // daemonset 无 replicas 语义 → 退到 readyReplicas；其余用 replicas（缺失再退 readyReplicas）
  const r = d.kind === 'daemonset' ? (d.readyReplicas ?? 1) : (d.replicas ?? d.readyReplicas ?? 1)
  return r > 0 ? r : 1
})
const cpuLimit = computed(() => {
  const per = sumCpuCores(metricContainers.value)
  return per == null ? null : per * replicaFactor.value
})
const memoryLimit = computed(() => {
  const per = sumMemBytes(metricContainers.value)
  return per == null ? null : per * replicaFactor.value
})

// ---------- 中间：Pod 副本列表派生 ----------
function podImages(pod: K8sPod): string[] {
  return (pod.containerDetails ?? []).filter((c) => !c.init).map((c) => c.image).filter(Boolean) as string[]
}
function readyCount(pod: K8sPod): string {
  const cs = (pod.containerDetails ?? []).filter((c) => !c.init)
  return `${cs.filter((c) => c.ready).length}/${cs.length}`
}

// ---------- 右侧：更新策略 / 调度策略 / 存储 ----------
interface KV { label: string; value: string }
const strategyRows = computed<KV[]>(() => {
  const d = detail.value
  const s = d?.strategy
  if (!s || !s.type) return []
  const rows: KV[] = [{ label: '类型', value: s.type }]
  const ru = s.rollingUpdate
  if (ru) {
    // maxSurge：Deployment / DaemonSet（StatefulSet 不支持）
    if (d?.kind !== 'statefulset' && ru.maxSurge != null) rows.push({ label: 'maxSurge', value: ru.maxSurge })
    // maxUnavailable：Deployment / StatefulSet / DaemonSet
    if (ru.maxUnavailable != null) rows.push({ label: 'maxUnavailable', value: ru.maxUnavailable })
    // partition：StatefulSet（/ DaemonSet）
    if (d?.kind === 'statefulset' && ru.partition != null) rows.push({ label: 'partition', value: String(ru.partition) })
  }
  return rows
})

const nodeSelector = computed<Record<string, string>>(() => detail.value?.podTemplate?.spec?.nodeSelector ?? {})
const tolerations = computed<Toleration[]>(() => detail.value?.podTemplate?.spec?.tolerations ?? [])
function tolText(t: Toleration): string {
  const parts = [t.key ?? '*']
  if (t.operator && t.operator !== 'Exists') parts.push(t.operator, t.value ?? '')
  if (t.effect) parts.push(`(${t.effect})`)
  return parts.filter(Boolean).join(' ')
}
function describeAffinity(aff?: Affinity | null): string[] {
  if (!aff) return []
  const out: string[] = []
  const na = aff.nodeAffinity
  if (na?.required?.nodeSelectorTerms?.length) out.push(`节点亲和（必须）· ${na.required.nodeSelectorTerms.length} 组`)
  if (na?.preferred?.length) out.push(`节点亲和（优先）· ${na.preferred.length} 项`)
  const pf = aff.podAffinity
  if (pf?.required?.length) out.push(`Pod 亲和（必须）· ${pf.required.length} 组`)
  if (pf?.preferred?.length) out.push(`Pod 亲和（优先）· ${pf.preferred.length} 项`)
  const pa = aff.podAntiAffinity
  if (pa?.required?.length) out.push(`Pod 反亲和（必须）· ${pa.required.length} 组`)
  if (pa?.preferred?.length) out.push(`Pod 反亲和（优先）· ${pa.preferred.length} 项`)
  return out
}

const volumes = computed<VolumeDef[]>(() => detail.value?.podTemplate?.spec?.volumes ?? [])
function volumeRef(v: VolumeDef): string {
  switch (v.type) {
    case 'configMap': return v.configMap?.name ?? ''
    case 'secret': return v.secret?.secretName ?? ''
    case 'persistentVolumeClaim': return v.persistentVolumeClaim?.claimName ?? ''
    case 'hostPath': return v.hostPath?.path ?? ''
    case 'emptyDir': return v.emptyDir?.medium === 'Memory' ? '内存' : ''
    case 'projected': {
      const kinds = (v.projected?.sources ?? [])
        .map((s) => s.serviceAccountToken ? 'token'
            : s.configMap ? 'configMap'+ "/" + s.configMap.name
                : s.secret ? 'secret' + "/" + s.secret.name
                    : s.downwardAPI ? 'downwardAPI' : '')
        .filter(Boolean)
      return kinds.length ? `${kinds.join(' + ')}` : ''
    }
    case 'downwardAPI': {
      const n = (v.downwardAPI?.items ?? []).length
      return n ? `${n} 个文件` : ''
    }
    case 'csi': return v.csi?.driver ?? ''
    case 'nfs': return [v.nfs?.server, v.nfs?.path].filter(Boolean).join(':')
    default: return ''
  }
}
const pvcTemplates = computed<PvcTemplate[]>(() => detail.value?.volumeClaimTemplates ?? [])

// ---------- 上下文联动 ----------
onMounted(() => {
  void load()
  if (ready.value) void refresh()
})
watch(ready, (r) => { if (r && !detail.value) void refresh() })
watch(name, () => { if (ready.value) void refresh() })

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div v-loading="loading || !ready" class="wl-page">
    <PageHeader title="工作负载详情">

      <span class="refresh-bar">
        <el-tooltip content="立即刷新" placement="bottom" :show-after="100">
          <el-button :icon="Refresh" circle size="small" :disabled="!ready" @click="refreshAll" />
        </el-tooltip>
        <el-select v-model="autoRefresh" size="small" style="width: 108px">
          <el-option v-for="o in REFRESH_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </span>
      <el-button size="small" @click="goBack">返回</el-button>
      <el-tooltip :content="isOpManaged ? '由 Operator 管理，不可编辑' : ''" :disabled="!isOpManaged" placement="bottom">
        <el-button size="small" type="primary" :disabled="!detail || isOpManaged" @click="goEditor">编辑</el-button>
      </el-tooltip>

    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏依次选择租户、集群、命名空间后查看工作负载详情。" />

    <template v-else-if="loadError">
      <EmptyState title="加载失败" description="该工作负载可能已被删除，或当前上下文下不存在。">
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </EmptyState>
    </template>

    <div v-else-if="detail" class="wl-detail">
      <!-- 左：基本信息 -->
      <aside class="col col-side">
        <section class="panel info-card">
          <h3 class="info-title">基本信息</h3>
          <div class="info-name">{{ detail.name }} </div>
          <div class="info-rows">
            <div class="info-row">
              <span class="k">类型</span>
              <el-tag :effect="detail.kind === 'statefulset' ? 'success' : detail.kind === 'daemonset' ? 'warning' : 'plain'" size="small">{{ kindLabel }}</el-tag>
            </div>
            <div v-if="isOpManaged" class="info-row col-row op-row">
              <el-tag type="warning" size="small" effect="plain" class="op-badge">Operator 管理</el-tag>
              <span class="v stack">
                <div v-for="(o, i) in detail.ownerReferences ?? []" :key="i" class="stack-line mono owner-ref">{{ o.apiVersion }}.{{ o.kind }}.{{ o.name }}<FieldHelp tip="<apiVersion>.<kind>.<name>"></FieldHelp></div>
              </span>
            </div>

            <div class="info-row"><span class="k">状态</span><StatusBadgeTip :label="status.label" :type="status.type" :reason="detail.statusReason" /></div>
            <div class="info-row"><span class="k">副本数</span><span class="v">{{ replicasText }}</span></div>
            <div class="info-row col-row">
              <span class="k">镜像</span>
              <code v-for="(c, i) in detail.podTemplate?.spec.containers" :key="i" class="res-name img-cell">
                <span class="muted">{{c.name}}:</span> {{ c.image }}
              </code>
            </div>

            <div class="info-row"><span class="k">ServiceAccount</span><code class="mono v">{{ serviceAccount }}</code></div>
            <div v-if="detail.kind === 'statefulset'" class="info-row"><span class="k">Headless Service</span><code class="mono v">{{ detail.serviceName || detail.name }}</code></div>
            <div class="info-row col-row"><span class="k">描述</span><span class="v desc">{{ detail.description || '—' }}</span></div>
            <div class="info-row"><span class="k">创建时间</span><span class="v">{{ fmtDate(detail.creationTime) }}</span></div>
            <div class="info-row"><span class="k">存活时长</span><span class="v">{{ fmtAge(detail.creationTime) }}</span></div>
            <div class="info-row col-row">
              <span class="k">标签</span>
              <KvTags title="标签" :data="detail.labels ?? {}" />
            </div>
            <div class="info-row col-row">
              <span class="k">注解</span>
              <KvTags title="注解" :data="detail.annotations ?? {}" />
            </div>
          </div>
        </section>
      </aside>

      <!-- 中：Pod 副本列表（上）+ 监控指标（下） -->
      <main class="col col-main">
        <div class="main-board panel">
        <section class="list-block">
          <div class="list-head">
            <h3 class="info-title">Pod 副本</h3>
            <span class="muted count">{{ pods.length }} 个</span>
          </div>
          <EmptyState v-if="pods.length === 0 && !loading" title="暂无 Pod" description="该工作负载下当前没有运行中的 Pod。" />
          <el-table v-else :data="pods" stripe size="small">
            <el-table-column label="名称" width="350">
              <template #default="{ row }"><code class="res-name name-link" @click="goPod(row.name)">{{ row.name }}</code></template>
            </el-table-column>
<!--            <el-table-column label="镜像" min-width="180">-->
<!--              <template #default="{ row }">-->
<!--                <code v-for="(img, i) in podImages(row)" :key="i" class="res-name img-cell">{{ img }}</code>-->
<!--              </template>-->
<!--            </el-table-column>-->
            <el-table-column label="容器" width="50">
              <template #default="{ row }">{{ readyCount(row) }}</template>
            </el-table-column>
            <el-table-column label="IP" width="130">
              <template #default="{ row }"><span class="muted">{{ row.podIp ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="调度节点" min-width="100">
              <template #default="{ row }"><span class="muted">{{ row.nodeName ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="重启" width="50">
              <template #default="{ row }">{{ row.restarts ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }"><StatusBadgeTip :label="row.phase ?? 'Unknown'" :type="podPhaseType(row.phase)" :reason="row.statusReason" /></template>
            </el-table-column>
            <el-table-column label="创建时间（存活时长）" width="140">
              <template #default="{ row }">
                <div class="age-cell"><div>{{ fmtDate(row.creationTime) }}</div><div class="muted">{{ fmtAge(row.creationTime) }}</div></div>
              </template>
            </el-table-column>
            <el-table-column  width="64" fixed="right">
              <template #default="{ row }">
                <el-dropdown trigger="click" @command="(cmd: string) => onPodCommand(cmd, row)">
                  <el-button link type="primary" :icon="MoreFilled" />
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="logs">日志</el-dropdown-item>
                      <el-dropdown-item command="exec" :disabled="row.phase !== 'Running'">Exec</el-dropdown-item>
                      <el-dropdown-item command="yaml">Yaml</el-dropdown-item>
                      <el-dropdown-item divided style="color: var(--el-color-danger)" command="delete">删除</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column >
          </el-table>
        </section>

        <MetricsPanel ref="metricsRef" dimension="workload" :name="detail.name" :kind="detail.kind" :tenant-id="state.tenantId!" :cluster-id="state.clusterId!" :namespace="state.namespace!" :cpu-limit="cpuLimit" :memory-limit="memoryLimit" />
        </div>
      </main>

      <!-- 右：对外暴露 / 更新策略 / 调度策略 / 存储 -->
      <aside class="col col-side">
        <section class="panel info-card">
          <h3 class="info-title">Service</h3>
          <div v-if="(detail.exposedServices ?? []).length" class="info-rows">
            <div v-for="svc in detail.exposedServices" :key="svc.name" class="info-row col-row svc-expose">
              <code class="res-name" @click="goService(svc.name)">{{ svc.name }}</code>
              <span class="muted stack">
                <span v-for="p in svc.ports" :key="p.port" class="mono">
                  {{ p.port }}{{p.nodePort? ':':''}}<span class="name-link">{{p.nodePort}}</span>
                </span>
              </span>
            </div>
          </div>
          <div v-else class="muted empty-line">无Service</div>
        </section>

        <section class="panel info-card">
          <h3 class="info-title">更新策略</h3>
          <div v-if="strategyRows.length" class="info-rows">
            <div v-for="r in strategyRows" :key="r.label" class="info-row">
              <span class="k">{{ r.label }}</span>
              <code class="mono v">{{ r.value }}</code>
            </div>
          </div>
          <div v-else class="muted empty-line">默认策略</div>
        </section>

        <section class="panel info-card">
          <h3 class="info-title">调度策略</h3>
          <div class="info-rows">
            <div class="info-row col-row">
              <span class="k">nodeSelector</span>
              <KvTags title="nodeSelector" :data="nodeSelector" placement="left" />
            </div>
            <div class="info-row col-row">
              <span class="k">亲和 / 反亲和</span>
              <span class="v stack">
                <span v-for="(line, i) in describeAffinity(detail?.podTemplate?.spec?.affinity)" :key="i" class="stack-line">{{ line }}</span>
                <span v-if="!describeAffinity(detail?.podTemplate?.spec?.affinity).length" class="muted">未配置</span>
              </span>
            </div>
            <div class="info-row col-row">
              <span class="k">容忍度</span>
              <span class="v stack">
                <code v-for="(t, i) in tolerations" :key="i" class="mono tol-line">{{ tolText(t) }}</code>
                <span v-if="!tolerations.length" class="muted">未配置</span>
              </span>
            </div>
          </div>
        </section>

        <section class="panel info-card">
          <h3 class="info-title">存储卷 volumes</h3>
          <div class="info-rows">
            <div class="info-row col-row">
              <span class="v stack">
                <div v-for="vol in volumes" :key="vol.name" class="stack-line vol-line">
                  <code class="mono">{{ vol.name }}</code>
                  <span class="muted">{{ vol.type }}<template v-if="volumeRef(vol)"> · {{ volumeRef(vol) }}</template></span>
                </div>
                <span v-if="!volumes.length" class="muted">无</span>
              </span>
            </div>
            <div v-if="detail.kind === 'statefulset'" class="info-row col-row">
              <span class="k">存储卷模板</span>
              <span class="v stack">
                <div v-for="t in pvcTemplates" :key="t.name" class="stack-line vol-line">
                  <code class="mono">{{ t.name }}</code>
                  <span class="muted">{{ t.storage }} · {{ (t.accessModes ?? []).join('/') }}<template v-if="t.storageClassName"> · {{ t.storageClassName }}</template></span>
                </div>
                <span v-if="!pvcTemplates.length" class="muted">无</span>
              </span>
            </div>
          </div>
        </section>
      </aside>
    </div>

    <div v-else class="loading-tip">加载中…</div>

    <!-- Pod 日志（流式：恒跟随 + 底部自动滚动 + 全屏） -->
    <PodLogDialog
      v-model="logVisible"
      :pod="logPod"
      :tenant-id="ctxParams.tenantId"
      :cluster-id="ctxParams.clusterId"
      :namespace="ctxParams.namespace"
    />

    <!-- Pod Exec 终端 -->
    <el-dialog v-model="execVisible" :title="`Exec · ${execPod?.name ?? ''}`" width="960px" destroy-on-close>
      <div class="log-toolbar">
        <el-select v-model="execContainer" size="small" style="width: 200px">
          <el-option v-for="c in execPod?.containers ?? []" :key="c" :label="c" :value="c" />
        </el-select>
        <span class="muted">切换容器会断开当前会话并重连；关闭弹窗即断开</span>
      </div>
      <PodTerminal
        v-if="execVisible && execPod"
        :key="execContainer"
        :tenant-id="ctxParams.tenantId"
        :cluster-id="ctxParams.clusterId"
        :namespace="ctxParams.namespace"
        :name="execPod.name"
        :container="execContainer || undefined"
      />
    </el-dialog>

    <!-- Pod Yaml（只读） -->
    <el-dialog v-model="yamlVisible" :title="`Yaml · ${yamlPod?.name ?? ''}`" width="760px">
      <pre v-if="yamlText" class="yaml-block">{{ yamlText }}</pre>
      <div v-else class="muted">加载中…</div>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 头部右侧的刷新控件组：icon + 间隔下拉，作为一个整体（PageHeader 的 .ph-actions 用大 gap 分隔子项） */
.refresh-bar {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-right: 12px;
}

/* 页面占满一屏：PageHeader 固定 + grid 撑满剩余，三列各自内部滚动（宽屏） */
.wl-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }

.wl-detail {
  flex: 1 1 auto;
  min-height: 0;                 /* 允许收缩 → 各列可内部滚动 */
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: stretch;          /* 三列等高填满 */
}

.col { min-width: 0; min-height: 0; display: flex; flex-direction: column; gap: 16px; }

/* 侧栏：固定高度，内容多则内部滚动（不再 sticky） */
.col-side { overflow-y: auto; }

/* 中间：一个大背板囊括列表 + 监控，整体内部滚动 */
.col-main { overflow: hidden; }
.main-board {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.info-card { padding: 16px; }

/* 窄屏：恢复单列堆叠 + 整页滚动（不固定一屏高） */
@media (max-width: 1180px) {
  .wl-page { height: auto; overflow: visible; }
  .wl-detail { flex: none; grid-template-columns: 1fr; align-items: start; }
  .col, .col-side, .col-main { overflow: visible; }
  .main-board { flex: none; overflow: visible; }
}
.info-title {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: .04em;
  color: var(--text-2);
}
.info-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
  word-break: break-all;
  margin-bottom: 14px;
}
.info-rows { display: flex; flex-direction: column; gap: 12px; }
.info-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.info-row.col-row { flex-direction: column; align-items: flex-start; gap: 6px; }
.info-row .k { color: var(--text-3); flex-shrink: 0; }
.info-row .v { color: var(--text-1); text-align: right; word-break: break-all; }
.col-row .v { text-align: left; width: 100%; }
.desc { white-space: pre-wrap; line-height: 1.5; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.stack { display: flex; flex-direction: column; gap: 4px; width: 100%; }
.stack-line { line-height: 1.5; }
.vol-line { display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap; }
.tol-line { display: inline-block; margin-bottom: 2px; }
.op-badge { margin-bottom: 6px; }
.owner-ref { word-break: break-all; color: var(--text-1); }

.list-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 10px; }
.list-head .info-title { margin: 0; }
.count { font-size: 12px; }

.res-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 13px; color: var(--text-1); }
.name-link { cursor: pointer; color: var(--accent); }
.name-link:hover { text-decoration: underline; }
.img-cell { display: inline-block; margin-right: 6px; word-break: break-all; }
.muted { color: var(--text-3); }
.age-cell { line-height: 1.5; }
.empty-line { font-size: 13px; }
.svc-expose { gap: 4px; }
.loading-tip { padding: 48px; text-align: center; font-size: 13px; color: var(--text-3); }

.log-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.yaml-block {
  margin: 0; padding: 14px; border-radius: 8px; background: var(--panel-hover); border: 1px solid var(--border);
  font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; line-height: 1.6; color: var(--text-2);
  max-height: 60vh; overflow: auto; white-space: pre-wrap;
}
</style>
