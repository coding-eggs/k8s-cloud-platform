-- ============================================================================================
-- 中间件用户管理的六个端点 · 权限行
-- ============================================================================================
-- 表驱动授权：这几个 @PostMapping 若没有对应的 API 权限行，PermissionCrossCheckRunner
-- 会在启动期直接 fail-closed（2026-10-26 实报）。
--
-- 其中 /middleware/instance/password 是 Milvus 批次（2026-10-25）就漏掉的行 —— 那版一直没人
-- 重启过 platform-api，所以直到本批才在启动校验里一起暴露。这里一并补上。
--
-- 口径：看用户列表 / 角色选项是读（tenant:middleware:read，成员可用）；
--       增 / 改口令 / 删 / 改内置账号口令是写（tenant:middleware:write）。
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_users_list',       'API','/middleware/instance/users',         'POST','tenant:middleware:read', '实例用户列表'),
 ('perm_mw_users_roles',      'API','/middleware/instance/users/roles',   'POST','tenant:middleware:read', '可分配角色选项'),
 ('perm_mw_user_create',      'API','/middleware/instance/users/create',  'POST','tenant:middleware:write','新增实例用户'),
 ('perm_mw_user_password',    'API','/middleware/instance/users/password','POST','tenant:middleware:write','修改实例用户口令'),
 ('perm_mw_user_delete',      'API','/middleware/instance/users/delete',  'POST','tenant:middleware:write','删除实例用户'),
 ('perm_mw_instance_password','API','/middleware/instance/password',      'POST','tenant:middleware:write','修改实例内置账号口令（Milvus）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- 平台管理员 + 租户管理员：六个端点全给（代租户操作）
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_users_list',        'builtin_role_admin','perm_mw_users_list'),
 ('rp_admin_mw_users_roles',       'builtin_role_admin','perm_mw_users_roles'),
 ('rp_admin_mw_user_create',       'builtin_role_admin','perm_mw_user_create'),
 ('rp_admin_mw_user_password',     'builtin_role_admin','perm_mw_user_password'),
 ('rp_admin_mw_user_delete',       'builtin_role_admin','perm_mw_user_delete'),
 ('rp_admin_mw_instance_password', 'builtin_role_admin','perm_mw_instance_password'),
 ('rp_tadmin_mw_users_list',        'builtin_role_tenant_admin','perm_mw_users_list'),
 ('rp_tadmin_mw_users_roles',       'builtin_role_tenant_admin','perm_mw_users_roles'),
 ('rp_tadmin_mw_user_create',       'builtin_role_tenant_admin','perm_mw_user_create'),
 ('rp_tadmin_mw_user_password',     'builtin_role_tenant_admin','perm_mw_user_password'),
 ('rp_tadmin_mw_user_delete',       'builtin_role_tenant_admin','perm_mw_user_delete'),
 ('rp_tadmin_mw_instance_password', 'builtin_role_tenant_admin','perm_mw_instance_password')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- 租户成员：只读（与实例详情 / 操作记录一致，清单可见但不能增删改）
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_tmember_mw_users_list',  'builtin_role_tenant_member','perm_mw_users_list'),
 ('rp_tmember_mw_users_roles', 'builtin_role_tenant_member','perm_mw_users_roles')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
