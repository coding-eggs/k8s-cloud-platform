<script setup lang="ts">
/**
 * 应用商店（计划①：管理员安装/升级/卸载）。
 *
 * <p>安装向导的顺序是「填 → 预览 → 装」：预览走 /app/preview（渲染 + 静态检查，不落盘），
 * 只要出现 BLOCK 项就禁用提交。真正的拦截在后端（k8s-server 的 /chart/apply 会再检查一次），
 * 前端这层只负责让人在点按钮之前知道会发生什么。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { appApi, clusterApi } from '@/api'
import type { ChartPreviewResult, K8sApp, K8sChartRef, K8sCluster } from '@/types'

const loading = ref(false)
const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
const apps = ref<K8sApp[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const previewing = ref(false)
const preview = ref<ChartPreviewResult | null>(null)
const pollTimer = ref<number | null>(null)
const opStatus = ref('')

const form = ref({
  source: 'HARBOR',
  ref: '',
  chartName: '',
  version: '',
  digest: '',
  namespace: '',
  displayName: '',
  values: '',
})

const chart = computed<K8sChartRef>(() => ({
  source: form.value.source,
  ref: form.value.ref.trim(),
  chartName: form.value.chartName.trim(),
  version: form.value.version.trim(),
  digest: form.value.digest.trim() || undefined,
}))

const blocked = computed(
  () => (preview.value?.violations ?? []).filter((v) => v.severity === 'BLOCK').length > 0,
)

const STATUS_TAG: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  RUNNING: { label: '运行中', type: 'success' },
  INSTALLING: { label: '安装中', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' },
  UNINSTALLED: { label: '已卸载', type: 'info' },
}

async function loadClusters(): Promise<void> {
  clusters.value = (await clusterApi.list()) ?? []
  const firstEnabled = clusters.value.find((c) => c.enabled === 1)
  clusterId.value = firstEnabled?.clusterId ?? clusters.value[0]?.clusterId ?? ''
}

async function loadApps(): Promise<void> {
  if (!clusterId.value) {
    apps.value = []
    return
  }
  loading.value = true
  try {
    apps.value = (await appApi.list(clusterId.value)) ?? []
  } finally {
    loading.value = false
  }
}

async function refresh(): Promise<void> {
  await loadApps()
}

function openInstall(): void {
  preview.value = null
  opStatus.value = ''
  form.value = {
    source: 'HARBOR',
    ref: '',
    chartName: '',
    version: '',
    digest: '',
    namespace: '',
    displayName: '',
    values: '',
  }
  dialogVisible.value = true
}

async function doPreview(): Promise<void> {
  if (!clusterId.value || !form.value.namespace.trim() || !form.value.version.trim()) {
    ElMessage.error('请先选择集群、命名空间并填写 chart 版本')
    return
  }
  previewing.value = true
  try {
    preview.value = await appApi.preview({
      clusterId: clusterId.value,
      namespace: form.value.namespace.trim(),
      chartRef: chart.value,
      values: form.value.values,
    })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '预览失败')
  } finally {
    previewing.value = false
  }
}

async function submit(): Promise<void> {
  if (blocked.value) {
    ElMessage.error('预览里有阻断项，不能安装')
    return
  }
  submitting.value = true
  try {
    const operationId = await appApi.install({
      clusterId: clusterId.value,
      namespace: form.value.namespace.trim(),
      displayName: form.value.displayName.trim() || undefined,
      chartRef: chart.value,
      values: form.value.values,
    })
    dialogVisible.value = false
    ElMessage.success('已提交安装，正在执行')
    poll(operationId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败')
  } finally {
    submitting.value = false
  }
}

async function uninstall(row: K8sApp): Promise<void> {
  await ElMessageBox.confirm(
    `卸载「${row.displayName}」（${row.namespace}/${row.releaseName}）？\n` +
      'CRD 与 PVC 不会被删除；数据卷需要手工处理。',
    '卸载应用',
    { type: 'warning', confirmButtonText: '卸载', cancelButtonText: '取消' },
  )
  const operationId = await appApi.uninstall(row.id)
  ElMessage.success('已提交卸载')
  poll(operationId)
}

function poll(operationId: number): void {
  if (pollTimer.value !== null) {
    window.clearTimeout(pollTimer.value)
  }
  let tries = 0
  const tick = async (): Promise<void> => {
    tries += 1
    try {
      const op = await appApi.operation(operationId)
      opStatus.value = op.status
      if (op.status === 'SUCCESS') {
        pollTimer.value = null
        opStatus.value = ''
        ElMessage.success('操作成功')
        await loadApps()
        return
      }
      if (op.status === 'FAILED' || op.status === 'UNKNOWN') {
        pollTimer.value = null
        opStatus.value = ''
        ElMessage.error(op.message ?? '操作失败')
        await loadApps()
        return
      }
    } catch {
      // 轮询失败不终止（可能是一次网络抖动），继续到次数上限
    }
    if (tries >= 150) {
      pollTimer.value = null
      opStatus.value = ''
      ElMessage.warning('操作仍在进行，请稍后在列表里刷新查看')
      return
    }
    pollTimer.value = window.setTimeout(tick, 2000)
  }
  void tick()
}

onMounted(async () => {
  await loadClusters()
  await loadApps()
})

onBeforeUnmount(() => {
  if (pollTimer.value !== null) {
    window.clearTimeout(pollTimer.value)
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">应用商店</h2>
        <p class="ph-desc">安装 / 卸载应用到集群（helm 执行；装前静态检查、装后复核）</p>
      </div>
      <div class="ph-actions">
        <el-select v-model="clusterId" placeholder="选择集群" style="width: 220px" @change="refresh">
          <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
        </el-select>
        <el-button :disabled="!clusterId" @click="refresh">刷新</el-button>
        <el-button type="primary" :disabled="!clusterId" @click="openInstall">安装应用</el-button>
      </div>
    </div>

    <el-table :data="apps" class="panel" empty-text="这个集群还没有应用">
      <el-table-column prop="displayName" label="名称" min-width="160">
        <template #default="{ row }">
          <span>{{ row.displayName }}</span>
          <el-tag v-if="row.needsReview" size="small" type="danger" effect="plain" style="margin-left: 6px">
            需复核
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="namespace" label="命名空间" width="150" />
      <el-table-column prop="chartName" label="chart" width="160" />
      <el-table-column prop="version" label="版本" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="STATUS_TAG[row.status]?.type ?? 'info'">
            {{ STATUS_TAG[row.status]?.label ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="releaseName" label="release" width="150" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" :disabled="row.status === 'UNINSTALLED'" @click="uninstall(row)">
            卸载
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="安装应用" width="860px" :close-on-click-modal="false">
      <el-form label-width="110px">
        <el-form-item label="来源">
          <el-radio-group v-model="form.source">
            <el-radio value="HARBOR">Harbor（OCI）</el-radio>
            <el-radio value="REPO">HTTP repo</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="form.source === 'HARBOR' ? 'OCI 地址' : 'repo URL'">
          <el-input v-model="form.ref" spellcheck="false"
                    :placeholder="form.source === 'HARBOR'
                      ? 'oci://harbor.example.com/project/chart'
                      : 'https://charts.example.com'" />
        </el-form-item>
        <el-form-item label="chart 名">
          <el-input v-model="form.chartName" spellcheck="false" placeholder="web" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="form.version" spellcheck="false" placeholder="1.2.3" />
        </el-form-item>
        <el-form-item label="digest">
          <el-input v-model="form.digest" spellcheck="false" placeholder="sha256:...（可选；填了就逐字节校验）" />
        </el-form-item>
        <el-form-item label="命名空间">
          <el-input v-model="form.namespace" spellcheck="false" placeholder="demo" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" spellcheck="false" placeholder="默认取 chart 名" />
        </el-form-item>
        <el-form-item label="values">
          <el-input v-model="form.values" type="textarea" :rows="6" spellcheck="false"
                    placeholder="YAML；留空 = chart 默认" />
        </el-form-item>
      </el-form>

      <div class="preview-bar">
        <el-button :loading="previewing" @click="doPreview">预览</el-button>
        <span v-if="opStatus" class="preview-hint">操作进行中：{{ opStatus }}</span>
      </div>

      <div v-if="preview" class="preview">
        <div v-if="preview.violations.length" class="violations">
          <div v-for="(v, i) in preview.violations" :key="i"
               class="violation" :class="v.severity === 'BLOCK' ? 'is-block' : 'is-warn'">
            <b>{{ v.severity }}</b> · {{ v.type }} · {{ v.object }} —— {{ v.message }}
          </div>
        </div>
        <div v-else class="preview-hint">检查通过：没有发现阻断项</div>
        <div class="objects">
          将创建 {{ preview.objects.length }} 个对象：
          <span v-for="(o, i) in preview.objects" :key="i" class="object-chip">
            {{ o.kind }}/{{ o.name }}{{ o.namespace ? '@' + o.namespace : '' }}
          </span>
        </div>
      </div>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="blocked" @click="submit">
          安装
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.ph-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
}
.ph-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.ph-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}
.preview-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 6px 0 10px;
}
.preview-hint {
  font-size: 12.5px;
  color: var(--text-3);
}
.preview {
  border-top: 1px solid var(--border);
  padding-top: 10px;
}
.violation {
  font-size: 12.5px;
  padding: 6px 10px;
  border-radius: 6px;
  margin-bottom: 6px;
}
.violation.is-block {
  background: rgba(239, 68, 68, 0.12);
  color: #ef4444;
}
.violation.is-warn {
  background: rgba(234, 179, 8, 0.12);
  color: #eab308;
}
.objects {
  font-size: 12.5px;
  color: var(--text-2);
  margin-top: 8px;
  line-height: 1.9;
}
.object-chip {
  display: inline-block;
  margin: 0 6px 4px 0;
  padding: 1px 8px;
  border-radius: 10px;
  background: var(--bg-2, rgba(255, 255, 255, 0.06));
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
</style>
