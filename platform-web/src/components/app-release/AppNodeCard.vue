<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { Cpu, Monitor } from '@element-plus/icons-vue'

/**
 * 拓扑图上的节点卡片（只读）。
 *
 * <p>形态由数据决定：服务 / 后台进程用图标，中间件 / 外部连接用两字母品类码；外部连接虚线边框，表示不在这个集群里。
 * 锚点只是连线的落点（拓扑由变量引用生成，不能拖线），所以隐藏不显示：左侧出（使用方指向被使用的一方），右侧入。
 */
const props = defineProps<{
  data: { key: string; kind: string; params: Record<string, unknown>; state?: string }
  selected?: boolean
}>()

const CODES: Record<string, { name: string; badge: string }> = {
  mysql: { name: 'MySQL', badge: 'MY' },
  postgresql: { name: 'PostgreSQL', badge: 'PG' },
  redis: { name: 'Redis', badge: 'RD' },
  kafka: { name: 'Kafka', badge: 'KF' },
  rabbitmq: { name: 'RabbitMQ', badge: 'MQ' },
  elasticsearch: { name: 'Elasticsearch', badge: 'ES' },
  nacos: { name: 'Nacos', badge: 'NC' },
  milvus: { name: 'Milvus', badge: 'MV' },
}

const OWNERSHIP: Record<string, string> = { CREATED: '应用创建', BOUND: '绑定已有' }

const kind = computed(() => String(props.data.kind))
const params = computed(() => props.data.params ?? {})
const isService = computed(() => kind.value === 'SERVICE')
const isCompute = computed(() => isService.value || kind.value === 'WORKER')
const state = computed(() => String(props.data.state ?? 'pending').toLowerCase())
const kindClass = computed(() => `app-node--${kind.value.toLowerCase()}`)

const code = computed(() => String(params.value.code ?? ''))
const codeMeta = computed(() => CODES[code.value] ?? {
  name: code.value ? code.value.toUpperCase() : (kind.value === 'EXTERNAL' ? '外部连接' : '中间件'),
  badge: code.value ? code.value.slice(0, 2).toUpperCase() : (kind.value === 'EXTERNAL' ? 'EX' : 'MW'),
})

const badgeText = computed(() => (isCompute.value ? '' : codeMeta.value.badge))
const icon = computed(() => (isService.value ? Monitor : Cpu))

const meta = computed(() => {
  const p = params.value
  if (isCompute.value) return String(p.image || '未填写镜像')
  const name = codeMeta.value.name
  if (kind.value === 'MIDDLEWARE') {
    const own = OWNERSHIP[String(p.ownership ?? '')]
    return own ? `${name} · ${own}` : name
  }
  const port = p.port ? `:${p.port}` : ''
  return `${name} · ${p.host || '未填写地址'}${port}`
})

const subtitle = computed(() => {
  if (!isCompute.value) return ''
  const r = params.value.replicas
  const replicas = r == null ? '' : `${r} 副本`
  return isService.value && params.value.port ? [params.value.port, replicas].filter(Boolean).join(' · ') : replicas
})
</script>

<template>
  <div
    class="app-node"
    :class="[kindClass, { 'app-node--selected': selected, 'app-node--failed': state === 'failed' }]"
  >
    <Handle v-if="isCompute" id="l" type="source" :position="Position.Left" :connectable="false" class="port" />
    <Handle id="r" type="target" :position="Position.Right" :connectable="false" class="port" />

    <div class="app-node__badge">
      <el-icon v-if="isCompute" :size="17"><component :is="icon" /></el-icon>
      <span v-else class="app-node__badge-text">{{ badgeText }}</span>
    </div>

    <div class="app-node__body">
      <div class="app-node__row">
        <span class="app-node__key" :title="data.key">{{ data.key }}</span>
        <span class="app-node__state" :class="`app-node__state--${state}`" :title="state" />
        <span class="app-node__tail">{{ subtitle }}</span>
      </div>
      <div class="app-node__meta" :title="meta">{{ meta }}</div>
    </div>
  </div>
</template>

<style scoped>
.app-node {
  --kind: #3b7dd8;
  --kind-soft: rgb(59 125 216 / 22%);

  display: flex;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
  width: 280px;
  height: 72px;
  padding: 0 12px 0 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  background: var(--el-bg-color);
  box-shadow: 0 1px 3px rgb(0 0 0 / 7%);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.app-node--service {
  --kind: #3b7dd8;
  --kind-soft: rgb(59 125 216 / 22%);
}
.app-node--worker {
  --kind: #7a5af5;
  --kind-soft: rgb(122 90 245 / 22%);
}
.app-node--middleware {
  --kind: #23925f;
  --kind-soft: rgb(35 146 95 / 22%);
}
.app-node--external {
  --kind: #d9822b;
  --kind-soft: rgb(217 130 43 / 22%);
  border-style: dashed;
}
.app-node--selected {
  border-color: var(--kind);
  box-shadow: 0 0 0 2px var(--kind-soft);
}
.app-node--failed {
  border-color: var(--el-color-danger);
}

.app-node__badge {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  color: #fff;
  background: var(--kind);
}
.app-node__badge-text {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.app-node__body {
  flex: 1;
  min-width: 0;
}
.app-node__row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.app-node__key {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.app-node__tail {
  margin-left: auto;
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.app-node__state {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-info-light-3);
}
.app-node__state--ready {
  background: var(--el-color-success);
}
.app-node__state--running {
  background: var(--el-color-primary);
}
.app-node__state--failed {
  background: var(--el-color-danger);
}
.app-node__state--missing {
  background: var(--el-color-warning);
}
.app-node__meta {
  overflow: hidden;
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-family: var(--el-font-family-mono, monospace);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 锚点只作连线落点，不可交互，也不显示 */
.app-node :deep(.vue-flow__handle) {
  width: 1px;
  height: 1px;
  min-width: 0;
  min-height: 0;
  border: none;
  background: transparent;
  opacity: 0;
  pointer-events: none;
}
.app-node {
  cursor: pointer;
}
.app-node:hover {
  border-color: var(--kind);
}
</style>
