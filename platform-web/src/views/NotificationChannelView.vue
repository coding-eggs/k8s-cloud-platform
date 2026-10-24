<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notificationChannelApi, tenantApi } from '@/api'
import type { NotificationChannel, NotificationChannelRequest, PlatformTenant } from '@/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 通知方式（**平台管理员专属**）。本轮只有邮件一种类型，但 `type` 是数据而不是常量 ——
 * 以后加短信/企微机器人只是多一种 `type` 与它的 config 结构，这一页的骨架不用动。
 */
const rows = ref<NotificationChannel[]>([])
const tenants = ref<PlatformTenant[]>([])
const loading = ref(false)
const drawer = ref(false)
/** 正在编辑的 code；null = 新建 */
const editing = ref<string | null>(null)
const saving = ref(false)

function emptyForm(): NotificationChannelRequest {
  return {
    code: '',
    name: '',
    type: 'EMAIL',
    tenantId: null,
    isDefault: false,
    enabled: true,
    config: { host: '', port: '465', from: '', username: '', password: '' },
  }
}
const form = reactive<NotificationChannelRequest>(emptyForm())

/** 后端把 config 存成 JSON 串；密码字段已摘掉，解析失败就当空配置 */
function parseConfig(raw?: string | null): Record<string, string> {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, string>
  } catch {
    return {}
  }
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    rows.value = await notificationChannelApi.list()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadTenants(): Promise<void> {
  try {
    tenants.value = await tenantApi.list()
  } catch {
    tenants.value = []
  }
}

function openCreate(): void {
  editing.value = null
  Object.assign(form, emptyForm())
  drawer.value = true
}

function openEdit(row: NotificationChannel): void {
  editing.value = row.code
  const cfg = parseConfig(row.config)
  Object.assign(form, {
    code: row.code,
    name: row.name,
    type: row.type,
    tenantId: row.tenantId ?? null,
    isDefault: !!row.isDefault,
    enabled: row.enabled !== false,
    config: {
      host: cfg.host ?? '',
      port: cfg.port ?? '465',
      from: cfg.from ?? '',
      username: cfg.username ?? '',
      // 密码不回填：留空 = 不修改（后端沿用库里的密文）
      password: '',
    },
  })
  drawer.value = true
}

async function submit(): Promise<void> {
  saving.value = true
  try {
    const config = { ...form.config }
    if (!config.password) delete config.password
    const body: NotificationChannelRequest = { ...form, config }
    if (editing.value) await notificationChannelApi.update(editing.value, body)
    else await notificationChannelApi.create(body)
    ElMessage.success(editing.value ? '已保存' : '已创建')
    drawer.value = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function onDelete(row: NotificationChannel): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除通知方式「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await notificationChannelApi.remove(row.code)
    ElMessage.success('已删除')
    await refresh()
  } catch {
    /* 仍被规则使用时由拦截器提示 */
  }
}

onMounted(async () => {
  await Promise.all([refresh(), loadTenants()])
})
</script>

<template>
  <div>
    <PageHeader title="通知方式" description="告警通知的发送渠道（当前支持邮件）">
      <el-button @click="refresh">刷新</el-button>
      <el-button type="primary" @click="openCreate">新建通知方式</el-button>
    </PageHeader>

    <EmptyState
      v-if="rows.length === 0 && !loading"
      title="还没有通知方式"
      description="先建一个邮件渠道并设为默认；没有绑定通知方式的规则会走它。在此之前告警只记录、不发送通知。"
    />

    <div v-else class="panel table-panel">
      <div class="notice-tip">停用的通知方式不再发送（已绑定的规则回落默认渠道）；没有任何启用的通知方式时，告警只记录、不发送通知。</div>
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="code" label="code" width="180">
          <template #default="{ row }"><code class="res-name">{{ row.code }}</code></template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column label="范围" width="160">
          <template #default="{ row }">
            <span v-if="row.tenantId">{{ row.tenantId }}</span>
            <el-tag v-else size="small" effect="plain">全局</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" size="small" type="success" effect="plain">默认</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span :class="row.enabled === false ? 'muted' : ''">{{ row.enabled === false ? '停用' : '启用' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="drawer" :title="editing ? `编辑 ${editing}` : '新建通知方式'" size="560px">
      <el-form label-width="120px" label-position="left">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 运维邮件" />
        </el-form-item>
        <el-form-item label="code" required>
          <el-input
            v-model="form.code"
            :disabled="!!editing"
            placeholder="如 ops-mail（K8s label 合法，建后不可改）"
          />
          <div class="form-tip">会写进规则标签 labels.platform_notify，只能用字母数字和 - _ .</div>
        </el-form-item>
        <el-form-item label="类型">
          <el-input :model-value="form.type" disabled />
        </el-form-item>

        <el-divider content-position="left">SMTP</el-divider>
        <el-form-item label="服务器" required>
          <el-input v-model="form.config.host" placeholder="smtp.example.com" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input v-model="form.config.port" placeholder="465" />
        </el-form-item>
        <el-form-item label="发件人" required>
          <el-input v-model="form.config.from" placeholder="alerts@example.com" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.config.username" placeholder="通常与发件人相同" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.config.password"
            type="password"
            show-password
            :placeholder="editing ? '留空表示不修改' : ''"
          />
          <div class="form-tip">加密后存库，列表与详情都不会回显</div>
        </el-form-item>

        <el-divider content-position="left">适用范围</el-divider>
        <el-form-item label="租户">
          <el-select v-model="form.tenantId" clearable placeholder="留空 = 全局（所有租户可用）" style="width: 100%">
            <el-option v-for="t in tenants" :key="t.id" :label="`${t.name}（${t.id}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" />
          <div class="form-tip">会清掉同范围（全局 / 该租户）的旧默认</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <div class="form-tip">停用后不再发送：已绑定它的规则也不再走它（回落默认渠道）</div>
        </el-form-item>
      </el-form>
      <div class="form-actions">
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 8px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.notice-tip {
  padding: 8px 8px 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.res-name {
  font-family: var(--el-font-family-mono, monospace);
}
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 4px;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 0;
}
</style>
