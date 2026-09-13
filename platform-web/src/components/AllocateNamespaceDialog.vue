<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { clusterApi, namespaceApi, templateApi, tenantApi } from '@/api'
import type { K8sCluster, PlatformTenant, RbacTemplate } from '@/types'

const props = defineProps<{
  /** 预设租户（从租户管理页打开时传入；不传则对话框内可选） */
  tenantId?: string
}>()
const emit = defineEmits<{ (e: 'success'): void }>()
const visible = defineModel<boolean>({ required: true })

const tenants = ref<PlatformTenant[]>([])
const clusters = ref<K8sCluster[]>([])
const templates = ref<RbacTemplate[]>([])
const saving = ref(false)
const form = reactive({ tenantId: '', clusterId: '', namespace: '', roleTemplateId: '' })

const enabledTenants = computed(() => tenants.value.filter((t) => t.status === 1))
const enabledClusters = computed(() => clusters.value.filter((c) => c.enabled === 1))
const defaultTemplate = computed(() => templates.value.find((t) => t.builtIn === 1)?.id ?? '')

// 命名空间下拉：集群内已有命名空间（选中集群后拉取）；新名字经 allow-create 手填，提交时仍走命名规范校验
const nsOptions = ref<string[]>([])
const nsLoading = ref(false)
// const SYSTEM_NAMESPACES = [
//     'kube-system', 'kube-public', 'kube-node-lease'
// ]
const SYSTEM_NAMESPACES = [
    ""
]

async function loadNsOptions(): Promise<void> {
  if (!form.clusterId) {
    nsOptions.value = []
    return
  }
  nsLoading.value = true
  try {
    nsOptions.value = (await namespaceApi.list(form.clusterId)).map((n) => n.name)
  } catch {
    nsOptions.value = [] // 拉取失败退化为纯手填（拦截器已提示）
  } finally {
    nsLoading.value = false
  }
}

watch(() => form.clusterId, loadNsOptions)

// 每次打开：重置表单 + 拉取元数据
watch(visible, async (v) => {
  if (!v) return
  form.tenantId = props.tenantId ?? ''
  form.clusterId = ''
  form.namespace = ''
  form.roleTemplateId = ''
  nsOptions.value = []
  ;[tenants.value, clusters.value, templates.value] = await Promise.all([
    tenantApi.list(),
    clusterApi.list(),
    templateApi.list(),
  ])
  form.roleTemplateId = defaultTemplate.value
})

async function submit(): Promise<void> {
  if (!form.tenantId || !form.clusterId) {
    ElMessage.warning('请选择租户和集群')
    return
  }
  const ns = form.namespace.trim()
  if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(ns) || ns.length > 63) {
    ElMessage.warning('命名空间需符合 K8s 命名规范：小写字母/数字/-，首尾为字母或数字，最长 63 字符')
    return
  }
  saving.value = true
  try {
    await tenantApi.namespaceAllocate({
      tenantId: form.tenantId,
      clusterId: form.clusterId,
      namespace: ns,
      roleTemplateId: form.roleTemplateId || undefined,
    })
    ElMessage.success('分配成功：命名空间 / ClusterRole / RoleBinding 已就绪')
    visible.value = false
    emit('success')
  } catch {
    /* 拦截器提示 */
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="分配命名空间" width="560px">
    <el-form label-width="100px">
      <el-form-item label="租户" required>
        <el-select
          v-model="form.tenantId"
          :disabled="!!tenantId"
          placeholder="选择启用状态的租户"
          style="width: 100%"
        >
          <el-option v-for="t in enabledTenants" :key="t.id" :label="`${t.name} (${t.serviceAccount})`" :value="t.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="集群" required>
        <el-select v-model="form.clusterId" placeholder="选择启用状态的集群" style="width: 100%">
          <el-option v-for="c in enabledClusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
        </el-select>
      </el-form-item>
      <el-form-item label="命名空间" required>
        <el-select
          v-model="form.namespace"
          filterable
          allow-create
          default-first-option
          :loading="nsLoading"
          placeholder="选择已有命名空间，或输入新名字"
          style="width: 100%"
        >
          <el-option
            v-for="n in nsOptions"
            :key="n"
            :label="n"
            :value="n"
            :disabled="SYSTEM_NAMESPACES.includes(n)"
          />
        </el-select>
        <div class="form-tip">已有命名空间可直接选择；新名字不存在时自动创建（打 managed-by 标签）；内置系统命名空间不可分配</div>
      </el-form-item>
      <el-form-item label="RBAC 模板">
        <el-select v-model="form.roleTemplateId" style="width: 100%">
          <el-option v-for="t in templates" :key="t.id" :label="`${t.name}${t.builtIn === 1 ? '（内置）' : ''}`" :value="t.id">
            <span>{{ t.name }}</span>
            <span v-if="t.description" class="tpl-desc">{{ t.description }}</span>
          </el-option>
        </el-select>
        <div class="form-tip">缺省使用内置模板；决定该命名空间内租户 SA 的权限</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确定分配</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.4;
}
.tpl-desc {
  margin-left: 8px;
  color: var(--text-3);
  font-size: 12px;
}
</style>
