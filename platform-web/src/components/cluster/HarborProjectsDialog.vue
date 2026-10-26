<script setup lang="ts">
/**
 * Harbor 的「租户镜像项目」面板：每租户/平台共享的 project、凭据状态与同步入口。
 *
 * 数据源是 Harbor 组件的平台侧台账（/registry/projects/*），不直接问 Harbor。
 */
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { registryApi } from '@/api'
import { fmtDate } from '@/utils/format'
import type { K8sRegistryProject } from '@/types'

const props = defineProps<{ visible: boolean; clusterId: string }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const rows = ref<K8sRegistryProject[]>([])
const loading = ref(false)
const syncing = ref(false)

async function load(): Promise<void> {
  if (!props.clusterId) return
  loading.value = true
  try {
    rows.value = await registryApi.projects(props.clusterId)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (v) => { if (v) void load() })

async function syncAll(): Promise<void> {
  syncing.value = true
  try {
    const result = await registryApi.syncProjects(props.clusterId)
    if (result.failed > 0) {
      ElMessage.warning(`同步完成，但有 ${result.failed} 个租户失败（见下表）`)
    } else {
      ElMessage.success(`同步完成（${result.items.length} 项）`)
    }
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    syncing.value = false
  }
}

async function rebuild(row: K8sRegistryProject): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `重建「${row.tenantName}」的 Harbor 机器人账号？旧账号会被删除，凭据密文会覆盖。`,
      '确认重建', { type: 'warning' })
  } catch {
    return
  }
  try {
    const item = await registryApi.rebuildProject(props.clusterId, row.tenantId ?? null)
    if (item.status === 'READY') ElMessage.success('已重建')
    else ElMessage.error(item.message || '重建失败')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function close(): void {
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog :model-value="visible" title="Harbor 租户镜像项目" width="820px"
             @update:model-value="(v: boolean) => emit('update:visible', v)">
    <el-alert type="info" :closable="false" show-icon
              title="装完 Harbor 与新建租户时会自动同步"
              description="每个租户一个 project + 一个 push/pull 机器人账号，并登记成一条租户归属的 REGISTRY 凭据。失败的行可在这里重试或重建。" />
    <div class="hp-actions">
      <el-button type="primary" :loading="syncing" @click="syncAll">同步全部</el-button>
      <el-button :disabled="loading" @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="tenantName" label="归属" min-width="120" />
      <el-table-column prop="projectName" label="项目" min-width="160" />
      <el-table-column prop="host" label="Harbor" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'READY' ? 'success' : 'danger'" size="small">
            {{ row.status === 'READY' ? '就绪' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.message || '—' }}</template>
      </el-table-column>
      <el-table-column label="上次同步" width="160">
        <template #default="{ row }">{{ row.lastSyncedAt ? fmtDate(row.lastSyncedAt) : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="rebuild(row)">重建账号</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hp-actions {
  display: flex;
  gap: 8px;
  margin: 12px 0;
}
</style>
