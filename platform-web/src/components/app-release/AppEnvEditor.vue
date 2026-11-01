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
 * 可引用字段按「依赖」和「有端口的其他服务」列出；具体哪些字段有值取决于依赖的协议，
 * 引用了没有的字段时保存可以通过，发布前会被校验拦下。
 */
import { computed } from 'vue'
import type { AppEnvRow } from '@/types'

const props = defineProps<{
  /** 可被引用的依赖 key */
  dependencyKeys?: string[]
  /** 可被引用的服务 key（仅有端口的，且不含自己） */
  serviceKeys?: string[]
}>()

const model = defineModel<AppEnvRow[]>({ default: () => [] })

const DEP_FIELDS = ['host', 'port', 'address', 'username', 'password', 'url']
const SVC_FIELDS = ['host', 'port', 'url']

const refGroups = computed(() => {
  const groups: { label: string; items: string[] }[] = []
  for (const k of props.dependencyKeys ?? []) {
    groups.push({ label: `依赖 ${k}`, items: DEP_FIELDS.map((f) => `\${${k}.${f}}`) })
  }
  for (const k of props.serviceKeys ?? []) {
    groups.push({ label: `服务 ${k}`, items: SVC_FIELDS.map((f) => `\${${k}.${f}}`) })
  }
  return groups
})

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
function insertRef(i: number, token: string): void {
  const r = model.value[i]
  if (!r) return
  patch(i, { kind: 'REF', value: (r.value ?? '') + token })
}

/** 软校验：含 `${` 却不是完整的 `${x.y}`（后端会拒绝并给出同样的提示） */
function refProblem(r: AppEnvRow): string {
  const v = r.value ?? ''
  if (!v.includes('${')) return ''
  const stripped = v.replace(/\$\{[a-z0-9-]+\.[a-z]+\}/g, '')
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
        <el-input :model-value="r.name" placeholder="变量名，如 DB_URL" @update:model-value="(v: string) => patch(i, { name: v })" />
        <div v-if="nameProblem(r, i)" class="app-env__err">{{ nameProblem(r, i) }}</div>
      </div>
      <el-select
        :model-value="isSecret(r) ? 'secret' : 'value'"
        class="app-env__src"
        placeholder="来源"
        @update:model-value="(v: 'value' | 'secret') => setSource(i, v)"
      >
        <el-option label="值 / 引用" value="value" />
        <el-option label="Secret" value="secret" />
      </el-select>
      <template v-if="isSecret(r)">
        <el-input :model-value="r.secretName" class="app-env__half" placeholder="Secret 名" @update:model-value="(v: string) => patch(i, { secretName: v })" />
        <el-input :model-value="r.secretKey" class="app-env__half" placeholder="键" @update:model-value="(v: string) => patch(i, { secretKey: v })" />
      </template>
      <div v-else class="app-env__cell app-env__cell--value">
        <el-input
          :model-value="r.value"
          placeholder="字面值，或 ${order-db.host} 这样的引用"
          @update:model-value="(v: string) => patch(i, { value: v, kind: v.includes('${') ? 'REF' : 'LITERAL' })"
        />
        <div v-if="refProblem(r)" class="app-env__err">{{ refProblem(r) }}</div>
      </div>
      <el-dropdown v-if="!isSecret(r)" trigger="click" :disabled="!refGroups.length" @command="(t: string) => insertRef(i, t)">
        <el-button :disabled="!refGroups.length">插入引用</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <template v-for="g in refGroups" :key="g.label">
              <el-dropdown-item disabled class="app-env__group">{{ g.label }}</el-dropdown-item>
              <el-dropdown-item v-for="t in g.items" :key="t" :command="t">{{ t }}</el-dropdown-item>
            </template>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <span v-else class="app-env__noref" />
      <el-button link type="danger" @click="remove(i)">删除</el-button>
    </div>
    <div class="app-env__foot">
      <el-button @click="add">+ 添加变量</el-button>
      <span v-if="!refGroups.length" class="app-env__hint">暂无可引用的依赖或服务；依赖在应用页添加。</span>
      <span v-else class="app-env__hint">凭据请用 ${依赖.password} 或 Secret，不要直接写明文。</span>
    </div>
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
/* el-dropdown 菜单挂在 body 下，scoped 打不到；唯一前缀 app-env__ */
.el-dropdown-menu__item.app-env__group {
  font-size: 12px;
  font-weight: 600;
  opacity: 1;
}
</style>
