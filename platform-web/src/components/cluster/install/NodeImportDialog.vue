<script setup lang="ts">
/**
 * 批量导入节点：粘贴表格 / 选 CSV 文件，本地解析，不上传。
 * 有错误行时不允许导入（避免半份数据进表单后还要逐行找问题）。
 */
import { computed, ref, watch } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import { parseNodeRows } from '@/utils/clusterInstall/importNodes'
import { osLabel } from '@/utils/clusterInstall/kubespray'
import type { InstallNode } from '@/utils/clusterInstall/types'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ existingCount: number }>()
const emit = defineEmits<{ confirm: [nodes: InstallNode[], mode: 'append' | 'replace'] }>()

const PLACEHOLDER = `cp-1\t10.0.0.11
cp-2\t10.0.0.12\t22\troot\trocky9\tcontrol-plane
worker-1\t10.0.0.21`

const text = ref('')
const mode = ref<'append' | 'replace'>('append')
const fileName = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

const parsed = computed(() => parseNodeRows(text.value))
const canImport = computed(() => parsed.value.nodes.length > 0 && parsed.value.issues.length === 0)

watch(open, (v) => {
  if (!v) return
  text.value = ''
  mode.value = 'append'
  fileName.value = ''
})

function onFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    text.value = String(reader.result ?? '')
    fileName.value = file.name
  }
  reader.readAsText(file)
  input.value = '' // 允许重复选同一个文件
}

function confirm(): void {
  if (!canImport.value) return
  emit('confirm', parsed.value.nodes, mode.value)
}
</script>

<template>
  <el-dialog v-model="open" title="批量导入节点" width="760px" append-to-body>
    <div class="node-import">
      <p class="node-import__tip">
        每行一个节点：<code>hostname　IP　[SSH端口]　[SSH用户]　[操作系统]　[角色]</code>。
        支持从 Excel 直接粘贴（制表符）或 CSV；首行表头会被跳过；角色可写
        <code>control-plane</code> / <code>worker</code> / <code>etcd</code>，多个用 <code>/</code> 分隔。
      </p>

      <div class="node-import__toolbar">
        <el-button :icon="Upload" @click="fileInput?.click()">选择 CSV 文件</el-button>
        <input
          ref="fileInput"
          type="file"
          accept=".csv,.tsv,.txt,text/csv,text/plain"
          class="node-import__file"
          @change="onFile"
        />
        <span v-if="fileName" class="node-import__file-name">{{ fileName }}</span>
        <span class="node-import__spacer" />
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="append">追加（保留现有 {{ props.existingCount }} 行）</el-radio-button>
          <el-radio-button value="replace">替换（覆盖现有 {{ props.existingCount }} 行）</el-radio-button>
        </el-radio-group>
      </div>

      <el-input v-model="text" type="textarea" :rows="8" :placeholder="PLACEHOLDER" />

      <div class="node-import__result">
        <span :class="['node-import__count', { 'is-ok': canImport }]">
          已识别 {{ parsed.nodes.length }} 个节点
        </span>
        <span v-if="parsed.issues.length" class="node-import__bad">，{{ parsed.issues.length }} 行有问题</span>
      </div>

      <ul v-if="parsed.issues.length" class="node-import__issues">
        <li v-for="(i, idx) in parsed.issues" :key="idx">第 {{ i.line }} 行：{{ i.message }}</li>
      </ul>

      <el-table v-if="parsed.nodes.length" :data="parsed.nodes" size="small" max-height="220" class="node-import__preview">
        <el-table-column prop="hostname" label="hostname" min-width="110" />
        <el-table-column prop="ip" label="IP" min-width="120" />
        <el-table-column prop="sshPort" label="端口" width="70" />
        <el-table-column prop="sshUser" label="SSH 用户" width="95" />
        <el-table-column label="操作系统" width="170">
          <template #default="{ row }">{{ osLabel(row.os) }}</template>
        </el-table-column>
        <el-table-column label="角色" min-width="150">
          <template #default="{ row }">{{ row.roles.join(' / ') }}</template>
        </el-table-column>
      </el-table>
    </div>

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :disabled="!canImport" @click="confirm">
        导入 {{ parsed.nodes.length }} 个节点
      </el-button>
    </template>
  </el-dialog>
</template>

<!-- EP 弹窗内容会被 teleport 到 body，scoped 样式对它不生效 —— 用非 scoped + 独有前缀（见 memory） -->
<style>
.node-import__tip {
  margin: 0 0 12px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.node-import__tip code {
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12px;
}
.node-import__toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.node-import__file {
  display: none;
}
.node-import__file-name {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.node-import__spacer {
  flex: 1;
}
.node-import__result {
  margin: 10px 0 4px;
  font-size: 12.5px;
  color: var(--el-text-color-regular);
}
.node-import__count.is-ok {
  color: var(--el-color-success);
  font-weight: 600;
}
.node-import__bad {
  color: var(--el-color-danger);
}
.node-import__issues {
  list-style: none;
  margin: 4px 0 8px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 120px;
  overflow: auto;
  font-size: 12.5px;
  color: var(--el-color-danger);
}
.node-import__preview {
  margin-top: 8px;
}
</style>
