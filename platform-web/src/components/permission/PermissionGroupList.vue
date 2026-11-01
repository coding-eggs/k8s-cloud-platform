<script setup lang="ts">
/**
 * 权限点的分组列表（权限点页与角色管理页共用）。
 *
 * <p>一行 = 一个**权限码（code）**，展开后是该码覆盖的规则行。勾选单位也是 code（ANY-of），
 * 所以角色页把 checkbox 放在组头；权限点页不传 selectable，只在规则行上给编辑/删除。
 *
 * <p>搜索时：命中组自动展开（不改变用户手动的展开集合），命中字段高亮；
 * 清空搜索后恢复原来的展开状态。高亮用分段渲染，不拼 HTML。
 */
import type { PermissionGroup, PermissionRuleView } from '@/types'
import { computed, reactive } from 'vue'
import { usePermissionSearch } from '@/composables/usePermissionSearch'

const props = defineProps<{
  groups: PermissionGroup[]
  /** 搜索关键词（空格分隔 = AND）；为空则不高亮、不强制展开 */
  keyword?: string
  /** 组头显示 checkbox（角色管理页） */
  selectable?: boolean
  /** 已勾选的 code（selectable=true 时使用） */
  checkedCodes?: string[]
  /** 规则行显示编辑/删除（权限点页） */
  ruleActions?: boolean
  /** 全部展开（URL 反查结果：命中的组必须直接露出命中规则） */
  expandAll?: boolean
  /** 反查的实际路径：规则 resource 与之相等 → 精确匹配，否则是模式命中 */
  matchPath?: string
}>()

const emit = defineEmits<{
  (e: 'toggle-code', code: string): void
  (e: 'edit-rule', group: PermissionGroup, rule: PermissionRuleView): void
  (e: 'delete-rule', group: PermissionGroup, rule: PermissionRuleView): void
}>()

const keywordRef = computed(() => props.keyword ?? '')
const { active: searchActive, ruleMatches, segments } = usePermissionSearch(keywordRef)

/** 用户手动展开的组（按 code）；搜索期间强制全展开，但不动这个集合 */
const expanded = reactive(new Set<string>())

function isExpanded(code: string): boolean {
  return !!props.expandAll || searchActive.value || expanded.has(code)
}

function toggle(code: string): void {
  if (props.expandAll || searchActive.value) return // 搜索/反查期间由结果决定展开，避免"刚展开就被折叠"
  if (expanded.has(code)) expanded.delete(code)
  else expanded.add(code)
}

function isChecked(code: string): boolean {
  return (props.checkedCodes ?? []).includes(code)
}

const METHOD_CLASS: Record<string, string> = {
  GET: 'm-get',
  VIEW: 'm-get',
  POST: 'm-post',
  PUT: 'm-put',
  PATCH: 'm-put',
  DELETE: 'm-del',
  '*': 'm-all',
}

function methodClass(action?: string | null): string {
  return METHOD_CLASS[(action ?? '').toUpperCase()] ?? 'm-all'
}

function groupKey(g: PermissionGroup): string {
  return `${g.domain ?? 'API'}\u0000${g.code}`
}
</script>

<template>
  <div class="pgl">
    <div v-for="g in groups" :key="groupKey(g)" class="pgl-group">
      <div class="pgl-head" :class="{ 'is-open': isExpanded(g.code) }">
        <el-checkbox
          v-if="selectable"
          class="pgl-check"
          :model-value="isChecked(g.code)"
          @update:model-value="emit('toggle-code', g.code)"
        />
        <button type="button" class="pgl-toggle" @click="toggle(g.code)">
          <span class="pgl-caret">{{ isExpanded(g.code) ? '▾' : '▸' }}</span>
          <span class="pgl-code">
            <span
              v-for="(s, i) in segments(g.code)"
              :key="i"
              :class="{ 'is-hit': s.hit }"
            >{{ s.text }}</span>
          </span>
          <el-tag size="small" effect="plain" class="pgl-domain">{{ g.domain ?? 'API' }}</el-tag>
          <span class="pgl-count">{{ g.rules.length }} 条规则</span>
        </button>
      </div>

      <div v-if="isExpanded(g.code)" class="pgl-rules">
        <div
          v-for="r in g.rules"
          :key="r.id"
          class="pgl-rule"
          :class="{ 'is-hit-row': searchActive && ruleMatches(r) }"
        >
          <span class="pgl-method" :class="methodClass(r.action)">{{ r.action ?? '-' }}</span>
          <span class="pgl-path">
            <span
              v-for="(s, i) in segments(r.resource)"
              :key="i"
              :class="{ 'is-hit': s.hit }"
            >{{ s.text }}</span>
          </span>
          <span class="pgl-desc">
            <span
              v-for="(s, i) in segments(r.description)"
              :key="i"
              :class="{ 'is-hit': s.hit }"
            >{{ s.text }}</span>
          </span>
          <span v-if="ruleActions" class="pgl-ops">
            <el-button link type="primary" size="small" @click="emit('edit-rule', g, r)">编辑</el-button>
            <el-button link type="danger" size="small" @click="emit('delete-rule', g, r)">删除</el-button>
          </span>
          <span v-else-if="matchPath" class="pgl-why">
            {{ r.resource === matchPath ? '精确匹配' : '模式命中' }}
          </span>
        </div>
        <div v-if="!g.rules.length" class="pgl-empty">该权限码下暂无规则</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pgl {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--panel);
}
.pgl-group + .pgl-group {
  border-top: 1px solid var(--border);
}
.pgl-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px 0 10px;
}
.pgl-head.is-open {
  background: var(--panel-hover);
}
.pgl-check {
  flex-shrink: 0;
}
.pgl-toggle {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 0;
  border: 0;
  background: transparent;
  color: var(--text-1);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.pgl-caret {
  width: 10px;
  flex-shrink: 0;
  color: var(--text-3);
  font-size: 11px;
}
.pgl-code {
  font-family: ui-monospace, monospace;
  font-size: 12.5px;
  color: var(--accent);
  word-break: break-all;
}
.pgl-domain {
  flex-shrink: 0;
}
.pgl-count {
  margin-left: auto;
  flex-shrink: 0;
  color: var(--text-3);
  font-size: 11.5px;
}
.pgl-rules {
  padding: 0 12px 10px 43px;
}
.pgl-rule {
  display: grid;
  grid-template-columns: 54px minmax(180px, 1.1fr) minmax(140px, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 7px 6px;
  border-top: 1px dashed var(--border);
  border-radius: 4px;
}
.pgl-rule.is-hit-row {
  background: var(--accent-soft);
}
.pgl-method {
  font-size: 10.5px;
  font-weight: 600;
  text-align: center;
  padding: 1px 5px;
  border-radius: 4px;
}
.m-get {
  color: var(--success);
  background: color-mix(in srgb, var(--success) 12%, transparent);
}
.m-post {
  color: var(--info);
  background: color-mix(in srgb, var(--info) 12%, transparent);
}
.m-put {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 12%, transparent);
}
.m-del {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 12%, transparent);
}
.m-all {
  color: var(--text-2);
  background: var(--panel-hover);
}
.pgl-path {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  color: var(--text-1);
  word-break: break-all;
}
.pgl-desc {
  color: var(--text-3);
  font-size: 11.5px;
}
.is-hit {
  background: color-mix(in srgb, var(--warning) 22%, transparent);
  color: var(--text-1);
  border-radius: 3px;
  padding: 0 1px;
}
.pgl-ops {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.12s ease;
}
.pgl-why {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--warning);
  white-space: nowrap;
}
.pgl-rule:hover .pgl-ops,
.pgl-rule:focus-within .pgl-ops {
  opacity: 1;
}
.pgl-empty {
  padding: 10px 0;
  color: var(--text-3);
  font-size: 12px;
}

@media (max-width: 720px) {
  .pgl-rules {
    padding-left: 20px;
  }
  .pgl-rule {
    grid-template-columns: 46px minmax(0, 1fr);
    row-gap: 3px;
  }
  .pgl-desc,
  .pgl-ops {
    grid-column: 2;
  }
  .pgl-ops {
    opacity: 1;
  }
}
</style>
