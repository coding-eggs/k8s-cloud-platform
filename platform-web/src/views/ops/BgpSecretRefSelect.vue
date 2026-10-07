<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { calicoApi } from '@/api'
import type { SecretRefOption } from '@/types'

// BGP 密码 Secret 引用三级下拉（命名空间 → Secret → key），级联查询：
// 命名空间一次加载；Secret 按所选命名空间按需查（含 data keys）；均可手工输入（allow-create）兜底
const props = defineProps<{
  clusterId: string
  modelValue: { namespace: string; name: string; key: string }
}>()
const emit = defineEmits<{ 'update:modelValue': [v: { namespace: string; name: string; key: string }] }>()

const namespaces = ref<string[]>([])
const secretCache = ref<Record<string, SecretRefOption[]>>({}) // ns → 候选（同集群内不重复查）

async function loadNamespaces(): Promise<void> {
  if (!props.clusterId) return
  try {
    const opts = await calicoApi.formOptions(props.clusterId)
    namespaces.value = opts.namespaces ?? []
  } catch {
    namespaces.value = [] // 拦截器已提示；降级空下拉（仍可手输）
  }
}

async function loadSecrets(ns: string): Promise<void> {
  if (!props.clusterId || !ns || secretCache.value[ns]) return
  try {
    secretCache.value[ns] = await calicoApi.secretOptions(props.clusterId, ns)
  } catch {
    secretCache.value[ns] = [] // 该 ns 可能不存在（手输的）；空候选仍可继续手输
  }
}

watch(
  () => props.clusterId,
  async (v, old) => {
    // 切换集群：旧缓存失效、选择清空（首次加载不清，保留编辑回填值）
    secretCache.value = {}
    if (old !== undefined && v) emit('update:modelValue', { namespace: '', name: '', key: '' })
    await loadNamespaces()
    if (v && props.modelValue.namespace) await loadSecrets(props.modelValue.namespace)
  },
  { immediate: true },
)

// 命名空间变化（选择或手输）→ 按需加载该 ns 的 Secret
watch(
  () => props.modelValue.namespace,
  async (ns) => { if (ns) await loadSecrets(ns) },
)

const secretOptions = computed<SecretRefOption[]>(() =>
  props.modelValue.namespace ? (secretCache.value[props.modelValue.namespace] ?? []) : [],
)
const keyOptions = computed(
  () =>
    secretOptions.value.find((s) => s.name === props.modelValue.name)?.keys ?? [],
)

function patch(p: Partial<{ namespace: string; name: string; key: string }>): void {
  emit('update:modelValue', { ...props.modelValue, ...p })
}
</script>

<template>
  <div class="secret-ref">
    <el-select
      :model-value="modelValue.namespace" filterable allow-create default-first-option clearable
      placeholder="命名空间（默认 default）" style="flex: 1"
      @update:model-value="(v: string) => patch({ namespace: v, name: '', key: '' })"
    >
      <el-option v-for="n in namespaces" :key="n" :label="n" :value="n" />
    </el-select>
    <el-select
      :model-value="modelValue.name" filterable allow-create default-first-option clearable
      :placeholder="modelValue.namespace ? 'Secret 名称（必填）' : '先选命名空间'" style="flex: 1"
      @update:model-value="(v: string) => patch({ name: v, key: '' })"
    >
      <el-option v-for="s in secretOptions" :key="s.name" :label="s.name" :value="s.name" />
    </el-select>
    <el-select
      :model-value="modelValue.key" filterable allow-create default-first-option clearable
      placeholder="Secret key" style="flex: 1"
      @update:model-value="(v: string) => patch({ key: v })"
    >
      <el-option v-for="k in keyOptions" :key="k" :label="k" :value="k" />
    </el-select>
  </div>
</template>

<style scoped>
.secret-ref { display: flex; gap: 8px; width: 100%; max-width: 720px; }
</style>
