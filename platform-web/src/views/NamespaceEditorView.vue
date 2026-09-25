<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { clusterApi, namespaceApi } from '@/api'
import type { K8sCluster } from '@/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import LabelEditor from '@/components/workload/LabelEditor.vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'

const route = useRoute()
const router = useRouter()

// ---- 平台上下文：集群级，无租户/命名空间级联（刻意不用 stores/context.ts 的租户优先单例，本页无租户）----
/** ?name= → 编辑回填；无 name → 创建 */
const editing = ref<string | null>((route.query.name as string) || null)
const clusters = ref<K8sCluster[]>([])
const clusterId = ref((route.query.clusterId as string) || '')
/** 平台级页面：ready = 集群已选 */
const ready = computed(() => !!clusterId.value)

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value) {
    const first = clusters.value.find((c) => c.enabled === 1) ?? clusters.value[0]
    if (first) clusterId.value = first.clusterId
  }
}

// ---- 表单模型 ----
const form = reactive({
  name: '',
  description: '',
  labels: {} as Record<string, string>,
})

function resetForm(): void {
  form.name = ''
  form.description = ''
  form.labels = {}
}

// ---- 编辑回填 ----
const detailState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const saving = ref(false)
const formVisible = computed(() => !editing.value || detailState.value === 'loaded')

async function loadDetail(): Promise<void> {
  if (!editing.value || !ready.value) return
  detailState.value = 'loading'
  try {
    const ns = await namespaceApi.get(clusterId.value, editing.value)
    if (!ns) { detailState.value = 'error'; return }
    form.name = ns.name
    form.description = ns.description ?? ''
    form.labels = { ...(ns.labels ?? {}) }
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}

onMounted(() => {
  void loadClusters().then(() => {
    if (editing.value) void loadDetail()
  })
})
// 切集群：编辑态按新集群重新回填（名字仍来自 query，不变）；创建态清空表单
watch(clusterId, () => {
  if (editing.value) void loadDetail()
  else resetForm()
})

/**
 * 约束链（配额 / 限制范围）落库 —— T11 替换为真实约束链（quota/limitrange upsert/delete）。
 * 返回 false 表示命名空间已存但约束部分失败，提交链据此走「可重试」分支（spec §8 非原子）。
 */
async function saveConstraints(_cid: string, _name: string): Promise<boolean> {
  return true
}

// ---- 提交：本地校验 → 命名空间 create/update → 约束链（非原子，失败可重试）----
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/

async function submit(): Promise<void> {
  if (!ready.value || saving.value) return
  const name = form.name.trim()
  if (!name) { ElMessage.warning('请输入命名空间名'); return }
  if (!RFC1123_RE.test(name)) { ElMessage.warning('名称需符合 RFC1123：小写字母/数字/-，且以字母或数字开头结尾'); return }
  if (name.length > 63) { ElMessage.warning('名称长度不得超过 63 字符'); return }
  // T11 在此追加约束校验：quotaSectionRef.value?.isValid() / limitRangeSectionRef…

  saving.value = true
  const payload = { clusterId: clusterId.value, name, description: form.description.trim(), labels: form.labels }
  try {
    if (editing.value) {
      await namespaceApi.update(payload)
    } else {
      await namespaceApi.create(payload)
    }
  } catch {
    saving.value = false
    return   // 命名空间本身失败：拦截器已提示，留在页面
  }
  // 命名空间成功 → 约束链（T11 提供）；部分失败不静默丢弃（spec §8）
  const constraintOk = await saveConstraints(clusterId.value, name)
  saving.value = false
  if (!constraintOk) {
    ElMessage.warning('命名空间已保存，但配额/限制范围设置失败，可重试编辑')
    if (!editing.value) {
      // 关键：切到编辑态（名字锁定），使重试能 upsert 三区块
      editing.value = name
      await router.replace({ name: 'namespace-editor', query: { clusterId: clusterId.value, name } })
    }
    return
  }
  ElMessage.success(editing.value ? '已更新' : '创建成功')
  await router.push({ name: 'namespace-detail', query: { clusterId: clusterId.value, name } })
}

function goBack(): void {
  router.push('/namespaces')
}

const pageTitle = computed(() => (editing.value ? '编辑命名空间' : '创建命名空间'))
</script>

<template>
  <div class="res-editor">
    <PageHeader :title="pageTitle">
      <el-select v-model="clusterId" placeholder="选择集群" size="small" style="width: 220px">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="goBack">返回</el-button>
    </PageHeader>

    <EmptyState v-if="!ready" title="尚未选择集群" description="请先在上方选择一个已启用的集群，再创建或编辑命名空间。" />

    <EmptyState v-else-if="detailState === 'error'" title="加载失败" description="该命名空间可能已被删除，或不在所选集群下。">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </EmptyState>

    <template v-else>
      <div v-if="formVisible" class="editor-body">
        <!-- 基础信息 -->
        <el-card shadow="never" class="sec-card">
          <template #header><span class="sec-title">{{ editing ? `命名空间 · ${form.name}` : '基础信息' }}</span></template>
          <el-form label-width="200px" label-position="left">
            <el-form-item required>
              <template #label>名称 <FieldHelp tip="K8s 命名空间名，需符合 RFC1123（小写字母/数字/-，字母或数字开头结尾），最长 63 字符；创建后不可修改。" /></template>
              <el-input v-model="form.name" :disabled="!!editing" placeholder="例如 order-prod" style="width: 360px" />
            </el-form-item>
            <el-form-item>
              <template #label>描述 <FieldHelp tip='说明该命名空间的用途。存于 metadata.annotations["description"]（平台约定，同工作负载描述），仅用于展示、不影响 K8s 行为。' /></template>
              <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="这个命名空间放什么服务、归哪个团队" style="width: 520px" />
            </el-form-item>
            <el-form-item>
              <template #label>标签 <FieldHelp tip="K8s 标签（metadata.labels），供选择器与第三方工具使用。平台保留标签 app.kubernetes.io/managed-by 由系统维护、不可编辑；编辑时其余既有标签自动保留。" /></template>
              <LabelEditor v-model="form.labels" class="sub-editor" style="max-width: 520px" />
            </el-form-item>
          </el-form>
        </el-card>

        <!-- T11 挂载点：QuotaSection / LimitRangeSection -->

        <div class="form-actions">
          <el-button @click="goBack">{{ editing ? '取消' : '返回' }}</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
        </div>
      </div>

      <div v-else class="loading-tip">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
/* 编辑器骨架样式自 HpaEditorView 复制（仓库无共享编辑器样式表，各编辑器各自复制为既有惯例） */
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
.sub-editor {
  width: 100%;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 4px 0 16px;
}
.loading-tip {
  padding: 48px;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}
</style>
