-- ============================================================================================
-- 中间件实例「详情 / 操作记录 / 改显示名」端点权限
-- ============================================================================================
-- 详情页需要拉实例的实时对象（CR/YAML/Pod/PVC/Service）与操作记录；改名是写操作。
--
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_instance_detail',    'API','/middleware/instance/detail',    'POST','tenant:middleware:read', '实例详情（含实时对象）'),
 ('perm_mw_instance_operations','API','/middleware/instance/operations','POST','tenant:middleware:read', '实例操作记录'),
 ('perm_mw_instance_rename',    'API','/middleware/instance/rename',    'POST','tenant:middleware:write','修改实例显示名')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_instance_detail',     'builtin_role_admin','perm_mw_instance_detail'),
 ('rp_admin_mw_instance_operations', 'builtin_role_admin','perm_mw_instance_operations'),
 ('rp_admin_mw_instance_rename',     'builtin_role_admin','perm_mw_instance_rename'),
 ('rp_tadmin_mw_instance_detail',    'builtin_role_tenant_admin','perm_mw_instance_detail'),
 ('rp_tadmin_mw_instance_operations','builtin_role_tenant_admin','perm_mw_instance_operations'),
 ('rp_tadmin_mw_instance_rename',    'builtin_role_tenant_admin','perm_mw_instance_rename'),
 ('rp_tmember_mw_instance_detail',   'builtin_role_tenant_member','perm_mw_instance_detail'),
 ('rp_tmember_mw_instance_operations','builtin_role_tenant_member','perm_mw_instance_operations')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
