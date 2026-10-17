-- ============================================================================================
-- S2.4：服务网格拓扑端点 POST /mesh/topology 的权限行
-- ============================================================================================
-- 为什么复用 `tenant:gateway:list`：与既有的 metrics 端点复用 `tenant:workload:get` 同一套路 ——
-- 端点和权限行按「一次有意义的操作」分，权限码按「资源 + 读动词」共享，不为每个只读查询再造码。
-- 能看 Gateway 的人就能看拓扑，这是合理的（拓扑画的就是这些 Gateway/服务之间的调用）。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
--    （反过来先起构建是安全的：多出来的权限行匹配不到端点只是幽灵行 WARN，不拒启。）
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- 幂等：权限行与角色关联都显式 id + ODKU。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

-- ⚠️ resource 列与 Spring MVC 的 pattern 一字不差（不带任何前缀），action 是 POST。
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mesh_topology', 'API','/mesh/topology','POST','tenant:gateway:list',
  '服务网格拓扑（只读：节点=工作负载，边=谁调谁；共享 gateway list 动词）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ⚠️ 新权限行必须**显式绑角色**（不按 code 继承）。
-- 三档齐全：只读动词与 Gateway 的三档一致（admin 代管态 / tenant-admin / tenant-member 的读动词）。
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mesh_topology',  'builtin_role_admin',        'perm_mesh_topology'),
 ('rp_tadmin_mesh_topology', 'builtin_role_tenant_admin', 'perm_mesh_topology'),
 ('rp_tmember_mesh_topology','builtin_role_tenant_member','perm_mesh_topology')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
