<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { clusterApi, meshApi } from '@/api'
import type { K8sCluster, K8sGatewayClass } from '@/types'

/**
 * GatewayClass 独立编辑页（集群级，平台管理面）。
 * <p>spec 只有三个字段：controllerName（必填）/ parametersRef（可选）/ description（可选）。
 * 全建模、无 fetch-overlay —— 与 Gateway/HTTPRoute 不同，本对象的 spec 没有 atomic list。
 */
const route = useRoute()
const router = useRouter()

const clusters = ref<K8sCluster[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>((route.query.name as string) || null)

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

const form = reactive({
  name: '',
  controllerName: '',
  description: '',
  refEnabled: false,
  refGroup: '',
  refKind: '',
  refName: '',
  refNamespace: '',
})

const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !clusterId.value) return
  detailState.value = 'loading'
  try {
    const d = await meshApi.gatewayClass.get(editing.value, clusterId.value)
    form.name = d.name
    form.controllerName = d.controllerName ?? ''
    form.description = d.description ?? ''
    const ref = d.parametersRef
    form.refEnabled = !!ref
    form.refGroup = ref?.group ?? ''
    form.refKind = ref?.kind ?? ''
    form.refName = ref?.name ?? ''
    form.refNamespace = ref?.namespace ?? ''
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
/** controllerName 的 CRD 校验：DOMAIN/PATH 形态 */
const CONTROLLER_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*\/[A-Za-z0-9/._~%!$&'()*+,;=:-]+$/

const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !clusterId.value) return
  const name = form.name.trim()
  const finalName = editing.value ?? name
  if (!finalName) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(finalName)) { ElMessage.warning('名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'); return }
  const controller = form.controllerName.trim()
  if (!controller) { ElMessage.warning('请输入 controllerName'); return }
  if (!CONTROLLER_RE.test(controller)) { ElMessage.warning('controllerName 须为 DOMAIN/PATH 形态，如 istio.io/gateway-controller'); return }
  if (form.refEnabled && !form.refName.trim()) { ElMessage.warning('已启用配置引用，请填写被引用对象名'); return }

  const body: K8sGatewayClass = {
    name: finalName,
    controllerName: controller,
    description: form.description.trim() || null,
    parametersRef: form.refEnabled
      ? {
          group: form.refGroup.trim() || null,
          kind: form.refKind.trim() || null,
          name: form.refName.trim(),
          namespace: form.refNamespace.trim() || null,
        }
      : null,
  }
  saving.value = true
  try {
    if (editing.value) {
      await meshApi.gatewayClass.update(editing.value, clusterId.value, body)
      ElMessage.success('保存成功')
    } else {
      await meshApi.gatewayClass.create(clusterId.value, body)
      ElMessage.success('创建成功')
    }
    router.push({ name: 'gatewayclasses' })
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push({ name: 'gatewayclasses' }) }

const pageTitle = computed(() => (editing.value ? '编辑 GatewayClass' : '创建 GatewayClass'))
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

onMounted(async () => {
  await loadClusters()
  if (editing.value && clusterId.value) await loadDetail()
})
</script>

<template>
  <div class="editor-page">
    <PageHeader :title="pageTitle" description="Gateway API 入口类别（集群级）">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="detailState === 'error'" title="加载失败" description="该 GatewayClass 可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <el-form v-if="formVisible" label-position="left" label-width="200px" class="editor-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 istio（RFC-1123）" style="max-width: 420px" />
          <FieldHelp tip="GatewayClass 名（集群级唯一，RFC-1123）。Gateway 的 spec.gatewayClassName 指向它。创建后不可改（K8s 对象名不可变）。" />
        </el-form-item>

        <el-form-item label="controllerName" required>
          <el-input v-model="form.controllerName" placeholder="如 istio.io/gateway-controller" style="max-width: 420px" />
          <FieldHelp tip="实现本 GatewayClass 的控制器，形如 DOMAIN/PATH。填错不会报错但永远没有控制器接管 —— 该类别下的 Gateway 会一直停在未编程状态。常用值：Istio = istio.io/gateway-controller；Envoy Gateway = gateway.envoyproxy.io/gatewayclass-controller。" />
        </el-form-item>

        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="可选：人类可读说明" style="max-width: 420px" />
          <FieldHelp tip="spec.description，纯说明性文本，不影响行为。" />
        </el-form-item>

        <el-form-item label="配置引用">
          <el-switch v-model="form.refEnabled" />
          <FieldHelp tip="spec.parametersRef：让控制器读一份私有配置 CRD（例如 Envoy Gateway 的 EnvoyProxy、Istio 的 IstioOperator 之类）。不启用则 spec 里不出现该字段。" />
        </el-form-item>

        <template v-if="form.refEnabled">
          <el-form-item label="引用 · group">
            <el-input v-model="form.refGroup" placeholder="如 gateway.envoyproxy.io" style="max-width: 420px" />
            <FieldHelp tip="被引用 CRD 的 API group（必填）。" />
          </el-form-item>
          <el-form-item label="引用 · kind">
            <el-input v-model="form.refKind" placeholder="如 EnvoyProxy" style="max-width: 420px" />
            <FieldHelp tip="被引用 CRD 的 kind（必填）。" />
          </el-form-item>
          <el-form-item label="引用 · 名称" required>
            <el-input v-model="form.refName" placeholder="被引用对象名" style="max-width: 420px" />
            <FieldHelp tip="被引用对象名（必填）。" />
          </el-form-item>
          <el-form-item label="引用 · 命名空间">
            <el-input v-model="form.refNamespace" placeholder="留空 = 集群级对象" style="max-width: 420px" />
            <FieldHelp tip="被引用对象所在命名空间。留空表示引用的是集群级对象（GatewayClass 自身是集群级，但 parametersRef 可以指向命名空间级 CRD）。" />
          </el-form-item>
        </template>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
          <el-button @click="goBack">取消</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.editor-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.editor-panel {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 24px;
}
.editor-form { max-width: 760px; }
</style>
