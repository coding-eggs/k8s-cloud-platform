-- ============================================================================================
-- 应用发布重做：服务配置与发布接口的权限行（2026-11-01）
-- ============================================================================================
-- 沿用 tenant:application:read / write 两个码。读：overview、release/preview|list|get、execution/active；
-- 写：service/create|update|remove、release/create、execution/resume。
-- release/preview 虽是 POST，只渲染不写集群，归读。
-- 角色绑定全部显式写出（不按 code 继承）：admin、tenant_admin 读写，tenant_member 只读。
--
-- 幂等：显式 id + ON DUPLICATE KEY UPDATE。⚠️ 本仓没有 Flyway，手工重放。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_overview',         'API', '/application/overview',          'POST','tenant:application:read',  '应用概览'),
 ('perm_app_release_preview',  'API', '/application/release/preview',   'POST','tenant:application:read',  '发布预览'),
 ('perm_app_release_list',     'API', '/application/release/list',      'POST','tenant:application:read',  '发布记录'),
 ('perm_app_release_get',      'API', '/application/release/get',       'POST','tenant:application:read',  '发布详情'),
 ('perm_app_execution_active', 'API', '/application/execution/active',  'POST','tenant:application:read',  '活动执行'),
 ('perm_app_service_create',   'API', '/application/service/create',    'POST','tenant:application:write', '添加服务'),
 ('perm_app_service_update',   'API', '/application/service/update',    'POST','tenant:application:write', '修改服务配置'),
 ('perm_app_service_remove',   'API', '/application/service/remove',    'POST','tenant:application:write', '移除服务'),
 ('perm_app_release_create',   'API', '/application/release/create',    'POST','tenant:application:write', '创建发布'),
 ('perm_app_execution_resume', 'API', '/application/execution/resume',  'POST','tenant:application:write', '继续执行')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_overview',                'builtin_role_admin',        'perm_app_overview'),
 ('rp_admin_app_release_preview',         'builtin_role_admin',        'perm_app_release_preview'),
 ('rp_admin_app_release_list',            'builtin_role_admin',        'perm_app_release_list'),
 ('rp_admin_app_release_get',             'builtin_role_admin',        'perm_app_release_get'),
 ('rp_admin_app_execution_active',        'builtin_role_admin',        'perm_app_execution_active'),
 ('rp_admin_app_service_create',          'builtin_role_admin',        'perm_app_service_create'),
 ('rp_admin_app_service_update',          'builtin_role_admin',        'perm_app_service_update'),
 ('rp_admin_app_service_remove',          'builtin_role_admin',        'perm_app_service_remove'),
 ('rp_admin_app_release_create',          'builtin_role_admin',        'perm_app_release_create'),
 ('rp_admin_app_execution_resume',        'builtin_role_admin',        'perm_app_execution_resume'),
 ('rp_tenant_admin_app_overview',         'builtin_role_tenant_admin', 'perm_app_overview'),
 ('rp_tenant_admin_app_release_preview',  'builtin_role_tenant_admin', 'perm_app_release_preview'),
 ('rp_tenant_admin_app_release_list',     'builtin_role_tenant_admin', 'perm_app_release_list'),
 ('rp_tenant_admin_app_release_get',      'builtin_role_tenant_admin', 'perm_app_release_get'),
 ('rp_tenant_admin_app_execution_active', 'builtin_role_tenant_admin', 'perm_app_execution_active'),
 ('rp_tenant_admin_app_service_create',   'builtin_role_tenant_admin', 'perm_app_service_create'),
 ('rp_tenant_admin_app_service_update',   'builtin_role_tenant_admin', 'perm_app_service_update'),
 ('rp_tenant_admin_app_service_remove',   'builtin_role_tenant_admin', 'perm_app_service_remove'),
 ('rp_tenant_admin_app_release_create',   'builtin_role_tenant_admin', 'perm_app_release_create'),
 ('rp_tenant_admin_app_execution_resume', 'builtin_role_tenant_admin', 'perm_app_execution_resume'),
 ('rp_tenant_member_app_overview',        'builtin_role_tenant_member','perm_app_overview'),
 ('rp_tenant_member_app_release_preview', 'builtin_role_tenant_member','perm_app_release_preview'),
 ('rp_tenant_member_app_release_list',    'builtin_role_tenant_member','perm_app_release_list'),
 ('rp_tenant_member_app_release_get',     'builtin_role_tenant_member','perm_app_release_get'),
 ('rp_tenant_member_app_execution_active','builtin_role_tenant_member','perm_app_execution_active')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
