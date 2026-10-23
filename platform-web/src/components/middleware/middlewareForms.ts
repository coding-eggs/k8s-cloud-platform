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
  /** count = 纯数量（节点数），不渲染单位 */
  unit: 'cpu' | 'size' | 'count'
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
      key: 'replicas', label: '节点数', type: 'number', unit: 'count', default: 1, min: 1, max: 9,
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.1, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    {
      key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Mi',
      default: 128,
    },
    {
      key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Mi',
      default: 512,
    },
    {
      key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 10, min: 1, placeholder: '留空 = 不持久化', help: '每个数据节点一块 PVC',
    },
  ],
  mysql: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'count', default: 1, min: 1, max: 9,
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi', default: 1 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 4 },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 20, help: '每个数据节点一块 PVC' },
  ],
  kafka: [
    {
      key: 'replicas', label: 'Broker 数', type: 'number', unit: 'count', default: 3, min: 3, max: 9,
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi', default: 2 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 4 },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 30, help: '每个 broker 一块 PVC' },
  ],
  elasticsearch: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'count', default: 3, min: 1, max: 9,
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    {
      key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 2,
    },
    {
      key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 4, help: '低于 3Gi 时 ES 9.x 容易 OOM',
    },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 30 },
  ],
  nacos: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'count', default: 1, min: 1, max: 9,
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.2, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 1, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Mi', default: 512 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 1 },
    {
      key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 5, min: 1, placeholder: '留空 = 不持久化',
    },
  ],
  postgresql: [
    {
      key: 'replicas', label: '实例数', type: 'number', unit: 'count', default: 1, min: 1, max: 9,
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi', default: 1 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 4 },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 20, help: '每个实例一块 PVC' },
  ],
  rabbitmq: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'count', default: 1, min: 1, max: 9,
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.3, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 1, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Mi', default: 512 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 1 },
    { key: 'storageSize', label: '存储大小', type: 'number', unit: 'size', unitDefault: 'Gi', default: 10, help: '每个节点一块 PVC' },
  ],
}

/** 数字 + 单位 → K8s quantity 字符串；CPU 直接写核数（"0.5" 就是 500m） */
export function toQuantity(field: MiddlewareField, value: unknown, unit: string | undefined): string | undefined {
  if (value === null || value === undefined || value === '') return undefined
  const num = Number(value)
  if (!Number.isFinite(num) || num <= 0) return undefined
  if (field.unit === 'count') return String(num)
  return field.unit === 'cpu' ? String(num) : `${num}${unit ?? field.unitDefault ?? 'Gi'}`
}
