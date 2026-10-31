-- ============================================================================================
-- 权限行：POST /mesh/parent-gateways（路由父引用候选，**租户可调**的窄投影）
-- ============================================================================================
-- 背景（spec 2026-10-31-platform-tenant-boundary-tightening-design.md §5.3）：
--   租户要把 HTTPRoute 挂到平台代管的对外网关（istio-system/external-gateway），
--   而按新边界「平台 ns 不分配给租户」，租户对那个 ns 没有任何 K8s 权限 —— 父资源下拉恒空，
--   用户只能手打名字，打错还静默不挂载。新增窄投影端点解决这个问题。
--
-- 权限码为什么是 tenant:gateway:list（复用，不造新码）：
--   · 它是"列出可选 Gateway"，与 /gateways/list 是同一件事的两个入口；
--   · 多端点共用一个码是既有约定（V2026_10_08_4 已把 /gateways/list 挂在这个码上）；
--   · 用 platform:cluster:manage 会让这个端点对租户完全不可达 —— 那正是它要解决的问题。
--
-- ⚠️ 新权限行必须**显式绑角色**（不按 code 继承）：这里绑给"已经持有 /gateways/list 那一行"的角色。
--    不绑 = 部署后编辑器里选不了父网关，而列表页一切正常 —— 最难发现的那种半残。
-- ⚠️ 先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 重放后需重新登录：token 的权限闭包在签发时计算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- 幂等：显式 id + ODKU。
-- ============================================================================================

INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_mesh_parent_gw', 'API','/mesh/parent-gateways','POST','tenant:gateway:list',
  '路由父引用候选（自己命名空间 + 平台网关白名单，窄投影）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- 角色关联：按"已持有 /gateways/list 那行"的集合来绑（缺省会漏掉自定义角色）。
-- id 用 MD5：role_id + permission_id 相加可能超 varchar(64)（同 V2026_10_08_4 的写法）。
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_mesh_pgw_', SUBSTRING(MD5(CONCAT(rp.role_id, '|', p.id)), 1, 32)), rp.role_id, p.id
FROM platform_permission p
JOIN platform_permission base ON base.id = 'perm_mesh_gw_list'
JOIN platform_role_permission rp ON rp.permission_id = base.id
WHERE p.id = 'perm_mesh_parent_gw'
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
