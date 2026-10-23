/**
 * 中间件商店：固定清单的创建表单字段。
 *
 * <p>资源一律「数字 + 单位」，不接受手写 quantity 字符串：
 * <ul>
 *   <li>{@code unit: 'cpu'} → CPU 核数（可小数，如 0.5；提交时直接写 "0.5"）；</li>
 *   <li>{@code unit: 'size'} → 数字 + Mi/Gi 下拉，提交时拼成 "512Mi" / "10Gi"。</li>
 * </ul>
 * 命名空间、StorageClass、版本由页面用后端下发的可选项渲染，不在这里声明。
 */

export interface MiddlewareField {
  key: string
  label: string
  type: 'number'
  unit: 'cpu' | 'size'
  /** size 类字段的默认单位 */
  unitDefault?: 'Mi' | 'Gi'
  step?: number
  default?: number
  min?: number
  max?: number
  help?: string
  placeholder?: string
}

export const MIDDLEWARE_FIELDS: Record<string, MiddlewareField[]> = {
  redis: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'cpu', default: 1, min: 1, max: 9,
      help: '单节点=1；哨兵=数据节点数（哨兵固定 3）；集群=主分片数（≥3）',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.1, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    {
      key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Mi',
      default: 128, min: 16,
    },
    {
      key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Mi',
      default: 512, min: 16,
    },
    {
      key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 10, min: 1, placeholder: '留空 = 不持久化', help: '每个数据节点一块 PVC',
    },
  ],
  mysql: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'cpu', default: 1, min: 1, max: 9,
      help: '单节点固定 1；高可用集群 ≥3（Group Replication，单主强一致）',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi', default: 1, min: 16 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 4, min: 16 },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 20, min: 1, help: '每个数据节点一块 PVC' },
  ],
  kafka: [
    {
      key: 'replicas', label: 'Broker 数', type: 'number', unit: 'cpu', default: 3, min: 3, max: 9,
      help: 'KRaft 要求 ≥3；节点同时承担 controller 与 broker',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi', default: 2, min: 16 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 4, min: 16 },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 30, min: 1, help: '每个 broker 一块 PVC' },
  ],
  elasticsearch: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'cpu', default: 3, min: 1, max: 9,
      help: '生产建议 ≥3；单节点只适合测试',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    {
      key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 2, min: 16,
    },
    {
      key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 4, min: 16, help: '低于 3Gi 时 ES 9.x 容易 OOM',
    },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 30, min: 1 },
  ],
  nacos: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'cpu', default: 1, min: 1, max: 9,
      help: '集群模式 ≥3；使用内嵌存储（JRaft），不需要外置 MySQL',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.2, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 1, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Mi', default: 512, min: 16 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 1, min: 16 },
    {
      key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 5, min: 1, placeholder: '留空 = 不持久化',
    },
  ],
  postgresql: [
    {
      key: 'replicas', label: '实例数', type: 'number', unit: 'cpu', default: 1, min: 1, max: 9,
      help: '单实例固定 1；主备 ≥3（CloudNativePG 自管一主多备与 failover）',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi', default: 1, min: 16 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 4, min: 16 },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 20, min: 1, help: '每个实例一块 PVC' },
  ],
  rabbitmq: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'cpu', default: 1, min: 1, max: 9,
      help: '单节点固定 1；集群 ≥3（Quorum 队列需要多数派）',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.3, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 1, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Mi', default: 512, min: 16 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 1, min: 16 },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 10, min: 1, help: '每个节点一块 PVC' },
  ],
}

/** 数字 + 单位 → K8s quantity 字符串；CPU 直接写核数（"0.5" 就是 500m） */
export function toQuantity(field: MiddlewareField, value: unknown, unit: string | undefined): string | undefined {
  if (value === null || value === undefined || value === '') return undefined
  const num = Number(value)
  if (!Number.isFinite(num) || num <= 0) return undefined
  return field.unit === 'cpu' ? String(num) : `${num}${unit ?? field.unitDefault ?? 'Gi'}`
}
