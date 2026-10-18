-- waypoint 链路追踪（OTel 批次二）
--
-- 新增两个端点：
--   POST /mesh/waypoints/tracing  设置开关（复用 tenant:gateway:update）
--   GET  /mesh/waypoints/tracing  查询状态（复用 tenant:gateway:get）
--
-- ⚠️ 新权限行不会按 code 自动继承角色：必须显式绑定（V2026_09_29_1 起的约定）。
--    漏绑 = 部署后 403（PermissionCrossCheckRunner 只检查行存在，不检查绑定）。
-- 幂等：显式 id + ODKU。

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mesh_wp_trace_set', 'API', '/mesh/waypoints/tracing', 'POST', 'tenant:gateway:update', '设置 waypoint 链路追踪'),
 ('perm_mesh_wp_trace_get', 'API', '/mesh/waypoints/tracing', 'GET',  'tenant:gateway:get',    '查询 waypoint 链路追踪')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- tenant-admin：两个都绑
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_tadmin_wptrace_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_mesh_wp_trace_set', 'perm_mesh_wp_trace_get')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-member：只读
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_tmember_wptrace_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.id = 'perm_mesh_wp_trace_get'
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- admin：两个都绑（代管态）
INSERT INTO platform_role_permission (id, role_id, permission_id)
SELECT CONCAT('rp_admin_wptrace_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_mesh_wp_trace_set', 'perm_mesh_wp_trace_get')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
