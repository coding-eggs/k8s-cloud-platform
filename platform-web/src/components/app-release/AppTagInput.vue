<script setup lang="ts">
/**
 * 镜像 tag 输入框：手填永远可用，聚焦/输入时从镜像仓库补候选 tag（只是便利）。
 * 候选来自 `/registry/suggest`（入参 `<仓库>:<已输入前缀>`，后端只问调用者可见的连接）；
 * 无权限、仓库为空、请求失败一律静默给空候选，不影响手填。
 */
import { computed } from 'vue'
import { registryApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'

const props = defineProps<{ modelValue: string; repo: string; size?: 'small' | 'default'; placeholder?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const perm = usePermission()
const model = computed({ get: () => props.modelValue, set: (v: string) => emit('update:modelValue', v) })

interface TagItem { value: string; connectionName: string }

function fetchTags(query: string, cb: (items: TagItem[]) => void): void {
  const repo = (props.repo ?? '').trim()
  if (!repo || !perm.has(apiCodes.registryCatalog)) { cb([]); return }
  registryApi.suggest(`${repo}:${(query ?? '').trim()}`)
    .then((r) => {
      const seen = new Set<string>()
      const out: TagItem[] = []
      const head = `${repo}:`.toLowerCase()
      for (const it of r.items ?? []) {
        // 无 host 的输入会问全部来源，候选可能是别的仓库的：只收 `<本仓库>:tag`
        if (!it.value.toLowerCase().startsWith(head)) continue
        const tag = it.value.slice(it.value.lastIndexOf(':') + 1)
        if (tag && !seen.has(tag)) { seen.add(tag); out.push({ value: tag, connectionName: it.connectionName }) }
      }
      cb(out)
    })
    .catch(() => cb([]))
}
</script>

<template>
  <el-autocomplete
    v-model="model"
    :fetch-suggestions="fetchTags"
    :size="size"
    :placeholder="placeholder ?? '镜像 tag'"
    :trigger-on-focus="true"
    clearable
    class="app-tag-input"
  >
    <template #default="{ item }">
      <span>{{ item.value }}</span>
      <span class="app-tag-input__src">{{ item.connectionName }}</span>
    </template>
  </el-autocomplete>
</template>

<style>
.app-tag-input { width: 100%; }
.app-tag-input__src { float: right; color: var(--el-text-color-secondary); font-size: 12px; margin-left: 12px; }
</style>
