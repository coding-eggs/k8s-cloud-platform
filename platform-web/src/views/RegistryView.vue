<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { registryApi } from '@/api'
import type { RegistryBrowseResult, RegistryConnectionView, RegistryNode } from '@/types'
import { writeClipboard } from '@/utils/clipboard'

const connections = ref<RegistryConnectionView[]>([])
const credentialId = ref('')
const path = ref<string[]>([])
const result = ref<RegistryBrowseResult | null>(null)

const loading = ref(false)
/** 与「这一层是空的」区分开 —— 浏览失败必须让用户看见（spec §5.3） */
const error = ref('')

async function loadConnections(): Promise<void> {
  connections.value = await registryApi.connections()
  // 取成局部常量：`arr[0]` 在 noUncheckedIndexedAccess 下是 `T | undefined`
  const first = connections.value[0]
  if (first && !credentialId.value) {
    credentialId.value = first.id
    await load()
  }
}

async function load(): Promise<void> {
  if (!credentialId.value) return
  loading.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await registryApi.browse(credentialId.value, path.value)
  } catch (e: unknown) {
    // 浏览**不静默** —— 用户主动点了一层，什么都不说等于页面坏了
    error.value = e instanceof Error ? e.message : '读取镜像目录失败'
  } finally {
    loading.value = false
  }
}

async function onConnectionChange(): Promise<void> {
  path.value = []
  await load()
}

async function drillInto(node: RegistryNode): Promise<void> {
  if (!node.hasChildren) return
  path.value = [...path.value, node.name]
  await load()
}

async function goTo(p: string[]): Promise<void> {
  path.value = [...p]
  await load()
}

async function copyImage(node: RegistryNode): Promise<void> {
  if (!node.copyText) return
  const ok = await writeClipboard(node.copyText)
  if (ok) {
    ElMessage.success('已复制镜像地址')
  } else {
    ElMessage.warning('当前浏览器不允许自动复制，请手动选中复制')
  }
}

onMounted(loadConnections)
</script>

<template>
  <div class="registry-page">
    <div class="page-head">
      <h2>镜像仓库</h2>
      <el-select
        v-model="credentialId"
        style="width: 320px"
        placeholder="选择镜像仓库"
        @change="onConnectionChange"
      >
        <el-option
          v-for="c in connections"
          :key="c.id"
          :label="`${c.name}（${c.endpoint}）`"
          :value="c.id"
        />
      </el-select>
    </div>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="hint" />

    <el-breadcrumb v-if="result && result.breadcrumb.length" separator="/" class="crumb">
      <el-breadcrumb-item>
        <a @click.prevent="goTo([])">根</a>
      </el-breadcrumb-item>
      <el-breadcrumb-item v-for="b in result.breadcrumb" :key="b.label">
        <a @click.prevent="goTo(b.path)">{{ b.label }}</a>
      </el-breadcrumb-item>
    </el-breadcrumb>

    <el-table v-loading="loading" :data="result?.children ?? []" stripe>
      <el-table-column label="名称" min-width="260">
        <template #default="{ row }">
          <a v-if="row.hasChildren" class="drill" @click.prevent="drillInto(row)">{{ row.name }}</a>
          <span v-else class="mono">{{ row.name }}</span>
        </template>
      </el-table-column>
      <!-- 列由数据决定：details 的标签就是列头（后端不写死，前端也不写死） -->
      <el-table-column
        v-for="(d, i) in result?.children?.[0]?.details ?? []"
        :key="i"
        :label="d.label"
        min-width="160"
      >
        <template #default="{ row }">
          <span>{{ row.details?.[i]?.value ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.copyText" link type="primary" @click="copyImage(row)">
            复制镜像地址
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && !error && result && !result.children.length" class="empty">
      这一层没有内容
    </div>
  </div>
</template>

<style scoped>
.registry-page {
  padding: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
}
.hint {
  margin-bottom: 12px;
}
.crumb {
  margin-bottom: 12px;
}
.crumb a,
.drill {
  color: var(--el-color-primary);
  cursor: pointer;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.empty {
  padding: 24px;
  text-align: center;
  color: var(--text-3);
}
</style>
