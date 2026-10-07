<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import { clusterApi, calicoApi } from '@/api'
import type { K8sCluster, K8sIpool } from '@/types'

const route = useRoute()
const router = useRouter()

// ---- 上下文：集群级（clusterId 来自 query + 下拉切换）；?name= → 编辑回填，无 name → 创建 ----
const clusters = ref<K8sCluster[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
const editing = ref<string | null>(route.query.name as string | null)

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

// ---- 表单模型（name 仅创建可填）----
interface SelectorRow { value: string }
const form = reactive({
  name: '',
  cidr: '',
  blockSize: null as number | null,
  nodeSelector: [] as SelectorRow[],
  natOutgoing: false,
  disabled: false,
  ipv4hierarchicalPortAllocation: false,
})

const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !clusterId.value) return
  detailState.value = 'loading'
  try {
    const d = await calicoApi.ippool.get(editing.value, clusterId.value)
    form.name = d.name
    form.cidr = d.cidr ?? ''
    form.blockSize = d.blockSize ?? null
    form.nodeSelector = (d.nodeSelector ?? []).map((v) => ({ value: v }))
    form.natOutgoing = !!d.natOutgoing
    form.disabled = !!d.disabled
    form.ipv4hierarchicalPortAllocation = !!d.ipv4hierarchicalPortAllocation
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

function addSelector(): void { form.nodeSelector.push({ value: '' }) }
function removeSelector(i: number): void { form.nodeSelector.splice(i, 1) }

const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

// ---- 提交（update 为整对象替换：清空字段 → 后端移除该 spec key）----
const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !clusterId.value) return
  const name = form.name.trim()
  const finalName = editing.value ?? name
  if (!finalName) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(finalName)) { ElMessage.warning('名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'); return }
  const cidr = form.cidr.trim()
  if (!cidr) { ElMessage.warning('请输入 CIDR'); return }

  const body: K8sIpool = {
    name: finalName,
    clusterId: clusterId.value,
    cidr,
    blockSize: form.blockSize,
    nodeSelector: form.nodeSelector.map((r) => r.value.trim()).filter(Boolean),
    natOutgoing: form.natOutgoing,
    disabled: form.disabled,
    ipv4hierarchicalPortAllocation: form.ipv4hierarchicalPortAllocation,
  }
  saving.value = true
  try {
    if (editing.value) {
      await calicoApi.ippool.update(editing.value, clusterId.value, body)
      ElMessage.success('保存成功')
    } else {
      await calicoApi.ippool.create(clusterId.value, body)
      ElMessage.success('创建成功')
    }
    router.push('/ops/ippools')
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/ops/ippools') }

const pageTitle = computed(() => (editing.value ? '编辑地址池' : '创建地址池'))
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

onMounted(async () => {
  await loadClusters()
  if (editing.value && clusterId.value) await loadDetail()
})
</script>

<template>
  <div class="editor-page">
    <PageHeader :title="pageTitle" description="Calico IPPool（集群级 CRD）">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="detailState === 'error'" title="加载失败" description="该地址池可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <el-form v-if="formVisible" label-position="left" label-width="200px" class="editor-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editing" placeholder="如 default-ipv4-ippool（RFC-1123）" style="max-width: 420px" />
          <FieldHelp tip="IPPool 名（集群级唯一，RFC-1123：小写字母/数字/-）。创建时可填，编辑时不可改。" />
        </el-form-item>

        <el-form-item label="CIDR" required>
          <el-input v-model="form.cidr" placeholder="如 10.48.0.0/16（IPv4）或 fd00::/64（IPv6）" style="max-width: 420px" />
          <FieldHelp tip="地址池的 CIDR。Calico 禁止池间重叠。必填。" />
        </el-form-item>

        <el-form-item label="块大小（blockSize）">
          <el-input-number v-model="form.blockSize" :min="1" :max="128" controls-position="right" placeholder="默认" style="width: 160px" />
          <FieldHelp tip="IPAM 块的前缀长度（IPv4 通常 26，IPv6 通常 122）。留空用 Calico 默认。" />
        </el-form-item>

        <el-form-item label="节点选择器">
          <div class="selector-list">
            <div v-for="(r, i) in form.nodeSelector" :key="i" class="selector-row">
              <el-input v-model="r.value" placeholder="如 projectcalico.org/node==worker" style="flex: 1" />
              <el-button link type="danger" @click="removeSelector(i)">删除</el-button>
            </div>
            <el-button size="small" @click="addSelector">添加选择器</el-button>
          </div>
          <FieldHelp tip="限制该池只在匹配节点上分配 IP（K8s 节点标签选择器，多条 AND）。留空 = 所有节点。" />
        </el-form-item>

        <el-form-item label="NAT 出网">
          <el-switch v-model="form.natOutgoing" />
          <FieldHelp tip="允许使用该池 IP 的 Pod 通过集群 NAT 出公网（默认开启）。" />
        </el-form-item>

        <el-form-item label="禁用">
          <el-switch v-model="form.disabled" />
          <FieldHelp tip="禁用后该池不再分配新 IP（已分配的保留）。" />
        </el-form-item>

        <el-form-item label="IPv4 分层端口分配">
          <el-switch v-model="form.ipv4hierarchicalPortAllocation" />
          <FieldHelp tip="启用 IPv4 hierarchical port allocation。" />
        </el-form-item>

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
.selector-list { display: flex; flex-direction: column; gap: 8px; width: 100%; max-width: 520px; }
.selector-row { display: flex; align-items: center; gap: 10px; }
</style>
