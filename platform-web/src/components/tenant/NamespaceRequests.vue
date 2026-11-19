<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import http from '@/api/http'
import { clusterApi, tenantApi } from '@/api'
import { usePermission } from '@/stores/permission'
import { useResourceContext } from '@/stores/context'
import type { NamespaceAllocation, K8sCluster, K8sNamespaceResourceStat } from '@/types'

interface RequestRow {
  id: string; namespace: string; purpose: string; status: string; kind: string; applicant: string
  quotaKey?: string; targetValue?: number; baseline?: number; approvedValue?: number; reason?: string
  allocationId?: string; clusterId?: string; expiresAt?: number
}
interface Impact { uid: string; pods: number; volumes: string[]; workloads: string[] }
const props = defineProps<{ tenantId: string; admin: boolean }>()
const perm = usePermission()
const ctx = useResourceContext()
const router = useRouter()
const rows = ref<RequestRow[]>([])
const allocations = ref<NamespaceAllocation[]>([])
const clusters = ref<K8sCluster[]>([])
const stat = ref<K8sNamespaceResourceStat>()
const kindNames: Record<string,string> = { OPEN:'开通空间', EXPAND:'配额扩容', RENEW:'续期' }
const states: Record<string,string> = { ACTIVE:'使用中', EXPIRED:'待回收', STOPPING:'正在归零', STOPPED:'Pod 已归零', DELETING:'删除中', RESTORING:'恢复中' }
const quotaFields = {
  requestsCpu: { label:'请求 CPU（核）', hard:'quotaCpuHard', used:'quotaCpuUsed', unit:1 },
  requestsMemory: { label:'请求内存（GiB）', hard:'quotaMemHard', used:'quotaMemUsed', unit:2 ** 30 },
  limitsCpu: { label:'限制 CPU（核）', hard:'quotaLimitCpuHard', used:'quotaLimitCpuUsed', unit:1 },
  limitsMemory: { label:'限制内存（GiB）', hard:'quotaLimitMemHard', used:'quotaLimitMemUsed', unit:2 ** 30 },
} as const
const form = reactive({ id:'', kind:'OPEN', namespace:'', allocationId:'', purpose:'', expiresAt:null as Date|null,
  quotaKey:'requestsCpu' as keyof typeof quotaFields, target:undefined as number|undefined })
const field = computed(() => quotaFields[form.quotaKey])
const hard = computed(() => stat.value?.[field.value.hard])
const used = computed(() => stat.value?.[field.value.used])
const editing = ref(false)
const active = ref<RequestRow>()
const reviewing = ref(false)
const decision = reactive({ clusterId:'', reason:'', approvedValue:undefined as number|undefined })
const pending = ref(false)
const impact = ref<Impact>()
const recovery = ref<NamespaceAllocation>()
const deleting = ref(false)
const confirming = ref(false)
const confirmation = ref('')
const date = (ms?: number) => ms ? new Date(ms).toLocaleString() : '长期'
async function refresh() {
  const data = await Promise.all([
    props.admin ? http.post<never,RequestRow[]>('/namespace-requests/list',null,{params:{tenantId:props.tenantId}})
      : http.post<never,RequestRow[]>('/namespace-requests/mine'),
    tenantApi.namespaceList({tenantId:props.tenantId}),
  ])
  rows.value = data[0]
  allocations.value = data[1]
}
let refreshTimer: number | undefined
onMounted(() => {
  void refresh()
  refreshTimer = window.setInterval(() => {
    if (allocations.value.some(row => ['STOPPING','DELETING','RESTORING'].includes(row.lifecycle ?? ''))) void refresh()
  }, 10000)
})
onUnmounted(() => window.clearInterval(refreshTimer))
async function edit(kind: string, row?: NamespaceAllocation) {
  const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2,'0')).join('')
  Object.assign(form, { id, kind, namespace:row?.namespace ?? '', allocationId:row?.id ?? '',
    purpose:'', expiresAt:null, target:undefined, quotaKey:'requestsCpu' })
  stat.value = undefined
  editing.value = true
  if (kind === 'EXPAND' && row) {
    const id = form.id
    const list = await tenantApi.overviewBreakdown({tenantId:props.tenantId})
    if (form.id === id) stat.value = list.find(e => e.clusterId === row.clusterId && e.snapshot.stat.namespace === row.namespace)?.snapshot.stat
  }
}
async function submit() {
  if (pending.value) return
  pending.value = true
  try {
    await http.post('/namespace-requests/submit',{ ...form, expiresAt:form.expiresAt?.getTime(),
      quotaKey:form.target == null ? null : form.quotaKey, targetValue:form.target == null ? null : form.target * field.value.unit,
      baseline:form.kind === 'EXPAND' ? hard.value : null })
    editing.value = false
    await refresh()
  } finally { pending.value = false }
}
async function review(row: RequestRow) {
  active.value = row
  Object.assign(decision,{clusterId:row.clusterId ?? '',reason:'',approvedValue:row.approvedValue ?? row.targetValue})
  if (row.kind === 'OPEN' && !clusters.value.length) clusters.value = await clusterApi.list()
  reviewing.value = true
}
async function saveReview(approve: boolean) {
  if (pending.value || !active.value) return
  pending.value = true
  try {
    await http.post('/namespace-requests/review',{id:active.value.id, approve,...decision})
    reviewing.value = false
    await refresh()
  } finally { pending.value = false }
}
async function preview(row: NamespaceAllocation, remove: boolean) {
  impact.value = await http.post<never,Impact>('/namespace-requests/preview',{allocationId:row.id})
  recovery.value = row
  confirmation.value = ''
  deleting.value = remove
  confirming.value = true
}
async function reclaim() {
  if (!recovery.value || !impact.value || pending.value) return
  pending.value = true
  try {
    await http.post('/namespace-requests/' + (deleting.value ? 'delete':'stop'),
      {allocationId:recovery.value.id, uid:impact.value.uid, confirmation:confirmation.value})
    confirming.value = false
    ElMessage.success('已提交回收任务')
    await refresh()
  } finally { pending.value = false }
}
async function enter(row: NamespaceAllocation) {
  ctx.state.loaded = false
  await ctx.load()
  if (ctx.locate(row.clusterId,row.namespace)) await router.push('/resources/workloads')
  else await ElMessageBox.alert('当前身份无法进入此空间')
}
</script>
<template>
  <div class="actions">
    <el-button v-if="!admin" type="primary" @click="edit('OPEN')">申请命名空间</el-button>
    <el-button @click="refresh">刷新</el-button>
  </div>
  <el-table :data="allocations" empty-text="尚未分配命名空间">
    <el-table-column prop="namespace" label="命名空间" /><el-table-column prop="clusterId" label="集群" />
    <el-table-column label="有效期"><template #default="{row}">{{ date(row.expiresAt) }}</template></el-table-column>
    <el-table-column label="状态"><template #default="{row}">{{ states[row.lifecycle] || row.lifecycle }} {{ row.lifecycleError }}</template></el-table-column>
    <el-table-column label="操作" min-width="190"><template #default="{row}">
      <template v-if="!admin">
        <el-button v-if="row.lifecycle === 'ACTIVE'" link @click="enter(row)">进入</el-button>
        <el-button v-if="row.lifecycle === 'ACTIVE'" link @click="edit('EXPAND',row)">申请配额扩容</el-button>
        <el-button v-if="row.expiresAt && ['ACTIVE','EXPIRED','STOPPED'].includes(row.lifecycle)" link @click="edit('RENEW',row)">申请续期</el-button>
      </template>
      <template v-else-if="['EXPIRED','STOPPED'].includes(row.lifecycle)">
        <el-button v-if="row.lifecycle === 'EXPIRED' && perm.has('platform:namespace:reclaim')" link @click="preview(row,false)">回收资源</el-button>
        <el-button v-if="perm.has('platform:namespace:destroy')" link type="danger" @click="preview(row,true)">删除整个空间</el-button>
      </template>
    </template></el-table-column>
  </el-table>
  <h4>{{ admin ? '租户申请' : '我的申请' }}</h4>
  <el-table :data="rows" empty-text="暂无申请">
    <el-table-column label="类型"><template #default="{row}">{{ kindNames[row.kind] }}</template></el-table-column>
    <el-table-column prop="namespace" label="空间" /><el-table-column prop="applicant" label="申请人" />
    <el-table-column prop="purpose" label="用途"/><el-table-column label="结果"><template #default="{row}">
      {{ ({ PENDING:'待审批', PROVISIONING:'开通中', APPROVED:'已批准', REJECTED:'已驳回' } as Record<string,string>)[row.status] }}
      {{ row.reason }} <span v-if="row.approvedValue"> · {{ row.quotaKey }} {{ row.approvedValue }}</span>
    </template></el-table-column>
    <el-table-column v-if="admin" label="操作"><template #default="{row}"><el-button v-if="['PENDING','PROVISIONING'].includes(row.status)" link @click="review(row)">{{ row.status === 'PENDING' ? '审批' : '继续开通' }}</el-button></template></el-table-column>
  </el-table>
  <el-dialog v-model="editing" :title="kindNames[form.kind]" width="510px" append-to-body>
    <el-form label-position="top">
      <el-form-item label="命名空间名称"><el-input v-model="form.namespace" :disabled="form.kind !== 'OPEN'" /></el-form-item>
      <el-form-item label="申请原因"><el-input v-model="form.purpose" type="textarea" maxlength="1000" /></el-form-item>
      <el-form-item v-if="form.kind !== 'EXPAND'" label="使用截止时间"><el-date-picker v-model="form.expiresAt" type="datetime" :placeholder="form.kind === 'OPEN' ? '留空申请长期使用' : '选择新的截止时间'"/></el-form-item>
      <template v-if="form.kind !== 'RENEW'">
        <el-form-item label="配额项"><el-select v-model="form.quotaKey"><el-option v-for="(f,k) in quotaFields" :key="k" :label="f.label" :value="k"/></el-select></el-form-item>
        <p v-if="form.kind === 'EXPAND'">当前上限 {{ hard == null ? '未设置或无数据' : hard / field.unit }} · 已用 {{ used == null ? '—' : used / field.unit }}</p>
        <el-form-item :label="'目标总量 · ' + field.label"><el-input-number v-model="form.target" :min="0"/></el-form-item>
      </template>
    </el-form>
    <template #footer><el-button type="primary" :loading="pending" :disabled="!form.namespace || !form.purpose.trim() || (form.kind==='RENEW' && !form.expiresAt) || (form.kind==='EXPAND' && (hard == null || !form.target || form.target * field.unit <= hard))" @click="submit">提交申请</el-button></template>
  </el-dialog>
  <el-dialog v-model="reviewing" title="审批空间申请" width="510px" append-to-body>
    <template v-if="active"><p>{{ kindNames[active.kind] }} · {{ active.namespace }} · {{ active.purpose }}</p>
      <p v-if="active.kind !== 'EXPAND'">申请截止时间 {{ date(active.expiresAt) }}</p>
      <el-select v-if="active.kind === 'OPEN'" v-model="decision.clusterId" :disabled="active.status === 'PROVISIONING'" placeholder="选择目标集群"><el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId"/></el-select>
      <p v-if="active.quotaKey">{{ active.quotaKey }} · 原上限 {{ active.baseline ?? '未设限' }} · 申请总量 {{ active.targetValue }}</p>
      <el-input-number v-if="active.quotaKey" v-model="decision.approvedValue" :disabled="active.status === 'PROVISIONING'" :min="0" :max="active.targetValue"/>
      <el-input v-model="decision.reason" placeholder="审批意见，驳回时必填" maxlength="1000"/>
    </template>
    <template #footer><el-button :loading="pending" :disabled="active?.status === 'PROVISIONING' || !decision.reason.trim()" @click="saveReview(false)">驳回</el-button><el-button type="primary" :loading="pending" @click="saveReview(true)">批准</el-button></template>
  </el-dialog>
  <el-dialog v-model="confirming" :title="deleting ? '删除整个命名空间' : '回收资源 · Pod 归零'" width="540px" append-to-body>
    <template v-if="impact && recovery"><p>{{ recovery.clusterId }} / <b>{{ recovery.namespace }}</b> · {{ impact.pods }} 个 Pod</p>
      <p>控制器 {{ impact.workloads.join('、') || '无' }}</p><p>数据卷与回收策略 {{ impact.volumes.join('、') || '无' }}</p>
      <p>{{ deleting ? '空间内对象将删除，卷数据可能随回收策略删除。请输入空间名称确认。' : '业务将停止，Pod 临时数据将释放。命名空间、工作负载定义与业务 PVC 保留。续期后独立 Pod 和已结束任务需手动重建。' }}</p>
      <el-input v-if="deleting" v-model="confirmation" :placeholder="recovery.namespace"/>
    </template>
    <template #footer><el-button type="danger" :disabled="deleting && confirmation !== recovery?.namespace" :loading="pending" @click="reclaim">{{ deleting ? '确认删除空间' : '确认归零' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>.actions { display:flex; gap:8px; margin-bottom:12px }</style>
