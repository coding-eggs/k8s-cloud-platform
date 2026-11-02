-- ============================================================================================
-- 应用发布重做：回滚接口的权限行（2026-11-01）
-- ============================================================================================
-- POST /application/release/rollback 复用 tenant:application:write（回滚会写 desired 并触发发布）。
-- 角色绑定显式写出（不按 code 继承）：admin、tenant_admin 读写；tenant_member 只读，不绑本行。
--
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE。⚠️ 本仓没有 Flyway，手工重放；先重放再起新构建。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_release_rollback', 'API', '/application/release/rollback', 'POST','tenant:application:write', '回滚发布')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_release_rollback',        'builtin_role_admin',        'perm_app_release_rollback'),
 ('rp_tenant_admin_app_release_rollback', 'builtin_role_tenant_admin', 'perm_app_release_rollback')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
