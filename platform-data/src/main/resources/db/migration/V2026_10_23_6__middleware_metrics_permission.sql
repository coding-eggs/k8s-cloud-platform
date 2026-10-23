-- ============================================================================================
-- 中间件实例「监控曲线」端点权限
-- ============================================================================================
-- 详情页概览新增 CPU / 内存 / 网络 / 磁盘四张图（一次请求返回），读权限即可。
--
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）；重放后无需重下发模板（平台侧权限）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_instance_metrics', 'API','/middleware/instance/metrics','POST','tenant:middleware:read','实例监控曲线')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_instance_metrics',  'builtin_role_admin','perm_mw_instance_metrics'),
 ('rp_tadmin_mw_instance_metrics', 'builtin_role_tenant_admin','perm_mw_instance_metrics'),
 ('rp_tmember_mw_instance_metrics','builtin_role_tenant_member','perm_mw_instance_metrics')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
