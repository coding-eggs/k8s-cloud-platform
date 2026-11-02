-- ============================================================================================
-- 应用发布重做：依赖接口的权限行（2026-11-02）
-- ============================================================================================
-- dependency/add|remove|init 都会写数据（建实例、改引用、跑初始化 Job），复用 tenant:application:write。
-- 角色绑定显式写出（不按 code 继承）：admin、tenant_admin 读写；tenant_member 只读，不绑本批任何行。
--
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE。⚠️ 本仓没有 Flyway，手工重放；先重放再起新构建。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_dependency_add',    'API', '/application/dependency/add',    'POST','tenant:application:write', '添加依赖'),
 ('perm_app_dependency_remove', 'API', '/application/dependency/remove', 'POST','tenant:application:write', '移除依赖'),
 ('perm_app_dependency_init',   'API', '/application/dependency/init',   'POST','tenant:application:write', '初始化依赖')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_dependency_add',           'builtin_role_admin',        'perm_app_dependency_add'),
 ('rp_admin_app_dependency_remove',        'builtin_role_admin',        'perm_app_dependency_remove'),
 ('rp_admin_app_dependency_init',          'builtin_role_admin',        'perm_app_dependency_init'),
 ('rp_tenant_admin_app_dependency_add',    'builtin_role_tenant_admin', 'perm_app_dependency_add'),
 ('rp_tenant_admin_app_dependency_remove', 'builtin_role_tenant_admin', 'perm_app_dependency_remove'),
 ('rp_tenant_admin_app_dependency_init',   'builtin_role_tenant_admin', 'perm_app_dependency_init')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
