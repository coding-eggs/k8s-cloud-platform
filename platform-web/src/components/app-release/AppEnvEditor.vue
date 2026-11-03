<script setup lang="ts">
/**
 * 应用服务的环境变量编辑器。
 *
 * 每行两种来源：
 * - 值：字面值，或含 `${依赖.字段}` 的引用 / 拼接（如 `mysql://${order-db.host}:${order-db.port}/orders`）；
 *   是否引用由后端按内容判定，前端只负责不写坏格式；
 * - Secret：已有 Secret 名 + 键。
 * 凭据请用 `${依赖.password}` 或 Secret，不要直接填明文（后端只对疑似明文给提示，不拦截）。
 *
 * 可引用字段按「依赖」和「有端口的其他服务」列出：值输入框聚焦（空值）或输入 `${` 时下拉可选，
 * 选中即填入 / 补全；拼接到字面值中间用「插入引用」（AppRefPicker，追加到末尾）。具体哪些字段有值取决于
 * 依赖的协议，引用了没有的字段时保存可以通过，发布前会被校验拦下。「从依赖导入」一次追加一组引用行（AppEnvImportDialog）。
 *
 * Secret 行的名与键从应用所在命名空间的 Secret 里选（useNamespaceSecrets，仍可手填），旁边给「新建 Secret」入口。
 */
import { computed, onMounted, ref, watch } from 'vue'
import type { AppDependencyRow, AppEnvRow } from '@/types'
import { REF, refGroups } from './dependencyUtil'
import { useNamespaceSecrets, type SecretCtx } from './useNamespaceSecrets'
import AppRefPicker from './AppRefPicker.vue'
import AppEnvImportDialog from './AppEnvImportDialog.vue'
import AppSecretCreateLink from './AppSecretCreateLink.vue'

const props = defineProps<{
  /** 可被引用的依赖（带登记信息，用来判断有哪些字段） */
  dependencies?: AppDependencyRow[]
  /** 可被引用的服务 key（仅有端口的，且不含自己） */
  serviceKeys?: string[]
  /** 应用所在的租户 / 集群 / 命名空间：Secret 候选从这里取；不传就只能手填 */
  ctx?: SecretCtx
  /** 只读：所有输入、下拉、按钮都真正禁用（不能靠 Tab 改） */
  disabled?: boolean
}>()

const model = defineModel<AppEnvRow[]>({ default: () => [] })

// ---------- 值：引用下拉 ----------
interface RefSuggestion { value: string; token: string; hint: string }
const refItems = computed(() => refGroups(props.dependencies, props.serviceKeys)
  .flatMap((g) => g.items.map((it) => ({ token: it.token, hint: `${g.label} · ${it.hint}` }))))

/**
 * 空值：列全部引用，选中 = 整个值就是这个引用；
 * 末尾有没闭合的 `${`：按其后的文字过滤，选中 = 把这段补全成完整引用（前面的字面值保留）；
 * 其它情况（在写字面值）不弹，免得打扰。
 */
function suggest(query: string, cb: (items: RefSuggestion[]) => void): void {
  const q = query ?? ''
  const open = q.lastIndexOf('${')
  const unclosed = open >= 0 && !q.slice(open).includes('}')
  if (q && !unclosed) { cb([]); return }
  const head = unclosed ? q.slice(0, open) : ''
  const tail = unclosed ? q.slice(open + 2).toLowerCase() : ''
  // 按「名称.字段」前缀或名称子串匹配；不按整串子串，否则 ${ord 会把所有 password 字段都带出来
  const hit = (token: string): boolean => {
    const body = token.slice(2, -1).toLowerCase()
    return body.startsWith(tail) || (body.split('.')[0] ?? '').includes(tail)
  }
  cb(refItems.value
    .filter((it) => !tail || hit(it.token))
    .map((it) => ({ value: head + it.token, token: it.token, hint: it.hint })))
}

// ---------- Secret 行：候选 ----------
const secretCtx = computed<SecretCtx>(() => props.ctx ?? { clusterId: '', namespace: '' })
const secrets = useNamespaceSecrets(secretCtx)
const hasSecretRow = computed(() => model.value.some((r) => r.kind === 'SECRET'))
// 有 Secret 行才拉（大多数服务用不上），上下文变了重拉
watch([hasSecretRow, () => secretCtx.value.clusterId, () => secretCtx.value.namespace], ([has], old) => {
  if (!has) return
  if (old && old[0] && old[1] === secretCtx.value.clusterId && old[2] === secretCtx.value.namespace) return
  void secrets.reload()
})
onMounted(() => { if (hasSecretRow.value) void secrets.reload() })

const importVisible = ref(false)
function appendImported(rows: AppEnvRow[]): void {
  model.value = [...model.value, ...rows]
}

function add(): void {
  model.value = [...model.value, { name: '', kind: 'LITERAL', value: '' }]
}
function remove(i: number): void {
  model.value = model.value.filter((_, idx) => idx !== i)
}
function patch(i: number, p: Partial<AppEnvRow>): void {
  model.value = model.value.map((r, idx) => (idx === i ? { ...r, ...p } : r))
}
function isSecret(r: AppEnvRow): boolean {
  return r.kind === 'SECRET'
}
function setSource(i: number, src: 'value' | 'secret'): void {
  if (src === 'secret') patch(i, { kind: 'SECRET', value: undefined, secretName: '', secretKey: '' })
  else patch(i, { kind: 'LITERAL', value: '', secretName: undefined, secretKey: undefined })
}
/** 换了 Secret：原来的键在新 Secret 里没有就清掉；新 Secret 只有一个键就直接选上 */
function pickSecret(i: number, name: string): void {
  const keys = secrets.keysOf(name)
  const cur = model.value[i]?.secretKey ?? ''
  const key = keys.includes(cur) ? cur : keys.length === 1 ? keys[0] : (keys.length ? '' : cur)
  patch(i, { secretName: name, secretKey: key })
}
function insertRef(i: number, token: string): void {
  const r = model.value[i]
  if (!r) return
  patch(i, { kind: 'REF', value: (r.value ?? '') + token })
}

/** 软校验：含 `${` 却不是完整的 `${x.y}`（后端会拒绝并给出同样的提示） */
function refProblem(r: AppEnvRow): string {
  const v = r.value ?? ''
  if (!v.includes('${')) return ''
  const stripped = v.replace(REF, '')
  return stripped.includes('${') ? '引用格式应为 ${依赖.字段}' : ''
}
function nameProblem(r: AppEnvRow, i: number): string {
  if (!r.name) return ''
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(r.name)) return '变量名只能含字母、数字、下划线，且不能以数字开头'
  if (model.value.some((o, idx) => idx !== i && o.name === r.name)) return '变量名重复'
  return ''
}
</script>

<template>
  <div class="app-env">
    <div v-if="!model.length" class="app-env__empty">还没有环境变量</div>
    <div v-for="(r, i) in model" :key="i" class="app-env__row">
      <div class="app-env__cell app-env__cell--name">
        <el-input :model-value="r.name" :disabled="disabled" placeholder="变量名，如 DB_URL" @update:model-value="(v: string) => patch(i, { name: v })" />
        <div v-if="nameProblem(r, i)" class="app-env__err">{{ nameProblem(r, i) }}</div>
      </div>
      <el-select
        :model-value="isSecret(r) ? 'secret' : 'value'"
        class="app-env__src"
        :disabled="disabled"
        placeholder="来源"
        @update:model-value="(v: 'value' | 'secret') => setSource(i, v)"
      >
        <el-option label="值 / 引用" value="value" />
        <el-option label="Secret" value="secret" />
      </el-select>
      <template v-if="isSecret(r)">
        <el-select
          :model-value="r.secretName"
          :disabled="disabled"
          :loading="secrets.loading.value"
          class="app-env__half"
          filterable
          allow-create
          default-first-option
          placeholder="选择或输入 Secret 名"
          no-data-text="本命名空间没有 Secret"
          @update:model-value="(v: string) => pickSecret(i, v)"
        >
          <el-option v-for="s in secrets.options.value" :key="s.name" :label="s.name" :value="s.name">
            <span>{{ s.name }}</span>
            <span class="app-env__opt-hint">{{ s.keys.length }} 个键</span>
          </el-option>
        </el-select>
        <el-select
          :model-value="r.secretKey"
          :disabled="disabled"
          class="app-env__half"
          filterable
          allow-create
          default-first-option
          placeholder="选择或输入键"
          :no-data-text="r.secretName ? '这个 Secret 没有键（或不在候选里，可手填）' : '先选 Secret'"
          @update:model-value="(v: string) => patch(i, { secretKey: v })"
        >
          <el-option v-for="k in secrets.keysOf(r.secretName)" :key="k" :label="k" :value="k" />
        </el-select>
      </template>
      <div v-else class="app-env__cell app-env__cell--value">
        <el-autocomplete
          :model-value="r.value ?? ''"
          :disabled="disabled"
          :fetch-suggestions="suggest"
          :debounce="0"
          class="app-env__auto"
          popper-class="app-env-ref-popper"
          placeholder="字面值；聚焦或输入 ${ 可选引用"
          @update:model-value="(v: string) => patch(i, { value: v, kind: v.includes('${') ? 'REF' : 'LITERAL' })"
        >
          <template #default="{ item }">
            <code>{{ item.token }}</code>
            <span class="app-env-ref-popper__hint">{{ item.hint }}</span>
          </template>
        </el-autocomplete>
        <div v-if="refProblem(r)" class="app-env__err">{{ refProblem(r) }}</div>
      </div>
      <AppRefPicker v-if="!isSecret(r)" :dependencies="dependencies" :service-keys="serviceKeys" :disabled="disabled" @pick="(t: string) => insertRef(i, t)" />
      <span v-else class="app-env__noref" />
      <el-button link type="danger" :disabled="disabled" @click="remove(i)">删除</el-button>
    </div>
    <div class="app-env__foot">
      <el-button :disabled="disabled" @click="add">+ 添加变量</el-button>
      <el-button :disabled="disabled || !(dependencies ?? []).length" :title="(dependencies ?? []).length ? '' : '应用还没有依赖'" @click="importVisible = true">从依赖导入</el-button>
      <span v-if="!(dependencies ?? []).length && !(serviceKeys ?? []).length" class="app-env__hint">暂无可引用的依赖或服务；依赖在应用页添加。</span>
      <span v-else class="app-env__hint">凭据请用 ${依赖.password} 或 Secret，不要直接写明文。</span>
      <template v-if="hasSecretRow && ctx">
        <span class="app-env__hint">Secret 取自命名空间 {{ ctx.namespace }}：</span>
        <AppSecretCreateLink :ctx="ctx" :loading="secrets.loading.value" :disabled="disabled" @refresh="secrets.reload" />
      </template>
    </div>
    <AppEnvImportDialog v-model="importVisible" :dependencies="dependencies ?? []" :existing-names="model.map((r) => r.name)" @import="appendImported" />
  </div>
</template>

<style scoped>
.app-env__row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 8px;
}
.app-env__cell--name { width: 220px; flex-shrink: 0; }
.app-env__cell--value { flex: 1; min-width: 0; }
.app-env__src { width: 130px; flex-shrink: 0; }
.app-env__half { flex: 1; min-width: 0; }
.app-env__auto { width: 100%; }
.app-env__opt-hint {
  float: right;
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.app-env__noref { width: 0; }
.app-env__err {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-color-danger);
}
.app-env__empty {
  padding: 8px 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.app-env__foot {
  display: flex;
  align-items: center;
  gap: 12px;
}
.app-env__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>

<style>
/* autocomplete 下拉挂在 body 下，scoped 打不到；唯一前缀 app-env-ref-popper */
.app-env-ref-popper__hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
