<script setup lang="ts">
import { computed } from 'vue'
import type { AppNode, MiddlewareCatalogItem, MiddlewareInstance } from '@/types'

interface EnvRow {
  name: string
  value?: string
  /** true = 引用已有 Secret（此时只剩 secretName/secretKey） */
  secret?: boolean
  secretName?: string
  secretKey?: string
}

const props = defineProps<{
  node: AppNode
  catalog?: MiddlewareCatalogItem[]
  instances?: MiddlewareInstance[]
  namespace?: string
}>()
const emit = defineEmits<{ update: [params: Record<string, unknown>]; remove: [] }>()

const isService = computed(() => props.node.kind === 'SERVICE')
const isWorker = computed(() => props.node.kind === 'WORKER')
const isMiddleware = computed(() => props.node.kind === 'MIDDLEWARE')
const isExternal = computed(() => props.node.kind === 'EXTERNAL')
const env = computed<EnvRow[]>(() => (props.node.params.env as EnvRow[] | undefined) ?? [])

const provisionMode = computed(() => String(props.node.params.provisionMode ?? 'CREATE'))
const catalogCodes = computed(() => props.catalog ?? [])
const selectedCode = computed(() => String(props.node.params.code ?? ''))
const modes = computed(() =>
  catalogCodes.value.find((c) => c.code === selectedCode.value)?.modes ?? [])

/** 只有这几种中间件支持初始化脚本（与后端 AppInitSupport 同一份清单）。 */
const initSupported = computed(() =>
  ['mysql', 'postgresql', 'redis', 'elasticsearch', 'kafka'].includes(selectedCode.value))

const sameNamespaceInstances = computed(() =>
  (props.instances ?? []).filter((i) => !props.namespace || i.namespace === props.namespace))

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
        <div class="inspector__sub">
          {{ isService ? '服务' : isWorker ? '后台进程' : isMiddleware ? '中间件' : '外部服务' }}
        </div>
      </div>
      <el-button link type="danger" @click="emit('remove')">移除节点</el-button>
    </div>

    <el-form label-width="86px" label-position="left" size="small">
      <!-- ================= 服务 / 后台进程 ================= -->
      <template v-if="isService || isWorker">
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

        <template v-if="isService">
          <el-form-item label="健康探针">
            <el-switch
              :model-value="node.params.probeEnabled !== false"
              @update:model-value="set('probeEnabled', $event)"
            />
          </el-form-item>
          <el-form-item v-if="node.params.probeEnabled !== false" label="探针路径">
            <el-input
              :model-value="String(node.params.probePath ?? '/')"
              @update:model-value="set('probePath', $event)"
            />
          </el-form-item>
          <el-form-item label="持久化">
            <el-switch
              :model-value="toggles('persistenceEnabled')"
              @update:model-value="set('persistenceEnabled', $event)"
            />
          </el-form-item>
          <template v-if="toggles('persistenceEnabled')">
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
          <el-form-item label="监控采集">
            <el-switch
              :model-value="toggles('metricsEnabled')"
              @update:model-value="set('metricsEnabled', $event)"
            />
          </el-form-item>
        </template>
      </template>

      <!-- ================= 中间件 ================= -->
      <template v-else-if="isMiddleware">
        <el-form-item label="中间件类型" required>
          <el-select
            :model-value="selectedCode"
            style="width: 100%"
            @update:model-value="set('code', $event)"
          >
            <el-option v-for="c in catalogCodes" :key="c.code" :label="c.name" :value="c.code" />
          </el-select>
        </el-form-item>

        <el-form-item label="来源" required>
          <el-select
            :model-value="provisionMode"
            style="width: 100%"
            @update:model-value="set('provisionMode', $event)"
          >
            <el-option label="平台创建" value="CREATE" />
            <el-option label="绑定已有实例" value="BIND" />
            <el-option label="链接外部" value="EXTERNAL" />
          </el-select>
        </el-form-item>

        <template v-if="provisionMode === 'BIND'">
          <el-form-item label="选择实例" required>
            <el-select
              :model-value="String(node.params.instanceId ?? '')"
              placeholder="同命名空间的实例"
              style="width: 100%"
              @update:model-value="set('instanceId', $event)"
            >
              <el-option
                v-for="i in sameNamespaceInstances"
                :key="i.id"
                :label="`${i.displayName || i.name}（${i.status}）`"
                :value="i.id"
              />
            </el-select>
          </el-form-item>
        </template>

        <template v-else-if="provisionMode === 'EXTERNAL'">
          <el-form-item label="地址" required>
            <el-input
              :model-value="String(node.params.host ?? '')"
              placeholder="mysql.corp.internal"
              @update:model-value="set('host', $event)"
            />
          </el-form-item>
          <el-form-item label="端口">
            <el-input-number
              :model-value="Number(node.params.port ?? 3306)"
              :min="1" :max="65535" controls-position="right"
              @update:model-value="set('port', $event)"
            />
          </el-form-item>
          <el-form-item label="Secret 名">
            <el-input
              :model-value="String(node.params.secretName ?? '')"
              placeholder="存放账号口令的 Secret"
              @update:model-value="set('secretName', $event)"
            />
          </el-form-item>
          <el-form-item label="用户名键">
            <el-input
              :model-value="String(node.params.usernameKey ?? '')"
              placeholder="Secret 里用户名的键（可空）"
              @update:model-value="set('usernameKey', $event)"
            />
          </el-form-item>
          <el-form-item label="口令键">
            <el-input
              :model-value="String(node.params.passwordKey ?? '')"
              placeholder="Secret 里口令的键"
              @update:model-value="set('passwordKey', $event)"
            />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="形态">
            <el-select
              :model-value="String(node.params.shape ?? '')"
              placeholder="默认按平台推荐"
              style="width: 100%"
              @update:model-value="set('shape', $event)"
            >
              <el-option
                v-for="m in modes"
                :key="m.mode"
                :label="m.label || m.mode"
                :value="m.mode"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="监控采集">
            <el-switch
              :model-value="toggles('metricsEnabled')"
              @update:model-value="set('metricsEnabled', $event)"
            />
          </el-form-item>
          <template v-if="initSupported">
            <el-form-item label="初始化脚本">
              <el-input
                :model-value="String(node.params.initPayload ?? '')"
                type="textarea"
                :rows="4"
                :placeholder="selectedCode === 'kafka' ? '每行 topic:分区:副本' : selectedCode === 'elasticsearch' ? 'JSON 请求体' : 'SQL / 命令，部署时以一次性 Job 执行'"
                @update:model-value="set('initPayload', $event)"
              />
            </el-form-item>
          </template>
        </template>
      </template>

      <!-- ================= 外部服务 ================= -->
      <template v-else-if="isExternal">
        <el-form-item label="协议">
          <el-select
            :model-value="String(node.params.code ?? 'mysql')"
            style="width: 100%"
            @update:model-value="set('code', $event)"
          >
            <el-option label="MySQL" value="mysql" />
            <el-option label="PostgreSQL" value="postgresql" />
            <el-option label="Redis" value="redis" />
            <el-option label="Kafka" value="kafka" />
            <el-option label="RabbitMQ" value="rabbitmq" />
            <el-option label="Elasticsearch" value="elasticsearch" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input
            :model-value="String(node.params.host ?? '')"
            placeholder="db.corp.internal"
            @update:model-value="set('host', $event)"
          />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number
            :model-value="Number(node.params.port ?? 3306)"
            :min="1" :max="65535" controls-position="right"
            @update:model-value="set('port', $event)"
          />
        </el-form-item>
        <el-form-item label="Secret 名">
          <el-input
            :model-value="String(node.params.secretName ?? '')"
            @update:model-value="set('secretName', $event)"
          />
        </el-form-item>
        <el-form-item label="用户名键">
          <el-input
            :model-value="String(node.params.usernameKey ?? '')"
            @update:model-value="set('usernameKey', $event)"
          />
        </el-form-item>
        <el-form-item label="口令键">
          <el-input
            :model-value="String(node.params.passwordKey ?? '')"
            @update:model-value="set('passwordKey', $event)"
          />
        </el-form-item>
      </template>

      <!-- ================= 环境变量（仅计算节点） ================= -->
      <template v-if="isService || isWorker">
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
          依赖的中间件会自动注入 <code>&lt;节点&gt;_&lt;协议&gt;_HOST</code> 这类变量。
        </p>
      </template>
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
