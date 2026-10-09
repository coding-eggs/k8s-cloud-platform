/**
 * 页面权限点 code 常量（与 seed `V2026_10_07_1__page_permissions.sql` 对齐）。
 *
 * <p><b>页面权限与接口权限是两件事</b>：
 * - 接口授权 = `platform_permission` 的 API 域行（resource=URL 模式、action=HTTP 方法），权威判定在后端
 *   `PermissionAuthorizationManager`。前端 `<code>perm.has('tenant:workload:list')</code>` 只能用于
 *   「这个按钮点下去会不会被拒」的判断。
 * - 页面可见性 = Page 域行（resource=前端路由 path、action=view），由**本文件**的 `pageCodes` 承载。
 *
 * <p>本文件是页面码的<b>唯一来源</b>：路由 `meta.requiresPerm`、菜单 `v-if`、以及任何页级门控都必须引用它，
 * 不得再写字面量，也不得回退去复用 API code —— 那会把「页面可见」与「接口可用」重新耦合起来
 * （收一个 API code 会连带页面消失）。规范全文见 docs/development/frontend-permission-conventions.md。
 *
 * <p>命名规则：平台页 `platform:page:*`、租户可达页 `tenant:page:*`。前缀必须与角色族一致——
 * 后端 `RoleService.assertScopeMatches` 只允许 TENANT 角色持 `tenant:` 码、PLATFORM 角色持 `platform:` 码
 * （内置 admin 豁免，可同时持两族）。
 *
 * <p><b>总览页（/overview）有意没有 page code</b>：它是路由守卫的回落目标（无权时跳这里），
 * 给它加码会形成重定向死循环。见 router/index.ts 的守卫与本文档同一处说明。
 */
export const pageCodes = {
  // ---- 平台管理 ----
  cluster: 'platform:page:cluster',
  node: {
    list: 'platform:page:node',
    detail: 'platform:page:node.detail',
  },
  tenant: {
    list: 'platform:page:tenant',
    /** 自管轨「我的租户」：租户成员无代管权时可达的租户详情 */
    self: 'tenant:page:tenant.self',
  },
  namespace: {
    list: 'platform:page:namespace',
    edit: 'platform:page:namespace.edit',
    detail: 'platform:page:namespace.detail',
  },
  template: 'platform:page:template',
  user: 'platform:page:user',
  role: 'platform:page:role',
  permission: 'platform:page:permission',

  // ---- 资源管理（租户可达；storageClass 因无命名空间维度归平台管理员）----
  workload: {
    list: 'tenant:page:workload.list',
    detail: 'tenant:page:workload.detail',
    edit: 'tenant:page:workload.edit',
  },
  pod: {
    list: 'tenant:page:pod.list',
    detail: 'tenant:page:pod.detail',
    container: 'tenant:page:pod.container',
  },
  configmap: { list: 'tenant:page:configmap.list', edit: 'tenant:page:configmap.edit' },
  secret: { list: 'tenant:page:secret.list', edit: 'tenant:page:secret.edit' },
  service: { list: 'tenant:page:service.list', edit: 'tenant:page:service.edit' },
  pvc: { list: 'tenant:page:pvc.list' },
  persistentVolume: { list: 'tenant:page:persistentvolume.list' },
  storageClass: { list: 'platform:page:storageclass.list' },
  serviceMonitor: { list: 'tenant:page:servicemonitor.list', edit: 'tenant:page:servicemonitor.edit' },
  podMonitor: { list: 'tenant:page:podmonitor.list', edit: 'tenant:page:podmonitor.edit' },
  hpa: { list: 'tenant:page:hpa.list', edit: 'tenant:page:hpa.edit' },

  // ---- 集群运维 ----
  ops: {
    ippool: {
      list: 'platform:page:ops.ippool',
      detail: 'platform:page:ops.ippool.detail',
      edit: 'platform:page:ops.ippool.edit',
    },
    ipReservation: {
      list: 'platform:page:ops.ipreservation',
      edit: 'platform:page:ops.ipreservation.edit',
    },
    bgpConfiguration: {
      list: 'platform:page:ops.bgpconfiguration',
      detail: 'platform:page:ops.bgpconfiguration.detail',
      edit: 'platform:page:ops.bgpconfiguration.edit',
    },
    bgpPeer: {
      list: 'platform:page:ops.bgppeer',
      detail: 'platform:page:ops.bgppeer.detail',
      edit: 'platform:page:ops.bgppeer.edit',
    },
    bgpFilter: {
      list: 'platform:page:ops.bgpfilter',
      detail: 'platform:page:ops.bgpfilter.detail',
      edit: 'platform:page:ops.bgpfilter.edit',
    },
  },

  // ---- 服务网格（B6）。跨两上下文：GatewayClass 平台管理面，Gateway/HTTPRoute 租户资源面 ----
  mesh: {
    /** 平台管理面：集群级 GatewayClass（无命名空间维度，同 storageClass 先例） */
    gatewayClass: {
      list: 'platform:page:mesh.gatewayclass',
      edit: 'platform:page:mesh.gatewayclass.edit',
    },
    /** 租户资源面：命名空间来自分配上下文，同 serviceMonitor */
    gateway: { list: 'tenant:page:gateway.list', edit: 'tenant:page:gateway.edit' },
    httpRoute: { list: 'tenant:page:httproute.list', edit: 'tenant:page:httproute.edit' },
    grpcRoute: { list: 'tenant:page:grpcroute.list', edit: 'tenant:page:grpcroute.edit' },
    tcpRoute: { list: 'tenant:page:tcproute.list', edit: 'tenant:page:tcproute.edit' },
    tlsRoute: { list: 'tenant:page:tlsroute.list', edit: 'tenant:page:tlsroute.edit' },
    udpRoute: { list: 'tenant:page:udproute.list', edit: 'tenant:page:udproute.edit' },
  },
} as const
