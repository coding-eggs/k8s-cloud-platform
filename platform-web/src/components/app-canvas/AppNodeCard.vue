<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { Cpu, Monitor } from '@element-plus/icons-vue'

/**
 * 画布上的节点卡片。
 *
 * <p>两种「形态」由数据决定，不是装饰：
 * <ul>
 *   <li>只有服务和后台进程有左侧 source 端口（连线的起点）。布局把被依赖的资源放在左边，
 *       所以「出」在左、「入」在右；从中间件往外拖线物理上拖不出来，
 *       比事后弹一句「只有服务和后台进程可以发起连线」更早地表达了规则；</li>
 *   <li>外部服务的边框是虚线，表示它不在这个集群里。</li>
 * </ul>
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

const MODES: Record<string, string> = { CREATE: '平台创建', BIND: '绑定已有', EXTERNAL: '链接外部' }

const kind = computed(() => String(props.data.kind))
const params = computed(() => props.data.params ?? {})
const isService = computed(() => kind.value === 'SERVICE')
const isCompute = computed(() => isService.value || kind.value === 'WORKER')
const state = computed(() => String(props.data.state ?? 'PENDING').toLowerCase())
const kindClass = computed(() => `app-node--${kind.value.toLowerCase()}`)

const code = computed(() => String(params.value.code ?? ''))
const codeMeta = computed(() => CODES[code.value] ?? { name: code.value.toUpperCase(), badge: code.value.slice(0, 2).toUpperCase() })

/** 徽标：计算节点用图标，中间件/外部服务用两字母品类码 */
const badgeText = computed(() => (isCompute.value ? '' : codeMeta.value.badge))
const icon = computed(() => (isService.value ? Monitor : Cpu))

const replicas = computed(() => Number(params.value.replicas ?? 1))

const meta = computed(() => {
  const p = params.value
  if (isCompute.value) return String(p.image || '未填写镜像')
  const name = codeMeta.value.name
  if (kind.value === 'MIDDLEWARE') {
    return `${name} · ${MODES[String(p.provisionMode ?? 'CREATE')] ?? '平台创建'}`
  }
  const port = p.port ? `:${p.port}` : ''
  return `${name} · ${p.host || '未填写地址'}${port}`
})

const subtitle = computed(() => {
  if (!isCompute.value) return ''
  return isService.value && params.value.port
    ? `${params.value.port} · ${replicas.value} 副本`
    : `${replicas.value} 副本`
})
</script>

<template>
  <div
    class="app-node"
    :class="[kindClass, { 'app-node--selected': selected, 'app-node--failed': state === 'failed' }]"
  >
    <!--
      锚点：左右各一个 source、一个 target，上下错开不重叠。
      任意一侧都能起、都能落 —— 线就画在你选的两个锚点之间，方向由布局和你摆的位置决定，
      不再被「左出右入」锁死。中间件/外部服务没有 source，仍然拖不出线。
    -->
    <template v-if="isCompute">
      <Handle id="l" type="source" :position="Position.Left" class="port port--out" />
      <Handle id="r" type="source" :position="Position.Right" class="port port--out" />
    </template>
    <Handle id="r" type="target" :position="Position.Right" class="port port--in" />
    <Handle id="l" type="target" :position="Position.Left" class="port port--in" />

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

/* 端口：从节点根元素派生尺寸/颜色；默认半透明，指到节点上才亮起来 */
.app-node :deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid var(--kind);
  border-radius: 50%;
  background: var(--el-bg-color);
  opacity: 0.5;
  transition: opacity 0.15s, transform 0.15s;
}
/* 同一侧有出/入两个锚点时上下错开，不叠在一起 */
.app-node :deep(.vue-flow__handle.port--out) {
  top: 32%;
}
.app-node :deep(.vue-flow__handle.port--in) {
  top: 70%;
}
/* 中间件/外部只有入口，居中放更好看 */
.app-node--middleware :deep(.vue-flow__handle.port--in),
.app-node--external :deep(.vue-flow__handle.port--in) {
  top: 50%;
}
.app-node:hover :deep(.vue-flow__handle),
.app-node--selected :deep(.vue-flow__handle) {
  opacity: 1;
}
.app-node :deep(.vue-flow__handle:hover) {
  transform: scale(1.3);
}
</style>
