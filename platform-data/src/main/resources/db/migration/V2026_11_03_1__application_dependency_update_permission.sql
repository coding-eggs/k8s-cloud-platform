-- ============================================================================================
-- 应用依赖：修改外部连接的权限行（2026-11-03）
-- ============================================================================================
-- /application/dependency/update 只改外部连接的地址 / 端口 / Secret 登记，复用 tenant:application:write。
-- 角色绑定显式写出（不按 code 继承）：admin、tenant_admin；tenant_member 只读，不绑。
--
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE。⚠️ 本仓没有 Flyway，手工重放；先重放再起新构建。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_dependency_update', 'API', '/application/dependency/update', 'POST', 'tenant:application:write', '修改外部连接')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_dependency_update',        'builtin_role_admin',        'perm_app_dependency_update'),
 ('rp_tenant_admin_app_dependency_update', 'builtin_role_tenant_admin', 'perm_app_dependency_update')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
