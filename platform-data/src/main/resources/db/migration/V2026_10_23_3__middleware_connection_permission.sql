-- ============================================================================================
-- 中间件实例「连接方式」端点权限
-- ============================================================================================
-- 背景：实例创建成功后要给用户弹连接方式（集群内地址 + 凭据在哪个 Secret），
--       实例列表里也能随时再打开。新端点 /middleware/instance/connection。
--
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_instance_connection', 'API','/middleware/instance/connection','POST','tenant:middleware:read','实例连接方式（集群内地址/凭据位置）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_instance_connection',  'builtin_role_admin','perm_mw_instance_connection'),
 ('rp_tadmin_mw_instance_connection', 'builtin_role_tenant_admin','perm_mw_instance_connection'),
 ('rp_tmember_mw_instance_connection','builtin_role_tenant_member','perm_mw_instance_connection')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
