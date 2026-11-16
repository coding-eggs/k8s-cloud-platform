<script setup lang="ts">
/**
 * 节点表：每行一个节点，角色可多选（control-plane 可以兼 worker）。
 * 更新一律产生新数组/新对象，父级的 v-model 与 computed 校验能可靠感知。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus, Upload } from '@element-plus/icons-vue'
import type { EtcdMode, InstallNode, NodeRole } from '@/utils/clusterInstall/types'
import { DEFAULT_OS, OS_OPTIONS } from '@/utils/clusterInstall/kubespray'
import { cleanupEtcdRoles } from '@/utils/clusterInstall/roles'
import NodeImportDialog from './NodeImportDialog.vue'

const rows = defineModel<InstallNode[]>({ required: true, default: () => [] })
const props = defineProps<{ etcdMode: EtcdMode }>()
const showImport = ref(false)

const ROLE_OPTIONS: { value: NodeRole; label: string }[] = [
  { value: 'control-plane', label: '控制面' },
  { value: 'worker', label: 'worker' },
  { value: 'etcd', label: 'etcd' },
]

/** etcd 角色只在「专用 etcd」模式下有意义：同机模式 etcd 就在控制面上，外部托管根本不用节点 */
const roleOptions = computed(() =>
  props.etcdMode === 'dedicated' ? ROLE_OPTIONS : ROLE_OPTIONS.filter((o) => o.value !== 'etcd'),
)

function patch<K extends keyof InstallNode>(index: number, key: K, value: InstallNode[K]): void {
  rows.value = rows.value.map((n, i) => (i === index ? { ...n, [key]: value } : n))
}

function patchRoles(index: number, roles: NodeRole[]): void {
  patch(index, 'roles', roles)
}

function addNode(): void {
  rows.value = [
    ...rows.value,
    {
      hostname: `node-${rows.value.length + 1}`,
      ip: '',
      sshPort: 22,
      sshUser: 'root',
      os: DEFAULT_OS,
      roles: ['worker'],
    },
  ]
}

function removeNode(index: number): void {
  rows.value = rows.value.filter((_, i) => i !== index)
}

function onImport(nodes: InstallNode[], mode: 'append' | 'replace'): void {
  const cleaned = cleanupEtcdRoles(nodes, props.etcdMode === 'dedicated')
  const incoming = cleaned.nodes.map((n) => ({ ...n, roles: [...n.roles] }))
  rows.value = mode === 'replace' ? incoming : [...rows.value, ...incoming]
  showImport.value = false
  if (cleaned.dropped > 0) {
    ElMessage.warning(
      `${cleaned.dropped} 行只有 etcd 角色，当前模式不由节点承载 etcd，已跳过（要专用 etcd 请先把模式切过去）`,
    )
  } else if (cleaned.stripped > 0) {
    ElMessage.info(`${cleaned.stripped} 行的 etcd 角色已忽略：当前不是「专用 etcd」模式`)
  }
}
</script>

<template>
  <div class="node-table">
    <el-table :data="rows" stripe>
      <el-table-column label="hostname" min-width="120">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.hostname"
            placeholder="cp-1"
            @update:model-value="(v: string) => patch($index, 'hostname', v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="IP" min-width="140">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.ip"
            placeholder="10.0.0.11"
            @update:model-value="(v: string) => patch($index, 'ip', v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="SSH 端口" width="86">
        <template #default="{ row, $index }">
          <el-input-number
            :model-value="row.sshPort"
            :min="1"
            :max="65535"
            :controls="false"
            style="width: 100%"
            @update:model-value="(v: number | undefined) => patch($index, 'sshPort', v ?? 22)"
          />
        </template>
      </el-table-column>
      <el-table-column label="SSH 用户" width="92">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.sshUser"
            placeholder="root"
            @update:model-value="(v: string) => patch($index, 'sshUser', v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作系统" width="205">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.os"
            style="width: 100%"
            @update:model-value="(v: string) => patch($index, 'os', v)"
          >
            <el-option v-for="o in OS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="215">
        <template #default="{ row, $index }">
          <el-checkbox-group
            :model-value="row.roles"
            @update:model-value="(v: NodeRole[]) => patchRoles($index, v)"
          >
            <el-checkbox v-for="opt in roleOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-checkbox>
          </el-checkbox-group>
        </template>
      </el-table-column>
      <el-table-column width="56" fixed="right">
        <template #default="{ $index }">
          <el-button link type="danger" :icon="Delete" @click="removeNode($index)" />
        </template>
      </el-table-column>
    </el-table>
    <div class="node-table__footer">
      <el-button :icon="Plus" @click="addNode">添加节点</el-button>
      <el-button :icon="Upload" @click="showImport = true">导入节点</el-button>
      <span class="node-table__hint">
        control-plane 可以同时勾选 worker；各节点 OS 可以不同（kubespray 按主机探测）<template v-if="etcdMode === 'dedicated'">；专用 etcd 不能与控制面或 worker 重叠</template>。
      </span>
    </div>

    <NodeImportDialog v-model="showImport" :existing-count="rows.length" @confirm="onImport" />
  </div>
</template>

<style scoped>
.node-table__footer {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}
.node-table__hint {
  font-size: 12px;
  color: var(--text-3, #909399);
}
</style>
