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

  // ---- 租户族 ----
  /** 自管轨租户成员管理 */
  memberManage: 'tenant:member:manage',
  configmapList: 'tenant:configmap:list',
  secretList: 'tenant:secret:list',
  pvcList: 'tenant:pvc:list',
  /** 集群 API 能力快照只读（V2026_10_07_2 起租户可读；refresh 仍独占 platform:cluster:manage） */
  clusterCapabilityView: 'tenant:cluster:capability:view',
} as const
