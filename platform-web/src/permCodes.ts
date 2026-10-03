/**
 * 资源域权限点 code 常量（与 seed V2026_09_29_1__resource_fine_grained.sql 对齐）。
 * 权威门控在后端表驱动授权；此文件只保证路由 meta.requiresPerm 与菜单 v-if 拼写一致。
 */
export const resCodes = {
  workload: { list: 'tenant:workload:list', get: 'tenant:workload:get', create: 'tenant:workload:create', update: 'tenant:workload:update' },
  pod: { list: 'tenant:pod:list', get: 'tenant:pod:get', logs: 'tenant:pod:logs' },
  configmap: { list: 'tenant:configmap:list', create: 'tenant:configmap:create', update: 'tenant:configmap:update' },
  secret: { list: 'tenant:secret:list', create: 'tenant:secret:create', update: 'tenant:secret:update' },
  service: { list: 'tenant:service:list', create: 'tenant:service:create', update: 'tenant:service:update' },
  pvc: { list: 'tenant:pvc:list' },
  servicemonitor: { list: 'tenant:servicemonitor:list', create: 'tenant:servicemonitor:create', update: 'tenant:servicemonitor:update' },
  podmonitor: { list: 'tenant:podmonitor:list', create: 'tenant:podmonitor:create', update: 'tenant:podmonitor:update' },
  hpa: { list: 'tenant:hpa:list', create: 'tenant:hpa:create', update: 'tenant:hpa:update' },
} as const
