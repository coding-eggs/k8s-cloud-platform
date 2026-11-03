<script setup lang="ts">
/**
 * 添加依赖（设计 §5）：新建 / 绑定 / 外部连接三种来源。
 *
 * - 新建：在应用所在的集群和命名空间里建一个中间件实例，规格字段沿用中间件商店的表单定义（middlewareForms）；
 *   后端依赖请求只接收形态、版本、副本数、资源和存储，Milvus（需要对象存储来源）请先在商店创建再绑定。
 * - 绑定：只列同一集群、同一命名空间、还没被本应用绑定的实例。
 * - 外部：协议 + 地址 + 端口 + Secret 名与键（表单项见 AppExternalForm，与「修改外部连接」共用）。
 *   **没有口令输入框**：口令放在与应用同命名空间的 Secret 里。
 * 依赖名与服务名共用一个命名空间；规则与后端一致（见 dependencyUtil）。
 * 提交失败的原文留在对话框内。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { appDependencyApi, middlewareApi } from '@/api'
import { MIDDLEWARE_FIELDS, toQuantity, type MiddlewareField } from '@/components/middleware/middlewareForms'
import type { AppDependencyRequest, MiddlewareCatalogItem, MiddlewareInstance } from '@/types'
import {
  CREATE_EXCLUDED, emptyExternal, externalPayload, keyProblem, type ExternalForm,
} from './dependencyUtil'
import { errMsg } from './releaseUtil'
import type { SecretCtx } from './useNamespaceSecrets'
import AppExternalForm from './AppExternalForm.vue'

const props = defineProps<{
  modelValue: boolean
  appId: string
  tenantId?: string
  clusterId: string
  namespace: string
  /** 已被依赖和服务占用的名称 */
  usedKeys: string[]
  /** 已被本应用绑定 / 创建的实例 id */
  usedInstanceIds: string[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'added', key: string): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

type Mode = 'CREATE' | 'BIND' | 'EXTERNAL'
const mode = ref<Mode>('CREATE')
const key = ref('')
const submitting = ref(false)
const error = ref('')

const keyHint = computed(() => {
  if (!key.value) return ''
  const p = keyProblem(key.value)
  if (p) return p
  return props.usedKeys.includes(key.value) ? '名称已被依赖或服务占用' : ''
})

// ---------------- 新建 ----------------
const ALLOWED_FIELDS = ['replicas', 'cpuRequest', 'cpuLimit', 'memoryRequest', 'memoryLimit', 'storageSize']
const catalog = ref<MiddlewareCatalogItem[]>([])
const storageClasses = ref<string[]>([])
const catalogLoading = ref(false)
const code = ref('')
const shape = ref('')
const version = ref('')
const storageClass = ref('')
const values = ref<Record<string, number | undefined>>({})
const units = ref<Record<string, string>>({})

const creatable = computed(() => catalog.value.filter((c) => !CREATE_EXCLUDED.includes(c.code)))
const item = computed(() => catalog.value.find((c) => c.code === code.value))
const itemReady = (c: MiddlewareCatalogItem): boolean => c.chartBacked || c.operatorReady
const modeInfo = computed(() => item.value?.modes.find((m) => m.mode === shape.value))
const fixedReplicas = computed(() => modeInfo.value?.fixedReplicas ?? null)
const minReplicas = computed(() => modeInfo.value?.minReplicas ?? 1)
const fields = computed<MiddlewareField[]>(() =>
  (item.value ? (MIDDLEWARE_FIELDS[item.value.code] ?? []) : []).filter((f) => ALLOWED_FIELDS.includes(f.key)))

async function loadCreateOptions(): Promise<void> {
  if (!props.clusterId) return
  catalogLoading.value = true
  try {
    const [c, o] = await Promise.all([
      middlewareApi.catalog(props.clusterId, props.tenantId),
      middlewareApi.formOptions(props.clusterId, props.tenantId).catch(() => null),
    ])
    catalog.value = c ?? []
    storageClasses.value = o?.storageClasses ?? []
  } catch (e) {
    error.value = errMsg(e, '中间件清单加载失败')
  } finally {
    catalogLoading.value = false
  }
}

function resetCreate(): void {
  const first = creatable.value.find(itemReady)
  code.value = first?.code ?? ''
  applyCode()
}
function applyCode(): void {
  const c = item.value
  shape.value = c?.modes[0]?.mode ?? ''
  version.value = c?.versions?.[0] ?? ''
  storageClass.value = ''
  const v: Record<string, number | undefined> = {}
  const u: Record<string, string> = {}
  for (const f of fields.value) {
    v[f.key] = f.default
    if (f.unit === 'size') u[f.key] = f.unitDefault ?? 'Gi'
  }
  values.value = v
  units.value = u
  onShape()
}
function onShape(): void {
  if (!('replicas' in values.value)) return
  if (fixedReplicas.value !== null) values.value.replicas = fixedReplicas.value
  else if (typeof values.value.replicas !== 'number' || values.value.replicas < minReplicas.value) {
    values.value.replicas = minReplicas.value
  }
}
function unitMin(f: MiddlewareField): number {
  if (f.unit !== 'size') return f.min ?? 0
  return (units.value[f.key] ?? f.unitDefault ?? 'Gi') === 'Mi' ? 16 : 1
}
/** 切换单位时换算数值（Gi→Mi ×1024，Mi→Gi 向上取整），避免 128Mi 变成 128Gi */
function changeUnit(f: MiddlewareField, next: 'Mi' | 'Gi'): void {
  const prev = (units.value[f.key] ?? f.unitDefault ?? 'Gi') as 'Mi' | 'Gi'
  if (prev === next) return
  const cur = Number(values.value[f.key] ?? 0)
  if (Number.isFinite(cur) && cur > 0) {
    values.value[f.key] = next === 'Mi' ? Math.max(16, Math.round(cur * 1024)) : Math.max(1, Math.ceil(cur / 1024))
  }
  units.value[f.key] = next
}

// ---------------- 绑定 ----------------
const instances = ref<MiddlewareInstance[]>([])
const instanceId = ref('')
const instLoading = ref(false)
const bindable = computed(() => instances.value.filter((i) =>
  i.namespace === props.namespace && i.status !== 'DELETING' && !props.usedInstanceIds.includes(i.id)))

async function loadInstances(): Promise<void> {
  if (!props.clusterId) return
  instLoading.value = true
  try {
    instances.value = (await middlewareApi.instanceList(props.clusterId, undefined, props.tenantId, props.namespace)) ?? []
    if (!bindable.value.some((i) => i.id === instanceId.value)) instanceId.value = ''
  } catch (e) {
    error.value = errMsg(e, '实例列表加载失败')
  } finally {
    instLoading.value = false
  }
}
const STATUS_TEXT: Record<string, string> = {
  CREATING: '创建中', READY: '运行中', DEGRADED: '降级', FAILED: '失败',
}
function instLabel(i: MiddlewareInstance): string {
  const from = i.appId && i.appName ? ` · 来自应用 ${i.appName}` : ''
  return `${i.displayName || i.name}（${i.code} · ${STATUS_TEXT[i.status] ?? i.status}${from}）`
}

// ---------------- 外部 ----------------
const ext = ref<ExternalForm>(emptyExternal())
const secretCtx = computed<SecretCtx>(() => ({ tenantId: props.tenantId, clusterId: props.clusterId, namespace: props.namespace }))
const extForm = ref<{ valid: boolean; reload: () => Promise<void> } | null>(null)

// ---------------- 提交 ----------------
const canSubmit = computed(() => {
  if (!key.value || keyHint.value || submitting.value) return false
  if (mode.value === 'CREATE') return !!code.value && !!item.value && itemReady(item.value)
  if (mode.value === 'BIND') return !!instanceId.value
  return !!extForm.value?.valid
})

function buildRequest(): AppDependencyRequest {
  const base = { appId: props.appId, tenantId: props.tenantId, key: key.value }
  if (mode.value === 'BIND') {
    return { ...base, type: 'MIDDLEWARE', source: 'BIND', instanceId: instanceId.value }
  }
  if (mode.value === 'EXTERNAL') {
    return { ...base, type: 'EXTERNAL', external: externalPayload(ext.value) }
  }
  const req: AppDependencyRequest = {
    ...base, type: 'MIDDLEWARE', source: 'CREATE', code: code.value, shape: shape.value || undefined,
    version: version.value || undefined, storageClass: storageClass.value || undefined,
  }
  for (const f of fields.value) {
    const q = toQuantity(f, values.value[f.key], units.value[f.key])
    if (q === undefined) continue
    if (f.key === 'replicas') req.replicas = Number(q)
    else (req as unknown as Record<string, unknown>)[f.key] = q
  }
  return req
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  submitting.value = true
  error.value = ''
  try {
    await appDependencyApi.add(buildRequest())
    visible.value = false
    ElMessage.success(mode.value === 'CREATE' ? '依赖已添加，实例创建中，就绪后即可发布' : '依赖已添加')
    emit('added', key.value)
  } catch (e) {
    // 拦截器已弹 toast；对话框内再留一份原文
    error.value = errMsg(e, '添加失败')
  } finally {
    submitting.value = false
  }
}

watch(() => props.modelValue, (open) => {
  if (!open) return
  mode.value = 'CREATE'
  key.value = ''
  error.value = ''
  instanceId.value = ''
  instances.value = []
  ext.value = emptyExternal()
  void loadCreateOptions().then(resetCreate)
})
watch(mode, (m) => {
  error.value = ''
  if (m === 'BIND' && !instances.value.length) void loadInstances()
  // 外部表单在切到 EXTERNAL 后才渲染：等它挂上再拉 Secret 候选
  if (m === 'EXTERNAL') void nextTick(() => extForm.value?.reload())
})
</script>

<template>
  <el-dialog v-model="visible" title="添加依赖" width="620px" class="app-dep-dlg" :close-on-click-modal="false" destroy-on-close>
    <el-form label-width="96px" @submit.prevent>
      <el-form-item label="来源">
        <el-radio-group v-model="mode">
          <el-radio-button value="CREATE">新建实例</el-radio-button>
          <el-radio-button value="BIND">绑定已有实例</el-radio-button>
          <el-radio-button value="EXTERNAL">外部连接</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="依赖名" required>
        <el-input v-model="key" maxlength="20" spellcheck="false" placeholder="如 order-db（创建后不可改，引用时写成 ${order-db.host}）" />
        <div v-if="keyHint" class="app-dep-dlg__err">{{ keyHint }}</div>
      </el-form-item>

      <!-- 新建 -->
      <template v-if="mode === 'CREATE'">
        <div v-loading="catalogLoading">
          <el-form-item label="中间件" required>
            <el-select v-model="code" placeholder="选择中间件类型" style="width: 100%" @change="applyCode">
              <el-option v-for="c in creatable" :key="c.code" :label="c.name + (itemReady(c) ? '' : '（组件未就绪）')" :value="c.code" :disabled="!itemReady(c)" />
            </el-select>
            <div class="app-dep-dlg__tip">实例建在本应用的命名空间 {{ namespace }}；Milvus 需要对象存储来源，请先在中间件商店创建，再用「绑定已有实例」。</div>
          </el-form-item>
          <template v-if="item">
            <el-form-item v-if="item.modes.length" label="形态">
              <el-radio-group v-model="shape" @change="onShape">
                <el-radio v-for="m in item.modes" :key="m.mode" :value="m.mode">{{ m.label }}</el-radio>
              </el-radio-group>
              <div v-if="modeInfo?.help" class="app-dep-dlg__tip">{{ modeInfo.help }}</div>
            </el-form-item>
            <el-form-item v-if="item.versions?.length" label="版本">
              <el-select v-model="version" style="width: 100%">
                <el-option v-for="v in item.versions" :key="v" :label="v" :value="v" />
              </el-select>
            </el-form-item>
            <el-form-item v-for="f in fields" :key="f.key" :label="f.key === 'replicas' ? (modeInfo?.replicasLabel || f.label) : f.label">
              <div class="app-dep-dlg__row">
                <el-input-number
                  v-model="values[f.key]"
                  :disabled="f.key === 'replicas' && fixedReplicas !== null"
                  :min="f.key === 'replicas' ? (fixedReplicas ?? minReplicas) : unitMin(f)"
                  :max="f.max ?? 9999"
                  :step="f.step ?? 1"
                  :precision="f.key === 'replicas' || f.unit === 'size' ? 0 : 2"
                  controls-position="right"
                />
                <span v-if="f.unit === 'cpu'">核</span>
                <el-select v-else-if="f.unit === 'size'" :model-value="units[f.key]" style="width: 80px" @update:model-value="(u: string) => changeUnit(f, u as 'Mi' | 'Gi')">
                  <el-option label="Mi" value="Mi" />
                  <el-option label="Gi" value="Gi" />
                </el-select>
              </div>
              <div v-if="f.help" class="app-dep-dlg__tip">{{ f.help }}</div>
            </el-form-item>
            <el-form-item label="StorageClass">
              <el-select v-model="storageClass" clearable placeholder="集群默认" style="width: 100%">
                <el-option v-for="sc in storageClasses" :key="sc" :label="sc" :value="sc" />
              </el-select>
            </el-form-item>
          </template>
        </div>
      </template>

      <!-- 绑定 -->
      <template v-else-if="mode === 'BIND'">
        <el-form-item label="实例" required>
          <el-select v-model="instanceId" v-loading="instLoading" filterable placeholder="选择本命名空间的实例" style="width: 100%">
            <el-option v-for="i in bindable" :key="i.id" :label="instLabel(i)" :value="i.id" />
          </el-select>
          <div class="app-dep-dlg__tip">只列同一集群、同一命名空间（{{ namespace }}）里还没被本应用使用的实例。绑定只建立引用，移除依赖时不会动实例。</div>
          <div v-if="!instLoading && !bindable.length" class="app-dep-dlg__tip">没有可绑定的实例。</div>
        </el-form-item>
      </template>

      <!-- 外部 -->
      <AppExternalForm v-else ref="extForm" v-model="ext" :ctx="secretCtx" />

      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">添加</el-button>
    </template>
  </el-dialog>
</template>

<style>
/* el-dialog 内部节点没有 data-v-*，scoped 打不到；唯一前缀 app-dep-dlg */
.app-dep-dlg .app-dep-dlg__err { color: var(--el-color-danger); font-size: 12px; line-height: 1.5; }
.app-dep-dlg .app-dep-dlg__tip { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.app-dep-dlg .app-dep-dlg__row { display: flex; align-items: center; gap: 8px; width: 100%; }
.app-dep-dlg__opt-hint { float: right; margin-left: 12px; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
