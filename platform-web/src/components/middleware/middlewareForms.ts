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
}
