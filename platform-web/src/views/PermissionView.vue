<script setup lang="ts">
/**
 * 权限点目录（platform_permission）：一行 = 一条 URL 规则；code 不唯一（ANY-of）。
 * 读 /permission/list = platform:role:read；写 create/update/delete/reload = platform:role:manage。
 * 表格把相邻同 code 行的「权限点/说明」两列纵向合并 —— 一眼看出"哪个权限码覆盖哪些 URL"。
 * 后端写前裸端点校验（会删空活端点覆盖即拒绝，错误经 http 拦截器统一弹错）+ 提交后热加载即时生效。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import { permissionApi } from '@/api'
import type { PlatformPermission } from '@/types'

const loading = ref(false)
const list = ref<PlatformPermission[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    const rows = await permissionApi.list()
    // 服务端 order by domain, code；此处按 code（再 domain/resource）稳定重排，使同 code 行相邻可合并
    list.value = [...rows].sort(
      (a, b) =>
        a.code.localeCompare(b.code) ||
        (a.domain ?? '').localeCompare(b.domain ?? '') ||
        (a.resource ?? '').localeCompare(b.resource ?? ''),
    )
  } finally {
    loading.value = false
  }
}

/** 每个 code 组的起始行号与行数（span-method 合并依据） */
const codeGroups = computed(() => {
  const groups = new Map<string, { index: number; size: number }>()
  list.value.forEach((p, i) => {
    const g = groups.get(p.code)
    if (!g) groups.set(p.code, { index: i, size: 1 })
    else g.size += 1
  })
  return groups
})

/** 仅合并第 0/1 列（权限点、说明）：组首行 rowspan=组大小，其余行隐藏 */
function spanMethod({ row, rowIndex, columnIndex }: { row: PlatformPermission; rowIndex: number; columnIndex: number }): [number, number] {
  if (columnIndex !== 0 && columnIndex !== 1) return [1, 1]
  const g = codeGroups.value.get(row.code)
  if (!g) return [1, 1]
  return rowIndex === g.index ? [g.size, 1] : [0, 1]
}

// ---- 创建 / 编辑对话框（共用，isEdit 切换） ----
const dialogVisible = ref(false)
const saving = ref(false)
const isEdit = ref(false)
const form = reactive({ id: '', domain: 'API', resource: '', action: 'POST', code: '', description: '' })

const DOMAINS = ['API', 'K8S', 'Page']
const ACTIONS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', '*']

function openCreate(): void {
  isEdit.value = false
  form.id = ''
  form.domain = 'API'
  form.resource = ''
  form.action = 'POST'
  form.code = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row: PlatformPermission): void {
  isEdit.value = true
  form.id = row.id
  form.domain = row.domain ?? 'API'
  form.resource = row.resource ?? ''
  form.action = row.action ?? 'POST'
  form.code = row.code
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  // 手动校验（与后端 PermissionService.validateFields 同口径，后端仍会兜底）
  const code = form.code.trim()
  if (!code || !(code.startsWith('platform:') || code.startsWith('tenant:'))) {
    ElMessage.warning('权限码必填，且须以 platform: 或 tenant: 开头')
    return
  }
  const resource = form.resource.trim()
  if (!resource) {
    ElMessage.warning('请输入资源（API 域为 URL 模式）')
    return
  }
  if (form.domain === 'API' && !resource.startsWith('/')) {
    ElMessage.warning('API 域 URL 模式须以 / 开头（支持 Ant 通配，如 /xxx/**）')
    return
  }
  saving.value = true
  try {
    const payload = {
      domain: form.domain,
      resource,
      action: form.action,
      code,
      description: form.description.trim() || undefined,
    }
    if (isEdit.value) {
      await permissionApi.update({ id: form.id, ...payload })
    } else {
      await permissionApi.create(payload)
    }
    ElMessage.success('已保存，授权规则已实时生效')
    dialogVisible.value = false
    await load()
  } catch {
    /* 拦截器提示（含裸端点保护拒绝的端点清单） */
  } finally {
    saving.value = false
  }
}

// ---- 行操作 ----
async function onDelete(row: PlatformPermission): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除权限行「${row.resource ?? '-'}（${row.action ?? '-'}）」？若它是某端点的唯一覆盖行，后端会拒绝并列出受影响端点。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await permissionApi.delete(row.id)
    ElMessage.success('已删除，授权规则已实时生效')
    await load()
  } catch {
    /* 拦截器提示 */
  }
}

// ---- 重载规则（SQL 直接改库后的对齐入口；CRUD 本身自动生效） ----
const reloading = ref(false)
async function onReload(): Promise<void> {
  reloading.value = true
  try {
    await permissionApi.reload()
    ElMessage.success('运行时规则已与数据库对齐')
    await load()
  } catch {
    /* 拦截器提示 */
  } finally {
    reloading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">创建权限点</el-button>
      <el-button @click="load">刷新</el-button>
      <el-button :loading="reloading" @click="onReload">重载规则</el-button>
    </div>

    <el-table v-loading="loading" :data="list" stripe :span-method="spanMethod">
      <el-table-column prop="code" label="权限点 (code)" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="160">
        <template #default="{ row }">
          <span>{{ row.description || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="域" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.domain === 'API' ? '' : 'info'">{{ row.domain ?? '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="URL 模式 (resource)" min-width="220">
        <template #default="{ row }">
          <span class="mono">{{ row.resource ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="方法 (action)" width="110">
        <template #default="{ row }">
          <span class="mono">{{ row.action ?? '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column width="64" fixed="right">
        <template #default="{ row }">
          <el-dropdown trigger="click">
            <el-button link type="primary" :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="openEdit(row)">
                  <el-button link type="primary">编辑</el-button>
                </el-dropdown-item>
                <el-dropdown-item divided style="color: var(--el-color-danger)" @click="onDelete(row)">
                  <el-button link type="danger">删除</el-button>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑权限点' : '创建权限点'" width="520px">
      <el-form label-width="130px">
        <el-form-item label="权限域" required>
          <el-select v-model="form.domain">
            <el-option v-for="d in DOMAINS" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源 (resource)" required>
          <el-input v-model="form.resource" placeholder="API 域：URL 模式，例如 /tenant/**；K8S/Page 域：资源名/页面标识" />
        </el-form-item>
        <el-form-item label="方法 (action)" required>
          <el-select v-model="form.action">
            <el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限码 (code)" required>
          <el-input v-model="form.code" placeholder="例如 platform:role:manage" />
          <div class="form-tip">
            须以 platform:（平台族）或 tenant:（租户族）开头；同 code 可覆盖多条 URL 规则（ANY-of），角色勾选按 code 展开全部行
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="例如 新建权限点" />
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
.form-tip {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.4;
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: 12.5px;
}
</style>
