<script setup lang="ts">
/**
 * 「挂载」页签：卷定义与挂载点分开，直接复用工作负载编辑器的 VolumeEditor / VolumeMountEditor，
 * 支持与工作负载页相同的全部卷类型（emptyDir / ConfigMap / Secret / PVC / hostPath /
 * projected / downwardAPI / CSI / NFS）。hostPath 只给平台管理员（后端 AppHostPathGuard 把关）。
 *
 * 候选下拉（ConfigMap / Secret / PVC）来自工作负载编辑器的资源选项单例，按顶栏上下文取数；
 * 服务页在切到本页签时会把命名空间对齐到应用所在命名空间（ApplicationServiceView）。
 */
import { computed } from 'vue'
import VolumeEditor from '@/components/workload/VolumeEditor.vue'
import VolumeMountEditor from '@/components/workload/VolumeMountEditor.vue'
import type { AppServiceDesired } from '@/types'

const props = defineProps<{
  form: AppServiceDesired
  disabled?: boolean
}>()

/** 挂载点下拉的候选：本次定义的卷名 */
const volumeNames = computed(() => (props.form.volumes ?? []).map((v) => v.name).filter(Boolean))
</script>

<template>
  <fieldset class="app-vols" :disabled="disabled" :class="{ 'is-readonly': disabled }">
    <div class="app-vols__hint">
      先定义卷，再把它挂到容器路径；卷与挂载点靠名称对应。持久化的数据卷（会建 PVC）不在这里配置，这里只挂已有的对象或临时目录。
    </div>
    <div class="app-vols__block">
      <div class="app-vols__title">卷（volumes）</div>
      <VolumeEditor v-model="form.volumes" />
    </div>
    <div class="app-vols__block">
      <div class="app-vols__title">挂载点（volumeMounts）</div>
      <VolumeMountEditor v-model="form.volumeMounts" :volume-names="volumeNames" />
    </div>
  </fieldset>
</template>

<style>
.app-vols { width: 100%; padding: 0; margin: 0; border: 0; }
.app-vols.is-readonly { pointer-events: none; opacity: .65; }
.app-vols__hint { margin-bottom: 16px; color: var(--text-3); font-size: 12.5px; line-height: 1.6; }
.app-vols__block { margin-bottom: 22px; }
.app-vols__title { margin-bottom: 8px; font-weight: 600; }
</style>
