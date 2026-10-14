<script setup lang="ts">
import { ref } from 'vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import type { K8sHrFilter, K8sHrMatch, K8sHrRule, K8sRouteBackendRef } from '@/types'

/**
 * HTTPRoute 的**规则表单**（matches / filters / backendRefs）。整体搬迁自原 `HttpRouteEditorView.vue` 的规则部分，
 * 逐字保留其建模范围与"未建模字段原样回传"的口径。
 *
 * <p><b>契约只有三个方法</b>（外壳对本文件内部结构一无所知，也**不持有** rules 状态）：
 * `setRules(raw)` 回填（DTO → 模型）、`toDto()` 取要写进 body 的 rules（模型 → DTO）、`validate()` 规则校验。
 * 换 kind 时外壳用 `v-if` 换掉整个组件，状态随之销毁 —— 这正是"切 kind 清专属字段"的实现方式。
 */
/** 规则模型（表单态）；DTO 的转换在 setRules / toDto 里做 */
const rules = ref<RuleRow[]>([newRule()])

/** 加载到的存量路由里本版不支持的 filter 类型（保存不影响它们，仅提示） */
const unsupportedFilterTypes = ref<string[]>([])


// ---------- 静态选项（搬迁） ----------
const METHODS = ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'CONNECT', 'OPTIONS', 'TRACE', 'PATCH'] as const
const PATH_TYPES = ['PathPrefix', 'Exact', 'RegularExpression'] as const
const MATCH_TYPES = ['Exact', 'RegularExpression'] as const
const PATH_MOD_TYPES = ['ReplaceFullPath', 'ReplacePrefixMatch'] as const
/** 本版建模的 filter 类型（与后端 HttpRouteConverter.MODELED_FILTER_TYPES 对齐） */
const FILTER_TYPES = [
  'RequestHeaderModifier',
  'ResponseHeaderModifier',
  'RequestRedirect',
  'URLRewrite',
  'RequestMirror',
  'ExtensionRef',
] as const

// ---------- 表单模型（搬迁） ----------
interface BackendRow {
  name: string
  namespace: string
  port: number | null
  weight: number | null
  /** UI 不编辑，仅回填透传 */
  group?: string | null
  kind?: string | null
}
interface KvRow { name: string; value: string; type: string }
interface MatchRow {
  pathType: string
  pathValue: string
  method: string
  headers: KvRow[]
  queryParams: KvRow[]
}
interface FilterRow {
  type: string
  // request/response header modifier 共用一组输入
  headerSet: { name: string; value: string }[]
  headerAdd: { name: string; value: string }[]
  headerRemove: string[]
  // RequestRedirect
  redirectScheme: string
  redirectHostname: string
  redirectPathType: string
  redirectPathValue: string
  redirectPort: number | null
  redirectStatusCode: number | null
  // URLRewrite
  rewriteHostname: string
  rewritePathType: string
  rewritePathValue: string
  // RequestMirror
  mirrorName: string
  mirrorNamespace: string
  mirrorPort: number | null
  mirrorPercent: number | null
  // ExtensionRef
  extGroup: string
  extKind: string
  extName: string
}
interface RuleRow {
  name: string
  matches: MatchRow[]
  filters: FilterRow[]
  backendRefs: BackendRow[]
}

function newBackend(): BackendRow { return { name: '', namespace: '', port: null, weight: null } }
function newMatch(): MatchRow { return { pathType: 'PathPrefix', pathValue: '/', method: '', headers: [], queryParams: [] } }
function newFilter(): FilterRow {
  return {
    type: 'RequestHeaderModifier',
    headerSet: [], headerAdd: [], headerRemove: [],
    redirectScheme: '', redirectHostname: '', redirectPathType: '', redirectPathValue: '', redirectPort: null, redirectStatusCode: null,
    rewriteHostname: '', rewritePathType: '', rewritePathValue: '',
    mirrorName: '', mirrorNamespace: '', mirrorPort: null, mirrorPercent: null,
    extGroup: '', extKind: '', extName: '',
  }
}
function newRule(): RuleRow { return { name: '', matches: [newMatch()], filters: [], backendRefs: [newBackend()] } }

// ---------- 增删（搬迁；form.rules → rules.value） ----------
function addRule(): void { rules.value.push(newRule()) }
function removeRule(i: number): void { rules.value.splice(i, 1) }
function addMatch(r: RuleRow): void { r.matches.push(newMatch()) }
function removeMatch(r: RuleRow, i: number): void { r.matches.splice(i, 1) }
function addFilter(r: RuleRow): void { r.filters.push(newFilter()) }
function removeFilter(r: RuleRow, i: number): void { r.filters.splice(i, 1) }
function addBackend(r: RuleRow): void { r.backendRefs.push(newBackend()) }
function removeBackend(r: RuleRow, i: number): void { r.backendRefs.splice(i, 1) }

// ---------- 回填映射（搬迁：DTO → 表格行） ----------
function fromBackend(b: K8sRouteBackendRef): BackendRow {
  return { name: b.name ?? '', namespace: b.namespace ?? '', port: b.port ?? null, weight: b.weight ?? null, group: b.group, kind: b.kind }
}
function fromMatch(m: K8sHrMatch): MatchRow {
  return {
    pathType: m.path?.type ?? 'PathPrefix',
    pathValue: m.path?.value ?? '/',
    method: m.method ?? '',
    headers: (m.headers ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '', type: h.type ?? 'Exact' })),
    queryParams: (m.queryParams ?? []).map((q) => ({ name: q.name ?? '', value: q.value ?? '', type: q.type ?? 'Exact' })),
  }
}
function fromFilter(f: K8sHrFilter): FilterRow {
  const row = newFilter()
  row.type = f.type ?? 'RequestHeaderModifier'
  // request/response header modifier 结构相同，按 type 取对应体
  const hf = row.type === 'ResponseHeaderModifier' ? f.responseHeaderModifier : f.requestHeaderModifier
  row.headerSet = (hf?.set ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '' }))
  row.headerAdd = (hf?.add ?? []).map((h) => ({ name: h.name ?? '', value: h.value ?? '' }))
  row.headerRemove = [...(hf?.remove ?? [])]
  if (f.requestRedirect) {
    row.redirectScheme = f.requestRedirect.scheme ?? ''
    row.redirectHostname = f.requestRedirect.hostname ?? ''
    row.redirectPathType = f.requestRedirect.path?.type ?? ''
    row.redirectPathValue = f.requestRedirect.path?.replaceFullPath ?? f.requestRedirect.path?.replacePrefixMatch ?? ''
    row.redirectPort = f.requestRedirect.port ?? null
    row.redirectStatusCode = f.requestRedirect.statusCode ?? null
  }
  if (f.urlRewrite) {
    row.rewriteHostname = f.urlRewrite.hostname ?? ''
    row.rewritePathType = f.urlRewrite.path?.type ?? ''
    row.rewritePathValue = f.urlRewrite.path?.replaceFullPath ?? f.urlRewrite.path?.replacePrefixMatch ?? ''
  }
  if (f.requestMirror) {
    row.mirrorName = f.requestMirror.backendRef?.name ?? ''
    row.mirrorNamespace = f.requestMirror.backendRef?.namespace ?? ''
    row.mirrorPort = f.requestMirror.backendRef?.port ?? null
    row.mirrorPercent = f.requestMirror.percent ?? null
  }
  if (f.extensionRef) {
    row.extGroup = f.extensionRef.group ?? ''
    row.extKind = f.extensionRef.kind ?? ''
    row.extName = f.extensionRef.name ?? ''
  }
  return row
}
function fromRule(r: K8sHrRule): RuleRow {
  return {
    name: r.name ?? '',
    matches: (r.matches ?? []).map(fromMatch),
    filters: (r.filters ?? []).map(fromFilter),
    backendRefs: (r.backendRefs ?? []).map(fromBackend),
  }
}

function cleanKv(rows: { name: string; value: string }[]): { name: string; value: string }[] {
  return rows.filter((r) => r.name.trim()).map((r) => ({ name: r.name.trim(), value: r.value }))
}

function buildFilter(f: FilterRow): K8sHrFilter | null {
  switch (f.type) {
    case 'RequestHeaderModifier':
    case 'ResponseHeaderModifier': {
      const body = {
        set: cleanKv(f.headerSet).length ? cleanKv(f.headerSet) : null,
        add: cleanKv(f.headerAdd).length ? cleanKv(f.headerAdd) : null,
        remove: f.headerRemove.map((s) => s.trim()).filter(Boolean).length
          ? f.headerRemove.map((s) => s.trim()).filter(Boolean) : null,
      }
      if (!body.set && !body.add && !body.remove) return null
      return f.type === 'RequestHeaderModifier'
        ? { type: f.type, requestHeaderModifier: body }
        : { type: f.type, responseHeaderModifier: body }
    }
    case 'RequestRedirect': {
      const path = f.redirectPathType
        ? {
            type: f.redirectPathType,
            replaceFullPath: f.redirectPathType === 'ReplaceFullPath' ? f.redirectPathValue : null,
            replacePrefixMatch: f.redirectPathType === 'ReplacePrefixMatch' ? f.redirectPathValue : null,
          }
        : null
      return {
        type: f.type,
        requestRedirect: {
          scheme: f.redirectScheme.trim() || null,
          hostname: f.redirectHostname.trim() || null,
          path,
          port: f.redirectPort,
          statusCode: f.redirectStatusCode,
        },
      }
    }
    case 'URLRewrite': {
      const path = f.rewritePathType
        ? {
            type: f.rewritePathType,
            replaceFullPath: f.rewritePathType === 'ReplaceFullPath' ? f.rewritePathValue : null,
            replacePrefixMatch: f.rewritePathType === 'ReplacePrefixMatch' ? f.rewritePathValue : null,
          }
        : null
      return { type: f.type, urlRewrite: { hostname: f.rewriteHostname.trim() || null, path } }
    }
    case 'RequestMirror': {
      if (!f.mirrorName.trim()) return null
      return {
        type: f.type,
        requestMirror: {
          backendRef: {
            name: f.mirrorName.trim(),
            namespace: f.mirrorNamespace.trim() || null,
            port: f.mirrorPort,
          },
          percent: f.mirrorPercent,
        },
      }
    }
    case 'ExtensionRef': {
      if (!f.extGroup.trim() || !f.extKind.trim() || !f.extName.trim()) return null
      return { type: f.type, extensionRef: { group: f.extGroup.trim(), kind: f.extKind.trim(), name: f.extName.trim() } }
    }
    default:
      return null
  }
}

// ---------- 外壳回填：DTO → 模型 ----------
/** 外壳回填时调用（顺带收集未建模 filter 类型用于提示） */
function setRules(raw: unknown): void {
  const list = (raw as K8sHrRule[] | null) ?? []
  rules.value = list.map(fromRule)
  if (!rules.value.length) rules.value = [newRule()]
  unsupportedFilterTypes.value = [
    ...new Set(
      list
        .flatMap((r) => r.filters ?? [])
        .map((f) => f.type ?? '')
        .filter((t) => t && !(FILTER_TYPES as readonly string[]).includes(t)),
    ),
  ]
}

// ---------- 外壳提交：模型 → DTO ----------
/** 外壳提交时调用：返回要写进 body 的 rules */
function toDto(): K8sHrRule[] {
  return rules.value.map((r) => ({
    name: r.name.trim() || null,
    matches: r.matches.map((m) => ({
      path: { type: m.pathType, value: m.pathValue.trim() || null },
      method: m.method || null,
      headers: m.headers.filter((h) => h.name.trim()).length
        ? m.headers.filter((h) => h.name.trim()).map((h) => ({ type: h.type, name: h.name.trim(), value: h.value }))
        : null,
      queryParams: m.queryParams.filter((q) => q.name.trim()).length
        ? m.queryParams.filter((q) => q.name.trim()).map((q) => ({ type: q.type, name: q.name.trim(), value: q.value }))
        : null,
    })),
    filters: r.filters.map(buildFilter).filter((f): f is K8sHrFilter => f !== null).length
      ? r.filters.map(buildFilter).filter((f): f is K8sHrFilter => f !== null)
      : null,
    backendRefs: r.backendRefs
      .filter((b) => b.name.trim())
      .map((b) => ({
        name: b.name.trim(),
        namespace: b.namespace.trim() || null,
        port: b.port,
        weight: b.weight,
        group: b.group ?? null,
        kind: b.kind ?? null,
      })),
  }))
}

// ---------- 校验（只校验规则部分；名称 / 父资源 / 主机名在外壳） ----------
/** 本 kind 的规则校验：null = 通过 */
function validate(): string | null {
  if (!rules.value.length) return '至少需要一条规则'
  for (const [ri, r] of rules.value.entries()) {
    const no = `规则 #${ri + 1}`
    const backends = r.backendRefs.filter((b) => b.name.trim())
    if (!backends.length) return `${no}：至少需要一个后端（backendRefs）`
    for (const b of backends) {
      if (b.port == null || b.port < 1 || b.port > 65535) return `${no}：后端「${b.name}」的端口须为 1–65535`
      if (b.weight != null && (b.weight < 0 || b.weight > 1000000)) return `${no}：后端「${b.name}」的权重须为 0–1000000`
    }
    const types = r.filters.map((f) => f.type)
    if (new Set(types).size !== types.length) return `${no}：同一过滤器类型只能出现一次`
    if (types.includes('RequestRedirect') && types.includes('URLRewrite')) {
      return `${no}：RequestRedirect 与 URLRewrite 互斥，只能选一个`
    }
  }
  return null
}

defineExpose({ validate, setRules, toDto })
</script>


<template>
  <el-alert
    v-if="unsupportedFilterTypes.length"
    type="info"
    show-icon
    :closable="false"
    class="unsupported-alert"
    :title="`本条路由含本版不建模的过滤器：${unsupportedFilterTypes.join('、')}`"
    description="它们不会因本次编辑丢失（后端会原样保留），但本页看不到也改不了 —— 需要时请在列表页的 YAML 标签里查看全量。"
  />

        <el-divider content-position="left">规则（rules）</el-divider>

        <div v-for="(r, ri) in rules" :key="ri" class="sub-block rule-block">
          <div class="sub-head">
            <span class="sub-title">规则 #{{ ri + 1 }}</span>
            <el-button v-if="rules.length > 1" link type="danger" @click="removeRule(ri)">删除规则</el-button>
          </div>

          <el-form-item label="规则名">
            <el-input v-model="r.name" placeholder="可选（本路由内唯一）" style="max-width: 300px" />
            <FieldHelp tip="规则的名称，仅用于可读性与状态引用。留空即可。" />
          </el-form-item>

          <!-- 匹配 -->
          <div class="sub-sub-title">匹配条件（matches；多条之间是 OR，同一条内各维度 AND）</div>
          <div v-for="(m, mi) in r.matches" :key="mi" class="sub-sub-block">
            <div class="sub-head">
              <span class="sub-title">匹配 #{{ mi + 1 }}</span>
              <el-button v-if="r.matches.length > 1" link type="danger" @click="removeMatch(r, mi)">删除</el-button>
            </div>
            <el-form-item label="路径类型">
              <el-select v-model="m.pathType" style="width: 200px">
                <el-option v-for="t in PATH_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
              <FieldHelp tip="PathPrefix = 前缀匹配（/api 命中 /api、/api/v1）；Exact = 完全相等；RegularExpression = 正则（实现可选支持，不支持时该条匹配会被忽略并报 ResolvedRefs=False）。" />
            </el-form-item>
            <el-form-item label="路径值">
              <el-input v-model="m.pathValue" placeholder="如 /api" style="max-width: 300px" />
              <FieldHelp tip="配合路径类型使用。留空表示不按路径匹配。" />
            </el-form-item>
            <el-form-item label="方法">
              <el-select v-model="m.method" clearable placeholder="留空 = 任意方法" style="width: 200px">
                <el-option v-for="mm in METHODS" :key="mm" :label="mm" :value="mm" />
              </el-select>
              <FieldHelp tip="HTTP 方法匹配。留空表示不限方法。" />
            </el-form-item>
            <el-form-item label="请求头">
              <div class="list-rows">
                <div v-for="(h, hi) in m.headers" :key="hi" class="list-row">
                  <el-input v-model="h.name" placeholder="头名，如 X-Api-Version" style="flex: 1" />
                  <el-select v-model="h.type" style="width: 170px">
                    <el-option v-for="t in MATCH_TYPES" :key="t" :label="t" :value="t" />
                  </el-select>
                  <el-input v-model="h.value" placeholder="值" style="flex: 1" />
                  <el-button link type="danger" @click="m.headers.splice(hi, 1)">删除</el-button>
                </div>
                <el-button size="small" @click="m.headers.push({ name: '', value: '', type: 'Exact' })">添加请求头匹配</el-button>
              </div>
              <FieldHelp tip="按请求头匹配（头名大小写不敏感）。多条之间是 AND。Exact = 值完全相等；RegularExpression = 值按正则匹配。" />
            </el-form-item>
            <el-form-item label="查询参数">
              <div class="list-rows">
                <div v-for="(q, qi) in m.queryParams" :key="qi" class="list-row">
                  <el-input v-model="q.name" placeholder="参数名" style="flex: 1" />
                  <el-select v-model="q.type" style="width: 170px">
                    <el-option v-for="t in MATCH_TYPES" :key="t" :label="t" :value="t" />
                  </el-select>
                  <el-input v-model="q.value" placeholder="值" style="flex: 1" />
                  <el-button link type="danger" @click="m.queryParams.splice(qi, 1)">删除</el-button>
                </div>
                <el-button size="small" @click="m.queryParams.push({ name: '', value: '', type: 'Exact' })">添加查询参数匹配</el-button>
              </div>
              <FieldHelp tip="按 URL 查询参数匹配（?k=v）。多条之间是 AND。" />
            </el-form-item>
          </div>
          <el-button size="small" @click="addMatch(r)">添加匹配</el-button>

          <!-- 过滤器 -->
          <div class="sub-sub-title">过滤器（filters；同一类型只能出现一次，RequestRedirect 与 URLRewrite 互斥）</div>
          <div v-for="(f, fi) in r.filters" :key="fi" class="sub-sub-block">
            <div class="sub-head">
              <span class="sub-title">过滤器 #{{ fi + 1 }}</span>
              <el-button link type="danger" @click="removeFilter(r, fi)">删除</el-button>
            </div>
            <el-form-item label="类型">
              <el-select v-model="f.type" style="width: 260px">
                <el-option v-for="t in FILTER_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
              <FieldHelp tip="RequestHeaderModifier / ResponseHeaderModifier：增删改请求/响应头。RequestRedirect：整请求重定向。URLRewrite：改写 Host/路径后再转发。RequestMirror：把副本另发一份到镜像后端。ExtensionRef：交给实现私有的过滤器 CRD。" />
            </el-form-item>

            <template v-if="f.type === 'RequestHeaderModifier' || f.type === 'ResponseHeaderModifier'">
              <el-form-item label="设置（set）">
                <div class="list-rows">
                  <div v-for="(h, hi) in f.headerSet" :key="hi" class="list-row">
                    <el-input v-model="h.name" placeholder="头名" style="flex: 1" />
                    <el-input v-model="h.value" placeholder="值" style="flex: 1" />
                    <el-button link type="danger" @click="f.headerSet.splice(hi, 1)">删除</el-button>
                  </div>
                  <el-button size="small" @click="f.headerSet.push({ name: '', value: '' })">添加 set</el-button>
                </div>
                <FieldHelp tip="覆盖式写入：头已存在则替换，不存在则新增。" />
              </el-form-item>
              <el-form-item label="追加（add）">
                <div class="list-rows">
                  <div v-for="(h, hi) in f.headerAdd" :key="hi" class="list-row">
                    <el-input v-model="h.name" placeholder="头名" style="flex: 1" />
                    <el-input v-model="h.value" placeholder="值" style="flex: 1" />
                    <el-button link type="danger" @click="f.headerAdd.splice(hi, 1)">删除</el-button>
                  </div>
                  <el-button size="small" @click="f.headerAdd.push({ name: '', value: '' })">添加 add</el-button>
                </div>
                <FieldHelp tip="追加式写入：保留已有同名头，额外再加一个。" />
              </el-form-item>
              <el-form-item label="删除（remove）">
                <el-select v-model="f.headerRemove" multiple filterable allow-create default-first-option placeholder="回车添加要删除的头名" style="max-width: 420px" />
                <FieldHelp tip="按头名移除（不看值）。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'RequestRedirect'">
              <el-form-item label="协议（scheme）">
                <el-select v-model="f.redirectScheme" clearable placeholder="留空 = 不变" style="width: 200px">
                  <el-option label="http" value="http" />
                  <el-option label="https" value="https" />
                </el-select>
                <FieldHelp tip="重定向目标协议。留空表示保持原协议。常见用法：http → https 跳转。" />
              </el-form-item>
              <el-form-item label="主机名">
                <el-input v-model="f.redirectHostname" placeholder="留空 = 不变" style="max-width: 300px" />
                <FieldHelp tip="重定向目标主机名（需精确主机名，不能用通配）。" />
              </el-form-item>
              <el-form-item label="路径重写">
                <el-select v-model="f.redirectPathType" clearable placeholder="留空 = 不变" style="width: 200px">
                  <el-option v-for="t in PATH_MOD_TYPES" :key="t" :label="t" :value="t" />
                </el-select>
                <el-input v-if="f.redirectPathType" v-model="f.redirectPathValue" placeholder="替换值，如 /new" style="max-width: 240px; margin-left: 8px" />
                <FieldHelp tip="ReplaceFullPath = 整段路径替换；ReplacePrefixMatch = 只替换前缀匹配到的那一段（常用于 /old → /new 保留后缀）。" />
              </el-form-item>
              <el-form-item label="端口">
                <el-input-number v-model="f.redirectPort" :min="1" :max="65535" controls-position="right" placeholder="不变" style="width: 160px" />
                <FieldHelp tip="重定向目标端口。留空表示保持原端口。" />
              </el-form-item>
              <el-form-item label="状态码">
                <el-select v-model="f.redirectStatusCode" clearable placeholder="缺省 302" style="width: 160px">
                  <el-option :value="301" label="301 永久" />
                  <el-option :value="302" label="302 临时" />
                </el-select>
                <FieldHelp tip="只能 301 或 302，缺省 302（临时重定向）。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'URLRewrite'">
              <el-form-item label="主机名">
                <el-input v-model="f.rewriteHostname" placeholder="留空 = 不变" style="max-width: 300px" />
                <FieldHelp tip="改写转发给后端时的 Host 头（不是重定向）。与路径重写至少给一个。" />
              </el-form-item>
              <el-form-item label="路径重写">
                <el-select v-model="f.rewritePathType" clearable placeholder="留空 = 不变" style="width: 200px">
                  <el-option v-for="t in PATH_MOD_TYPES" :key="t" :label="t" :value="t" />
                </el-select>
                <el-input v-if="f.rewritePathType" v-model="f.rewritePathValue" placeholder="替换值，如 /" style="max-width: 240px; margin-left: 8px" />
                <FieldHelp tip="转发前改写路径。ReplacePrefixMatch 适合把 /api 前缀剥掉再转给后端。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'RequestMirror'">
              <el-form-item label="镜像后端 · 名称" required>
                <el-input v-model="f.mirrorName" placeholder="Service 名" style="max-width: 300px" />
                <FieldHelp tip="副本流量另发一份到这个后端；原请求仍按 backendRefs 正常转发。" />
              </el-form-item>
              <el-form-item label="镜像后端 · 命名空间">
                <el-input v-model="f.mirrorNamespace" placeholder="留空 = 本命名空间" style="max-width: 300px" />
                <FieldHelp tip="跨命名空间镜像需目标命名空间有 ReferenceGrant。" />
              </el-form-item>
              <el-form-item label="镜像后端 · 端口">
                <el-input-number v-model="f.mirrorPort" :min="1" :max="65535" controls-position="right" style="width: 160px" />
                <FieldHelp tip="目标 Service 的端口号。" />
              </el-form-item>
              <el-form-item label="镜像比例（percent）">
                <el-input-number v-model="f.mirrorPercent" :min="0" :max="100" controls-position="right" placeholder="缺省全部" style="width: 160px" />
                <FieldHelp tip="0–100，只镜像这个百分比的请求。留空表示全部镜像 —— 生产上建议给个小比例，避免镜像后端被打满。" />
              </el-form-item>
            </template>

            <template v-else-if="f.type === 'ExtensionRef'">
              <el-form-item label="引用 · group" required>
                <el-input v-model="f.extGroup" placeholder="如 gateway.envoyproxy.io" style="max-width: 300px" />
                <FieldHelp tip="被引用过滤器 CRD 的 group（必填）。" />
              </el-form-item>
              <el-form-item label="引用 · kind" required>
                <el-input v-model="f.extKind" placeholder="如 HTTPRouteFilter" style="max-width: 300px" />
                <FieldHelp tip="被引用过滤器 CRD 的 kind（必填）。" />
              </el-form-item>
              <el-form-item label="引用 · 名称" required>
                <el-input v-model="f.extName" placeholder="对象名" style="max-width: 300px" />
                <FieldHelp tip="被引用对象名（必填，需与本 Route 同命名空间）。" />
              </el-form-item>
            </template>
          </div>
          <el-button size="small" @click="addFilter(r)">添加过滤器</el-button>

          <!-- 后端 -->
          <div class="sub-sub-title">后端（backendRefs；weight 非百分比，占比 = weight / 本规则权重和）</div>
          <div v-for="(b, bi) in r.backendRefs" :key="bi" class="list-row backend-row">
            <el-input v-model="b.name" placeholder="Service 名" style="flex: 1" />
            <el-input v-model="b.namespace" placeholder="命名空间（留空=本 ns）" style="width: 190px" />
            <el-input-number v-model="b.port" :min="1" :max="65535" controls-position="right" placeholder="端口" style="width: 140px" />
            <el-input-number v-model="b.weight" :min="0" :max="1000000" controls-position="right" placeholder="权重" style="width: 140px" />
            <el-button v-if="r.backendRefs.length > 1" link type="danger" @click="removeBackend(r, bi)">删除</el-button>
          </div>
          <el-button size="small" @click="addBackend(r)">添加后端</el-button>
          <FieldHelp tip="后端 Service 名 + 端口（必填）+ 权重。权重是相对值不是百分比：两个后端各 50 与各 1 的效果相同；某个后端权重 0 表示不向它转发（常用于灰度占位）。默认 kind=Service。" />
        </div>
        <el-button size="small" @click="addRule">添加规则</el-button>

</template>

<style scoped>
/* 规则块用到的样式；与外壳 scoped 样式各自一份（scoped 不共享，是刻意的：不引入跨组件耦合） */
.unsupported-alert { margin-bottom: 16px; }
.sub-block {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px 14px 2px;
  margin-bottom: 12px;
  background: var(--panel-hover);
}
.rule-block { background: var(--panel); }
.sub-sub-block {
  border: 1px dashed var(--border);
  border-radius: 8px;
  padding: 10px 12px 2px;
  margin-bottom: 12px;
}
.sub-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.sub-sub-title { font-size: 12px; color: var(--text-3); margin: 12px 0 8px; }
.list-rows { display: flex; flex-direction: column; gap: 8px; width: 100%; max-width: 620px; }
.list-row { display: flex; align-items: center; gap: 10px; }
.backend-row { max-width: 780px; margin-bottom: 8px; }
</style>
