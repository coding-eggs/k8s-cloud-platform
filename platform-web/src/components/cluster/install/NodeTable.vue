<script setup lang="ts">
/**
 * 节点表：每行一个节点，角色可多选（control-plane 可以兼 worker）。
 * 更新一律产生新数组/新对象，父级的 v-model 与 computed 校验能可靠感知。
 */
import { Delete, Plus } from '@element-plus/icons-vue'
import type { InstallNode, NodeRole } from '@/utils/clusterInstall/types'

const rows = defineModel<InstallNode[]>({ required: true, default: () => [] })

const ROLE_OPTIONS: { value: NodeRole; label: string }[] = [
  { value: 'control-plane', label: 'control-plane' },
  { value: 'worker', label: 'worker' },
  { value: 'etcd', label: 'etcd（专用）' },
]

function patch<K extends keyof InstallNode>(index: number, key: K, value: InstallNode[K]): void {
  rows.value = rows.value.map((n, i) => (i === index ? { ...n, [key]: value } : n))
}

function patchRoles(index: number, roles: NodeRole[]): void {
  patch(index, 'roles', roles)
}

function addNode(): void {
  rows.value = [
    ...rows.value,
    { hostname: `node-${rows.value.length + 1}`, ip: '', sshPort: 22, sshUser: 'root', roles: ['worker'] },
  ]
}

function removeNode(index: number): void {
  rows.value = rows.value.filter((_, i) => i !== index)
}
</script>

<template>
  <div class="node-table">
    <el-table :data="rows" stripe>
      <el-table-column label="hostname" min-width="150">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.hostname"
            placeholder="cp-1"
            @update:model-value="(v: string) => patch($index, 'hostname', v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="IP" min-width="150">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.ip"
            placeholder="10.0.0.11"
            @update:model-value="(v: string) => patch($index, 'ip', v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="SSH 端口" width="120">
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
      <el-table-column label="SSH 用户" width="130">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.sshUser"
            placeholder="root"
            @update:model-value="(v: string) => patch($index, 'sshUser', v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="280">
        <template #default="{ row, $index }">
          <el-checkbox-group
            :model-value="row.roles"
            @update:model-value="(v: NodeRole[]) => patchRoles($index, v)"
          >
            <el-checkbox v-for="opt in ROLE_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-checkbox>
          </el-checkbox-group>
        </template>
      </el-table-column>
      <el-table-column width="64" fixed="right">
        <template #default="{ $index }">
          <el-button link type="danger" :icon="Delete" @click="removeNode($index)" />
        </template>
      </el-table-column>
    </el-table>
    <div class="node-table__footer">
      <el-button :icon="Plus" @click="addNode">添加节点</el-button>
      <span class="node-table__hint">control-plane 可以同时勾选 worker；专用 etcd 不能和控制面或 worker 重叠。</span>
    </div>
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
