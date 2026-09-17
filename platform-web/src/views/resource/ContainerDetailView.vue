<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { podApi } from '@/api'
import type { PodContainerDetail } from '@/types'
import type { EnvVar, EnvFrom, Probe, LifecycleHandler } from '@/types/workload'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import { fmtAge, fmtDate, containerStateType } from '@/utils/format'
import { formatQuantity, parseQuantity } from '@/utils/quantity'

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const podName = computed(() => (route.query.pod as string) || '')
const containerName = computed(() => (route.query.container as string) || '')
// 命名空间：优先取路由 query（从 Pod 详情跨命名空间跳转携带），否则用顶栏选中的命名空间
const ns = computed(() => (route.query.namespace as string) || state.namespace!)

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: ns.value,
}))

const pod = ref<Awaited<ReturnType<typeof podApi.get>> | null>(null)
const loading = ref(false)
const loadError = ref(false)

async function refresh(): Promise<void> {
  if (!ready.value || !podName.value) return
  loading.value = true
  loadError.value = false
  try {
    pod.value = await podApi.get(podName.value, ctxParams.value)
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const container = computed<PodContainerDetail | null>(() =>
  (pod.value?.containerDetails ?? []).find((c) => c.name === containerName.value) ?? null,
)

function goBack(): void {
  router.push(`/resources/pods/detail?name=${encodeURIComponent(podName.value)}&namespace=${encodeURIComponent(ns.value)}`)
}

// ---------- 展示辅助 ----------
const isInit = computed(() => !!container.value?.init)

/** 容器运行态悬浮提示：waiting/terminated 的 reason + message（Running 时两者皆空 → null，不显示） */
function containerTip(c: PodContainerDetail | null): string | null {
  if (!c) return null
  const parts = [c.reason, c.message].filter((s): s is string => !!s && s.trim() !== '')
  return parts.length ? parts.join(' — ') : null
}

const envs = computed<EnvVar[]>(() => container.value?.envs ?? [])
function envSource(e: EnvVar): string {
  const vf = e.valueFrom
  if (!vf) return ''
  if (vf.configMapKeyRef?.key) return 'ConfigMap'
  if (vf.secretKeyRef?.key) return 'Secret'
  if (vf.fieldRef?.fieldPath) return 'FieldRef'
  if (vf.fileKeyRef?.volumeName) return 'Volume'
  if (vf.resourceKeyRef?.containerName) return 'Resource'
  return ''
}
function envValueText(e: EnvVar): string {
  if (e.value) return e.value;
  const vf = e.valueFrom
  if (!vf) return ''
  if (vf.configMapKeyRef && vf.configMapKeyRef.key) {
    return `${vf.configMapKeyRef.name}.${vf.configMapKeyRef.key}`
  }
  if (vf.fieldRef && vf.fieldRef.fieldPath) {
    return `${vf.fieldRef.fieldPath}`
  }
  if (vf.secretKeyRef && vf.secretKeyRef.key) {
    return `${vf.secretKeyRef.name}.${vf.secretKeyRef.key}`
  }
  if (vf.fileKeyRef && vf.fileKeyRef.key) {
    return `${vf.fileKeyRef.path}.${vf.fileKeyRef.key}`
  }
  if (vf.resourceKeyRef && vf.resourceKeyRef.containerName) {
    return `${vf.resourceKeyRef.containerName}.${vf.resourceKeyRef.resource}.${vf.resourceKeyRef.divisor}`
  }

  return '—'
}

const envFrom = computed<EnvFrom[]>(() => container.value?.envFrom ?? [])
function envFromText(f: EnvFrom): string {
  const parts: string[] = []
  if (f.prefix) parts.push(`前缀 ${f.prefix}`)
  if (f.configMapRef && f.configMapRef.name) parts.push(`ConfigMap.${f.configMapRef.name}`)
  if (f.secretRef && f.secretRef.name) parts.push(`Secret.${f.secretRef.name}`)
  return parts.join(' · ') || '—'
}

const limits = computed<Record<string, number | string>>(() => container.value?.resources?.limits ?? {})
const requests = computed<Record<string, number | string>>(() => container.value?.resources?.requests ?? {})

/** 资源值展示：基础单位数字 → cpu 核 / memory 人性化字节 / 其它原值；兼容旧字符串。 */
function fmtRes(key: string, v: number | string): string {
  const n = typeof v === 'number' ? v : parseQuantity(v)
  if (Number.isNaN(n)) return String(v)
  const kind = key === 'cpu' ? 'cpu' : key === 'memory' ? 'memory' : 'count'
  return formatQuantity(n, kind)
}

function handlerText(h?: LifecycleHandler | null): string {
  if (!h) return ''
  if (h.exec) return `exec: ${h.exec.command.join(' ')}`
  if (h.httpGet) return `HTTP GET :${h.httpGet.port ?? ''}${h.httpGet.path ?? '/'}`
  if (h.sleep) return `sleep ${h.sleep.seconds}s`
  return ''
}

function probeHandler(p?: Probe | null): string {
  if (!p) return ''
  if (p.httpGet) return `HTTP GET :${p.httpGet.port ?? ''}${p.httpGet.path ?? '/'}`
  if (p.tcpSocket) return `TCP :${p.tcpSocket.port ?? ''}`
  if (p.exec) return `exec: ${p.exec.command.join(' ')}`
  return ''
}
function probeTiming(p?: Probe | null): string {
  if (!p) return ''
  const parts: string[] = []
  if (p.initialDelaySeconds != null) parts.push(`延迟 ${p.initialDelaySeconds}s`)
  if (p.periodSeconds != null) parts.push(`周期 ${p.periodSeconds}s`)
  if (p.timeoutSeconds != null) parts.push(`超时 ${p.timeoutSeconds}s`)
  if (p.successThreshold != null) parts.push(`成功阈值 ${p.successThreshold}`)
  if (p.failureThreshold != null) parts.push(`失败阈值 ${p.failureThreshold}`)
  return parts.join(' · ')
}

const hasAnyProbe = computed(() => !!(probeHandler(container.value?.livenessProbe) || probeHandler(container.value?.readinessProbe) || probeHandler(container.value?.startupProbe)))

// ---------- 上下文联动 ----------
onMounted(() => {
  void load()
  if (ready.value) void refresh()
})
watch(ready, (r) => { if (r && !pod.value) void refresh() })
watch([podName, containerName], () => { if (ready.value) void refresh() })

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${ns.value}`
})
</script>

<template>
  <div v-loading="loading || !ready">
    <PageHeader :title="`容器详情 · ${containerName || '—'}`" >
      <el-button @click="goBack">返回 Pod</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏依次选择租户、集群、命名空间后查看容器详情。" />

    <template v-else-if="loadError">
      <EmptyState title="加载失败" description="该 Pod 可能已被删除，或当前上下文下不存在。">
        <el-button type="primary" @click="goBack">返回</el-button>
      </EmptyState>
    </template>

    <div v-else-if="container" class="cd-wrap">
      <!-- 头部：名称 / 镜像 / 状态 -->
      <section class="panel cd-hero">
        <div class="hero-main">
          <div class="hero-kicker">{{ isInit ? '初始化容器' : '主 / 副容器' }} · {{ podName }}</div>
          <h2 class="hero-name">{{ container.name }}</h2>
          <code v-if="container.image" class="hero-image">{{ container.image }}</code>
        </div>
        <div class="hero-badges">
          <el-tag v-if="!isInit" size="large" :type="container.ready ? 'success' : 'info'" effect="light" round>{{ container.ready ? '就绪' : '未就绪' }}</el-tag>
          <StatusBadgeTip :label="container.state ?? 'Unknown'" :type="containerStateType(container.state)" :reason="containerTip(container)" />
          <div class="hero-restart">
            <span class="num">{{ container.restartCount ?? 0 }}</span>
            <span class="lbl">重启次数</span>
          </div>
        </div>
      </section>

      <!-- 基础信息 -->
      <section class="panel cd-card">
        <h3 class="cd-title">基础信息</h3>
        <div class="def-grid">
          <div class="def-item"><span class="def-k">名称</span><code class="def-v mono">{{ container.name || '—' }}</code></div>
          <div class="def-item"><span class="def-k">镜像</span><code class="def-v mono">{{ container.image || '—' }}</code></div>
          <div class="def-item"><span class="def-k">Command</span><code class="def-v mono multi">{{ container.command?.length ? container.command.join(' ') : '（使用镜像默认）' }}</code></div>
          <div class="def-item"><span class="def-k">Args</span><code class="def-v mono multi">{{ container.args?.length ? container.args.join(' ') : '—' }}</code></div>
          <div class="def-item"><span class="def-k">工作目录</span><code class="def-v mono">{{ container.workingDir || '—' }}</code></div>
          <div class="def-item"><span class="def-k">拉取策略</span><span class="def-v">{{ container.imagePullPolicy || '默认（按 tag 决定）' }}</span></div>
        </div>
      </section>

      <!-- 环境变量 -->
      <section class="panel cd-card">
        <h3 class="cd-title">环境变量 <span class="cd-count">{{ envs.length }}</span></h3>
        <el-table v-if="envs.length" :data="envs"  stripe>
          <el-table-column prop="name" label="变量名" min-width="180">
            <template #default="{ row }"><code class="mono">{{ row.name }}</code></template>
          </el-table-column>
          <el-table-column label="值 / 引用" min-width="220">
            <template #default="{ row }"><code class="mono">{{ envValueText(row) }}</code></template>
          </el-table-column>
          <el-table-column label="来源" width="130">
            <template #default="{ row }">
              <el-tag v-if="envSource(row)"  type="info" effect="plain">{{ envSource(row) }}</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="cd-empty">未配置环境变量</div>
      </section>

      <!-- 环境来源 -->
      <section class="panel cd-card">
        <h3 class="cd-title">环境来源 envFrom <span class="cd-count">{{ envFrom.length }}</span></h3>
        <ul v-if="envFrom.length" class="from-list">
          <li v-for="(f, i) in envFrom" :key="i"><code class="mono">{{ envFromText(f) }}</code></li>
        </ul>
        <div v-else class="cd-empty">未配置环境来源</div>
      </section>

      <!-- 端口 -->
      <section class="panel cd-card">
        <h3 class="cd-title">端口 <span class="cd-count">{{ (container.ports ?? []).length }}</span></h3>
        <el-table v-if="(container.ports ?? []).length" :data="container.ports" size="small" stripe>
          <el-table-column prop="name" label="名称" min-width="140">
            <template #default="{ row }"><code class="mono">{{ row.name || '—' }}</code></template>
          </el-table-column>
          <el-table-column prop="containerPort" label="端口" width="120">
            <template #default="{ row }"><span class="mono">{{ row.containerPort ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="protocol" label="协议" width="120">
            <template #default="{ row }">{{ row.protocol ?? 'TCP' }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="cd-empty">未配置端口</div>
      </section>

      <!-- 资源 -->
      <section class="panel cd-card">
        <h3 class="cd-title">资源 limits / requests</h3>
        <div v-if="Object.keys(limits).length || Object.keys(requests).length" class="res-cols">
          <div class="res-block">
            <div class="res-sub">Limits（上限）</div>
            <div v-for="(val, key) in limits" :key="'l' + key" class="res-row"><span class="mono k">{{ key }}</span><code class="mono v">{{ fmtRes(key, val) }}</code></div>
            <div v-if="!Object.keys(limits).length" class="cd-empty">未设置</div>
          </div>
          <div class="res-block">
            <div class="res-sub">Requests（请求）</div>
            <div v-for="(val, key) in requests" :key="'r' + key" class="res-row"><span class="mono k">{{ key }}</span><code class="mono v">{{ fmtRes(key, val) }}</code></div>
            <div v-if="!Object.keys(requests).length" class="cd-empty">未设置</div>
          </div>
        </div>
        <div v-else class="cd-empty">未配置资源配额</div>
      </section>

      <!-- 生命周期（仅主/副容器） -->
      <section v-if="!isInit" class="panel cd-card">
        <h3 class="cd-title">生命周期 hooks</h3>
        <div v-if="handlerText(container.lifecycle?.postStart) || handlerText(container.lifecycle?.preStop)" class="def-grid single">
          <div class="def-item"><span class="def-k">postStart</span><code class="def-v mono">{{ handlerText(container.lifecycle?.postStart) || '—' }}</code></div>
          <div class="def-item"><span class="def-k">preStop</span><code class="def-v mono">{{ handlerText(container.lifecycle?.preStop) || '—' }}</code></div>
        </div>
        <div v-else class="cd-empty">未配置生命周期钩子</div>
      </section>

      <!-- 探针（仅主/副容器） -->
      <section v-if="!isInit" class="panel cd-card">
        <h3 class="cd-title">探针 probes</h3>
        <div v-if="hasAnyProbe" class="probe-list">
          <div v-for="p in [
            { key: 'liveness', label: '存活探针', probe: container.livenessProbe },
            { key: 'readiness', label: '就绪探针', probe: container.readinessProbe },
            { key: 'startup', label: '启动探针', probe: container.startupProbe },
          ]" :key="p.key" class="probe-item">
            <div class="probe-head">
              <span class="probe-label">{{ p.label }}</span>
              <el-tag v-if="probeHandler(p.probe)" size="small" type="success" effect="light" round>已配置</el-tag>
              <el-tag v-else size="small" type="info" effect="plain" round>未配置</el-tag>
            </div>
            <template v-if="probeHandler(p.probe)">
              <code class="mono probe-action">{{ probeHandler(p.probe) }}</code>
              <div v-if="probeTiming(p.probe)" class="probe-timing">{{ probeTiming(p.probe) }}</div>
            </template>
          </div>
        </div>
        <div v-else class="cd-empty">未配置探针</div>
      </section>

      <!-- 卷挂载 -->
      <section class="panel cd-card">
        <h3 class="cd-title">卷挂载 volumeMounts <span class="cd-count">{{ (container.volumeMounts ?? []).length }}</span></h3>
        <el-table v-if="(container.volumeMounts ?? []).length" :data="container.volumeMounts" size="small" stripe>
          <el-table-column prop="name" label="卷名" min-width="150">
            <template #default="{ row }"><code class="mono">{{ row.name }}</code></template>
          </el-table-column>
          <el-table-column prop="mountPath" label="挂载路径" min-width="200">
            <template #default="{ row }"><code class="mono">{{ row.mountPath }}</code></template>
          </el-table-column>
          <el-table-column label="只读" width="90">
            <template #default="{ row }">{{ row.readOnly ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column prop="subPath" label="子路径" min-width="120">
            <template #default="{ row }"><code class="mono">{{ row.subPath || '—' }}</code></template>
          </el-table-column>
        </el-table>
        <div v-else class="cd-empty">未挂载卷</div>
      </section>
    </div>

    <EmptyState v-else-if="pod" title="未找到该容器" :description="`Pod「${podName}」中没有名为「${containerName}」的容器。`">
      <el-button type="primary" @click="goBack">返回 Pod</el-button>
    </EmptyState>

    <div v-else class="loading-tip">加载中…</div>
  </div>
</template>

<style scoped>
.cd-wrap { display: flex; flex-direction: column; gap: 16px;  }

.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }
.muted { color: var(--text-3); }

/* 头部 */
.cd-hero {
  display: flex; align-items: center; justify-content: space-between; gap: 20px;
  padding: 20px 24px; position: relative; overflow: hidden;
}
.cd-hero::before {
  content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px;
  background: var(--accent-grad);
}
.hero-kicker { font-size: 12px; color: var(--text-3); margin-bottom: 6px; }
.hero-name { margin: 0; font-family: Consolas, 'JetBrains Mono', monospace; font-size: 22px; font-weight: 700; color: var(--text-1); word-break: break-all; }
.hero-image { display: inline-block; margin-top: 8px; padding: 4px 10px; border-radius: 6px; background: var(--panel-hover); border: 1px solid var(--border); color: var(--text-2); font-size: 13px; word-break: break-all; }
.hero-badges { display: flex; align-items: center; gap: 2rem; flex-shrink: 0; }
.hero-restart { display: flex; flex-direction: column; align-items: center; line-height: 1.2; }
.hero-restart .num { font-size: 20px; font-weight: 700; color: var(--text-1); }
.hero-restart .lbl { font-size: 11px; color: var(--text-3); }

/* 卡片 */
.cd-card { padding: 18px 20px; }
.cd-title { margin: 0 0 14px; font-size: 14px; font-weight: 700; color: var(--text-1); display: flex; align-items: center; gap: 8px; }
.cd-count { font-size: 12px; font-weight: 500; color: var(--text-3); background: var(--panel-hover); border-radius: 10px; padding: 1px 8px; }
.cd-empty { color: var(--text-3); font-size: 13px; padding: 4px 0; }

/* 定义网格 */
.def-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 28px; }
.def-grid.single { grid-template-columns: 1fr; }
@media (max-width: 720px) { .def-grid { grid-template-columns: 1fr; } }
.def-item { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.def-k { font-size: 12px; color: var(--text-3); }
.def-v { font-size: 13px; color: var(--text-1); word-break: break-all; }
.def-v.multi { white-space: pre-wrap; line-height: 1.5; }

/* envFrom 列表 */
.from-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.from-list li { padding: 8px 12px; border-radius: 8px; background: var(--panel-hover); border: 1px solid var(--border); }

/* 资源 */
.res-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
@media (max-width: 720px) { .res-cols { grid-template-columns: 1fr; } }
.res-sub { font-size: 12px; color: var(--text-3); margin-bottom: 8px; padding-bottom: 6px; border-bottom: 1px solid var(--border); }
.res-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 4px 0; }
.res-row .k { color: var(--text-2); }
.res-row .v { color: var(--text-1); }

/* 探针 */
.probe-list { display: flex; flex-direction: column; gap: 12px; }
.probe-item { padding: 12px 14px; border-radius: 8px; background: var(--panel-hover); border: 1px solid var(--border); }
.probe-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.probe-label { font-size: 13px; font-weight: 600; color: var(--text-1); }
.probe-action { display: inline-block; padding: 4px 8px; border-radius: 5px; background: var(--panel); border: 1px solid var(--border); color: var(--text-2); margin-bottom: 6px; word-break: break-all; }
.probe-timing { font-size: 12px; color: var(--text-3); }

.loading-tip { padding: 48px; text-align: center; font-size: 13px; color: var(--text-3); }
</style>
