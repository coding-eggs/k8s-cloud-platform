<script setup lang="ts">
import type { VolumeDef } from '@/types/workload'

type VolumeType = VolumeDef['type']
type ItemSourceType = 'configMap' | 'secret'

const model = defineModel<VolumeDef[]>()

const HOST_PATH_TYPES = ['', 'Directory', 'File', 'Socket', 'CharDevice', 'BlockDevice', 'DirectoryOrCreate', 'FileOrCreate']

/** 切换 type：清空其它子对象、初始化当前子对象，保留 name（互斥，同 ProbeEditor A2） */
function setType(v: VolumeDef, t: VolumeType): void {
  v.type = t
  v.emptyDir = t === 'emptyDir' ? (v.emptyDir ?? { medium: '', sizeLimit: '' }) : null
  v.configMap = t === 'configMap' ? (v.configMap ?? { name: '' }) : null
  v.secret = t === 'secret' ? (v.secret ?? { secretName: '' }) : null
  v.persistentVolumeClaim = t === 'persistentVolumeClaim' ? (v.persistentVolumeClaim ?? { claimName: '' }) : null
  v.hostPath = t === 'hostPath' ? (v.hostPath ?? { path: '', type: null }) : null
}

function blankVolume(): VolumeDef {
  const v: VolumeDef = { name: '', type: 'emptyDir', emptyDir: null, configMap: null, secret: null, persistentVolumeClaim: null, hostPath: null }
  setType(v, 'emptyDir')
  return v
}

function add(): void {
  model.value ??= []
  model.value.push(blankVolume())
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
          <el-radio value="emptyDir">emptyDir</el-radio>
          <el-radio value="configMap">configMap</el-radio>
          <el-radio value="secret">secret</el-radio>
          <el-radio value="persistentVolumeClaim">persistentVolumeClaim</el-radio>
          <el-radio value="hostPath">hostPath</el-radio>
        </el-radio-group>
        <el-button link type="danger" @click="remove(i)">删除</el-button>
      </div>

      <!-- emptyDir -->
      <div v-if="v.emptyDir" class="vol-sub">
        <el-select v-model="v.emptyDir.medium" style="width: 130px">
          <el-option label="空（节点磁盘）" value="" />
          <el-option label="Memory" value="Memory" />
        </el-select>
        <el-input v-model="v.emptyDir.sizeLimit" placeholder="sizeLimit（如 1Gi，可选）" style="width: 180px" />
      </div>

      <!-- configMap -->
      <template v-else-if="v.configMap">
        <div class="vol-sub">
          <el-input v-model="v.configMap.name" placeholder="ConfigMap 名称" style="width: 180px" />
        </div>
        <div class="ktp-list">
          <div v-for="(it, j) in v.configMap.items ?? []" :key="j" class="kv-row ktp-row">
            <el-input v-model="it.key" placeholder="key" style="width: 120px" />
            <el-input v-model="it.path" placeholder="path" style="width: 140px" />
            <el-input-number v-model="it.mode" :min="0" controls-position="right" placeholder="mode（可选）" style="width: 130px" />
            <el-button link type="danger" @click="removeItem(v, 'configMap', j)">删除</el-button>
          </div>
          <el-button link type="primary" @click="addItem(v, 'configMap')">+ 添加 items 映射</el-button>
        </div>
      </template>

      <!-- secret -->
      <template v-else-if="v.secret">
        <div class="vol-sub">
          <el-input v-model="v.secret.secretName" placeholder="Secret 名称" style="width: 180px" />
        </div>
        <div class="ktp-list">
          <div v-for="(it, j) in v.secret.items ?? []" :key="j" class="kv-row ktp-row">
            <el-input v-model="it.key" placeholder="key" style="width: 120px" />
            <el-input v-model="it.path" placeholder="path" style="width: 140px" />
            <el-input-number v-model="it.mode" :min="0" controls-position="right" placeholder="mode（可选）" style="width: 130px" />
            <el-button link type="danger" @click="removeItem(v, 'secret', j)">删除</el-button>
          </div>
          <el-button link type="primary" @click="addItem(v, 'secret')">+ 添加 items 映射</el-button>
        </div>
      </template>

      <!-- persistentVolumeClaim -->
      <div v-else-if="v.persistentVolumeClaim" class="vol-sub">
        <el-input v-model="v.persistentVolumeClaim.claimName" placeholder="PVC 名称（claimName）" style="width: 180px" />
      </div>

      <!-- hostPath -->
      <div v-else-if="v.hostPath" class="vol-sub">
        <el-input v-model="v.hostPath.path" placeholder="主机路径（如 /var/log）" style="width: 200px" />
        <el-select v-model="v.hostPath.type" style="width: 170px">
          <el-option v-for="x in HOST_PATH_TYPES" :key="x" :label="x === '' ? '未指定' : x" :value="x" />
        </el-select>
      </div>
    </div>
    <el-button class="add-row-btn" plain @click="add">+ 添加 Volume</el-button>
  </div>
</template>

<style scoped>
.kv-editor { width: 100% }
.vol-block { margin-bottom: 12px; padding: 8px; border: 1px dashed var(--el-border-color); border-radius: 4px }
.kv-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.vol-sub { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding-left: 12px }
.ktp-list { padding-left: 12px }
.ktp-row { margin-bottom: 4px }
.add-row-btn { width: 100% }
</style>
