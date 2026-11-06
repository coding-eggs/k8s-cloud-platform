-- ============================================================================================
-- 应用下线：接口权限行（2026-11-06）
-- ============================================================================================
-- POST /application/release/offline 复用 tenant:application:write（下线会删除集群对象并记一条发布）。
-- 角色绑定显式写出（不按 code 继承）：admin、tenant_admin；tenant_member 只读，不绑本行。
-- service 列取默认 platform-api（V2026_11_04_1 加的列，默认值即 api）。
--
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE。⚠️ 本仓没有 Flyway，手工重放；先重放再起新构建。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_release_offline', 'API', '/application/release/offline', 'POST','tenant:application:write', '应用下线')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_release_offline',        'builtin_role_admin',        'perm_app_release_offline'),
 ('rp_tenant_admin_app_release_offline', 'builtin_role_tenant_admin', 'perm_app_release_offline')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
