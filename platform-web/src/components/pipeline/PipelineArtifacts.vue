<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { applicationApi, appServiceApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { usePermission } from '@/stores/permission'
import type { ApplicationView, AppOverview, PipelineRun } from '@/types'
import AppReleaseDialog from '@/components/app-release/AppReleaseDialog.vue'

interface Artifact { nodeName: string; image: string; digest: string }
const props = defineProps<{ run: PipelineRun }>()
const router = useRouter()
const perm = usePermission()
const artifacts = computed<Artifact[]>(() => JSON.parse(props.run.artifactsJson || '[]'))
const artifact = ref<Artifact>()
const apps = ref<ApplicationView[]>([])
const appId = ref('')
const service = ref('')
const overview = ref<AppOverview>()
const choosing = ref(false)
const preview = ref(false)
const repo = computed(() => artifact.value?.image.slice(0, artifact.value.image.lastIndexOf(':')))
const services = computed(() => overview.value?.services.filter(s =>
  s.desired?.imageRepo === repo.value && s.pending !== 'DELETED' && !s.canary) ?? [])

async function choose(item: Artifact) {
  artifact.value = item
  appId.value = ''
  service.value = ''
  overview.value = undefined
  apps.value = (await applicationApi.list(props.run.clusterId, props.run.tenantId ?? undefined))
    .filter(app => app.namespace === props.run.namespace)
  choosing.value = true
}
async function loadApp(id: string) {
  service.value = ''
  overview.value = undefined
  const result = await appServiceApi.overview(id, props.run.tenantId ?? undefined)
  if (id === appId.value) overview.value = result
}
</script>

<template>
  <section v-if="artifacts.length">
    <h3>构建产物</h3>
    <div v-for="item in artifacts" :key="item.nodeName">
      <strong>{{ item.nodeName }}</strong> <code>{{ item.image }}@{{ item.digest }}</code>
      <el-button v-if="run.status === 'SUCCEEDED' && perm.has(apiCodes.applicationWrite)"
                 link type="primary" @click="choose(item)">发布到应用</el-button>
    </div>
    <el-dialog v-model="choosing" title="选择发布目标" width="500px">
      <el-form label-width="64">
        <el-form-item label="应用"><el-select v-model="appId" filterable @change="loadApp">
          <el-option v-for="app in apps" :key="app.id" :value="app.id" :label="app.displayName || app.name" />
        </el-select></el-form-item>
        <el-form-item label="服务"><el-select v-model="service" :disabled="!overview" no-data-text="没有使用此镜像仓库的可发布服务">
          <el-option v-for="s in services" :key="s.key" :value="s.key" :label="s.key" />
        </el-select></el-form-item>
      </el-form>
      <template #footer><el-button type="primary" :disabled="!service" @click="choosing = false; preview = true">预览发布</el-button></template>
    </el-dialog>
    <AppReleaseDialog v-if="artifact && overview" v-model="preview" :app-id="appId" :tenant-id="run.tenantId ?? undefined"
      :services="overview.services" :pending-keys="overview.pendingKeys"
      :artifact="{ runName: run.runName, nodeName: artifact.nodeName, serviceKey: service, digest: artifact.digest }"
      @published="router.push({ name: 'application-detail', params: { id: appId } })" />
  </section>
</template>
