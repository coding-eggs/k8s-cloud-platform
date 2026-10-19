<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { prometheusRuleApi, notificationChannelApi, notificationUserApi } from '@/api'
import type { K8sPrometheusRule, K8sPrometheusRuleGroup, K8sPrometheusRuleRule, NotificationChannelOption } from '@/types'
import { useResourceContext } from '@/stores/context'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'

const route = useRoute()
const router = useRouter()
const { state, ready, load } = useResourceContext()

/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>((route.query.name as string | null) ?? null)

const ctx3 = computed(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

// ---------- 表单模型 ----------
interface RuleRow {
  /** 仅前端：决定提交时写 alert 还是 record（CRD 要求二选一） */
  kind: 'alert' | 'record'
  name: string
  expr: string
  for_: string
  keepFiringFor: string
  labels: Record<string, string>
  annotations: Record<string, string>
}

interface GroupRow {
  name: string
  interval: string
  limit: number | null
  /**
   * 以下三项本页不给编辑入口，但必须原样带回 —— converter 的 overlay 会按
   * MODELED_GROUP_KEYS 把「DTO 里没有的键」从对象上移除，
   * 不带就等于用户改一次组名顺手抹掉了它们。
   */
  queryOffset: string
  partialResponseStrategy: string
  groupLabels: Record<string, string>
  rules: RuleRow[]
}

function newRule(): RuleRow {
  return { kind: 'alert', name: '', expr: '', for_: '', keepFiringFor: '', labels: {}, annotations: {} }
}
function newGroup(): GroupRow {
  return { name: '', interval: '', limit: null, queryOffset: '', partialResponseStrategy: '', groupLabels: {}, rules: [newRule()] }
}

const form = reactive({
  name: '',
  labels: {} as Record<string, string>,
  groups: [newGroup()] as GroupRow[],
  /**
   * 平台侧（不写进 K8s 对象的 spec，只进绑定表）：
   * 留空 = 用默认通知方式；收件人留空 = 该命名空间所属租户的管理员。
   */
  notifyChannel: null as string | null,
  notifyUserIds: [] as string[],
})

// ---------- 通知方式候选（全局 + 本租户的启用渠道） ----------
const channelOptions = ref<NotificationChannelOption[]>([])
const userOptions = ref<{ id: string; displayName: string; email: string | null }[]>([])
/** 下拉里标出「默认」，让用户知道留空会走哪条渠道 */
const defaultChannelName = computed(() => channelOptions.value.find((c) => c.isDefault)?.name ?? '')

/** 切换类型时清掉另一边专属的字段 —— 否则会发出 alert 与 record 同时存在的、注定被后端拒的请求。 */
function onKindChange(row: RuleRow): void {
  if (row.kind === 'record') {
    row.for_ = ''
    row.keepFiringFor = ''
    row.annotations = {}
  }
}

// ---------- 编辑回填 ----------
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

function fromRule(r: K8sPrometheusRuleRule): RuleRow {
  // namespace 标签由后端在保存时统一覆写，回填时不带出来（表单不该控制它）。
  // platform_notify（label）与 platform_rule（annotation）同理：都是后端代打的，
  // 留在表单里只会与绑定表打架。
  const labels = { ...(r.labels ?? {}) }
  delete labels.namespace
  delete labels.platform_notify
  const annotations = { ...(r.annotations ?? {}) }
  delete annotations.platform_rule
  return {
    kind: r.alert ? 'alert' : 'record',
    name: r.alert ?? r.record ?? '',
    expr: r.expr ?? '',
    for_: r.for_ ?? '',
    keepFiringFor: r.keepFiringFor ?? '',
    labels,
    annotations,
  }
}

function fromGroup(g: K8sPrometheusRuleGroup): GroupRow {
  const rules = (g.rules ?? []).map(fromRule)
  return {
    name: g.name ?? '',
    interval: g.interval ?? '',
    limit: g.limit ?? null,
    queryOffset: g.queryOffset ?? '',
    partialResponseStrategy: g.partialResponseStrategy ?? '',
    groupLabels: { ...(g.labels ?? {}) },
    rules: rules.length ? rules : [newRule()],
  }
}

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const d = await prometheusRuleApi.get(editing.value, ctx3.value)
    form.name = d.name
    form.labels = { ...(d.labels ?? {}) }
    const gs = (d.groups ?? []).map(fromGroup)
    form.groups = gs.length ? gs : [newGroup()]
    // 平台侧字段：后端从绑定表补回来的（CR 里没有这两个）
    form.notifyChannel = d.notifyChannel ?? null
    form.notifyUserIds = [...(d.notifyUserIds ?? [])]
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

/** 候选渠道与收件人：纯候选值，失败只让下拉为空（拦截器已提示），不影响保存。 */
async function loadNotifyOptions(): Promise<void> {
  if (!ready.value) return
  try {
    channelOptions.value = await notificationChannelApi.options(state.tenantId)
    userOptions.value = await notificationUserApi.list(state.tenantId)
  } catch {
    channelOptions.value = []
    userOptions.value = []
  }
}

onMounted(() => {
  void load()
  if (ready.value) {
    void loadNotifyOptions()
    if (editing.value) void loadDetail()
  }
})
watch(ready, (r) => {
  if (r && !channelOptions.value.length) void loadNotifyOptions()
  if (r && editing.value && detailState.value === 'idle') void loadDetail()
})

const formVisible = computed(() => !editing.value || detailState.value === 'loaded')
const pageTitle = computed(() => (editing.value ? `编辑 PrometheusRule · ${editing.value}` : '创建 PrometheusRule'))

// ---------- 提交：本地校验 → 组装 body ----------
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const saving = ref(false)

/** 与后端 PrometheusRuleService 的校验同一套口径 —— 在这里拦是为了不发注定被拒的请求。 */
function validate(): string | null {
  const name = form.name.trim()
  if (!name) return '请输入名称'
  if (!RFC1123_RE.test(name)) return '名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'
  if (!form.groups.length) return '至少需要一组规则'
  const groupNames = new Set<string>()
  for (const g of form.groups) {
    const gn = g.name.trim()
    if (!gn) return '每一组都要有组名'
    if (groupNames.has(gn)) return `组名「${gn}」重复了（同一 PrometheusRule 内组名必须唯一）`
    groupNames.add(gn)
    if (!g.rules.length) return `组「${gn}」里没有规则`
    const keys = new Set<string>()
    for (const r of g.rules) {
      const rn = r.name.trim()
      if (!rn) return `组「${gn}」里有一条规则没填名字`
      const key = `${r.kind}:${rn}`
      if (keys.has(key)) return `组「${gn}」里的${r.kind === 'alert' ? '告警' : '记录'}规则「${rn}」重复了（同组内必须唯一）`
      keys.add(key)
      if (!r.expr.trim()) return `组「${gn}」里的规则「${rn}」没有表达式（expr）`
    }
  }
  return null
}

function toRuleDto(r: RuleRow): K8sPrometheusRuleRule {
  const dto: K8sPrometheusRuleRule = { expr: r.expr.trim() }
  if (r.kind === 'alert') {
    dto.alert = r.name.trim()
    if (r.for_.trim()) dto.for_ = r.for_.trim()
    if (r.keepFiringFor.trim()) dto.keepFiringFor = r.keepFiringFor.trim()
    if (Object.keys(r.labels).length) dto.labels = r.labels
    if (Object.keys(r.annotations).length) dto.annotations = r.annotations
  } else {
    dto.record = r.name.trim()
    if (Object.keys(r.labels).length) dto.labels = r.labels
  }
  return dto
}

function toGroupDto(g: GroupRow): K8sPrometheusRuleGroup {
  const dto: K8sPrometheusRuleGroup = { name: g.name.trim(), rules: g.rules.map(toRuleDto) }
  if (g.interval.trim()) dto.interval = g.interval.trim()
  if (g.limit !== null && g.limit !== undefined) dto.limit = g.limit
  // 只读保留字段：原样带回，避免 overlay 把它们当作"用户清空了"
  if (g.queryOffset) dto.queryOffset = g.queryOffset
  if (g.partialResponseStrategy) dto.partialResponseStrategy = g.partialResponseStrategy
  if (Object.keys(g.groupLabels).length) dto.labels = g.groupLabels
  return dto
}

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  const name = form.name.trim()
  const body: K8sPrometheusRule = {
    name,
    namespace: state.namespace!,
    labels: form.labels,
    groups: form.groups.map(toGroupDto),
    // 平台侧：留空 = 走默认（后端不会写空值 label，也不会落绑定行）
    notifyChannel: form.notifyChannel || null,
    notifyUserIds: form.notifyUserIds,
  }
  saving.value = true
  try {
    if (editing.value) {
      await prometheusRuleApi.update(editing.value, ctx3.value, body)
      ElMessage.success('已保存')
    } else {
      await prometheusRuleApi.create(ctx3.value, body)
      ElMessage.success('已创建')
    }
    goBack()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function goBack(): void {
  router.push('/resources/prometheusrules')
}
</script>

<template>
  <div class="res-editor">
    <PageHeader :title="pageTitle">
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState
      v-if="!ready"
      title="尚未选择上下文"
      description="请在顶栏依次选择租户、集群、命名空间后，再创建或编辑 PrometheusRule。"
    />

    <template v-else>
      <div v-if="formVisible" class="editor-body">
        <!-- 基础信息 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">基础信息</span></template>
          <el-form label-width="140px" label-position="left">
            <el-form-item label="名称" required>
              <el-input v-model="form.name" :disabled="!!editing" placeholder="如 app-rules（RFC1123，小写字母/数字/-）" style="max-width: 360px" />
            </el-form-item>
            <el-form-item label="标签 labels">
              <LabelEditor v-model="form.labels" class="sub-editor" style="max-width: 1080px" />
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 通知：平台侧配置，不写进 CR 的 spec（后端存绑定表 + 代打 label） -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">通知</span></template>
          <el-form label-width="140px" label-position="left">
            <el-form-item label="通知方式">
              <el-select
                v-model="form.notifyChannel"
                clearable
                placeholder="留空 = 使用默认通知方式"
                style="width: 320px"
              >
                <el-option v-for="c in channelOptions" :key="c.code" :label="c.name" :value="c.code" />
              </el-select>
              <div class="form-tip">
                默认通知方式：{{ defaultChannelName || '未配置' }}。留空时走默认。
              </div>
            </el-form-item>
            <el-form-item label="通知给谁">
              <el-select
                v-model="form.notifyUserIds"
                multiple
                filterable
                placeholder="留空 = 该命名空间所属租户的管理员"
                style="width: 100%; max-width: 720px"
              >
                <el-option
                  v-for="u in userOptions"
                  :key="u.id"
                  :label="`${u.displayName}（${u.email || '无邮箱'}）`"
                  :value="u.id"
                />
              </el-select>
              <div class="form-tip">只能选本租户用户；没有邮箱的用户选了也发不出去。</div>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 规则组 -->
        <el-card v-for="(g, gi) in form.groups" :key="gi" shadow="never" class="sec-card">
          <template #header>
            <div class="group-head">
              <span class="sec-title">组 {{ gi + 1 }}</span>
              <el-button link type="danger" @click="form.groups.splice(gi, 1)">删除组</el-button>
            </div>
          </template>

          <el-form label-width="140px" label-position="left">
            <div class="row-3">
              <el-form-item label="组名 name" required>
                <el-input v-model="g.name" placeholder="如 app" />
              </el-form-item>
              <el-form-item label="求值间隔 interval">
                <el-input v-model="g.interval" placeholder="如 30s（留空 = Prometheus 默认）" />
              </el-form-item>
              <el-form-item label="limit">
                <el-input-number v-model="g.limit" :min="1" controls-position="right" placeholder="可空" class="num-input" />
              </el-form-item>
            </div>

            <div class="section-title">规则</div>

            <el-card v-for="(r, ri) in g.rules" :key="ri" shadow="never" class="rule-card">
              <template #header>
                <div class="group-head">
                  <span class="sec-title">规则 {{ ri + 1 }}</span>
                  <el-button link type="danger" @click="g.rules.splice(ri, 1)">删除规则</el-button>
                </div>
              </template>

              <el-form label-width="140px" label-position="left">
                <div class="row-2">
                  <el-form-item label="类型">
                    <el-select v-model="r.kind" style="width: 180px" @change="onKindChange(r)">
                      <el-option label="告警 alert" value="alert" />
                      <el-option label="记录 record" value="record" />
                    </el-select>
                  </el-form-item>
                  <el-form-item :label="r.kind === 'alert' ? '告警名 alert' : '记录名 record'" required>
                    <el-input v-model="r.name" :placeholder="r.kind === 'alert' ? '如 HighCPU' : '如 job:http_errors:rate5m'" />
                  </el-form-item>
                </div>

                <el-form-item label="表达式 expr" required>
                  <el-input v-model="r.expr" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" placeholder="PromQL，如 rate(http_requests_total[5m]) > 1" />
                </el-form-item>

                <div v-if="r.kind === 'alert'" class="row-2">
                  <el-form-item label="持续 for">
                    <el-input v-model="r.for_" placeholder="如 5m（留空 = 立即触发）" />
                  </el-form-item>
                  <el-form-item label="条件消失后继续 keep_firing_for">
                    <el-input v-model="r.keepFiringFor" placeholder="如 5m（可空）" />
                  </el-form-item>
                </div>

                <el-form-item label="标签 labels">
                  <div class="sub-editor-wrap">
                    <div v-if="r.kind === 'alert'" class="form-tip">平台会在保存时把 namespace 标签写成这条规则所在的命名空间 —— 告警的归属靠它，所以它不由表单控制。</div>
                    <LabelEditor v-model="r.labels" class="sub-editor" :exclude-keys="['namespace']" style="max-width: 1080px" />
                  </div>
                </el-form-item>

                <el-form-item v-if="r.kind === 'alert'" label="注解 annotations">
                  <LabelEditor v-model="r.annotations" class="sub-editor" style="max-width: 1080px" />
                </el-form-item>
              </el-form>
            </el-card>

            <el-button plain size="small" @click="g.rules.push(newRule())">+ 添加规则</el-button>
          </el-form>
        </el-card>

        <div>
          <el-button plain @click="form.groups.push(newGroup())">+ 添加组</el-button>
        </div>

        <div class="form-actions">
          <el-button @click="goBack">取消 / 返回</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
      </div>

      <EmptyState
        v-else-if="detailState === 'error'"
        title="加载 PrometheusRule 失败"
        description="请返回列表重试；若该资源已被删除，刷新列表即可。"
      >
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </EmptyState>

      <div v-else class="loading-tip">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
.editor-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sec-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.sec-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.rule-card {
  margin-bottom: 10px;
  border-color: var(--border-1, #e5e7eb);
}
.row-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.row-3 {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr;
  gap: 0 16px;
}
.num-input {
  width: 180px;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  margin: 14px 0 8px;
}
.form-tip {
  width: 100%;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 6px;
}
.sub-editor-wrap {
  width: 100%;
}
.sub-editor {
  width: 100%;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 16px 0 32px;
}
.loading-tip {
  padding: 32px;
  color: var(--text-3);
}
</style>
