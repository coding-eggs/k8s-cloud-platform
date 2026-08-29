<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { templateApi } from '@/api'
import type { PolicyRule, RbacTemplate } from '@/types'
import { fmtDate } from '@/utils/format'

const loading = ref(false)
const list = ref<RbacTemplate[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await templateApi.list()
  } finally {
    loading.value = false
  }
}

// ---- 候选项（可自定义输入） ----
const API_GROUP_OPTIONS = ['', 'apps', 'batch', 'networking.k8s.io', 'rbac.authorization.k8s.io', 'autoscaling', 'policy', 'extensions']
const RESOURCE_OPTIONS = [
  'pods', 'pods/log', 'pods/exec', 'services', 'serviceaccounts', 'configmaps', 'secrets',
  'persistentvolumeclaims', 'endpoints', 'deployments', 'replicasets', 'statefulsets', 'daemonsets',
  'jobs', 'cronjobs', 'ingresses', 'namespaces',
]
const VERB_OPTIONS = ['get', 'list', 'watch', 'create', 'update', 'patch', 'delete', 'deletecollection', '*']

// ---- 创建 / 编辑对话框 ----
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const form = reactive({
  id: '',
  name: '',
  description: '',
  rules: [] as PolicyRule[],
})

function newRule(): PolicyRule {
  return { apiGroups: [''], resources: [], verbs: [] }
}

function openCreate(): void {
  isEdit.value = false
  form.id = ''
  form.name = ''
  form.description = ''
  form.rules = [newRule()]
  dialogVisible.value = true
}

function openEdit(row: RbacTemplate): void {
  if (row.builtIn === 1) return
  isEdit.value = true
  form.id = row.id
  form.name = row.name
  form.description = row.description ?? ''
  // 深拷贝，避免改动列表数据
  form.rules = (row.rules ?? []).map((r) => ({
    apiGroups: [...(r.apiGroups ?? [])],
    resources: [...(r.resources ?? [])],
    verbs: [...(r.verbs ?? [])],
  }))
  if (form.rules.length === 0) form.rules = [newRule()]
  dialogVisible.value = true
}

function addRule(): void {
  form.rules.push(newRule())
}

function removeRule(index: number): void {
  form.rules.splice(index, 1)
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请输入模板名')
    return
  }
  if (form.rules.length === 0) {
    ElMessage.warning('至少需要一条权限规则')
    return
  }
  for (let i = 0; i < form.rules.length; i++) {
    const r = form.rules[i]!
    if (!r.apiGroups?.length) {
      ElMessage.warning(`第 ${i + 1} 条规则：apiGroups 不能为空（核心组选“(核心)”）`)
      return
    }
    if (!r.verbs?.length) {
      ElMessage.warning(`第 ${i + 1} 条规则：verbs 不能为空`)
      return
    }
    if (!r.resources?.length) {
      ElMessage.warning(`第 ${i + 1} 条规则：resources 不能为空`)
      return
    }
  }

  // 组装请求体：空数组字段省略
  const rules = form.rules.map((r) => {
    const rule: PolicyRule = { apiGroups: r.apiGroups, verbs: r.verbs }
    if (r.resources?.length) rule.resources = r.resources
    return rule
  })

  saving.value = true
  try {
    if (isEdit.value) {
      await templateApi.update({ id: form.id, description: form.description || undefined, rules })
      ElMessage.success('已更新，各集群 ClusterRole 同步中')
    } else {
      await templateApi.create({ name: form.name.trim(), description: form.description || undefined, rules })
      ElMessage.success('创建成功，ClusterRole（tn-tpl-<name>）已同步到各启用集群')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}

// ---- 删除 ----
async function onDelete(row: RbacTemplate): Promise<void> {
  if (row.builtIn === 1) return
  try {
    await ElMessageBox.confirm(
      `确认删除模板「${row.name}」？被命名空间分配引用时不可删除。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await templateApi.delete(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器提示 */
  }
}

function ruleSummary(t: RbacTemplate): string {
  const resources = (t.rules ?? []).flatMap((r) => r.resources ?? [])
  return `${t.rules?.length ?? 0} 条规则 / ${resources.length} 种资源`
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">创建模板</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column label="模板名" min-width="200">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <el-tag size="small" type="info" class="k8s-tag">tn-tpl-{{ row.name }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
      <el-table-column label="内置" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.builtIn === 1" size="small" type="warning">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="规则概览" width="160">
        <template #default="{ row }">{{ ruleSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ fmtDate(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.builtIn === 1" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :disabled="row.builtIn === 1" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑模板' : '创建模板'" width="760px" top="6vh">
      <el-form label-width="90px">
        <el-form-item label="模板名" required>
          <el-input v-model="form.name" :disabled="isEdit" placeholder="小写字母/数字/-，例如 readonly" />
          <div class="form-tip">K8s ClusterRole 名 = tn-tpl- + 该值；创建后不可修改</div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" placeholder="模板用途说明" />
        </el-form-item>
        <el-form-item label="权限规则">
          <div class="rules-editor">
            <div v-for="(rule, idx) in form.rules" :key="idx" class="rule-block">
              <div class="rule-head">
                <span>规则 {{ idx + 1 }}</span>
                <el-button link type="danger" :disabled="form.rules.length <= 1" @click="removeRule(idx)">删除</el-button>
              </div>
              <el-form-item label="apiGroups">
                <el-select v-model="rule.apiGroups" multiple filterable allow-create default-first-option style="width: 100%" placeholder="选择或输入 API 组">
                  <el-option v-for="g in API_GROUP_OPTIONS" :key="g || 'core'" :label="g === '' ? '(核心) core' : g" :value="g" />
                </el-select>
              </el-form-item>
              <el-form-item label="resources">
                <el-select v-model="rule.resources" multiple filterable allow-create default-first-option style="width: 100%" placeholder="选择或输入资源类型">
                  <el-option v-for="r in RESOURCE_OPTIONS" :key="r" :label="r" :value="r" />
                </el-select>
              </el-form-item>
              <el-form-item label="verbs">
                <el-select v-model="rule.verbs" multiple filterable allow-create default-first-option style="width: 100%" placeholder="选择或输入操作">
                  <el-option v-for="v in VERB_OPTIONS" :key="v" :label="v" :value="v" />
                </el-select>
              </el-form-item>
            </div>
            <el-button class="add-rule-btn" type="primary" plain @click="addRule">+ 添加规则</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}
.k8s-tag {
  margin-left: 8px;
}
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.4;
}
.rules-editor {
  width: 100%;
}
.rule-block {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 12px 0;
  margin-bottom: 12px;
}
.rule-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  margin-bottom: 4px;
}
.add-rule-btn {
  width: 100%;
}
</style>
