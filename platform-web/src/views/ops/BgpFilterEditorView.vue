<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import BgpDocPanel from './BgpDocPanel.vue'
import BgpFilterRuleList from './BgpFilterRuleList.vue'
import { dtoToRule, ruleToDto, type RuleRow } from './bgpFilterForm'
import { clusterApi, calicoApi } from '@/api'
import type { K8sCluster, BgpFilter } from '@/types'

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

// ---- 表单模型：name + 四个方向的规则列表（空值 = 不限，交还 Calico 默认）----
const form = reactive({
  name: '',
  exportV4: [] as RuleRow[],
  importV4: [] as RuleRow[],
  exportV6: [] as RuleRow[],
  importV6: [] as RuleRow[],
})

const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadDetail(): Promise<void> {
  if (!editing.value || !clusterId.value) return
  detailState.value = 'loading'
  try {
    const d = await calicoApi.bgpFilter.get(editing.value, clusterId.value)
    form.name = d.name
    form.exportV4 = (d.exportV4 ?? []).map(dtoToRule)
    form.importV4 = (d.importV4 ?? []).map(dtoToRule)
    form.exportV6 = (d.exportV6 ?? []).map(dtoToRule)
    form.importV6 = (d.importV6 ?? []).map(dtoToRule)
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

/** 全空规则不下发（避免产生一条「匹配一切」的空规则） */
function hasContent(r: RuleRow): boolean {
  return !!(
    r.cidr.trim() || r.prefixLengthMin != null || r.prefixLengthMax != null ||
    r.source || r.iface.trim() || r.matchOperator || r.peerType ||
    r.communityValues.length || r.asPathPrefix.length || r.priority != null ||
    r.opsRows.some((o) => o.value.trim())
  )
}

// ---- 提交（update 为整对象替换：清空某方向列表 → 该方向恢复不过滤）----
const saving = ref(false)
async function submit(): Promise<void> {
  if (saving.value || !clusterId.value) return
  const finalName = editing.value ?? form.name.trim()
  if (!finalName) { ElMessage.warning('请输入名称'); return }
  if (!RFC1123_RE.test(finalName)) { ElMessage.warning('名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'); return }

  const toRules = (rows: RuleRow[]) => rows.filter(hasContent).map(ruleToDto)
  const body: BgpFilter = {
    name: finalName,
    clusterId: clusterId.value,
    exportV4: toRules(form.exportV4),
    importV4: toRules(form.importV4),
    exportV6: toRules(form.exportV6),
    importV6: toRules(form.importV6),
  }
  saving.value = true
  try {
    if (editing.value) {
      await calicoApi.bgpFilter.update(editing.value, clusterId.value, body)
      ElMessage.success('保存成功')
    } else {
      await calicoApi.bgpFilter.create(clusterId.value, body)
      ElMessage.success('创建成功')
    }
    router.push('/ops/bgpfilters')
  } catch { /* 拦截器提示 */ } finally {
    saving.value = false
  }
}

function goBack(): void { router.push('/ops/bgpfilters') }

const pageTitle = computed(() => (editing.value ? '编辑 BGP 过滤器' : '创建 BGP 过滤器'))
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

onMounted(async () => {
  await loadClusters()
  if (editing.value && clusterId.value) await loadDetail()
})
</script>

<template>
  <div class="editor-page">
    <PageHeader :title="pageTitle" description="Calico BGPFilter（集群级 CRD）">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 200px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="detailState === 'error'" title="加载失败" description="该 BGP 过滤器可能已被删除，或所选集群下不存在。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <div v-else class="panel editor-panel">
      <template v-if="formVisible">
        <BgpDocPanel
          what="命名的路由策略对象：定义 Calico 与 BGP 对端交换路由时，哪些前缀放行（Accept）、拒绝（Reject），以及命中后执行的附加操作（追加 community、AS-Path Prepend、调整 priority）。由 exportV4 / importV4 / exportV6 / importV6 四个独立规则列表组成。"
          how="先在这里建好过滤器，再到 BGPPeer 的「应用的 BGPFilter」里勾选它的名字才生效——过滤器本身不产生任何效果。export = 宣告给对端前过滤；import = 从对端学到后过滤。同一列表内规则自上而下评估，第一条命中的规则执行其动作后停止。"
          :warnings="[
            '未被任何 BGPPeer 引用的过滤器完全不生效——创建后记得挂到对等体上。',
            'Reject 会让该方向的路由不再宣告/学习；CIDR 写错或 Reject 用错可能造成路由黑洞，保存前逐条核对。',
            '规则按顺序评估、首条命中即止：更具体的规则放上面，兜底规则放最后。',
            '修改/删除已被 BGPPeer 引用的过滤器会立即影响对应 peering 的路由交换，先确认影响面再操作。',
          ]"
        />

        <el-form label-width="200px" label-position="left" class="editor-form">
          <el-form-item label="名称" required>
            <el-input v-model="form.name" :disabled="!!editing" placeholder="如 deny-private-v4（RFC-1123）" style="max-width: 420px" />
            <FieldHelp tip="BGPFilter 名（集群级唯一，RFC-1123）。创建时可填，编辑时不可改；BGPPeer 按此名引用。" />
          </el-form-item>

          <el-divider content-position="left">IPv4 · 宣告给对端（exportV4）</el-divider>
          <el-form-item label="导出规则 v4">
            <BgpFilterRuleList :rules="form.exportV4" />
            <FieldHelp tip="节点向对端宣告 IPv4 路由前逐条评估。空列表 = 该方向不做过滤（全部放行）。" />
          </el-form-item>

          <el-divider content-position="left">IPv4 · 从对端学习（importV4）</el-divider>
          <el-form-item label="导入规则 v4">
            <BgpFilterRuleList :rules="form.importV4" />
            <FieldHelp tip="从对端学到 IPv4 路由后逐条评估，Reject 的路由不进入本地转发表。空列表 = 不过滤。" />
          </el-form-item>

          <el-divider content-position="left">IPv6 · 宣告给对端（exportV6）</el-divider>
          <el-form-item label="导出规则 v6">
            <BgpFilterRuleList :rules="form.exportV6" />
            <FieldHelp tip="节点向对端宣告 IPv6 路由前逐条评估。空列表 = 该方向不做过滤。" />
          </el-form-item>

          <el-divider content-position="left">IPv6 · 从对端学习（importV6）</el-divider>
          <el-form-item label="导入规则 v6">
            <BgpFilterRuleList :rules="form.importV6" />
            <FieldHelp tip="从对端学到 IPv6 路由后逐条评估。空列表 = 不过滤。" />
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
            <el-button @click="goBack">取消</el-button>
          </el-form-item>
        </el-form>
      </template>
    </div>
  </div>
</template>

<style scoped>
.editor-page { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; }
.editor-panel { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 20px 24px; }
.editor-form { max-width: 980px; }
</style>
