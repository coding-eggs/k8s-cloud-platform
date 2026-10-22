/**
 * 中间件商店：固定清单（Operator 中间件 + Nacos chart）。
 *
 * 批①-1 只有 Redis 已打通；其余条目的 modes 为空，卡片显示「即将支持」。
 * Operator 未安装时卡片禁用，管理员能看到「安装 Operator」。
 */

export interface MiddlewareField {
  key: string
  label: string
  type: 'number' | 'text'
  default?: string | number
  min?: number
  max?: number
  help?: string
  placeholder?: string
}

export const MIDDLEWARE_FIELDS: Record<string, MiddlewareField[]> = {
  redis: [
    {
      key: 'replicas', label: '节点数', type: 'number', default: 1, min: 1, max: 9,
      help: '单节点=1；哨兵=数据节点数（哨兵固定 3）；集群=主分片数（≥3）',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'text', default: '100m' },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'text', default: '500m' },
    { key: 'memoryRequest', label: '内存请求', type: 'text', default: '128Mi' },
    { key: 'memoryLimit', label: '内存上限', type: 'text', default: '512Mi' },
    {
      key: 'storageSize', label: '存储大小', type: 'text', default: '10Gi',
      placeholder: '留空 = 不持久化', help: '每个数据节点一块 PVC',
    },
    { key: 'storageClass', label: 'StorageClass', type: 'text', default: '', placeholder: '留空 = 集群默认' },
  ],
  mysql: [
    {
      key: 'replicas', label: '节点数', type: 'number', default: 1, min: 1, max: 9,
      help: '单节点固定 1；高可用集群 ≥3（Group Replication，单主强一致）',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'text', default: '500m' },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'text', default: '2' },
    { key: 'memoryRequest', label: '内存请求', type: 'text', default: '1Gi' },
    { key: 'memoryLimit', label: '内存上限', type: 'text', default: '4Gi' },
    { key: 'storageSize', label: '存储大小', type: 'text', default: '20Gi', help: '每个数据节点一块 PVC' },
    { key: 'storageClass', label: 'StorageClass', type: 'text', default: '', placeholder: '留空 = 集群默认' },
  ],
  kafka: [
    {
      key: 'replicas', label: 'Broker 数', type: 'number', default: 3, min: 3, max: 9,
      help: 'KRaft 要求 ≥3；节点同时承担 controller 与 broker',
    },
    { key: 'version', label: 'Kafka 版本', type: 'text', default: '4.4.0' },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'text', default: '500m' },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'text', default: '2' },
    { key: 'memoryRequest', label: '内存请求', type: 'text', default: '2Gi' },
    { key: 'memoryLimit', label: '内存上限', type: 'text', default: '4Gi' },
    { key: 'storageSize', label: '存储大小', type: 'text', default: '30Gi', help: '每个 broker 一块 PVC' },
    { key: 'storageClass', label: 'StorageClass', type: 'text', default: '', placeholder: '留空 = 集群默认' },
  ],
  elasticsearch: [
    {
      key: 'replicas', label: '节点数', type: 'number', default: 3, min: 1, max: 9,
      help: '生产建议 ≥3；单节点只适合测试',
    },
    { key: 'version', label: 'ES 版本', type: 'text', default: '9.5.0', help: '必须是当前 ECK 支持的版本' },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'text', default: '500m' },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'text', default: '2' },
    { key: 'memoryRequest', label: '内存请求', type: 'text', default: '2Gi' },
    { key: 'memoryLimit', label: '内存上限', type: 'text', default: '4Gi' },
    { key: 'storageSize', label: '存储大小', type: 'text', default: '30Gi' },
    { key: 'storageClass', label: 'StorageClass', type: 'text', default: '', placeholder: '留空 = 集群默认' },
  ],
  nacos: [
    {
      key: 'replicas', label: '节点数', type: 'number', default: 1, min: 1, max: 9,
      help: '集群模式 ≥3；使用内嵌存储（JRaft），不需要外置 MySQL',
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'text', default: '200m' },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'text', default: '1' },
    { key: 'memoryRequest', label: '内存请求', type: 'text', default: '512Mi' },
    { key: 'memoryLimit', label: '内存上限', type: 'text', default: '1Gi' },
    { key: 'storageSize', label: '存储大小', type: 'text', default: '5Gi', placeholder: '留空 = 不持久化' },
    { key: 'storageClass', label: 'StorageClass', type: 'text', default: '', placeholder: '留空 = 集群默认' },
  ],
}
