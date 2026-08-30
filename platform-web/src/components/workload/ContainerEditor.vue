<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ContainerDef } from '@/types/workload'
import EnvEditor from './EnvEditor.vue'
import EnvFromEditor from './EnvFromEditor.vue'
import PortEditor from './PortEditor.vue'
import ResourcesEditor from './ResourcesEditor.vue'
import LifecycleEditor from './LifecycleEditor.vue'
import ProbeEditor from './ProbeEditor.vue'
import VolumeMountEditor from './VolumeMountEditor.vue'

const props = defineProps<{ isInit: boolean; volumeNames: string[] }>()

const model = defineModel<ContainerDef>()

/**
 * ContainerDef 各子字段的空安全可写访问器：
 * get 在 model 缺失时返回 undefined；set 仅在 model 存在时写回，避免 v-model 直接绑 model.xxx 的类型/崩溃问题。
 */
const name = computed<string>({
  get: () => model.value?.name ?? '',
  set: (v) => { if (model.value) model.value.name = v },
})
const image = computed({
  get: () => model.value?.image ?? undefined,
  set: (v) => { if (model.value) model.value.image = v },
})
const workingDir = computed({
  get: () => model.value?.workingDir ?? undefined,
  set: (v) => { if (model.value) model.value.workingDir = v },
})
const imagePullPolicy = computed({
  get: () => model.value?.imagePullPolicy ?? undefined,
  set: (v) => { if (model.value) model.value.imagePullPolicy = v },
})
const envs = computed({
  get: () => model.value?.envs ?? undefined,
  set: (v) => { if (model.value) model.value.envs = v },
})
const envFrom = computed({
  get: () => model.value?.envFrom ?? undefined,
  set: (v) => { if (model.value) model.value.envFrom = v },
})
const ports = computed({
  get: () => model.value?.ports ?? undefined,
  set: (v) => { if (model.value) model.value.ports = v },
})
const resources = computed({
  get: () => model.value?.resources ?? undefined,
  set: (v) => { if (model.value) model.value.resources = v },
})
const lifecycle = computed({
  get: () => model.value?.lifecycle ?? undefined,
  set: (v) => { if (model.value) model.value.lifecycle = v },
})
const livenessProbe = computed({
  get: () => model.value?.livenessProbe ?? undefined,
  set: (v) => { if (model.value) model.value.livenessProbe = v },
})
const readinessProbe = computed({
  get: () => model.value?.readinessProbe ?? undefined,
  set: (v) => { if (model.value) model.value.readinessProbe = v },
})
const startupProbe = computed({
  get: () => model.value?.startupProbe ?? undefined,
  set: (v) => { if (model.value) model.value.startupProbe = v },
})
const volumeMounts = computed({
  get: () => model.value?.volumeMounts ?? undefined,
  set: (v) => { if (model.value) model.value.volumeMounts = v },
})

/** command/args 多值：textarea 按行/逗号切分 → string[]（与 ProbeEditor exec command 同一做法） */
const commandText = computed<string>({
  get: () => model.value?.command?.join('\n') ?? '',
  set: (text) => { if (model.value) model.value.command = text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean) },
})
const argsText = computed<string>({
  get: () => model.value?.args?.join('\n') ?? '',
  set: (text) => { if (model.value) model.value.args = text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean) },
})

/** D2：image 含 :latest 且未设置 imagePullPolicy → 提示默认按 Always 拉取 */
const latestHint = computed(() => (model.value?.image ?? '').includes(':latest') && !model.value?.imagePullPolicy)

const PULL_POLICIES = ['IfNotPresent', 'Always', 'Never']

/** 默认展开基础信息分区 */
const activeSections = ref<string[]>(['base'])

/** B4：向父页面暴露 isValid()，委托给 ResourcesEditor（ref 缺失时默认 true） */
const resourcesRef = ref<InstanceType<typeof ResourcesEditor> | null>(null)
function isValid(): boolean {
  return resourcesRef.value?.isValid() ?? true
}
defineExpose({ isValid })
</script>

<template>
  <div class="container-editor">
    <el-collapse v-model="activeSections">
      <!-- 基础信息 -->
      <el-collapse-item title="基础信息" name="base">
        <div class="ce-row">
          <span class="ce-label">name</span>
          <el-input v-model="name" placeholder="容器名（DNS_LABEL）" style="width: 200px" />
        </div>
        <div class="ce-row">
          <span class="ce-label">image</span>
          <el-input v-model="image" placeholder="镜像（如 nginx:1.27）" style="width: 320px" />
        </div>
        <div v-if="latestHint" class="d2-hint">镜像 tag 为 :latest 且未设置 imagePullPolicy，将默认按 Always 拉取</div>
        <div class="ce-row">
          <span class="ce-label">command</span>
          <el-input v-model="commandText" type="textarea" :rows="2" placeholder="启动命令，每行一个（或逗号分隔）" style="flex: 1; min-width: 260px" />
        </div>
        <div class="ce-row">
          <span class="ce-label">args</span>
          <el-input v-model="argsText" type="textarea" :rows="2" placeholder="启动参数，每行一个（或逗号分隔）" style="flex: 1; min-width: 260px" />
        </div>
        <div class="ce-row">
          <span class="ce-label">workingDir</span>
          <el-input v-model="workingDir" placeholder="工作目录（可选）" style="width: 240px" />
        </div>
        <div class="ce-row">
          <span class="ce-label">imagePullPolicy</span>
          <el-select v-model="imagePullPolicy" clearable placeholder="默认" style="width: 160px">
            <el-option v-for="p in PULL_POLICIES" :key="p" :label="p" :value="p" />
          </el-select>
        </div>
      </el-collapse-item>

      <!-- 环境变量 -->
      <el-collapse-item title="环境变量 envs" name="envs">
        <EnvEditor v-model="envs" />
      </el-collapse-item>

      <el-collapse-item title="环境来源 envFrom" name="envFrom">
        <EnvFromEditor v-model="envFrom" />
      </el-collapse-item>

      <!-- 端口 -->
      <el-collapse-item title="端口 ports" name="ports">
        <PortEditor v-model="ports" />
      </el-collapse-item>

      <!-- 资源 -->
      <el-collapse-item title="资源 resources" name="resources">
        <ResourcesEditor ref="resourcesRef" v-model="resources" />
      </el-collapse-item>

      <!-- A1：init 容器不支持生命周期钩子与探针 -->
      <template v-if="!props.isInit">
        <el-collapse-item title="生命周期 lifecycle" name="lifecycle">
          <LifecycleEditor v-model="lifecycle" />
        </el-collapse-item>
        <el-collapse-item title="存活探针 livenessProbe" name="liveness">
          <ProbeEditor kind="liveness" v-model="livenessProbe" />
        </el-collapse-item>
        <el-collapse-item title="就绪探针 readinessProbe" name="readiness">
          <ProbeEditor kind="readiness" v-model="readinessProbe" />
        </el-collapse-item>
        <el-collapse-item title="启动探针 startupProbe" name="startup">
          <ProbeEditor kind="startup" v-model="startupProbe" />
        </el-collapse-item>
      </template>

      <!-- 卷挂载 -->
      <el-collapse-item title="卷挂载 volumeMounts" name="mounts">
        <VolumeMountEditor :volume-names="props.volumeNames" v-model="volumeMounts" />
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.container-editor { width: 100% }
.ce-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap }
.ce-label { width: 130px; flex-shrink: 0; font-size: 12px; color: var(--el-text-color-secondary) }
.d2-hint { margin: -4px 0 8px; padding-left: 138px; font-size: 12px; color: var(--el-color-warning) }
</style>
