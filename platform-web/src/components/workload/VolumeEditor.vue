<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { VolumeDef } from '@/types/workload'
import DownwardAPIFilesEditor from './DownwardAPIFilesEditor.vue'
import ProjectedSourcesEditor from './ProjectedSourcesEditor.vue'
import StringMapEditor from './StringMapEditor.vue'
import FieldHelp from "@/components/workload/FieldHelp.vue";
import { ensureModelList } from './modelList'
import { useResourceOptions } from '@/composables/useResourceOptions'
import { configMapApi, secretApi } from '@/api'
import { useResourceContext } from '@/stores/context'

const { options } = useResourceOptions()
const { state } = useResourceContext()

type VolumeType = VolumeDef['type']
type ItemSourceType = 'configMap' | 'secret'

const model = defineModel<VolumeDef[]>()

const HOST_PATH_TYPES = ['', 'Directory', 'File', 'Socket', 'CharDevice', 'BlockDevice', 'DirectoryOrCreate', 'FileOrCreate']

/** 各卷类型作用说明（FieldHelp，详细） */
const T_EMPTYDIR = 'Pod 调度到节点时由 kubelet 创建的空目录；同一 Pod 内多个容器可共享读写。容器崩溃重启数据不丢，但 Pod 被删除后数据永久清除。medium=Memory 时用内存承载（tmpfs），不落盘、速度更快但受内存限制。'
const T_CONFIGMAP = '把 ConfigMap 中的键值以文件形式挂载进容器，用于注入非机密配置。items 可挑选部分 key 并自定义文件名/权限；不填 items 则默认每个 key 各生成一个文件。'
const T_SECRET = '把 Secret（密码、token、TLS 证书等敏感数据）以文件形式注入容器。相比 ConfigMap 更受保护，建议配合 readOnly 与最小化 items。'
const T_PVC = '挂载已有 PVC 指向的持久化存储卷；数据独立于 Pod 生命周期，Pod 重建或漂移后数据保留。需先在集群中创建好对应的 PVC。'
const T_HOSTPATH = '直接挂载宿主机上的文件/目录进容器，可访问节点本地资源（日志、设备文件等）。依赖具体节点、不具备可移植性，且权限较高有安全风险，慎用。type 用于校验路径类型（如 Directory、File）。'
const T_PROJECTED = '把多个来源（ServiceAccountToken、ConfigMap、Secret、DownwardAPI、ClusterTrustBundle、PodCertificate）合并投影到同一目录；常用来集中挂载 token/配置，避免为每个来源单独建卷。'
const T_DOWNWARDAPI = '把 Pod 自身的元数据（标签、注解、IP、资源限额等）以文件形式注入容器，无需调用 API 即可读取自身信息。fieldRef 读字段，resourceFieldRef 读资源量。'
const T_CSI = '通过 CSI（Container Storage Interface）驱动挂载第三方存储；具体挂载逻辑由集群中安装的 CSI 驱动实现。driver 指定驱动名，volumeAttributes 传驱动自定义参数，nodePublishSecretRef 可选地提供节点侧凭证。'
const T_NFS = '挂载 NFS 网络共享目录，跨节点共享读写，适合无状态共享文件场景；性能受网络影响，readOnly 可设为只读。'
/** items 映射按钮说明（configMap / secret 共用） */
const T_ITEMS = '把源对象中的指定 key 映射为容器内的单个文件：key=源里的键，path=容器内文件名，mode=文件权限（八进制，如 600）。不添加则默认整个对象的所有 key 各生成一个文件。'

/** 各类型子字段说明（FieldHelp） */
const T_MEDIUM = '介质：空（节点磁盘）= 落在节点本地磁盘；Memory = 用内存 tmpfs 承载，读写更快但受内存限制，Pod 删除即清空。'
const T_SIZELIMIT = '限制该 emptyDir 卷可占用的最大容量（单位 Gi）。超过后写入会失败；留空表示不限制。'
const T_CM_NAME = '要挂载的 ConfigMap 名称，其键值会以文件形式注入容器。'
const T_SECRET_NAME = '要挂载的 Secret 名称，敏感数据（密码 / token / 证书）以文件形式注入容器。'
const T_PVC_CLAIM = '要挂载的已有 PVC 名称；数据独立于 Pod 生命周期。需先在集群中创建好该 PVC。'
const T_HOSTPATH_PATH = '宿主机上要挂载进容器的文件/目录路径（如 /var/log）。依赖具体节点、可移植性差，慎用。'
const T_HOSTPATH_TYPE = '校验主机路径的类型（Directory、File、Socket 等）；留空不校验。'
const T_DEFAULTMODE = '投影文件的默认权限（八进制数字，如 420 = rw-r--r--）。'
const T_CSI_DRIVER = 'CSI 驱动名（如 ebs.csi.aws.com），由集群中安装的 CSI 驱动实现具体挂载逻辑。'
const T_CSI_FSTYPE = '卷的文件系统类型（如 ext4）；留空由驱动决定。'
const T_READONLY = '是否以只读方式挂载该卷。'
const T_NFS_SERVER = 'NFS 服务器的地址（IP 或域名）。'
const T_NFS_PATH = '要挂载的 NFS 共享目录路径（如 /export）。'

/** emptyDir.sizeLimit：数字 + 固定单位 Gi（与 ResourcesEditor 同一做法，只能填数字）。
 *  model 存字节（number|null）；显示换算成 Gi，输入换算回字节。 */
function sizeLimitToGi(bytes?: number | null): number | null {
  if (bytes == null || Number.isNaN(bytes)) return null
  return bytes / 2 ** 30
}
function setGiLimit(v: VolumeDef, n: number | string | null | undefined): void {
  if (!v.emptyDir) return
  const s = (n == null ? '' : String(n)).trim()
  v.emptyDir.sizeLimit = (s === '' || Number.isNaN(Number(s))) ? null : Number(s) * 2 ** 30
}

/** items.key 下拉：按「命名空间/资源名」缓存各 ConfigMap / Secret 的 key 列表，惰性拉取。
 *  拉取失败置空数组（下拉仍 allow-create 可手填，不破坏已有配置回显） */
const cmKeyCache = reactive<Record<string, string[]>>({})
const secKeyCache = reactive<Record<string, string[]>>({})

function ctx3(): { tenantId: string; clusterId: string; namespace: string } {
  return { tenantId: state.tenantId!, clusterId: state.clusterId!, namespace: state.namespace! }
}
async function loadCmKeys(name: string): Promise<void> {
  if (!name || !state.namespace) return   // 上下文未就绪 → 不缓存，等就绪后重试
  const k = `${state.namespace}/${name}`
  if (cmKeyCache[k] !== undefined) return
  try {
    const cm = await configMapApi.get(name, ctx3())
    cmKeyCache[k] = Object.keys({ ...(cm.data ?? {}), ...(cm.binaryData ?? {}) })
  } catch { cmKeyCache[k] = [] }
}
async function loadSecKeys(name: string): Promise<void> {
  if (!name || !state.namespace) return
  const k = `${state.namespace}/${name}`
  if (secKeyCache[k] !== undefined) return
  try {
    const s = await secretApi.get(name, ctx3())
    secKeyCache[k] = Object.keys(s.data ?? {})
  } catch { secKeyCache[k] = [] }
}
/** 模板取 key 选项（命名空间未就绪或尚未拉取 → 空数组，下拉仍可手填） */
function cmKeyOptions(name?: string): string[] {
  if (!name) return []
  return cmKeyCache[`${state.namespace}/${name}`] ?? []
}
function secKeyOptions(name?: string): string[] {
  if (!name) return []
  return secKeyCache[`${state.namespace}/${name}`] ?? []
}
/** 回填 / 上下文变化时，为已选中的 configMap/secret 预取 key */
function preloadKeys(v: VolumeDef): void {
  if (v.configMap?.name) void loadCmKeys(v.configMap.name)
  if (v.secret?.secretName) void loadSecKeys(v.secret.secretName)
}
watch(model, (vols) => { for (const v of vols ?? []) preloadKeys(v) }, { immediate: true })
// 上下文（命名空间）就绪 / 切换后，为已选中的 configMap/secret 补拉 key（挂载时可能尚未就绪）
watch(
  () => [state.tenantId, state.clusterId, state.namespace],
  () => { for (const v of model.value ?? []) preloadKeys(v) },
)

/** csi 的 nodePublishSecretRef 在模板里直接绑定 .name，须保证对象存在（后端可能省略 → null） */
function normalizeCsi(vols?: VolumeDef[]): void {
  for (const v of vols ?? []) {
    if (v.type === 'csi' && v.csi && !v.csi.nodePublishSecretRef) {
      v.csi.nodePublishSecretRef = { name: '' }
    }
  }
}
watch(model, normalizeCsi, { immediate: true })

/** 本地暂存：按卷对象身份缓存各子类型已填数据，切走再切回可恢复。
 *  model 始终只保留当前 type 的子对象（提交/转换器不变）；loadDetail 换成新对象时旧缓存自然失效、不串数据 */
type Stash = Partial<Pick<VolumeDef, 'emptyDir' | 'configMap' | 'secret' | 'persistentVolumeClaim' | 'hostPath' | 'projected' | 'downwardAPI' | 'csi' | 'nfs'>>
const stash = new Map<VolumeDef, Stash>()
function st(v: VolumeDef): Stash {
  let m = stash.get(v)
  if (!m) { m = {}; stash.set(v, m) }
  return m
}

/** 切换 type：先暂存当前子对象，再初始化目标（从缓存恢复或默认），清空其它 */
function setType(v: VolumeDef, t: VolumeType): void {
  const m = st(v)
  if (v.type !== t) {
    if (v.emptyDir) m.emptyDir = v.emptyDir
    if (v.configMap) m.configMap = v.configMap
    if (v.secret) m.secret = v.secret
    if (v.persistentVolumeClaim) m.persistentVolumeClaim = v.persistentVolumeClaim
    if (v.hostPath) m.hostPath = v.hostPath
    if (v.projected) m.projected = v.projected
    if (v.downwardAPI) m.downwardAPI = v.downwardAPI
    if (v.csi) m.csi = v.csi
    if (v.nfs) m.nfs = v.nfs
  }
  v.type = t
  v.emptyDir = null; v.configMap = null; v.secret = null; v.persistentVolumeClaim = null
  v.hostPath = null; v.projected = null; v.downwardAPI = null; v.csi = null; v.nfs = null
  if (t === 'emptyDir') v.emptyDir = m.emptyDir ?? { medium: '', sizeLimit: null }
  else if (t === 'configMap') v.configMap = m.configMap ?? { name: '' }
  else if (t === 'secret') v.secret = m.secret ?? { secretName: '' }
  else if (t === 'persistentVolumeClaim') v.persistentVolumeClaim = m.persistentVolumeClaim ?? { claimName: '' }
  else if (t === 'hostPath') v.hostPath = m.hostPath ?? { path: '', type: null }
  else if (t === 'projected') v.projected = m.projected ?? { defaultMode: null, sources: [] }
  else if (t === 'downwardAPI') v.downwardAPI = m.downwardAPI ?? { defaultMode: null, items: [] }
  else if (t === 'csi') v.csi = m.csi ?? { driver: '', readOnly: false, fsType: null, volumeAttributes: {}, nodePublishSecretRef: { name: '' } }
  else if (t === 'nfs') v.nfs = m.nfs ?? { server: '', path: '', readOnly: false }
}

function blankVolume(): VolumeDef {
  const v: VolumeDef = { name: '', type: 'emptyDir', emptyDir: null, configMap: null, secret: null, persistentVolumeClaim: null, hostPath: null }
  setType(v, 'emptyDir')
  return v
}

function add(): void {
  ensureModelList(model).push(blankVolume())
}

function remove(i: number): void {
  model.value?.splice(i, 1)
}

/** KeyToPath（configMap/secret items）多行增删 */
function addItem(v: VolumeDef, t: ItemSourceType): void {
  const sub = v[t]
  if (!sub) return
  const items = sub.items ?? (sub.items = [])
  items.push({ key: '', path: '', mode: null })
}

function removeItem(v: VolumeDef, t: ItemSourceType, j: number): void {
  v[t]?.items?.splice(j, 1)
}
</script>

<template>
  <div class="kv-editor">
    <div v-for="(v, i) in model" :key="i" class="vol-block">
      <div class="kv-row">
        <el-input v-model="v.name" placeholder="Volume 名称" style="width: 160px" />
        <el-radio-group :model-value="v.type" @update:model-value="(t: VolumeType) => setType(v, t)">
          <el-radio value="emptyDir">EmptyDir<FieldHelp :tip="T_EMPTYDIR" /></el-radio>
          <el-radio value="configMap">ConfigMap<FieldHelp :tip="T_CONFIGMAP" /></el-radio>
          <el-radio value="secret">Secret<FieldHelp :tip="T_SECRET" /></el-radio>
          <el-radio value="persistentVolumeClaim">PersistentVolumeClaim<FieldHelp :tip="T_PVC" /></el-radio>
          <el-radio value="hostPath">HostPath<FieldHelp :tip="T_HOSTPATH" /></el-radio>
          <el-radio value="projected">Projected<FieldHelp :tip="T_PROJECTED" /></el-radio>
          <el-radio value="downwardAPI">DownwardAPI<FieldHelp :tip="T_DOWNWARDAPI" /></el-radio>
          <el-radio value="csi">CSI<FieldHelp :tip="T_CSI" /></el-radio>
          <el-radio value="nfs">NFS<FieldHelp :tip="T_NFS" /></el-radio>
        </el-radio-group>
        <el-button link type="danger" @click="remove(i)">删除</el-button>
      </div>

      <!-- emptyDir -->
      <div v-if="v.emptyDir" class="vol-fields">
        <div class="vol-field">
          <span class="vol-label">介质 <FieldHelp :tip="T_MEDIUM" /></span>
          <el-select v-model="v.emptyDir.medium" style="width: 160px">
            <el-option label="空（节点磁盘）" value="" />
            <el-option label="Memory" value="Memory" />
          </el-select>
        </div>
        <div class="vol-field">
          <span class="vol-label">大小上限 <FieldHelp :tip="T_SIZELIMIT" /></span>
          <el-input :model-value="sizeLimitToGi(v.emptyDir.sizeLimit)"
                    @update:model-value="(n: number | string | null) => setGiLimit(v, n)"
                    placeholder="可选" style="width: 180px"
                    :formatter="(val: string) => val.replace(/[^\d.]/g, '')"
                    :parser="(val: string) => val.replace(/[^\d.]/g, '')">
            <template #append>Gi</template>
          </el-input>
        </div>
      </div>

      <!-- configMap -->
      <template v-else-if="v.configMap">
        <div class="vol-fields">
          <div class="vol-field">
            <span class="vol-label">ConfigMap <FieldHelp :tip="T_CM_NAME" /></span>
            <el-select v-model="v.configMap.name" filterable allow-create default-first-option placeholder="ConfigMap 名称" style="width: 200px" @change="(n: string) => loadCmKeys(n)">
              <el-option v-for="n in options.configMaps" :key="n" :label="n" :value="n" />
            </el-select>
          </div>
        </div>
        <div class="ktp-list">
          <div v-for="(it, j) in v.configMap.items ?? []" :key="j" class="kv-row ktp-row">
            <el-select v-model="it.key" filterable allow-create default-first-option placeholder="key" style="width: 140px">
              <el-option v-for="k in cmKeyOptions(v.configMap.name)" :key="k" :label="k" :value="k" />
            </el-select>
            <el-input v-model="it.path" placeholder="path" style="width: 140px" />
            <el-input-number v-model="it.mode" :min="0" controls-position="right" placeholder="mode（可选）" style="width: 150px" />
            <el-button link type="danger" @click="removeItem(v, 'configMap', j)">删除</el-button>
          </div>
          <el-button link type="primary" @click="addItem(v, 'configMap')">+ 添加 items 映射<FieldHelp :tip="T_ITEMS" /></el-button>
        </div>
      </template>

      <!-- secret -->
      <template v-else-if="v.secret">
        <div class="vol-fields">
          <div class="vol-field">
            <span class="vol-label">Secret <FieldHelp :tip="T_SECRET_NAME" /></span>
            <el-select v-model="v.secret.secretName" filterable allow-create default-first-option placeholder="Secret 名称" style="width: 200px" @change="(n: string) => loadSecKeys(n)">
              <el-option v-for="n in options.secrets" :key="n" :label="n" :value="n" />
            </el-select>
          </div>
        </div>
        <div class="ktp-list">
          <div v-for="(it, j) in v.secret.items ?? []" :key="j" class="kv-row ktp-row">
            <el-select v-model="it.key" filterable allow-create default-first-option placeholder="key" style="width: 140px">
              <el-option v-for="k in secKeyOptions(v.secret.secretName)" :key="k" :label="k" :value="k" />
            </el-select>
            <el-input v-model="it.path" placeholder="path" style="width: 140px" />
            <el-input-number v-model="it.mode" :min="0" controls-position="right" placeholder="mode（可选）" style="width: 130px" />
            <el-button link type="danger" @click="removeItem(v, 'secret', j)">删除</el-button>
          </div>
          <el-button link type="primary" @click="addItem(v, 'secret')">+ 添加 items 映射<FieldHelp :tip="T_ITEMS" /></el-button>
        </div>
      </template>

      <!-- persistentVolumeClaim -->
      <div v-else-if="v.persistentVolumeClaim" class="vol-fields">
        <div class="vol-field">
          <span class="vol-label">PVC <FieldHelp :tip="T_PVC_CLAIM" /></span>
          <el-select v-model="v.persistentVolumeClaim.claimName" filterable allow-create default-first-option placeholder="PVC 名称（claimName）" style="width: 200px">
            <el-option v-for="n in options.pvcs" :key="n" :label="n" :value="n" />
          </el-select>
        </div>
      </div>

      <!-- hostPath -->
      <div v-else-if="v.hostPath" class="vol-fields">
        <div class="vol-field">
          <span class="vol-label">主机路径 <FieldHelp :tip="T_HOSTPATH_PATH" /></span>
          <el-input v-model="v.hostPath.path" placeholder="如 /var/log" style="width: 240px" />
        </div>
        <div class="vol-field">
          <span class="vol-label">类型 <FieldHelp :tip="T_HOSTPATH_TYPE" /></span>
          <el-select v-model="v.hostPath.type" style="width: 180px">
            <el-option v-for="x in HOST_PATH_TYPES" :key="x" :label="x === '' ? '未指定' : x" :value="x" />
          </el-select>
        </div>
      </div>

      <!-- projected -->
      <template v-else-if="v.projected">
        <div class="vol-fields">
          <div class="vol-field">
            <span class="vol-label">默认权限 <FieldHelp :tip="T_DEFAULTMODE" /></span>
            <el-input-number v-model="v.projected.defaultMode" :min="0" controls-position="right" placeholder="如 420，可选" style="width: 180px" />
          </div>
        </div>
        <ProjectedSourcesEditor v-model="v.projected.sources" />
      </template>

      <!-- downwardAPI -->
      <template v-else-if="v.downwardAPI">
        <div class="vol-fields">
          <div class="vol-field">
            <span class="vol-label">默认权限 <FieldHelp :tip="T_DEFAULTMODE" /></span>
            <el-input-number v-model="v.downwardAPI.defaultMode" :min="0" controls-position="right" placeholder="如 420，可选" style="width: 180px" />
          </div>
        </div>
        <DownwardAPIFilesEditor v-model="v.downwardAPI.items" />
      </template>

      <!-- csi -->
      <template v-else-if="v.csi">
        <div class="vol-fields">
          <div class="vol-field">
            <span class="vol-label">驱动名 <FieldHelp :tip="T_CSI_DRIVER" /></span>
            <el-input v-model="v.csi.driver" placeholder="如 ebs.csi.aws.com" style="width: 240px" />
          </div>
          <div class="vol-field">
            <span class="vol-label">文件系统 <FieldHelp :tip="T_CSI_FSTYPE" /></span>
            <el-input v-model="v.csi.fsType" placeholder="如 ext4，可选" style="width: 200px" />
          </div>
          <div class="vol-field">
            <span class="vol-label">只读 <FieldHelp :tip="T_READONLY" /></span>
            <el-checkbox v-model="v.csi.readOnly">readOnly</el-checkbox>
          </div>
          <div class="vol-field">
            <span class="vol-label">节点凭证 <FieldHelp tip="nodePublishSecretRef：可选，提供节点侧挂载所需的 secret 名称。" /></span>
            <el-select v-model="v.csi.nodePublishSecretRef!.name" filterable allow-create default-first-option clearable placeholder="secret 名称（可选）" style="width: 200px">
              <el-option v-for="n in options.secrets" :key="n" :label="n" :value="n" />
            </el-select>
          </div>
        </div>
        <div class="ktp-list">
          <span class="muted-label">volumeAttributes：</span>
          <StringMapEditor v-model="v.csi.volumeAttributes" />
        </div>
      </template>

      <!-- nfs -->
      <div v-else-if="v.nfs" class="vol-fields">
        <div class="vol-field">
          <span class="vol-label">服务器地址 <FieldHelp :tip="T_NFS_SERVER" /></span>
          <el-input v-model="v.nfs.server" placeholder="IP 或域名" style="width: 240px" />
        </div>
        <div class="vol-field">
          <span class="vol-label">共享路径 <FieldHelp :tip="T_NFS_PATH" /></span>
          <el-input v-model="v.nfs.path" placeholder="如 /export" style="width: 200px" />
        </div>
        <div class="vol-field">
          <span class="vol-label">只读 <FieldHelp :tip="T_READONLY" /></span>
          <el-checkbox v-model="v.nfs.readOnly">readOnly</el-checkbox>
        </div>
      </div>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加 Volume</el-button>
  </div>
</template>

<style scoped>
.kv-editor {
  width: 100%
}
.vol-block {
  margin-bottom: 12px;
  padding: 8px;
  border: 1px dashed var(--el-border-color);
  border-radius: 4px
}
.kv-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
  flex-wrap: wrap
}
/* 各类型子字段：竖排，每行 = label(+FieldHelp) + 控件 */
.vol-fields { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px }
.vol-field { display: flex; align-items: center; gap: 12px }
.vol-label { width: 96px; flex-shrink: 0; font-size: 13px; color: var(--el-text-color-secondary); display: inline-flex; align-items: center; gap: 4px; white-space: nowrap }
.ktp-list {

}
.ktp-row {
  margin-bottom: 4px
}
.muted-label {
  color: var(--el-text-color-secondary);
  font-size: 13px; white-space: nowrap
}
.add-row-btn {
  width: 100%
}
</style>
