<script setup lang="ts">
import { computed } from 'vue'
import type { AppNode } from '@/types'

interface EnvRow {
  name: string
  value?: string
  /** true = 引用已有 Secret（此时只剩 secretName/secretKey） */
  secret?: boolean
  secretName?: string
  secretKey?: string
}

const props = defineProps<{ node: AppNode }>()
const emit = defineEmits<{ update: [params: Record<string, unknown>]; remove: [] }>()

const isService = computed(() => props.node.kind === 'SERVICE')
const env = computed<EnvRow[]>(() => (props.node.params.env as EnvRow[] | undefined) ?? [])

function set(key: string, value: unknown) {
  emit('update', { ...props.node.params, [key]: value })
}

function addEnv() {
  set('env', [...env.value, { name: '' }])
}

function removeEnv(index: number) {
  set('env', env.value.filter((_, i) => i !== index))
}

function setEnv(index: number, patch: Partial<EnvRow>) {
  set('env', env.value.map((row, i) => (i === index ? { ...row, ...patch } : row)))
}

function toggles(flag: string): boolean {
  return Boolean(props.node.params[flag])
}
</script>

<template>
  <div class="inspector">
    <div class="inspector__head">
      <div>
        <div class="inspector__title">{{ node.key }}</div>
        <div class="inspector__sub">{{ isService ? '服务' : '后台进程' }}</div>
      </div>
      <el-button link type="danger" @click="emit('remove')">移除节点</el-button>
    </div>

    <el-form label-width="86px" label-position="left" size="small">
      <el-form-item label="镜像" required>
        <el-input
          :model-value="String(node.params.image ?? '')"
          placeholder="nginx:1.27"
          @update:model-value="set('image', $event)"
        />
      </el-form-item>

      <el-form-item v-if="isService" label="容器端口">
        <el-input-number
          :model-value="Number(node.params.port ?? 8080)"
          :min="1"
          :max="65535"
          controls-position="right"
          @update:model-value="set('port', $event)"
        />
      </el-form-item>

      <el-form-item label="副本数">
        <el-input-number
          :model-value="Number(node.params.replicas ?? 1)"
          :min="1"
          :max="20"
          controls-position="right"
          :disabled="toggles('autoscalingEnabled')"
          @update:model-value="set('replicas', $event)"
        />
      </el-form-item>

      <el-form-item v-if="isService" label="暴露方式">
        <el-select
          :model-value="String(node.params.expose ?? 'none')"
          style="width: 100%"
          @update:model-value="set('expose', $event)"
        >
          <el-option label="仅集群内（ClusterIP）" value="none" />
          <el-option label="节点端口（NodePort）" value="nodeport" />
        </el-select>
      </el-form-item>

      <el-divider content-position="left">高级</el-divider>

      <el-form-item v-if="isService" label="健康探针">
        <el-switch
          :model-value="node.params.probeEnabled !== false"
          @update:model-value="set('probeEnabled', $event)"
        />
      </el-form-item>
      <el-form-item v-if="isService && node.params.probeEnabled !== false" label="探针路径">
        <el-input
          :model-value="String(node.params.probePath ?? '/')"
          @update:model-value="set('probePath', $event)"
        />
      </el-form-item>

      <el-form-item v-if="isService" label="持久化">
        <el-switch
          :model-value="toggles('persistenceEnabled')"
          @update:model-value="set('persistenceEnabled', $event)"
        />
      </el-form-item>
      <template v-if="isService && toggles('persistenceEnabled')">
        <el-form-item label="存储大小">
          <el-input
            :model-value="String(node.params.storageSize ?? '1Gi')"
            @update:model-value="set('storageSize', $event)"
          />
        </el-form-item>
        <el-form-item label="StorageClass">
          <el-input
            :model-value="String(node.params.storageClass ?? '')"
            placeholder="留空 = 默认存储类"
            @update:model-value="set('storageClass', $event)"
          />
        </el-form-item>
      </template>

      <el-form-item label="自动伸缩">
        <el-switch
          :model-value="toggles('autoscalingEnabled')"
          @update:model-value="set('autoscalingEnabled', $event)"
        />
      </el-form-item>
      <template v-if="toggles('autoscalingEnabled')">
        <el-form-item label="副本范围">
          <div class="range">
            <el-input-number
              :model-value="Number(node.params.minReplicas ?? 1)"
              :min="1" :max="50" controls-position="right"
              @update:model-value="set('minReplicas', $event)"
            />
            <span>—</span>
            <el-input-number
              :model-value="Number(node.params.maxReplicas ?? 10)"
              :min="1" :max="100" controls-position="right"
              @update:model-value="set('maxReplicas', $event)"
            />
          </div>
        </el-form-item>
        <el-form-item label="CPU 目标">
          <el-input-number
            :model-value="Number(node.params.targetCpu ?? 60)"
            :min="10" :max="95" controls-position="right"
            @update:model-value="set('targetCpu', $event)"
          />
        </el-form-item>
      </template>

      <el-form-item v-if="isService" label="监控采集">
        <el-switch
          :model-value="toggles('metricsEnabled')"
          @update:model-value="set('metricsEnabled', $event)"
        />
      </el-form-item>

      <el-divider content-position="left">环境变量</el-divider>

      <div v-for="(row, i) in env" :key="i" class="env-row">
        <el-input :model-value="row.name" placeholder="变量名" @update:model-value="setEnv(i, { name: $event })" />
        <el-input
          v-if="!row.secret"
          :model-value="row.value ?? ''"
          placeholder="值"
          @update:model-value="setEnv(i, { value: $event })"
        />
        <template v-else>
          <el-input :model-value="row.secretName" placeholder="Secret 名" @update:model-value="setEnv(i, { secretName: $event })" />
          <el-input :model-value="row.secretKey ?? ''" placeholder="键" @update:model-value="setEnv(i, { secretKey: $event })" />
        </template>
        <el-button link @click="setEnv(i, row.secret
          ? { secret: false, secretName: undefined, secretKey: undefined }
          : { secret: true })">
          {{ row.secret ? '改为普通值' : '引用 Secret' }}
        </el-button>
        <el-button link type="danger" @click="removeEnv(i)">删</el-button>
      </div>
      <el-button size="small" @click="addEnv">添加环境变量</el-button>

      <p class="inspector__hint">
        敏感值不要填在这里：先在「Secret」页建好，再用「引用 Secret」。
      </p>
    </el-form>
  </div>
</template>

<style scoped>
.inspector__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}
.inspector__title {
  font-weight: 600;
}
.inspector__sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.inspector__hint {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.env-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto auto;
  gap: 6px;
  margin-bottom: 6px;
  align-items: center;
}
.range {
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
