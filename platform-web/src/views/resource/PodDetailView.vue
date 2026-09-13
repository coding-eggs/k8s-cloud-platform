<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { podApi } from '@/api'
import type { K8sPod, PodContainerDetail } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadgeTip from '@/components/StatusBadgeTip.vue'
import KvTags from '@/components/KvTags.vue'
import MetricsPanel from '@/components/workload/MetricsPanel.vue'
import { fmtAge, fmtDate, podPhaseType, containerStateType } from '@/utils/format'
import { sumCpuCores, sumMemBytes } from '@/utils/metrics'

const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()

const name = computed(() => (route.query.name as string) || '')

const ctxParams = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

const pod = ref<K8sPod | null>(null)
const loading = ref(false)
const loadError = ref(false)
/** 监控面板引用：全局刷新时联动重取指标 */
const metricsRef = ref<InstanceType<typeof MetricsPanel> | null>(null)

async function refresh(): Promise<void> {
  if (!ready.value || !name.value) return
  loading.value = true
  loadError.value = false
  try {
    pod.value = await podApi.get(name.value, ctxParams.value)
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function goBack(): void {
  router.push('/resources/workloads')
}
function goContainer(c: PodContainerDetail): void {
  if (!c.name) return
  router.push(`/resources/pods/container?pod=${encodeURIComponent(name.value)}&container=${encodeURIComponent(c.name)}`)
}

/** 容器运行态悬浮提示：waiting/terminated 的 reason + message（Running 时两者皆空 → null，不显示） */
function containerTip(c: PodContainerDetail): string | null {
  const parts = [c.reason, c.message].filter((s): s is string => !!s && s.trim() !== '')
  return parts.length ? parts.join(' — ') : null
}

// ---------- 全局刷新（顶栏）：Pod + 监控指标一起重取 ----------
/** 自动刷新：0 = 不刷新；其余为毫秒间隔 */
const REFRESH_OPTIONS: { label: string; value: number }[] = [
  { label: '不刷新', value: 0 },
  { label: '30s', value: 30_000 },
  { label: '1m', value: 60_000 },
  { label: '5m', value: 300_000 },
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

/** 全局刷新：Pod + 监控指标。上下文/名称切换仍走 refresh()，指标由 MetricsPanel 自身 watch 触发。 */
async function refreshAll(): Promise<void> {
  await refresh()
  metricsRef.value?.refresh()
}

// ---------- 容器分组：主容器（首个常规）/ 副容器（其余常规）/ 初始化容器 ----------
const allContainers = computed<PodContainerDetail[]>(() => pod.value?.containerDetails ?? [])
const mainContainers = computed(() => allContainers.value.filter((c) => !c.init).slice(0, 1))
const sidecarContainers = computed(() => allContainers.value.filter((c) => !c.init).slice(1))
const initContainers = computed(() => allContainers.value.filter((c) => c.init))

// ---------- 监控指标配额（CPU/内存 % 切换用；取非 init 容器的 limit 之和）----------
const metricContainers = computed(() => allContainers.value.filter((c) => !c.init))
const cpuLimit = computed(() => sumCpuCores(metricContainers.value))
const memoryLimit = computed(() => sumMemBytes(metricContainers.value))

function portText(c: PodContainerDetail): string {
  const ps = (c.ports ?? []).filter((p) => p.containerPort != null)
  if (!ps.length) return '—'
  return ps
    .map((p) => (p.name ? `${p.name}:${p.containerPort}/${p.protocol ?? 'TCP'}` : `${p.containerPort}/${p.protocol ?? 'TCP'}`))
    .join(', ')
}

// ---------- 上下文联动 ----------
onMounted(() => {
  void load()
  if (ready.value) void refresh()
})
watch(ready, (r) => { if (r && !pod.value) void refresh() })
watch(name, () => { if (ready.value) void refresh() })

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>

<template>
  <div v-loading="loading || !ready" class="pod-page">
    <PageHeader :title="`Pod 详情 · ${name || '—'}`">
      <span class="refresh-bar">
        <el-tooltip content="立即刷新" placement="bottom" :show-after="100">
          <el-button :icon="Refresh" circle size="small" :disabled="!ready" @click="refreshAll" />
        </el-tooltip>
        <el-select v-model="autoRefresh" size="small" style="width: 108px">
          <el-option v-for="o in REFRESH_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </span>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择上下文" description="请在顶栏依次选择租户、集群、命名空间后查看 Pod 详情。" />

    <template v-else-if="loadError">
      <EmptyState title="加载失败" description="该 Pod 可能已被删除，或当前上下文下不存在。">
        <el-button type="primary" @click="goBack">返回</el-button>
      </EmptyState>
    </template>

    <div v-else-if="pod" class="pod-detail">
      <!-- 左：Pod 基本信息 -->
      <aside class="col col-side">
        <section class="panel info-card">
          <h3 class="info-title">Pod 信息</h3>
          <div class="info-name">{{ pod.name }}</div>
          <div class="info-rows">
            <div class="info-row"><span class="k">状态</span><StatusBadgeTip :label="pod.phase ?? 'Unknown'" :type="podPhaseType(pod.phase)" :reason="pod.statusReason" /></div>
            <div class="info-row"><span class="k">IP</span><code class="mono v">{{ pod.podIp ?? '—' }}</code></div>
            <div class="info-row"><span class="k">重启次数</span><span class="v">{{ pod.restarts ?? 0 }}</span></div>
            <div class="info-row"><span class="k">调度节点</span><code class="mono v">{{ pod.nodeName ?? '—' }}</code></div>
            <div class="info-row"><span class="k">ServiceAccount</span><code class="mono v">{{ pod.serviceAccountName || 'default' }}</code></div>
            <div class="info-row"><span class="k">创建时间</span><span class="v">{{ fmtDate(pod.creationTime) }}</span></div>
            <div class="info-row"><span class="k">存活时长</span><span class="v">{{ fmtAge(pod.creationTime) }}</span></div>
            <div class="info-row col-row">
              <span class="k">标签</span>
              <KvTags title="标签" :data="pod.labels ?? {}" />
            </div>
            <div class="info-row col-row">
              <span class="k">注解</span>
              <KvTags title="注解" :data="pod.annotations ?? {}" />
            </div>
          </div>
        </section>
      </aside>

      <!-- 中：容器列表（主 / 副 / 初始化，上）+ 监控指标（下） -->
      <main class="col col-main">
        <div class="main-board panel">
        <EmptyState v-if="allContainers.length === 0 && !loading" title="暂无容器信息" description="该 Pod 未返回容器详情。" />

        <template v-else>
          <section v-if="mainContainers.length" class="panel group-card">
            <div class="group-head"><h3 class="info-title">主容器</h3><span class="muted count">{{ mainContainers.length }}</span></div>
            <div class="container-list">
              <article v-for="c in mainContainers" :key="'m' + c.name" class="container-card" @click="goContainer(c)">
                <header class="cc-head">
                  <code class="cc-name">{{ c.name }}</code>
                  <div class="cc-badges">
                    <el-tag v-if="!c.init" size="small" :type="c.ready ? 'success' : 'info'" effect="light" round>{{ c.ready ? '就绪' : '未就绪' }}</el-tag>
                    <StatusBadgeTip :label="c.state ?? 'Unknown'" :type="containerStateType(c.state)" :reason="containerTip(c)" />
                  </div>
                </header>
                <dl class="cc-body">
                  <div class="cc-row"><dt>镜像</dt><dd><code class="mono">{{ c.image || '—' }}</code></dd></div>
                  <div class="cc-row"><dt>完整命令</dt><dd class="cmd">
                    <code v-if="c.command?.length" class="mono">{{ c.command.join(' ') }}</code>
                    <code v-if="c.args?.length" class="mono args">{{ c.args.join(' ') }}</code>
                    <span v-if="!c.command?.length && !c.args?.length" class="muted">使用镜像默认命令</span>
                  </dd></div>
                  <div class="cc-row"><dt>端口</dt><dd><span class="mono">{{ portText(c) }}</span></dd></div>
                  <div class="cc-row"><dt>创建时间（存活时长）</dt>
                    <dd>
                      <template v-if="c.startedAt">{{ fmtDate(c.startedAt) }} <span class="muted">· {{ fmtAge(c.startedAt) }}</span></template>
                      <span v-else class="muted">—</span>
                    </dd>
                  </div>
                </dl>
              </article>
            </div>
          </section>

          <section v-if="sidecarContainers.length" class="panel group-card">
            <div class="group-head"><h3 class="info-title">副容器（Sidecar）</h3><span class="muted count">{{ sidecarContainers.length }}</span></div>
            <div class="container-list">
              <article v-for="c in sidecarContainers" :key="'s' + c.name" class="container-card" @click="goContainer(c)">
                <header class="cc-head">
                  <code class="cc-name">{{ c.name }}</code>
                  <div class="cc-badges">
                    <el-tag size="small" :type="c.ready ? 'success' : 'info'" effect="light" round>{{ c.ready ? '就绪' : '未就绪' }}</el-tag>
                    <StatusBadgeTip :label="c.state ?? 'Unknown'" :type="containerStateType(c.state)" :reason="containerTip(c)" />
                  </div>
                </header>
                <dl class="cc-body">
                  <div class="cc-row"><dt>镜像</dt><dd><code class="mono">{{ c.image || '—' }}</code></dd></div>
                  <div class="cc-row"><dt>完整命令</dt><dd class="cmd">
                    <code v-if="c.command?.length" class="mono">{{ c.command.join(' ') }}</code>
                    <code v-if="c.args?.length" class="mono args">{{ c.args.join(' ') }}</code>
                    <span v-if="!c.command?.length && !c.args?.length" class="muted">使用镜像默认命令</span>
                  </dd></div>
                  <div class="cc-row"><dt>端口</dt><dd><span class="mono">{{ portText(c) }}</span></dd></div>
                  <div class="cc-row"><dt>创建时间（存活时长）</dt><dd>
                    <template v-if="c.startedAt">{{ fmtDate(c.startedAt) }} <span class="muted">· {{ fmtAge(c.startedAt) }}</span></template>
                    <span v-else class="muted">—</span>
                  </dd></div>
                </dl>
              </article>
            </div>
          </section>

          <section v-if="initContainers.length" class="panel group-card">
            <div class="group-head"><h3 class="info-title">初始化容器（Init）</h3><span class="muted count">{{ initContainers.length }}</span></div>
            <div class="container-list">
              <article v-for="c in initContainers" :key="'i' + c.name" class="container-card" @click="goContainer(c)">
                <header class="cc-head">
                  <code class="cc-name">{{ c.name }}</code>
                  <div class="cc-badges">
                    <StatusBadgeTip :label="c.state ?? 'Unknown'" :type="containerStateType(c.state)" :reason="containerTip(c)" />
                  </div>
                </header>
                <dl class="cc-body">
                  <div class="cc-row"><dt>镜像</dt><dd><code class="mono">{{ c.image || '—' }}</code></dd></div>
                  <div class="cc-row"><dt>完整命令</dt><dd class="cmd">
                    <code v-if="c.command?.length" class="mono">{{ c.command.join(' ') }}</code>
                    <code v-if="c.args?.length" class="mono args">{{ c.args.join(' ') }}</code>
                    <span v-if="!c.command?.length && !c.args?.length" class="muted">使用镜像默认命令</span>
                  </dd></div>
                  <div class="cc-row"><dt>端口</dt><dd><span class="mono">{{ portText(c) }}</span></dd></div>
                  <div class="cc-row"><dt>创建时间（存活时长）</dt><dd>
                    <template v-if="c.startedAt">{{ fmtDate(c.startedAt) }} <span class="muted">· {{ fmtAge(c.startedAt) }}</span></template>
                    <span v-else class="muted">—</span>
                  </dd></div>
                </dl>
              </article>
            </div>
          </section>
        </template>

        <MetricsPanel ref="metricsRef" dimension="pod" :name="pod.name" :tenant-id="state.tenantId!" :cluster-id="state.clusterId!" :namespace="state.namespace!" :cpu-limit="cpuLimit" :memory-limit="memoryLimit" />
        </div>
      </main>
    </div>

    <div v-else class="loading-tip">加载中…</div>
  </div>
</template>

<style scoped>
/* 页面占满一屏：PageHeader 固定 + grid 撑满剩余，各列各自内部滚动（宽屏） */
.pod-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }

.pod-detail {
  flex: 1 1 auto;
  min-height: 0;                 /* 允许收缩 → 各列可内部滚动 */
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;          /* 两列等高填满 */
}

.col { min-width: 0; min-height: 0; display: flex; flex-direction: column; gap: 16px; }

/* 侧栏：固定高度，内容多则内部滚动（不再 sticky） */
.col-side { overflow-y: auto; }

/* 中间：一个大背板囊括容器分组 + 监控，整体内部滚动 */
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
.group-card { padding: 16px; }

/* 窄屏：恢复单列堆叠 + 整页滚动（不固定一屏高） */
@media (max-width: 960px) {
  .pod-page { height: auto; overflow: visible; }
  .pod-detail { flex: none; grid-template-columns: 1fr; align-items: start; }
  .col, .col-side, .col-main { overflow: visible; }
  .main-board { flex: none; overflow: visible; }
}
.info-title { margin: 0; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.info-name {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 16px; font-weight: 600; color: var(--text-1);
  word-break: break-all; margin-bottom: 14px;
}
.info-rows { display: flex; flex-direction: column; gap: 12px; }
.info-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.info-row.col-row { flex-direction: column; align-items: flex-start; gap: 6px; }
.info-row .k { color: var(--text-3); flex-shrink: 0; }
.info-row .v { color: var(--text-1); text-align: right; word-break: break-all; }
.col-row .v { text-align: left; width: 100%; }
.refresh-bar { display: inline-flex; align-items: center; gap: 8px; margin-right: 12px; }
.mono { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 12.5px; }

.group-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }
.count { font-size: 12px; }
.container-list { display: flex; flex-direction: column; gap: 10px; }

.container-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color .15s, background .15s;
}
.container-card:hover { border-color: var(--accent); background: var(--panel-hover); }
.cc-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.cc-name { font-family: Consolas, 'JetBrains Mono', monospace; font-size: 14px; font-weight: 600; color: var(--accent); word-break: break-all; }
.cc-badges { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }

.cc-body { margin: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 8px 24px; }
@media (max-width: 720px) { .cc-body { grid-template-columns: 1fr; } }
.cc-row { display: flex; gap: 10px; min-width: 0; }
.cc-row dt { color: var(--text-3); font-size: 12.5px; width: 150px; flex-shrink: 0; }
.cc-row dd { margin: 0; color: var(--text-1); font-size: 12.5px; word-break: break-all; min-width: 0; }
.cc-row .cmd { display: flex; flex-wrap: wrap; gap: 6px; align-items: baseline; }
.cc-row .args { color: var(--info); }
.muted { color: var(--text-3); }
.loading-tip { padding: 48px; text-align: center; font-size: 13px; color: var(--text-3); }
</style>
