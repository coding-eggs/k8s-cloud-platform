<script setup lang="ts">
import type { LabelSelector, NodeSelectorRequirement, ProjectedSource } from '@/types/workload'
import DownwardAPIFilesEditor from './DownwardAPIFilesEditor.vue'
import LabelEditor from './LabelEditor.vue'
import StringMapEditor from './StringMapEditor.vue'
import { ensureModelList } from './modelList'
import { useResourceOptions } from '@/composables/useResourceOptions'

const { options } = useResourceOptions()

/** projected 卷的投影来源列表编辑器（官方六种：serviceAccountToken / configMap / secret / downwardAPI / clusterTrustBundle / podCertificate，六选一） */
const model = defineModel<ProjectedSource[] | null>()

type SourceKind = 'serviceAccountToken' | 'configMap' | 'secret' | 'downwardAPI' | 'clusterTrustBundle' | 'podCertificate'

/** Pod 证书投影的合法密钥类型（K8s 校验枚举） */
const KEY_TYPES = ['RSA3072', 'RSA4096', 'ECDSAP256', 'ECDSAP384', 'ECDSAP521', 'ED25519']

/** 标签选择器表达式操作符（label selector 不支持 Gt/Lt，区别于 nodeSelector） */
const LABEL_OPS = ['In', 'NotIn', 'Exists', 'DoesNotExist']

function blankSource(): ProjectedSource {
  return { serviceAccountToken: { audience: null, expirationSeconds: null, path: '' }, configMap: null, secret: null, downwardAPI: null, clusterTrustBundle: null, podCertificate: null }
}

function add(): void {
  ensureModelList(model).push(blankSource())
}
function remove(i: number): void {
  model.value?.splice(i, 1)
}

/** 当前选中的来源类型（按非空子对象推断；互斥，同 VolumeEditor type 约定） */
function kindOf(s: ProjectedSource): SourceKind | '' {
  if (s.serviceAccountToken) return 'serviceAccountToken'
  if (s.configMap) return 'configMap'
  if (s.secret) return 'secret'
  if (s.downwardAPI) return 'downwardAPI'
  if (s.clusterTrustBundle) return 'clusterTrustBundle'
  if (s.podCertificate) return 'podCertificate'
  return ''
}

/** 本地暂存：按来源行身份缓存各类型已填数据，切走再切回可恢复。
 *  model 始终只保留当前一个来源（A2 不变式）；loadDetail 换成新对象时旧缓存自然失效、不串数据 */
type Stash = Partial<Pick<ProjectedSource, 'serviceAccountToken' | 'configMap' | 'secret' | 'downwardAPI' | 'clusterTrustBundle' | 'podCertificate'>>
const stash = new Map<ProjectedSource, Stash>()
function st(s: ProjectedSource): Stash {
  let m = stash.get(s)
  if (!m) { m = {}; stash.set(s, m) }
  return m
}

/** 切换某来源类型：先暂存当前来源，再初始化目标（从缓存恢复或默认），清空其它（互斥） */
function setKind(s: ProjectedSource, kind: SourceKind): void {
  const m = st(s)
  if (kindOf(s) !== kind) {
    if (s.serviceAccountToken) m.serviceAccountToken = s.serviceAccountToken
    if (s.configMap) m.configMap = s.configMap
    if (s.secret) m.secret = s.secret
    if (s.downwardAPI) m.downwardAPI = s.downwardAPI
    if (s.clusterTrustBundle) m.clusterTrustBundle = s.clusterTrustBundle
    if (s.podCertificate) m.podCertificate = s.podCertificate
  }
  s.serviceAccountToken = null; s.configMap = null; s.secret = null
  s.downwardAPI = null; s.clusterTrustBundle = null; s.podCertificate = null
  if (kind === 'serviceAccountToken') s.serviceAccountToken = m.serviceAccountToken ?? { audience: null, expirationSeconds: null, path: '' }
  else if (kind === 'configMap') s.configMap = m.configMap ?? { name: '', items: null, optional: null }
  else if (kind === 'secret') s.secret = m.secret ?? { name: '', items: null, optional: null }
  else if (kind === 'downwardAPI') s.downwardAPI = m.downwardAPI ?? { items: [] }
  else if (kind === 'clusterTrustBundle') s.clusterTrustBundle = m.clusterTrustBundle ?? { name: null, signerName: null, labelSelector: null, optional: null, path: '' }
  else s.podCertificate = m.podCertificate ?? { signerName: '', keyType: null, credentialBundlePath: null, keyPath: null, certificateChainPath: null, maxExpirationSeconds: null, userAnnotations: null }
}

/** configMap / secret 的 items（KeyToPath）多行增删 */
function addItem(s: ProjectedSource, kind: 'configMap' | 'secret'): void {
  const sub = s[kind]
  if (!sub) return
  const items = sub.items ?? (sub.items = [])
  items.push({ key: '', path: '', mode: null })
}
function removeItem(s: ProjectedSource, kind: 'configMap' | 'secret', j: number): void {
  s[kind]?.items?.splice(j, 1)
}

/** clusterTrustBundle.labelSelector：确保对象存在后取 matchExpressions */
function ctbExprs(s: ProjectedSource): NodeSelectorRequirement[] {
  const c = s.clusterTrustBundle!
  const sel: LabelSelector = c.labelSelector ?? (c.labelSelector = {})
  return sel.matchExpressions ?? (sel.matchExpressions = [])
}
/** 初始化 labelSelector.matchLabels（LabelEditor 要求非空对象） */
function addCtbMatchLabels(s: ProjectedSource): void {
  const c = s.clusterTrustBundle!
  const sel = c.labelSelector ?? (c.labelSelector = {})
  sel.matchLabels = {}
}
function addCtbExpr(s: ProjectedSource): void {
  ctbExprs(s).push({ key: '', operator: 'In', values: [] })
}
function removeCtbExpr(s: ProjectedSource, j: number): void {
  s.clusterTrustBundle?.labelSelector?.matchExpressions?.splice(j, 1)
}
</script>

<template>
  <div class="proj-list">
    <div v-for="(s, i) in model" :key="i" class="proj-src">
      <div class="kv-row">
        <el-radio-group :model-value="kindOf(s)" @update:model-value="(k: string) => setKind(s, k as SourceKind)">
          <el-radio value="serviceAccountToken">serviceAccountToken</el-radio>
          <el-radio value="configMap">configMap</el-radio>
          <el-radio value="secret">secret</el-radio>
          <el-radio value="downwardAPI">downwardAPI</el-radio>
          <el-radio value="clusterTrustBundle">clusterTrustBundle</el-radio>
          <el-radio value="podCertificate">podCertificate</el-radio>
        </el-radio-group>
        <el-button link type="danger" @click="remove(i)">删除</el-button>
      </div>

      <!-- serviceAccountToken -->
      <div v-if="s.serviceAccountToken" class="kv-row sub">
        <el-input v-model="s.serviceAccountToken.audience" placeholder="audience（可选）" style="width: 150px" />
        <el-input-number v-model="s.serviceAccountToken.expirationSeconds" :min="0" controls-position="right" placeholder="expirationSeconds（可选）" style="width: 170px" />
        <el-input v-model="s.serviceAccountToken.path" placeholder="path（如 token，默认 token）" style="width: 160px" />
      </div>

      <!-- configMap -->
      <template v-else-if="s.configMap">
        <div class="kv-row sub">
          <el-select v-model="s.configMap.name" filterable allow-create default-first-option placeholder="ConfigMap 名称" style="width: 180px">
            <el-option v-for="n in options.configMaps" :key="n" :label="n" :value="n" />
          </el-select>
          <el-checkbox v-model="s.configMap.optional">optional</el-checkbox>
        </div>
        <div class="ktp-list">
          <div v-for="(it, j) in s.configMap.items ?? []" :key="j" class="kv-row ktp-row">
            <el-input v-model="it.key" placeholder="key" style="width: 120px" />
            <el-input v-model="it.path" placeholder="path" style="width: 140px" />
            <el-input-number v-model="it.mode" :min="0" controls-position="right" placeholder="mode（可选）" style="width: 130px" />
            <el-button link type="danger" @click="removeItem(s, 'configMap', j)">删除</el-button>
          </div>
          <el-button link type="primary" @click="addItem(s, 'configMap')">+ 添加 items 映射</el-button>
        </div>
      </template>

      <!-- secret -->
      <template v-else-if="s.secret">
        <div class="kv-row sub">
          <el-select v-model="s.secret.name" filterable allow-create default-first-option placeholder="Secret 名称" style="width: 180px">
            <el-option v-for="n in options.secrets" :key="n" :label="n" :value="n" />
          </el-select>
          <el-checkbox v-model="s.secret.optional">optional</el-checkbox>
        </div>
        <div class="ktp-list">
          <div v-for="(it, j) in s.secret.items ?? []" :key="j" class="kv-row ktp-row">
            <el-input v-model="it.key" placeholder="key" style="width: 120px" />
            <el-input v-model="it.path" placeholder="path" style="width: 140px" />
            <el-input-number v-model="it.mode" :min="0" controls-position="right" placeholder="mode（可选）" style="width: 130px" />
            <el-button link type="danger" @click="removeItem(s, 'secret', j)">删除</el-button>
          </div>
          <el-button link type="primary" @click="addItem(s, 'secret')">+ 添加 items 映射</el-button>
        </div>
      </template>

      <!-- downwardAPI -->
      <div v-else-if="s.downwardAPI" class="sub">
        <DownwardAPIFilesEditor v-model="s.downwardAPI.items" />
      </div>

      <!-- clusterTrustBundle（集群信任根 CA bundle，自动更新；name 与 signerName+labelSelector 互斥） -->
      <template v-else-if="s.clusterTrustBundle">
        <div class="kv-row sub">
          <el-input v-model="s.clusterTrustBundle.path" placeholder="path（如 ca-bundle.pem）" style="width: 170px" />
          <el-input v-model="s.clusterTrustBundle.name" placeholder="name（按名选择，与 signerName 互斥）" style="width: 200px" />
          <el-input v-model="s.clusterTrustBundle.signerName" placeholder="signerName（按签名者选择，与 name 互斥）" style="width: 200px" />
          <el-checkbox v-model="s.clusterTrustBundle.optional">optional</el-checkbox>
        </div>
        <div v-if="s.clusterTrustBundle.signerName" class="ktp-list">
          <span class="muted-label">labelSelector（仅设置 signerName 时生效）：</span>
          <div class="kv-row ktp-row">
            <span class="muted-label">matchLabels</span>
            <LabelEditor v-if="s.clusterTrustBundle.labelSelector?.matchLabels" v-model="s.clusterTrustBundle.labelSelector.matchLabels" />
            <el-button v-else link type="primary" @click="addCtbMatchLabels(s)">+ 添加 matchLabels</el-button>
          </div>
          <div v-for="(r, j) in s.clusterTrustBundle.labelSelector?.matchExpressions ?? []" :key="j" class="kv-row ktp-row">
            <el-input v-model="r.key" placeholder="key" style="width: 140px" />
            <el-select v-model="r.operator" style="width: 130px">
              <el-option v-for="op in LABEL_OPS" :key="op" :label="op" :value="op" />
            </el-select>
            <el-select
              v-if="r.operator !== 'Exists' && r.operator !== 'DoesNotExist'"
              v-model="r.values" multiple filterable allow-create default-first-option :reserve-keyword="false"
              placeholder="values（输入后回车）" style="flex: 1; min-width: 180px"
            />
            <el-button link type="danger" @click="removeCtbExpr(s, j)">删除</el-button>
          </div>
          <el-button link type="primary" @click="addCtbExpr(s)">+ 添加 matchExpression</el-button>
        </div>
      </template>

      <!-- podCertificate（Kubelet 代申请并自动轮换的 TLS 凭据；bundle 单文件与 key/chain 双文件二选一） -->
      <template v-else-if="s.podCertificate">
        <div class="kv-row sub">
          <el-input v-model="s.podCertificate.signerName" placeholder="signerName（必填）" style="width: 200px" />
          <el-select v-model="s.podCertificate.keyType" placeholder="keyType（必填）" style="width: 150px">
            <el-option v-for="k in KEY_TYPES" :key="k" :label="k" :value="k" />
          </el-select>
        </div>
        <div class="kv-row sub">
          <el-input v-model="s.podCertificate.credentialBundlePath" placeholder="credentialBundlePath（单文件 bundle：私钥+证书链，推荐）" style="width: 300px" />
        </div>
        <div class="kv-row sub">
          <el-input v-model="s.podCertificate.keyPath" placeholder="keyPath（与下项配套，非推荐）" style="width: 180px" />
          <el-input v-model="s.podCertificate.certificateChainPath" placeholder="certificateChainPath" style="width: 200px" />
        </div>
        <div class="kv-row sub">
          <el-input-number v-model="s.podCertificate.maxExpirationSeconds" :min="3600" :max="7862400" controls-position="right" placeholder="maxExpirationSeconds（可选，默认 86400）" style="width: 220px" />
        </div>
        <div class="ktp-list">
          <span class="muted-label">userAnnotations：</span>
          <StringMapEditor v-model="s.podCertificate.userAnnotations" />
        </div>
      </template>
    </div>
    <el-button link type="primary" @click="add">+ 添加投影来源</el-button>
  </div>
</template>

<style scoped>
.proj-list { width: 100% }
.proj-src { margin-bottom: 8px; padding: 6px; border: 1px dashed var(--el-border-color); border-radius: 4px }
.kv-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 6px }
.kv-row.sub { padding-left: 12px }
.ktp-list { padding-left: 12px }
.ktp-row { margin-bottom: 4px }
.muted-label { color: var(--el-text-color-secondary); font-size: 13px; white-space: nowrap }
</style>
