/**
 * 客户端「能力位」用到的 **API 域** 权限码常量（与 seed 的 API 域行对齐）。
 *
 * <p><b>与 pageCodes.ts 的分工</b>：
 * - `pageCodes.ts` = 页面可见性（Page 域）。决定路由能不能进、菜单露不露。
 * - 本文件 = 接口能力（API 域）。决定"这个按钮/这次拉取会不会被后端拒"，
 *   用于①按钮置灰、②可选拉取短路、③能力门禁（如集群能力快照）。
 *
 * <p>权威判定永远在后端 `PermissionAuthorizationManager` + 权限表；这里只是让前端<b>不要发出注定被拒的请求</b>，
 * 并且让"为什么这个功能灰着"在代码里有据可查。规范见 docs/development/frontend-permission-conventions.md。
 *
 * <p>只收录<b>前端真的用来做判断</b>的码。接口自身的鉴权不需要在这里登记——
 * 那由权限表负责，前端不复制一份必然会漂移的清单。
 */
export const apiCodes = {
  // ---- 平台族 ----
  /** 集群管理面（/cluster/list、/cluster/capability/refresh、/nodes/**、存储类等） */
  clusterManage: 'platform:cluster:manage',
  /** 命名空间分配读（/namespace/list|get|yaml|quota/get、/cluster/options） */
  allocationList: 'platform:allocation:list',
  /** 命名空间分配写（创建/编辑/删除、配额与限制范围 upsert） */
  allocationManage: 'platform:allocation:manage',
  tenantRead: 'platform:tenant:read',
  userManage: 'platform:user:manage',
  roleRead: 'platform:role:read',
  roleManage: 'platform:role:manage',
  templateManage: 'platform:template:manage',
  bgpConfigManage: 'platform:bgp:config:manage',
  /** 代管轨租户成员管理（admin 代某租户加/移成员） */
  platformMemberManage: 'platform:member:manage',
  /** 跨全部命名空间列出 ConfigMap / Pod（平台侧全局视图，V2026_10_08_2）。
   *  与 list 的 tenant:xxx:list 是**两套独立授权** —— 全局视图会回带所有命名空间的对象
   *  （含不属于任何租户的命名空间），是显式的数据面扩张，可单独授予/收回。 */
  configmapListAll: 'platform:configmap:list-all',
  podListAll: 'platform:pod:list-all',
  /** 凭据写（新建/更新/删除）—— 页面内的写按钮置灰用 */
  credentialWrite: 'platform:credential:write',
  /** 镜像候选值与目录浏览（S3.1）—— 补全的可选拉取短路用 */
  registryCatalog: 'tenant:registry:catalog',

  // ---- 租户族 ----
  /** 自管轨租户成员管理 */
  memberManage: 'tenant:member:manage',
  configmapList: 'tenant:configmap:list',
  secretList: 'tenant:secret:list',
  pvcList: 'tenant:pvc:list',
  /** 改容量（扩容）—— 与创建/删除分开，扩容是唯一能改 spec 的动作 */
  pvcUpdate: 'tenant:pvc:update',
  /** 集群 API 能力快照只读（V2026_10_07_2 起租户可读；refresh 仍独占 platform:cluster:manage） */
  clusterCapabilityView: 'tenant:cluster:capability:view',
  /** 工作负载写（编辑/伸缩/暂停/ambient 开关共享该动词码）—— ambient 开关与 pause 同挂此码 */
  workloadUpdate: 'tenant:workload:update',
  /** Gateway 列表读（工作负载的 L7 开关要列本命名空间的 waypoint 候选） */
  gatewayList: 'tenant:gateway:list',

  // ---- CI（流水线）----
  /** 定义与运行历史（只读）—— 列表/详情/节点元数据/预览/运行历史共用一个码 */
  pipelineRead: 'tenant:pipeline:read',
  /** 定义（新建/改/删）—— 按钮置灰用。**它是本平台权限面最大的一个码**：
   *  能改定义 = 能往构建 Pod 里塞任意命令（spec §10.1） */
  pipelineManage: 'tenant:pipeline:manage',
  /** 触发与取消。member 也有 —— run 只能跑别人定义好的流水线 */
  pipelineRun: 'tenant:pipeline:run',
  /** 看运行日志。单独成码：日志是容器输出，可能含敏感内容 */
  pipelineLogs: 'tenant:pipeline:logs',

  // ---- 路由策略（Route 五合一）：逐 kind 的拉取短路 / 创建与保存的按钮置灰 ----
  // 页面可见性用 pageCodes.mesh.<x>Route.{list,edit}（本批不加页面码）；这里只放"发不发请求 / 按不按得动"的判断。
  httpRouteList: 'tenant:httproute:list',
  httpRouteCreate: 'tenant:httproute:create',
  httpRouteUpdate: 'tenant:httproute:update',
  grpcRouteList: 'tenant:grpcroute:list',
  grpcRouteCreate: 'tenant:grpcroute:create',
  grpcRouteUpdate: 'tenant:grpcroute:update',
  tcpRouteList: 'tenant:tcproute:list',
  tcpRouteCreate: 'tenant:tcproute:create',
  tcpRouteUpdate: 'tenant:tcproute:update',
  tlsRouteList: 'tenant:tlsroute:list',
  tlsRouteCreate: 'tenant:tlsroute:create',
  tlsRouteUpdate: 'tenant:tlsroute:update',
  udpRouteList: 'tenant:udproute:list',
  udpRouteCreate: 'tenant:udproute:create',
  udpRouteUpdate: 'tenant:udproute:update',

  // ---- 告警（S2.2）----
  /** 列表接口（租户侧收窄到本租户命名空间）。**页面可见性用 pageCodes.alert**，这个只用于接口门控 */
  alertListTenant: 'tenant:alert:list',
  /** 平台侧告警（看全部命名空间）+ 删除告警记录。租户角色不持有 → 删除按钮对租户隐藏 */
  alertRead: 'platform:alert:read',
  /** 链路自检（会往真实命名空间写两条测试告警）。仅平台管理员 */
  alertTest: 'platform:alert:test',
} as const
