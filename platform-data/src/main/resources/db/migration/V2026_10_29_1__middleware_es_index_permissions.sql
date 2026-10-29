-- ============================================================================================
-- 中间件实例「索引」查询端点 · 权限行（Elasticsearch）
-- ============================================================================================
-- 实例详情页新增「索引」页签：索引列表 + 单个索引的 mapping / settings / aliases。
-- 两个端点都是**只读**，因此都挂 tenant:middleware:read（与实例详情 / 监控曲线一致）。
--
-- ⚠️ 表驱动授权：没有对应权限行时 PermissionCrossCheckRunner 会在启动期 fail-closed，
--    platform-api 直接起不来 —— 所以本文件必须与代码同批重放。
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）；三条内置角色都要绑。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_instance_indexes', 'API','/middleware/instance/indexes','POST','tenant:middleware:read','实例索引列表（ES）'),
 ('perm_mw_instance_index',   'API','/middleware/instance/index',  'POST','tenant:middleware:read','索引结构 mapping/settings/aliases（ES）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_instance_indexes',   'builtin_role_admin','perm_mw_instance_indexes'),
 ('rp_admin_mw_instance_index',     'builtin_role_admin','perm_mw_instance_index'),
 ('rp_tadmin_mw_instance_indexes',  'builtin_role_tenant_admin','perm_mw_instance_indexes'),
 ('rp_tadmin_mw_instance_index',    'builtin_role_tenant_admin','perm_mw_instance_index'),
 ('rp_tmember_mw_instance_indexes', 'builtin_role_tenant_member','perm_mw_instance_indexes'),
 ('rp_tmember_mw_instance_index',   'builtin_role_tenant_member','perm_mw_instance_index')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
